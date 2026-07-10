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

  const { chatSessionId, rating, comment } = body;
  if (!chatSessionId || !rating)
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Missing fields' }) };

  if (!SB_URL || !SB_KEY)
    return { statusCode: 503, headers: CORS, body: JSON.stringify({ error: 'Not configured' }) };

  const safeRating = Number.isInteger(Number(rating)) && Number(rating) >= 1 && Number(rating) <= 5 ? Number(rating) : null;
  if (!safeRating) return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Invalid rating' }) };
  const safeComment = typeof comment === 'string' && comment.trim() ? comment.trim().slice(0, 500) : null;

  try {
    const H = { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` };

    // Toujours sauvegarder dans chat_sessions (fonctionne pour sessions gratuites ET payantes)
    await fetch(`${SB_URL}/rest/v1/chat_sessions?id=eq.${encodeURIComponent(chatSessionId)}`, {
      method: 'PATCH',
      headers: { ...H, 'Content-Type': 'application/json', Prefer: 'return=minimal' },
      body: JSON.stringify({ rating: safeRating, rating_comment: safeComment })
    });

    // Si session payante Stripe, aussi mettre à jour la table sessions
    const r = await fetch(`${SB_URL}/rest/v1/chat_sessions?id=eq.${encodeURIComponent(chatSessionId)}&select=stripe_payment_id`, { headers: H });
    const rows = await r.json();
    const paymentId = rows?.[0]?.stripe_payment_id;

    if (paymentId) {
      await fetch(`${SB_URL}/rest/v1/sessions?stripe_payment_id=eq.${encodeURIComponent(paymentId)}`, {
        method: 'PATCH',
        headers: { ...H, 'Content-Type': 'application/json', Prefer: 'return=minimal' },
        body: JSON.stringify({ rating: safeRating, rating_comment: safeComment, statut: 'ended' })
      });
    }

    return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true }) };
  } catch (e) {
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: e.message }) };
  }
};
