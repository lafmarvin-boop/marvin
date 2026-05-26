const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;

const CORS = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type',
};

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };

  let body;
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Bad Request' }; }

  if (!SB_URL || !SB_KEY) return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true }) };

  const { visitorId, isNew } = body;
  if (!visitorId || typeof visitorId !== 'string' || visitorId.length > 64)
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Invalid visitor ID' }) };

  try {
    // Enregistrer la visite avec timestamp (pour stats par période)
    await fetch(`${SB_URL}/rest/v1/visits`, {
      method: 'POST',
      headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}`, 'Content-Type': 'application/json', Prefer: 'return=minimal' },
      body: JSON.stringify({ visitor_id: visitorId, is_new: !!isNew })
    });

    // Mettre à jour les compteurs globaux (all-time rapide)
    const res = await fetch(`${SB_URL}/rest/v1/site_stats?id=eq.1&select=total_visits,unique_visitors`, {
      headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` }
    });
    const rows = await res.json();
    const s = rows[0] || { total_visits: 0, unique_visitors: 0 };
    await fetch(`${SB_URL}/rest/v1/site_stats?id=eq.1`, {
      method: 'PATCH',
      headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}`, 'Content-Type': 'application/json', Prefer: 'return=minimal' },
      body: JSON.stringify({
        total_visits: (s.total_visits || 0) + 1,
        unique_visitors: (s.unique_visitors || 0) + (isNew ? 1 : 0),
        updated_at: new Date().toISOString()
      })
    });
  } catch(e) { console.error('track-visit:', e.message); }

  return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true }) };
};
