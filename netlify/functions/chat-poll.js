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
        sbGet(`chat_sessions?id=eq.${encodeURIComponent(sessionId)}&select=status,agent_email,assigned_at,extension_pending,transfer_session_id,duration_sec&limit=1`),
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
          assignedAt: s.assigned_at || null,
          extensionPending: s.extension_pending || null,
          transferSessionId: s.transfer_session_id || null,
          durationSec: s.duration_sec || null,
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

      // --- Réassignation : si un agent n'a pas envoyé de premier message dans les 2 min ---
      const nowIso = new Date().toISOString();
      const timedOut = await sbGet(
        `chat_sessions?status=eq.active&response_deadline=lt.${encodeURIComponent(nowIso)}&select=id,agent_email&limit=10`
      );
      for (const ts of timedOut) {
        // Chercher un agent disponible différent de celui qui a raté la session
        const freeAgents = await sbGet(
          `agent_presence?status=eq.online&current_session_id=is.null&agent_email=neq.${encodeURIComponent(ts.agent_email)}&select=agent_email&order=connected_since.asc&limit=1`
        );
        const newDeadline = new Date(Date.now() + 2 * 60 * 1000).toISOString();
        if (freeAgents.length) {
          // Réassigner à un autre agent (mise à jour conditionnelle pour éviter les races)
          const pr = await fetch(
            `${SB_URL}/rest/v1/chat_sessions?id=eq.${encodeURIComponent(ts.id)}&status=eq.active&response_deadline=lt.${encodeURIComponent(nowIso)}`,
            { method: 'PATCH', headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=representation' },
              body: JSON.stringify({ agent_email: freeAgents[0].agent_email, response_deadline: newDeadline, assigned_at: nowIso }) }
          );
          const patched = await pr.json();
          if (Array.isArray(patched) && patched.length) {
            Promise.all([
              fetch(`${SB_URL}/rest/v1/agent_presence?agent_email=eq.${encodeURIComponent(freeAgents[0].agent_email)}`,
                { method: 'PATCH', headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
                  body: JSON.stringify({ current_session_id: ts.id, status: 'busy', last_seen: nowIso }) }),
              fetch(`${SB_URL}/rest/v1/agent_presence?agent_email=eq.${encodeURIComponent(ts.agent_email)}`,
                { method: 'PATCH', headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
                  body: JSON.stringify({ current_session_id: null, status: 'online', last_seen: nowIso }) }),
              fetch(`${SB_URL}/rest/v1/chat_messages`,
                { method: 'POST', headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
                  body: JSON.stringify({ session_id: ts.id, content: 'Un autre écoutant va vous rejoindre dans quelques instants.', sender_type: 'system' }) })
            ]).catch(() => {});
          }
        } else {
          // Aucun agent dispo : remettre en attente
          const pr = await fetch(
            `${SB_URL}/rest/v1/chat_sessions?id=eq.${encodeURIComponent(ts.id)}&status=eq.active&response_deadline=lt.${encodeURIComponent(nowIso)}`,
            { method: 'PATCH', headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=representation' },
              body: JSON.stringify({ status: 'waiting', agent_email: null, response_deadline: null, assigned_at: null }) }
          );
          const patched = await pr.json();
          if (Array.isArray(patched) && patched.length) {
            Promise.all([
              fetch(`${SB_URL}/rest/v1/agent_presence?agent_email=eq.${encodeURIComponent(ts.agent_email)}`,
                { method: 'PATCH', headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
                  body: JSON.stringify({ current_session_id: null, status: 'online', last_seen: nowIso }) }),
              fetch(`${SB_URL}/rest/v1/chat_messages`,
                { method: 'POST', headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
                  body: JSON.stringify({ session_id: ts.id, content: 'Nous recherchons un écoutant disponible. Merci de patienter.', sender_type: 'system' }) })
            ]).catch(() => {});
          }
        }
      }

      // Sessions en attente (file)
      const waitingSessions = await sbGet(
        `chat_sessions?status=eq.waiting&select=id,pre_name,pre_topic,created_at,loyalty_discount&order=created_at.asc&limit=10`
      );

      // Toutes les sessions actives de cet agent
      const activeSessions = await sbGet(
        `chat_sessions?agent_email=eq.${encodeURIComponent(agentEmail)}&status=eq.active&select=id,pre_name,pre_topic,session_label,duration_sec,assigned_at,extension_pending,visitor_ip,loyalty_discount,response_deadline&order=assigned_at.asc&limit=3`
      );

      // L'agent poll = il voit ses sessions : lever response_deadline si encore actif
      const withDeadline = activeSessions.filter(s => s.response_deadline);
      if (withDeadline.length) {
        await Promise.all(withDeadline.map(s =>
          fetch(`${SB_URL}/rest/v1/chat_sessions?id=eq.${encodeURIComponent(s.id)}`, {
            method: 'PATCH',
            headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
            body: JSON.stringify({ response_deadline: null })
          }).catch(() => {})
        ));
      }

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
            sbGet(`chat_sessions?id=eq.${encodeURIComponent(currentSessionId)}&select=id,pre_name,pre_topic,status,created_at,session_label,duration_sec,assigned_at,visitor_ip&limit=1`),
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
