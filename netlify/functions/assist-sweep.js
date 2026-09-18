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

const { AI_EMAIL, ASSIST_FIRST_MS, assistDecision } = require('./_assist');

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
      `chat_sessions?status=eq.active&agent_email=not.is.null&agent_email=neq.${encodeURIComponent(AI_EMAIL)}&select=id,assigned_at,agent_typing_at&order=assigned_at.desc&limit=200`
    );

    const siteUrl = process.env.SITE_URL || process.env.URL || 'https://parlonsecoute.fr';
    let declenchees = 0;

    await Promise.all(sessions.map(async (s) => {
      const msgs = await sbGet(`chat_messages?session_id=eq.${encodeURIComponent(s.id)}&select=id,sender_type,created_at&order=created_at.desc&limit=12`);
      const last = msgs.find(m => m.sender_type !== 'system');
      const d = assistDecision(msgs, s.assigned_at, { agentTypingAt: s.agent_typing_at });
      if (!d.go) return;
      declenchees++;
      try {
        await fetch(`${siteUrl}/.netlify/functions/ai-reply`, {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            sessionId: s.id,
            messageId: last && last.sender_type === 'visitor' ? last.id : null,
            assist: true
          }),
          // 3 s : le temps que la requête parte. ai-reply poursuit de son côté — inutile de
          // l'attendre, et avec beaucoup de sessions en parallèle l'attente coûterait cher.
          signal: AbortSignal.timeout(3000)
        });
      } catch { /* ai-reply poursuit de son côté */ }
    }));

    // ── Sessions en file d'attente : le trou que personne ne couvrait ──
    // Une session non attribuée — jamais prise, ou rendue à la file parce que l'écoutant n'a pas
    // envoyé son premier message dans les deux minutes — n'a **aucun** interlocuteur : Max n'est
    // attribué qu'au démarrage, et seulement si personne n'est en ligne. Le visiteur pouvait donc
    // écrire dans le vide indéfiniment, y compris en situation grave. Max prend le relais.
    const enAttente = await sbGet(`chat_sessions?status=eq.waiting&select=id,created_at&order=created_at.asc&limit=50`);
    let reprises = 0;
    await Promise.all(enAttente.map(async (s) => {
      if (Date.now() - new Date(s.created_at || Date.now()).getTime() < ASSIST_FIRST_MS) return;
      // Attribution conditionnelle : si un écoutant l'a prise entre-temps, la mise à jour ne
      // touche aucune ligne et Max s'abstient.
      const pr = await fetch(`${SB_URL}/rest/v1/chat_sessions?id=eq.${encodeURIComponent(s.id)}&status=eq.waiting`, {
        method: 'PATCH', headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=representation' },
        body: JSON.stringify({ agent_email: AI_EMAIL, status: 'active', assigned_at: new Date().toISOString(), response_deadline: null })
      });
      const pris = await pr.json().catch(() => []);
      if (!Array.isArray(pris) || !pris.length) return;
      reprises++;
      try {
        await fetch(`${siteUrl}/.netlify/functions/ai-reply`, {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ sessionId: s.id }),
          signal: AbortSignal.timeout(3000)
        });
      } catch { /* ai-reply poursuit de son côté */ }
    }));
    if (reprises) console.log(`assist-sweep : ${reprises} session(s) reprise(s) en file d'attente`);

    if (declenchees) console.log(`assist-sweep : ${declenchees}/${sessions.length} session(s) assistée(s)`);
    return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, sessions: sessions.length, declenchees, reprises }) };
  } catch (e) {
    console.error('assist-sweep:', e.message);
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: e.message }) };
  }
};
