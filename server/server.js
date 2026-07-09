require('dotenv').config({ quiet: true });
const express = require('express');
const cors = require('cors');
const rateLimit = require('express-rate-limit');
const { RtcTokenBuilder, RtcRole } = require('agora-token');
const db = require('./db');
const { requireVerifiedPhone } = require('./firebaseAuth');

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
if (!process.env.FIREBASE_PROJECT_ID) {
  console.error('Falta FIREBASE_PROJECT_ID en .env');
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

// Frena creación masiva de familias desde una misma IP.
const createFamilyLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  limit: 10,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'Demasiados intentos, probá de nuevo en un rato' }
});

// Frena fuerza bruta de códigos de invitación (6 caracteres, ~1.4 mil millones de combinaciones,
// pero sin límite alguien podría automatizar la búsqueda).
const joinFamilyLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  limit: 15,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'Demasiados intentos, probá de nuevo en un rato' }
});

// Más permisivo: la app reintenta sola cada 5s si se corta la conexión.
const tokenLimiter = rateLimit({
  windowMs: 60 * 1000,
  limit: 30,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'Demasiados intentos, probá de nuevo en un rato' }
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

app.post('/families', createFamilyLimiter, requireVerifiedPhone, async (req, res) => {
  const { name, role, displayName } = req.body || {};
  const deviceId = req.verifiedPhone;
  if (!role || !VALID_ROLES.includes(role)) {
    return res.status(400).json({ error: 'role inválido, debe ser ABUELA o CUIDADOR' });
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

app.post('/families/:inviteCode/join', joinFamilyLimiter, requireVerifiedPhone, async (req, res) => {
  const { role, displayName } = req.body || {};
  const deviceId = req.verifiedPhone;
  if (!role || !VALID_ROLES.includes(role)) {
    return res.status(400).json({ error: 'role inválido, debe ser ABUELA o CUIDADOR' });
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

app.post('/users/profile', createFamilyLimiter, requireVerifiedPhone, async (req, res) => {
  const { nombres, apellidos, fechaNacimiento, email } = req.body || {};
  if (!nombres || !apellidos || !fechaNacimiento || !email) {
    return res.status(400).json({ error: 'Faltan datos del perfil' });
  }
  try {
    await db.upsertUserProfile({
      phoneNumber: req.verifiedPhone,
      nombres,
      apellidos,
      fechaNacimiento,
      email
    });
    res.status(201).json({ ok: true });
  } catch (e) {
    console.error(e);
    res.status(500).json({ error: 'No se pudo guardar el perfil' });
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

// El canal y el UID NUNCA se toman de lo que manda el celular: se derivan acá
// a partir de una membresía real (familyId + deviceId) ya registrada en la
// base de datos. Así nadie puede pedir un token para el canal de otra familia
// solo adivinando el nombre (los canales son "family_1", "family_2", ...).
app.get('/token', tokenLimiter, requireVerifiedPhone, async (req, res) => {
  const familyId = Number(req.query.familyId);
  const deviceId = req.verifiedPhone;

  if (!familyId) {
    return res.status(400).json({ error: 'Falta el parametro familyId' });
  }

  try {
    const family = await db.getFamilyById(familyId);
    if (!family) {
      return res.status(404).json({ error: 'Familia no encontrada' });
    }
    const member = await db.findExistingMember(familyId, deviceId);
    if (!member) {
      return res.status(403).json({ error: 'Este dispositivo no es miembro de esta familia' });
    }

    const channelName = db.channelNameForFamily(familyId);
    const uid = member.id;
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
  } catch (e) {
    console.error(e);
    res.status(500).json({ error: 'No se pudo generar el token' });
  }
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

