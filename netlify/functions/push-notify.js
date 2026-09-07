const webpush = require('web-push');
const crypto = require('crypto');

const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;

// Secret partagé entre fonctions internes (chat-send.js, free-session.js, chat-start.js) : sans
// lui, n'importe qui pouvait POSTer ici un titre/message/URL arbitraires vers les écoutants
// (phishing, harcèlement par notification). Ce n'est pas une authentification utilisateur — juste
// une preuve que l'appel vient bien du serveur lui-même. INTERNAL_FN_SECRET si défini, sinon la
// clé de service Supabase (déjà présente côté serveur, jamais exposée au navigateur), comme _auth.js.
const INTERNAL_SECRET = process.env.INTERNAL_FN_SECRET || SB_KEY || '';

function validInternalCaller(provided) {
  if (!INTERNAL_SECRET || typeof provided !== 'string' || !provided) return false;
  const a = Buffer.from(provided);
  const b = Buffer.from(INTERNAL_SECRET);
  if (a.length !== b.length) return false;
  return crypto.timingSafeEqual(a, b);
}

const CORS = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type',
};

const H = () => ({ apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` });

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };

  const vapidPublic  = process.env.VAPID_PUBLIC_KEY;
  const vapidPrivate = process.env.VAPID_PRIVATE_KEY;
  const vapidEmail   = process.env.VAPID_EMAIL || `mailto:${process.env.ADMIN_EMAIL || 'admin@example.com'}`;

  if (!vapidPublic || !vapidPrivate || !SB_URL || !SB_KEY)
    return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, skipped: true }) };

  let body;
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Bad Request' }; }

  let { title = 'Nouveau tchat', message = 'Un visiteur attend votre aide', url = '/agent-app.html', agentEmail } = body;

  // Deux appelants légitimes :
  //   — les fonctions internes, qui prouvent leur origine par le secret partagé ;
  //   — un écoutant connecté qui teste ses notifications depuis son application (bouton « 🔔 Test »).
  //     Le navigateur ne peut pas connaître le secret : on l'authentifie par son jeton de présence,
  //     et l'envoi est alors **verrouillé sur lui-même**, avec un contenu fixe. Il ne peut donc ni
  //     notifier un collègue, ni choisir le texte ou le lien — ce que le secret partagé empêche.
  if (!validInternalCaller(body.internalSecret)) {
    const email = (body.agentEmail || '').toLowerCase().trim();
    const jeton = body.agentToken;
    if (!email || !jeton)
      return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Non autorisé' }) };
    const pres = await fetch(`${SB_URL}/rest/v1/agent_presence?agent_email=eq.${encodeURIComponent(email)}&select=session_token&limit=1`, { headers: H() });
    const rows = await pres.json().catch(() => []);
    if (!Array.isArray(rows) || !rows.length || rows[0].session_token !== jeton)
      return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Non autorisé' }) };
    agentEmail = email;
    title = '🔔 Test Parlons';
    message = 'Les notifications fonctionnent correctement !';
    url = '/agent-app.html';
  }

  webpush.setVapidDetails(vapidEmail, vapidPublic, vapidPrivate);

  try {
    const filter = agentEmail ? `agent_email=eq.${encodeURIComponent(agentEmail)}&` : '';
    const res = await fetch(`${SB_URL}/rest/v1/push_subscriptions?${filter}select=subscription`, { headers: H() });
    const rows = await res.json();
    if (!Array.isArray(rows) || !rows.length) return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, sent: 0 }) };

    const payload = JSON.stringify({ title, body: message, url });
    const results = await Promise.allSettled(
      rows.map(row => {
        let sub;
        try {
          sub = typeof row.subscription === 'string' ? JSON.parse(row.subscription) : row.subscription;
        } catch { return Promise.reject(new Error('invalid_json')); }
        return webpush.sendNotification(sub, payload).catch(async err => {
          // 404 / 410 : l'endpoint n'existe plus, la ligne n'a plus d'objet. En revanche 403
          // signale le plus souvent des clés VAPID qui ne correspondent pas — une erreur de
          // configuration, pas un appareil disparu : supprimer effacerait tous les abonnements
          // d'un coup et couperait les notifications de toute l'équipe.
          if (err.statusCode === 403) console.error('push-notify : 403 (VAPID ?) — abonnement conservé');
          if ([404, 410].includes(err.statusCode)) {
            await fetch(`${SB_URL}/rest/v1/push_subscriptions?endpoint=eq.${encodeURIComponent(sub.endpoint)}`, {
              method: 'DELETE', headers: H()
            }).catch(() => {});
          }
          throw err;
        });
      })
    );

    const sent = results.filter(r => r.status === 'fulfilled').length;
    return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, sent }) };
  } catch (e) {
    console.error('push-notify:', e.message);
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: e.message }) };
  }
};
