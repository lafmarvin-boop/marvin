# PixelHero — Android Studio Project

Application Android native (Kotlin) pour créer des personnages en pixel art et
des frames d'animation. Optimisée tactile pour smartphone et tablette.

## Ouvrir dans Android Studio

1. Ouvrir **Android Studio** (Hedgehog 2023.1.1 ou plus récent recommandé).
2. **File → Open** → sélectionner le dossier `pixelart-android`.
3. Cliquer **OK**. Android Studio va :
   - télécharger Gradle 8.7 si nécessaire (1ère fois ~2-3 min)
   - télécharger les dépendances AndroidX/Material/Kotlin
   - synchroniser le projet
4. Connecter un téléphone Android en USB (mode développeur activé) ou démarrer
   un émulateur (AVD Manager).
5. Cliquer le bouton ▶ **Run 'app'**.

> **Min SDK 24** (Android 7.0) — couvre ~98% des appareils en circulation.
> **Target SDK 34** (Android 14).

## Fonctionnalités

- **Création de projet** : dimensions 1×1 → 512×512, presets 16/24/32/48/64/128.
- **Outils** : crayon, gomme, pot de peinture (flood fill), pipette, ligne,
  rectangle plein/vide, déplacement de la vue.
- **Couleurs** : palette 24 couleurs + sélecteur RGB custom + 16 récentes.
- **Image de référence** (fond) : importer une photo, opacité réglable.
- **Frames d'animation** : ajout, duplication, suppression, réorganisation
  (boutons ▲▼).
- **Onion skinning** : frame précédente en transparence pour aider à animer.
- **Aperçu animation** : lecture en boucle, FPS réglable (1-60).
- **Export** dans le dossier `Pictures/PixelHero/` du téléphone :
  - PNG (frame courante, ×8)
  - Sprite sheet (toutes frames en grille)
  - GIF animé (encodeur GIF89a fait maison, fonctionne hors ligne)
- **Sauvegarde locale** : projets dans le stockage interne de l'app (JSON +
  base64 des pixels).
- **Undo / Redo** : jusqu'à 80 niveaux.
- **Gestes tactiles** :
  - 1 doigt : dessiner avec l'outil sélectionné
  - 2 doigts (pinch) : zoom + pan
  - Outil **Déplacer** : pan avec un seul doigt

## Logo / Icône

L'icône de lancement utilise un **héros chibi pixel art 16×16** dessiné en
adaptive icon (foreground + background). Le foreground est défini dans
`app/src/main/res/drawable/ic_launcher_foreground.xml` (vector drawable), et
des fallbacks PNG sont générés pour API 24-25 dans les dossiers `mipmap-*`.

Pour personnaliser l'icône :
1. Éditer le tableau `ART` dans `tools/gen-launcher-icon.js` et
   `tools/gen-mipmaps.js`.
2. Régénérer :
   ```bash
   node tools/gen-launcher-icon.js   # vector drawable
   node tools/gen-mipmaps.js          # PNG fallbacks
   ```

## Structure du projet

```
pixelart-android/
├── build.gradle.kts                    # Plugins (Android, Kotlin)
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── tools/
│   ├── gen-launcher-icon.js            # Régénère l'icône vector
│   └── gen-mipmaps.js                  # Régénère les PNG d'icône
└── app/
    ├── build.gradle.kts                # Dépendances AndroidX, Material, Kotlin
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/pixelhero/app/
        │   ├── MainActivity.kt         # Activité principale + UI wiring
        │   ├── PixelCanvasView.kt      # Custom View (dessin + tactile + zoom)
        │   ├── Models.kt               # Project, Frame, Tool, UndoSnapshot
        │   ├── ProjectStorage.kt       # Sauvegarde/chargement JSON
        │   ├── GifEncoder.kt           # Encodeur GIF89a animé
        │   ├── SwatchAdapter.kt        # Palette / couleurs récentes
        │   └── FramesAdapter.kt        # Liste des frames
        └── res/
            ├── layout/                 # activity_main, dialogs, items
            ├── drawable/               # Icônes vector + adaptive icon fg/bg
            ├── mipmap-anydpi-v26/      # Adaptive icons (API 26+)
            ├── mipmap-*dpi/            # PNG fallbacks (API 24-25)
            ├── values/                 # strings, colors, themes, styles
            └── xml/backup_rules.xml
```

