const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;

const CORS = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type',
};

const H = () => ({ apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` });

async function sbGet(path) {
  const res = await fetch(`${SB_URL}/rest/v1/${path}`, { headers: H() });
  const d = await res.json();
  return Array.isArray(d) ? d : [];
}

async function sbPatch(path, body) {
  return fetch(`${SB_URL}/rest/v1/${path}`, {
    method: 'PATCH',
    headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
    body: JSON.stringify(body)
  });
}

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };

  let body;
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Bad Request' }; }

  if (!SB_URL || !SB_KEY) return { statusCode: 503, headers: CORS, body: JSON.stringify({ error: 'Service non configuré' }) };

  const { sessionId, rating, ratingComment, agentEmail, agentToken, closedBy } = body;
  if (!sessionId) return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'sessionId requis' }) };

  try {
    const sessions = await sbGet(`chat_sessions?id=eq.${encodeURIComponent(sessionId)}&select=agent_email,status&limit=1`);
    if (!sessions.length) return { statusCode: 404, headers: CORS, body: JSON.stringify({ error: 'Session introuvable' }) };
    if (sessions[0].status === 'closed') return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true }) };

    // Vérifier le token si c'est l'agent qui ferme
    if (closedBy === 'agent') {
      if (!agentToken) return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Token requis' }) };
      const presence = await sbGet(`agent_presence?agent_email=eq.${encodeURIComponent(agentEmail)}&select=session_token&limit=1`);
      if (!presence.length || presence[0].session_token !== agentToken)
        return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Token invalide' }) };
    }

    const now = new Date().toISOString();
    const updates = { status: 'closed', closed_at: now };
    if (rating) { updates.rating = parseInt(rating); updates.rating_comment = ratingComment || null; }

    await sbPatch(`chat_sessions?id=eq.${encodeURIComponent(sessionId)}`, updates);

    // Libérer l'agent (repasse en ligne, prêt pour la session suivante)
    const agentMail = sessions[0].agent_email;
    if (agentMail) {
      await sbPatch(`agent_presence?agent_email=eq.${encodeURIComponent(agentMail)}`, {
        status: 'online',
        current_session_id: null
      });
    }

    return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true }) };
  } catch (e) {
    console.error('chat-close:', e.message);
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: e.message }) };
  }
};
