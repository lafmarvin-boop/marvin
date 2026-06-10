# Parlons — Mémo de session

Projet : service d'écoute anonyme en ligne (Netlify + Supabase + Stripe + Resend).

---

## ✅ Ce qui est fait

- `index.html` : paiement unique (Stripe), abonnement mensuel, groupe, notation, suggestions, fidélité Option A (localStorage), responsive mobile
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

## 🚧 En attente

1. **Fidélité Option B (futur)** — tracking par email (cross-device). Mis en attente.

---

## 🔑 Accès admin espace.html

- URL : `/espace.html`
- Email : `lafmarvin@gmail.com`
- Mot de passe : valeur de `ADMIN_PASSWORD` (env var Netlify)
