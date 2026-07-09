const { initializeApp } = require('firebase-admin/app');
const { getAuth } = require('firebase-admin/auth');

// Solo necesitamos verificar tokens (no crear usuarios ni nada admin), así que
// alcanza con inicializar con el projectId — no hace falta una service account
// key, lo que evita tener que guardar ese secreto en Render.
const app = initializeApp({ projectId: process.env.FIREBASE_PROJECT_ID });
const auth = getAuth(app);

// Exige un ID token válido de Firebase (número de teléfono verificado por SMS)
// en el header Authorization. La identidad real del usuario pasa a ser
// decodedToken.phone_number, nunca algo que mande el cliente sin verificar.
async function requireVerifiedPhone(req, res, next) {
  const authHeader = req.headers.authorization || '';
  const idToken = authHeader.startsWith('Bearer ') ? authHeader.slice(7) : null;
  if (!idToken) {
    return res.status(401).json({ error: 'Falta el token de autenticación' });
  }
  try {
    const decoded = await auth.verifyIdToken(idToken);
    if (!decoded.phone_number) {
      return res.status(401).json({ error: 'El token no tiene un número de teléfono verificado' });
    }
    req.verifiedPhone = decoded.phone_number;
    next();
  } catch (e) {
    console.error('Fallo al verificar el ID token:', e.message);
    res.status(401).json({ error: 'Token de autenticación inválido o expirado' });
  }
}

module.exports = { requireVerifiedPhone };
