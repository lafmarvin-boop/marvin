const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;

const CORS = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type'
};

const H = () => ({ apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` });

function getIP(event) {
  return (event.headers['x-nf-client-connection-ip'] ||
    (event.headers['x-forwarded-for'] || '').split(',')[0] || '').trim();
}

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

  const { action, name, visitorId } = body;
  const ip = getIP(event);

  if (!ip) return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'IP introuvable' }) };

  const existing = await sbGet(`free_trial_ips?ip=eq.${encodeURIComponent(ip)}&select=ip&limit=1`);
  const eligible = existing.length === 0;

  if (action === 'check') {
    return { statusCode: 200, headers: CORS, body: JSON.stringify({ eligible }) };
  }

  if (action === 'start') {
    if (!eligible) return { statusCode: 403, headers: CORS, body: JSON.stringify({ error: 'Offre déjà utilisée' }) };
    if (!name || !visitorId) return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Données manquantes' }) };

    try {
      const created = await sbPost('chat_sessions', {
        visitor_id: visitorId,
        status: 'waiting',
        pre_name: name,
        session_type: 'free',
        session_label: '10 min GRATUIT',
        duration_sec: 600,
        stripe_payment_id: null,
        visitor_ip: ip,
        loyalty_discount: 0
      });
      const session = Array.isArray(created) ? created[0] : created;
      if (!session?.id) throw new Error('Création session échouée');
      const sessionId = session.id;

      // Mark IP immediately (before agent assignment to avoid race condition)
      await sbPost('free_trial_ips', { ip, session_id: sessionId });

      // Trouver n'importe quel agent online ou busy avec < 3 sessions actives
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

      const greeting = assignedAgent
        ? `${agentPseudo || 'Un écoutant'} vous a rejoint. La session peut commencer.`
        : 'Votre demande est bien enregistrée. Un écoutant vous rejoindra dans les 2 prochaines minutes — restez bien en ligne !';
      await fetch(`${SB_URL}/rest/v1/chat_messages`, {
        method: 'POST',
        headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
        body: JSON.stringify({ session_id: sessionId, content: greeting, sender_type: 'system' })
      });

      const siteUrl = process.env.SITE_URL || 'https://parlonsecoute.fr';
      fetch(`${siteUrl}/.netlify/functions/push-notify`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          title: assignedAgent ? '🎁 Tchat gratuit assigné' : '🎁 Nouvelle conversation gratuite',
          message: `${name} attend (offre découverte 10 min)`,
          url: '/agent-app.html',
          ...(assignedAgent ? { agentEmail: assignedAgent } : {})
        })
      }).catch(() => {});

      return {
        statusCode: 200, headers: CORS,
        body: JSON.stringify({ ok: true, sessionId, agentAssigned: !!assignedAgent })
      };
    } catch (e) {
      console.error('free-session:', e.message);
      return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: e.message }) };
    }
  }

  return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Action invalide' }) };
};
