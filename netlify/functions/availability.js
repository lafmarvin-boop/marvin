const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'Parlons2026!';

const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;

const headers = {
  'Content-Type': 'application/json',
  'Cache-Control': 'no-cache',
  'Access-Control-Allow-Origin': '*'
};

async function getOnline() {
  const r = await fetch(
    `${SB_URL}/rest/v1/agents?pseudo=eq.parlons_admin&select=actif&limit=1`,
    { headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` } }
  );
  const d = await r.json();
  return !!(Array.isArray(d) && d.length > 0 && d[0].actif);
}

async function setOnline(online) {
  await fetch(`${SB_URL}/rest/v1/agents`, {
    method: 'POST',
    headers: {
      apikey: SB_KEY,
      Authorization: `Bearer ${SB_KEY}`,
      'Content-Type': 'application/json',
      Prefer: 'resolution=merge-duplicates'
    },
    body: JSON.stringify({ pseudo: 'parlons_admin', nom: 'Admin', actif: online })
  });
}

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers };
  if (!SB_URL || !SB_KEY) return { statusCode: 200, headers, body: JSON.stringify({ online: false, error: 'Supabase non configuré' }) };

  // ── POST : toggle admin manuel ──
  if (event.httpMethod === 'POST') {
    let body;
    try { body = JSON.parse(event.body || '{}'); } catch { body = {}; }
    if (body.password !== ADMIN_PASSWORD)
      return { statusCode: 403, headers, body: JSON.stringify({ error: 'Non autorisé' }) };
    try {
      await setOnline(!!body.online);
      return { statusCode: 200, headers, body: JSON.stringify({ online: !!body.online }) };
    } catch (e) {
      return { statusCode: 500, headers, body: JSON.stringify({ error: e.message }) };
    }
  }

  // ── GET : statut depuis Supabase ──
  if (event.httpMethod !== 'GET') return { statusCode: 405, headers, body: 'Method Not Allowed' };
  try {
    const online = await getOnline();
    return { statusCode: 200, headers, body: JSON.stringify({ online, source: 'supabase' }) };
  } catch (e) {
    return { statusCode: 200, headers, body: JSON.stringify({ online: false, error: e.message }) };
  }
};
