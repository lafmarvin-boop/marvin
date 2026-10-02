const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;
const { OFFRE_MESSAGE } = require('./_refund');
const AI_EMAIL = 'claude@parlonsecoute.fr'; // Max, assistant d'écoute IA (ai-reply.js)

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
    const sessions = await sbGet(`chat_sessions?id=eq.${encodeURIComponent(sessionId)}&select=agent_email,status,stripe_payment_id,session_type,session_label,pre_name,assigned_at,visitor_id,duration_sec&limit=1`);
    if (!sessions.length) return { statusCode: 404, headers: CORS, body: JSON.stringify({ error: 'Session introuvable' }) };
    if (sessions[0].status === 'closed') return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true }) };

    // Vérifier le token si c'est l'agent qui ferme
    if (closedBy === 'agent') {
      if (!agentToken) return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Token requis' }) };
      const presence = await sbGet(`agent_presence?agent_email=eq.${encodeURIComponent(agentEmail)}&select=session_token&limit=1`);
      if (!presence.length || presence[0].session_token !== agentToken)
        return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Token invalide' }) };
    } else if (closedBy !== 'sweep') {
      // Fermeture par le visiteur : le sessionId seul ne prouve rien (il peut fuiter par un lien,
      // un historique ou un log). On exige aussi le visitor_id, qui n'est jamais renvoyé par l'API
      // et qui reste dans le navigateur du visiteur. Sans cela, un tiers pourrait fermer la session
      // d'autrui et, sur une session tenue par Max, déclencher un remboursement Stripe.
      const claimed = String(body.visitorId || '');
      if (!claimed || !sessions[0].visitor_id || claimed !== sessions[0].visitor_id)
        return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Non autorisé' }) };
    }

    const now = new Date().toISOString();
    const updates = { status: 'closed', closed_at: now };

    await sbPatch(`chat_sessions?id=eq.${encodeURIComponent(sessionId)}`, updates);

    // Libérer l'agent — mais seulement si c'est sa dernière session active
    const chatSession = sessions[0];
    const agentMail = chatSession.agent_email;
    if (agentMail) {
      const remaining = await sbGet(
        `chat_sessions?agent_email=eq.${encodeURIComponent(agentMail)}&status=eq.active&id=neq.${encodeURIComponent(sessionId)}&select=id&limit=3`
      );
      const presenceUpdate = remaining.length > 0
        ? { status: 'busy', current_session_id: remaining[0].id }
        : { status: 'online', current_session_id: null };
      await sbPatch(`agent_presence?agent_email=eq.${encodeURIComponent(agentMail)}`, presenceUpdate);

      // ── Enregistrer la session dans la table sessions (stats agent/admin) ──
      if (chatSession.session_type !== 'test') {
        const profiles = await sbGet(`agent_profiles?email=eq.${encodeURIComponent(agentMail)}&select=prenom,nom&limit=1`);
        const profile = profiles[0];
        const agentName = agentMail === AI_EMAIL ? 'Max (IA)'
          : profile && profile.prenom ? `${profile.prenom} ${profile.nom || ''}`.trim() : agentMail.split('@')[0];

        const sessionUpdate = {
          statut: 'paid',
          agent_email: agentMail,
          agent_name: agentName,
          resolved_at: now,
          ...(rating ? { rating: parseInt(rating), rating_comment: typeof ratingComment === 'string' ? ratingComment.trim().slice(0, 500) : null } : {})
        };

        const stripeId = chatSession.stripe_payment_id;
        if (stripeId && stripeId.startsWith('pi_')) {
          // Session à la carte : mettre à jour la ligne existante créée par Stripe
          await fetch(`${SB_URL}/rest/v1/sessions?stripe_payment_id=eq.${encodeURIComponent(stripeId)}`, {
            method: 'PATCH',
            headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
            body: JSON.stringify(sessionUpdate)
          });
        } else {
          // Abonnement : insérer une nouvelle ligne (pas de paiement Stripe individuel)
          await fetch(`${SB_URL}/rest/v1/sessions`, {
            method: 'POST',
            headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
            body: JSON.stringify({
              statut: 'paid',
              formule: chatSession.session_label || chatSession.session_type || 'Session',
              montant: 0,
              client_pseudo: chatSession.pre_name || 'Visiteur',
              started_at: chatSession.assigned_at || now,
              ...sessionUpdate
            })
          });
        }
      }
    }

    // ── Engagement Max : le visiteur allé au bout de sa session (closedBy 'timer') sans qu'un
    //    écoutant humain l'ait rejoint a droit au remboursement intégral — mais il doit le
    //    **demander** : on lui propose ici, refund-request.js le traite en un clic. Page fermée /
    //    abandon (balayage) → aucun droit, comme avec un écoutant humain.
    //    C'est le seul endroit qui sait distinguer les deux cas : le message d'offre écrit
    //    ci-dessous est donc ce qui atteste l'éligibilité par la suite.
    let refundOffer = false;
    // Deuxième garde, indépendante de l'authentification : le remboursement n'est dû que si la
    // session est arrivée à son terme. Sans cela, un visiteur légitime pourrait payer puis appeler
    // immédiatement la fermeture pour être remboursé tout en ayant parlé avec Max.
    const startedAt = chatSession.assigned_at ? new Date(chatSession.assigned_at).getTime() : 0;
    const sessionOver = startedAt > 0 &&
      Date.now() >= startedAt + (chatSession.duration_sec || 1800) * 1000 - 60000;
    if (agentMail === AI_EMAIL && closedBy === 'timer' && sessionOver) {
      const pi = chatSession.stripe_payment_id;
      if (pi && pi.startsWith('pi_') && process.env.STRIPE_SECRET_KEY) {
        // Proposition, pas versement : aucun appel à Stripe ici.
        refundOffer = true;
        await fetch(`${SB_URL}/rest/v1/chat_messages`, {
          method: 'POST', headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
          body: JSON.stringify({ session_id: sessionId, content: OFFRE_MESSAGE, sender_type: 'system' })
        });
      } else if (chatSession.session_type === 'sub' || (chatSession.session_label || '').includes('Pass mensuel')) {
        await fetch(`${SB_URL}/rest/v1/chat_messages`, {
          method: 'POST', headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
          body: JSON.stringify({ session_id: sessionId, content: 'Aucun écoutant n\'a pu vous rejoindre pendant cette session. Elle n\'est pas décomptée de votre pass mensuel : vous pouvez en relancer une à tout moment. Merci de votre patience.', sender_type: 'system' })
        });
      }
    }

    return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, refundOffer }) };
  } catch (e) {
    console.error('chat-close:', e.message);
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: e.message }) };
  }
};
