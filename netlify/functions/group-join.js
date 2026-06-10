const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;
const ADMIN_PWD = process.env.ADMIN_PASSWORD;
const ADMIN_EMAIL = (process.env.ADMIN_EMAIL || '').toLowerCase();
const crypto = require('crypto');

const CORS = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type'
};

async function sbGet(path) {
  try {
    const res = await fetch(`${SB_URL}/rest/v1/${path}`, {
      headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` }
    });
    const d = await res.json();
    return Array.isArray(d) ? d : [];
  } catch { return []; }
}

async function verifyAgent(email, password) {
  if (!email || !password) return false;
  const emailLower = email.toLowerCase().trim();
  if (ADMIN_EMAIL && emailLower === ADMIN_EMAIL) return password === ADMIN_PWD;
  const rows = await sbGet(`agent_passwords?email=eq.${encodeURIComponent(emailLower)}&select=password_hash,password_salt&limit=1`);
  if (!rows.length) return false;
  const { password_hash, password_salt } = rows[0];
  const hash = crypto.pbkdf2Sync(password, password_salt, 100000, 64, 'sha512').toString('hex');
  return hash === password_hash;
}

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };
  if (!SB_URL || !SB_KEY) return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: 'Supabase non configuré' }) };

  let body;
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Invalid JSON' }; }

  const { pseudo, room_id, is_agent, agent_password, agent_email } = body;

  if (!pseudo || pseudo.trim().length < 2 || pseudo.trim().length > 30)
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Pseudo invalide (2-30 caractères)' }) };
  if (!room_id || !/^[a-z0-9_-]{2,60}$/i.test(room_id))
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Salon invalide' }) };

  const cleanPseudo = pseudo.trim();
  const isAgent = is_agent ? await verifyAgent(agent_email, agent_password) : false;

  if (is_agent && !isAgent)
    return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Identifiants écoutant incorrects' }) };

  const now = new Date();

  // Check if access record already exists for this pseudo+room
  const checkRes = await fetch(
    `${SB_URL}/rest/v1/group_access?room_id=eq.${encodeURIComponent(room_id)}&pseudo=eq.${encodeURIComponent(cleanPseudo)}&select=*&limit=1`,
    { headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` } }
  );
  const existing = await checkRes.json();

  if (Array.isArray(existing) && existing.length > 0) {
    const record = existing[0];
    if (isAgent) {
      const paid_until = new Date(now.getTime() + 24 * 60 * 60 * 1000).toISOString();
      await fetch(`${SB_URL}/rest/v1/group_access?id=eq.${record.id}`, {
        method: 'PATCH',
        headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}`, 'Content-Type': 'application/json', Prefer: 'return=minimal' },
        body: JSON.stringify({ is_agent: true, paid_until })
      });
      return { statusCode: 200, headers: CORS, body: JSON.stringify({ pseudo: cleanPseudo, room_id, is_agent: true, free_until: null, paid_until }) };
    }
    return {
      statusCode: 200,
      headers: CORS,
      body: JSON.stringify({ pseudo: record.pseudo, room_id: record.room_id, is_agent: record.is_agent, free_until: record.free_until, paid_until: record.paid_until })
    };
  }

  // New member
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
    if (insertRes.status === 409 || errText.includes('duplicate')) {
      const retry = await fetch(
        `${SB_URL}/rest/v1/group_access?room_id=eq.${encodeURIComponent(room_id)}&pseudo=eq.${encodeURIComponent(cleanPseudo)}&select=*&limit=1`,
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
