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

  const { paymentMethodId, email, pseudo } = body;

  if (!paymentMethodId) return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'paymentMethodId manquant' }) };
  if (!email || !email.includes('@')) return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Email invalide' }) };
  if (!process.env.STRIPE_PRICE_ID) return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: 'Configuration manquante: STRIPE_PRICE_ID' }) };

  try {
    // 1. Créer ou récupérer le client Stripe
    const existing = await stripe.customers.list({ email: email.toLowerCase().trim(), limit: 1 });
    let customer = existing.data[0] || await stripe.customers.create({
      email: email.toLowerCase().trim(),
      name: pseudo || undefined,
      metadata: { pseudo: pseudo || '', plateforme: 'parlons' },
    });

    // 2. Attacher la carte au client
    await stripe.paymentMethods.attach(paymentMethodId, { customer: customer.id });
    await stripe.customers.update(customer.id, {
      invoice_settings: { default_payment_method: paymentMethodId },
    });

    // 3. Créer l'abonnement récurrent
    const subscription = await stripe.subscriptions.create({
      customer: customer.id,
      items: [{ price: process.env.STRIPE_PRICE_ID }],
      payment_settings: {
        payment_method_types: ['card'],
        save_default_payment_method: 'on_subscription',
      },
      expand: ['latest_invoice.payment_intent'],
      metadata: { email: email.toLowerCase().trim(), pseudo: pseudo || '', plateforme: 'parlons' },
    });

    const paymentIntent = subscription.latest_invoice?.payment_intent;

    // 4. Pré-enregistrer l'abonné dans Supabase (sera activé par le webhook invoice.payment_succeeded)
    if (SB_URL && SB_KEY) {
      const expires_at = new Date(subscription.current_period_end * 1000).toISOString();
      await fetch(`${SB_URL}/rest/v1/subscribers`, {
        method: 'POST',
        headers: {
          apikey: SB_KEY,
          Authorization: `Bearer ${SB_KEY}`,
          'Content-Type': 'application/json',
          Prefer: 'resolution=merge-duplicates,return=minimal',
        },
        body: JSON.stringify({
          email: email.toLowerCase().trim(),
          pseudo: pseudo || null,
          stripe_customer_id: customer.id,
          stripe_subscription_id: subscription.id,
          status: 'pending',
          expires_at,
          cancel_at_period_end: false,
        }),
      });
    }

    return {
      statusCode: 200,
      headers: CORS,
      body: JSON.stringify({
        subscriptionId: subscription.id,
        clientSecret: paymentIntent?.client_secret || null,
        status: subscription.status,
      }),
    };
  } catch (err) {
    console.error('create-subscription error:', err.message);
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: err.message }) };
  }
};
