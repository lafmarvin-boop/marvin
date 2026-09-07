// ─────────────────────────────────────────────────────────────────────────────
// Demande de remboursement d'une session tenue par Max.
//
// Le visiteur allé au bout de sa session sans qu'un écoutant humain le rejoigne
// s'en est vu proposer le remboursement par `chat-close`. Il l'obtient ici en un
// clic : Stripe rembourse immédiatement, sans justification et sans attente.
//
// Trois vérifications, toutes indispensables :
//   1. le demandeur est bien le visiteur de cette session (`visitor_id`) ;
//   2. la session était tenue par Max et est close ;
//   3. le message d'offre est présent dans la conversation — c'est la seule
//      preuve que la session est allée à son terme. Sans lui, quelqu'un ayant
//      simplement fermé sa page pourrait se faire rembourser, ce qui n'est pas
//      le cas avec un écoutant humain.
//
// L'appel est idempotent : Stripe est interrogé avant tout versement, et un
// paiement déjà remboursé renvoie simplement un message de confirmation.
// ─────────────────────────────────────────────────────────────────────────────

const { OFFRE_PREFIXE } = require('./_refund');

const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;
const AI_EMAIL = 'claude@parlonsecoute.fr';

const CORS = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type'
};
const H = () => ({ apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` });

async function sbGet(path) {
  const res = await fetch(`${SB_URL}/rest/v1/${path}`, { headers: H() });
  const d = await res.json();
  return Array.isArray(d) ? d : [];
}

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };
  if (!SB_URL || !SB_KEY) return { statusCode: 503, headers: CORS, body: JSON.stringify({ error: 'Service non configuré' }) };

  let body;
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Bad Request' }; }

  const { sessionId, visitorId } = body;
  if (!sessionId) return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'sessionId requis' }) };

  try {
    const sessions = await sbGet(`chat_sessions?id=eq.${encodeURIComponent(sessionId)}&select=id,status,agent_email,visitor_id,stripe_payment_id,pre_name&limit=1`);
    const sess = sessions[0];
    if (!sess) return { statusCode: 404, headers: CORS, body: JSON.stringify({ error: 'Session introuvable' }) };

    // 1. Seul le visiteur de cette session peut demander son remboursement.
    if (!visitorId || !sess.visitor_id || visitorId !== sess.visitor_id)
      return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Demande non autorisée' }) };

    // 2. Session close, tenue par Max jusqu'au bout.
    if (sess.status !== 'closed' || sess.agent_email !== AI_EMAIL)
      return { statusCode: 403, headers: CORS, body: JSON.stringify({ error: 'Cette session n\'ouvre pas droit au remboursement.' }) };

    // 3. Le message d'offre atteste que la session est allée à son terme.
    const msgs = await sbGet(`chat_messages?session_id=eq.${encodeURIComponent(sessionId)}&sender_type=eq.system&select=content&order=created_at.desc&limit=20`);
    if (!msgs.some(m => String(m.content || '').startsWith(OFFRE_PREFIXE)))
      return { statusCode: 403, headers: CORS, body: JSON.stringify({ error: 'Cette session n\'ouvre pas droit au remboursement.' }) };

    const pi = sess.stripe_payment_id;
    if (!pi || !pi.startsWith('pi_') || !process.env.STRIPE_SECRET_KEY)
      return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Aucun paiement à rembourser pour cette session.' }) };

    const Stripe = require('stripe');
    const stripe = new Stripe(process.env.STRIPE_SECRET_KEY);
    const intent = await stripe.paymentIntents.retrieve(pi, { expand: ['latest_charge'] });
    const charge = intent.latest_charge && typeof intent.latest_charge === 'object' ? intent.latest_charge : null;
    const deja = (charge && (charge.refunded || charge.amount_refunded >= charge.amount)) || intent.status === 'canceled';

    if (!deja && intent.status === 'succeeded') {
      await stripe.refunds.create({
        payment_intent: pi,
        reason: 'requested_by_customer',
        metadata: { motif: 'aucun_ecoutant_demande_visiteur', session_id: sessionId }
      });
    }

    await fetch(`${SB_URL}/rest/v1/sessions?stripe_payment_id=eq.${encodeURIComponent(pi)}`, {
      method: 'PATCH', headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
      body: JSON.stringify({ statut: 'refunded' })
    }).catch(() => {});

    const montant = intent.amount_received ? (intent.amount_received / 100).toFixed(2).replace('.', ',') + ' €' : 'votre paiement';
    await fetch(`${SB_URL}/rest/v1/chat_messages`, {
      method: 'POST', headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
      body: JSON.stringify({
        session_id: sessionId, sender_type: 'system',
        content: deja
          ? `Le remboursement de ${montant} a déjà été enregistré. Le crédit apparaît sur votre compte sous 5 à 10 jours selon votre banque.`
          : `C'est fait : ${montant} vous sont remboursés intégralement. Le crédit apparaît sur votre compte sous 5 à 10 jours selon votre banque. Merci de votre confiance, et à bientôt.`
      })
    });

    return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, montant, deja }) };
  } catch (e) {
    console.error('refund-request:', e.message);
    // Le visiteur a demandé un remboursement auquel il a droit : si Stripe échoue, l'admin doit
    // le savoir pour le traiter à la main plutôt que de laisser la demande sans suite.
    const siteUrl = process.env.SITE_URL || process.env.URL || 'https://parlonsecoute.fr';
    await fetch(`${siteUrl}/.netlify/functions/notify-admin`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        type: 'recontact', prenom: 'Remboursement', email: 'remboursement@auto',
        message: `ÉCHEC remboursement demandé — session ${sessionId} : ${e.message} — à traiter manuellement dans Stripe.`
      }),
      signal: AbortSignal.timeout(2500)
    }).catch(() => {});
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: 'Le remboursement n\'a pas pu être traité. Nous en avons été informés et le traiterons manuellement.' }) };
  }
};
