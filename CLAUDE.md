# Parlons — Mémo de session

Projet : service d'écoute anonyme en ligne (Netlify + Supabase + Stripe + Resend).

---

## ✅ Ce qui est fait

- `index.html` : paiement unique (Stripe), abonnement mensuel, notation, suggestions, fidélité Option A (localStorage), responsive mobile
- `espace.html` : login unifié admin/abonné, dashboard admin (stats, tableau agents, suggestions, abonnés, sessions récentes), dashboard abonné (démarrer session, changer mdp, résilier), responsive mobile
- `netlify/functions/` : create-payment-intent, create-subscription, stripe-webhook, cancel-subscription, change-password, subscriber-session, submit-suggestion, admin-stats
- Documents juridiques : contrat prestation v2.5, protocole agents v2.0, charte écoutant v1.0, CGV v2.0, registre RGPD v2.8
- PDF contrat + annexes : `parlons-contrat-et-annexes.pdf`
- Configuration Netlify env vars : ✅ fait
- Resend : ✅ fait
- Bouton "Demander un agent" : ✅ fonctionnel
- Nom de domaine : ✅ déjà acquis

---

## ⚠️ Seule chose restante côté configuration

### SQL à exécuter dans Supabase (si pas encore fait)

Supabase → SQL Editor → New query → Run. Tout utilise `IF NOT EXISTS`, sans risque si déjà fait.

```sql
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS rating SMALLINT;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS rating_comment TEXT;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS agent_name TEXT;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS agent_email TEXT;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMPTZ;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS visitor_id TEXT;

CREATE TABLE IF NOT EXISTS suggestions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  content TEXT NOT NULL,
  payment_id TEXT,
  created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS agent_requests (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  email TEXT NOT NULL,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  notified_at TIMESTAMPTZ
);

ALTER TABLE agent_requests ADD COLUMN IF NOT EXISTS push_subscription TEXT;

ALTER TABLE agent_profiles ADD COLUMN IF NOT EXISTS notify_email TEXT;
ALTER TABLE agent_profiles ADD COLUMN IF NOT EXISTS notify_requests BOOLEAN DEFAULT FALSE;

ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS response_deadline TIMESTAMPTZ;

ALTER TABLE sessions ADD COLUMN IF NOT EXISTS rating_comment TEXT;

ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS loyalty_discount SMALLINT DEFAULT 0;
```

---

## ✅ Fonctionnalités complètes

- Discount fidélité affiché dans l'app agent (badge 🎁 dans panneau flottant + file d'attente)
- Programme fidélité Option A : fenêtre glissante 3 mois (localStorage `parlons_session_dates`)

## 🤖 Automatisations

- **Audit sécurité quotidien** (Routine Claude, 7h Paris) : audite le code, applique les correctifs sûrs directement sur la branche de production `claude/fix-api-keys-mobile-J4B0A`, rapport dans `security-reports/` + notification.
- **Clôture comptable mensuelle** (`netlify/functions/monthly-accounting.js`, planifiée dans `netlify.toml` le 26 à 05:00 UTC) : pour le **mois précédent**, génère et envoie par email (Resend) la **facture** et le **relevé URSSAF** de chaque écoutant, plus le **récapitulatif + rapport de contrôle** à l'admin (barème contrat art. 7, solde Pass mensuel partagé à parts égales entre les écoutants ≥ 10 sessions/mois, rapprochement Stripe, remboursements, profils/SIRET/IBAN, seuil micro-entrepreneur). Lancement manuel : espace admin → bouton « 📧 Clôture mensuelle » → `netlify/functions/accounting-run.js` (fonction **non planifiée** qui vérifie les identifiants admin puis appelle `runClosing` : Netlify refuse tout appel HTTP externe vers une fonction planifiée, d'où cette séparation obligatoire). Variables optionnelles : `PASS_COMMISSION_PCT` (défaut 50), `PASS_MIN_SESSIONS` (défaut 10), `MICRO_THRESHOLD` (défaut 77700). Les écoutants sont auto-entrepreneurs : pas de bulletin de paie, la facture est préparée pour leur compte (mandat).

