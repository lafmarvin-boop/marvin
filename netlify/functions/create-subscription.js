const stripe = require('stripe')(process.env.STRIPE_SECRET_KEY);
const crypto = require('crypto');
const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;
const SITE_URL = process.env.SITE_URL || 'https://parlonsecoute.fr';

const CORS = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type',
};

function generatePassword() {
  const chars = 'ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789';
  let pwd = '';
  for (let i = 0; i < 10; i++) pwd += chars[Math.floor(Math.random() * chars.length)];
  return pwd;
}

function hashPassword(password, salt) {
  return crypto.pbkdf2Sync(password, salt, 100000, 64, 'sha512').toString('hex');
}

async function sendWelcomeEmail(email, pseudo, password) {
  if (!process.env.RESEND_API_KEY) { console.warn('RESEND_API_KEY manquant, email non envoyé'); return; }
  const from = process.env.FROM_EMAIL || 'Parlons <noreply@parlons.fr>';
  const html = `<!DOCTYPE html><html lang="fr"><head><meta charset="UTF-8"></head>
<body style="margin:0;padding:0;background:#FBF6EF;font-family:Arial,sans-serif;">
<div style="max-width:520px;margin:0 auto;padding:2rem 1rem;">
  <h1 style="font-family:Georgia,serif;color:#C4714A;text-align:center;margin-bottom:.3rem;">Parlons</h1>
  <p style="text-align:center;color:#B09070;font-size:.8rem;margin-top:0;">Écoute & Soutien</p>
  <div style="background:white;border-radius:16px;padding:2rem;border:1px solid #E8D5B0;margin-top:1.5rem;">
    <h2 style="font-family:Georgia,serif;color:#2C1F14;margin-top:0;">Bienvenue, ${pseudo || 'cher abonné'} !</h2>
    <p style="color:#7A5C42;line-height:1.7;">Votre <strong>Pass mensuel Parlons</strong> est maintenant actif. Vous pouvez démarrer des sessions illimitées de 30 minutes sans repayer.</p>
    <div style="background:#F5EDE0;border-radius:12px;padding:1.2rem 1.4rem;margin:1.4rem 0;border-left:3px solid #C4714A;">
      <p style="margin:0 0 .6rem;font-size:.85rem;color:#7A5C42;"><strong>Email :</strong> ${email}</p>
      <p style="margin:0;font-size:.85rem;color:#7A5C42;"><strong>Mot de passe :</strong> <span style="background:white;padding:.25rem .7rem;border-radius:6px;font-family:monospace;font-size:1rem;color:#2C1F14;border:1px solid #E8D5B0;letter-spacing:.05em;">${password}</span></p>
    </div>
    <a href="${SITE_URL}/espace.html" style="display:block;background:#C4714A;color:white;text-decoration:none;text-align:center;padding:.9rem;border-radius:50px;font-weight:600;font-size:.95rem;margin-bottom:1.2rem;">Accéder à mon espace →</a>
    <p style="font-size:.78rem;color:#B09070;text-align:center;line-height:1.6;margin:0;">
      Vous pouvez modifier votre mot de passe depuis votre espace abonné.<br>
      Renouvellement automatique chaque mois · Résiliable à tout moment.
    </p>
  </div>
  <p style="font-size:.72rem;color:#B09070;text-align:center;margin-top:1rem;">
    Ce service n'est pas un service médical. En cas d'urgence : <strong>3114</strong> · <strong>15</strong> · <strong>112</strong>
  </p>
</div>
</body></html>`;

  try {
    const res = await fetch('https://api.resend.com/emails', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${process.env.RESEND_API_KEY}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ from, to: email, subject: '✓ Votre Pass mensuel Parlons — identifiants de connexion', html })
    });
    if (!res.ok) console.error('Resend error:', await res.text());
  } catch (err) { console.error('sendWelcomeEmail error:', err.message); }
}

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };

  let body;
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Invalid JSON' }; }

  const { paymentMethodId, email, pseudo } = body;
  if (!paymentMethodId) return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'paymentMethodId manquant' }) };
  if (!email || !email.includes('@')) return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Email invalide' }) };
  if (!process.env.STRIPE_PRICE_ID) return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: 'Configuration manquante: STRIPE_PRICE_ID' }) };

  const normalEmail = email.toLowerCase().trim();

  try {
    // Vérifier si l'abonné a déjà un mot de passe (re-souscription)
    const existingSub = await fetch(`${SB_URL}/rest/v1/subscribers?email=eq.${encodeURIComponent(normalEmail)}&select=password_hash&limit=1`,
      { headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` } });
    const existingData = await existingSub.json();
    const alreadyHasPassword = Array.isArray(existingData) && existingData[0]?.password_hash;

    // Générer le mot de passe si c'est un nouvel abonné
    let plainPassword = null, passwordHash = null, passwordSalt = null;
    if (!alreadyHasPassword) {
      plainPassword = generatePassword();
      passwordSalt = crypto.randomBytes(16).toString('hex');
      passwordHash = hashPassword(plainPassword, passwordSalt);
    }

    // Créer ou récupérer le client Stripe
    const existing = await stripe.customers.list({ email: normalEmail, limit: 1 });
    let customer = existing.data[0] || await stripe.customers.create({
      email: normalEmail,
      name: pseudo || undefined,
      metadata: { pseudo: pseudo || '', plateforme: 'parlons' },
    });

    await stripe.paymentMethods.attach(paymentMethodId, { customer: customer.id });
    await stripe.customers.update(customer.id, {
      invoice_settings: { default_payment_method: paymentMethodId },
    });

    const subscription = await stripe.subscriptions.create({
      customer: customer.id,
      items: [{ price: process.env.STRIPE_PRICE_ID }],
      payment_settings: { payment_method_types: ['card'], save_default_payment_method: 'on_subscription' },
      expand: ['latest_invoice.payment_intent'],
      metadata: { email: normalEmail, pseudo: pseudo || '', plateforme: 'parlons' },
    });

    const paymentIntent = subscription.latest_invoice?.payment_intent;
    const expires_at = new Date(subscription.current_period_end * 1000).toISOString();

    if (SB_URL && SB_KEY) {
      const subData = {
        email: normalEmail,
        pseudo: pseudo || null,
        stripe_customer_id: customer.id,
        stripe_subscription_id: subscription.id,
        status: 'pending',
        expires_at,
        cancel_at_period_end: false,
      };
      if (passwordHash) { subData.password_hash = passwordHash; subData.password_salt = passwordSalt; }

      await fetch(`${SB_URL}/rest/v1/subscribers`, {
        method: 'POST',
        headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}`, 'Content-Type': 'application/json', Prefer: 'resolution=merge-duplicates,return=minimal' },
        body: JSON.stringify(subData),
      });
    }

    // Envoyer l'email de bienvenue avec le mot de passe (une seule fois)
    if (plainPassword) await sendWelcomeEmail(normalEmail, pseudo, plainPassword);

    return {
      statusCode: 200,
      headers: CORS,
      body: JSON.stringify({ subscriptionId: subscription.id, clientSecret: paymentIntent?.client_secret || null, status: subscription.status }),
    };
  } catch (err) {
    console.error('create-subscription error:', err.message);
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: 'Erreur serveur' }) };
  }
};
