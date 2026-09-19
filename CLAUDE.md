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

-- Abonnements push des écoutants. Cette table figurait dans supabase/schema.sql mais **pas**
-- dans ce mémo, alors que c'est ce bloc-ci qui est réellement exécuté.
-- ⚠️ Une version aux colonnes différentes existait en base : `CREATE TABLE IF NOT EXISTS` ne
-- corrigeait rien et l'enregistrement échouait sur PGRST204 (« colonne subscription introuvable »).
-- Voir plus bas le bloc de reconstruction si l'erreur réapparaît.
CREATE TABLE IF NOT EXISTS push_subscriptions (
  id           UUID        DEFAULT gen_random_uuid() PRIMARY KEY,
  agent_email  TEXT        NOT NULL,
  endpoint     TEXT        NOT NULL UNIQUE,
  subscription JSONB       NOT NULL,
  updated_at   TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_push_subs_email ON push_subscriptions (agent_email);
ALTER TABLE push_subscriptions ENABLE ROW LEVEL SECURITY;
DO $$ BEGIN CREATE POLICY "no_public_read" ON push_subscriptions FOR ALL TO anon USING (false); EXCEPTION WHEN duplicate_object THEN NULL; END $$;
```

### ✅ Exécuté — reconstruction de `push_subscriptions`

La table existait avec d'autres colonnes : `push-subscribe` échouait sur `PGRST204` (colonne
`subscription` introuvable), et comme la fonction ne regardait pas la réponse de Supabase, elle
répondait « succès ». Aucune notification push n'a donc jamais pu fonctionner. L'ancienne table est
**renommée** et non supprimée : rien n'est perdu, et les écoutants dont l'abonnement disparaît sont
réinscrits automatiquement sous 30 min (voir « Auto-réparation de l'abonnement »).

```sql
ALTER TABLE IF EXISTS push_subscriptions RENAME TO push_subscriptions_ancienne;

CREATE TABLE push_subscriptions (
  id           UUID        DEFAULT gen_random_uuid() PRIMARY KEY,
  agent_email  TEXT        NOT NULL,
  endpoint     TEXT        NOT NULL UNIQUE,
  subscription JSONB       NOT NULL,
  updated_at   TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_push_subs_email ON push_subscriptions (agent_email);
ALTER TABLE push_subscriptions ENABLE ROW LEVEL SECURITY;
DO $$ BEGIN CREATE POLICY "no_public_read" ON push_subscriptions FOR ALL TO anon USING (false); EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DROP TABLE push_subscriptions_ancienne;   -- fait, après vérification
```

✅ **Notifications push confirmées en fonctionnement** (sept. 2026) — pour la première fois : la
table n'avait jamais eu la bonne structure, et `push-subscribe` répondait « succès » sans regarder
la réponse de Supabase, ce qui masquait l'erreur depuis l'origine.

### ✅ Exécuté — pour que Max puisse assister les écoutants

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

### ✅ Exécuté — accusés de réception et indicateur « en train d'écrire »

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

## 💶 Offre commerciale (sept. 2026)

**Deux formules seulement**, mises en avant comme « offre spéciale » :

| | |
|---|---|
| Première conversation | **20 min offertes**, sans carte bancaire, une fois par personne |
| Pass mensuel illimité | **2 €/mois — tarif de lancement**, sessions illimitées de 30 min max, 24 h/24, résiliable à tout moment |

Le tarif est annoncé comme **offre de lancement** sur le site et dans les CGV : il pourra monter pour les **nouvelles** souscriptions, les abonnements en cours conservant leur prix. C'est ce qui rend une hausse future légitime plutôt que subie.

**Max garde le même profil pour tout le monde — décision du propriétaire (sept. 2026).** Une
distinction avait été mise en place (Sonnet sur les conversations offertes, Opus sur les sessions
payantes) puis **retirée à sa demande** : la qualité de l'accueil prime, y compris sur un essai
gratuit qui ne rapporte rien. **Ne pas la réintroduire** — ni dans un audit, ni au nom de
l'économie. La conséquence est assumée : chaque conversation offerte coûte le tarif Opus avec
réflexion étendue, et un abonné à 2 €/mois exploitant l'illimité coûte plus qu'il ne rapporte.
Si l'arbitrage devait changer, deux variables Netlify suffisent, sans toucher au code :
`AI_THINKING_TOKENS=0` (garde Opus, supprime la réflexion) ou `AI_LISTENER_MODEL=claude-sonnet-5`.

**Échec de souscription = alerte** : `create-subscription` prévient l'admin par email, en nommant `STRIPE_PRICE_ID` quand Stripe répond « No such price ». Un tarif mal configuré faisait perdre 100 % des abonnements en silence.

**Sessions à l'unité (1 € / 3 € / 5 €) désactivées.** Le verrou est **côté serveur**
(`create-payment-intent.js`, `FORFAITS_UNITAIRES`) : masquer les boutons ne suffirait pas, un appel
direct à l'API pourrait encore en acheter une. Remettre `FORFAITS_UNITAIRES=on` dans les variables
Netlify les réactive — tout le code (montants, durées `_plans.js`, remise fidélité) est intact.

**Prix de l'abonnement** : il vient du **Price Stripe** désigné par `STRIPE_PRICE_ID`, pas du code.
Changer le tarif impose de créer un nouveau Price dans Stripe et de mettre à jour cette variable.

**Résiliation** : arrête le renouvellement ; le mois entamé reste dû et n'est pas remboursé, l'accès
courant jusqu'à son terme. Le droit de rétractation de 14 jours (CGV art. 11) est **distinct** et
subsiste — ne pas le supprimer des CGV en le confondant avec la résiliation.

**Programme fidélité masqué** : les remises portaient sur les sessions à l'unité. Le bloc reste dans
`index.html` entre un `<div style="display:none">` pour un retour éventuel des forfaits.

**Friction du parcours gratuit** : la conversation offerte ne demande **que** le prénom et trois
cases (majorité, service non médical / pas de crise suicidaire, CGV). La case « je renonce à mon
droit de rétractation — la session démarre après paiement » est **masquée et non exigée** : sans
paiement il n'y a aucun droit de rétractation en jeu, et la faire cocher à quelqu'un à qui on vient
de promettre la gratuité sans carte était à la fois sans objet et inquiétant. Elle reste évidemment
obligatoire pour l'abonnement. **Ne pas la réimposer sur le parcours gratuit.**

## ✅ Fonctionnalités complètes

- Discount fidélité affiché dans l'app agent (badge 🎁 dans panneau flottant + file d'attente)
- Programme fidélité Option A : fenêtre glissante 3 mois (localStorage `parlons_session_dates`)

## 🤖 Automatisations

- **Audit sécurité quotidien** (Routine Claude, 7h Paris) : audite le code, applique les correctifs sûrs directement sur la branche de production `claude/fix-api-keys-mobile-J4B0A`, rapport dans `security-reports/` + notification.
- **Clôture comptable mensuelle** (`netlify/functions/monthly-accounting.js`, planifiée dans `netlify.toml` le 26 à 05:00 UTC) : pour le **mois précédent**, génère et envoie par email (Resend) la **facture** et le **relevé URSSAF** de chaque écoutant, plus le **récapitulatif + rapport de contrôle** à l'admin (barème contrat art. 7, solde Pass mensuel partagé à parts égales entre les écoutants ≥ 10 sessions/mois, rapprochement Stripe, remboursements, profils/SIRET/IBAN, seuil micro-entrepreneur). Lancement manuel : espace admin → bouton « 📧 Clôture mensuelle » → `netlify/functions/accounting-run.js` (fonction **non planifiée** qui vérifie les identifiants admin puis appelle `runClosing` : Netlify refuse tout appel HTTP externe vers une fonction planifiée, d'où cette séparation obligatoire). Variables optionnelles : `PASS_COMMISSION_PCT` (défaut 50), `PASS_MIN_SESSIONS` (défaut 10), `MICRO_THRESHOLD` (défaut 77700). Les écoutants sont auto-entrepreneurs : pas de bulletin de paie, la facture est préparée pour leur compte (mandat).

- **Max, assistant d'écoute IA** (`netlify/functions/ai-reply.js`, identité interne `claude@parlonsecoute.fr`) : quand aucun écoutant humain n'est en ligne, `chat-start.js` (payant / pass) et `free-session.js` (conversation offerte) attribuent la session à Max, qui engage la conversation (intérêt pour le besoin de parler, sans délai promis), envoie un push à tous les écoutants et un **email à l'admin + aux écoutants qui ont activé « Recevoir les demandes d'écoutant »** (`agent_profiles.notify_requests`, `notify_email` sinon `email`, Resend, envoi attendu avant de répondre). **Engagement remboursement — à la demande, plus automatique** : uniquement si le visiteur **va au bout de sa session** (`chat-close` avec `closedBy: 'timer'`, envoyé par index.html quand le minuteur atteint 0) sans qu'un humain ait pris le relais. `chat-close.js` n'appelle plus Stripe : il **propose** le remboursement en écrivant le message d'offre (`_refund.js`, `OFFRE_MESSAGE`) ; un bouton apparaît sous ce message dans index.html et `refund-request.js` rembourse en un clic, sans justification ni validation admin. L'éligibilité **ne peut pas être recalculée après coup** — seul `chat-close` sait distinguer une session menée à son terme d'une page simplement fermée —, c'est donc la **présence du message d'offre** qui en fait foi. Sa première phrase (`OFFRE_PREFIXE`) sert de repère aux deux bouts : elle est dupliquée dans index.html (`REMB_PREFIXE`), **les deux doivent rester identiques**, et le message du pass mensuel doit rester distinct sous peine d'afficher un bouton à tort. `refund-request` vérifie le `visitor_id`, la session close tenue par Max, et interroge Stripe avant tout versement (appel idempotent) ; en cas d'échec, l'admin est prévenu par email. Pass mensuel → message « non décomptée », sans démarche. Page fermée / abandon → pas de remboursement (comme avec un humain) : `ai-sweep.js` (toutes les 10 min, `netlify.toml`) ferme ces sessions IA expirées avec `closedBy: 'sweep'`, sans remboursement. En cas d'échec Stripe, l'admin reçoit un email. `chat-send.js` déclenche `ai-reply` à chaque message visiteur (réponses courtes, protocole de crise 3114/15/112 ; en cas d'échec, email à l'admin). **Deux profils d'exécution** : toute la logique vit dans `_ai-core.js`, `ai-reply.js` reste le point d'entrée unique de tous les appelants et passe la main à `ai-reply-background.js` — le suffixe `-background` lève la coupure à 10 s de Netlify (15 min), ce qui permet le profil **soigné** : `claude-opus-5` avec réflexion étendue (`AI_THINKING_TOKENS`, 1500 par défaut). Si ce relais échoue — fonctions background absentes du forfait, incident réseau —, `ai-reply` traite lui-même en profil **rapide** (`AI_LISTENER_FAST_MODEL`, `claude-sonnet-5` sans réflexion, tenu dans les 10 s) : Max répond toujours. Un double départ serait sans effet, les verrous n'en laissent écrire qu'un. Verrou porté à 90 s en profil soigné, la rédaction étant plus longue, et l'indicateur « … » est rafraîchi toutes les 6 s pendant la rédaction (sinon il s'éteindrait au bout de 8 s). Quand un écoutant se connecte (`chat-presence.js` `online`), il reprend jusqu'à 3 sessions IA avec l'historique complet (`chat-poll.js` renvoie tout l'historique d'une session nouvellement attribuée). Prolongations acceptées automatiquement (`chat-extend.js`). Sessions IA enregistrées avec `agent_name = 'Max (IA)'`, exclues des honoraires (comptabilité, stats admin). **Assistance des écoutants** : Max intervient aussi sur une session tenue par un **écoutant humain** qui n'a pas répondu depuis `ASSIST_DELAY_MS` (30 s par défaut) — tchat jamais ouvert après attribution, ou écoutant occupé sur un autre visiteur. La session **reste attribuée à l'écoutant** (aucun changement de `agent_email`, donc aucune réassignation) ; la règle de décision est unique (`_assist.js`) et **trois points d'appel** la déclenchent, car aucun ne suffit seul : le sondage du visiteur, le sondage de l'écoutant (`chat-poll.js`) et surtout **`assist-sweep.js`, planifiée toutes les minutes** (`netlify.toml`) — les onglets mis en veille par les navigateurs mobiles arrêtent de sonder précisément quand l'écoutant tarde à répondre, l'assistance ne peut donc pas dépendre d'une page restée au premier plan. Chacun appelle `ai-reply` avec `assist: true`, qui revérifie toutes les conditions avant d'écrire. **Trois rythmes**, décidés par `assistDecision()` — la même fonction sert aux déclencheurs et à la revérification d'`ai-reply`, les deux bouts raisonnent donc à l'identique : **10 s** (`ASSIST_FIRST_MS`) au *premier contact*, quand le tchat vient d'être attribué et que l'écoutant n'a pas encore dit un mot — le décompte part de l'attribution, ou du dernier message visiteur s'il est postérieur ; **20 s** (`ASSIST_DELAY_MS`) pour un silence en cours d'échange, l'écoutant ayant déjà parlé ; **1,5 s** (`ASSIST_RESUME_MS`) tant que Max porte le fil — il a parlé après le dernier message de l'écoutant, `maxCarriesThread()` —, `chat-send.js` le déclenchant alors dès l'envoi du message visiteur, comme en mode autonome. Dès que l'écoutant reprend la main, on revient aux 20 s. **Max se tait pendant que l'écoutant écrit** (`agent_typing_at` de moins de 8 s) : à 10 s, intervenir pendant qu'il rédige son accueil lui couperait la parole. **File d'attente couverte** (sept. 2026) : une session **non attribuée** n'avait aucun interlocuteur — Max n'est attribué qu'au démarrage et seulement si personne n'est en ligne, et `assist-sweep` ne regardait que les sessions *actives*. Une session jamais prise, ou **rendue à la file** par `chat-poll` parce que l'écoutant n'avait pas envoyé son premier message en deux minutes, laissait donc le visiteur écrire dans le vide indéfiniment. C'est ce qui s'est produit lors d'un appel à l'aide grave. `assist-sweep` reprend désormais ces sessions au bout d'`ASSIST_FIRST_MS` et les attribue à Max, par une mise à jour conditionnée à `status=eq.waiting` : si un écoutant l'a prise entre-temps, Max s'abstient. Verrou `chat_sessions.assist_lock` : les déclencheurs peuvent appeler simultanément, un seul écrit. Ses messages sont insérés avec `sender_type = 'assistant'` (valeur à autoriser dans `chat_messages_sender_type_check`, voir SQL ci-dessus) et Max **poursuit le fil naturellement** : aucune ligne système n'annonce son intervention (elle laisserait croire que l'écoutant s'est absenté) et il ne se présente pas. La transparence est portée par la bulle elle-même — fond bleuté signé « Max · assistant » côté visiteur, « Max a répondu pour vous » dans l'app écoutant. Pas de verrou `response_deadline` en assistance : ce champ pilote la réassignation d'une session humaine.

**Protocole de crise étendu (sept. 2026)** — après un appel à l'aide réel où une personne se
déclarant **mineure** signalait une amie de 13 ans en danger, a répondu « je peux appeler personne,
juste des messages » et est repartie sans solution. Le protocole ne donnait que des numéros de
téléphone. Trois situations sont désormais couvertes dans `_ai-core.js` (section SÉCURITÉ) :
**ne peut pas téléphoner** → tchat de Fil Santé Jeunes (9h-22h, jusqu'à 26 ans, professionnels) ou
SOS Amitié (13h-3h), sans jamais insister sur l'appel ; **moins de 18 ans** → orientation douce vers
Fil Santé Jeunes, Parlons étant réservé aux majeurs ; **s'inquiète pour un tiers** → accueillir la
peur, **ne demander aucune donnée sur l'autre personne** et n'en rien faire si elle est donnée
spontanément, orienter vers 15 / 3114 / 119. Les mêmes recours écrits figurent dans le bandeau
d'urgence et l'avertissement d'`index.html`. **Ne pas réduire ce protocole aux seuls numéros de
téléphone** : quelqu'un qui ne peut pas parler à voix haute n'est pas quelqu'un qui refuse l'aide.

**Ton de Max — moins de questions, de vrais conseils (sept. 2026).** En relisant une conversation
réelle, le propriétaire a constaté que Max **posait une question à presque chaque message**, au point que
le visiteur a fini par demander s'il parlait à une IA. Le prompt disait déjà « tu ne bombardes pas de
questions » : trop vague pour être appliqué. La consigne est donc devenue **comptable** — une question
maximum par message, jamais deux. La fréquence a ensuite été **relevée à un message sur deux**
(sept. 2026) après une conversation où Max n'en posait presque plus : trop peu, il donne l'impression
de recevoir sans s'intéresser ; trop, la personne se sent auditionnée. Les messages sans question se
terminent par une phrase affirmative. **Ses questions portent sur la personne, pas seulement sur son
problème** — qui est autour d'elle, ce qui lui fait encore du bien, ce qu'elle aimait avant : c'est la
curiosité pour quelqu'un qui réchauffe un échange, pas l'enquête sur le symptôme. Et : des **conseils concrets** assumés une fois la personne entendue (renvoyer indéfiniment
quelqu'un à ses propres ressources ressemble à une dérobade), un registre parlé (phrases courtes, pas de
vocabulaire de fiche technique).

**Longueur des réponses — une ou deux phrases, 250 caractères (sept. 2026).** Le prompt se contredisait : la section
FORME demandait « 2 à 5 phrases » pendant que la section sur la voix disait « une ligne suffit la
plupart du temps ». Le modèle arbitrait au milieu, d'où des réponses trop longues. Les deux sections
disent désormais la même chose : **une à trois phrases, une seule très souvent, quatre c'est déjà trop**
— sauf en situation de crise, où Max prend la place nécessaire pour les recours et la mise en sécurité.
S'ajoute un mode d'emploi pour couper (supprimer l'introduction qui annonce, la reformulation qui
n'apporte rien, la justification du conseil, la conclusion qui répète). **Vérifier la cohérence des
trois mentions de longueur** (sections « qui tu es », FORME, « ta façon d'écrire ») avant d'en modifier
une : c'est leur désaccord qui produit des réponses hors cible, pas le chiffre lui-même.

⚠️ **La consigne chiffrée n'a pas suffi.** Une conversation réelle postérieure au déploiement montrait
encore des paragraphes de six à huit phrases. La cause tenait à une autre consigne du prompt : « donne
de vrais conseils » produisait systématiquement *conseil + explication de pourquoi il marche*, ce qui
double la longueur. D'où trois renforts : un plafond **en caractères** (250, plus facile à tenir qu'un
compte de phrases), l'interdiction explicite d'expliquer un conseil, et surtout un **exemple travaillé**
dans le prompt — le message trop long réellement produit, suivi de sa version correcte. C'est le levier
qui manquait : une règle abstraite se contourne, un exemple concret beaucoup moins.

⚠️ **Le risque opposé, à ne pas créer en resserrant.** Un plafond strict peut pousser Max à garder
l'empathie et à lâcher le fond — court, poli, inutile. Le prompt fixe donc une **règle de priorité** :
raccourcir consiste à enlever l'emballage (l'annonce, la reformulation, l'explication du conseil, la
conclusion qui répète), jamais le contenu. Et explicitement : **si la personne demande quoi faire, Max
répond quoi faire** ; entre couper le conseil et couper la phrase d'empathie qui le précède, c'est
l'empathie qui saute — elle transparaît de toute façon dans la façon d'écrire. L'exemple « correct »
du prompt fait 146 caractères **et** contient un conseil concret : il démontre les deux exigences
ensemble, ce qu'aucune règle chiffrée ne fait.

## 💬 Proposition du pass en fin de conversation offerte

À la fin d'une **conversation offerte** seulement, Max peut mentionner **une fois, en une phrase** que
le pass à 2 €/mois permet de continuer. Le moment est décidé **côté serveur** (`_ai-core.js`, contexte
dynamique : libellé contenant `GRATUIT` **et** `remainingMin` entre 1 et 4) et non par Max, qui jugerait
mal à partir du seul « temps restant ». Un abonné n'est jamais sollicité.

⚠️ **Jamais quand la personne va mal** — crise, idées suicidaires, violences, détresse aiguë, ou
simplement une émotion forte en cours. Quelqu'un qui souffre n'est pas un client à convertir : une
proposition payante à ce moment-là abîmerait la personne et le service. Jamais de message entier
consacré à ça, jamais en ouverture, aucune insistance après un refus ou un silence, pas de vocabulaire
commercial (urgence, « offre spéciale »). L'écoute n'est jamais conditionnée à un paiement, et Max ne
laisse jamais entendre que la suite serait meilleure en payant. **Ne pas assouplir ces garde-fous pour
améliorer la conversion.**

**Pas d'écho en tête de message, et des longueurs variées (sept. 2026).** Deux tics relevés sur une
conversation réelle. **L'écho** : la personne écrit « depuis toujours », Max répond « Depuis toujours,
c'est lourd à porter ». Elle sait ce qu'elle a écrit — la reprise ne prouve pas l'écoute, elle remplit.
Le prompt l'interdit maintenant nommément, avec les deux exemples tirés de cette conversation et leur
version corrigée. Noter que « Je comprends » figurait **déjà** dans les formules interdites et
apparaissait quand même : une interdiction en liste tient mal, un exemple contrastif tient mieux.
**L'uniformité** : des messages tous calibrés pareil, même courts, sonnent mécaniques. Max doit
alterner — après deux réponses de deux ou trois lignes, une de quelques mots (« Ah. », « C'est dur,
ça. », « Depuis combien de temps ? »), qui sont de vraies réponses et non des remplissages.

**⚠️ Le prompt enseigne par l'exemple autant que par la règle (sept. 2026).** Max plaçait des tirets
longs à la place d'un point (« ça se passe comment — tu le fais dans son dos ? »), alors que le prompt
disait déjà « pas de tirets cadratins ». Cause trouvée en comptant : **le prompt lui-même en contenait
32**. Le modèle imitait le style de ses instructions plutôt que d'obéir à l'une d'elles. La prose du
prompt en est désormais expurgée ; il n'en reste que **trois, dans les exemples « À éviter »**, où ils
montrent la faute. Même le séparateur des exemples contrastifs est passé de « — Mieux : » à
« → Mieux : », pour la même raison.

**Règle générale à retenir** : avant d'ajouter une interdiction de forme au prompt, vérifier que le
prompt ne la viole pas lui-même. Et préférer partout l'**exemple contrastif** (« À éviter : … → Mieux :
… ») à la liste d'interdits : trois tics successifs — « Je comprends », l'écho en tête de message, le
tiret long — figuraient en liste et n'ont cédé qu'une fois montrés par l'exemple.

**Voix de Max calquée sur celle du fondateur (sept. 2026).** Section « TA FAÇON D'ÉCRIRE » du
prompt, tirée d'un échange WhatsApp réel fourni comme échantillon : messages **très courts** (souvent
une ligne), prénom employé naturellement mais pas à chaque message, mots du quotidien (« dur »,
« lourd », jamais « éprouvant sur le plan émotionnel »), enthousiasme franc (« Super », « Ah oui,
d'accord »), ponctuation vivante. Le trait le plus important : **attraper le détail concret de la vie
de la personne et le lui renvoyer** (« bon courage pour les nuits ») — c'est ce qui prouve qu'on a
écouté, bien plus qu'une reformulation parfaite.

Émojis : **un seul par message au maximum**, jamais deux dans le même, et **jamais quand la personne
va mal** (pleurs, mort, violence — là c'est une gifle). En revanche **plusieurs messages peuvent en
porter un au fil d'une même conversation**, chaque fois que le moment s'y prête. La formulation
précédente — « un seul de temps en temps » — se lisait comme une ration pour tout l'échange et n'en
produisait qu'un ; le propriétaire a précisé (sept. 2026) qu'il en voulait **plusieurs, aux moments
appropriés**, sans pour autant un par message. Ni quota, ni rationnement : c'est le moment qui décide.
⚠️ **Ne pas les supprimer au nom du sérieux d'un service d'écoute** — ils ont été explicitement
validés comme rendant la conversation plus vivante. L'échantillon
en contenait plusieurs, mais c'était une conversation d'organisation entre proches ; un 😀 adressé à
quelqu'un qui parle de mourir serait une gifle. Cette restriction est une adaptation délibérée de
l'échantillon, pas un oubli.

⚠️ L'échantillon fourni était un échange **administratif**, pas une écoute : il donne le registre
(chaleur, brièveté, attention au concret), pas la manière d'accompagner une détresse. À affiner avec
de vrais messages d'écoute quand il y en aura. **Aucune donnée personnelle de la capture** (noms de
tiers, date de naissance d'un enfant, adresse e-mail) n'a été reprise dans le prompt ni dans le dépôt.

⚠️ **Les anecdotes de Max sont impersonnelles, et doivent le rester.** Le propriétaire a demandé « des
anecdotes ». Max illustre donc par ce que vivent **d'autres gens** (« beaucoup de personnes décrivent
exactement ça ») — ce qui est vrai et normalise sans minimiser. Il **n'invente aucun souvenir personnel**
(« moi aussi j'ai vécu ça », une famille, un passé) : ce serait fabriquer une confidence auprès de
quelqu'un de vulnérable, alors même que le motif de la demande était un visiteur qui soupçonnait déjà une
IA. Découvrir ensuite que la confidence était inventée serait bien pire que le soupçon de départ — sans
compter l'obligation de ne jamais nier sa nature (règlement IA). **Ne pas transformer cette consigne en
anecdotes à la première personne.**

**Transparence** : la nature de Max (programme) est indiquée une seule fois, dans le message système d'ouverture (et dans le modal / FAQ / CGV) ; ailleurs il est simplement « Max · assistant d'écoute » et ne le rappelle jamais de lui-même, mais ne le nie jamais si on lui demande — ne pas supprimer cette mention (obligation légale, règlement IA / pratiques commerciales). **Variable Netlify requise : `ANTHROPIC_API_KEY`** (sans elle, comportement d'origine : file d'attente). Optionnels : `AI_LISTENER_MODEL` (défaut `claude-opus-5`), `AI_LISTENER_FAST_MODEL` (repli), `AI_THINKING_TOKENS` (0 pour désactiver la réflexion), `AI_EFFORT` (`low`/`medium`/`high`…, défaut `medium`), `AI_SUGGEST_MODEL`, `ASSIST_FIRST_MS` / `ASSIST_DELAY_MS` / `ASSIST_RESUME_MS`.

**⚠️ Forme de la réflexion étendue (sept. 2026 — cause d'un silence total de Max).** `claude-opus-5` **refuse** `thinking: { type: 'enabled', budget_tokens }` : l'API répond 400 « use thinking.type.adaptive and output_config ». La profondeur se règle par `output_config.effort`, plus par un budget de jetons. L'erreur était invisible : elle survenait dans `ai-reply-background`, qui avait **déjà répondu 202**, si bien qu'`ai-reply` croyait l'appel réussi et ne repliait jamais sur le profil rapide. Max s'est tu sur **tous** ses appels, assistance comprise, alors que la règle `_assist.js` se déclenchait correctement (`go: true` dans un traçage temporaire, depuis retiré) — chercher le défaut du côté des délais aurait été sans fin. **Leçon à garder** : les journaux Netlify n'étant pas consultables depuis la session de développement, un traçage jetable écrit en base (`suggestions`, `payment_id = 'TRACE'`) a été le seul moyen de trancher entre quatre hypothèses. À refaire si un défaut redevient invisible — et à retirer aussitôt la cause trouvée, y compris le point de lecture HTTP. ⚠️ **Le coût caché d'un tel dispositif** : le filtre qui excluait les lignes de diagnostic du tableau de bord admin (`payment_id=not.eq.TRACE`) masquait aussi, sans qu'on le veuille, **toutes les suggestions dont `payment_id` est `NULL`** — c'est-à-dire celles envoyées sans paiement. En PostgREST comme en SQL, `NOT (colonne = 'x')` vaut `NULL`, donc faux, quand la colonne est nulle. Un filtre d'exclusion sur une colonne nullable doit s'écrire `or=(payment_id.is.null,payment_id.neq.X)`. Retenir surtout que l'instrumentation temporaire a des effets de bord sur le code de production qu'elle traverse. Deux garde-fous désormais : le champ `thinking` n'est **envoyé que si la réflexion est demandée** (son absence est acceptée par tous les modèles, sa forme non), et `_ai-core.js` **rejoue lui-même en profil rapide** si le profil soigné échoue — un modèle muet vaut moins qu'un modèle plus simple qui parle. Ne pas remettre ce repli à la charge d'`ai-reply` : il ne peut pas voir l'échec d'une fonction background.

**Rythme de réponse de Max — lecture puis écriture, 12 à 26 s (sept. 2026).** Une réponse instantanée
trahit la machine. L'attente est découpée comme chez un humain, et les deux temps sont **successifs** :

| | Dépend de | Durée |
|---|---|---|
| **Lecture** (rien ne s'affiche) | longueur du message du **visiteur** | 3 s à 6 s |
| **Écriture** (« … » allumé) | longueur de la réponse de **Max** | 9 s à 20 s |

⚠️ **Source unique : `netlify/functions/_rythme.js`.** Deux fichiers en dépendent — `_ai-core.js` pour
savoir quand allumer l'indicateur de frappe, `chat-poll.js` pour savoir quand livrer le message.
Dupliquer le calcul les ferait diverger et l'indicateur s'allumerait à contretemps. Même raison d'être
que `_assist.js`. **Ne pas réintroduire de copie locale.**

Mesuré : « oui » → « Ah. » = 12-16 s ; message moyen → deux lignes = 17-21 s ; message long → trois
lignes = 22-26 s. Le total a augmenté en rendant les deux temps successifs (la lecture était
auparavant comprise dans l'attente, pas ajoutée) : c'est un choix explicite du propriétaire.

Les délais sont dérivés du **contenu et de l'identifiant** des messages, jamais tirés au sort à
l'appel : deux sondages successifs doivent trouver la même échéance, sinon la réponse apparaîtrait
puis disparaîtrait. C'est un **minimum** d'affichage, pas un maximum — si Max met plus longtemps à
rédiger, sa réponse arrive quand elle est prête. Pendant la retenue, l'indicateur reste allumé
(`otherTyping: … || retenu`). `SEUIL_TROIS_LIGNES` (95 caractères) est calibré sur des bulles réelles
— 82 caractères tenaient en deux lignes sur mobile, 113 en trois — et reste une approximation, la
largeur d'une ligne dépendant de l'appareil.

**Le rythme s'applique aussi en assistance, mais pas au premier relais.** Il en était exclu au motif
que le visiteur avait déjà patienté `ASSIST_DELAY_MS` — vrai de la *première* intervention seulement.
Ensuite, tant que Max porte le fil, il répond au bout d'`ASSIST_RESUME_MS` (1,5 s) et la réponse
tombait **instantanément**. La condition est donc : Max portait-il déjà le fil **au moment où le
visiteur a écrit** (`maxCarriesThread` sur les messages antérieurs à celui-ci) ? Si oui, délai ; si
non — première prise de parole, ou premier relais après une réponse humaine —, affichage immédiat,
sans quoi l'attente cumulée atteindrait 15 à 30 s. ⚠️ La lecture du fil est précédée d'une garde
**sans requête** (`peutVenirDeMax`) : sans elle, on ajouterait une requête Supabase à *chaque*
sondage, toutes les 2,5 s et pour chaque visiteur.

**Ouverture d'une session récupérée en file d'attente.** Quand `assist-sweep` attribue à Max une session que personne n'a prise, le visiteur n'a souvent **rien écrit**. Le mode « Max tient la session » exigeait un message visiteur en attente et s'arrêtait net : la session restait muette. Max ouvre donc la conversation lui-même quand le fil ne contient aucun message (hors système), comme le font déjà `chat-start` / `free-session`.

- **Article SEO hebdomadaire** (Routine Claude, mardi 6h Paris) : choisit une requête réelle non couverte (voir `blog/_topics.md`), rédige un article de 900-1 300 mots et le publie via `node tools/new-article.mjs article.json` → page statique `blog/<slug>.html` (template `blog/_template.html`, JSON-LD Article, canonical, OG), carte en tête de `blog.html`, URL dans `sitemap.xml`, ligne dans `blog/_topics.md` ; commit `blog:` + push + notification.
- **Plan Google Ads mensuel** (Routine Claude, le 1er à 8h Paris) : rédige `marketing/google-ads/AAAA-MM.md` (conformité, structure de campagne, mots-clés, annonces responsives avec longueurs vérifiées, négatifs, budget, suivi des conversions d'après `index.html`, plan du mois) et envoie un résumé par notification. Ne modifie pas le site.

## 💡 Co-pilote de l'écoutant

`netlify/functions/ai-suggest.js` + bouton 💡 dans la barre de saisie d'`agent-app.html`. À la
demande, propose **trois** façons de poursuivre (un reflet / validation, une question ouverte, une
reformulation ou un résumé), dans le tutoiement ou le vouvoiement déjà employé. Mêmes interdits que
Max : aucun diagnostic, aucun nom de trouble, aucun médicament, jamais de titre de professionnel de
santé ; en situation de crise, les trois propositions servent uniquement la mise en sécurité et les
numéros (3114 / 15 / 112 / 3919 / 119).

**La proposition retenue est insérée dans la zone de saisie, jamais envoyée.** Ce n'est pas un détail
d'ergonomie : le message part au nom de l'écoutant, il faut donc qu'un humain l'ait relu, adapté et
envoyé — comme une suggestion de réponse d'une messagerie. Un envoi en un geste ferait passer un
texte automatique pour une parole humaine, alors que Max, lui, assume la sienne par sa bulle signée.
Ne pas transformer ce bouton en envoi direct.

Déclenché **à la demande** et non à chaque message : coût maîtrisé, et l'écoutant garde la main.
Contrôle d'accès : jeton valide **et** session attribuée à cet écoutant (sinon il lirait le fil d'un
collègue). La fonction ne fait que lire, elle n'écrit rien en base. Modèle surchargeable via
`AI_SUGGEST_MODEL`. Sans `ANTHROPIC_API_KEY`, le bouton signale simplement l'indisponibilité.

## 🔔 Notifications push

`push-subscribe.js` enregistre l'abonnement (`push_subscriptions`), `push-notify.js` envoie (VAPID),
le service worker `sw.js` affiche. L'app écoutant a un bouton de test et un bouton de renouvellement
d'abonnement dans ses réglages.

`push-notify.js` n'accepte que deux appelants : les **fonctions internes**, qui prouvent leur origine
par `internalSecret` (`INTERNAL_FN_SECRET`, sinon `SUPABASE_SERVICE_KEY`), et un **écoutant connecté**
qui teste ses propres notifications depuis l'app — le navigateur ne peut pas détenir le secret, il
s'authentifie donc par son jeton de présence, et l'envoi est alors verrouillé sur lui-même avec un
titre, un message et un lien fixes. Il ne peut ni notifier un collègue, ni choisir le texte ou l'URL.

**Tous les envois sont `await`és, avec un plafond de 2,5 s** (`chat-send`, `chat-start`,
`free-session`, `chat-presence`). C'est la même leçon que pour les emails : une fonction Netlify peut
être gelée dès qu'elle a répondu, et une requête lancée sans être attendue n'a alors jamais le temps
de partir. Le symptôme était exactement celui-là — pas de notification quand l'application est en
arrière-plan, de façon intermittente. **Ne pas repasser ces appels en « sans attendre ».**

Dans `chat-send`, les envois sortants sont regroupés dans `envois[]` puis attendus **ensemble**
(`Promise.allSettled`) : les attendre l'un après l'autre ajouterait leurs délais à chaque message.

Le push est le **seul** moyen d'atteindre l'écoutant quand son application est en arrière-plan : le
navigateur y suspend les minuteurs, donc le sondage ne tourne plus.

**Auto-réparation de l'abonnement.** `push-notify` supprime une ligne dès qu'un envoi est rejeté par
le service de push, et l'application ne la recréait qu'à de rares moments : un seul échec suffisait à
couper les notifications définitivement et en silence — l'écoutant se croyait joignable, le
navigateur détenant toujours son abonnement local. L'app appelle donc `push-subscribe`
(`action: 'status'`) à la mise en ligne, au retour au premier plan et toutes les 30 min ; si la ligne
manque, elle refait un abonnement **complet** (désinscription puis réinscription — renvoyer l'ancien
ne servirait à rien s'il a été supprimé pour endpoint mort). Silencieux, rien à faire côté écoutant.

Suppression sur **404 / 410 uniquement** : un 403 signale le plus souvent des clés VAPID qui ne
correspondent pas — une erreur de configuration, pas un appareil disparu. Supprimer sur 403
effacerait d'un coup les abonnements de toute l'équipe.

## 🔔 Alerte visiteur à l'arrivée d'un message

Diagnostic (sept. 2026) : des visiteurs ouvraient une conversation puis ne répondaient jamais. Le
parcours est sain — vérifié dans un navigateur mobile réel, la zone de saisie apparaît bien quand
l'écoutant prend le tchat. La cause était ailleurs : **rien n'avertissait le visiteur**. Sa page
n'était abonnée à aucune notification (`index.html` n'appelle jamais `pushManager`), et il n'y avait
ni son, ni vibration, ni changement de titre. La réponse de l'écoutant arrivait dans un onglet
endormi. Vu du visiteur : il a attendu, personne n'est venu.

`pcpAlerteMessage()` dans `index.html` : son doux (deux notes sinusoïdales, gain 0,05 — la personne
peut être couchée près de quelqu'un la nuit), `navigator.vibrate([35,60,35])`, et titre de l'onglet
pendant 10 s. Déclenché **uniquement** quand le panneau est replié ou l'onglet en arrière-plan : on
ne sonne pas dans l'oreille de quelqu'un qui regarde déjà l'écran.

**Émis par la page, donc aucune autorisation à demander** — choix délibéré : solliciter une
permission de notification auprès de quelqu'un qui cherche de l'aide n'est pas anodin. Le contexte
audio est armé au premier geste de l'utilisateur (`pointerdown` / `keydown`), les navigateurs
refusant le son sans interaction préalable.

**Réglages du visiteur** : deux bascules dans l'en-tête du panneau (`pcp-son-btn`, `pcp-vib-btn`),
mémorisées dans `localStorage` (`parlons_alerte_son`, `parlons_alerte_vib`), **activées par défaut** —
quelqu'un qui attend une réponse a besoin d'être prévenu, celui que ça dérange coupe en un geste.
Toucher la bascule rejoue immédiatement le son ou la vibration, pour savoir à quoi s'attendre. Le
bouton vibration est masqué quand `typeof navigator.vibrate !== 'function'` (iOS) : une commande qui
ne ferait rien vaut moins que pas de commande.

**Limite assumée** : si le navigateur a gelé l'onglet — application quittée, téléphone verrouillé —
plus rien ne s'exécute dans la page, sondage compris, et aucune alerte ne part. Seule une
notification push y remédierait, au prix d'une demande d'autorisation. Écarté pour l'instant.

## ⏳ Affichage immédiat du message envoyé (visiteur)

`ajouterBulleProvisoire()` dans `index.html` : le message du visiteur s'affiche dès le clic, avec une
coche **⏳**, puis la bulle est remplacée quand le serveur le renvoie au sondage suivant
(correspondance sur `dataset.contenu`). Sans cela, il écrivait, appuyait, et ne voyait rien pendant
près d'une seconde — inquiétant sur une connexion lente. Si l'envoi échoue, la bulle est retirée et
le texte rendu au champ : jamais laisser croire qu'un message est parti.

⚠️ La bulle provisoire **n'entre pas** dans `chatLastMsgTime` ni `chatRenderedIds`. Ce repère pilote
le `since` du sondage : l'avancer avec un horodatage local ferait manquer des messages du serveur.

## ✓✓ Accusés de réception et indicateur de saisie

Style SMS / WhatsApp, dans les deux sens (`index.html` visiteur, `agent-app.html` écoutant) :

| Signe | Signification | Posé par |
|---|---|---|
| ✓ | envoyé (enregistré en base) | le message existe |
| ✓✓ | reçu par l'autre | `chat-poll` en livrant les messages (`*_fetched_at`) |
| ✓✓ bleu | lu | `chat-poll` (`seen` / `viewingSessionId`) quand la conversation est ouverte **et** l'écran au premier plan, et `chat-send` — répondre vaut lecture (`*_seen_at`) |

**Aucun état par message** : six horodatages sur `chat_sessions` suffisent, on compare l'heure du
message aux repères. Écriture seulement quand quelque chose arrive, pas à chaque sondage.

Le « lu » voyage dans le **sondage** (qui passe déjà toutes les 2,5-3 s) plutôt que dans une requête
séparée : si un signal se perd — onglet rechargé, application relancée —, l'état se rétablit au
sondage suivant. Et `chat-send` le pose côté serveur à chaque envoi, car répondre prouve la lecture
sans dépendre d'aucun signal du navigateur.

**« … en train d'écrire »** : `netlify/functions/chat-signal.js`, appelé dès la première frappe
(limité à un envoi toutes les 3 s) — le sondage seul serait trop lent, l'indicateur arriverait après
le message. Il s'éteint tout seul après 8 s sans nouvelle frappe : aucun signal d'arrêt à envoyer,
rien ne peut rester bloqué. La lecture se fait dans la réponse de `chat-poll`, sans requête en plus.
Côté écoutant, `chat-signal` vérifie le jeton **et** que la session lui est attribuée : sans ce
contrôle, n'importe qui pourrait faire croire au visiteur qu'on lui répond.

**Max** affiche aussi « … » pendant qu'il rédige (`_ai-core.js`), y compris pendant que `chat-poll`
retient volontairement sa réponse le temps « de lire et d'écrire » : c'est bien le moment où il écrit.

**Séquence vue par le visiteur — reçu, lu, temps mort, puis « … » (sept. 2026).** L'ordre était
exactement inverse : `marquerEcrit()` allumait « … » dès la première milliseconde, et `agent_seen_at`
(le « lu ») n'était posé qu'à l'insertion, dix secondes plus tard. Désormais `_ai-core` pose
`agent_fetched_at` **et** `agent_seen_at` dès qu'il prend la main, puis attend **3 à 5 s**
(`DELAI_AVANT_ECRITURE`, tiré au sort) avant d'allumer l'indicateur de frappe. C'est l'ordre humain :
on lit, on réfléchit, et seulement ensuite on tape. ⚠️ Ce délai **ne retarde pas la réponse** — la
rédaction a déjà commencé, seul l'affichage de l'indicateur est différé. Le `setTimeout` est annulé
dans le `finally` au même titre que l'intervalle d'entretien.

## 🕐 Archives de conversation (espace admin)

Onglet « 📁 Archives » d'`espace.html` (`renderArchives`, données par `admin-chat-archive.js`,
30 derniers jours). Chaque message porte son **heure d'envoi** à côté de la bulle — à l'extérieur et
non dedans : à l'intérieur il faudrait deux couleurs de texte selon l'expéditeur (fond clair côté
visiteur, terracotta côté écoutant). Heure au format `HH:MM:SS` — les **secondes** comptent ici : les seuils d'intervention de Max se
mesurent en secondes (10 s au premier contact, 20 s en cours d'échange), et c'est dans les archives
qu'on relit après coup si le rythme a été tenu. **Date complète au survol** (`title`) :
une conversation peut passer minuit alors que l'en-tête de la carte ne porte que la date de clôture.

⚠️ Les messages de Max en assistance (`sender_type = 'assistant'`) n'avaient **aucun style** ici :
la feuille ne connaissait que `visitor`, `agent` et `system`, si bien qu'ils s'affichaient sans bulle.
Corrigé avec les mêmes teintes qu'`index.html` et `agent-app.html` (fond `#eef2fb`). **Penser à cette
feuille si un nouveau `sender_type` apparaît.**

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
