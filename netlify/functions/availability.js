const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;
const ADMIN_PWD   = process.env.ADMIN_PASSWORD;
const ADMIN_EMAIL = (process.env.ADMIN_EMAIL || 'lafmarvin@gmail.com').toLowerCase();

const headers = {
  'Content-Type': 'application/json',
  'Cache-Control': 'no-cache',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type',
};

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers };

  const H = SB_URL && SB_KEY ? { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` } : null;

  // GET — lire le statut de disponibilité
  if (event.httpMethod === 'GET') {
    if (!H) return { statusCode: 200, headers, body: JSON.stringify({ online: false }) };
    try {
      const res = await fetch(
        `${SB_URL}/rest/v1/agent_presence?status=in.(online,busy)&select=agent_email,status&limit=20`,
        { headers: H }
      );
      const agents = await res.json();
      const list = Array.isArray(agents) ? agents : [];
      const online = list.length > 0;
      const freeCount = list.filter(a => a.status === 'online').length;

      return { statusCode: 200, headers, body: JSON.stringify({ online, freeCount, source: 'agent_presence' }) };
    } catch (e) {
      return { statusCode: 200, headers, body: JSON.stringify({ online: false, error: e.message }) };
    }
  }

  // POST — l'admin bascule sa propre disponibilité
  if (event.httpMethod === 'POST') {
    if (!H) return { statusCode: 503, headers, body: JSON.stringify({ error: 'Non configuré' }) };

    let body;
    try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers, body: JSON.stringify({ error: 'JSON invalide' }) }; }

    const { online, password } = body;
    if (!password || !ADMIN_PWD || password !== ADMIN_PWD)
      return { statusCode: 401, headers, body: JSON.stringify({ error: 'Non autorisé' }) };

    const newStatus = online ? 'online' : 'offline';
    const now = new Date().toISOString();

    try {
      await fetch(`${SB_URL}/rest/v1/agent_presence`, {
        method: 'POST',
        headers: { ...H, 'Content-Type': 'application/json', Prefer: 'resolution=merge-duplicates' },
        body: JSON.stringify({
          agent_email: ADMIN_EMAIL,
          status: newStatus,
          last_seen: now,
          current_session_id: online ? undefined : null,
          session_token: online ? undefined : null,
        })
      });
      return { statusCode: 200, headers, body: JSON.stringify({ online: newStatus === 'online' }) };
    } catch (e) {
      return { statusCode: 500, headers, body: JSON.stringify({ error: e.message }) };
    }
  }

  return { statusCode: 405, headers, body: 'Method Not Allowed' };
};
