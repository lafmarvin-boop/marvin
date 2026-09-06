const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;
// Max, assistant d'écoute IA (ai-reply.js) et règle d'assistance partagée (_assist.js)
const { AI_EMAIL, maxShouldAssist } = require('./_assist');

// « … en train d'écrire » : l'indicateur s'éteint seul si la dernière frappe date
// de plus de 8 s. Aucun signal d'arrêt n'est nécessaire — rien ne peut rester bloqué.
const TYPING_TTL_MS = 8000;
const isTyping = ts => !!ts && Date.now() - new Date(ts).getTime() < TYPING_TTL_MS;
const { trace } = require('./_trace'); // TEMPORAIRE (voir _trace.js)

const CORS = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type',
};

const H = () => ({ apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` });

// Rythme de réponse de Max : une réponse instantanée trahit la machine et met le visiteur
// en position de « chat bot ». On retient donc l'affichage de sa réponse le temps qu'un humain
// aurait mis à lire et écrire : 5-7 s pour un message court (< 6 mots), 10-15 s au-delà.
// Le délai est dérivé de l'identifiant du message visiteur pour rester stable d'un poll à l'autre.
function maxReplyDelayMs(visitorMsg) {
  const words = String(visitorMsg.content || '').trim().split(/\s+/).filter(Boolean).length;
  let seed = 0;
  for (const ch of String(visitorMsg.id || '')) seed = (seed * 31 + ch.charCodeAt(0)) >>> 0;
  return words < 6 ? 5000 + (seed % 2001) : 10000 + (seed % 5001);
}

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

      const sessions = await sbGet(`chat_sessions?id=eq.${encodeURIComponent(sessionId)}&select=status,agent_email,assigned_at,extension_pending,transfer_session_id,duration_sec,response_deadline,agent_fetched_at,agent_seen_at,agent_typing_at,visitor_fetched_at&limit=1`);
      if (!sessions.length) return { statusCode: 404, headers: CORS, body: JSON.stringify({ error: 'Session introuvable' }) };
      const s = sessions[0];

      // Max répond ici dans deux cas :
      //   — il tient la session (aucun écoutant connecté) : dès 1,5 s, pour être prêt à l'échéance
      //     d'affichage calculée par maxReplyDelayMs() ;
      //   — il assiste un écoutant humain silencieux depuis plus de ASSIST_DELAY_MS (tchat jamais
      //     ouvert, ou écoutant occupé ailleurs). ai-reply revérifie les conditions de son côté.
      const lockFree = !s.response_deadline || new Date(s.response_deadline).getTime() < Date.now();
      const humanHeld = !!s.agent_email && s.agent_email !== AI_EMAIL;
      if (s.status === 'active' && (s.agent_email === AI_EMAIL ? lockFree : humanHeld)) {
        const lastRows = await sbGet(`chat_messages?session_id=eq.${encodeURIComponent(sessionId)}&select=id,sender_type,created_at&order=created_at.desc&limit=12`);
        // Les messages système (« l'utilisateur a quitté la page », prolongation…) s'intercalent
        // entre le visiteur et la réponse attendue : ils ne doivent pas masquer qui attend.
        const last = lastRows.find(m => m.sender_type !== 'system');
        const waitingMs = last && last.sender_type === 'visitor' ? Date.now() - new Date(last.created_at).getTime() : 0;
        let trigger = false, assist = false;
        if (!humanHeld) {
          trigger = !!last && last.sender_type === 'visitor' && waitingMs > 1500;
        } else {
          trigger = maxShouldAssist(lastRows, s.assigned_at);
          assist = true;
        }
        if (trigger) {
          if (assist) trace('sondage-visiteur', { s: String(sessionId).slice(0, 8), dernier: last?.sender_type || null }); // TEMPORAIRE
          const siteUrl = process.env.SITE_URL || process.env.URL || 'https://parlonsecoute.fr';
          try {
            await fetch(`${siteUrl}/.netlify/functions/ai-reply`, {
              method: 'POST', headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ sessionId, messageId: last && last.sender_type === 'visitor' ? last.id : null, assist }),
              // 3 s : le temps que la requête parte. Au-delà, ai-reply poursuit de son côté et
              // le message apparaîtra au sondage suivant — attendre plus ferait dépasser
              // chat-poll, que Netlify coupe à 10 s.
              signal: AbortSignal.timeout(3000)
            });
          } catch (e) { console.error('chat-poll ai fallback:', e.message); }
        }
      }

      const messages = await sbGet(`chat_messages?session_id=eq.${encodeURIComponent(sessionId)}&created_at=gt.${encodeURIComponent(sinceIso)}&select=id,content,sender_type,created_at&order=created_at.asc&limit=50`);

      // Retenir la réponse de Max tant que le délai « le temps de lire et d'écrire » n'est pas écoulé.
      let messagesOut = messages, retenu = false;
      // Uniquement quand Max tient la session : en assistance, le visiteur a déjà attendu
      // ASSIST_DELAY_MS, inutile d'ajouter le délai « le temps d'écrire ».
      const fromMax = m => s.agent_email === AI_EMAIL
        && (m.sender_type === 'agent' || m.sender_type === 'assistant');
      if (messages.some(fromMax)) {
        const recent = await sbGet(`chat_messages?session_id=eq.${encodeURIComponent(sessionId)}&select=id,content,sender_type,created_at&order=created_at.desc&limit=6`);
        const vIdx = recent.findIndex(m => m.sender_type === 'visitor');
        if (vIdx > 0) {
          const visitorMsg = recent[vIdx];
          const due = new Date(visitorMsg.created_at).getTime() + maxReplyDelayMs(visitorMsg);
          if (Date.now() < due) {
            const hide = new Set(recent.slice(0, vIdx).filter(fromMax).map(m => m.id));
            if (hide.size) { messagesOut = messages.filter(m => !hide.has(m.id)); retenu = true; }
          }
        }
      }

      // « Reçu » : le visiteur vient de recevoir ces messages. On n'écrit que si
      // quelque chose est réellement arrivé — inutile d'une écriture par sondage.
      if (messages.length || !s.visitor_fetched_at) {
        fetch(`${SB_URL}/rest/v1/chat_sessions?id=eq.${encodeURIComponent(sessionId)}`, {
          method: 'PATCH', headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
          body: JSON.stringify({ visitor_fetched_at: new Date().toISOString() })
        }).catch(() => {});
      }

      let agentPseudo = null;
      if (s.agent_email === AI_EMAIL) agentPseudo = 'Max'; // l'interface ajoute « vous écoute »
      else if (s.agent_email) {
        const profiles = await sbGet(`agent_profiles?email=eq.${encodeURIComponent(s.agent_email)}&select=pseudo,prenom&limit=1`);
        agentPseudo = profiles[0]?.pseudo || profiles[0]?.prenom || null;
      }

      return {
        statusCode: 200, headers: CORS,
        body: JSON.stringify({
          status: s.status,
          agentConnected: s.status === 'active' && !!s.agent_email,
          agentPseudo,
          agentIsAI: s.agent_email === AI_EMAIL,
          assignedAt: s.assigned_at || null,
          extensionPending: s.extension_pending || null,
          transferSessionId: s.transfer_session_id || null,
          durationSec: s.duration_sec || null,
          // Accusés portant sur les messages du visiteur : jusqu'où l'autre a reçu, jusqu'où il a lu
          deliveredAt: s.agent_fetched_at || null,
          readAt: s.agent_seen_at || null,
          // Réponse de Max déjà écrite mais volontairement retenue : c'est bien « en train d'écrire »
          otherTyping: isTyping(s.agent_typing_at) || retenu,
          messages: messagesOut
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
      const serverTime = new Date().toISOString();
      const sinceIso = since ? new Date(since).toISOString() : new Date(0).toISOString();

      // --- Réassignation : si un agent n'a pas envoyé de premier message dans les 2 min ---
      const nowIso = new Date().toISOString();
      const timedOut = await sbGet(
        `chat_sessions?status=eq.active&response_deadline=lt.${encodeURIComponent(nowIso)}&agent_email=neq.${encodeURIComponent(AI_EMAIL)}&select=id,agent_email&limit=10`
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
        `chat_sessions?agent_email=eq.${encodeURIComponent(agentEmail)}&status=eq.active&select=id,pre_name,pre_topic,session_label,duration_sec,assigned_at,extension_pending,visitor_ip,loyalty_discount,response_deadline,visitor_fetched_at,visitor_seen_at,visitor_typing_at,agent_fetched_at&order=assigned_at.asc&limit=3`
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

      // ── Assistance de Max, déclenchée depuis le sondage de l'ÉCOUTANT ──
      // Le sondage du visiteur ne suffit pas : sur mobile, sa page passe en arrière-plan dès qu'il
      // change d'application et le navigateur y suspend les minuteurs. L'application de l'écoutant,
      // elle, tourne au premier plan — et c'est précisément lui qui tarde à répondre.
      // Une seule requête pour toutes ses sessions, le sondage étant fréquent.
      if (activeSessions.length) {
        try {
          const siteUrl = process.env.SITE_URL || process.env.URL || 'https://parlonsecoute.fr';
          await Promise.all(activeSessions.map(async (sess) => {
            // Une requête par session : un « in.(…) » commun, borné en nombre de lignes, peut
            // n'en couvrir qu'une seule si l'une d'elles est bavarde.
            const msgs = await sbGet(`chat_messages?session_id=eq.${encodeURIComponent(sess.id)}&select=id,sender_type,created_at&order=created_at.desc&limit=12`);
            if (!maxShouldAssist(msgs, sess.assigned_at)) return;
            const last = msgs.find(m => m.sender_type !== 'system');
            trace('sondage-ecoutant', { s: sess.id.slice(0, 8), dernier: last?.sender_type || null }); // TEMPORAIRE
            try {
              await fetch(`${siteUrl}/.netlify/functions/ai-reply`, {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId: sess.id, messageId: last && last.sender_type === 'visitor' ? last.id : null, assist: true }),
                signal: AbortSignal.timeout(2500)
              });
            } catch { /* ai-reply poursuit de son côté */ }
          }));
        } catch (e) { console.error('chat-poll assist (agent):', e.message); }
      }

      // Pour chaque session active, récupérer les messages depuis sinceIso.
      // Session attribuée depuis le dernier poll (nouvelle, transfert, relais de Max) → tout l'historique,
      // pour que l'écoutant voie la conversation déjà engagée.
      const sessions = await Promise.all(activeSessions.map(async (s) => {
        const newlyAssigned = !since || (s.assigned_at && new Date(s.assigned_at) >= new Date(sinceIso));
        const msgs = await sbGet(
          `chat_messages?session_id=eq.${encodeURIComponent(s.id)}${newlyAssigned ? '' : `&created_at=gt.${encodeURIComponent(sinceIso)}`}&select=id,content,sender_type,created_at&order=created_at.asc&limit=${newlyAssigned ? 300 : 100}`
        );
        // « Reçu » côté écoutant : son application vient de recevoir ces messages.
        if (msgs.length || !s.agent_fetched_at) {
          fetch(`${SB_URL}/rest/v1/chat_sessions?id=eq.${encodeURIComponent(s.id)}`, {
            method: 'PATCH', headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
            body: JSON.stringify({ agent_fetched_at: new Date().toISOString() })
          }).catch(() => {});
        }
        return {
          ...s, messages: msgs,
          // Accusés portant sur les messages de l'écoutant
          deliveredAt: s.visitor_fetched_at || null,
          readAt: s.visitor_seen_at || null,
          otherTyping: isTyping(s.visitor_typing_at)
        };
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
        body: JSON.stringify({ agentStatus, currentSessionId, currentSession, messages, sessions, waitingSessions, serverTime })
      };
    }

    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Rôle invalide' }) };
  } catch (e) {
    console.error('chat-poll:', e.message);
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: e.message }) };
  }
};
