const RESEND_KEY = process.env.RESEND_API_KEY;
const FROM = process.env.FROM_EMAIL || 'Parlons <noreply@parlons.fr>';
const ADMIN = process.env.ADMIN_EMAIL || 'lafmarvin@gmail.com';

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

  const { type, email, name, pseudo, dispo, why, exp, prenom, message } = body;
  if (!type) return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'type requis' }) };

  // Si Resend non configuré : on logue et on répond OK (pas bloquant)
  if (!RESEND_KEY) {
    console.warn('notify-admin: RESEND_API_KEY manquant —', type, email || name);
    return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, sent: false }) };
  }

  let subject, html;

  if (type === 'notify') {
    subject = '📩 Nouvelle alerte disponibilité — Parlons';
    html = `<p>Un visiteur souhaite être prévenu quand un écoutant est disponible.</p>
<p><strong>Email :</strong> ${email || '—'}</p>
<p><strong>Date :</strong> ${new Date().toLocaleString('fr-FR')}</p>`;
  } else if (type === 'recontact') {
    subject = '📩 Demande de recontact — attente dépassée — Parlons';
    html = `<p>Demande de recontact après 10 min d'attente sans réponse.</p>
<p><strong>Prénom :</strong> ${prenom || '—'}</p>
<p><strong>Email :</strong> ${email || '—'}</p>
<p><strong>Message :</strong> ${message || '—'}</p>
<p><strong>Date :</strong> ${new Date().toLocaleString('fr-FR')}</p>`;
  } else if (type === 'candidature') {
    subject = '🎙️ Nouvelle candidature écoutant — Parlons';
    html = `<p>Nouvelle candidature écoutant reçue.</p>
<p><strong>Nom :</strong> ${name || '—'}</p>
<p><strong>Pseudo :</strong> ${pseudo || '—'}</p>
<p><strong>Email :</strong> ${email || '—'}</p>
<p><strong>Disponibilités :</strong> ${dispo || '—'}</p>
<p><strong>Motivation :</strong> ${why || '—'}</p>
<p><strong>Expérience :</strong> ${exp || '—'}</p>
<p><strong>Date :</strong> ${new Date().toLocaleString('fr-FR')}</p>`;
  } else {
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'type inconnu' }) };
  }

  try {
    const res = await fetch('https://api.resend.com/emails', {
      method: 'POST',
      headers: { Authorization: `Bearer ${RESEND_KEY}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ from: FROM, to: ADMIN, subject, html })
    });
    if (!res.ok) console.error('notify-admin Resend error:', await res.text());
  } catch (err) { console.error('notify-admin:', err.message); }

  return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, sent: true }) };
};
