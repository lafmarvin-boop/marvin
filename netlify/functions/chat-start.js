const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;
const AI_EMAIL = 'claude@parlonsecoute.fr'; // identité de Max, l'assistant d'écoute IA (voir ai-reply.js)
const RESEND_KEY = process.env.RESEND_API_KEY;
const FROM_EMAIL = process.env.FROM_EMAIL || 'Parlons <noreply@parlonsecoute.fr>';

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

    // Aucun écoutant humain disponible + session payante → Max (assistant IA) prend le relais
    let aiAssigned = false;
    if (!assignedAgent && (sessionType || 'paid') !== 'free' && process.env.ANTHROPIC_API_KEY) {
      const now = new Date().toISOString();
      await sbPatch(`chat_sessions?id=eq.${encodeURIComponent(sessionId)}`, {
        agent_email: AI_EMAIL, status: 'active', assigned_at: now, response_deadline: null
      });
      const post = (content, sender_type) => fetch(`${SB_URL}/rest/v1/chat_messages`, {
        method: 'POST', headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
        body: JSON.stringify({ session_id: sessionId, content, sender_type })
      });
      await post('Aucun écoutant n\'est connecté à cet instant. Max, l\'assistant d\'écoute de Parlons (une intelligence artificielle), engage la conversation avec vous et alerte par email tous nos écoutants disponibles. Dès que l\'un d\'eux se connecte, il reprend l\'échange avec tout l\'historique. Si vous allez au bout de votre session sans qu\'un écoutant vous ait rejoint, elle vous est intégralement remboursée.', 'system');
      await post(`Bonjour ${name}, je suis Max, l'assistant d'écoute de Parlons. Je viens d'alerter nos écoutants pour que l'un d'eux vous rejoigne, et je suis là avec vous dès maintenant, sans jugement et en toute confidentialité. Qu'est-ce qui vous donne envie de parler aujourd'hui ?`, 'agent');
      aiAssigned = true;

      // Alerter par email tous les écoutants qui ont activé « Recevoir les demandes d'écoutant » (fire-and-forget)
      if (RESEND_KEY) {
        (async () => {
          const agents = await sbGet('agent_profiles?notify_requests=eq.true&notify_email=not.is.null&select=notify_email,email');
          const targets = [...new Set(agents.map(a => a.notify_email || a.email).filter(Boolean))];
          const siteUrl = process.env.SITE_URL || process.env.URL || 'https://parlonsecoute.fr';
          const html = `<p style="font-family:sans-serif">Un visiteur vient de démarrer une <strong>session payante</strong> et personne n'est connecté : Max (assistant IA) engage la conversation en attendant un écoutant.</p>
<p style="font-family:sans-serif"><strong>Prénom :</strong> ${String(name).replace(/[<>&]/g, '')}<br><strong>Formule :</strong> ${String(sessionLabel || 'session').replace(/[<>&]/g, '')}<br><strong>Heure :</strong> ${new Date().toLocaleString('fr-FR', { timeZone: 'Europe/Paris' })}</p>
<p><a href="${siteUrl}/agent-app.html" style="display:inline-block;background:#C4714A;color:white;text-decoration:none;padding:.65rem 1.5rem;border-radius:50px;font-weight:700">Me connecter et prendre le relais →</a></p>
<p style="font-size:.8rem;color:#888;font-family:sans-serif">Si le visiteur va au bout de sa session sans écoutant, il est remboursé automatiquement. Vous recevez cet email car vous avez activé les demandes d'écoutant dans votre profil.</p>`;
          await Promise.all(targets.map(to => fetch('https://api.resend.com/emails', {
            method: 'POST', headers: { Authorization: `Bearer ${RESEND_KEY}`, 'Content-Type': 'application/json' },
            body: JSON.stringify({ from: FROM_EMAIL, to, subject: `🚨 ${name} attend un écoutant — session payante en cours avec Max`, html })
          })));
        })().catch(e => console.error('chat-start alert email:', e.message));
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

    // Message système initial (le cas IA a déjà inséré les siens)
    if (!aiAssigned) {
      const greeting = agentPseudo
        ? `${agentPseudo} vous a rejoint. La session peut commencer.`
        : 'Un écoutant vous a rejoint. La session peut commencer.';
      await fetch(`${SB_URL}/rest/v1/chat_messages`, {
        method: 'POST',
        headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
        body: JSON.stringify({
          session_id: sessionId,
          content: assignedAgent ? greeting : 'Votre demande est bien enregistrée. Un écoutant vous rejoindra dès que possible — restez bien en ligne !',
          sender_type: 'system'
        })
      });
    }

    // Push notification : à l'agent assigné, ou à tous si personne n'était disponible
    const siteUrl = process.env.SITE_URL || process.env.URL || 'https://parlonsecoute.fr';
    fetch(`${siteUrl}/.netlify/functions/push-notify`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        title: assignedAgent ? '💬 Nouveau tchat assigné' : aiAssigned ? '🚨 Visiteur payant avec Max — un écoutant est attendu' : '💬 Nouveau tchat en attente',
        message: aiAssigned ? `${name} parle avec Max (IA) : connectez-vous pour prendre le relais (remboursé si personne ne vient)` : `${name} attend votre aide`,
        url: '/agent-app.html',
        ...(assignedAgent ? { agentEmail: assignedAgent } : {})
      })
    }).catch(() => {});

    return {
      statusCode: 200, headers: CORS,
      body: JSON.stringify({ sessionId, status: (assignedAgent || aiAssigned) ? 'active' : 'waiting', agentAssigned: !!assignedAgent || aiAssigned, ai: aiAssigned })
    };
  } catch (e) {
    console.error('chat-start:', e.message);
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: e.message }) };
  }
};
