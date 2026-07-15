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
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };

  const vapidPublic  = process.env.VAPID_PUBLIC_KEY;
  const vapidPrivate = process.env.VAPID_PRIVATE_KEY;
  const vapidEmail   = process.env.VAPID_EMAIL || `mailto:${process.env.ADMIN_EMAIL || 'admin@example.com'}`;

  if (!vapidPublic || !vapidPrivate || !SB_URL || !SB_KEY)
    return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, skipped: true }) };

  let body;
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Bad Request' }; }

  const { title = 'Nouveau tchat', message = 'Un visiteur attend votre aide', url = '/agent-app.html', agentEmail } = body;

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
          if ([404, 410, 403].includes(err.statusCode)) {
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
