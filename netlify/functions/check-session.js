const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type',
  'Content-Type': 'application/json',
};

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') {
    return { statusCode: 200, headers: CORS, body: '' };
  }
  if (event.httpMethod !== 'GET') {
    return { statusCode: 405, headers: CORS, body: JSON.stringify({ error: 'Method Not Allowed' }) };
  }

  const token = event.queryStringParameters?.token;
  if (!token) {
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ valid: false, error: 'Token manquant' }) };
  }

  if (!process.env.SUPABASE_URL || !process.env.SUPABASE_SERVICE_KEY) {
    // Sans Supabase, accepte tous les tokens (mode dégradé)
    return { statusCode: 200, headers: CORS, body: JSON.stringify({ valid: true, degraded: true }) };
  }

  try {
    const res = await fetch(
      `${process.env.SUPABASE_URL}/rest/v1/sessions?token=eq.${encodeURIComponent(token)}&statut=eq.paid&select=id,formule,ends_at,statut`,
      {
        headers: {
          apikey: process.env.SUPABASE_SERVICE_KEY,
          Authorization: `Bearer ${process.env.SUPABASE_SERVICE_KEY}`,
        },
      }
    );

    const sessions = await res.json();
    if (!sessions || sessions.length === 0) {
      return { statusCode: 200, headers: CORS, body: JSON.stringify({ valid: false, error: 'Session introuvable ou expirée' }) };
    }

    const session = sessions[0];
    const now = new Date();
    const endsAt = new Date(session.ends_at);
    const remainingSeconds = Math.max(0, Math.floor((endsAt - now) / 1000));

    return {
      statusCode: 200,
      headers: CORS,
      body: JSON.stringify({
        valid: remainingSeconds > 0,
        formule: session.formule,
        remainingSeconds,
        endsAt: session.ends_at,
      }),
    };
  } catch (err) {
    console.error('check-session:', err.message);
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: err.message }) };
  }
};
