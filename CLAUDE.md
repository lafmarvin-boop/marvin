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
- **Clôture comptable mensuelle** (`netlify/functions/monthly-accounting.js`, planifiée dans `netlify.toml` le 26 à 05:00 UTC) : pour le **mois précédent**, génère et envoie par email (Resend) la **facture** et le **relevé URSSAF** de chaque écoutant, plus le **récapitulatif + rapport de contrôle** à l'admin (barème contrat art. 7, solde Pass mensuel partagé à parts égales entre les écoutants ≥ 10 sessions/mois, rapprochement Stripe, remboursements, profils/SIRET/IBAN, seuil micro-entrepreneur). Lancement manuel : espace admin → bouton « 📧 Clôture mensuelle » (aperçu admin seul ou envoi à tous). Variables optionnelles : `PASS_COMMISSION_PCT` (défaut 50), `PASS_MIN_SESSIONS` (défaut 10), `MICRO_THRESHOLD` (défaut 77700). Les écoutants sont auto-entrepreneurs : pas de bulletin de paie, la facture est préparée pour leur compte (mandat).

- **Max, assistant d'écoute IA** (`netlify/functions/ai-reply.js`, identité interne `claude@parlonsecoute.fr`) : quand aucun écoutant humain n'est en ligne, `chat-start.js` attribue les sessions **payantes** (à l'unité + pass mensuel, jamais la conversation offerte) à Max, qui engage la conversation (intérêt pour le besoin de parler, sans délai promis), envoie un push à tous les écoutants et un **email à ceux qui ont activé « Recevoir les demandes d'écoutant »** (`agent_profiles.notify_requests`, Resend). **Engagement remboursement** : uniquement si le visiteur **va au bout de sa session** (`chat-close` avec `closedBy: 'timer'`, envoyé par index.html quand le minuteur atteint 0) sans qu'un humain ait pris le relais → `chat-close.js` rembourse le paiement Stripe (`refunds.create`), marque `sessions.statut = 'refunded'` et prévient le visiteur ; pass mensuel → message « non décomptée ». Page fermée / abandon → pas de remboursement (comme avec un humain) : `ai-sweep.js` (toutes les 10 min, `netlify.toml`) ferme ces sessions IA expirées avec `closedBy: 'sweep'`, sans remboursement. En cas d'échec Stripe, l'admin reçoit un email. `chat-send.js` déclenche `ai-reply` à chaque message visiteur (API Claude, modèle `claude-opus-5`, effort `low`, réponses courtes, protocole de crise 3114/15/112). Quand un écoutant se connecte (`chat-presence.js` `online`), il reprend jusqu'à 3 sessions IA avec l'historique complet (`chat-poll.js` renvoie tout l'historique d'une session nouvellement attribuée). Prolongations acceptées automatiquement (`chat-extend.js`). Sessions IA enregistrées avec `agent_name = 'Claude (IA)'`, exclues des honoraires (comptabilité, stats admin). **Variable Netlify requise : `ANTHROPIC_API_KEY`** (sans elle, comportement d'origine : file d'attente). Optionnel : `AI_LISTENER_MODEL`.

## 🚧 En attente

1. **Fidélité Option B (futur)** — tracking par email (cross-device). Mis en attente.

## 🗑️ Supprimé

- **Chat de groupe** (sept. 2026) : page `groupe.html`, fonctions `netlify/functions/group-*.js`, tables `group_access` / `group_messages` retirées du code, des documents juridiques (HTML + PDF régénérés), du service worker et du sitemap. Tables supprimées côté Supabase (fait manuellement).

---

## 🔑 Accès admin espace.html

- URL : `/espace.html`
- Email : `lafmarvin@gmail.com`
- Mot de passe : valeur de `ADMIN_PASSWORD` (env var Netlify)
