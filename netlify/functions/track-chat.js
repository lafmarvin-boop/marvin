const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;
const CORS = { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'Content-Type' };

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };

  let body;
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Bad Request' }; }

  if (!SB_URL || !SB_KEY) return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true }) };

  const { visitorId, formule } = body;

  try {
    await fetch(`${SB_URL}/rest/v1/chats`, {
      method: 'POST',
      headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}`, 'Content-Type': 'application/json', Prefer: 'return=minimal' },
      body: JSON.stringify({ visitor_id: visitorId || null, formule: formule || null })
    });

    const res = await fetch(`${SB_URL}/rest/v1/site_stats?id=eq.1&select=total_chats`, {
      headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` }
    });
    const rows = await res.json();
    const s = rows[0] || { total_chats: 0 };
    await fetch(`${SB_URL}/rest/v1/site_stats?id=eq.1`, {
      method: 'PATCH',
      headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}`, 'Content-Type': 'application/json', Prefer: 'return=minimal' },
      body: JSON.stringify({ total_chats: (s.total_chats || 0) + 1 })
    });
  } catch(e) { console.error('track-chat:', e.message); }

  return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true }) };
};