## Notes techniques

- **viewBinding** activé : accès aux vues par
  `binding.canvas`, `binding.btnPlay`, etc.
- **Coroutines** utilisées pour l'export GIF (encodage sur Dispatchers.Default).
- **Stockage** :
  - Projets : `filesDir/projects/<id>.json` (stockage interne, privé)
  - Exports : `Pictures/PixelHero/` (MediaStore sur API 29+,
    fallback `Environment.DIRECTORY_PICTURES` sur API 24-28)
- **Permissions** :
  - `READ_MEDIA_IMAGES` (API 33+) pour charger une image de référence
  - `READ_EXTERNAL_STORAGE` (API ≤32) idem

## Build APK

Dans Android Studio :
- **Build → Build Bundle(s) / APK(s) → Build APK(s)**
- L'APK signé debug est dans `app/build/outputs/apk/debug/app-debug.apk`

Pour un APK release signé (Play Store) :
- **Build → Generate Signed Bundle / APK** → suivre l'assistant.

## Fonctionnalités avancées (v2)

### Création de personnages
- **Mode symétrie** (bouton symétrie dans la barre du haut) :
  Aucune / Horizontale / Verticale / Les deux. Dessinez la moitié, l'autre se peint
  automatiquement. La ligne de symétrie est visible en bleu sur le canvas.
- **Pixel parfait** (checkbox dans le panneau droit) : supprime les pixels en
  coin pour des lignes diagonales propres, sans escalier en double épaisseur.
- **Outil sélection** (icône damier) + appui long pour : Copier / Couper /
  Coller / Miroir horizontal / Miroir vertical sur la zone sélectionnée.
- **Bibliothèque de palettes** : PICO-8, Game Boy DMG, Game Boy Pocket, NES,
  Sweetie 16, Endesga 32, Tons chair.
- **Auto-shading** : touche un bouton, l'app ajoute automatiquement 4 nuances
  (2 ombres, 2 highlights) de la couleur active à la palette — fini la galère
  pour trouver des ombres cohérentes.
- **Remplacement de couleur global** : changer toutes les occurrences d'une
  couleur sur toutes les frames (ou seulement la frame courante).
- **Couleur primaire/secondaire** + bouton **swap** (échanger les deux).

### Animation
- **Onion skin multi-frames** : frame précédente en **bleu**, suivante en
  **rouge**, jusqu'à ±3 frames (slider dans le panneau droit). Voir tout le
  mouvement d'un coup d'œil.
- **Générateur d'animation** (icône baguette magique dans la barre du haut) :
  partir de votre frame de base et **générer automatiquement les frames
  suivantes** pour :
  - **Marche** (4 ou 8 frames) — décalage automatique des jambes gauche/droite
  - **Idle / respiration** — léger mouvement haut/bas
  - **Attaque** — anticipation → impact → recovery
  - **Saut** — squash crouch → stretch jump → land
  - **Défense / tremblement** — petits décalages
  - **Rotation gauche/droite** — squash horizontal vers le miroir
  - **Flottement (bob)** — sinusoïde verticale fluide
  Le résultat n'est jamais parfait : c'est un brouillon que vous raffinez.
- **Étiquettes de frames** + **durée par frame** : appui long sur une frame
  dans la liste pour éditer son `tag` ("walk", "attack"…) et sa durée (en ms).
  Plusieurs animations dans un seul projet.
- **Flip / Décaler une frame** (bouton flip dans la barre d'outils gauche) :
  miroir H/V ou décaler la frame d'1 px dans n'importe quelle direction.

### Confort
- **Auto-save** toutes les 30 secondes + à chaque pause/fermeture de l'app.
- **Sauvegarde au format JSON** (avec base64 des pixels) — stockage interne
  privé, jamais perdu.

## Roadmap (idées d'extensions futures)

- Calques par frame
- Symétrie horizontale / verticale en temps réel pour les sprites de personnages
- Sélection rectangulaire + copier/coller/déplacer
- Import sprite sheet pour réimporter un projet exporté
- Mode "miroir" pour animer un personnage qui marche vers la gauche/droite
- Bibliothèque de palettes (NES, GameBoy, PICO-8…)

## Licence

Code libre — utilisez-le comme bon vous semble.
