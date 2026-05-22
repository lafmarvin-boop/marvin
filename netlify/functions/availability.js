const { getStore } = require('@netlify/blobs');

const CRISP_WEBSITE_ID = 'fd20e23d-059a-4552-9aae-6df05f653d02';
const ADMIN_PASSWORD   = process.env.ADMIN_PASSWORD || 'Parlons2026!';

exports.handler = async (event) => {
  const headers = {
    'Content-Type': 'application/json',
    'Cache-Control': 'no-cache',
    'Access-Control-Allow-Origin': '*'
  };

  if (event.httpMethod === 'OPTIONS')
    return { statusCode: 204, headers };

  let store;
  try {
    store = getStore({ name: 'parlons-availability', consistency: 'strong' });
  } catch (e) {
    return { statusCode: 200, headers, body: JSON.stringify({ online: false, error: 'store-init: ' + e.message }) };
  }

  // ── POST : toggle admin ──
  if (event.httpMethod === 'POST') {
    let body;
    try { body = JSON.parse(event.body || '{}'); } catch { body = {}; }
    if (body.password !== ADMIN_PASSWORD)
      return { statusCode: 403, headers, body: JSON.stringify({ error: 'Non autorisé' }) };
    const online = !!body.online;
    try {
      await store.set('status', online ? 'true' : 'false');
      return { statusCode: 200, headers, body: JSON.stringify({ online }) };
    } catch (e) {
      return { statusCode: 500, headers, body: JSON.stringify({ error: e.message }) };
    }
  }

  // ── GET : retourne le statut ──
  if (event.httpMethod !== 'GET')
    return { statusCode: 405, headers, body: 'Method Not Allowed' };

  // 1. Essai Crisp REST API (website token tier)
  const crispId    = process.env.CRISP_API_IDENTIFIER;
  const crispToken = process.env.CRISP_API_TOKEN;
  const isDebug    = event.queryStringParameters?.debug === '1';

  if (crispId && crispToken) {
    try {
      const auth = Buffer.from(`${crispId}:${crispToken}`).toString('base64');
      const authHeaders = { 'Authorization': `Basic ${auth}`, 'X-Crisp-Tier': 'website' };

      const opsResp = await fetch(
        `https://api.crisp.chat/v1/website/${CRISP_WEBSITE_ID}/operators`,
        { headers: authHeaders }
      );
      if (isDebug) {
        const raw = await opsResp.json();
        return { statusCode: 200, headers, body: JSON.stringify({ status: opsResp.status, raw }) };
      }
      if (opsResp.ok) {
        const opsData = await opsResp.json();
        const operators = Array.isArray(opsData.data) ? opsData.data : [];
        const online = operators.some(op => {
          const a = op.availability;
          return a === 'online' || a?.type === 'online';
        });
        return { statusCode: 200, headers, body: JSON.stringify({ online, source: 'crisp' }) };
      }
    } catch (e) { /* fallback */ }
  }

  // 2. Fallback : état stocké dans Netlify Blobs (toggle admin)
  try {
    const val = await store.get('status');
    const online = val === 'true';
    return { statusCode: 200, headers, body: JSON.stringify({ online, source: 'blobs' }) };
  } catch (e) {
    return { statusCode: 200, headers, body: JSON.stringify({ online: false, error: e.message }) };
  }
};
