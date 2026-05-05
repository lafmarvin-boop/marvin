# Marvin – Assistant vocal Android

Assistant vocal mains-libres pour Samsung S24 Ultra (Android 14). Tu dis
**« jarvis »**, Marvin t'écoute, parse ta commande et soit la dispatch vers
l'app concernée (SMS, Spotify, Waze…), soit la transmet à un cerveau IA
(Claude API ou Gemma local) pour les questions de culture générale ou les
discussions multi-tours.

## Apps gérées

| App | Statut | Comment |
|---|---|---|
| SMS | ✅ Auto-envoi | `SmsManager` + résolution contact |
| Appels | ✅ Auto-lancement | `ACTION_CALL` |
| WhatsApp | ⚠️ Pré-rempli | `wa.me` deep-link – tu valides l'envoi |
| Spotify | ✅ Play/Pause/Next/Prev/Search | media buttons + deep-link `spotify:search:` |
| Waze | ✅ Navigation | `waze.com/ul?q=...&navigate=yes` |
| Samsung Météo | ✅ Ouvre l'app | « jarvis ouvre la météo » |
| FamilyWall | 🟡 Ouvre l'app | pas d'API publique, automatisation à venir |
| Ecovacs Home | 🟡 Ouvre l'app | pas d'API publique, automatisation à venir |
| Boursobank / Banque Pop | 🟡 Ouvre l'app (lecture seule) | **jamais d'action transactionnelle** |

## Outils IA (mode Cloud uniquement)

Quand le parser local ne reconnaît pas la commande, ou en mode discussion,
Claude reçoit la question avec 4 outils gratuits qu'il peut appeler tout seul :

