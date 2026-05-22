const { createClient } = require('@supabase/supabase-js');

const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'Parlons2026!';

exports.handler = async (event) => {
  const headers = {
    'Content-Type': 'application/json',
    'Cache-Control': 'no-cache',
    'Access-Control-Allow-Origin': '*'
  };

  const url  = process.env.SUPABASE_URL;
  const key  = process.env.SUPABASE_SERVICE_KEY;

  // ── GET : lit la disponibilité depuis Supabase ──
  if (event.httpMethod === 'GET') {
    if (!url || !key) return { statusCode: 200, headers, body: JSON.stringify({ online: false }) };
    try {
      const sb = createClient(url, key);
      const { data } = await sb.from('agents').select('actif').eq('pseudo', 'parlons_admin').limit(1);
      const online = !!(data && data.length > 0 && data[0].actif);
      return { statusCode: 200, headers, body: JSON.stringify({ online }) };
    } catch (e) {
      return { statusCode: 200, headers, body: JSON.stringify({ online: false }) };
    }
  }

  // ── POST : met à jour la disponibilité ──
  if (event.httpMethod === 'POST') {
    let body;
    try { body = JSON.parse(event.body || '{}'); } catch { body = {}; }

    if (body.password !== ADMIN_PASSWORD)
      return { statusCode: 403, headers, body: JSON.stringify({ error: 'Non autorisé' }) };

    if (!url || !key)
      return { statusCode: 500, headers, body: JSON.stringify({ error: 'Supabase non configuré' }) };

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

  return { statusCode: 405, headers, body: 'Method Not Allowed' };
};
