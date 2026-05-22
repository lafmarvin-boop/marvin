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
      const authHeaders = { 'Authorization': `Basic ${auth}`, 'X-Crisp-Tier': 'user' };

      // Endpoint direct : disponibilité globale du site (vue visiteur)
      const availResp = await fetch(
        `https://api.crisp.chat/v1/website/${CRISP_WEBSITE_ID}/availability`,
        { headers: authHeaders }
      );
      if (availResp.ok) {
        const availData = await availResp.json();
        const avail = availData.data?.availability;
        const online = avail === 'online';
        return { statusCode: 200, headers, body: JSON.stringify({ online, source: 'crisp' }) };
      }

      // Fallback : liste des opérateurs actifs
      const opsResp = await fetch(
        `https://api.crisp.chat/v1/website/${CRISP_WEBSITE_ID}/operators/active`,
        { headers: authHeaders }
      );
      if (opsResp.ok) {
        const opsData = await opsResp.json();
        const operators = Array.isArray(opsData.data) ? opsData.data : [];
        const online = operators.some(op => {
          const a = op.availability;
          return a === 'online' || a?.type === 'online' || a?.status === 'online';
        });
        return { statusCode: 200, headers, body: JSON.stringify({ online, source: 'crisp-ops' }) };
      }
    } catch (e) { /* tombe sur le fallback Supabase */ }
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
