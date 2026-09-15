// ─────────────────────────────────────────────────────────────────────────────
// Traçage TEMPORAIRE de l'assistance de Max (2e campagne, sept. 2026).
//
// Les journaux Netlify ne sont pas consultables depuis la session de
// développement : sans trace, chaque hypothèse reste invérifiable. Les lignes
// sont écrites dans `suggestions` avec `payment_id = 'TRACE'`, exclues du
// tableau de bord admin, et ne contiennent aucun contenu de conversation —
// uniquement des identifiants tronqués, des types de messages et des durées.
//
// À SUPPRIMER une fois la cause trouvée : ce fichier, ses appels dans
// assist-sweep.js et _ai-core.js, le filtre TRACE d'admin-stats.js et le
// diagnostic `diag: 'trace'` d'ai-reply.js.
// ─────────────────────────────────────────────────────────────────────────────
const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;

function trace(tag, data) {
  if (!SB_URL || !SB_KEY) return;
  const content = `[${new Date().toISOString()}] ${tag} ${JSON.stringify(data)}`.slice(0, 900);
  fetch(`${SB_URL}/rest/v1/suggestions`, {
    method: 'POST',
    headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}`, 'Content-Type': 'application/json', Prefer: 'return=minimal' },
    body: JSON.stringify({ content, payment_id: 'TRACE' })
  }).catch(() => {});
}
module.exports = { trace };
