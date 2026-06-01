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
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Invalid JSON' }; }

  const { content, paymentId } = body;
  if (!content || !content.trim())
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Suggestion vide' }) };
  if (content.length > 1000)
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Trop long (max 1000 caractères)' }) };

  if (!SB_URL || !SB_KEY)
    return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, degraded: true }) };

  const res = await fetch(`${SB_URL}/rest/v1/suggestions`, {
    method: 'POST',
    headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}`, 'Content-Type': 'application/json', Prefer: 'return=minimal' },
    body: JSON.stringify({ content: content.trim(), payment_id: paymentId || null }),
  });

  if (!res.ok) {
    console.error('submit-suggestion error:', await res.text());
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: 'Erreur serveur' }) };
  }

  return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true }) };
};
