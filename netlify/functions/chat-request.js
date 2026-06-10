const RESEND_API_KEY = process.env.RESEND_API_KEY;
const FROM_EMAIL = process.env.FROM_EMAIL || 'notifications@parlonsecoute.fr';
const ADMIN_EMAIL = process.env.ADMIN_EMAIL || 'lafmarvin@gmail.com';

const CORS = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type',
};

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };

  let body;
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Bad Request' }; }

  const { name, message } = body;

  // Email de notification si Resend configuré
  if (RESEND_API_KEY) {
    try {
      await fetch('https://api.resend.com/emails', {
        method: 'POST',
        headers: { Authorization: `Bearer ${RESEND_API_KEY}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          from: FROM_EMAIL,
          to: ADMIN_EMAIL,
          subject: '🔔 Parlons — Demande d\'écoutant',
          html: `<p><strong>Nom :</strong> ${name || 'Anonyme'}</p><p><strong>Message :</strong> ${message || '—'}</p><p><em>Un visiteur attend qu'un écoutant se connecte.</em></p>`
        })
      });
    } catch (e) { console.error('chat-request email:', e.message); }
  }

  // Phase 2 : notifications push via push_subscriptions (Web Push API + VAPID)
  // TODO

  return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true }) };
};
