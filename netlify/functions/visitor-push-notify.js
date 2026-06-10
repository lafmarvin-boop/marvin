const webpush = require('web-push');

const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;

const CORS = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type',
};

const H = () => ({ apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` });

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };

  const vapidPublic  = process.env.VAPID_PUBLIC_KEY;
  const vapidPrivate = process.env.VAPID_PRIVATE_KEY;
  const vapidEmail   = `mailto:${process.env.ADMIN_EMAIL || 'admin@example.com'}`;

  if (!vapidPublic || !vapidPrivate || !SB_URL || !SB_KEY)
    return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, skipped: true }) };

  let body;
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Bad Request' }; }

  const { requestIds } = body;
  if (!Array.isArray(requestIds) || !requestIds.length)
    return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, sent: 0 }) };

  webpush.setVapidDetails(vapidEmail, vapidPublic, vapidPrivate);

  try {
    const ids = requestIds.map(id => encodeURIComponent(id)).join(',');
    const res = await fetch(
      `${SB_URL}/rest/v1/agent_requests?id=in.(${ids})&push_subscription=not.is.null&select=push_subscription`,
      { headers: H() }
    );
    const rows = await res.json();
    if (!Array.isArray(rows) || !rows.length)
      return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, sent: 0 }) };

    const payload = JSON.stringify({
      title: 'Un écoutant est disponible',
      body: 'Démarrez votre conversation maintenant →',
      url: '/app-visiteur.html'
    });

    const results = await Promise.allSettled(rows.map(row => {
      try {
        const sub = typeof row.push_subscription === 'string'
          ? JSON.parse(row.push_subscription)
          : row.push_subscription;
        return webpush.sendNotification(sub, payload);
      } catch { return Promise.resolve(); }
    }));

    const sent = results.filter(r => r.status === 'fulfilled').length;
    return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, sent }) };
  } catch (e) {
    console.error('visitor-push-notify:', e.message);
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: e.message }) };
  }
};
