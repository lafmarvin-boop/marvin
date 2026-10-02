// ─────────────────────────────────────────────────────────────────────────────
// Max — point d'entrée unique pour tous les appelants (chat-send, chat-poll,
// assist-sweep). Il n'a pas changé d'adresse : seul le chemin d'exécution diffère.
//
// Deux voies :
//   1. Passer la main à ai-reply-background.js — profil soigné (Opus, réflexion
//      étendue), possible parce qu'une fonction background n'est pas coupée à 10 s.
//   2. Si ce relais échoue — fonctions background indisponibles sur le forfait,
//      incident réseau —, traiter ici même en profil rapide (Sonnet, sans
//      réflexion), c'est-à-dire le comportement d'origine. Max répond toujours.
//
// Le double traitement est sans danger : si les deux voies partaient malgré tout,
// les verrous de _ai-core (response_deadline / assist_lock) n'en laisseraient
// écrire qu'une seule.
// ─────────────────────────────────────────────────────────────────────────────

const { repondre } = require('./_ai-core');

const CORS = { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'Content-Type' };

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };

  let body;
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Bad Request' }; }

  // Sauf demande explicite du contraire (repli déjà tenté), on tente le profil soigné.
  if (body.rapide !== true) {
    const siteUrl = process.env.SITE_URL || process.env.URL || 'https://parlonsecoute.fr';
    try {
      const r = await fetch(`${siteUrl}/.netlify/functions/ai-reply-background`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        // Une fonction background répond 202 sans attendre : 3 s suffisent largement.
        signal: AbortSignal.timeout(3000)
      });
      if (r.status === 202)
        return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, mode: 'background' }) };
      console.warn('ai-reply : background indisponible (HTTP ' + r.status + '), repli en profil rapide');
    } catch (e) {
      console.warn('ai-reply : relais background impossible (' + e.message + '), repli en profil rapide');
    }
  }

  return repondre(body, { rapide: true });
};
