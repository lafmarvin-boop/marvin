// ─────────────────────────────────────────────────────────────────────────────
// Max — exécution « soignée » de la réponse (fonction background).
//
// Le suffixe `-background` est ce qui, chez Netlify, lève la coupure à 10 s :
// la fonction répond 202 immédiatement et poursuit son travail jusqu'à 15 min.
// C'est ce qui permet à Max d'utiliser un modèle plus capable avec réflexion
// étendue — impossible dans une fonction classique.
//
// Toute la logique vit dans _ai-core.js ; ce fichier n'est qu'un point d'entrée.
// L'appelant est ai-reply.js, qui bascule ici quand c'est disponible.
// ─────────────────────────────────────────────────────────────────────────────

const { repondre } = require('./_ai-core');

exports.handler = async (event) => {
  if (event.httpMethod !== 'POST') return { statusCode: 405, body: 'Method Not Allowed' };
  let body;
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, body: 'Bad Request' }; }
  // Le retour n'est lu par personne (Netlify a déjà répondu 202 à l'appelant) :
  // les erreurs éventuelles sont journalisées et signalées par courriel dans _ai-core.
  return repondre(body, { rapide: false });
};
