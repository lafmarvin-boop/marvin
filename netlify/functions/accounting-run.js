// ─────────────────────────────────────────────────────────────────────────────
// Lancement manuel de la clôture comptable — bouton « Clôture mensuelle » (espace.html)
//
// Pourquoi une fonction séparée : `monthly-accounting` est déclarée comme fonction
// planifiée dans netlify.toml, et Netlify refuse tout appel HTTP externe vers une
// fonction planifiée (403) — le bouton admin ne pouvait donc pas l'atteindre.
// Cette fonction-ci n'est pas planifiée : elle vérifie les identifiants admin puis
// réutilise exactement la même logique (runClosing).
// ─────────────────────────────────────────────────────────────────────────────

const { runClosing } = require('./monthly-accounting.js');

const ADMIN_EMAIL = (process.env.ADMIN_EMAIL || '').toLowerCase();
const ADMIN_PWD   = process.env.ADMIN_PASSWORD;

const CORS = { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'Content-Type' };

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };

  let body = {};
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Bad Request' }; }

  const email = (body.adminEmail || '').toLowerCase().trim();
  if (!ADMIN_EMAIL || !ADMIN_PWD || email !== ADMIN_EMAIL || body.adminPassword !== ADMIN_PWD)
    return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Non autorisé' }) };

  try {
    const result = await runClosing({
      month: body.month,
      mode: body.mode === 'all' ? 'all' : 'admin',
      dryRun: !!body.dryRun
    });
    console.log(`accounting-run ${result.period} : ${result.verdict} · ${result.agents.length} écoutant(s) · ${result.sendErrors.length} échec(s)`);
    return { statusCode: 200, headers: CORS, body: JSON.stringify(result) };
  } catch (e) {
    console.error('accounting-run:', e);
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: e.message }) };
  }
};
