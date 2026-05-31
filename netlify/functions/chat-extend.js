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

  const { sessionId, newDurationSec, paymentId, label, remainingSec } = body;
  if (!sessionId || !newDurationSec)
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Paramètres manquants' }) };

  try {
    const sessions = await sbGet(
      `chat_sessions?id=eq.${encodeURIComponent(sessionId)}&select=status,agent_email,pre_name&limit=1`
    );
    if (!sessions.length) return { statusCode: 404, headers: CORS, body: JSON.stringify({ error: 'Session introuvable' }) };
    const sess = sessions[0];
    if (sess.status !== 'active') return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Session non active' }) };

    const addSec = parseInt(newDurationSec) || 0;
    if (addSec <= 0) return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Durée invalide' }) };

    const remaining = parseInt(remainingSec) || 0;
    const totalForNew = addSec + remaining;
    const mins = Math.floor(addSec / 60);

    // Stocker la demande de prolongation dans la session (sans étendre encore)
    await fetch(`${SB_URL}/rest/v1/chat_sessions?id=eq.${encodeURIComponent(sessionId)}`, {
      method: 'PATCH',
      headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
      body: JSON.stringify({
        extension_pending: {
          newDurationSec: addSec,
          remainingSec: remaining,
          totalForNewSession: totalForNew,
          paymentId: paymentId || null,
          label: label || null,
          requestedAt: new Date().toISOString()
        }
      })
    });

    // Message système dans le tchat courant
    await fetch(`${SB_URL}/rest/v1/chat_messages`, {
      method: 'POST',
      headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
      body: JSON.stringify({
        session_id: sessionId,
        content: `⏳ Le visiteur souhaite prolonger la session de ${mins} min. En attente de votre accord.`,
        sender_type: 'system'
      })
    });

    return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, pending: true }) };
  } catch (e) {
    console.error('chat-extend:', e.message);
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: e.message }) };
  }
};
