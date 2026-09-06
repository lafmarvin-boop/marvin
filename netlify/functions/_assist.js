// ─────────────────────────────────────────────────────────────────────────────
// Règle unique décidant si Max doit assister un écoutant humain silencieux.
//
// Trois points d'appel s'en servent, pour que l'assistance ne dépende pas d'un
// onglet resté au premier plan : le sondage du visiteur, le sondage de
// l'écoutant (chat-poll.js) et le balayage planifié à la minute
// (assist-sweep.js). ai-reply.js revérifie ensuite les mêmes conditions avant
// d'écrire quoi que ce soit — le déclencheur ne décide jamais seul.
//
// Deux rythmes :
//   — PREMIER relais : 30 s de silence de l'écoutant. Le délai est volontaire,
//     c'est le temps qu'on lui laisse pour répondre lui-même.
//   — Max PORTE DÉJÀ le fil (il a parlé après le dernier mot de l'écoutant) :
//     il répond au rythme d'une conversation qu'il mène seul, sans réimposer
//     30 s à chaque message. Dès que l'écoutant reprend la main, on revient au
//     premier rythme.
// ─────────────────────────────────────────────────────────────────────────────

const AI_EMAIL = 'claude@parlonsecoute.fr';
// Silence toléré avant le premier relais (tchat jamais ouvert, ou écoutant occupé ailleurs)
const ASSIST_DELAY_MS = parseInt(process.env.ASSIST_DELAY_MS || '30000', 10);
// Une fois Max aux commandes : même réactivité que lorsqu'il mène la conversation seul
const RESUME_DELAY_MS = parseInt(process.env.ASSIST_RESUME_MS || '1500', 10);

// msgs : messages de la session du plus récent au plus ancien (order=created_at.desc)

// Max porte-t-il le fil en ce moment ? Vrai s'il a parlé après le dernier message
// de l'écoutant. (Les messages de Max quand il TIENT une session sont de type
// « agent » : sur une session reprise par un humain, ils comptent donc comme
// parole de l'écoutant — et le premier relais repasse bien par les 30 s.)
function maxCarriesThread(msgs) {
  const iAssist = msgs.findIndex(m => m.sender_type === 'assistant');
  const iAgent = msgs.findIndex(m => m.sender_type === 'agent');
  return iAssist !== -1 && (iAgent === -1 || iAssist < iAgent);
}

// Silence à attendre avant que Max ne prenne (ou reprenne) la parole
function assistDelayMs(msgs) {
  return maxCarriesThread(msgs) ? RESUME_DELAY_MS : ASSIST_DELAY_MS;
}

function maxShouldAssist(msgs, assignedAt) {
  // Les messages système (« l'utilisateur a quitté la page », prolongation, reprise…)
  // s'intercalent et masqueraient le fait que le visiteur attend une réponse.
  const last = msgs.find(m => m.sender_type !== 'system');

  // Le visiteur attend une réponse. C'est le cas courant : le délai dépend de qui
  // porte le fil. Si le dernier message est de Max ou de l'écoutant, rien n'est en
  // attente — Max ne relance jamais quelqu'un qui n'a pas écrit.
  if (last && last.sender_type === 'visitor')
    return Date.now() - new Date(last.created_at).getTime() > assistDelayMs(msgs);

  // Tchat attribué mais jamais ouvert : personne n'a encore rien dit au visiteur.
  const humanSpoke = msgs.some(m => m.sender_type === 'agent');
  const anyAssist = msgs.some(m => m.sender_type === 'assistant');
  if (!humanSpoke && !anyAssist)
    return Date.now() - new Date(assignedAt || Date.now()).getTime() > ASSIST_DELAY_MS;

  return false;
}

module.exports = { AI_EMAIL, ASSIST_DELAY_MS, RESUME_DELAY_MS, maxCarriesThread, assistDelayMs, maxShouldAssist };
