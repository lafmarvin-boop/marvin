# Marvin – Assistant vocal Android (100 % local)

Assistant vocal mains-libres pour Samsung S24 Ultra (Android 14). Tu dis
**« yo poto »**, Marvin t'écoute, comprend une commande et la dispatch vers
l'app concernée. Tout tourne **on-device** : pas de cloud, pas d'API payante.

## Apps gérées

| App | Statut | Comment |
|---|---|---|
| SMS | ✅ Auto-envoi | `SmsManager` + résolution contact |
| Appels | ✅ Auto-lancement | `ACTION_CALL` |
| WhatsApp | ⚠️ Pré-rempli | `wa.me` deep-link – tu valides l'envoi |
| Spotify | ✅ Play/Pause/Next/Prev/Search | media buttons + deep-link `spotify:search:` |
| Waze | ✅ Navigation | `waze.com/ul?q=...&navigate=yes` |
| FamilyWall | 🟡 Ouvre l'app | pas d'API publique, automatisation à venir via AccessibilityService |
| Ecovacs Home | 🟡 Ouvre l'app | pas d'API publique, automatisation à venir |
| Boursobank / Banque Pop | 🟡 Ouvre l'app (lecture seule) | **jamais d'action transactionnelle** |

## Architecture

```
[Microphone] ──► WakeWordEngine (Porcupine, "yo poto")
                       │ détection
                       ▼
              SpeechToText (Vosk FR, offline)
                       │ texte
                       ▼
              IntentParser (regex FR)
                       │ MarvinIntent
                       ▼
              ActionExecutor ──► SMS / WhatsApp / Spotify / Waze / Intents
                       │
                       ▼
              TextToSpeechEngine (Android natif, FR)
```

Tout vit dans un `ForegroundService` (`AssistantService`) avec une notification
persistante – c'est obligatoire pour utiliser le micro en arrière-plan sur
Android 14.

## Premier démarrage

### 1. Cloner et ouvrir dans Android Studio

```bash
git clone <ce repo>
cd android
```

Ouvre le dossier `android/` dans **Android Studio Hedgehog (2023.1.1) ou plus
récent**. Laisse-le générer le wrapper Gradle si nécessaire (`File → Sync
Project with Gradle Files`).

### 2. Configurer le wake word « yo poto »

1. Crée un compte gratuit sur https://console.picovoice.ai/
2. Onglet **Porcupine → Train a wake word**
   - Phrase : `Yo Poto`
   - Plateforme : `Android (arm64)`
3. Télécharge le `.ppn`, renomme-le **`yo_poto_android.ppn`**
4. Pose-le dans `app/src/main/assets/wakeword/`
5. Copie ton **AccessKey** (page d'accueil de la console) dans `local.properties` :
   ```
   PICOVOICE_ACCESS_KEY=xxxxxxxxxxxxxxxxxxxx
   ```

> Le plan gratuit Picovoice autorise 3 appareils en usage personnel : large.

### 3. Télécharger le modèle Vosk FR

```bash
curl -L -o vosk.zip https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip
unzip vosk.zip
mv vosk-model-small-fr-0.22/* app/src/main/assets/vosk-fr/
```

(Le contenu du modèle doit atterrir directement dans `assets/vosk-fr/`,
**pas** dans un sous-dossier.)

### 4. Build & install

Branche le S24 Ultra en USB (mode développeur + débogage USB activés), puis :

```bash
./gradlew installDebug
```

Lance l'app **Marvin** :
1. Accorde toutes les permissions (micro, SMS, contacts, téléphone, notifications).
2. **Active le service d'accessibilité** dans `Paramètres → Accessibilité →
   Apps installées → Marvin → Activer`. Sans ça, l'automatisation FamilyWall /
   Ecovacs / banques ne marche pas (même si les SMS, Spotify, Waze, etc.
   marchent quand même via Intents).
3. Tap **Démarrer Marvin**. La notification « Marvin écoute » apparaît.

### 5. Tester

Dis :
- « Yo poto, lance la musique »
- « Yo poto, mets Daft Punk sur Spotify »
- « Yo poto, envoie un SMS à Marie pour dire que j'arrive »
- « Yo poto, guide-moi vers la Tour Eiffel »
- « Yo poto, appelle Papa »
- « Yo poto, ouvre familywall »

## Sécurité bancaire

L'`AccessibilityService` est restreint (cf. `accessibility_service_config.xml`)
aux **packages explicitement listés**. Les actions bancaires sont **strictement
en lecture** : `BankAction` se contente d'ouvrir l'app, jamais de simuler un
clic sur un bouton « Valider » ou « Virer ». Ne change pas ce périmètre sans
y réfléchir – un bug pourrait virer de l'argent.

## Étapes suivantes

- [ ] Étape 2 (à venir) : enrichir `MarvinAccessibilityService` pour cliquer
      les bons boutons dans Ecovacs Home (Démarrer / Pause / Dock).
- [ ] Étape 3 : lecture du solde affiché à l'écran dans Boursobank et Banque Pop.
- [ ] Étape 4 (optionnel) : fallback NLU local via MediaPipe + Gemma 2 2B
      pour les phrases hors regex (déjà câblé en dépendance, pas encore branché).

## Limites connues

- Vosk small fait quelques fautes sur les noms propres ; pour des destinations
  précises ou des contacts à orthographe inhabituelle, passe au modèle complet
  (`vosk-model-fr-0.22`, ~1.4 GB).
- WhatsApp ne permet pas l'envoi 100 % automatique sans AccessibilityService
  qui clique le bouton — on garde un tap manuel par défaut.
- Sur Samsung One UI, vérifie que **Marvin** est en « Pas d'optimisation
  batterie » (`Paramètres → Batterie → Limites d'utilisation en arrière-plan
  → Apps non mises en veille → ajouter Marvin`), sinon le service est tué
  après quelques heures.
