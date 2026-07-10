const { initializeApp, cert } = require('firebase-admin/app');
const { getAuth } = require('firebase-admin/auth');

// Credenciales reales (no solo projectId): las necesitamos para poder
// cambiar la contraseña de un usuario desde el servidor después de
// verificar un código enviado por correo (Firebase no tiene "código por
// email" nativo, así que ese flujo lo manejamos nosotros).
const serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT_JSON);
const app = initializeApp({ credential: cert(serviceAccount) });
const auth = getAuth(app);

module.exports = { auth };
