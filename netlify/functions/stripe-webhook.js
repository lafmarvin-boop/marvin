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

  const type = webhookEvent.type;
  console.log('Webhook reçu:', type);

  // ── Paiement unique (sessions à la carte) ──
  if (type === 'payment_intent.succeeded') {
    const pi = webhookEvent.data.object;
    // Ignorer les PaymentIntents liés à des abonnements (ils sont gérés par invoice.payment_succeeded)
    if (pi.invoice) return { statusCode: 200, body: JSON.stringify({ received: true }) };

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
    }
  }

  if (type === 'payment_intent.payment_failed') {
    const pi = webhookEvent.data.object;
    if (pi.invoice) return { statusCode: 200, body: JSON.stringify({ received: true }) };

    if (process.env.SUPABASE_URL && process.env.SUPABASE_SERVICE_KEY) {
      await patchSession(pi.id, { statut: 'failed' });
    }
  }

  // ── Abonnement : paiement réussi (premier paiement ou renouvellement) ──
  if (type === 'invoice.payment_succeeded') {
    const invoice = webhookEvent.data.object;
    if (!invoice.subscription) return { statusCode: 200, body: JSON.stringify({ received: true }) };

    try {
      const subscription = await stripe.subscriptions.retrieve(invoice.subscription);
      const customer = await stripe.customers.retrieve(subscription.customer);
      const email = customer.email || subscription.metadata?.email;
      if (!email) return { statusCode: 200, body: JSON.stringify({ received: true }) };

      const expires_at = new Date(subscription.current_period_end * 1000).toISOString();

      await upsertSubscriber({
        email: email.toLowerCase().trim(),
        pseudo: subscription.metadata?.pseudo || customer.name || null,
        stripe_customer_id: subscription.customer,
        stripe_subscription_id: subscription.id,
        status: 'active',
        expires_at,
        cancel_at_period_end: subscription.cancel_at_period_end || false,
      });

      console.log('Abonné activé/renouvelé:', email, 'jusqu\'au', expires_at);
    } catch (err) {
      console.error('invoice.payment_succeeded error:', err.message);
    }
  }

  // ── Abonnement : paiement de renouvellement échoué ──
  if (type === 'invoice.payment_failed') {
    const invoice = webhookEvent.data.object;
    if (!invoice.subscription) return { statusCode: 200, body: JSON.stringify({ received: true }) };

    try {
      const subscription = await stripe.subscriptions.retrieve(invoice.subscription);
      const customer = await stripe.customers.retrieve(subscription.customer);
      const email = customer.email || subscription.metadata?.email;
      if (email) {
        await patchSubscriberByEmail(email.toLowerCase().trim(), { status: 'payment_failed' });
        console.log('Renouvellement échoué pour:', email);
      }
    } catch (err) {
      console.error('invoice.payment_failed error:', err.message);
    }
  }

  // ── Abonnement : résilié définitivement (après cancel_at_period_end) ──
  if (type === 'customer.subscription.deleted') {
    const subscription = webhookEvent.data.object;
    try {
      const customer = await stripe.customers.retrieve(subscription.customer);
      const email = customer.email || subscription.metadata?.email;
      if (email) {
        await patchSubscriberByEmail(email.toLowerCase().trim(), {
          status: 'cancelled',
          cancel_at_period_end: false,
        });
        console.log('Abonnement résilié:', email);
      }
    } catch (err) {
      console.error('subscription.deleted error:', err.message);
    }
  }

  // ── Abonnement : mis à jour (ex: annulation programmée) ──
  if (type === 'customer.subscription.updated') {
    const subscription = webhookEvent.data.object;
    try {
      const customer = await stripe.customers.retrieve(subscription.customer);
      const email = customer.email || subscription.metadata?.email;
      if (email) {
        await patchSubscriberByEmail(email.toLowerCase().trim(), {
          cancel_at_period_end: subscription.cancel_at_period_end || false,
          expires_at: new Date(subscription.current_period_end * 1000).toISOString(),
        });
      }
    } catch (err) {
      console.error('subscription.updated error:', err.message);
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

async function patchSubscriberByEmail(email, patch) {
  const res = await fetch(
    `${process.env.SUPABASE_URL}/rest/v1/subscribers?email=eq.${encodeURIComponent(email)}`,
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
  if (!res.ok) console.error('patchSubscriber error:', await res.text());
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
  if (!res.ok) console.error('Supabase patch error:', await res.text());
}
