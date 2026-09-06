const crypto = require('crypto');
const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;

const CORS = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type'
};

// Même mécanisme de vérification que admin-stats.js (login abonné) :
// mot de passe requis et vérifié dès qu'un hash existe pour ce compte.
function hashPassword(password, salt) {
  return crypto.pbkdf2Sync(password, salt, 100000, 64, 'sha512').toString('hex');
}

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };

  let body;
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Invalid JSON' }; }

  const { email, pseudo, password } = body;
  if (!email || !email.includes('@'))
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Email invalide' }) };

  // Check active or pending subscription (pending = paiement confirmé, webhook pas encore traité)
  const subRes = await fetch(
    `${SB_URL}/rest/v1/subscribers?email=eq.${encodeURIComponent(email.toLowerCase().trim())}&status=in.(active,pending)&select=*&limit=1`,
    { headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` } }
  );
  const subs = await subRes.json();

  if (!Array.isArray(subs) || subs.length === 0)
    return { statusCode: 403, headers: CORS, body: JSON.stringify({ error: 'Aucun abonnement actif pour cet email' }) };

  const sub = subs[0];

  // Vérifier le mot de passe si un hash est défini pour ce compte (comme admin-stats.js)
  if (sub.password_hash && sub.password_salt) {
    if (!password)
      return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Mot de passe requis' }) };
    if (hashPassword(password, sub.password_salt) !== sub.password_hash)
      return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Mot de passe incorrect' }) };
  }

  const now = new Date();
  if (sub.expires_at && new Date(sub.expires_at) <= now)
    return { statusCode: 403, headers: CORS, body: JSON.stringify({ error: 'Abonnement expiré — renouvelez depuis l\'espace abonné' }) };

  // Create a free session for this subscriber
  const token = crypto.randomBytes(32).toString('hex');
  const ends_at = new Date(now.getTime() + 30 * 60 * 1000).toISOString();
  const stripe_payment_id = `sub_${token.substring(0, 20)}`;
  const name = pseudo?.trim() || sub.pseudo || email.split('@')[0];

  const insertRes = await fetch(`${SB_URL}/rest/v1/sessions`, {
    method: 'POST',
    headers: {
      apikey: SB_KEY,
      Authorization: `Bearer ${SB_KEY}`,
      'Content-Type': 'application/json',
      Prefer: 'return=minimal'
    },
    body: JSON.stringify({
      stripe_payment_id,
      client_pseudo: name,
      formule: 'Pass mensuel',
      montant: 0,
      statut: 'paid',
      token,
      started_at: now.toISOString(),
      ends_at
    })
  });

  if (!insertRes.ok) {
    const err = await insertRes.text();
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: 'Erreur création session: ' + err }) };
  }

  return {
    statusCode: 200,
    headers: CORS,
    body: JSON.stringify({
      token,
      stripe_payment_id,
      pseudo: name,
      expires_at: sub.expires_at
    })
  };
};
