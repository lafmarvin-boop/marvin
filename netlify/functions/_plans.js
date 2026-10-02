// ─────────────────────────────────────────────────────────────────────────────
// Durées de session — source de vérité côté serveur
//
// La durée ne doit JAMAIS être reprise telle quelle depuis le navigateur : un
// appel direct à l'API pourrait payer le tarif le plus bas (1 €) tout en
// demandant une session d'une heure. Toutes les fonctions qui créent, paient ou
// prolongent une session dérivent la durée d'ici, à partir du montant ou du
// libellé de la formule.
// ─────────────────────────────────────────────────────────────────────────────

// Montant en centimes (ou 'sub') → durée en secondes
const SECONDS_BY_AMOUNT = {
  '100': 600,   // 10 minutes — 1,00 €
  '300': 1800,  // 30 minutes — 3,00 €
  '500': 3600,  // 1 heure    — 5,00 €
  'sub': 1800   // Pass mensuel : 30 minutes maximum par session
};

const DEFAULT_SECONDS = 1800;
const MAX_SECONDS = 3600; // aucune formule ne dépasse une heure

// Libellé de formule → durée en secondes (le libellé peut être suffixé, ex. « 10 min GRATUIT »)
function durationForLabel(label, fallback = DEFAULT_SECONDS) {
  const l = String(label || '');
  if (l.includes('GRATUIT')) return 1200;      // première conversation offerte : 20 minutes
  if (l.includes('Pass mensuel')) return 1800;
  if (l.includes('1 heure')) return 3600;
  if (l.includes('30 min')) return 1800;
  if (l.includes('10 min')) return 600;
  return fallback;
}

function durationForAmount(montant, fallback = DEFAULT_SECONDS) {
  const v = SECONDS_BY_AMOUNT[String(montant)];
  return typeof v === 'number' ? v : fallback;
}

// Plafond de sécurité pour toute durée qui transite malgré tout par le client
function clampSeconds(value, fallback = DEFAULT_SECONDS) {
  const n = parseInt(value, 10);
  if (!Number.isFinite(n) || n <= 0) return fallback;
  return Math.min(n, MAX_SECONDS);
}

module.exports = { SECONDS_BY_AMOUNT, DEFAULT_SECONDS, MAX_SECONDS, durationForLabel, durationForAmount, clampSeconds };
