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
Claude reçoit la question avec 9 outils qu'il peut appeler tout seul :

| Outil | Source | Permission requise |
|---|---|---|
| `get_weather` | [Open-Meteo](https://open-meteo.com/) | — |
| `get_time` | Horloge du téléphone | — |
| `get_location` | GPS (FusedLocationProvider) | `ACCESS_FINE_LOCATION` |
| `get_calendar_events` | Calendrier Android | `READ_CALENDAR` |
| `get_battery` | BatteryManager | — |
| `get_device_info` | Build + StatFs | — |
| `get_recent_sms` | SMS Provider | `READ_SMS` |
| `get_recent_calls` | Call Log | `READ_CALL_LOG` |
| `get_unread_notifications` | NotificationListenerService | Accès aux notifications (réglages) |

⚠️ **Confidentialité** : en mode Cloud, le contenu des SMS / notifications / appels que Claude lit est envoyé à `api.anthropic.com` quand il appelle l'outil. Si tu veux désactiver une catégorie, on peut ajouter des toggles dans les Réglages.

## Mode discussion — visualiseur "réacteur"

Quand tu dis « jarvis discutons », Marvin lance un écran plein écran avec
un visualiseur animé : anneaux concentriques en rotation, glow cyan, triangle
central qui pulse. Le visuel change selon la phase :

- **J'écoute** — pulsation rapide, cyan clair
- **Je réfléchis** — pulsation plus lente, indigo
- **Je parle** — pulsation très rapide, cyan saturé, le texte de la
  réponse s'affiche dessous

L'écran reste allumé pendant la discussion (`FLAG_KEEP_SCREEN_ON`) et se
ferme automatiquement quand tu sors (« merci » ou silence prolongé).

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

1. **Permissions runtime** : micro, SMS (envoi + lecture), contacts,
   téléphone, journal d'appels, calendrier, position, notifications
2. **Accès aux notifications** (Paramètres → Apps → Accès spécial → Accès
   aux notifications → Marvin) — pour l'outil `get_unread_notifications`
3. **Service d'accessibilité** (Paramètres → Accessibilité → Marvin) —
   pour les automatisations FamilyWall / Ecovacs / banques
4. **Réglages Marvin** : choisis Cloud ou Local, colle ta clé API Claude
5. **Démarrer Marvin** → la notification « Marvin écoute » apparaît

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

### Stockage local

- **Clé API Anthropic** chiffrée dans `EncryptedSharedPreferences` (AES-256 GCM,
  clé maître stockée dans l'Android Keystore — non-extractible même avec root).
- Réglages, quota et historique en `SharedPreferences` standard. Pas sensible.
- L'historique de discussion **n'est jamais persisté sur disque** — il vit en
  RAM dans le `AssistantService` et meurt avec lui.

### Confidentialité Anthropic (mode Cloud uniquement)

- Anthropic conserve les requêtes **30 jours** pour détection d'abus, puis
  supprime. Pas d'entraînement par défaut côté API.
- Tout en TLS, juridiction US.
- En mode local Gemma, **rien ne quitte le téléphone**.

### Confirmation orale obligatoire

Pour les actions destructrices ou irréversibles, Marvin demande oralement
« tu confirmes ? » avant d'exécuter :

| Action | Confirmation par défaut |
|---|---|
| Envoi SMS, appel, WhatsApp | ✅ activable/désactivable dans Réglages |
| Wipe de toutes les données | ✅ **toujours obligatoire** + mot précis « efface » |
| Banques (transactionnel) | N'existe pas — jamais automatisé |

Toggle « Confirmer les actions sensibles » dans `Réglages Marvin` pour
désactiver la confirmation des SMS/appels (à tes risques).

### Toggles par outil

Dans `Réglages Marvin → Outils que Claude peut appeler`, tu peux désactiver
chaque outil individuellement. Si tu coupes `get_recent_sms`, Claude ne saura
même pas que cet outil existe — il ne pourra pas y accéder, point. C'est la
garantie la plus forte si tu veux bloquer une catégorie de données.

### « Jarvis efface tout » — wipe complet

Trois façons d'effacer toutes les données :

1. **Voix** : « Jarvis, efface tout » → Marvin demande « dis "oui efface" pour
   confirmer ». Le mot « efface » est obligatoire (un simple « oui »
   accidentel ne déclenchera rien). Wipe + arrêt du service.
2. **UI** : `Réglages Marvin → Zone de danger → Tout effacer` (bouton rouge)
   avec dialogue de confirmation.
3. **Manuel** : `adb shell pm clear com.marvin.assistant` (efface aussi
   modèles Vosk/Gemma).

Wipe efface : clé API Anthropic, réglages, quota, historique. **N'efface pas**
les modèles Vosk/Gemma (ce sont des assets, pas des données personnelles —
gain de temps pour ré-utiliser l'app après wipe).

### PIN d'accès aux Réglages

- Configurable dans `Réglages → Sécurité → PIN d'accès aux Réglages` (4-6 chiffres).
- PIN stocké chiffré dans `EncryptedSharedPreferences` (clé maître liée au keystore).
- Comparaison constant-time pour éviter les timing attacks.
- 5 mauvais essais consécutifs → lockout 30 secondes.
- Si tu oublies le PIN : `adb shell pm clear com.marvin.assistant` (perd toutes les données) — c'est l'unique moyen de reset.

### Allowlist SMS

Dans `Réglages → Sécurité → Allowlist SMS`, tu peux mettre une liste de fragments
de noms (séparés par virgules, ex: « Marie, Papa, école »). Si la liste est non
vide, l'outil `get_recent_sms` ne retourne **que** les SMS provenant de contacts
dont le nom contient un de ces fragments — Claude ne verra jamais les autres.

Insensible à la casse et aux accents.

### Certificate pinning sur api.anthropic.com

Le code est en place dans `ClaudeBackend.kt` mais **désactivé par défaut**
(`PINS_ENABLED = false`). Pour l'activer :

1. Extraire le SPKI hash du leaf cert :

   ```bash
   echo | openssl s_client -servername api.anthropic.com \
     -connect api.anthropic.com:443 2>/dev/null \
     | openssl x509 -pubkey -noout \
     | openssl pkey -pubin -outform der \
     | openssl dgst -sha256 -binary | base64
   ```

2. Extraire un pin de backup (intermediate CA), pour ne pas casser l'app
   si Anthropic rotate le leaf :

   ```bash
   openssl s_client -showcerts -servername api.anthropic.com \
     -connect api.anthropic.com:443 < /dev/null 2>/dev/null \
     | awk '/BEGIN/,/END/{print}' \
     | awk 'BEGIN{n=0} /BEGIN/{n++} {print > "cert_" n ".pem"}'
   # Puis sur cert_2.pem (intermediate):
   openssl x509 -in cert_2.pem -pubkey -noout \
     | openssl pkey -pubin -outform der \
     | openssl dgst -sha256 -binary | base64
   ```

3. Coller dans `ClaudeBackend.kt` :

   ```kotlin
   private const val PINS_ENABLED = true
   private val CERT_PINS = listOf(
       "sha256/<leaf hash>",
       "sha256/<intermediate hash>"
   )
   ```

⚠️ **Risque** : si Anthropic rotate les certs et que tu n'as pas mis à jour
les pins, l'app sera incapable de joindre l'API jusqu'à ce que tu refasses
l'extraction. C'est pour ça que c'est off par défaut.

### Build release durci

Le build debug (`./gradlew installDebug`) est OK pour développer mais
décompile facilement. Pour distribuer (toi-même, à toi-même), build release :

1. Génère un keystore une fois :

   ```bash
   keytool -genkey -v -keystore marvin-release.jks \
     -keyalg RSA -keysize 2048 -validity 10000 -alias marvin
   ```

2. Mets les valeurs dans `local.properties` :

   ```
   MARVIN_KEYSTORE_PATH=/chemin/vers/marvin-release.jks
   MARVIN_KEYSTORE_PASSWORD=...
   MARVIN_KEY_ALIAS=marvin
   MARVIN_KEY_PASSWORD=...
   ```

3. Build :

   ```bash
   ./gradlew assembleRelease
   # APK: app/build/outputs/apk/release/app-release.apk
   ```

   En release : `isMinifyEnabled=true` + `isShrinkResources=true` → ProGuard/R8
   obfusque le code, supprime le code mort. Reverse engineering nettement
   plus pénible.

> Si tu skip le keystore, le build release retombe sur le keystore debug
> (signature partagée publiquement, OK pour tester localement, **pas pour
> distribuer**).

### Vecteurs d'attaque connus + mitigations

| Vecteur | Mitigation actuelle | Vraie limite |
|---|---|---|
| Wake word déclenchable par n'importe qui | Confirmation orale pour SMS/appels/WhatsApp | Voice biometric pas encore (cf. plan ci-dessous) |
| Phone déverrouillé par un tiers | Verrouillage Android + **PIN d'app** sur l'écran Réglages | Le service tourne sans PIN — un tiers pourrait dire « jarvis lance Spotify ». Pour les actions sensibles, la confirmation orale couvre. |
| APK décompilé | Build release : `isMinifyEnabled=true` + ProGuard/R8 | Obfuscation, pas chiffrement — un attaquant déterminé peut toujours reverse |
| Réseau intercepté | TLS 1.3, **certificate pinning prêt à activer** (cf. ci-dessus) | Désactivé par défaut tant que les pins ne sont pas extraits |
| Clé API extraite | EncryptedSharedPreferences (AES-256 keystore) | Si quelqu'un a root, il peut extraire la clé maître |
| App malveillante avec accès accessibilité | `AccessibilityService` Marvin restreint à 4 packages | Une autre app accessibility pourrait observer Marvin (mitigation Android standard) |

### Voice biometric — pourquoi c'est différé

L'idée séduisante : « Marvin ne se déclenche que si c'est *moi* qui dit
"jarvis", pas un autre ». Techniquement c'est faisable mais c'est un projet
à part de plusieurs jours. Ce que ça demande honnêtement :

1. **Modèle d'embedding vocal** type WeSpeaker / SpeechBrain / pyannote,
   compilé pour Android. Les libs prêtes-à-l'emploi sont rares :
   - **sherpa-onnx** (k2-fsa, MIT) : a un sample `speaker-identification`
     Android, mais demande de vendrer un AAR + télécharger un modèle (~15 MB).
   - **Picovoice Eagle** : un SDK propre, mais on a déjà vu qu'ils demandent
     un AccessKey (même piège que Porcupine au début).
2. **Phase d'enrôlement** : enregistrer 3-5 fois la phrase « Jarvis » pour
   capturer ton empreinte vocale, calculer un embedding moyen, le stocker.
3. **Vérif au moment du wake word** : ré-extraire un embedding du segment
   audio qui a déclenché Vosk, calculer la similarité cosinus avec ton
   empreinte, rejeter si seuil < 0.7 (typique).
4. **Faux positifs / négatifs** : même les meilleurs modèles ont 1-5 % de
   FAR/FRR. Tu seras parfois rejeté quand tu es enrhumé, ou un sosie vocal
   passera. Pas d'illusion.

**Pourquoi je l'ai différé honnêtement** : pour un assistant perso sur ton
téléphone (pas une enceinte connectée laissée seule), la vraie surface
d'attaque c'est :

- Quelqu'un qui a déverrouillé ton téléphone → couvert par le verrouillage
  Android et les confirmations orales pour les actions sensibles.
- La TV / un voisin qui dit « jarvis » → pas une attaque, juste un agacement.
  Au pire ça déclenche une écoute qui timeout dans 6 s sans rien faire.

Le voice biometric apporte un vrai gain pour des cas de niche (assistant
laissé sur la table en open-space, partenaire taquin, etc.). Si tu en veux
vraiment, dis-le et je l'attaque comme étape dédiée — il y a ~300 lignes +
intégration sherpa-onnx.

### Garde-fous bancaires

- `BankAction` se contente d'ouvrir l'app, **jamais** de simuler un clic sur
  « Valider » ou « Virer ».
- `accessibility_service_config.xml` restreint l'AccessibilityService aux
  packages bancaires explicites — Marvin ne reçoit aucun événement des
  autres apps.
- Aucune commande vocale ne peut déclencher un virement. Si on automatise
  un jour la lecture du solde (étape 4), ce sera **lecture seule** et
  documenté noir sur blanc.

### Quotas et budgets

- 50 requêtes Claude/jour (compteur reset à minuit) — éveille un mur si bug
- `max_tokens=200` côté Claude — réponses bornées
- Timeout 15 s par appel — pas de boucle infinie

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