| Outil | Source | Coût |
|---|---|---|
| `get_weather` | [Open-Meteo](https://open-meteo.com/) | Gratuit, pas de clé |
| `get_time` | Horloge du téléphone | Gratuit |
| `get_location` | GPS (FusedLocationProvider) | Gratuit |
| `get_calendar_events` | Calendrier Android | Gratuit |

## Architecture

```
[Microphone] ──► WakeWordEngine (Vosk FR, mots-clés "jarvis"/"djarvis"…)
                       │ détection
                       ▼
              SpeechToText (Vosk FR, transcription)
                       │ texte
                       ▼
              IntentParser (regex FR)
                       │
                ┌──────┴──────┐
                ▼             ▼
         Action locale   StartDiscussion
         (SMS, Spotify,  ou Unknown
          Waze…)              │
                              ▼
                  LlmBackend (Cloud Claude OU Local Gemma)
                              │ + tool use loop
                              ▼
              TextToSpeechEngine (Android natif, voix masculine FR)
```

`AssistantService` (ForegroundService) tient l'état, le wake word, la pipeline,
et l'historique de conversation en mode discussion. La notification
persistante est obligatoire pour le micro en arrière-plan sur Android 14.

## Cerveau IA – Cloud vs Local

Tu peux switcher dans les **Réglages Marvin** (bouton dans l'app) :

| Mode | Quand | Coût | Outils dispo |
|---|---|---|---|
| **Cloud — Claude Haiku 4.5** | Défaut recommandé | ~0,02 €/100 req (5 € de crédit prépayé suffisent des mois) | ✅ tous |
| **Cloud — Claude Sonnet 4.6** | Raisonnement plus poussé | ~5-10× Haiku | ✅ tous |
| **Local — Gemma 2 2B** | Pas d'internet / pas envie de payer | Gratuit | ❌ pas d'outils |

Limite de **50 requêtes/jour** sur le mode Cloud (modifiable dans `Settings.kt`).

## Premier démarrage

### 1. Ouvrir dans Android Studio

```bash
git clone <ce repo>
cd android
```

Ouvre le dossier `android/` dans **Android Studio Hedgehog ou +**.

### 2. Modèle Vosk FR

```bash
curl -L -o vosk.zip https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip
unzip vosk.zip
mv vosk-model-small-fr-0.22/* app/src/main/assets/vosk-fr/
```

Le contenu doit atterrir **directement** dans `assets/vosk-fr/` (donc
`assets/vosk-fr/conf/model.conf` doit exister).

### 3. Clé API Anthropic (mode Cloud)

1. Compte gratuit sur https://console.anthropic.com/
2. **Recharge 5 €** (prépayé, zéro abonnement, dure des mois)
3. Génère une clé API (`sk-ant-...`)
4. **Important** : l'abonnement Claude.ai (web) ne te donne **pas** d'accès
   API, c'est une facturation séparée
5. Tu colles la clé dans **Réglages Marvin** (UI in-app, pas dans
   `local.properties`)

### 4. Modèle Gemma local (optionnel, pour le mode hors-ligne)

1. Va sur https://huggingface.co/google/gemma-2-2b-it (accepte la licence
   Gemma)
2. Télécharge `gemma-2-2b-it-cpu-int4.task` (~1,3 GB)
3. Pousse-le sur le téléphone :
   ```bash
   adb push gemma-2-2b-it-cpu-int4.task \
     /sdcard/Android/data/com.marvin.assistant/files/
   ```
4. Dans Réglages Marvin, choisis « Local — Gemma »

> Note : sans tools en mode local, Marvin ne pourra pas répondre aux
> questions qui demandent du temps réel (météo, agenda, position).

### 5. Build & install

```bash
./gradlew installDebug
```

Au premier lancement de l'app **Marvin** :

1. **Permissions** : micro, SMS, contacts, téléphone, calendrier, position,
   notifications
2. **Service d'accessibilité** (Paramètres → Accessibilité → Marvin) — pour
   les automatisations FamilyWall / Ecovacs / banques
3. **Réglages Marvin** : choisis Cloud ou Local, colle ta clé API Claude
4. **Démarrer Marvin** → la notification « Marvin écoute » apparaît

### 6. Optimisation batterie (One UI)

Sinon Samsung tue le service après quelques heures :

`Paramètres → Batterie → Limites d'utilisation en arrière-plan → Apps non
mises en veille → ajouter Marvin`.

## Tester

Commandes locales (instantanées, pas de Cloud) :

- « Jarvis, lance la musique »
- « Jarvis, mets Daft Punk sur Spotify »
- « Jarvis, envoie un SMS à Marie pour dire que j'arrive »
- « Jarvis, guide-moi vers la Tour Eiffel »
- « Jarvis, appelle Papa »
- « Jarvis, ouvre familywall »
- « Jarvis, combien il me reste sur mon compte » (ouvre Boursobank)

Commandes via Cloud (Claude répond + outils) :

- « Jarvis, quelle météo demain à Lyon ? » → `get_weather`
- « Jarvis, il est quelle heure ? » → `get_time`
- « Jarvis, où je suis ? » → `get_location`
- « Jarvis, j'ai quoi cet après-midi ? » → `get_calendar_events`
- « Jarvis, c'est qui Charles de Gaulle ? » → réponse Claude directe

Mode discussion multi-tours :

- « Jarvis, discutons » → entre en mode discussion
- *(tu enchaînes les questions, Claude garde le contexte)*
- « Jarvis, merci » → sort du mode discussion (ou 30 s de silence)

## Voix « façon Jarvis »

Marvin utilise le TTS Android natif avec :

- Locale : `fr_FR`
- Voix masculine FR détectée automatiquement (sinon défaut)
- Pitch : 0.85 (un peu plus grave)
- Vitesse : 0.95 (légèrement plus posé)

Pour aller plus loin (qualité cinéma), on pourra brancher [Piper TTS](https://github.com/rhasspy/piper)
offline ou [ElevenLabs](https://elevenlabs.io/) (~5 $/mois) en remplacement —
voir TODO.

## Sécurité

- **Banques** : `BankAction` se contente d'ouvrir l'app — jamais de simulation
  de clic sur un bouton « Valider ». L'`AccessibilityService` est restreint
  dans `accessibility_service_config.xml` aux 4 packages cibles uniquement.
- **Clé API Anthropic** : stockée dans des `EncryptedSharedPreferences`
  (chiffrement AES-256 piloté par le keystore Android).
- **Pas de cloud non-Anthropic** : aucune donnée n'est envoyée ailleurs que
  chez Anthropic (et seulement en mode Cloud).
- **Quota quotidien** : 50 req/jour côté Claude pour limiter les dégâts en
  cas de boucle ou de bug.

## Roadmap

- [ ] Piper TTS local pour une voix de meilleure qualité que TTS Android
- [ ] AccessibilityService : automatisation Ecovacs (start / pause / dock)
- [ ] AccessibilityService : lecture du solde affiché Boursobank / Banque Pop
- [ ] Bouton « télécharger Gemma » in-app au lieu d'`adb push`
- [ ] Multi-banques : choisir laquelle quand la commande est ambiguë

## Limites connues

- **Wake word « jarvis »** : Vosk FR n'a pas le mot dans son lexique français
  ; on tente plusieurs orthographes phonétiques (`jarvis`, `djarvis`,
  `djarvisse`, etc.). Si la détection est mauvaise, ajuste la grammaire dans
  `WakeWordEngine.DEFAULT_KEYWORDS`.
- **Vosk small** rate parfois les noms propres (destinations, contacts
  inhabituels). Passe au modèle full (`vosk-model-fr-0.22`, ~1,4 GB) si ça
  pose problème.
- **WhatsApp** : pas d'envoi 100 % auto sans AccessibilityService cliqueur —
  on garde un tap manuel par défaut.
- **Caching Claude** : Haiku 4.5 demande un préfixe ≥ 4096 tokens pour
  cacher. Notre system prompt court est sous ce seuil → cache silencieusement
  ignoré. À 50 req/jour, le coût total reste sous 1 €/mois sans cache.
