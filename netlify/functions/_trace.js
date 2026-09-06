// ─────────────────────────────────────────────────────────────────────────────
// Traçage TEMPORAIRE de l'assistance de Max.
//
// Objectif : savoir ce qui se passe réellement en production sur le chemin de
// l'assistance — la fonction planifiée s'exécute-t-elle ? que décide-t-elle ?
// que répond ai-reply ? Les journaux Netlify ne sont pas lisibles d'ici.
//
// Les lignes sont écrites dans la table `suggestions` avec `payment_id = 'TRACE'`
// (aucune table à créer) et **exclues du tableau de bord admin** (admin-stats.js).
// Elles ne contiennent aucun contenu de conversation : uniquement des
// identifiants tronqués, des types de messages et des durées.
//
// À SUPPRIMER une fois l'assistance confirmée : _trace.js, ses appels dans
// assist-sweep.js / chat-poll.js / ai-reply.js, le filtre TRACE d'admin-stats.js
// et le diagnostic `diag: 'trace'` d'ai-reply.js.
// ─────────────────────────────────────────────────────────────────────────────

const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;

const TRACE_MARK = 'TRACE';

// N'attend pas : le traçage ne doit jamais ralentir ni faire échouer l'appel qu'il observe.
function trace(tag, data) {
  if (!SB_URL || !SB_KEY) return;
  const content = `[${new Date().toISOString()}] ${tag} ${JSON.stringify(data)}`.slice(0, 900);
  fetch(`${SB_URL}/rest/v1/suggestions`, {
    method: 'POST',
    headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}`, 'Content-Type': 'application/json', Prefer: 'return=minimal' },
    body: JSON.stringify({ content, payment_id: TRACE_MARK })
  }).catch(() => {});
}

module.exports = { trace, TRACE_MARK };
