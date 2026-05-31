const crypto = require('crypto');
const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;
const ADMIN_EMAIL = (process.env.ADMIN_EMAIL || '').toLowerCase();
const ADMIN_PWD   = process.env.ADMIN_PASSWORD;

const CORS = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type',
};

const H = () => ({ apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` });

function hashPassword(password, salt) {
  return crypto.pbkdf2Sync(password, salt, 100000, 64, 'sha512').toString('hex');
}

async function sbGet(path) {
  const res = await fetch(`${SB_URL}/rest/v1/${path}`, { headers: H() });
  const d = await res.json();
  return Array.isArray(d) ? d : [];
}

async function sbPatch(path, body) {
  return fetch(`${SB_URL}/rest/v1/${path}`, {
    method: 'PATCH',
    headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
    body: JSON.stringify(body)
  });
}

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };

  let body;
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Bad Request' }; }

  if (!SB_URL || !SB_KEY) return { statusCode: 503, headers: CORS, body: JSON.stringify({ error: 'Service non configuré' }) };

  const { agentEmail, password, agentToken, status } = body;
  if (!agentEmail || !status)
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Données manquantes' }) };

  try {
    // ── PASSER EN LIGNE : vérifie le mot de passe, génère un token ──
    if (status === 'online') {
      if (!password) return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Mot de passe requis' }) };

      // Bypass admin : accepte les identifiants admin directement
      const isAdmin = ADMIN_EMAIL && agentEmail.toLowerCase() === ADMIN_EMAIL && ADMIN_PWD && password === ADMIN_PWD;

      if (!isAdmin) {
        const pwdRows = await sbGet(`agent_passwords?email=eq.${encodeURIComponent(agentEmail)}&select=password_hash,password_salt&limit=1`);
        if (!pwdRows.length) return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Agent non autorisé' }) };

        const hash = hashPassword(password, pwdRows[0].password_salt);
        if (hash !== pwdRows[0].password_hash)
          return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Mot de passe incorrect' }) };
      }

      const token = crypto.randomBytes(32).toString('hex');
      const now = new Date().toISOString();

      // Upsert présence
      await fetch(`${SB_URL}/rest/v1/agent_presence`, {
        method: 'POST',
        headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'resolution=merge-duplicates' },
        body: JSON.stringify({
          agent_email: agentEmail,
          status: 'online',
          session_token: token,
          connected_since: now,
          last_seen: now,
          current_session_id: null
        })
      });

      // Vérifier si des visiteurs attendent et en assigner un
      const waiting = await sbGet(`chat_sessions?status=eq.waiting&select=id&order=created_at.asc&limit=1`);
      if (waiting.length) {
        const sessionId = waiting[0].id;
        await Promise.all([
          sbPatch(`chat_sessions?id=eq.${encodeURIComponent(sessionId)}`, {
            agent_email: agentEmail, status: 'active', assigned_at: now
          }),
          sbPatch(`agent_presence?agent_email=eq.${encodeURIComponent(agentEmail)}`, {
            current_session_id: sessionId, status: 'busy'
          }),
          fetch(`${SB_URL}/rest/v1/chat_messages`, {
            method: 'POST',
            headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
            body: JSON.stringify({ session_id: sessionId, content: 'Un écoutant vous a rejoint.', sender_type: 'system' })
          })
        ]);
        return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, token, assignedSession: sessionId }) };
      }

      return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, token }) };
    }

    // ── PASSER HORS LIGNE : vérifie le token ──
    if (status === 'offline') {
      if (!agentToken) return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Token requis' }) };

      const presence = await sbGet(
        `agent_presence?agent_email=eq.${encodeURIComponent(agentEmail)}&select=session_token,current_session_id&limit=1`
      );
      if (!presence.length || presence[0].session_token !== agentToken)
        return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Token invalide' }) };

      // Fermer la session en cours si elle existe
      const sid = presence[0].current_session_id;
      if (sid) {
        const now = new Date().toISOString();
        await Promise.all([
          sbPatch(`chat_sessions?id=eq.${encodeURIComponent(sid)}`, { status: 'closed', closed_at: now }),
          fetch(`${SB_URL}/rest/v1/chat_messages`, {
            method: 'POST',
            headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
            body: JSON.stringify({ session_id: sid, content: "L'écoutant a mis fin à la session.", sender_type: 'system' })
          })
        ]);
      }

      await sbPatch(`agent_presence?agent_email=eq.${encodeURIComponent(agentEmail)}`, {
        status: 'offline', current_session_id: null, session_token: null
      });

      return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true }) };
    }

    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Statut invalide' }) };
  } catch (e) {
    console.error('chat-presence:', e.message);
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: e.message }) };
  }
};
