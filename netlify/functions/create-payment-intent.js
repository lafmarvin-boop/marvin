const stripe = require('stripe')(process.env.STRIPE_SECRET_KEY);

const BASE_AMOUNTS  = { '100': 100, '300': 300, '500': 500 };
const FIXED_AMOUNTS = { 'sub': 1500, 'group': 150 };

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type',
  'Content-Type': 'application/json',
};

function discountFromCount(count) {
  if (count >= 19) return 30;
  if (count >= 9)  return 20;
  if (count >= 4)  return 10;
  return 0;
}

async function getVerifiedDiscount(visitorId) {
  if (!visitorId || !/^[a-z0-9]+$/i.test(visitorId) || visitorId.length > 64) return 0;
  if (!process.env.SUPABASE_URL || !process.env.SUPABASE_SERVICE_KEY) return 0;
  try {
    // Seuls les paiements confirmés par le webhook Stripe ont statut='paid'
    const res = await fetch(
      `${process.env.SUPABASE_URL}/rest/v1/sessions?visitor_id=eq.${encodeURIComponent(visitorId)}&statut=eq.paid&select=id`,
      { headers: { apikey: process.env.SUPABASE_SERVICE_KEY, Authorization: `Bearer ${process.env.SUPABASE_SERVICE_KEY}` } }
    );
    if (!res.ok) return 0;
    const rows = await res.json();
    return discountFromCount(Array.isArray(rows) ? rows.length : 0);
  } catch {
    return 0;
  }
}

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
    const { montant, formule, pseudo, duree, email, visitorId } = JSON.parse(event.body || '{}');

    let amountCents;
    let effectiveDiscount = 0;
    if (FIXED_AMOUNTS[String(montant)] !== undefined) {
      amountCents = FIXED_AMOUNTS[String(montant)];
    } else if (BASE_AMOUNTS[String(montant)] !== undefined) {
      const base = BASE_AMOUNTS[String(montant)];
      effectiveDiscount = await getVerifiedDiscount(visitorId);
      amountCents = Math.round(base * (1 - effectiveDiscount / 100));
    } else {
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
      metadata: { formule, pseudo, duree: String(duree || 1800), plateforme: 'parlons', discount: String(effectiveDiscount), ...(visitorId ? { visitor_id: visitorId } : {}), ...(email ? { email } : {}) },
    });

    // Enregistrement optionnel dans Supabase
    if (process.env.SUPABASE_URL && process.env.SUPABASE_SERVICE_KEY) {
      await saveSessionToSupabase({
        stripe_payment_id: paymentIntent.id,
        client_pseudo: pseudo,
        formule,
        montant: amountCents / 100,
        statut: 'pending',
        visitor_id: visitorId || null,
      });
    }

    return {
      statusCode: 200,
      headers: CORS,
      body: JSON.stringify({ clientSecret: paymentIntent.client_secret }),
    };
  } catch (err) {
    console.error('create-payment-intent:', err.message);
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: 'Erreur serveur' }) };
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
