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

### ⚠️ À exécuter pour que Max puisse assister les écoutants

`chat_messages.sender_type` est contraint par `chat_messages_sender_type_check` : il refusait la
valeur `assistant`, donc **toutes** les réponses d'assistance de Max étaient rejetées (erreur 23514)
alors que tout le reste de la chaîne fonctionnait. Transaction explicite : si une valeur inattendue
existait déjà en base, l'ajout échoue et l'ancienne contrainte est conservée.

```sql
BEGIN;
ALTER TABLE chat_messages DROP CONSTRAINT IF EXISTS chat_messages_sender_type_check;
ALTER TABLE chat_messages
  ADD CONSTRAINT chat_messages_sender_type_check
  CHECK (sender_type IN ('visitor', 'agent', 'system', 'assistant'));
COMMIT;

-- Verrou d'assistance : trois déclencheurs peuvent appeler Max en même temps ; sans lui,
-- le visiteur verrait plusieurs réponses successives. Distinct de response_deadline,
-- qui pilote la réassignation des sessions humaines.
ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS assist_lock TIMESTAMPTZ;
```

### ⚠️ À exécuter pour les accusés de réception et l'indicateur « en train d'écrire »

Six horodatages par session, trois par interlocuteur. Aucun état n'est stocké par message :
les coches se déduisent en comparant l'heure du message à ces repères.

```sql
ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS visitor_fetched_at TIMESTAMPTZ;
ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS agent_fetched_at   TIMESTAMPTZ;
ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS visitor_seen_at    TIMESTAMPTZ;
ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS agent_seen_at      TIMESTAMPTZ;
ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS visitor_typing_at  TIMESTAMPTZ;
ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS agent_typing_at    TIMESTAMPTZ;
```

---

## ✅ Fonctionnalités complètes

- Discount fidélité affiché dans l'app agent (badge 🎁 dans panneau flottant + file d'attente)
- Programme fidélité Option A : fenêtre glissante 3 mois (localStorage `parlons_session_dates`)

## 🤖 Automatisations

- **Audit sécurité quotidien** (Routine Claude, 7h Paris) : audite le code, applique les correctifs sûrs directement sur la branche de production `claude/fix-api-keys-mobile-J4B0A`, rapport dans `security-reports/` + notification.
- **Clôture comptable mensuelle** (`netlify/functions/monthly-accounting.js`, planifiée dans `netlify.toml` le 26 à 05:00 UTC) : pour le **mois précédent**, génère et envoie par email (Resend) la **facture** et le **relevé URSSAF** de chaque écoutant, plus le **récapitulatif + rapport de contrôle** à l'admin (barème contrat art. 7, solde Pass mensuel partagé à parts égales entre les écoutants ≥ 10 sessions/mois, rapprochement Stripe, remboursements, profils/SIRET/IBAN, seuil micro-entrepreneur). Lancement manuel : espace admin → bouton « 📧 Clôture mensuelle » → `netlify/functions/accounting-run.js` (fonction **non planifiée** qui vérifie les identifiants admin puis appelle `runClosing` : Netlify refuse tout appel HTTP externe vers une fonction planifiée, d'où cette séparation obligatoire). Variables optionnelles : `PASS_COMMISSION_PCT` (défaut 50), `PASS_MIN_SESSIONS` (défaut 10), `MICRO_THRESHOLD` (défaut 77700). Les écoutants sont auto-entrepreneurs : pas de bulletin de paie, la facture est préparée pour leur compte (mandat).

