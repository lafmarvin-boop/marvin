// ─────────────────────────────────────────────────────────────────────────────
// Co-pilote de l'écoutant — propositions de réponse.
//
// À la demande de l'écoutant (bouton 💡), propose trois formulations possibles
// pour poursuivre la conversation. Elles sont **insérées dans sa zone de saisie**,
// jamais envoyées : c'est lui qui relit, adapte et appuie sur envoyer.
//
// Cette limite n'est pas cosmétique. Max, quand il assiste, parle en son nom et
// sa bulle le dit (« Max »). Ici le message part au nom de l'écoutant : il faut
// donc qu'un humain l'ait réellement choisi et puisse le modifier, comme une
// correction orthographique ou une suggestion de réponse d'une messagerie. Une
// interface qui enverrait la proposition d'un seul geste ferait passer un texte
// automatique pour une parole humaine — ce que ni la loi ni la charte n'admettent.
//
// Rien n'est écrit en base : cette fonction ne fait que lire et proposer.
// ─────────────────────────────────────────────────────────────────────────────

const Anthropic = require('@anthropic-ai/sdk');

const SB_URL = process.env.SUPABASE_URL;
const SB_KEY = process.env.SUPABASE_SERVICE_KEY;
// Même contrainte que ai-reply : Netlify coupe à 10 s, donc modèle rapide et réponse courte.
const MODEL = process.env.AI_SUGGEST_MODEL || process.env.AI_LISTENER_MODEL || 'claude-sonnet-5';

