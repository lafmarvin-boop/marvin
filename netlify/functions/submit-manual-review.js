const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;

const CORS = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type',
};

exports.handler = async (ev) => {
  if (ev.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (ev.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: '{}' };

  let body;
  try { body = JSON.parse(ev.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: '{}' }; }

  const { name, stars, text } = body;
  if (!name || !stars || !text)
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Champs manquants' }) };

  const safeStars = Number.isInteger(Number(stars)) && Number(stars) >= 1 && Number(stars) <= 5 ? Number(stars) : null;
  if (!safeStars) return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Note invalide' }) };
  const safeName = String(name).trim().slice(0, 100);
  const safeText = String(text).trim().slice(0, 1000);

  if (!SB_URL || !SB_KEY)
    return { statusCode: 503, headers: CORS, body: JSON.stringify({ error: 'Non configuré' }) };

  try {
    await fetch(`${SB_URL}/rest/v1/manual_reviews`, {
      method: 'POST',
      headers: {
        apikey: SB_KEY,
        Authorization: `Bearer ${SB_KEY}`,
        'Content-Type': 'application/json',
        Prefer: 'return=minimal',
      },
      body: JSON.stringify({ name: safeName, stars: safeStars, text: safeText }),
    });
    return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true }) };
  } catch (e) {
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: e.message }) };
  }
};
