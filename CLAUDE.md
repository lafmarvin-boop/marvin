# Parlons — Mémo de session

Projet : service d'écoute anonyme en ligne (Netlify + Supabase + Stripe + Crisp + Resend).

---

## ✅ Ce qui est fait

- `index.html` : paiement unique (Stripe), abonnement mensuel, groupe, notation, suggestions, fidélité Option A (localStorage)
- `espace.html` : login unifié admin/abonné, dashboard admin (stats, tableau agents, suggestions, abonnés, sessions récentes), dashboard abonné (démarrer session, changer mdp, résilier)
- `netlify/functions/` : create-payment-intent, create-subscription, stripe-webhook, cancel-subscription, change-password, subscriber-session, crisp-webhook, submit-suggestion, admin-stats

---

## 🔧 Configuration à faire (côté Netlify env vars)

Ces variables d'environnement ne sont pas encore définies en production :

| Variable | Valeur |
|---|---|
| `ADMIN_PASSWORD` | Le mot de passe admin choisi |
| `ADMIN_EMAIL` | `lafmarvin@gmail.com` |
| `STRIPE_PRICE_ID` | ID du prix récurrent 15€/mois à créer dans Stripe |
| `CRISP_API_IDENTIFIER` | Crisp → Settings → Integrations → API |
| `CRISP_API_KEY` | Crisp → Settings → Integrations → API |
| `CRISP_HOOK_TOKEN` | Token secret à choisir, à mettre aussi dans l'URL du webhook Crisp |
| `RESEND_API_KEY` | Compte Resend (resend.com) |
| `FROM_EMAIL` | Ex : `bonjour@parlons.fr` (domaine vérifié dans Resend) |
| `SITE_URL` | `https://parlons.fr` (ou URL Netlify actuelle) |

---

## 🗄️ SQL à exécuter dans Supabase (nouvelle requête vide)

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

-- Colonne pour les push notifications visiteurs (app-visiteur.html)
ALTER TABLE agent_requests ADD COLUMN IF NOT EXISTS push_subscription TEXT;

-- agent_profiles existe déjà (pseudo, nom, prenom, adresse, siret, iban…)
-- Ajouter les colonnes de notification :
ALTER TABLE agent_profiles ADD COLUMN IF NOT EXISTS notify_email TEXT;
ALTER TABLE agent_profiles ADD COLUMN IF NOT EXISTS notify_requests BOOLEAN DEFAULT FALSE;

-- Réassignation automatique si l'agent ne répond pas dans les 2 min :
ALTER TABLE chat_sessions ADD COLUMN IF NOT EXISTS response_deadline TIMESTAMPTZ;
```

---

## 📋 Crisp — configuration webhook

1. Crisp Dashboard → Settings → Integrations → Webhooks
2. URL : `https://TON-SITE.netlify.app/.netlify/functions/crisp-webhook?token=TON_CRISP_HOOK_TOKEN`
3. Événements à cocher : `message:send` + `conversation:resolved`

---

## 🌐 Domaine

- Acheter `parlons.fr` (ou alternative) sur OVH ou Gandi (~7-15€/an)
- Netlify → Site settings → Domain management → Add custom domain
- Remplacer les nameservers chez le registrar par ceux de Netlify
- SSL auto via Let's Encrypt (rien à faire)
- Déclarer sur Google Search Console après connexion du domaine

---

## ⚠️ À faire depuis l'ordinateur (priorité)

### 1. Configurer Resend (emails transactionnels)
Toutes les notifications email (demande d'écoutant, email de bienvenue abonné) sont codées mais silencieuses sans ces variables.

**Étapes :**
1. Créer un compte sur [resend.com](https://resend.com)
2. Vérifier le domaine `parlons.fr` dans Resend (DNS → ajouter les enregistrements TXT/DKIM qu'ils fournissent)
3. Créer une API Key dans Resend
4. Dans Netlify → Site settings → Environment variables, ajouter :
   - `RESEND_API_KEY` = la clé Resend
   - `FROM_EMAIL` = `Parlons <noreply@parlons.fr>`
   - `SITE_URL` = `https://parlons.fr` (ou l'URL Netlify en attendant le domaine)
5. Redéployer le site (Netlify → Deploys → Trigger deploy)

**Ce que ça débloque :**
- ✉️ Email admin sur `contact.parlons.ecoute@gmail.com` quand un visiteur clique "Demander un agent"
- ✉️ Email automatique au visiteur dès qu'un agent se connecte
- ✉️ Email de bienvenue lors d'un nouvel abonnement

### 2. Tester le bouton "Demander un agent"
Une fois Resend configuré :
1. Se déconnecter de l'app agent (aucun agent en ligne)
2. Sur `index.html`, vérifier que le bouton "🔔 Demander un agent" apparaît en haut à droite
3. Soumettre un email → vérifier réception sur `contact.parlons.ecoute@gmail.com`
4. Se reconnecter en tant qu'agent → vérifier que l'email de disponibilité part vers le demandeur

---

## 🚧 Code à compléter (prochaines sessions)

1. **Email de bienvenue (Resend)** — la fonction `create-subscription.js` envoie déjà l'email, mais nécessite `RESEND_API_KEY` + `FROM_EMAIL` + `SITE_URL` configurés. À tester une fois les variables en place.

2. **Système de fidélité Option A — mobile** — vérifier que le badge et la réduction s'affichent correctement sur mobile (résolutions < 390px).

3. **Espace admin — mobile** — le tableau agents a beaucoup de colonnes, prévoir un affichage adapté sur petits écrans si besoin.

4. **Fidélité Option B (futur)** — si on veut passer au tracking par email (cross-device), à concevoir. Mis en attente à la demande de l'utilisateur.

5. **Afficher le discount fidélité dans Crisp** — quand un agent prend un chat, lui montrer si le client est en tarif réduit fidélité (nécessite de passer l'info dans session:data Crisp).

---

## 🔑 Accès admin espace.html

- URL : `/espace.html`
- Email : `lafmarvin@gmail.com`
- Mot de passe : valeur de `ADMIN_PASSWORD` (env var Netlify)
