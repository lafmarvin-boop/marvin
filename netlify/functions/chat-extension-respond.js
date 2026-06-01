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

async function sbPost(path, body) {
  const res = await fetch(`${SB_URL}/rest/v1/${path}`, {
    method: 'POST',
    headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=representation' },
    body: JSON.stringify(body)
  });
  return res.json();
}

async function sbPatch(path, body) {
  return fetch(`${SB_URL}/rest/v1/${path}`, {
    method: 'PATCH',
    headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
    body: JSON.stringify(body)
  });
}

async function insertMsg(sessionId, content, senderType) {
  return fetch(`${SB_URL}/rest/v1/chat_messages`, {
    method: 'POST',
    headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
    body: JSON.stringify({ session_id: sessionId, content, sender_type: senderType })
  });
}

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };

  let body;
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Bad Request' }; }

  if (!SB_URL || !SB_KEY) return { statusCode: 503, headers: CORS, body: JSON.stringify({ error: 'Service non configuré' }) };

  const { sessionId, agentEmail, agentToken, accept } = body;
  if (!sessionId || !agentEmail || !agentToken || accept === undefined)
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Paramètres manquants' }) };

  try {
    // Vérifier le token agent
    const presence = await sbGet(`agent_presence?agent_email=eq.${encodeURIComponent(agentEmail)}&select=session_token&limit=1`);
    if (!presence.length || presence[0].session_token !== agentToken)
      return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Token invalide' }) };

    // Charger la session
    const sessions = await sbGet(`chat_sessions?id=eq.${encodeURIComponent(sessionId)}&select=*&limit=1`);
    if (!sessions.length) return { statusCode: 404, headers: CORS, body: JSON.stringify({ error: 'Session introuvable' }) };
    const sess = sessions[0];

    // Lire la demande depuis extension_pending JSONB, ou en fallback depuis le message système
    let ext = sess.extension_pending;
    if (!ext) {
      const msgs = await sbGet(
        `chat_messages?session_id=eq.${encodeURIComponent(sessionId)}&sender_type=eq.system&order=created_at.desc&limit=10&select=content`
      );
      const extMsg = msgs.find(m => m.content && m.content.includes('souhaite prolonger'));
      if (extMsg) {
        const match = extMsg.content.match(/de (\d+) min/);
        if (match) {
          const mins = parseInt(match[1]);
          ext = { newDurationSec: mins * 60, totalForNewSession: mins * 60, paymentId: null, label: null };
        }
      }
    }
    if (!ext) return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Aucune demande en attente' }) };
    const now = new Date().toISOString();

    // ── CAS ACCEPTÉ ────────────────────────────────────────────────────────────
    if (accept) {
      // Idempotency: if extension_pending is already null, the extension was already processed
      if (!sess.extension_pending) {
        return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, accepted: true }) };
      }
      const newTotal = (sess.duration_sec || 1800) + ext.newDurationSec;
      const mins = Math.floor(ext.newDurationSec / 60);
      await Promise.all([
        sbPatch(`chat_sessions?id=eq.${encodeURIComponent(sessionId)}`, {
          duration_sec: newTotal,
          extension_pending: null
        }),
        insertMsg(sessionId, `⏱ Session prolongée de ${mins} min. Bonne continuation !`, 'system')
      ]);
      return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, accepted: true }) };
    }

    // ── CAS REFUSÉ ─────────────────────────────────────────────────────────────
    // 1. Récupérer l'historique de l'ancienne session
    const oldMessages = await sbGet(
      `chat_messages?session_id=eq.${encodeURIComponent(sessionId)}&order=created_at.asc&select=content,sender_type`
    );

    // 2. Créer la nouvelle session
    const newSessData = await sbPost('chat_sessions', {
      visitor_id: sess.visitor_id,
      status: 'waiting',
      pre_name: sess.pre_name,
      pre_topic: sess.pre_topic,
      session_type: sess.session_type,
      session_label: sess.session_label,
      duration_sec: ext.totalForNewSession || ext.newDurationSec,
      stripe_payment_id: ext.paymentId || null
    });
    const newSess = Array.isArray(newSessData) ? newSessData[0] : newSessData;
    if (!newSess?.id) throw new Error('Création nouvelle session échouée');
    const newSessionId = newSess.id;

    // 3. Copier l'historique dans la nouvelle session
    await insertMsg(newSessionId, '— Reprise de la conversation précédente —', 'system');
    for (const msg of oldMessages) {
      if (!msg.content.startsWith('⏳')) { // ne pas copier les messages système internes
        await insertMsg(newSessionId, msg.content, msg.sender_type);
      }
    }
    await insertMsg(newSessionId, '— Nouvelle session avec un autre écoutant —', 'system');

    // 4. Chercher un nouvel agent disponible (différent de l'actuel)
    const newAgents = await sbGet(
      `agent_presence?status=eq.online&current_session_id=is.null&agent_email=neq.${encodeURIComponent(agentEmail)}&select=agent_email,connected_since&order=connected_since.asc&limit=1`
    );

    if (newAgents.length) {
      const newAgentEmail = newAgents[0].agent_email;
      const profiles = await sbGet(`agent_profiles?email=eq.${encodeURIComponent(newAgentEmail)}&select=pseudo,prenom&limit=1`);
      const pseudo = profiles[0]?.pseudo || profiles[0]?.prenom || null;
      const greeting = pseudo
        ? `${pseudo} vous a rejoint. La session peut commencer.`
        : 'Un nouvel écoutant vous a rejoint. La session peut commencer.';
      await Promise.all([
        sbPatch(`chat_sessions?id=eq.${encodeURIComponent(newSessionId)}`, {
          agent_email: newAgentEmail, status: 'active', assigned_at: now
        }),
        sbPatch(`agent_presence?agent_email=eq.${encodeURIComponent(newAgentEmail)}`, {
          current_session_id: newSessionId, status: 'busy'
        }),
        insertMsg(newSessionId, greeting, 'system')
      ]);
    } else {
      await insertMsg(newSessionId, 'Un nouvel écoutant va vous rejoindre dès que possible.', 'system');
    }

    // 5. Fermer l'ancienne session avec marqueur de transfert
    await Promise.all([
      sbPatch(`chat_sessions?id=eq.${encodeURIComponent(sessionId)}`, {
        status: 'closed',
        closed_at: now,
        extension_pending: null,
        transfer_session_id: newSessionId
      }),
      sbPatch(`agent_presence?agent_email=eq.${encodeURIComponent(agentEmail)}`, {
        status: 'online', current_session_id: null
      }),
      insertMsg(sessionId, "L'écoutant n'a pas pu continuer. Votre conversation a été transférée.", 'system')
    ]);

    return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, accepted: false, newSessionId }) };
  } catch (e) {
    console.error('chat-extension-respond:', e.message);
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: e.message }) };
  }
};
