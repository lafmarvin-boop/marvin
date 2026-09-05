// ─────────────────────────────────────────────────────────────────────────────
// Balayage des sessions tenues par Max (assistant IA) — planifié toutes les
// 10 minutes (netlify.toml).
//
// Un visiteur qui quitte l'onglet sans fermer sa session laisserait celle-ci
// « active » indéfiniment. Quand le temps de la formule est écoulé (+ 10 min de
// marge) et qu'aucun écoutant humain n'a pris le relais, on ferme la session via
// chat-close (closedBy 'sweep'). Pas de remboursement dans ce cas : le visiteur a
// quitté la page de lui-même, comme avec un écoutant humain. Le remboursement
// n'est déclenché que par la fin naturelle de session côté visiteur ('timer').
// ─────────────────────────────────────────────────────────────────────────────

const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;
const AI_EMAIL = 'claude@parlonsecoute.fr';
const GRACE_MS = 10 * 60 * 1000;

const CORS = { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' };
const H = () => ({ apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` });

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (!SB_URL || !SB_KEY) return { statusCode: 503, headers: CORS, body: JSON.stringify({ error: 'Service non configuré' }) };

  try {
    const res = await fetch(`${SB_URL}/rest/v1/chat_sessions?agent_email=eq.${encodeURIComponent(AI_EMAIL)}&status=eq.active&select=id,assigned_at,duration_sec,response_deadline&limit=50`, { headers: H() });
    const rows = await res.json();
    const now = Date.now();
    // Verrou de génération (response_deadline) oublié depuis > 2 min (fonction interrompue) → le lever
    await Promise.all((Array.isArray(rows) ? rows : []).filter(s => s.response_deadline && new Date(s.response_deadline).getTime() < now - 120000).map(s =>
      fetch(`${SB_URL}/rest/v1/chat_sessions?id=eq.${encodeURIComponent(s.id)}`, { method: 'PATCH', headers: { ...H(), 'Content-Type': 'application/json', Prefer: 'return=minimal' }, body: JSON.stringify({ response_deadline: null }) }).catch(() => {})
    ));
    const expired = (Array.isArray(rows) ? rows : []).filter(s => {
      const start = s.assigned_at ? new Date(s.assigned_at).getTime() : 0;
      return start && now > start + (s.duration_sec || 1800) * 1000 + GRACE_MS;
    });

    const siteUrl = process.env.SITE_URL || process.env.URL || 'https://parlonsecoute.fr';
    const results = await Promise.allSettled(expired.map(s =>
      fetch(`${siteUrl}/.netlify/functions/chat-close`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId: s.id, closedBy: 'sweep' })
      }).then(r => r.json())
    ));
    const closed = results.filter(r => r.status === 'fulfilled' && r.value?.ok).length;
    console.log(`ai-sweep: ${expired.length} session(s) IA expirée(s), ${closed} fermée(s)`);
    return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, expired: expired.length, closed }) };
  } catch (e) {
    console.error('ai-sweep:', e.message);
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: e.message }) };
  }
};
