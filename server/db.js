const { Pool } = require('pg');

const pool = new Pool({
  connectionString: process.env.DATABASE_URL,
  ssl: { rejectUnauthorized: false }
});

async function initSchema() {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS families (
      id SERIAL PRIMARY KEY,
      invite_code TEXT UNIQUE NOT NULL,
      name TEXT,
      max_familiares INT NOT NULL DEFAULT 2,
      max_cuidadores INT NOT NULL DEFAULT 4,
      created_at TIMESTAMPTZ NOT NULL DEFAULT now()
    );
  `);
  await pool.query(`
    CREATE TABLE IF NOT EXISTS members (
      id SERIAL PRIMARY KEY,
      family_id INT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
      role TEXT NOT NULL CHECK (role IN ('ABUELA', 'CUIDADOR')),
      device_id TEXT NOT NULL,
      display_name TEXT,
      joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
      UNIQUE(family_id, device_id)
    );
  `);
  await pool.query(`
    CREATE TABLE IF NOT EXISTS users (
      phone_number TEXT PRIMARY KEY,
      nombres TEXT NOT NULL,
      apellidos TEXT NOT NULL,
      fecha_nacimiento DATE NOT NULL,
      email TEXT NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT now()
    );
  `);
}

function channelNameForFamily(familyId) {
  return `family_${familyId}`;
}

const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // sin 0/O/1/I para evitar confusiones

function generateInviteCode(length = 6) {
  let code = '';
  for (let i = 0; i < length; i++) {
    code += CODE_ALPHABET[Math.floor(Math.random() * CODE_ALPHABET.length)];
  }
  return code;
}

async function createFamily({ name }) {
  for (let attempt = 0; attempt < 5; attempt++) {
    const inviteCode = generateInviteCode();
    try {
      const result = await pool.query(
        `INSERT INTO families (invite_code, name) VALUES ($1, $2) RETURNING id, invite_code`,
        [inviteCode, name || null]
      );
      return result.rows[0];
    } catch (e) {
      if (e.code === '23505') continue; // colisión de invite_code, reintentar
      throw e;
    }
  }
  throw new Error('No se pudo generar un código de invitación único');
}

async function getFamilyByInviteCode(inviteCode) {
  const result = await pool.query(
    `SELECT * FROM families WHERE invite_code = $1`,
    [inviteCode.toUpperCase()]
  );
  return result.rows[0] || null;
}

async function getFamilyById(familyId) {
  const result = await pool.query(`SELECT * FROM families WHERE id = $1`, [familyId]);
  return result.rows[0] || null;
}

async function getMembers(familyId) {
  const result = await pool.query(
    `SELECT id, role, device_id, display_name, joined_at FROM members WHERE family_id = $1 ORDER BY joined_at ASC`,
    [familyId]
  );
  return result.rows;
}

async function getMemberCounts(familyId) {
  const result = await pool.query(
    `SELECT role, COUNT(*)::int AS count FROM members WHERE family_id = $1 GROUP BY role`,
    [familyId]
  );
  const counts = { ABUELA: 0, CUIDADOR: 0 };
  for (const row of result.rows) counts[row.role] = row.count;
  return counts;
}

async function findExistingMember(familyId, deviceId) {
  const result = await pool.query(
    `SELECT * FROM members WHERE family_id = $1 AND device_id = $2`,
    [familyId, deviceId]
  );
  return result.rows[0] || null;
}

async function insertMember({ familyId, role, deviceId, displayName }) {
  const result = await pool.query(
    `INSERT INTO members (family_id, role, device_id, display_name) VALUES ($1, $2, $3, $4) RETURNING *`,
    [familyId, role, deviceId, displayName || null]
  );
  return result.rows[0];
}

async function upsertUserProfile({ phoneNumber, nombres, apellidos, fechaNacimiento, email }) {
  const result = await pool.query(
    `INSERT INTO users (phone_number, nombres, apellidos, fecha_nacimiento, email)
     VALUES ($1, $2, $3, $4, $5)
     ON CONFLICT (phone_number) DO UPDATE
       SET nombres = EXCLUDED.nombres, apellidos = EXCLUDED.apellidos,
           fecha_nacimiento = EXCLUDED.fecha_nacimiento, email = EXCLUDED.email
     RETURNING *`,
    [phoneNumber, nombres, apellidos, fechaNacimiento, email]
  );
  return result.rows[0];
}

module.exports = {
  pool,
  initSchema,
  channelNameForFamily,
  createFamily,
  getFamilyByInviteCode,
  getFamilyById,
  getMembers,
  getMemberCounts,
  findExistingMember,
  insertMember,
  upsertUserProfile
};
