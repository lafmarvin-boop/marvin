const stripe = require('stripe')(process.env.STRIPE_SECRET_KEY);
const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;

const CORS = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type'
};

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };

  let body;
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Invalid JSON' }; }

  const { pseudo, room_id, payment_intent_id } = body;
  if (!pseudo || !room_id || !payment_intent_id)
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Paramètres manquants' }) };

  // Verify payment with Stripe
  let pi;
  try {
    pi = await stripe.paymentIntents.retrieve(payment_intent_id);
  } catch (e) {
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'PaymentIntent invalide' }) };
  }

  if (pi.status !== 'succeeded')
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: `Paiement non confirmé: ${pi.status}` }) };
  if (pi.amount !== 150 || pi.currency !== 'eur')
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Montant inattendu' }) };

  const paid_until = new Date(Date.now() + 30 * 60 * 1000).toISOString();

  await fetch(
    `${SB_URL}/rest/v1/group_access?room_id=eq.${encodeURIComponent(room_id)}&pseudo=eq.${encodeURIComponent(pseudo)}`,
    {
      method: 'PATCH',
      headers: {
        apikey: SB_KEY,
        Authorization: `Bearer ${SB_KEY}`,
        'Content-Type': 'application/json',
        Prefer: 'return=minimal'
      },
      body: JSON.stringify({ paid_until, payment_intent_id })
    }
  );

  return { statusCode: 200, headers: CORS, body: JSON.stringify({ paid_until }) };
};