- **Max, assistant d'écoute IA** (`netlify/functions/ai-reply.js`, identité interne `claude@parlonsecoute.fr`) : quand aucun écoutant humain n'est en ligne, `chat-start.js` (payant / pass) et `free-session.js` (conversation offerte) attribuent la session à Max, qui engage la conversation (intérêt pour le besoin de parler, sans délai promis), envoie un push à tous les écoutants et un **email à l'admin + aux écoutants qui ont activé « Recevoir les demandes d'écoutant »** (`agent_profiles.notify_requests`, `notify_email` sinon `email`, Resend, envoi attendu avant de répondre). **Engagement remboursement** : uniquement si le visiteur **va au bout de sa session** (`chat-close` avec `closedBy: 'timer'`, envoyé par index.html quand le minuteur atteint 0) sans qu'un humain ait pris le relais → `chat-close.js` rembourse le paiement Stripe (`refunds.create`), marque `sessions.statut = 'refunded'` et prévient le visiteur ; pass mensuel → message « non décomptée ». Page fermée / abandon → pas de remboursement (comme avec un humain) : `ai-sweep.js` (toutes les 10 min, `netlify.toml`) ferme ces sessions IA expirées avec `closedBy: 'sweep'`, sans remboursement. En cas d'échec Stripe, l'admin reçoit un email. `chat-send.js` déclenche `ai-reply` à chaque message visiteur (API Claude, modèle `claude-sonnet-5` sans réflexion étendue — contrainte des 10 s Netlify —, effort `low`, réponses courtes, protocole de crise 3114/15/112 ; en cas d'échec, email à l'admin). Quand un écoutant se connecte (`chat-presence.js` `online`), il reprend jusqu'à 3 sessions IA avec l'historique complet (`chat-poll.js` renvoie tout l'historique d'une session nouvellement attribuée). Prolongations acceptées automatiquement (`chat-extend.js`). Sessions IA enregistrées avec `agent_name = 'Max (IA)'`, exclues des honoraires (comptabilité, stats admin). **Assistance des écoutants** : Max intervient aussi sur une session tenue par un **écoutant humain** qui n'a pas répondu depuis `ASSIST_DELAY_MS` (30 s par défaut) — tchat jamais ouvert après attribution, ou écoutant occupé sur un autre visiteur. La session **reste attribuée à l'écoutant** (aucun changement de `agent_email`, donc aucune réassignation) ; `chat-poll` déclenche `ai-reply` avec `assist: true` et la fonction revérifie les conditions. Ses messages sont insérés avec `sender_type = 'assistant'` (nouveau type, `chat_messages.sender_type` est un TEXT libre) et une ligne système annonce chaque épisode (« … est occupé un instant. Max prend le relais »), le visiteur ne croit donc jamais parler à l'écoutant. Un épisode se termine dès que l'écoutant reprend la parole. Affichage dédié côté visiteur (« Max · assistant ») et côté app écoutant (« Max a répondu pour vous »). Pas de verrou `response_deadline` en assistance : ce champ pilote la réassignation d'une session humaine.

**Transparence** : la nature de Max (programme) est indiquée une seule fois, dans le message système d'ouverture (et dans le modal / FAQ / CGV) ; ailleurs il est simplement « Max · assistant d'écoute » et ne le rappelle jamais de lui-même, mais ne le nie jamais si on lui demande — ne pas supprimer cette mention (obligation légale, règlement IA / pratiques commerciales). **Variable Netlify requise : `ANTHROPIC_API_KEY`** (sans elle, comportement d'origine : file d'attente). Optionnel : `AI_LISTENER_MODEL`.

- **Article SEO hebdomadaire** (Routine Claude, mardi 6h Paris) : choisit une requête réelle non couverte (voir `blog/_topics.md`), rédige un article de 900-1 300 mots et le publie via `node tools/new-article.mjs article.json` → page statique `blog/<slug>.html` (template `blog/_template.html`, JSON-LD Article, canonical, OG), carte en tête de `blog.html`, URL dans `sitemap.xml`, ligne dans `blog/_topics.md` ; commit `blog:` + push + notification.
- **Plan Google Ads mensuel** (Routine Claude, le 1er à 8h Paris) : rédige `marketing/google-ads/AAAA-MM.md` (conformité, structure de campagne, mots-clés, annonces responsives avec longueurs vérifiées, négatifs, budget, suivi des conversions d'après `index.html`, plan du mois) et envoie un résumé par notification. Ne modifie pas le site.

## 🔐 Authentification par jetons signés

Depuis sept. 2026, **aucun mot de passe n'est conservé dans le navigateur**. Au login, le serveur
émet un jeton signé HMAC-SHA256 (`netlify/functions/_auth.js`, secret `AUTH_SECRET` sinon
`SUPABASE_SERVICE_KEY`) que le navigateur stocke à la place :

| Interface | Stockage | Champ envoyé | Durée |
|---|---|---|---|
| `espace.html` admin / abonné | `parlons_espace_session.token` | `token`, `adminToken` | 24 h |
| `espace.html` / `agent-app.html` écoutant | `parlons_agent_authtoken` | `token`, `authToken` | 30 j |

Le compte administrateur reçoit un jeton de rôle `admin`, accepté aussi par les fonctions écoutant
(`role: ['agent','admin']`). Les fonctions acceptent **le mot de passe ou le jeton** : le formulaire de
connexion continue d'envoyer le mot de passe, la reconnexion automatique le jeton. **Le changement de
mot de passe exige toujours le mot de passe actuel**, jamais un jeton.

Durées de session : `netlify/functions/_plans.js` est la seule source de vérité (dérivée du montant ou
du libellé), jamais la valeur envoyée par le navigateur.

## 🚧 En attente

1. **Fidélité Option B (futur)** — tracking par email (cross-device). Mis en attente.

## 🗑️ Supprimé

- **Chat de groupe** (sept. 2026) : page `groupe.html`, fonctions `netlify/functions/group-*.js`, tables `group_access` / `group_messages` retirées du code, des documents juridiques (HTML + PDF régénérés), du service worker et du sitemap. Tables supprimées côté Supabase (fait manuellement).

---

## 🔑 Accès admin espace.html

- URL : `/espace.html`
- Email : `lafmarvin@gmail.com`
- Mot de passe : valeur de `ADMIN_PASSWORD` (env var Netlify)
