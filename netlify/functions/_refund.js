// ─────────────────────────────────────────────────────────────────────────────
// Remboursement des sessions tenues par Max, à la demande du visiteur.
//
// Le remboursement n'est plus automatique : le visiteur allé au bout de sa
// session sans qu'un écoutant humain le rejoigne se voit **proposer** le
// remboursement, et l'obtient en un clic. Beaucoup n'en feront pas la demande ;
// ceux qui la font sont remboursés immédiatement, sans intervention ni attente.
//
// L'éligibilité n'est pas recalculée au moment de la demande : elle ne peut pas
// l'être. Seul `chat-close` sait si la session s'est terminée à son terme
// (`closedBy: 'timer'`) ou si le visiteur a simplement quitté la page — et ce
// second cas n'ouvre aucun droit, comme avec un écoutant humain. C'est donc le
// message d'offre, écrit par `chat-close` au moment de la fermeture, qui fait
// foi : `refund-request` vérifie sa présence dans la conversation.
//
// Le texte commence par une phrase stable qui sert de repère aux deux bouts —
// ne pas la modifier sans modifier aussi la constante côté index.html.
// ─────────────────────────────────────────────────────────────────────────────

// Repère d'éligibilité. Reproduit dans index.html (constante REMB_PREFIXE).
const OFFRE_PREFIXE = 'Aucun écoutant n\'a pu vous rejoindre pendant toute votre session.';

const OFFRE_MESSAGE = `${OFFRE_PREFIXE} Vous avez donc droit au remboursement intégral de ce que vous avez payé : demandez-le ci-dessous, c'est immédiat et sans justification.`;

module.exports = { OFFRE_PREFIXE, OFFRE_MESSAGE };