const CORS = {
  'Content-Type': 'application/json',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type'
};
const H = () => ({ apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` });

async function sbGet(path) {
  const res = await fetch(`${SB_URL}/rest/v1/${path}`, { headers: H() });
  const d = await res.json();
  return Array.isArray(d) ? d : [];
}

const SYSTEM_PROMPT = `Tu assistes un écoutant de Parlons, un service français d'écoute et de soutien en ligne. Tu ne parles pas à la personne : tu proposes à l'écoutant trois façons possibles de poursuivre l'échange. C'est lui qui choisira, adaptera et enverra — écris donc des phrases qu'il pourra reprendre telles quelles, à la première personne, dans son ton.

LES TROIS PROPOSITIONS
Elles doivent être nettement différentes les unes des autres, et choisies parmi ce qui aiderait vraiment ici :
- un reflet ou une validation : nommer l'émotion sous les faits, la légitimer, sans minimiser (« ce n'est pas si grave », « il y a pire ») ni forcer le positif ;
- une question ouverte, une seule, qui aide la personne à aller un peu plus loin : ce qui s'est passé, ce qu'elle ressent, depuis quand, ce que ça touche, ce dont elle aurait besoin ;
- une reformulation qui vérifie la compréhension, un résumé de ce qui a été dit, ou parfois une simple présence — toutes les réponses n'ont pas à finir par une question.

FORME
Français, 1 à 3 phrases par proposition. Tutoiement ou vouvoiement : exactement celui déjà employé dans la conversation. Pas de mise en forme, pas d'emoji, pas de numérotation.

À ÉVITER
« Je comprends » ou « Merci de partager » en ouverture. Les formules creuses. Les conseils génériques (« essaie de te reposer », « parle-en à quelqu'un ») tant que la personne ne s'est pas sentie entendue. Deux questions à la fois. Redemander ce qui a déjà été dit.

INTERDITS
Aucun diagnostic, aucun nom de trouble (« dépression », « burn-out »), jamais de médicament. Ne fais jamais dire à l'écoutant qu'il est psychologue, psychiatre, médecin ou thérapeute.

SÉCURITÉ
Si la personne évoque des idées suicidaires, un danger immédiat, des violences ou une urgence médicale, tes trois propositions doivent servir cela et rien d'autre : vérifier si elle est en sécurité là maintenant, si quelqu'un est auprès d'elle, et transmettre les numéros adaptés — 3114 (prévention du suicide, 24h/24), 15 ou 112, 3919 (violences faites aux femmes), 119 (enfance en danger).

SORTIE
Exactement trois propositions, une par ligne, séparées par un retour à la ligne. Rien d'autre : pas de titre, pas de tiret, pas de numéro, aucun commentaire.`;

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return { statusCode: 204, headers: CORS };
  if (event.httpMethod !== 'POST') return { statusCode: 405, headers: CORS, body: 'Method Not Allowed' };
  if (!SB_URL || !SB_KEY) return { statusCode: 503, headers: CORS, body: JSON.stringify({ error: 'Service non configuré' }) };
  if (!process.env.ANTHROPIC_API_KEY) return { statusCode: 503, headers: CORS, body: JSON.stringify({ error: 'Co-pilote indisponible' }) };

  let body;
  try { body = JSON.parse(event.body || '{}'); } catch { return { statusCode: 400, headers: CORS, body: 'Bad Request' }; }

  const { sessionId, agentEmail, agentToken } = body;
  if (!sessionId || !agentEmail || !agentToken)
    return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Non authentifié' }) };

  try {
    // Le co-pilote donne accès au fil d'une conversation : l'écoutant doit être authentifié
    // ET la session doit être la sienne. Sans ce second contrôle, un écoutant pourrait lire
    // les échanges d'un collègue.
    const presence = await sbGet(`agent_presence?agent_email=eq.${encodeURIComponent(agentEmail)}&select=session_token&limit=1`);
    if (!presence.length || presence[0].session_token !== agentToken)
      return { statusCode: 401, headers: CORS, body: JSON.stringify({ error: 'Token invalide' }) };

    const sessions = await sbGet(`chat_sessions?id=eq.${encodeURIComponent(sessionId)}&select=id,status,agent_email,pre_name,pre_topic&limit=1`);
    const sess = sessions[0];
    if (!sess || sess.status !== 'active' || (sess.agent_email || '').toLowerCase() !== agentEmail.toLowerCase())
      return { statusCode: 403, headers: CORS, body: JSON.stringify({ error: 'Session non attribuée' }) };

    const msgs = await sbGet(`chat_messages?session_id=eq.${encodeURIComponent(sessionId)}&select=content,sender_type,created_at&order=created_at.asc&limit=60`);
    const dialogue = msgs.filter(m => m.sender_type !== 'system');
    if (!dialogue.length)
      return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, suggestions: [] }) };

    // L'écoutant est « assistant » du point de vue du modèle : ce sont ses répliques à lui
    // qu'on prolonge. Les messages de Max (type « assistant » en base) en font partie.
    const firstVisitor = dialogue.findIndex(m => m.sender_type === 'visitor');
    const messages = (firstVisitor < 0 ? [] : dialogue.slice(firstVisitor)).map(m => ({
      role: m.sender_type === 'visitor' ? 'user' : 'assistant',
      content: m.content
    }));
    if (!messages.length)
      return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, suggestions: [] }) };

    const contexte = `La personne s'appelle ${sess.pre_name || 'Visiteur'}${sess.pre_topic ? `, elle a indiqué comme sujet : « ${sess.pre_topic} »` : ''}. Propose maintenant trois façons de poursuivre.`;

    const client = new Anthropic({ timeout: 8000, maxRetries: 0 });
    const response = await client.messages.create({
      model: MODEL,
      max_tokens: 400,
      thinking: { type: 'disabled' },
      system: [
        { type: 'text', text: SYSTEM_PROMPT, cache_control: { type: 'ephemeral' } },
        { type: 'text', text: contexte }
      ],
      messages
    });

    const brut = response.content.filter(b => b.type === 'text').map(b => b.text).join('\n');
    // Analyse tolérante : on retire une éventuelle puce ou numérotation que le modèle
    // aurait ajoutée malgré la consigne, plutôt que de rejeter toute la réponse.
    const suggestions = brut.split('\n')
      .map(l => l.trim().replace(/^[-–—*•]\s*/, '').replace(/^\d+[.)]\s*/, '').trim())
      .filter(l => l.length > 10)
      .slice(0, 3);

    return { statusCode: 200, headers: CORS, body: JSON.stringify({ ok: true, suggestions }) };
  } catch (e) {
    console.error('ai-suggest:', e.message);
    return { statusCode: 500, headers: CORS, body: JSON.stringify({ error: 'Propositions indisponibles' }) };
  }
};