- **Max, assistant d'écoute IA** (`netlify/functions/ai-reply.js`, identité interne `claude@parlonsecoute.fr`) : quand aucun écoutant humain n'est en ligne, `chat-start.js` (payant / pass) et `free-session.js` (conversation offerte) attribuent la session à Max, qui engage la conversation (intérêt pour le besoin de parler, sans délai promis), envoie un push à tous les écoutants et un **email à l'admin + aux écoutants qui ont activé « Recevoir les demandes d'écoutant »** (`agent_profiles.notify_requests`, `notify_email` sinon `email`, Resend, envoi attendu avant de répondre). **Engagement remboursement** : uniquement si le visiteur **va au bout de sa session** (`chat-close` avec `closedBy: 'timer'`, envoyé par index.html quand le minuteur atteint 0) sans qu'un humain ait pris le relais → `chat-close.js` rembourse le paiement Stripe (`refunds.create`), marque `sessions.statut = 'refunded'` et prévient le visiteur ; pass mensuel → message « non décomptée ». Page fermée / abandon → pas de remboursement (comme avec un humain) : `ai-sweep.js` (toutes les 10 min, `netlify.toml`) ferme ces sessions IA expirées avec `closedBy: 'sweep'`, sans remboursement. En cas d'échec Stripe, l'admin reçoit un email. `chat-send.js` déclenche `ai-reply` à chaque message visiteur (API Claude, modèle `claude-sonnet-5` sans réflexion étendue — contrainte des 10 s Netlify —, effort `low`, réponses courtes, protocole de crise 3114/15/112 ; en cas d'échec, email à l'admin). Quand un écoutant se connecte (`chat-presence.js` `online`), il reprend jusqu'à 3 sessions IA avec l'historique complet (`chat-poll.js` renvoie tout l'historique d'une session nouvellement attribuée). Prolongations acceptées automatiquement (`chat-extend.js`). Sessions IA enregistrées avec `agent_name = 'Max (IA)'`, exclues des honoraires (comptabilité, stats admin). **Assistance des écoutants** : Max intervient aussi sur une session tenue par un **écoutant humain** qui n'a pas répondu depuis `ASSIST_DELAY_MS` (30 s par défaut) — tchat jamais ouvert après attribution, ou écoutant occupé sur un autre visiteur. La session **reste attribuée à l'écoutant** (aucun changement de `agent_email`, donc aucune réassignation) ; la règle de décision est unique (`_assist.js`) et **trois points d'appel** la déclenchent, car aucun ne suffit seul : le sondage du visiteur, le sondage de l'écoutant (`chat-poll.js`) et surtout **`assist-sweep.js`, planifiée toutes les minutes** (`netlify.toml`) — les onglets mis en veille par les navigateurs mobiles arrêtent de sonder précisément quand l'écoutant tarde à répondre, l'assistance ne peut donc pas dépendre d'une page restée au premier plan. Chacun appelle `ai-reply` avec `assist: true`, qui revérifie toutes les conditions avant d'écrire. **Deux rythmes** : 30 s de silence pour le *premier* relais (le temps laissé à l'écoutant), puis, tant que Max porte le fil — il a parlé après le dernier message de l'écoutant, `maxCarriesThread()` — il répond au rythme d'une conversation qu'il mène seul (`ASSIST_RESUME_MS`, 1,5 s ; `chat-send.js` le déclenche alors dès l'envoi du message visiteur, comme en mode autonome). Dès que l'écoutant reprend la main, on revient aux 30 s. Verrou `chat_sessions.assist_lock` : les déclencheurs peuvent appeler simultanément, un seul écrit. Ses messages sont insérés avec `sender_type = 'assistant'` (valeur à autoriser dans `chat_messages_sender_type_check`, voir SQL ci-dessus) et Max **poursuit le fil naturellement** : aucune ligne système n'annonce son intervention (elle laisserait croire que l'écoutant s'est absenté) et il ne se présente pas. La transparence est portée par la bulle elle-même — fond bleuté signé « Max · assistant » côté visiteur, « Max a répondu pour vous » dans l'app écoutant. Pas de verrou `response_deadline` en assistance : ce champ pilote la réassignation d'une session humaine.

**Transparence** : la nature de Max (programme) est indiquée une seule fois, dans le message système d'ouverture (et dans le modal / FAQ / CGV) ; ailleurs il est simplement « Max · assistant d'écoute » et ne le rappelle jamais de lui-même, mais ne le nie jamais si on lui demande — ne pas supprimer cette mention (obligation légale, règlement IA / pratiques commerciales). **Variable Netlify requise : `ANTHROPIC_API_KEY`** (sans elle, comportement d'origine : file d'attente). Optionnel : `AI_LISTENER_MODEL`.

- **Article SEO hebdomadaire** (Routine Claude, mardi 6h Paris) : choisit une requête réelle non couverte (voir `blog/_topics.md`), rédige un article de 900-1 300 mots et le publie via `node tools/new-article.mjs article.json` → page statique `blog/<slug>.html` (template `blog/_template.html`, JSON-LD Article, canonical, OG), carte en tête de `blog.html`, URL dans `sitemap.xml`, ligne dans `blog/_topics.md` ; commit `blog:` + push + notification.
- **Plan Google Ads mensuel** (Routine Claude, le 1er à 8h Paris) : rédige `marketing/google-ads/AAAA-MM.md` (conformité, structure de campagne, mots-clés, annonces responsives avec longueurs vérifiées, négatifs, budget, suivi des conversions d'après `index.html`, plan du mois) et envoie un résumé par notification. Ne modifie pas le site.

## ✓✓ Accusés de réception et indicateur de saisie

Style SMS / WhatsApp, dans les deux sens (`index.html` visiteur, `agent-app.html` écoutant) :

| Signe | Signification | Posé par |
|---|---|---|
| ✓ | envoyé (enregistré en base) | le message existe |
| ✓✓ | reçu par l'autre | `chat-poll` en livrant les messages (`*_fetched_at`) |
| ✓✓ bleu | lu | `chat-signal` quand la conversation est ouverte **et** l'onglet au premier plan (`*_seen_at`) |

**Aucun état par message** : six horodatages sur `chat_sessions` suffisent, on compare l'heure du
message aux repères. Écriture seulement quand quelque chose arrive, pas à chaque sondage.

**« … en train d'écrire »** : `netlify/functions/chat-signal.js`, appelé dès la première frappe
(limité à un envoi toutes les 3 s) — le sondage seul serait trop lent, l'indicateur arriverait après
le message. Il s'éteint tout seul après 8 s sans nouvelle frappe : aucun signal d'arrêt à envoyer,
rien ne peut rester bloqué. La lecture se fait dans la réponse de `chat-poll`, sans requête en plus.
Côté écoutant, `chat-signal` vérifie le jeton **et** que la session lui est attribuée : sans ce
contrôle, n'importe qui pourrait faire croire au visiteur qu'on lui répond.

**Max** affiche aussi « … » pendant qu'il rédige (`ai-reply.js`), y compris pendant que `chat-poll`
retient volontairement sa réponse le temps « de lire et d'écrire » — c'est bien le moment où il écrit.
Répondre vaut lecture : il pose `agent_seen_at` avant d'insérer.

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
