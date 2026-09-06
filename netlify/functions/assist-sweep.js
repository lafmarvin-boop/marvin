// ─────────────────────────────────────────────────────────────────────────────
// Balayage d'assistance — planifié toutes les minutes (netlify.toml).
//
// Max assiste un écoutant humain silencieux depuis plus de ASSIST_DELAY_MS.
// Le déclenchement se fait aussi depuis les sondages (chat-poll.js), mais aucun
// des deux n'est fiable seul : la page du visiteur passe en arrière-plan dès
// qu'il change d'application (les navigateurs mobiles y suspendent les
// minuteurs), et l'application de l'écoutant peut être en arrière-plan pour la
// même raison — c'est précisément le cas où il tarde à répondre. Ce balayage,
// exécuté côté serveur, ne dépend d'aucun onglet resté au premier plan.
//
// Il ne décide de rien : il applique la règle commune (_assist.js) puis appelle
// ai-reply, qui revérifie tout avant d'écrire.
// ─────────────────────────────────────────────────────────────────────────────

const { AI_EMAIL, maxShouldAssist } = require('./_assist');
const { trace } = require('./_trace'); // TEMPORAIRE (voir _trace.js)

const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;

const CORS = { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' };
const H = () => ({ apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` });

async function sbGet(path) {
  const res = await fetch(`${SB_URL}/rest/v1/${path}`, { headers: H() });
  const d = await res.json();
  return Array.isArray(d) ? d : [];
}

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (!SB_URL || !SB_KEY) return { statusCode: 503, headers: CORS, body: JSON.stringify({ error: 'Service non configuré' }) };
  if (!process.env.ANTHROPIC_API_KEY) return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, skipped: 'pas de clé API' }) };

  try {
    // Sessions actives tenues par un écoutant humain
    const sessions = await sbGet(
      `chat_sessions?status=eq.active&agent_email=not.is.null&agent_email=neq.${encodeURIComponent(AI_EMAIL)}&select=id,assigned_at&order=assigned_at.desc&limit=200`
    );

    const siteUrl = process.env.SITE_URL || process.env.URL || 'https://parlonsecoute.fr';
    let declenchees = 0;

    const decisions = [];
    await Promise.all(sessions.map(async (s) => {
      const msgs = await sbGet(`chat_messages?session_id=eq.${encodeURIComponent(s.id)}&select=id,sender_type,created_at&order=created_at.desc&limit=12`);
      const last = msgs.find(m => m.sender_type !== 'system');
      const go = maxShouldAssist(msgs, s.assigned_at);
      decisions.push({ s: s.id.slice(0, 8), n: msgs.length, dernier: last?.sender_type || null, go });
      if (!go) return;
      declenchees++;
      let reponse = null;
      try {
        reponse = await fetch(`${siteUrl}/.netlify/functions/ai-reply`, {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            sessionId: s.id,
            messageId: last && last.sender_type === 'visitor' ? last.id : null,
            assist: true
          }),
          // 3 s : le temps que la requête parte. ai-reply poursuit de son côté — inutile de
          // l'attendre, et avec beaucoup de sessions en parallèle l'attente coûterait cher.
          signal: AbortSignal.timeout(3000)
        }).then(() => 'appelé');
      } catch (e) { reponse = `échec appel: ${e.message}`; }
      decisions[decisions.length - 1].aiReply = reponse;
    }));

    // TEMPORAIRE : trace chaque passage dès qu'il y a une session à examiner, sinon une fois par
    // heure — de quoi vérifier que la planification s'exécute vraiment, sans remplir la table.
    if (sessions.length || new Date().getUTCMinutes() === 0) trace('sweep', { n: sessions.length, decisions });

    if (declenchees) console.log(`assist-sweep : ${declenchees}/${sessions.length} session(s) assistée(s)`);
    return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, sessions: sessions.length, declenchees, decisions }) };
  } catch (e) {
    console.error('assist-sweep:', e.message);
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: e.message }) };
  }
};
