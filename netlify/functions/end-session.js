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
      if (rating) patch.rating = rating;
      if (comment) patch.rating_comment = comment;

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
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: err.message }) };
  }
};
