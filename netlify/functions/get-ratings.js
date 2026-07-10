const CORS = { 'Access-Control-Allow-Origin': '*', 'Content-Type': 'application/json' };

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  const url = process.env.SUPABASE_URL;
  const key = process.env.SUPABASE_SERVICE_KEY;
  if (!url || !key) return { statusCode: 200, headers: CORS, body: '[]' };
  const H = { apikey: key, Authorization: `Bearer ${key}` };
  try {
    const [r1, r2, r3] = await Promise.all([
      fetch(`${url}/rest/v1/sessions?statut=in.(paid,ended)&rating=gte.1&rating_comment=not.is.null&select=rating,rating_comment,client_pseudo,formule,started_at&order=started_at.desc&limit=50`, { headers: H }),
      fetch(`${url}/rest/v1/chat_sessions?rating=gte.1&rating_comment=not.is.null&select=rating,rating_comment,pre_name,session_label,assigned_at&order=assigned_at.desc&limit=50`, { headers: H }),
      fetch(`${url}/rest/v1/manual_reviews?stars=gte.1&text=not.is.null&select=stars,text,name,created_at&order=created_at.desc&limit=50`, { headers: H })
    ]);
    const [paid, chat, manual] = await Promise.all([r1.json(), r2.json(), r3.json()]);
    const merged = [
      ...(Array.isArray(paid) ? paid : []),
      ...(Array.isArray(chat) ? chat.map(r => ({ rating: r.rating, rating_comment: r.rating_comment, client_pseudo: r.pre_name, formule: r.session_label, started_at: r.assigned_at })) : []),
      ...(Array.isArray(manual) ? manual.map(r => ({ rating: r.stars, rating_comment: r.text, client_pseudo: r.name, formule: null, started_at: r.created_at })) : [])
    ].sort((a, b) => new Date(b.started_at) - new Date(a.started_at)).slice(0, 100);
    return { statusCode: 200, headers: CORS, body: JSON.stringify(merged) };
  } catch {
    return { statusCode: 500, headers: CORS, body: '[]' };
  }
};
