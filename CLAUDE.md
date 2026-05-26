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
| `ADMIN_PASSWORD` | Le mot de passe admin choisi (défaut actuel : `Parlons2026!`) |
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

CREATE TABLE IF NOT EXISTS suggestions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  content TEXT NOT NULL,
  payment_id TEXT,
  created_at TIMESTAMPTZ DEFAULT NOW()
);
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
- Mot de passe : valeur de `ADMIN_PASSWORD` (défaut : `Parlons2026!`)
