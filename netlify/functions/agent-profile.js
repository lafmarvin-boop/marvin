const crypto = require('crypto');
const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;
const ADMIN_EMAIL = (process.env.ADMIN_EMAIL || '').toLowerCase();
const ADMIN_PWD = process.env.ADMIN_PASSWORD;

const CORS = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type'
};

const H = () => ({ apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` });

async function verifyAgent(email, password) {
  const emailLower = email.toLowerCase().trim();
  if (ADMIN_EMAIL && emailLower === ADMIN_EMAIL && ADMIN_PWD && password === ADMIN_PWD) return true;
  const res = await fetch(
    `${SB_URL}/rest/v1/agent_passwords?email=eq.${encodeURIComponent(emailLower)}&select=password_hash,password_salt&limit=1`,
    { headers: H() }
  );
  const rows = await res.json().catch(() => []);
  if (!Array.isArray(rows) || !rows.length) return false;
  const hash = crypto.pbkdf2Sync(password, rows[0].password_salt, 100000, 64, 'sha512').toString('hex');
  return hash === rows[0].password_hash;
}

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };

  let body;
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Invalid JSON' }; }

  if (!SB_URL || !SB_KEY) return { statusCode: 503, headers: CORS, body: JSON.stringify({ error: 'Service non configuré' }) };

  const { action, email, password, notify_email, notify_requests } = body;
  if (!email || !password) return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Identifiants requis' }) };

  const ok = await verifyAgent(email, password).catch(() => false);
  if (!ok) return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Non autorisé' }) };

  const emailLower = email.toLowerCase().trim();

  if (action === 'get') {
    const res = await fetch(
      `${SB_URL}/rest/v1/agent_profiles?agent_email=eq.${encodeURIComponent(emailLower)}&select=notify_email,notify_requests&limit=1`,
      { headers: H() }
    );
    const rows = await res.json().catch(() => []);
    const profile = Array.isArray(rows) && rows.length ? rows[0] : { notify_email: '', notify_requests: false };
    return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, profile }) };
  }

  if (action === 'save') {
    await fetch(`${SB_URL}/rest/v1/agent_profiles`, {
      method: 'POST',
      headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'resolution=merge-duplicates' },
      body: JSON.stringify({
        agent_email: emailLower,
        notify_email: notify_email || null,
        notify_requests: !!notify_requests,
        updated_at: new Date().toISOString()
      })
    });
    return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true }) };
  }

  return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Action inconnue' }) };
};
