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

  const { agentEmail, agentToken, subscription, action } = body;
  if (!agentEmail || !agentToken) return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Paramètres manquants' }) };

  // Vérifier le token
  const presence = await sbGet(`agent_presence?agent_email=eq.${encodeURIComponent(agentEmail)}&select=session_token&limit=1`);
  if (!presence.length || presence[0].session_token !== agentToken)
    return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Token invalide' }) };

  try {
    if (action === 'unsubscribe') {
      await fetch(`${SB_URL}/rest/v1/push_subscriptions?agent_email=eq.${encodeURIComponent(agentEmail)}`, {
        method: 'DELETE',
        headers: H()
      });
      return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true }) };
    }

    if (!subscription?.endpoint) return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Subscription manquante' }) };

    // Upsert par endpoint (un appareil = une souscription)
    await fetch(`${SB_URL}/rest/v1/push_subscriptions`, {
      method: 'POST',
      headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'resolution=merge-duplicates' },
      body: JSON.stringify({
        agent_email: agentEmail,
        endpoint: subscription.endpoint,
        subscription: JSON.stringify(subscription),
        updated_at: new Date().toISOString()
      })
    });

    return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true }) };
  } catch (e) {
    console.error('push-subscribe:', e.message);
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: e.message }) };
  }
};
