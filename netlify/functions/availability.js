const { getStore } = require('@netlify/blobs');

const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'Parlons2026!';

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

  try {
    const val = await store.get('status');
    const online = val === 'true';
    return { statusCode: 200, headers, body: JSON.stringify({ online, source: 'blobs' }) };
  } catch (e) {
    return { statusCode: 200, headers, body: JSON.stringify({ online: false, error: e.message }) };
  }
};
