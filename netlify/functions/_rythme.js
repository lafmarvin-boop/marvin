// ─────────────────────────────────────────────────────────────────────────────
// Rythme de réponse de Max — source unique, partagée par les deux bouts.
//
// Une réponse instantanée trahit la machine. On découpe donc l'attente comme
// elle se découpe chez un humain :
//
//   message du visiteur
//     │  ✓✓ reçu, ✓✓ lu          posés tout de suite par _ai-core
//     │  TEMPS DE LECTURE        rien ne s'affiche ; dépend de la longueur
//     │                          du message du visiteur (3 à 6 s)
//     │  « … en train d'écrire » allumé par _ai-core au bout de ce temps
//     │  TEMPS D'ÉCRITURE        dépend de la longueur de la réponse de Max
//     ▼  la réponse apparaît     chat-poll la libère
//
// ⚠️ Les deux fonctions vivent ici parce que **deux fichiers en dépendent** :
// `_ai-core.js` pour savoir quand allumer l'indicateur, `chat-poll.js` pour
// savoir quand livrer le message. Dupliquer le calcul les ferait diverger, et
// l'indicateur s'allumerait à contretemps. Même raison d'être que `_assist.js`.
//
// Tous les délais sont dérivés du contenu et de l'identifiant des messages, et
// jamais tirés au sort à l'appel : deux sondages successifs doivent trouver la
// **même** échéance, sinon la réponse apparaîtrait puis disparaîtrait.
// ─────────────────────────────────────────────────────────────────────────────

// Seuil 2/3 lignes, calibré sur des bulles réellement affichées en mobile :
// 82 caractères tenaient en deux lignes, 113 en trois. Approximation assumée,
// la largeur d'une ligne dépendant de l'appareil.
const SEUIL_TROIS_LIGNES = 95;

// ── Temps de lecture : 3 s pour un mot, jusqu'à 6 s pour un long message ──────
// Proportionnel à la longueur du message du visiteur : on ne lit pas « oui » et
// cinq lignes de confidences à la même vitesse.
const LECTURE_MIN_MS = 3000;
const LECTURE_MAX_MS = 6000;
const LECTURE_PAR_CARACTERE_MS = 18;   // ~55 caractères/s, plafonné à 6 s

function tempsLectureMs(visitorMsg) {
  const n = String((visitorMsg && visitorMsg.content) || '').trim().length;
  return Math.min(LECTURE_MAX_MS, LECTURE_MIN_MS + n * LECTURE_PAR_CARACTERE_MS);
}

// ── Temps d'écriture : il démarre une fois la lecture finie ───────────────────
// La base vient de la longueur du message du visiteur (une réponse à un long
// message se rédige rarement d'un trait), puis deux suppléments demandés après
// lecture de conversations réelles, puis un ajustement selon la longueur de la
// réponse de Max : une réaction de deux mots ne se tape pas en quinze secondes.
function tempsEcritureMs(visitorMsg, reponseMax) {
  const mots = String((visitorMsg && visitorMsg.content) || '').trim().split(/\s+/).filter(Boolean).length;
  let seed = 0;
  for (const ch of String((visitorMsg && visitorMsg.id) || '')) seed = (seed * 31 + ch.charCodeAt(0)) >>> 0;

  const base = mots < 6 ? 5000 + (seed % 3001) : 7000 + (seed % 3001);
  let ms = base + 3000 + ((seed >>> 7) % 1001) + 3000;

  const texte = String((reponseMax && reponseMax.content) || '').trim();
  if (texte) {
    const motsMax = texte.split(/\s+/).filter(Boolean).length;
    if (motsMax <= 2) ms -= 2000;                              // réaction brève
    else if (texte.length > SEUIL_TROIS_LIGNES) ms += 3000;    // trois lignes ou plus
  }
  return ms;
}

// Attente totale entre le message du visiteur et l'apparition de la réponse.
function delaiTotalMs(visitorMsg, reponseMax) {
  return tempsLectureMs(visitorMsg) + tempsEcritureMs(visitorMsg, reponseMax);
}

module.exports = {
  SEUIL_TROIS_LIGNES, LECTURE_MIN_MS, LECTURE_MAX_MS,
  tempsLectureMs, tempsEcritureMs, delaiTotalMs
};
