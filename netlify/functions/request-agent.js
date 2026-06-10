const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;
const RESEND_KEY = process.env.RESEND_API_KEY;
const FROM = process.env.FROM_EMAIL || 'Parlons <noreply@parlonsecoute.fr>';
const CONTACT_EMAIL = 'contact.parlons.ecoute@gmail.com';

const CORS = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type'
};

const H = () => ({ apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` });

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };

  let body;
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Invalid JSON' }; }

  const { email, pushSubscription } = body;
  if (!email || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email))
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Email invalide' }) };

  const emailLower = email.toLowerCase().trim();

  if (SB_URL && SB_KEY) {
    // Si une demande non-notifiée existe déjà pour cet email, ne pas dupliquer
    const check = await fetch(
      `${SB_URL}/rest/v1/agent_requests?email=eq.${encodeURIComponent(emailLower)}&notified_at=is.null&select=id&limit=1`,
      { headers: H() }
    ).catch(() => null);
    if (check && check.ok) {
      const ex = await check.json().catch(() => []);
      if (Array.isArray(ex) && ex.length > 0) {
        // Mettre à jour la push subscription si fournie
        if (pushSubscription && ex[0]?.id) {
          await fetch(`${SB_URL}/rest/v1/agent_requests?id=eq.${encodeURIComponent(ex[0].id)}`, {
            method: 'PATCH',
            headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
            body: JSON.stringify({ push_subscription: JSON.stringify(pushSubscription) })
          }).catch(() => {});
        }
        return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, already: true }) };
      }
    }

    // Enregistrer la demande
    await fetch(`${SB_URL}/rest/v1/agent_requests`, {
      method: 'POST',
      headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
      body: JSON.stringify({
        email: emailLower,
        push_subscription: pushSubscription ? JSON.stringify(pushSubscription) : null
      })
    }).catch(() => {});
  }

  if (RESEND_KEY) {
    const emailHtml = `<p style="font-family:sans-serif">Un visiteur souhaite parler à un écoutant.</p>
<p style="font-family:sans-serif"><strong>Email :</strong> ${emailLower}</p>
<p style="font-family:sans-serif"><strong>Date :</strong> ${new Date().toLocaleString('fr-FR')}</p>
<p style="font-family:sans-serif">Il sera automatiquement prévenu dès qu'un écoutant se connectera.</p>`;

    const targets = [CONTACT_EMAIL];

    // Ajouter les agents qui ont activé les notifications
    if (SB_URL && SB_KEY) {
      try {
        const agRes = await fetch(
          `${SB_URL}/rest/v1/agent_profiles?notify_requests=eq.true&notify_email=not.is.null&select=notify_email`,
          { headers: H() }
        );
        const agents = await agRes.json().catch(() => []);
        if (Array.isArray(agents)) {
          agents.forEach(a => { if (a.notify_email && !targets.includes(a.notify_email)) targets.push(a.notify_email); });
        }
      } catch (e) { console.error('fetch-agent-profiles:', e.message); }
    }

    await Promise.all(targets.map(to =>
      fetch('https://api.resend.com/emails', {
        method: 'POST',
        headers: { Authorization: `Bearer ${RESEND_KEY}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ from: FROM, to, subject: "📩 Demande d'écoutant — Parlons", html: emailHtml })
      }).catch(e => console.error('request-agent resend:', e.message))
    ));
  }

  return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true }) };
};
