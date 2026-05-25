const stripe = require('stripe')(process.env.STRIPE_SECRET_KEY);
const crypto = require('crypto');

exports.handler = async (event) => {
  if (event.httpMethod !== 'POST') {
    return { statusCode: 405, body: 'Method Not Allowed' };
  }

  const sig = event.headers['stripe-signature'];
  if (!sig || !process.env.STRIPE_WEBHOOK_SECRET) {
    console.warn('Webhook: signature ou secret manquant');
    return { statusCode: 400, body: 'Configuration webhook manquante' };
  }

  let webhookEvent;
  try {
    const rawBody = Buffer.from(event.body, event.isBase64Encoded ? 'base64' : 'utf8');
    webhookEvent = stripe.webhooks.constructEvent(rawBody, sig, process.env.STRIPE_WEBHOOK_SECRET);
  } catch (err) {
    console.error('Webhook signature invalide:', err.message);
    return { statusCode: 400, body: `Webhook Error: ${err.message}` };
  }

  if (webhookEvent.type === 'payment_intent.succeeded') {
    const pi = webhookEvent.data.object;
    console.log('Paiement confirmé:', pi.id, pi.metadata);

    if (process.env.SUPABASE_URL && process.env.SUPABASE_SERVICE_KEY) {
      const token = generateToken();
      const duree = parseInt(pi.metadata.duree || '1800');
      const now = new Date();
      const ends = new Date(now.getTime() + duree * 1000);

      await patchSession(pi.id, {
        statut: 'paid',
        token,
        started_at: now.toISOString(),
        ends_at: ends.toISOString(),
      });

      // Si c'est un Pass mensuel avec un email : créer/renouveler l'abonné
      if (pi.metadata.formule === 'Pass mensuel' && pi.metadata.email) {
        const expires_at = new Date(now.getTime() + 30 * 24 * 60 * 60 * 1000).toISOString();
        await upsertSubscriber({
          email: pi.metadata.email.toLowerCase().trim(),
          pseudo: pi.metadata.pseudo || null,
          stripe_payment_id: pi.id,
          status: 'active',
          expires_at,
        });
      }
    }
  }

  if (webhookEvent.type === 'payment_intent.payment_failed') {
    const pi = webhookEvent.data.object;
    console.log('Paiement échoué:', pi.id);

    if (process.env.SUPABASE_URL && process.env.SUPABASE_SERVICE_KEY) {
      await patchSession(pi.id, { statut: 'failed' });
    }
  }

  return { statusCode: 200, body: JSON.stringify({ received: true }) };
};

function generateToken() {
  return crypto.randomBytes(32).toString('hex');
}

async function upsertSubscriber(data) {
  const res = await fetch(`${process.env.SUPABASE_URL}/rest/v1/subscribers`, {
    method: 'POST',
    headers: {
      apikey: process.env.SUPABASE_SERVICE_KEY,
      Authorization: `Bearer ${process.env.SUPABASE_SERVICE_KEY}`,
      'Content-Type': 'application/json',
      Prefer: 'resolution=merge-duplicates,return=minimal',
    },
    body: JSON.stringify(data),
  });
  if (!res.ok) console.error('upsertSubscriber error:', await res.text());
}

async function patchSession(stripePaymentId, patch) {
  const res = await fetch(
    `${process.env.SUPABASE_URL}/rest/v1/sessions?stripe_payment_id=eq.${stripePaymentId}`,
    {
      method: 'PATCH',
      headers: {
        apikey: process.env.SUPABASE_SERVICE_KEY,
        Authorization: `Bearer ${process.env.SUPABASE_SERVICE_KEY}`,
        'Content-Type': 'application/json',
        Prefer: 'return=minimal',
      },
      body: JSON.stringify(patch),
    }
  );
  if (!res.ok) {
    const text = await res.text();
    console.error('Supabase patch error:', text);
  }
}
