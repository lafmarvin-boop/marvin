const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type',
  'Content-Type': 'application/json',
};

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') {
    return { statusCode: 200, headers: CORS, body: '' };
  }
  if (event.httpMethod !== 'POST') {
    return { statusCode: 405, headers: CORS, body: JSON.stringify({ error: 'Method Not Allowed' }) };
  }

  try {
    const { paymentId, rating, comment } = JSON.parse(event.body || '{}');

    if (process.env.SUPABASE_URL && process.env.SUPABASE_SERVICE_KEY && paymentId) {
      const patch = { statut: 'ended' };
      const safeRating = Number.isInteger(rating) && rating >= 1 && rating <= 5 ? rating : null;
      const safeComment = typeof comment === 'string' ? comment.slice(0, 500) : null;
      if (safeRating) patch.rating = safeRating;
      if (safeComment) patch.rating_comment = safeComment;

      await fetch(
        `${process.env.SUPABASE_URL}/rest/v1/sessions?stripe_payment_id=eq.${paymentId}`,
        {
          method: 'PATCH',
          headers: {
            apikey: process.env.SUPABASE_SERVICE_KEY,
            Authorization: `Bearer ${process.env.SUPABASE_SERVICE_KEY}`,
            'Content-Type': 'application/json',
            Prefer: 'return=minimal',
          },
          body: JSON.stringify(patch),
        }
      );
    }

    return { statusCode: 200, headers: CORS, body: JSON.stringify({ ended: true }) };
  } catch (err) {
    console.error('end-session:', err.message);
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: 'Erreur serveur' }) };
  }
};
