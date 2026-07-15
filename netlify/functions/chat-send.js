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

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };

  let body;
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Bad Request' }; }

  if (!SB_URL || !SB_KEY) return { statusCode: 503, headers: CORS, body: JSON.stringify({ error: 'Service non configuré' }) };

  const { sessionId, content, senderType, agentEmail, agentToken } = body;

  if (!sessionId || !content || !senderType)
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Données manquantes' }) };
  if (content.length > 2000)
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Message trop long' }) };

  try {
    const sessions = await sbGet(
      `chat_sessions?id=eq.${encodeURIComponent(sessionId)}&select=id,status,agent_email&limit=1`
    );
    if (!sessions.length) return { statusCode: 404, headers: CORS, body: JSON.stringify({ error: 'Session introuvable' }) };
    if (sessions[0].status === 'closed') return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Session terminée' }) };

    // Vérifier l'identité de l'agent
    if (senderType === 'agent') {
      if (!agentEmail || !agentToken)
        return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Non authentifié' }) };
      const presence = await sbGet(
        `agent_presence?agent_email=eq.${encodeURIComponent(agentEmail)}&select=session_token&limit=1`
      );
      if (!presence.length || presence[0].session_token !== agentToken)
        return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Token invalide' }) };
      if (sessions[0].agent_email !== agentEmail)
        return { statusCode: 403, headers: CORS, body: JSON.stringify({ error: 'Session non assignée à cet agent' }) };
    }
    // Visiteur : le sessionId (UUID) est le secret suffisant

    await fetch(`${SB_URL}/rest/v1/chat_messages`, {
      method: 'POST',
      headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
      body: JSON.stringify({ session_id: sessionId, content: content.trim(), sender_type: senderType })
    });

    // Premier message agent : effacer le délai de réponse (fire-and-forget)
    if (senderType === 'agent') {
      fetch(`${SB_URL}/rest/v1/chat_sessions?id=eq.${encodeURIComponent(sessionId)}&response_deadline=not.is.null`, {
        method: 'PATCH',
        headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
        body: JSON.stringify({ response_deadline: null })
      }).catch(() => {});
    }

    // Message visiteur : notifier l'agent assigné par push (fire-and-forget)
    if (senderType === 'visitor' && sessions[0].agent_email) {
      const siteUrl = process.env.SITE_URL || process.env.URL || 'https://parlonsecoute.fr';
      fetch(`${siteUrl}/.netlify/functions/push-notify`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          title: '💬 Nouveau message',
          message: content.trim().slice(0, 80),
          url: '/agent-app.html',
          agentEmail: sessions[0].agent_email
        })
      }).catch(() => {});
    }

    return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true }) };
  } catch (e) {
    console.error('chat-send:', e.message);
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: e.message }) };
  }
};
