// ─────────────────────────────────────────────────────────────────────────────
// Signaux de conversation : « en train d'écrire » et « lu ».
//
// Volontairement séparé de chat-poll : le sondage tourne toutes les 2,5-3 s, mais
// « en train d'écrire » doit partir dès la première frappe, sinon l'indicateur
// arrive après le message lui-même. La lecture des signaux, elle, se fait bien
// dans la réponse de chat-poll — aucune requête supplémentaire de ce côté.
//
// Quatre horodatages par session, deux par interlocuteur (chat_sessions) :
//   *_typing_at : dernière frappe ; l'indicateur « … » s'éteint seul après 8 s
//                 (aucun signal d'arrêt à envoyer, rien ne reste bloqué).
//   *_seen_at   : dernier moment où la conversation était réellement affichée.
// « Reçu » ne passe pas par ici : chat-poll le pose lui-même en livrant les
// messages, ce qui est exactement ce que le double signe veut dire.
//
// Confidentialité : ces champs ne disent rien du contenu, seulement qu'une
// conversation est ouverte ou en cours de saisie.
// ─────────────────────────────────────────────────────────────────────────────

const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;

const CORS = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type'
};
const H = () => ({ apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` });

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };
  if (!SB_URL || !SB_KEY) return { statusCode: 503, headers: CORS, body: JSON.stringify({ error: 'Service non configuré' }) };

  let body;
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Bad Request' }; }

  const { sessionId, role } = body;
  if (!sessionId || (role !== 'visitor' && role !== 'agent'))
    return { statusCode: 400, headers: CORS, body: JSON.stringify({ error: 'Paramètres invalides' }) };

  // Côté écoutant, la session doit être la sienne : sans ce contrôle, n'importe qui
  // pourrait faire croire au visiteur que son écoutant est en train de lui répondre.
  if (role === 'agent') {
    const { agentEmail, agentToken } = body;
    if (!agentEmail || !agentToken)
      return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Non authentifié' }) };
    const res = await fetch(`${SB_URL}/rest/v1/agent_presence?agent_email=eq.${encodeURIComponent(agentEmail)}&select=session_token&limit=1`, { headers: H() });
    const rows = await res.json().catch(() => []);
    if (!Array.isArray(rows) || !rows.length || rows[0].session_token !== agentToken)
      return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Token invalide' }) };
  }

  const now = new Date().toISOString();
  const patch = {};
  if (body.typing === true) patch[`${role}_typing_at`] = now;
  if (body.seen === true) patch[`${role}_seen_at`] = now;
  if (!Object.keys(patch).length)
    return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, skipped: 'rien à signaler' }) };

  // Un écoutant ne signale que sur une session qui lui est attribuée.
  const scope = role === 'agent'
    ? `&agent_email=eq.${encodeURIComponent((body.agentEmail || '').toLowerCase())}`
    : '';

  try {
    await fetch(`${SB_URL}/rest/v1/chat_sessions?id=eq.${encodeURIComponent(sessionId)}&status=eq.active${scope}`, {
      method: 'PATCH',
      headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' },
      body: JSON.stringify(patch)
    });
    return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true }) };
  } catch (e) {
    console.error('chat-signal:', e.message);
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: e.message }) };
  }
};
