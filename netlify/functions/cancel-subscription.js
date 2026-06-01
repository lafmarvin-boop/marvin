const stripe = require('stripe')(process.env.STRIPE_SECRET_KEY);
const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;

const CORS = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type',
};

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };

  let body;
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Invalid JSON' }; }

  const { email } = body;
  if (!email || !email.includes('@'))
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Email invalide' }) };

  // Récupérer l'abonné depuis Supabase
  const res = await fetch(
    `${SB_URL}/rest/v1/subscribers?email=eq.${encodeURIComponent(email.toLowerCase().trim())}&select=*&limit=1`,
    { headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` } }
  );
  const subs = await res.json();

  if (!Array.isArray(subs) || subs.length === 0)
    return { statusCode: 404, headers: CORS, body: JSON.stringify({ error: 'Abonnement introuvable' }) };

  const sub = subs[0];

  if (!sub.stripe_subscription_id)
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Aucun abonnement récurrent associé à ce compte' }) };

  if (sub.cancel_at_period_end) {
    const d = sub.expires_at ? new Date(sub.expires_at).toLocaleDateString('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' }) : '—';
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: `Résiliation déjà programmée — accès jusqu'au ${d}` }) };
  }

  // Résilier à la fin de la période dans Stripe
  const subscription = await stripe.subscriptions.update(sub.stripe_subscription_id, {
    cancel_at_period_end: true,
  });

  const cancelAt = new Date(subscription.cancel_at * 1000);

  // Mettre à jour Supabase
  await fetch(
    `${SB_URL}/rest/v1/subscribers?email=eq.${encodeURIComponent(email.toLowerCase().trim())}`,
    {
      method: 'PATCH',
      headers: {
        apikey: SB_KEY,
        Authorization: `Bearer ${SB_KEY}`,
        'Content-Type': 'application/json',
        Prefer: 'return=minimal',
      },
      body: JSON.stringify({ cancel_at_period_end: true }),
    }
  );

  return {
    statusCode: 200,
    headers: CORS,
    body: JSON.stringify({
      success: true,
      cancel_at: cancelAt.toISOString(),
      message: `Résiliation confirmée. Votre accès reste actif jusqu'au ${cancelAt.toLocaleDateString('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' })}.`,
    }),
  };
};
