const CORS = { 'Access-Control-Allow-Origin': '*', 'Content-Type': 'application/json' };

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  const url = process.env.SUPABASE_URL;
  const key = process.env.SUPABASE_SERVICE_KEY;
  if (!url || !key) return { statusCode: 200, headers: CORS, body: '[]' };
  try {
    const r = await fetch(
      `${url}/rest/v1/sessions?statut=in.(paid,ended)&rating=gte.4&rating_comment=not.is.null&select=rating,rating_comment,client_pseudo,formule,started_at&order=started_at.desc&limit=20`,
      { headers: { apikey: key, Authorization: `Bearer ${key}` } }
    );
    const data = await r.json();
    return { statusCode: 200, headers: CORS, body: JSON.stringify(Array.isArray(data) ? data : []) };
  } catch {
    return { statusCode: 500, headers: CORS, body: '[]' };
  }
};
