# Guide débutant — installer Marvin sur ton S24 Ultra

Ce guide t'amène de zéro à « Marvin écoute mon S24 et m'obéit ». Compte
**1h30-2h la première fois**, dont une bonne partie de téléchargements
qui tournent tout seuls.

> ⚠️ Tu vas devoir taper des **commandes dans un terminal**. C'est
> impressionnant la première fois mais c'est juste : ouvrir un programme
> qui s'appelle « Terminal » (Mac) ou « PowerShell » (Windows), copier-coller
> ce que je te dis, appuyer Entrée. C'est tout.

---

## Vue d'ensemble — ce qu'on va faire

| Étape | Quoi | Combien de temps |
|---|---|---|
| 1 | Préparer ton ordinateur (Java + Android Studio + Git) | 30-45 min (90 % téléchargements) |
| 2 | Préparer ton S24 (mode développeur, débogage USB) | 5 min |
| 3 | Récupérer le code de Marvin depuis GitHub | 2 min |
| 4 | Télécharger le modèle de reconnaissance vocale Vosk (obligatoire) | 10 min |
| 5 | Créer un compte Anthropic + obtenir une clé API (recommandé) | 10 min |
| 6 | Build et installation sur le téléphone | 5-10 min (1ère fois) |
| 7 | Configurer Marvin dans l'app (permissions + clé API) | 5 min |
| 8 | Tester ! | -- |

À la fin tu auras Marvin qui tourne. Pour les mises à jour suivantes, ce
sera 2 commandes (~30 secondes).

---

## Étape 1 — Préparer ton ordinateur

### 1.1 Quel est ton ordinateur ?

- **Mac** : suis les sections marquées 🍎
- **Windows** : suis les sections marquées 🪟
- **Linux** : suis les sections marquées 🐧

Tu auras besoin d'**au moins 15 Go de libre** sur ton disque (Android Studio
+ SDKs c'est lourd).

### 1.2 Installer Java JDK 17

Android Studio en a besoin pour fonctionner.

