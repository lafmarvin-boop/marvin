// ─────────────────────────────────────────────────────────────────────────────
// Règle unique décidant si Max doit assister un écoutant humain silencieux.
//
// Trois points d'appel s'en servent, pour que l'assistance ne dépende pas d'un
// onglet resté au premier plan : le sondage du visiteur, le sondage de
// l'écoutant (chat-poll.js) et le balayage planifié à la minute
// (assist-sweep.js). ai-reply.js revérifie ensuite les mêmes conditions avant
// d'écrire quoi que ce soit — le déclencheur ne décide jamais seul.
// ─────────────────────────────────────────────────────────────────────────────

const AI_EMAIL = 'claude@parlonsecoute.fr';
// Silence toléré avant que Max n'intervienne (tchat jamais ouvert, ou écoutant occupé ailleurs)
const ASSIST_DELAY_MS = parseInt(process.env.ASSIST_DELAY_MS || '30000', 10);

// msgs : messages de la session du plus récent au plus ancien (order=created_at.desc)
function maxShouldAssist(msgs, assignedAt) {
  // Les messages système (« l'utilisateur a quitté la page », prolongation, reprise…)
  // s'intercalent et masqueraient le fait que le visiteur attend une réponse.
  const last = msgs.find(m => m.sender_type !== 'system');
  const humanSpoke = msgs.some(m => m.sender_type === 'agent');
  const anyAssist = msgs.some(m => m.sender_type === 'assistant');
  // Max vient-il déjà d'intervenir ? Évite d'appeler ai-reply à chaque sondage pour rien.
  const recentAssist = msgs.some(m => m.sender_type === 'assistant'
    && Date.now() - new Date(m.created_at).getTime() < 20000);
  const waitingMs = last && last.sender_type === 'visitor'
    ? Date.now() - new Date(last.created_at).getTime() : 0;
  const idle = Date.now() - new Date(assignedAt || Date.now()).getTime();
  return !recentAssist && (
       waitingMs > ASSIST_DELAY_MS                                  // visiteur sans réponse
    || (!humanSpoke && !anyAssist && idle > ASSIST_DELAY_MS));      // tchat jamais ouvert
}

module.exports = { AI_EMAIL, ASSIST_DELAY_MS, maxShouldAssist };
