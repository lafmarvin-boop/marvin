const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;
const ADMIN_PWD = process.env.ADMIN_PASSWORD;
const ADMIN_EMAIL = (process.env.ADMIN_EMAIL || '').toLowerCase();

const CORS = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type'
};

const H = () => ({ apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` });

async function sbGet(path) {
  const res = await fetch(`${SB_URL}/rest/v1/${path}`, { headers: H() });
  const d = await res.json();
  return Array.isArray(d) ? d : [];
}

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };

  let body;
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Bad Request' }; }

  if (!SB_URL || !SB_KEY) return { statusCode: 503, headers: CORS, body: JSON.stringify({ error: 'Service non configuré' }) };

  const email = (body.email || '').toLowerCase().trim();
  const password = body.password || '';
  if (!ADMIN_EMAIL || email !== ADMIN_EMAIL || !ADMIN_PWD || password !== ADMIN_PWD)
    return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Non autorisé' }) };

  const { action, sessionId } = body;

  try {
    if (action === 'list') {
      const since = new Date(Date.now() - 30 * 24 * 3600 * 1000).toISOString();
      const sessions = await sbGet(
        `chat_sessions?status=eq.closed&closed_at=gte.${encodeURIComponent(since)}&select=id,pre_name,pre_topic,session_label,duration_sec,agent_email,assigned_at,closed_at,session_type&order=closed_at.desc&limit=100`
      );

      // Enrich with agent pseudos
      const uniqueEmails = [...new Set(sessions.filter(s => s.agent_email).map(s => s.agent_email))];
      const profileMap = {};
      await Promise.all(uniqueEmails.map(async em => {
        const p = await sbGet(`agent_profiles?email=eq.${encodeURIComponent(em)}&select=pseudo,prenom&limit=1`);
        if (p[0]) profileMap[em] = p[0].pseudo || p[0].prenom || em;
      }));

      return {
        statusCode: 200, headers: CORS,
        body: JSON.stringify({
          sessions: sessions.map(s => ({
            ...s,
            agentName: s.agent_email ? (profileMap[s.agent_email] || s.agent_email) : null
          }))
        })
      };
    }

    if (action === 'messages') {
      if (!sessionId) return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'sessionId requis' }) };
      const messages = await sbGet(
        `chat_messages?session_id=eq.${encodeURIComponent(sessionId)}&order=created_at.asc&select=id,content,sender_type,created_at`
      );
      return { statusCode: 200, headers: CORS, body: JSON.stringify({ messages }) };
    }

    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Action invalide' }) };
  } catch (e) {
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: e.message }) };
  }
};
