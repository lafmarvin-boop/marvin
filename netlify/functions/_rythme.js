// ─────────────────────────────────────────────────────────────────────────────
// Rythme de réponse de Max — source unique, partagée par les deux bouts.
//
// Une réponse instantanée trahit la machine. On découpe donc l'attente comme
// elle se découpe chez un humain :
//
//   message du visiteur
//     │  ✓✓ reçu                 posé tout de suite par _ai-core
//     │  ✓✓ lu                   posé tout de suite lui aussi (~1,5 s)
//     │  TEMPS DE LECTURE        rien ne bouge ; 3 à 15 s selon la longueur
//     │  « … en train d'écrire » allumé par _ai-core, et par `retenu` côté
//     │                          chat-poll — les deux respectent ce délai
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

// ── Temps de lecture : entre le « lu » bleu et l'apparition de « … » ─────────
// Trois paliers, calés sur le nombre de lignes qu'occupe le message du visiteur
// (~45 caractères par ligne sur mobile, même calibrage que SEUIL_TROIS_LIGNES) :
//
//   message court, une ligne      →  3 à 4 s
//   deux lignes                   →  4 à 6 s
//   au-delà                       →  8 à 15 s, selon la longueur
//
// Le saut entre 6 s et 8 s au passage de la deuxième à la troisième ligne est
// voulu : c'est le moment où on cesse de parcourir un message pour le lire.
const UNE_LIGNE_CAR  = 50;
const DEUX_LIGNES_CAR = 95;   // = SEUIL_TROIS_LIGNES
const LONG_PLAFOND_CAR = 400; // au-delà, la lecture plafonne à 15 s

function tempsLectureMs(visitorMsg) {
  const n = String((visitorMsg && visitorMsg.content) || '').trim().length;
  if (n <= UNE_LIGNE_CAR) {
    return Math.round(3000 + (n / UNE_LIGNE_CAR) * 1000);                    // 3 → 4 s
  }
  if (n <= DEUX_LIGNES_CAR) {
    const t = (n - UNE_LIGNE_CAR) / (DEUX_LIGNES_CAR - UNE_LIGNE_CAR);
    return Math.round(4000 + t * 2000);                                      // 4 → 6 s
  }
  const t = Math.min(1, (n - DEUX_LIGNES_CAR) / (LONG_PLAFOND_CAR - DEUX_LIGNES_CAR));
  return Math.round(8000 + t * 7000);                                        // 8 → 15 s
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
  SEUIL_TROIS_LIGNES, UNE_LIGNE_CAR, DEUX_LIGNES_CAR, LONG_PLAFOND_CAR,
  tempsLectureMs, tempsEcritureMs, delaiTotalMs
};
