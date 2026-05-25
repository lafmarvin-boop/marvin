const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;

const CORS = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type'
};

const VALID_ROOMS = ['anxiete', 'deuil', 'couple', 'travail', 'solitude', 'confiance'];

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (!SB_URL || !SB_KEY) return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: 'Supabase non configuré' }) };

  // GET — load messages for a room, optionally since a timestamp
  if (event.httpMethod === 'GET') {
    const { room_id, since } = event.queryStringParameters || {};
    if (!VALID_ROOMS.includes(room_id))
      return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Salon invalide' }) };

    let url = `${SB_URL}/rest/v1/group_messages?room_id=eq.${room_id}&order=created_at.asc&limit=200`;
    if (since) url += `&created_at=gt.${encodeURIComponent(since)}`;

    const r = await fetch(url, { headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` } });
    const data = await r.json();
    return { statusCode: 200, headers: CORS, body: JSON.stringify(Array.isArray(data) ? data : []) };
  }

  // POST — send a new message
  if (event.httpMethod === 'POST') {
    let body;
    try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Invalid JSON' }; }

    const { room_id, author, content, is_private, recipient, is_question, is_agent } = body;

    if (!VALID_ROOMS.includes(room_id))
      return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Salon invalide' }) };
    if (!author || author.length < 1 || author.length > 30)
      return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Auteur invalide' }) };
    if (!content || !content.trim() || content.length > 1000)
      return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Contenu invalide' }) };

    const r = await fetch(`${SB_URL}/rest/v1/group_messages`, {
      method: 'POST',
      headers: {
        apikey: SB_KEY,
        Authorization: `Bearer ${SB_KEY}`,
        'Content-Type': 'application/json',
        Prefer: 'return=representation'
      },
      body: JSON.stringify({
        room_id,
        author,
        content: content.trim(),
        is_private: !!is_private,
        recipient: is_private ? (recipient || null) : null,
        is_question: !is_private && !!is_question,
        is_agent: !!is_agent
      })
    });

    if (!r.ok) return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: 'Erreur insertion' }) };
    const data = await r.json();
    return { statusCode: 201, headers: CORS, body: JSON.stringify(Array.isArray(data) ? data[0] : data) };
  }

  return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };
};
