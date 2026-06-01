const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;

const headers = {
  'Content-Type': 'application/json',
  'Cache-Control': 'no-cache',
  'Access-Control-Allow-Origin': '*'
};

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers };
  if (event.httpMethod !== 'GET') return { statusCode: 405, headers, body: 'Method Not Allowed' };
  if (!SB_URL || !SB_KEY) return { statusCode: 200, headers, body: JSON.stringify({ online: false }) };

  const H = { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` };

  // Un agent est "vivant" s'il a pingé dans les 3 dernières minutes
  const freshCutoff = new Date(Date.now() - 3 * 60 * 1000).toISOString();

  try {
    const res = await fetch(
      `${SB_URL}/rest/v1/agent_presence?status=in.(online,busy)&last_seen=gte.${encodeURIComponent(freshCutoff)}&select=agent_email,status&limit=20`,
      { headers: H }
    );
    const agents = await res.json();
    const list = Array.isArray(agents) ? agents : [];
    const online = list.length > 0;
    const freeCount = list.filter(a => a.status === 'online').length;

    // Auto-expirer les agents fantômes (fire-and-forget)
    fetch(
      `${SB_URL}/rest/v1/agent_presence?status=in.(online,busy)&last_seen=lt.${encodeURIComponent(freshCutoff)}`,
      {
        method: 'PATCH',
        headers: { ...H, 'Content-Type': 'application/json', Prefer: 'return=minimal' },
        body: JSON.stringify({ status: 'offline', current_session_id: null, session_token: null })
      }
    ).catch(() => {});

    return { statusCode: 200, headers, body: JSON.stringify({ online, freeCount, source: 'agent_presence' }) };
  } catch (e) {
    return { statusCode: 200, headers, body: JSON.stringify({ online: false, error: e.message }) };
  }
};
