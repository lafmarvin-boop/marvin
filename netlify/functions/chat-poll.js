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

  const { role } = body;

  try {
    // ── VISITEUR ──
    if (role === 'visitor') {
      const { sessionId, since } = body;
      if (!sessionId) return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'sessionId requis' }) };

      const sinceIso = since ? new Date(since).toISOString() : new Date(0).toISOString();

      const [sessions, messages] = await Promise.all([
        sbGet(`chat_sessions?id=eq.${encodeURIComponent(sessionId)}&select=status,agent_email,assigned_at,extension_pending,transfer_session_id&limit=1`),
        sbGet(`chat_messages?session_id=eq.${encodeURIComponent(sessionId)}&created_at=gt.${encodeURIComponent(sinceIso)}&select=id,content,sender_type,created_at&order=created_at.asc&limit=50`)
      ]);

      if (!sessions.length) return { statusCode: 404, headers: CORS, body: JSON.stringify({ error: 'Session introuvable' }) };
      const s = sessions[0];

      let agentPseudo = null;
      if (s.agent_email) {
        const profiles = await sbGet(`agent_profiles?email=eq.${encodeURIComponent(s.agent_email)}&select=pseudo,prenom&limit=1`);
        agentPseudo = profiles[0]?.pseudo || profiles[0]?.prenom || null;
      }

      return {
        statusCode: 200, headers: CORS,
        body: JSON.stringify({
          status: s.status,
          agentConnected: s.status === 'active' && !!s.agent_email,
          agentPseudo,
          extensionPending: s.extension_pending || null,
          transferSessionId: s.transfer_session_id || null,
          messages
        })
      };
    }

    // ── AGENT ──
    if (role === 'agent') {
      const { agentEmail, agentToken, since } = body;
      if (!agentEmail || !agentToken)
        return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Non authentifié' }) };

      const presence = await sbGet(
        `agent_presence?agent_email=eq.${encodeURIComponent(agentEmail)}&select=session_token,current_session_id,status&limit=1`
      );
      if (!presence.length || presence[0].session_token !== agentToken)
        return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Token invalide' }) };

      // Mettre à jour last_seen (fire-and-forget)
      fetch(`${SB_URL}/rest/v1/agent_presence?agent_email=eq.${encodeURIComponent(agentEmail)}`, {
        method: 'PATCH',
        headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
        body: JSON.stringify({ last_seen: new Date().toISOString() })
      }).catch(() => {});

      const { current_session_id: currentSessionId, status: agentStatus } = presence[0];
      const sinceIso = since ? new Date(since).toISOString() : new Date(0).toISOString();

      // Sessions en attente (file)
      const waitingSessions = await sbGet(
        `chat_sessions?status=eq.waiting&select=id,pre_name,pre_topic,created_at&order=created_at.asc&limit=10`
      );

      // Toutes les sessions actives de cet agent
      const activeSessions = await sbGet(
        `chat_sessions?agent_email=eq.${encodeURIComponent(agentEmail)}&status=eq.active&select=id,pre_name,pre_topic,session_label,duration_sec,assigned_at,extension_pending&order=assigned_at.asc&limit=3`
      );

      // Pour chaque session active, récupérer les messages depuis sinceIso
      const sessions = await Promise.all(activeSessions.map(async (s) => {
        const msgs = await sbGet(
          `chat_messages?session_id=eq.${encodeURIComponent(s.id)}&created_at=gt.${encodeURIComponent(sinceIso)}&select=id,content,sender_type,created_at&order=created_at.asc&limit=100`
        );
        return { ...s, messages: msgs };
      }));

      // Compat: session courante + messages (basé sur current_session_id)
      let currentSession = null;
      let messages = [];
      if (currentSessionId) {
        const found = sessions.find(s => s.id === currentSessionId);
        if (found) {
          currentSession = found;
          messages = found.messages;
        } else {
          // currentSessionId présent mais pas dans les sessions actives — charger quand même
          const [sessRows, msgs] = await Promise.all([
            sbGet(`chat_sessions?id=eq.${encodeURIComponent(currentSessionId)}&select=id,pre_name,pre_topic,status,created_at,session_label,duration_sec,assigned_at&limit=1`),
            sbGet(`chat_messages?session_id=eq.${encodeURIComponent(currentSessionId)}&created_at=gt.${encodeURIComponent(sinceIso)}&select=id,content,sender_type,created_at&order=created_at.asc&limit=100`)
          ]);
          currentSession = sessRows[0] || null;
          messages = msgs;
        }
      }

      return {
        statusCode: 200, headers: CORS,
        body: JSON.stringify({ agentStatus, currentSessionId, currentSession, messages, sessions, waitingSessions })
      };
    }

    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Rôle invalide' }) };
  } catch (e) {
    console.error('chat-poll:', e.message);
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: e.message }) };
  }
};
