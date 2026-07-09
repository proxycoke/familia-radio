require('dotenv').config({ quiet: true });
const express = require('express');
const cors = require('cors');
const { RtcTokenBuilder, RtcRole } = require('agora-token');

const appId = process.env.AGORA_APP_ID;
const appCertificate = process.env.AGORA_APP_CERTIFICATE;
const port = process.env.PORT || 3000;

if (!appId || !appCertificate) {
  console.error('Falta AGORA_APP_ID o AGORA_APP_CERTIFICATE en .env');
  process.exit(1);
}

const app = express();
app.use(cors());
app.use((req, _res, next) => {
  console.log(`${new Date().toISOString()} ${req.method} ${req.url} from ${req.ip}`);
  next();
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

app.listen(port, '0.0.0.0', () => {
  console.log(`Servidor de tokens escuchando en http://0.0.0.0:${port}`);
});
