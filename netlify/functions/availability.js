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

  try {
    // Un agent est "disponible" s'il est en ligne (online ou busy = quelqu'un est pris en charge)
    const res = await fetch(
      `${SB_URL}/rest/v1/agent_presence?status=in.(online,busy)&select=agent_email,status&limit=20`,
      { headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` } }
    );
    const agents = await res.json();
    const list = Array.isArray(agents) ? agents : [];
    const online = list.length > 0;
    const freeCount = list.filter(a => a.status === 'online').length;

    return { statusCode: 200, headers, body: JSON.stringify({ online, freeCount, source: 'agent_presence' }) };
  } catch (e) {
    return { statusCode: 200, headers, body: JSON.stringify({ online: false, error: e.message }) };
  }
};
