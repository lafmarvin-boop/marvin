# PixelHero — Éditeur de pixel art & animation

Application web optimisée pour Android (smartphone & tablette) permettant de
créer des héros / personnages en pixel art et d'animer leurs mouvements via un
système de frames.

## Fonctionnalités

- **Création de projet** : dimensions personnalisables (1×1 à 512×512) ou
  préréglages 16, 24, 32, 48, 64, 128.
- **Outils de dessin** : crayon, gomme, pot de peinture (flood fill), pipette,
  ligne, rectangle plein/vide, déplacement de la vue.
- **Palette de couleurs** : 24 couleurs par défaut + sélecteur couleur natif +
  16 couleurs récentes mémorisées.
- **Image de référence** (fond) : importez une photo ou un dessin, dessinez
  par-dessus, opacité réglable.
- **Frames d'animation** : ajout, duplication, suppression, réorganisation.
- **Onion skinning** : voir la frame précédente en transparence pour faciliter
  les transitions.
- **Aperçu animation** : lecture en boucle avec FPS réglable (1–60).
- **Export** : PNG (frame courante, ×8), sprite sheet, GIF animé.
- **Sauvegarde locale** : projets stockés dans `localStorage` du navigateur.
- **Undo/Redo** illimité (jusqu'à 80 niveaux).
- **Tactile** : pince à 2 doigts pour zoomer/déplacer la vue.
- **Hors ligne** (PWA, service worker) — installable comme une appli native.

## Lancer en local

```bash
cd pixelart-app
python3 -m http.server 8080
# Puis ouvrir http://localhost:8080 dans Chrome / Edge / Firefox
```

## Installer sur Android (smartphone ou tablette)

1. Héberger l'application sur un serveur **HTTPS** (par ex. GitHub Pages,
   Netlify, Vercel, ou votre serveur). La PWA nécessite HTTPS pour fonctionner
   hors ligne.
2. Ouvrir l'URL dans **Chrome Android** ou **Edge Android**.
3. Menu navigateur → **« Ajouter à l'écran d'accueil »** ou **« Installer
   l'application »**.
4. L'icône apparaît sur l'écran d'accueil et l'app s'ouvre en plein écran sans
   barres de navigation, comme une appli native.

### Générer un APK (optionnel)

Pour publier sur le Play Store, vous pouvez emballer la PWA en TWA (Trusted Web
Activity) avec **Bubblewrap** :

```bash
npm i -g @bubblewrap/cli
bubblewrap init --manifest=https://votre-domaine.com/pixelart-app/manifest.webmanifest
bubblewrap build
```

## Raccourcis clavier (utile avec un clavier Bluetooth)

| Touche | Action |
|---|---|
| P | Crayon |
| E | Gomme |
| B | Pot de peinture |
| I | Pipette |
| L | Ligne |
| R | Rectangle |
| M / Espace | Déplacer la vue |
| Ctrl+Z | Annuler |
| Ctrl+Shift+Z / Ctrl+Y | Refaire |
| Ctrl+S | Sauvegarder |

## Astuces tactiles

- **1 doigt** : dessiner avec l'outil sélectionné
- **2 doigts (pinch)** : zoom et déplacement simultané de la vue
- **Outil « Déplacer »** : pour glisser la vue avec un seul doigt
- Roulette souris : zoom

## Structure des fichiers

```
pixelart-app/
├── index.html            # Page principale
├── manifest.webmanifest  # Manifeste PWA
├── service-worker.js     # Cache hors ligne
├── css/styles.css        # Styles + responsive
├── js/
│   ├── util.js           # Helpers
│   ├── state.js          # État global (frames, couleurs...)
│   ├── storage.js        # localStorage save/load
│   ├── canvas.js         # Rendu canvas + entrées tactiles
│   ├── tools.js          # Outils de dessin
│   ├── palette.js        # Palette UI
│   ├── frames.js         # Liste frames + vignettes
│   ├── animation.js      # Lecture animation
│   ├── exporter.js       # Export PNG / sprite sheet / GIF
│   ├── ui.js             # Wiring UI (boutons, modales)
│   └── app.js            # Point d'entrée
├── lib/gif.js            # Encodeur GIF animé (auto-suffisant)
├── icons/                # Icônes PWA 192/512
└── tools/gen-icons.js    # Régénère les icônes
```

## Licence

Code libre — utilisez-le comme bon vous semble.
