const stripe = require('stripe')(process.env.STRIPE_SECRET_KEY);
const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;

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

  try {
    const { client, session, paymentId } = JSON.parse(event.body || '{}');

    if (!paymentId || !process.env.STRIPE_SECRET_KEY) {
      return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Paramètres manquants' }) };
    }

    let rembourse = false;
    let refundId = null;
    let delaiOk = false;

    try {
      // Vérification côté serveur : récupérer le PI depuis Stripe (source de vérité)
      const pi = await stripe.paymentIntents.retrieve(paymentId);

      if (pi.status !== 'succeeded' || !pi.latest_charge) {
        return { statusCode: 200, headers: CORS, body: JSON.stringify({ rembourse: false, delaiOk: false }) };
      }

      // Vérification du délai côté serveur : temps écoulé depuis le paiement
      const elapsedSec = Math.floor(Date.now() / 1000) - pi.created;
      delaiOk = elapsedSec >= 120;

      if (delaiOk && SB_URL && SB_KEY) {
        // Ne pas rembourser si un agent a déjà été assigné (il a donc répondu)
        const checkRes = await fetch(
          `${SB_URL}/rest/v1/sessions?stripe_payment_id=eq.${encodeURIComponent(paymentId)}&agent_email=not.is.null&select=id&limit=1`,
          { headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` } }
        );
        const assigned = await checkRes.json();
        if (Array.isArray(assigned) && assigned.length > 0) {
          delaiOk = false;
        }
      }

      if (delaiOk) {
        const refund = await stripe.refunds.create({
          charge: pi.latest_charge,
          reason: 'requested_by_customer',
          metadata: { motif: 'agent_lent', client, session, elapsed_sec: String(elapsedSec) },
        });
        rembourse = refund.status === 'succeeded' || refund.status === 'pending';
        refundId = refund.id;
      }
    } catch (stripeErr) {
      console.error('Stripe error:', stripeErr.message);
    }

    if (SB_URL && SB_KEY) {
      await saveSignalement({ client, session, rembourse, paymentId, elapsedSec });
    }

    return {
      statusCode: 200,
      headers: CORS,
      body: JSON.stringify({ rembourse, refundId, delaiOk }),
    };
  } catch (err) {
    console.error('report-slow-agent:', err.message);
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: 'Erreur serveur' }) };
  }
};

async function saveSignalement(data) {
  const res = await fetch(`${SB_URL}/rest/v1/signalements`, {
    method: 'POST',
    headers: {
      apikey: SB_KEY,
      Authorization: `Bearer ${SB_KEY}`,
      'Content-Type': 'application/json',
      Prefer: 'return=minimal',
    },
    body: JSON.stringify({
      client_pseudo: data.client,
      formule: data.session,
      remboursement_effectue: data.rembourse,
      stripe_payment_id: data.paymentId,
      delai_attente: data.elapsedSec != null ? Math.floor(data.elapsedSec) : null,
    }),
  });
  if (!res.ok) console.error('Supabase signalement error:', await res.text());
}
