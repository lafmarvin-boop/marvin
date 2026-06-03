const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;
const ADMIN_PWD = process.env.ADMIN_PASSWORD;
const ADMIN_EMAIL = (process.env.ADMIN_EMAIL || '').toLowerCase();

const CORS = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type',
};

const H = () => ({ apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}`, 'Content-Type': 'application/json' });

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };

  let body;
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Bad Request' }; }

  const { adminEmail, adminPassword, agentEmail } = body;

  if (!adminEmail || !adminPassword || !agentEmail)
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Paramètres manquants' }) };

  if (adminEmail.toLowerCase() !== ADMIN_EMAIL || adminPassword !== ADMIN_PWD)
    return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Non autorisé' }) };

  if (!SB_URL || !SB_KEY)
    return { statusCode: 503, headers: CORS, body: JSON.stringify({ error: 'Service non configuré' }) };

  try {
    // Invalider le token + mettre offline + effacer la session en cours
    await fetch(
      `${SB_URL}/rest/v1/agent_presence?agent_email=eq.${encodeURIComponent(agentEmail)}`,
      {
        method: 'PATCH',
        headers: { ...H(), Prefer: 'return=minimal' },
        body: JSON.stringify({ status: 'offline', session_token: null, current_session_id: null, last_seen: new Date().toISOString() })
      }
    );

    return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true }) };
  } catch (e) {
    console.error('admin-force-offline:', e.message);
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: e.message }) };
  }
};