**🍎 Mac** :
1. Ouvre Terminal (Spotlight → tape "Terminal" → Entrée)
2. Tape :
   ```bash
   /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
   ```
   (installe Homebrew, le gestionnaire de paquets Mac. Suit les instructions à l'écran.)
3. Puis :
   ```bash
   brew install openjdk@17
   ```
4. Vérifie :
   ```bash
   /opt/homebrew/opt/openjdk@17/bin/java -version
   ```
   Ça doit afficher `openjdk version "17.x.x"`.

**🪟 Windows** :
1. Va sur https://adoptium.net/temurin/releases/?version=17
2. Télécharge le `.msi` pour Windows x64
3. Lance le `.msi`, clique "Next" partout, **coche** "Set JAVA_HOME variable"
4. Pour vérifier : ouvre PowerShell (Win+X → "Windows PowerShell")
   ```powershell
   java -version
   ```
   Doit afficher `openjdk version "17.x.x"`.

**🐧 Linux** :
```bash
sudo apt install openjdk-17-jdk      # Ubuntu/Debian
# ou
sudo dnf install java-17-openjdk     # Fedora
```

### 1.3 Installer Git

C'est l'outil pour récupérer le code.

**🍎 Mac** : déjà installé ! Vérifie : `git --version` → doit donner un numéro.

**🪟 Windows** :
1. Va sur https://git-scm.com/download/win
2. Télécharge → installe → "Next" partout (les défauts sont bons)
3. Une fois fini, dans le menu démarrer cherche **"Git Bash"** — c'est un
   terminal qu'on utilisera pour les commandes git. Plus pratique que PowerShell
   pour ça.

**🐧 Linux** : `sudo apt install git` (déjà installé sur 99% des distros)

### 1.4 Installer Android Studio

C'est l'éditeur qui va builder l'app et la pousser sur ton téléphone.

1. Va sur https://developer.android.com/studio
2. Clique **« Download Android Studio »**, accepte la licence
3. Télécharge (~1.2 Go)
4. Lance l'installeur :
   - **🍎 Mac** : drag-and-drop dans Applications, puis lance
   - **🪟 Windows** : exécute le `.exe`, "Next" partout
   - **🐧 Linux** : extrait le tar.gz, lance `bin/studio.sh`
5. Au premier lancement :
   - Clique **« Standard »** quand on demande le type d'install
   - Accepte toutes les licences SDK
   - **Ça télécharge ~3 Go de SDKs** → patience (15-30 min)
6. Quand l'écran d'accueil "Welcome to Android Studio" apparaît, c'est bon.

---

## Étape 2 — Préparer ton S24 Ultra

### 2.1 Activer le « mode développeur »

C'est une option Samsung cachée par défaut.

1. **Paramètres** → **À propos du téléphone** → **Informations sur le logiciel**
2. Trouve la ligne **« Numéro de version »**
3. Tape dessus **7 fois rapidement** (oui sept).
4. Au bout de 4 taps, un toast en bas dit « Vous êtes à 3 étapes de devenir développeur ». Continue de taper.
5. Au 7e tap, ça te demande ton code de verrouillage.
6. Voilà, le mode développeur est activé.

### 2.2 Activer le débogage USB

1. **Paramètres** → **Options développeur** (nouveau menu, en bas du Settings principal)
2. Active le toggle tout en haut (« Options développeur » lui-même doit être ON)
3. Trouve **« Débogage USB »** → active

### 2.3 Brancher le téléphone à l'ordi

1. Branche ton S24 à l'ordi avec **un vrai câble USB** (les câbles "charge only" ne marchent pas — utilise celui qui est venu avec ton tel ou un câble data)
2. Sur le téléphone, une notification apparaît : choisir le mode USB → choisis **« Transfert de fichiers (MTP) »** ou **« Transfert de fichiers »**
3. Une popup demande **« Autoriser le débogage USB ? »** → coche **« Toujours autoriser depuis cet ordinateur »** → **OK**

### 2.4 Vérifier que l'ordi voit le téléphone

Ouvre un terminal sur l'ordi (Terminal sur Mac, PowerShell ou Git Bash sur Windows) et tape :

```bash
~/Library/Android/sdk/platform-tools/adb devices    # 🍎 Mac
%LOCALAPPDATA%\Android\Sdk\platform-tools\adb devices    # 🪟 Windows (PowerShell)
~/Android/Sdk/platform-tools/adb devices             # 🐧 Linux
```

Tu devrais voir :
```
List of devices attached
RFCXXXXXXXXX    device
```

Si à la place tu vois `unauthorized`, débranche, regarde sur le tel s'il y a une popup, accepte. Re-essaie.

---

## Étape 3 — Récupérer le code de Marvin

Toujours dans le terminal :

```bash
cd ~/Documents       # ou n'importe quel dossier
git clone <URL_DU_REPO_QUE_JE_T_AI_FILÉ>
cd marvin/android
git checkout claude/voice-ai-assistant-y3gkW
```

> Si tu n'as pas l'URL, va sur GitHub voir ton repo `lafmarvin-boop/marvin`,
> clique le bouton vert **« Code »**, copie l'URL HTTPS.

À la fin, le dossier courant est `~/Documents/marvin/android`. C'est là que vit le projet.

---

## Étape 4 — Télécharger le modèle Vosk (reconnaissance vocale, obligatoire)

Vosk c'est ce qui permet à Marvin de transformer ta voix en texte.

```bash
# Toujours dans ~/Documents/marvin/android
curl -L -o vosk.zip https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip
unzip vosk.zip
mv vosk-model-small-fr-0.22/* app/src/main/assets/vosk-fr/
rm -rf vosk-model-small-fr-0.22 vosk.zip
```

**🪟 Windows** : le `unzip` n'existe pas dans PowerShell. Utilise plutôt :
```powershell
Invoke-WebRequest -Uri https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip -OutFile vosk.zip
Expand-Archive vosk.zip -DestinationPath .
Move-Item vosk-model-small-fr-0.22\* app\src\main\assets\vosk-fr\
Remove-Item vosk-model-small-fr-0.22, vosk.zip
```

Vérifie que ça a marché :
```bash
ls app/src/main/assets/vosk-fr/conf/model.conf       # 🍎 🐧
dir app\src\main\assets\vosk-fr\conf\model.conf       # 🪟
```

Le fichier doit exister.

---

## Étape 5 — Créer un compte Anthropic + obtenir une clé API

C'est ce qui permet à Marvin de répondre aux questions générales et de discuter. Compte gratuit, **5 € de crédit prépayé suffit pour des mois** (~250 questions par jour pendant un mois sur du Haiku).

> ⚠️ Important : c'est **séparé** de ton abonnement claude.ai. Si tu paies déjà Claude.ai, tu dois quand même créer un compte API séparé.

1. Va sur https://console.anthropic.com/
2. Inscris-toi (email + mot de passe). **L'email pro n'est PAS obligatoire**, ton email perso marche.
3. Une fois connecté : menu **Settings** (en bas à gauche) → **Billing** → **Add credit**
4. Recharge **5 €** (ou 5 $ — environ pareil). C'est du prépayé, zéro abonnement.
5. Menu **Settings** → **API keys** → **Create Key**
6. Donne un nom (ex: « Marvin S24 ») → **Create**
7. **Copie la clé maintenant** — elle commence par `sk-ant-...`. Elle ne sera plus jamais affichée. Note-la quelque part en sécurité.

Garde cette clé sous la main, on l'utilisera à l'étape 7.

---

## Étape 6 — Build et installation

C'est là qu'on installe Marvin sur ton téléphone.

### 6.1 Ouvrir le projet dans Android Studio

1. Lance Android Studio
2. Sur l'écran d'accueil : **« Open »**
3. Navigue jusqu'à `~/Documents/marvin/android` → **OK**
4. Première ouverture : Android Studio télécharge plein de trucs (Gradle, dépendances). **Patience, 5-15 min**.
5. En bas de l'écran tu vois la barre de progression « Gradle Build ». Quand elle disparaît et que tu vois « Build Successful » dans le terminal en bas, c'est prêt.

> Si tu as des popups « Plugin updates » ou « Android SDK update » → clique « Update » et relance.

### 6.2 Lancer l'app sur ton téléphone

1. Ton S24 est toujours branché en USB.
2. En haut de Android Studio, à droite des icônes de play : un menu déroulant qui dit le nom de ton téléphone (ex: « Pixel » ou « SM-S928 »). **Sélectionne ton S24** (« samsung SM-S928... »).
3. Clique le **bouton ▶️ (Run 'app')** vert tout à droite.
4. Compilation. **Patience, ~3-5 min la première fois**.
5. Sur ton téléphone, l'app **Marvin** s'ouvre automatiquement.

🎉 Si tu vois l'écran d'accueil avec « Marvin » écrit en gros et trois boutons, **c'est gagné**. Sinon, va voir la section Troubleshooting tout en bas.

---

## Étape 7 — Configurer Marvin dans l'app (5 min)

Ton app Marvin est ouverte sur le téléphone. Suis dans l'ordre :

### 7.1 Accorder les permissions

1. Tap **« Accorder les permissions »**
2. Une série de popups Android te demande l'autorisation pour : **Micro, SMS, Contacts, Téléphone, Notifications, Calendrier, Position, Lecture SMS, Journal d'appels**.
3. Tap **« Autoriser »** sur chacune. Toutes.

> Si tu refuses une permission, l'outil correspondant ne marchera juste pas. Tu peux toujours revenir dans Paramètres Android plus tard.

### 7.2 Activer le service d'accessibilité (pour FamilyWall, Ecovacs, banques)

1. Tap **« Activer le service d'accessibilité »**
2. Ça ouvre les Paramètres Android sur la liste des services d'accessibilité
3. Trouve **« Marvin – contrôle d'apps »** dans la liste, tap dessus
4. Active le toggle, accepte la confirmation
5. Reviens à l'app Marvin (bouton retour Android)

### 7.3 Activer l'accès aux notifications (pour l'outil de lecture des notifs)

1. Sur ton téléphone : **Paramètres** → **Notifications** → **Paramètres avancés** → **Accès aux notifications** (ou direct : **Apps** → **Accès spécial** → **Accès aux notifications**)
2. Trouve **Marvin** dans la liste, active le toggle
3. Reviens à l'app

### 7.4 Coller ta clé API Anthropic

1. Dans l'app Marvin, tap **« Réglages Marvin »**
2. Section **« Cerveau IA »** : laisse **« Cloud — Claude »** sélectionné
3. Section **« Modèle Claude »** : laisse **Haiku 4.5** (recommandé)
4. Section **« Clé API Anthropic »** : tap dans le champ, **colle ta clé `sk-ant-...`** (de l'étape 5)
5. (Optionnel) Coche **« Confirmer les actions sensibles »** — Marvin demandera oralement avant chaque SMS/appel
6. (Optionnel) PIN : crée un PIN si tu veux protéger l'écran Réglages
7. Descends jusqu'au bouton **« Enregistrer »** → tap

### 7.5 Démarrer Marvin

1. Reviens à l'écran d'accueil
2. Tap **« Démarrer Marvin »**
3. Une notif apparaît dans la barre d'état : **« Marvin écoute »**

🎉 **Marvin est en marche.**

---

## Étape 8 — Tester !

Garde le téléphone près de toi. Parle à voix normale, distinctement.

### Commandes locales (instantanées)

- « **Jarvis, lance la musique** »
- « **Jarvis, mets Daft Punk sur Spotify** »
- « **Jarvis, guide-moi vers la Tour Eiffel** »
- « **Jarvis, appelle Papa** » (Marvin demande confirmation avant)
- « **Jarvis, envoie un SMS à Marie pour dire que j'arrive** » (idem)

### Commandes via Claude (réponse en ~2 s)

- « **Jarvis, quelle météo demain à Lyon ?** »
- « **Jarvis, il est quelle heure ?** »
- « **Jarvis, j'ai quoi de prévu cet après-midi ?** »
- « **Jarvis, c'est qui Charles de Gaulle ?** »
- « **Jarvis, quel est mon niveau de batterie ?** »

### Mode discussion multi-tours (avec visuel plein écran)

- « **Jarvis, discutons** » → l'écran réacteur s'ouvre, tu enchaînes les questions
- « **Jarvis, merci** » → ferme le mode discussion

### Effacer toutes les données

- « **Jarvis, efface tout** » → Marvin demande de dire « **oui efface** » pour confirmer

---

## Étape 9 — Optimisation batterie Samsung (IMPORTANT)

Sinon One UI tue Marvin après quelques heures :

1. **Paramètres** → **Batterie** → **Limites d'utilisation en arrière-plan**
2. Section **« Apps non mises en veille »** → **+** → ajoute **Marvin**

---

## Mises à jour ultérieures (le workflow simple)

Quand je pousse de nouvelles versions sur GitHub, voici comment récupérer
sur ton tel.

**Téléphone branché en USB, app fermée :**

1. Ouvre un terminal, va dans le dossier du projet :
   ```bash
   cd ~/Documents/marvin/android
   ```
2. Récupère mes modifs :
   ```bash
   git pull origin claude/voice-ai-assistant-y3gkW
   ```
3. Build + install :
   ```bash
   ./gradlew installDebug              # 🍎 🐧
   gradlew installDebug                # 🪟
   ```
4. Sur le tel, ouvre Marvin → tap **« Démarrer Marvin »** (Android tue le service à chaque réinstall, c'est normal)

⚠️ Tes données (clé API, modèle Vosk, réglages) **sont préservées** entre les
réinstalls.

### Plus pratique : ADB sans fil (pas de câble à brancher chaque fois)

**Une seule fois** :

1. Sur le S24 : **Paramètres → Options développeur → Débogage sans fil** → ON
2. Tap **« Jumeler avec un code WiFi »** → te donne un code à 6 chiffres + une adresse
3. Sur l'ordi :
   ```bash
   adb pair 192.168.X.X:XXXXX        # adresse affichée pour pairing
   # entre le code à 6 chiffres
   adb connect 192.168.X.X:5555      # autre adresse, pour la connexion permanente
   adb devices                        # vérifie : ton tel doit apparaître
   ```

Désormais tu peux faire `./gradlew installDebug` sans câble. À chaque
redémarrage du tel ou de l'ordi, refais juste `adb connect ...`.

---

## Troubleshooting (« ça marche pas »)

### Android Studio dit « Unable to detect adb » ou « no devices »
- Vérifie que le câble est un câble data (pas un câble juste de charge)
- Sur le tel : Paramètres → Options développeur → **Révoquer les autorisations de débogage USB** → débranche → rebranche → accepte la nouvelle popup

### Android Studio bloque sur « Gradle sync » pendant >30 min
- Vérifie ton internet
- File → Invalidate Caches → Invalidate and Restart
- Si ça persiste : supprime `~/.gradle/caches/`, relance

### Le build échoue avec une erreur de licence SDK
- Dans Android Studio : **Tools → SDK Manager → SDK Tools tab → Accept all licenses**
- Ou en terminal : `~/Library/Android/sdk/cmdline-tools/latest/bin/sdkmanager --licenses` (Mac)

### L'app installée mais elle plante au lancement
- Branche le tel, dans Android Studio onglet **Logcat** en bas, regarde les logs en rouge
- Copie-colle le stack trace, je te dirai quoi faire
- Souvent c'est : Vosk model pas placé au bon endroit, ou clé API manquante

### « Jarvis » n'est jamais détecté
- Le modèle Vosk small FR a un peu de mal avec « Jarvis ». Essaie de prononcer plus posé, ou en accentuant : « **Djar**-viss ».
- Vérifie dans Logcat (filter par "WakeWord") si Vosk transcrit autre chose
- En dernier recours, on peut entraîner sur tes propres samples — dis-le moi

### Marvin n'entend rien après « Jarvis »
- Approche le tel
- Vérifie qu'aucune autre app n'utilise le micro (Spotify enregistrement, appel en cours…)

### La permission SMS est refusée par Android sans demander
- Va dans **Paramètres → Apps → Marvin → Autorisations → SMS** → active manuellement
- Sur Android récent (14+), Google bloque parfois SMS pour les apps non-Play-Store : `adb shell pm grant com.marvin.assistant android.permission.READ_SMS`

---

## Aller plus loin (optionnel)

### Mode local Gemma (gratuit, sans clé API)
Voir README.md section « Gemma local ». Ça demande de pousser un modèle de 1.3 GB sur le tel.

### Voice biometric (Marvin n'écoute que toi)
Voir README.md section « Voice biometric ». Ça demande de pousser un AAR + un modèle ~26 MB. Une fois fait, tu enrôles ta voix via Réglages → Voice biometric → Enrôler.

### Build durci (release signé, avec ProGuard)
Voir README.md section « Build release durci ». Pour distribution, pas pour usage perso.

---

## En résumé

Tu auras besoin de revenir voir ce guide la première fois pour l'install
complète. Après ça, le cycle de mise à jour c'est **3 commandes** :

```bash
cd ~/Documents/marvin/android
git pull origin claude/voice-ai-assistant-y3gkW
./gradlew installDebug
```

Et tap « Démarrer Marvin » sur le tel.

Si à n'importe quelle étape tu bloques : screenshot l'erreur, envoie-la moi,
on déblocke ensemble.
