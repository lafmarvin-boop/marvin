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

  const { sessionId, newDurationSec, paymentId, label } = body;
  if (!sessionId || !newDurationSec)
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Paramètres manquants' }) };

  try {
    // Charger la session en cours
    const sessions = await sbGet(
      `chat_sessions?id=eq.${encodeURIComponent(sessionId)}&select=status,duration_sec,agent_email,pre_name,stripe_payment_id&limit=1`
    );
    if (!sessions.length) return { statusCode: 404, headers: CORS, body: JSON.stringify({ error: 'Session introuvable' }) };
    const sess = sessions[0];
    if (sess.status !== 'active') return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Session non active' }) };

    const addSec = parseInt(newDurationSec) || 0;
    if (addSec <= 0) return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Durée invalide' }) };

    const newTotal = (sess.duration_sec || 1800) + addSec;

    // Prolonger la session
    await fetch(`${SB_URL}/rest/v1/chat_sessions?id=eq.${encodeURIComponent(sessionId)}`, {
      method: 'PATCH',
      headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
      body: JSON.stringify({ duration_sec: newTotal, stripe_payment_id: paymentId || sess.stripe_payment_id })
    });

    // Message système dans le tchat
    const mins = Math.floor(addSec / 60);
    await fetch(`${SB_URL}/rest/v1/chat_messages`, {
      method: 'POST',
      headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
      body: JSON.stringify({
        session_id: sessionId,
        content: `⏱ Session prolongée de ${mins} min. Continuez !`,
        sender_type: 'system'
      })
    });

    // Enregistrer le nouveau paiement dans sessions (stats)
    if (paymentId && paymentId.startsWith('pi_')) {
      const now = new Date().toISOString();
      const agentEmail = sess.agent_email || null;
      let agentName = null;
      if (agentEmail) {
        const profiles = await sbGet(`agent_profiles?email=eq.${encodeURIComponent(agentEmail)}&select=prenom,nom&limit=1`);
        const p = profiles[0];
        agentName = p && p.prenom ? `${p.prenom} ${p.nom || ''}`.trim() : agentEmail.split('@')[0];
      }
      await fetch(`${SB_URL}/rest/v1/sessions`, {
        method: 'POST',
        headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
        body: JSON.stringify({
          stripe_payment_id: paymentId,
          statut: 'paid',
          formule: label || 'Prolongation',
          client_pseudo: sess.pre_name || 'Visiteur',
          started_at: now,
          ...(agentEmail ? { agent_email: agentEmail, agent_name: agentName } : {})
        })
      });
    }

    return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, totalDurationSec: newTotal }) };
  } catch (e) {
    console.error('chat-extend:', e.message);
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: e.message }) };
  }
};
