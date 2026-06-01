const crypto = require('crypto');
const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;

const CORS = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type',
};

function hashPassword(password, salt) {
  return crypto.pbkdf2Sync(password, salt, 100000, 64, 'sha512').toString('hex');
}

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };

  let body;
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Invalid JSON' }; }

  const { email, currentPassword, newPassword } = body;
  if (!email || !newPassword) return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Données manquantes' }) };
  if (newPassword.length < 8) return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Le mot de passe doit faire au moins 8 caractères' }) };

  const normalEmail = email.toLowerCase().trim();

  const res = await fetch(
    `${SB_URL}/rest/v1/subscribers?email=eq.${encodeURIComponent(normalEmail)}&select=password_hash,password_salt,status&limit=1`,
    { headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` } }
  );
  const subs = await res.json();

  if (!Array.isArray(subs) || subs.length === 0)
    return { statusCode: 404, headers: CORS, body: JSON.stringify({ error: 'Compte introuvable' }) };

  const sub = subs[0];

  // Vérifier le mot de passe actuel si un hash existe
  if (sub.password_hash && sub.password_salt) {
    if (!currentPassword)
      return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Mot de passe actuel requis' }) };
    const currentHash = hashPassword(currentPassword, sub.password_salt);
    if (currentHash !== sub.password_hash)
      return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Mot de passe actuel incorrect' }) };
  }

  const newSalt = crypto.randomBytes(16).toString('hex');
  const newHash = hashPassword(newPassword, newSalt);

  const patch = await fetch(
    `${SB_URL}/rest/v1/subscribers?email=eq.${encodeURIComponent(normalEmail)}`,
    {
      method: 'PATCH',
      headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}`, 'Content-Type': 'application/json', Prefer: 'return=minimal' },
      body: JSON.stringify({ password_hash: newHash, password_salt: newSalt }),
    }
  );
  if (!patch.ok) return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: 'Erreur lors de la mise à jour' }) };

  return { statusCode: 200, headers: CORS, body: JSON.stringify({ success: true }) };
};
