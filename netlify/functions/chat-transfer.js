const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;

const CORS = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type',
};

const H = () => ({ apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` });

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

  const { action, agentEmail, agentToken } = body;
  if (!agentEmail || !agentToken)
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Paramètres manquants' }) };

  // Vérifier le token dans tous les cas
  const presence = await sbGet(`agent_presence?agent_email=eq.${encodeURIComponent(agentEmail)}&select=session_token&limit=1`);
  if (!presence.length || presence[0].session_token !== agentToken)
    return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Token invalide' }) };

  try {
    // ── LISTE DES AGENTS DISPONIBLES ──────────────────────────────────────────
    if (action === 'list') {
      const available = await sbGet(
        `agent_presence?status=eq.online&current_session_id=is.null&agent_email=neq.${encodeURIComponent(agentEmail)}&select=agent_email&order=connected_since.asc`
      );
      if (!available.length) return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, agents: [] }) };

      const emails = available.map(a => a.agent_email);
      const profiles = await sbGet(
        `agent_profiles?email=in.(${emails.map(e => `"${e}"`).join(',')})&select=email,pseudo,prenom`
      );
      const profileMap = Object.fromEntries(profiles.map(p => [p.email, p]));

      const agents = emails.map(email => {
        const p = profileMap[email];
        const name = p ? (p.pseudo || p.prenom || email.split('@')[0]) : email.split('@')[0];
        return { email, name };
      });

      return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, agents }) };
    }

    // ── TRANSFERT ─────────────────────────────────────────────────────────────
    if (action === 'transfer') {
      const { sessionId, targetEmail } = body;
      if (!sessionId || !targetEmail)
        return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Paramètres manquants' }) };

      // Vérifier que la session appartient à cet agent et est active
      const sessions = await sbGet(
        `chat_sessions?id=eq.${encodeURIComponent(sessionId)}&agent_email=eq.${encodeURIComponent(agentEmail)}&status=eq.active&select=id&limit=1`
      );
      if (!sessions.length)
        return { statusCode: 404, headers: CORS, body: JSON.stringify({ error: 'Session introuvable ou accès refusé' }) };

      // Vérifier que l'agent cible est toujours disponible
      const target = await sbGet(
        `agent_presence?agent_email=eq.${encodeURIComponent(targetEmail)}&status=eq.online&current_session_id=is.null&select=agent_email&limit=1`
      );
      if (!target.length)
        return { statusCode: 409, headers: CORS, body: JSON.stringify({ error: 'Agent cible non disponible' }) };

      const now = new Date().toISOString();

      // Pseudo du nouvel agent pour le message système
      const profiles = await sbGet(`agent_profiles?email=eq.${encodeURIComponent(targetEmail)}&select=pseudo,prenom&limit=1`);
      const pseudo = profiles[0]?.pseudo || profiles[0]?.prenom || targetEmail.split('@')[0];

      await Promise.all([
        // Mettre à jour la session : nouvel agent + reset assigned_at
        sbPatch(`chat_sessions?id=eq.${encodeURIComponent(sessionId)}`, {
          agent_email: targetEmail,
          assigned_at: now
        }),
        // Libérer l'agent original
        sbPatch(`agent_presence?agent_email=eq.${encodeURIComponent(agentEmail)}`, {
          status: 'online',
          current_session_id: null
        }),
        // Assigner au nouvel agent
        sbPatch(`agent_presence?agent_email=eq.${encodeURIComponent(targetEmail)}`, {
          status: 'busy',
          current_session_id: sessionId,
          last_seen: now
        }),
        // Message système
        fetch(`${SB_URL}/rest/v1/chat_messages`, {
          method: 'POST',
          headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
          body: JSON.stringify({ session_id: sessionId, content: `↗ Session transférée à ${pseudo}.`, sender_type: 'system' })
        })
      ]);

      return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true }) };
    }

    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Action inconnue' }) };
  } catch (e) {
    console.error('chat-transfer:', e.message);
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: e.message }) };
  }
};
