const stripe = require('stripe')(process.env.STRIPE_SECRET_KEY);

const { durationForAmount } = require('./_plans.js');

// Sessions à l'unité — DÉSACTIVÉES (sept. 2026). L'offre se limite à la conversation de
// 20 minutes offerte et à l'abonnement mensuel illimité à 2 €. Masquer les boutons ne suffit
// pas : sans ce verrou côté serveur, un appel direct à l'API pourrait encore acheter une
// session à l'unité. Remettre `FORFAITS_UNITAIRES=on` dans les variables Netlify pour les
// réactiver — le reste du code est intact.
const FORFAITS_UNITAIRES = process.env.FORFAITS_UNITAIRES === 'on';

// Object.create(null) : évite qu'un montant "__proto__" ou "constructor" renvoie une propriété
// héritée du prototype (Object.prototype) au lieu de undefined.
const BASE_AMOUNTS  = FORFAITS_UNITAIRES
  ? Object.assign(Object.create(null), { '100': 100, '300': 300, '500': 500 })
  : Object.create(null);
const FIXED_AMOUNTS = Object.assign(Object.create(null), { 'sub': 200 });

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
    // `duree` n'est volontairement pas lu depuis le client : la durée est dérivée du montant (_plans.js)
    const { montant, formule, pseudo, email, visitorId } = JSON.parse(event.body || '{}');

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
      metadata: { formule, pseudo, duree: String(durationForAmount(montant)), plateforme: 'parlons', discount: String(effectiveDiscount), ...(visitorId ? { visitor_id: visitorId } : {}), ...(email ? { email } : {}) },
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
