const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;

const CORS = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type',
};

const H = () => ({ apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` });

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };

  let body;
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Bad Request' }; }

  if (!SB_URL || !SB_KEY) return { statusCode: 503, headers: CORS, body: JSON.stringify({ error: 'Service non configuré' }) };

  const { sessionId, agentEmail, agentToken } = body;
  if (!sessionId || !agentEmail || !agentToken)
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Paramètres manquants' }) };

  try {
    // 1. Vérifier le token de l'agent
    const presRes = await fetch(
      `${SB_URL}/rest/v1/agent_presence?agent_email=eq.${encodeURIComponent(agentEmail)}&select=session_token,status&limit=1`,
      { headers: H() }
    );
    const presRows = await presRes.json();
    if (!Array.isArray(presRows) || !presRows.length || presRows[0].session_token !== agentToken)
      return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Token invalide' }) };

    // 2. Vérifier que la session est bien en attente
    const sessRes = await fetch(
      `${SB_URL}/rest/v1/chat_sessions?id=eq.${encodeURIComponent(sessionId)}&status=eq.waiting&select=id,pre_name,pre_topic,session_label,duration_sec&limit=1`,
      { headers: H() }
    );
    const sessRows = await sessRes.json();
    if (!Array.isArray(sessRows) || !sessRows.length)
      return { statusCode: 404, headers: CORS, body: JSON.stringify({ error: 'Session introuvable ou déjà prise' }) };

    const session = sessRows[0];
    const now = new Date().toISOString();

    // 3. Mettre à jour la session : assigner l'agent, passer en active
    const patchRes = await fetch(
      `${SB_URL}/rest/v1/chat_sessions?id=eq.${encodeURIComponent(sessionId)}`,
      {
        method: 'PATCH',
        headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=representation' },
        body: JSON.stringify({ agent_email: agentEmail, status: 'active', assigned_at: now })
      }
    );
    const patchRows = await patchRes.json();
    const updatedSession = (Array.isArray(patchRows) && patchRows[0]) || session;

    // 4. Chercher le pseudo de l'agent
    const profRes = await fetch(
      `${SB_URL}/rest/v1/agent_profiles?email=eq.${encodeURIComponent(agentEmail)}&select=pseudo,prenom&limit=1`,
      { headers: H() }
    );
    const profRows = await profRes.json();
    const agentPseudo = (Array.isArray(profRows) && profRows[0])
      ? (profRows[0].pseudo || profRows[0].prenom || null)
      : null;
    const greeting = agentPseudo
      ? `${agentPseudo} vous a rejoint. La session peut commencer.`
      : 'Un écoutant vous a rejoint. La session peut commencer.';

    await fetch(`${SB_URL}/rest/v1/chat_messages`, {
      method: 'POST',
      headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
      body: JSON.stringify({ session_id: sessionId, content: greeting, sender_type: 'system' })
    });

    // 5. Mettre à jour current_session_id dans agent_presence (awaité pour éviter la race condition)
    await fetch(
      `${SB_URL}/rest/v1/agent_presence?agent_email=eq.${encodeURIComponent(agentEmail)}`,
      {
        method: 'PATCH',
        headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
        body: JSON.stringify({ current_session_id: sessionId, status: 'busy', last_seen: now })
      }
    );

    return {
      statusCode: 200, headers: CORS,
      body: JSON.stringify({ ok: true, session: updatedSession })
    };
  } catch (e) {
    console.error('chat-take:', e.message);
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: e.message }) };
  }
};
