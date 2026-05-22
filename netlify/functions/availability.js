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
      const authH = { 'Authorization': `Basic ${auth}`, 'X-Crisp-Tier': 'website' };

      // Essai 1 : disponibilité globale du site
      const r1 = await fetch(`https://api.crisp.chat/v1/website/${CRISP_WEBSITE_ID}/availability`, { headers: authH });
      if (isDebug) {
        const raw1 = await r1.json();
        // Essai 2 en debug aussi
        const r2 = await fetch(`https://api.crisp.chat/v1/website/${CRISP_WEBSITE_ID}/operator-availability`, { headers: authH });
        const raw2 = await r2.json();
        return { statusCode: 200, headers, body: JSON.stringify({ s1: r1.status, raw1, s2: r2.status, raw2 }) };
      }
      if (r1.ok) {
        const d1 = await r1.json();
        const avail = d1.data?.availability ?? d1.data?.status;
        if (avail !== undefined)
          return { statusCode: 200, headers, body: JSON.stringify({ online: avail === 'online', source: 'crisp-avail' }) };
      }

      // Essai 2 : liste des disponibilités opérateurs
      const r2 = await fetch(`https://api.crisp.chat/v1/website/${CRISP_WEBSITE_ID}/operator-availability`, { headers: authH });
      if (r2.ok) {
        const d2 = await r2.json();
        const ops = Array.isArray(d2.data) ? d2.data : [];
        const online = ops.some(op => {
          const a = op.availability;
          return a === 'online' || a?.type === 'online';
        });
        return { statusCode: 200, headers, body: JSON.stringify({ online, source: 'crisp-ops' }) };
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
