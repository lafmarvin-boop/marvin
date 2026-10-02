// ─────────────────────────────────────────────────────────────────────────────
// Règle unique décidant si Max doit prendre la parole sur une session tenue par
// un écoutant humain.
//
// Trois points d'appel s'en servent, pour que l'assistance ne dépende pas d'un
// onglet resté au premier plan : le sondage du visiteur, le sondage de
// l'écoutant (chat-poll.js) et le balayage planifié à la minute
// (assist-sweep.js). ai-reply.js revérifie ensuite les mêmes conditions avant
// d'écrire quoi que ce soit — le déclencheur ne décide jamais seul.
//
// Trois rythmes, selon la situation :
//   — PREMIER CONTACT (`ASSIST_FIRST_MS`, 10 s) : le tchat vient d'être attribué
//     et l'écoutant n'a pas encore dit un mot. Le visiteur ne doit pas rester
//     seul devant un écran muet ; Max engage, l'écoutant reprend dès qu'il voit.
//   — SILENCE EN COURS D'ÉCHANGE (`ASSIST_DELAY_MS`, 20 s) : l'écoutant a déjà
//     parlé mais tarde à répondre (il gère peut-être un autre visiteur). Le
//     délai est plus long : c'est le temps qu'on lui laisse pour revenir.
//   — MAX PORTE DÉJÀ LE FIL (`ASSIST_RESUME_MS`, 1,5 s) : il a parlé après le
//     dernier message de l'écoutant, il répond donc au rythme d'une conversation
//     qu'il mène seul. Dès que l'écoutant reprend la main, on ressort d'ici.
//
// Dans tous les cas, Max se tait si l'écoutant est **en train d'écrire** : à
// 10 s, intervenir pendant qu'il rédige son accueil lui couperait la parole.
// ─────────────────────────────────────────────────────────────────────────────

const AI_EMAIL = 'claude@parlonsecoute.fr';
// Silence toléré avant que Max ne prenne la parole
const ASSIST_FIRST_MS = parseInt(process.env.ASSIST_FIRST_MS || '10000', 10);  // tchat jamais ouvert
const ASSIST_DELAY_MS = parseInt(process.env.ASSIST_DELAY_MS || '20000', 10);  // silence en cours d'échange
const RESUME_DELAY_MS = parseInt(process.env.ASSIST_RESUME_MS || '1500', 10);  // Max mène déjà l'échange
// Une frappe de moins de 8 s signale un écoutant en train d'écrire (voir chat-signal.js)
const TYPING_TTL_MS = 8000;

// msgs : messages de la session du plus récent au plus ancien (order=created_at.desc)

// Max porte-t-il le fil en ce moment ? Vrai s'il a parlé après le dernier message
// de l'écoutant. (Les messages de Max quand il TIENT une session sont de type
// « agent » : sur une session reprise par un humain, ils comptent donc comme
// parole de l'écoutant — et le premier relais repasse bien par le délai normal.)
function maxCarriesThread(msgs) {
  const iAssist = msgs.findIndex(m => m.sender_type === 'assistant');
  const iAgent = msgs.findIndex(m => m.sender_type === 'agent');
  return iAssist !== -1 && (iAgent === -1 || iAssist < iAgent);
}

// Décision complète : faut-il intervenir, à partir de quand attend-on, et quel
// seuil s'applique. ai-reply s'en sert aussi pour revérifier — les deux bouts
// doivent raisonner exactement pareil.
function assistDecision(msgs, assignedAt, opts = {}) {
  // Les messages système (« l'utilisateur a quitté la page », prolongation, reprise…)
  // s'intercalent et masqueraient le fait que le visiteur attend une réponse.
  const last = msgs.find(m => m.sender_type !== 'system');
  const humanSpoke = msgs.some(m => m.sender_type === 'agent');
  const porte = maxCarriesThread(msgs);

  // L'écoutant est en train d'écrire : on lui laisse finir sa phrase.
  if (opts.agentTypingAt && Date.now() - new Date(opts.agentTypingAt).getTime() < TYPING_TTL_MS)
    return { go: false, raison: 'ecoutant_ecrit' };

  const seuil = porte ? RESUME_DELAY_MS : (humanSpoke ? ASSIST_DELAY_MS : ASSIST_FIRST_MS);
  const visiteurMs = last && last.sender_type === 'visitor' ? new Date(last.created_at).getTime() : 0;

  let depuis;
  if (!humanSpoke && !porte) {
    // Premier contact : le décompte part de l'attribution — c'est à ce moment que
    // l'écoutant est censé voir arriver la conversation. Si le visiteur a écrit depuis,
    // c'est ce message-là qui fait référence (le plus tardif des deux).
    depuis = Math.max(new Date(assignedAt || Date.now()).getTime(), visiteurMs);
  } else {
    // Ensuite, Max ne parle que si le visiteur attend une réponse : il ne relance
    // jamais quelqu'un qui n'a rien écrit.
    depuis = visiteurMs;
  }
  if (!depuis) return { go: false, raison: 'rien_en_attente', seuil };

  const attente = Date.now() - depuis;
  return { go: attente > seuil, raison: attente > seuil ? 'ok' : 'trop_tot', seuil, depuis, attente };
}

function maxShouldAssist(msgs, assignedAt, opts) {
  return assistDecision(msgs, assignedAt, opts).go;
}

module.exports = {
  AI_EMAIL, ASSIST_FIRST_MS, ASSIST_DELAY_MS, RESUME_DELAY_MS,
  maxCarriesThread, assistDecision, maxShouldAssist
};
