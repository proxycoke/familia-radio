require('dotenv').config({ quiet: true });
const express = require('express');
const cors = require('cors');
const { RtcTokenBuilder, RtcRole } = require('agora-token');
const db = require('./db');

const appId = process.env.AGORA_APP_ID;
const appCertificate = process.env.AGORA_APP_CERTIFICATE;
const port = process.env.PORT || 3000;

if (!appId || !appCertificate) {
  console.error('Falta AGORA_APP_ID o AGORA_APP_CERTIFICATE en .env');
  process.exit(1);
}
if (!process.env.DATABASE_URL) {
  console.error('Falta DATABASE_URL en .env');
  process.exit(1);
}

const VALID_ROLES = ['ABUELA', 'CUIDADOR'];

const app = express();
app.use(cors());
app.use(express.json());
app.use((req, _res, next) => {
  console.log(`${new Date().toISOString()} ${req.method} ${req.url} from ${req.ip}`);
  next();
});

function memberResponse(family, member) {
  return {
    familyId: family.id,
    inviteCode: family.invite_code,
    channelName: db.channelNameForFamily(family.id),
    agoraUid: member.id,
    role: member.role
  };
}

app.post('/families', async (req, res) => {
  const { name, role, deviceId, displayName } = req.body || {};
  if (!role || !VALID_ROLES.includes(role)) {
    return res.status(400).json({ error: 'role inválido, debe ser ABUELA o CUIDADOR' });
  }
  if (!deviceId) {
    return res.status(400).json({ error: 'Falta deviceId' });
  }
  try {
    const family = await db.createFamily({ name });
    const member = await db.insertMember({ familyId: family.id, role, deviceId, displayName });
    res.status(201).json(memberResponse(family, member));
  } catch (e) {
    console.error(e);
    res.status(500).json({ error: 'No se pudo crear la familia' });
  }
});

app.post('/families/:inviteCode/join', async (req, res) => {
  const { role, deviceId, displayName } = req.body || {};
  if (!role || !VALID_ROLES.includes(role)) {
    return res.status(400).json({ error: 'role inválido, debe ser ABUELA o CUIDADOR' });
  }
  if (!deviceId) {
    return res.status(400).json({ error: 'Falta deviceId' });
  }
  try {
    const family = await db.getFamilyByInviteCode(req.params.inviteCode);
    if (!family) {
      return res.status(404).json({ error: 'Código de familia no encontrado' });
    }

    const existing = await db.findExistingMember(family.id, deviceId);
    if (existing) {
      return res.json(memberResponse(family, existing));
    }

    const counts = await db.getMemberCounts(family.id);
    const limit = role === 'ABUELA' ? family.max_familiares : family.max_cuidadores;
    if (counts[role] >= limit) {
      return res.status(409).json({
        error: role === 'ABUELA'
          ? 'Esta familia ya alcanzó el máximo de familiares mayores'
          : 'Esta familia ya alcanzó el máximo de cuidadores'
      });
    }

    const member = await db.insertMember({ familyId: family.id, role, deviceId, displayName });
    res.status(201).json(memberResponse(family, member));
  } catch (e) {
    console.error(e);
    res.status(500).json({ error: 'No se pudo unir a la familia' });
  }
});

app.get('/families/:inviteCode', async (req, res) => {
  try {
    const family = await db.getFamilyByInviteCode(req.params.inviteCode);
    if (!family) {
      return res.status(404).json({ error: 'Código de familia no encontrado' });
    }
    const [members, counts] = await Promise.all([
      db.getMembers(family.id),
      db.getMemberCounts(family.id)
    ]);
    res.json({
      familyId: family.id,
      inviteCode: family.invite_code,
      name: family.name,
      channelName: db.channelNameForFamily(family.id),
      limits: { maxFamiliares: family.max_familiares, maxCuidadores: family.max_cuidadores },
      counts,
      members: members.map(m => ({
        role: m.role,
        displayName: m.display_name,
        joinedAt: m.joined_at
      }))
    });
  } catch (e) {
    console.error(e);
    res.status(500).json({ error: 'No se pudo obtener la familia' });
  }
});

const TOKEN_TTL_SECONDS = 24 * 60 * 60;

app.get('/token', (req, res) => {
  const channelName = req.query.channel;
  const uid = Number(req.query.uid || 0);

  if (!channelName) {
    return res.status(400).json({ error: 'Falta el parametro channel' });
  }

  const now = Math.floor(Date.now() / 1000);
  const expireAt = now + TOKEN_TTL_SECONDS;

  const token = RtcTokenBuilder.buildTokenWithUid(
    appId,
    appCertificate,
    channelName,
    uid,
    RtcRole.PUBLISHER,
    expireAt,
    expireAt
  );

  res.json({ appId, channelName, uid, token, expireAt });
});

app.get('/health', (_req, res) => res.json({ ok: true }));

db.initSchema()
  .then(() => {
    app.listen(port, '0.0.0.0', () => {
      console.log(`Servidor de tokens escuchando en http://0.0.0.0:${port}`);
    });
  })
  .catch((e) => {
    console.error('No se pudo inicializar la base de datos:', e.message);
    process.exit(1);
  });
