const CRISP_WEBSITE_ID = 'fd20e23d-059a-4552-9aae-6df05f653d02';
const ADMIN_PASSWORD   = process.env.ADMIN_PASSWORD || 'Parlons2026!';

// Stockage en mémoire (fallback léger entre les invocations chaudes)
// Remplacé par la vraie valeur dès le premier POST admin
let _manualStatus = null;

exports.handler = async (event) => {
  const headers = {
    'Content-Type': 'application/json',
    'Cache-Control': 'no-cache',
    'Access-Control-Allow-Origin': '*'
  };

  if (event.httpMethod === 'OPTIONS')
    return { statusCode: 204, headers };

  // ── POST : toggle manuel ──
  if (event.httpMethod === 'POST') {
    let body;
    try { body = JSON.parse(event.body || '{}'); } catch { body = {}; }
    if (body.password !== ADMIN_PASSWORD)
      return { statusCode: 403, headers, body: JSON.stringify({ error: 'Non autorisé' }) };
    _manualStatus = !!body.online;
    return { statusCode: 200, headers, body: JSON.stringify({ online: _manualStatus }) };
  }

  if (event.httpMethod !== 'GET')
    return { statusCode: 405, headers, body: 'Method Not Allowed' };

  const isDebug = event.queryStringParameters?.debug === '1';

  // ── 1. Crisp REST API (website token) ──
  const crispId    = process.env.CRISP_API_IDENTIFIER;
  const crispToken = process.env.CRISP_API_TOKEN;

  if (crispId && crispToken) {
    try {
      const auth = Buffer.from(`${crispId}:${crispToken}`).toString('base64');
      const resp = await fetch(
        `https://api.crisp.chat/v1/website/${CRISP_WEBSITE_ID}/operators`,
        { headers: { 'Authorization': `Basic ${auth}`, 'X-Crisp-Tier': 'website' } }
      );
      if (isDebug) {
        const raw = await resp.json();
        return { statusCode: 200, headers, body: JSON.stringify({ crisp_status: resp.status, raw }) };
      }
      if (resp.ok) {
        const data = await resp.json();
        const operators = Array.isArray(data.data) ? data.data : [];
        const online = operators.some(op => {
          const a = op.availability;
          return a === 'online' || a?.type === 'online';
        });
        return { statusCode: 200, headers, body: JSON.stringify({ online, source: 'crisp' }) };
      }
    } catch (e) {
      if (isDebug)
        return { statusCode: 200, headers, body: JSON.stringify({ crisp_error: e.message }) };
    }
  }

  // ── 2. Fallback : toggle manuel ──
  if (_manualStatus !== null)
    return { statusCode: 200, headers, body: JSON.stringify({ online: _manualStatus, source: 'manual' }) };

  return { statusCode: 200, headers, body: JSON.stringify({ online: false, source: 'default' }) };
};
