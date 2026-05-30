const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;
const CRISP_WEBSITE_ID = process.env.CRISP_WEBSITE_ID || 'fd20e23d-059a-4552-9aae-6df05f653d02';
const CRISP_API_ID = process.env.CRISP_API_IDENTIFIER;
const CRISP_API_KEY = process.env.CRISP_API_KEY || process.env.CRISP_API_TOKEN;
const HOOK_TOKEN = process.env.CRISP_HOOK_TOKEN;

const CORS = { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' };

async function getCrispSessionData(sessionId) {
  if (!CRISP_API_ID || !CRISP_API_KEY) return null;
  const auth = Buffer.from(`${CRISP_API_ID}:${CRISP_API_KEY}`).toString('base64');
  try {
    const res = await fetch(
      `https://api.crisp.chat/v1/website/${CRISP_WEBSITE_ID}/conversation/${encodeURIComponent(sessionId)}`,
      { headers: { Authorization: `Basic ${auth}`, 'X-Crisp-Tier': 'plugin' } }
    );
    if (!res.ok) { console.error('Crisp API error:', res.status); return null; }
    const json = await res.json();
    return json.data?.meta?.data || null;
  } catch (err) { console.error('getCrispSessionData:', err.message); return null; }
}

async function assignCrispConversation(sessionId, userId) {
  if (!CRISP_API_ID || !CRISP_API_KEY || !userId) return;
  const auth = Buffer.from(`${CRISP_API_ID}:${CRISP_API_KEY}`).toString('base64');
  try {
    const res = await fetch(
      `https://api.crisp.chat/v1/website/${CRISP_WEBSITE_ID}/conversation/${encodeURIComponent(sessionId)}/meta`,
      {
        method: 'PATCH',
        headers: { Authorization: `Basic ${auth}`, 'X-Crisp-Tier': 'plugin', 'Content-Type': 'application/json' },
        body: JSON.stringify({ assigned: { user_id: userId } })
      }
    );
    if (!res.ok) console.error('assignCrispConversation error:', res.status, await res.text());
    else console.log('Conversation assignée à userId:', userId);
  } catch (err) { console.error('assignCrispConversation:', err.message); }
}

async function patchSession(parlonsId, patch) {
  const res = await fetch(
    `${SB_URL}/rest/v1/sessions?stripe_payment_id=eq.${encodeURIComponent(parlonsId)}`,
    {
      method: 'PATCH',
      headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}`, 'Content-Type': 'application/json', Prefer: 'return=minimal' },
      body: JSON.stringify(patch),
    }
  );
  if (!res.ok) console.error('patchSession error:', await res.text());
}

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };

  // Vérification du token de sécurité (query param ou header)
  if (HOOK_TOKEN) {
    const incoming = event.queryStringParameters?.token || event.headers['x-crisp-hook-token'];
    if (incoming !== HOOK_TOKEN) return { statusCode: 401, headers: CORS, body: 'Unauthorized' };
  }

  let payload;
  try { payload = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Invalid JSON' }; }

  const { event: eventType, data } = payload;
  console.log('Crisp webhook:', eventType);

  // ── Premier message d'un agent → enregistrer son nom + assigner la conversation ──
  if (eventType === 'message:send' && data?.from === 'operator' && data?.user?.nickname) {
    const sessionId = data.session_id;
    const agentName = data.user.nickname;
    const agentEmail = data.user.email || null;
    const agentUserId = data.user.user_id || null;

    try {
      const sessionData = await getCrispSessionData(sessionId);
      const parlonsId = sessionData?.parlons_id;
      if (!parlonsId) { console.warn('parlons_id absent du session data Crisp'); return { statusCode: 200, headers: CORS, body: '{"ok":true}' }; }

      // N'écrire que si pas encore assigné
      const check = await fetch(
        `${SB_URL}/rest/v1/sessions?stripe_payment_id=eq.${encodeURIComponent(parlonsId)}&agent_name=is.null&select=id&limit=1`,
        { headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` } }
      );
      const rows = await check.json();
      if (Array.isArray(rows) && rows.length > 0) {
        // Enregistrer en Supabase + assigner dans Crisp en parallèle
        await Promise.all([
          patchSession(parlonsId, { agent_name: agentName, agent_email: agentEmail }),
          assignCrispConversation(sessionId, agentUserId)
        ]);
        console.log('Agent enregistré et conversation assignée:', agentName, 'session:', parlonsId);
      }
    } catch (err) { console.error('message:send handler:', err.message); }
  }

  // ── Conversation résolue → enregistrer la date de fin ──
  if (eventType === 'conversation:resolved') {
    const sessionId = data?.session_id;
    if (sessionId) {
      try {
        const sessionData = await getCrispSessionData(sessionId);
        const parlonsId = sessionData?.parlons_id;
        if (parlonsId) {
          await patchSession(parlonsId, { resolved_at: new Date().toISOString() });
          console.log('Session résolue:', parlonsId);
        }
      } catch (err) { console.error('conversation:resolved handler:', err.message); }
    }
  }

  return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true }) };
};
