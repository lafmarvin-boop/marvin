const { createClient } = require('@supabase/supabase-js');

const CRISP_WEBSITE_ID = 'fd20e23d-059a-4552-9aae-6df05f653d02';
const ADMIN_PASSWORD   = process.env.ADMIN_PASSWORD || 'Parlons2026!';

exports.handler = async (event) => {
  const headers = {
    'Content-Type': 'application/json',
    'Cache-Control': 'no-cache',
    'Access-Control-Allow-Origin': '*'
  };

  // ── POST : toggle manuel (fallback admin) ──
  if (event.httpMethod === 'POST') {
    let body;
    try { body = JSON.parse(event.body || '{}'); } catch { body = {}; }
    if (body.password !== ADMIN_PASSWORD)
      return { statusCode: 403, headers, body: JSON.stringify({ error: 'Non autorisé' }) };
    const url = process.env.SUPABASE_URL;
    const key = process.env.SUPABASE_SERVICE_KEY;
    if (!url || !key) return { statusCode: 500, headers, body: JSON.stringify({ error: 'Supabase non configuré' }) };
    const online = !!body.online;
    try {
      const sb = createClient(url, key);
      await sb.from('agents').upsert(
        { pseudo: 'parlons_admin', nom: 'Admin', actif: online },
        { onConflict: 'pseudo' }
      );
      return { statusCode: 200, headers, body: JSON.stringify({ online }) };
    } catch (e) {
      return { statusCode: 500, headers, body: JSON.stringify({ error: e.message }) };
    }
  }

  // ── GET : vérifie la disponibilité ──
  if (event.httpMethod !== 'GET')
    return { statusCode: 405, headers, body: 'Method Not Allowed' };

  // 1. Essai via l'API REST Crisp (si clés configurées)
  const crispId    = process.env.CRISP_API_IDENTIFIER;
  const crispToken = process.env.CRISP_API_TOKEN;

  if (crispId && crispToken) {
    try {
      const auth = Buffer.from(`${crispId}:${crispToken}`).toString('base64');
      const resp = await fetch(
        `https://api.crisp.chat/v1/website/${CRISP_WEBSITE_ID}/operator/list/1`,
        { headers: { 'Authorization': `Basic ${auth}`, 'X-Crisp-Tier': 'user' } }
      );
      if (resp.ok) {
        const data = await resp.json();
        const operators = Array.isArray(data.data) ? data.data : [];
        const online = operators.some(op =>
          op.availability && (op.availability.type === 'online' || op.availability === 'online')
        );
        return { statusCode: 200, headers, body: JSON.stringify({ online, source: 'crisp' }) };
      }
    } catch (e) { /* tombe sur le fallback */ }
  }

  // 2. Fallback : toggle manuel dans Supabase
  const url = process.env.SUPABASE_URL;
  const key = process.env.SUPABASE_SERVICE_KEY;
  if (!url || !key) return { statusCode: 200, headers, body: JSON.stringify({ online: false }) };
  try {
    const sb = createClient(url, key);
    const { data } = await sb.from('agents').select('actif').eq('pseudo', 'parlons_admin').limit(1);
    const online = !!(data && data.length > 0 && data[0].actif);
    return { statusCode: 200, headers, body: JSON.stringify({ online, source: 'supabase' }) };
  } catch (e) {
    return { statusCode: 200, headers, body: JSON.stringify({ online: false }) };
  }
};
