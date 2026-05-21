const stripe = require('stripe')(process.env.STRIPE_SECRET_KEY);

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
    const { client, session, montant, attente, paymentId } = JSON.parse(event.body || '{}');

    // Vérification : délai >= 120 secondes pour le remboursement automatique
    const delaiOk = typeof attente === 'number' && attente >= 120;

    let rembourse = false;
    let refundId = null;

    if (delaiOk && paymentId && process.env.STRIPE_SECRET_KEY) {
      try {
        // Récupère le PaymentIntent pour obtenir le charge_id
        const pi = await stripe.paymentIntents.retrieve(paymentId);
        if (pi.status === 'succeeded' && pi.latest_charge) {
          const refund = await stripe.refunds.create({
            charge: pi.latest_charge,
            reason: 'requested_by_customer',
            metadata: {
              motif: 'agent_lent',
              client,
              session,
              attente_secondes: String(attente),
            },
          });
          rembourse = refund.status === 'succeeded' || refund.status === 'pending';
          refundId = refund.id;
        }
      } catch (stripeErr) {
        console.error('Stripe refund error:', stripeErr.message);
      }
    }

    // Enregistrement Supabase
    if (process.env.SUPABASE_URL && process.env.SUPABASE_SERVICE_KEY) {
      await saveSignalement({ client, session, attente, rembourse, paymentId });
    }

    // Log admin (visible dans les logs Netlify)
    console.log(`SIGNALEMENT — client: ${client}, session: ${session}, attente: ${attente}s, remboursé: ${rembourse}, refundId: ${refundId}`);

    return {
      statusCode: 200,
      headers: CORS,
      body: JSON.stringify({ rembourse, refundId, delaiOk }),
    };
  } catch (err) {
    console.error('report-slow-agent:', err.message);
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: err.message }) };
  }
};

async function saveSignalement(data) {
  const res = await fetch(`${process.env.SUPABASE_URL}/rest/v1/signalements`, {
    method: 'POST',
    headers: {
      apikey: process.env.SUPABASE_SERVICE_KEY,
      Authorization: `Bearer ${process.env.SUPABASE_SERVICE_KEY}`,
      'Content-Type': 'application/json',
      Prefer: 'return=minimal',
    },
    body: JSON.stringify({
      client_pseudo: data.client,
      formule: data.session,
      delai_attente: data.attente,
      remboursement_effectue: data.rembourse,
      stripe_payment_id: data.paymentId,
    }),
  });
  if (!res.ok) console.error('Supabase signalement error:', await res.text());
}
