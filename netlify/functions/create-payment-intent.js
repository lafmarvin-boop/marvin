const stripe = require('stripe')(process.env.STRIPE_SECRET_KEY);

const VALID_AMOUNTS = { '200': 200, '500': 500, '900': 900, 'sub': 1500, 'group': 150 };

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type',
  'Content-Type': 'application/json',
};

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') {
    return { statusCode: 200, headers: CORS, body: '' };
  }
  if (event.httpMethod !== 'POST') {
    return { statusCode: 405, headers: CORS, body: JSON.stringify({ error: 'Method Not Allowed' }) };
  }
  if (!process.env.STRIPE_SECRET_KEY) {
    return { statusCode: 503, headers: CORS, body: JSON.stringify({ error: 'STRIPE_SECRET_KEY non configurée' }) };
  }

  try {
    const { montant, formule, pseudo, duree, email } = JSON.parse(event.body || '{}');

    const amountCents = VALID_AMOUNTS[String(montant)];
    if (!amountCents) {
      return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Montant invalide' }) };
    }
    if (!pseudo || pseudo.length > 50) {
      return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Pseudo invalide' }) };
    }

    const paymentIntent = await stripe.paymentIntents.create({
      amount: amountCents,
      currency: 'eur',
      automatic_payment_methods: { enabled: true, allow_redirects: 'never' },
      description: `Parlons - ${formule} - ${pseudo}`,
      metadata: { formule, pseudo, duree: String(duree || 1800), plateforme: 'parlons', ...(email ? { email } : {}) },
    });

    // Enregistrement optionnel dans Supabase
    if (process.env.SUPABASE_URL && process.env.SUPABASE_SERVICE_KEY) {
      await saveSessionToSupabase({
        stripe_payment_id: paymentIntent.id,
        client_pseudo: pseudo,
        formule,
        montant: amountCents / 100,
        statut: 'pending',
      });
    }

    return {
      statusCode: 200,
      headers: CORS,
      body: JSON.stringify({ clientSecret: paymentIntent.client_secret }),
    };
  } catch (err) {
    console.error('create-payment-intent:', err.message);
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: err.message }) };
  }
};

async function saveSessionToSupabase(data) {
  const res = await fetch(`${process.env.SUPABASE_URL}/rest/v1/sessions`, {
    method: 'POST',
    headers: {
      apikey: process.env.SUPABASE_SERVICE_KEY,
      Authorization: `Bearer ${process.env.SUPABASE_SERVICE_KEY}`,
      'Content-Type': 'application/json',
      Prefer: 'return=minimal',
    },
    body: JSON.stringify(data),
  });
  if (!res.ok) {
    const text = await res.text();
    console.error('Supabase insert error:', text);
  }
}
