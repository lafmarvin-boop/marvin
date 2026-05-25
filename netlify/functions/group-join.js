const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;
const ADMIN_PWD = process.env.ADMIN_PASSWORD || 'Parlons2026!';

const CORS = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type'
};

const VALID_ROOMS = ['anxiete', 'deuil', 'couple', 'travail', 'solitude', 'confiance'];

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };
  if (!SB_URL || !SB_KEY) return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: 'Supabase non configuré' }) };

  let body;
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Invalid JSON' }; }

  const { pseudo, room_id, is_agent, agent_password } = body;

  if (!pseudo || pseudo.trim().length < 2 || pseudo.trim().length > 30)
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Pseudo invalide (2-30 caractères)' }) };
  if (!VALID_ROOMS.includes(room_id))
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Salon invalide' }) };

  const cleanPseudo = pseudo.trim();
  const isAgent = !!(is_agent && agent_password === ADMIN_PWD);
  const now = new Date();

  // Check if access record already exists for this pseudo+room
  const checkRes = await fetch(
    `${SB_URL}/rest/v1/group_access?room_id=eq.${room_id}&pseudo=eq.${encodeURIComponent(cleanPseudo)}&select=*&limit=1`,
    { headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` } }
  );
  const existing = await checkRes.json();

  if (Array.isArray(existing) && existing.length > 0) {
    const record = existing[0];
    // Agent re-joining: renew 24h access
    if (isAgent) {
      const paid_until = new Date(now.getTime() + 24 * 60 * 60 * 1000).toISOString();
      await fetch(`${SB_URL}/rest/v1/group_access?id=eq.${record.id}`, {
        method: 'PATCH',
        headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}`, 'Content-Type': 'application/json', Prefer: 'return=minimal' },
        body: JSON.stringify({ is_agent: true, paid_until })
      });
      return { statusCode: 200, headers: CORS, body: JSON.stringify({ pseudo: cleanPseudo, room_id, is_agent: true, free_until: null, paid_until }) };
    }
    // Return existing access — free period is not reset
    return {
      statusCode: 200,
      headers: CORS,
      body: JSON.stringify({ pseudo: record.pseudo, room_id: record.room_id, is_agent: record.is_agent, free_until: record.free_until, paid_until: record.paid_until })
    };
  }

  // New member — create access record
  const free_until = isAgent ? null : new Date(now.getTime() + 5 * 60 * 1000).toISOString();
  const paid_until = isAgent ? new Date(now.getTime() + 24 * 60 * 60 * 1000).toISOString() : null;

  const insertRes = await fetch(`${SB_URL}/rest/v1/group_access`, {
    method: 'POST',
    headers: {
      apikey: SB_KEY,
      Authorization: `Bearer ${SB_KEY}`,
      'Content-Type': 'application/json',
      Prefer: 'return=representation'
    },
    body: JSON.stringify({ room_id, pseudo: cleanPseudo, is_agent: isAgent, free_until, paid_until })
  });

  if (!insertRes.ok) {
    const errText = await insertRes.text();
    // Race condition duplicate — fetch existing record
    if (insertRes.status === 409 || errText.includes('duplicate')) {
      const retry = await fetch(
        `${SB_URL}/rest/v1/group_access?room_id=eq.${room_id}&pseudo=eq.${encodeURIComponent(cleanPseudo)}&select=*&limit=1`,
        { headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` } }
      );
      const rd = await retry.json();
      if (Array.isArray(rd) && rd.length > 0) {
        const r = rd[0];
        return { statusCode: 200, headers: CORS, body: JSON.stringify({ pseudo: r.pseudo, room_id: r.room_id, is_agent: r.is_agent, free_until: r.free_until, paid_until: r.paid_until }) };
      }
    }
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: 'Erreur création accès' }) };
  }

  const inserted = await insertRes.json();
  const record = Array.isArray(inserted) ? inserted[0] : inserted;

  return {
    statusCode: 200,
    headers: CORS,
    body: JSON.stringify({ pseudo: record.pseudo, room_id: record.room_id, is_agent: isAgent, free_until: record.free_until, paid_until: record.paid_until })
  };
};
