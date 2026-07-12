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

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };

  let body;
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Bad Request' }; }

  if (!SB_URL || !SB_KEY) return { statusCode: 503, headers: CORS, body: JSON.stringify({ error: 'Service non configuré' }) };

  const { visitorId, name, sessionType, sessionLabel, durationSec, paymentId, loyaltyDiscount } = body;
  if (!visitorId || !name)
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Données manquantes' }) };

  const visitorIp = (event.headers['x-nf-client-connection-ip']
    || (event.headers['x-forwarded-for'] || '').split(',')[0]
    || '').trim() || null;

  try {
    // Créer la session
    const created = await sbPost('chat_sessions', {
      visitor_id: visitorId,
      status: 'waiting',
      pre_name: name,
      session_type: sessionType || 'paid',
      session_label: sessionLabel || '',
      duration_sec: parseInt(durationSec) || 1800,
      stripe_payment_id: paymentId || null,
      visitor_ip: visitorIp,
      loyalty_discount: parseInt(loyaltyDiscount) || 0
    });
    const session = Array.isArray(created) ? created[0] : created;
    if (!session?.id) throw new Error('Création session échouée');
    const sessionId = session.id;

    // Trouver le meilleur agent disponible (online ou busy avec < 3 sessions actives)
    const candidates = await sbGet(
      `agent_presence?status=in.(online,busy)&select=agent_email,connected_since&order=connected_since.asc&limit=10`
    );

    let assignedAgent = null;
    let agentPseudo = null;
    for (const candidate of candidates) {
      const activeSessions = await sbGet(
        `chat_sessions?agent_email=eq.${encodeURIComponent(candidate.agent_email)}&status=eq.active&select=id&limit=4`
      );
      if (activeSessions.length < 3) {
        assignedAgent = candidate.agent_email;
        break;
      }
    }

    if (assignedAgent) {
      const now = new Date().toISOString();
      const [profiles] = await Promise.all([
        sbGet(`agent_profiles?email=eq.${encodeURIComponent(assignedAgent)}&select=pseudo,prenom&limit=1`),
        sbPatch(`chat_sessions?id=eq.${encodeURIComponent(sessionId)}`, {
          agent_email: assignedAgent,
          status: 'active',
          assigned_at: now,
          response_deadline: new Date(Date.now() + 2 * 60 * 1000).toISOString()
        }),
        sbPatch(`agent_presence?agent_email=eq.${encodeURIComponent(assignedAgent)}`, {
          current_session_id: sessionId,
          status: 'busy'
        })
      ]);
      agentPseudo = profiles[0]?.pseudo || profiles[0]?.prenom || null;
    }

    // Message système initial
    const greeting = agentPseudo
      ? `${agentPseudo} vous a rejoint. La session peut commencer.`
      : 'Un écoutant vous a rejoint. La session peut commencer.';
    await fetch(`${SB_URL}/rest/v1/chat_messages`, {
      method: 'POST',
      headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
      body: JSON.stringify({
        session_id: sessionId,
        content: assignedAgent ? greeting : 'Votre demande est bien enregistrée. Un écoutant vous rejoindra dans les 2 prochaines minutes — restez bien en ligne !',
        sender_type: 'system'
      })
    });

    // Push notification : à l'agent assigné, ou à tous si personne n'était disponible
    const siteUrl = process.env.SITE_URL || 'https://parlonsecoute.netlify.app';
    fetch(`${siteUrl}/.netlify/functions/push-notify`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        title: assignedAgent ? '💬 Nouveau tchat assigné' : '💬 Nouveau tchat en attente',
        message: `${name} attend votre aide`,
        url: '/agent-app.html',
        ...(assignedAgent ? { agentEmail: assignedAgent } : {})
      })
    }).catch(() => {});

    return {
      statusCode: 200, headers: CORS,
      body: JSON.stringify({ sessionId, status: assignedAgent ? 'active' : 'waiting', agentAssigned: !!assignedAgent })
    };
  } catch (e) {
    console.error('chat-start:', e.message);
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: e.message }) };
  }
};
