# Marvin Sport — App Android

Application Android (Kotlin + Jetpack Compose). Deux modules principaux :

1. **Programmes** — 3 plannings d'entraînement (musculation + 2 axés combat)
2. **Course** — tracking GPS façon Strava avec carte réelle (OpenStreetMap)

## Navigation

Barre inférieure à deux onglets :
- **Programmes** (icône haltère)
- **Course** (icône coureur)

## Module Programmes

Sélecteur en haut de l'écran (chips) pour basculer entre :

### 1. Musculation — Marvin (12 semaines)
Programme original extrait du fichier Excel. **3 phases × 4 semaines × 3 séances**.
- Phase 1 — Technique
- Phase 2 — Volume
- Phase 3 — Force maximale

### 2. Striking — Boxe / MMA debout (16 semaines)
**4 phases × 4 semaines × 3 séances**, focus explosivité du puncheur.
- Phase 1 — PPG explosive
- Phase 2 — Force-vitesse
- Phase 3 — Puissance pliométrique
- Phase 4 — Pic compétition

Sessions hebdomadaires :
- S1 — Bas du corps explosif & sprint (box jump, squat dynamique, sprint navette)
- S2 — Push explosif & frappe (DVP balistique, lancer médecine-ball, sac)
- S3 — Core rotation & HIIT (deadlift vitesse, twists, burpees+sprawl)

### 3. Grappling — Lutte / BJJ / MMA sol (16 semaines)
**4 phases × 4 semaines × 3 séances**, focus grip + force-endurance + posture.
- Phase 1 — Grip & force de base
- Phase 2 — Force-endurance
- Phase 3 — Puissance combinée
- Phase 4 — Pic compétition

Sessions hebdomadaires :
- S1 — Tirage explosif & grip (power clean, tractions lestées, dead-hang)
- S2 — Chaîne postérieure & tronc isométrique (KB swing, GHR, planche)
- S3 — Wrestling-spécifique (front squat, push press, bear crawl, sprawl)

### Tableau d'une séance

Colonnes : **Exercice · Séries · Reps · Charge · Repos · Annotation**.
Les paires en superset sont précédées de `↳` et signalées par un fond contrasté.

### Progression automatique

**+1,5 kg toutes les 4 séances effectuées sur un même exercice**. Le compteur
est par exercice et partagé entre les 3 programmes (les charges progressent
en cohérence sur tous les programmes pour un même mouvement).

- Affichage de la charge ajustée dans le tableau
- Indicateur "palier dans N" pour visualiser le compteur

### Annotations

Champ libre par séance (ressenti, RPE, ajustements) sauvegardé en local.

## Module Course

- **Démarrer une course** → demande des permissions GPS et notifications
- **Foreground service** (`RunTrackingService`) : tracking GPS continu même
  écran éteint via `FusedLocationProviderClient`, notification persistante
- **Carte OSM** (osmdroid, MAPNIK) avec marqueur début/fin et polyline du tracé
- **Stats temps réel** : distance, durée, allure (min/km), vitesse (km/h)
- **Filtrage qualité GPS** : accuracy > 30 m écartée, sauts > 80 m / < 5 s ignorés
- **Historique** : liste des courses (mini-tracé Canvas), détail avec carte OSM
  pleine taille et fit-to-bounds, suppression possible
- Persistance JSON via DataStore

Tuiles servies par OpenStreetMap — user-agent défini sur le package de l'app.

## Build

```bash
cd android-app
./gradlew assembleDebug
```

Ouvrir le dossier dans Android Studio (Hedgehog+). Wrapper Gradle généré à
l'ouverture ou via `gradle wrapper`.

Stack : Kotlin 1.9.22 · AGP 8.2 · Compose BOM 2024.02 · Material 3 ·
Navigation Compose · DataStore · kotlinx.serialization · Play Services
Location · osmdroid 6.1.18.

## 1RM de référence (programme Musculation)

| Exercice              | 1RM (kg) |
| --------------------- | -------- |
| Back Squat            | 100      |
| Barbell Row           | 60       |
| DVP Incliné           | 70       |
| Deadlift              | 140      |
| Tirage poulie haute   | 70       |
| Tirage poulie basse   | 60       |
| Fente                 | 50       |
| DVP couché            | 70       |
| Écarté poulie         | 18       |

Les programmes combat se basent sur ces mêmes 1RM avec des coefficients
adaptés à la phase (PPG, force-vitesse, puissance, pic).
