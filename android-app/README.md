# Marvin Sport — App Android

Application Android (Kotlin + Jetpack Compose) avec bottom navigation à
**4 onglets** pour basculer en un tap :

| Onglet      | Contenu                                            |
| ----------- | -------------------------------------------------- |
| **Muscu**     | Programme Marvin original (12 sem · 3 phases)      |
| **Striking**  | Programme boxe / MMA debout (16 sem · 4 phases)    |
| **Grappling** | Programme lutte / BJJ / MMA sol (16 sem · 4 phases) |
| **Course**    | GPS façon Strava + Timer fractionné                |

## Programmes d'entraînement

### Tableau d'une séance

Colonnes : **▶ · Exercice · Séries · Reps · Charge · Repos · Annotation**.
Le bouton **▶** ouvre une fiche technique avec description du mouvement et
un lien vers une démonstration vidéo (recherche YouTube). Les paires en
superset sont précédées de `↳`.

### Progression automatique

**+1,5 kg toutes les 4 séances** effectuées sur un même exercice (compteur
par exercice, partagé entre programmes). La colonne Charge affiche le
palier en cours et "palier dans N" pour visualiser le compteur.

### Annotations

Champ libre par séance (ressenti, RPE, ajustements) sauvegardé en local.

### Détail des programmes combat

**Striking — Boxe / MMA debout**
- Phase 1 PPG explosive · 2 Force-vitesse · 3 Puissance pliométrique · 4 Pic
- S1 Bas du corps explosif (box jump, squat dynamique, sprint navette)
- S2 Push explosif & frappe (DVP balistique, lancer médecine-ball, sac)
- S3 Core rotation & HIIT (deadlift vitesse, twists, burpees+sprawl)

**Grappling — Lutte / BJJ / MMA sol**
- Phase 1 Grip & force base · 2 Force-endurance · 3 Puissance combinée · 4 Pic
- S1 Tirage explosif & grip (power clean, traction lestée, dead-hang)
- S2 Chaîne postérieure & tronc isométrique (KB swing, GHR, planche)
- S3 Wrestling-spécifique (front squat, push press, bear crawl, sprawl)

## Module Course

L'onglet Course propose deux sous-onglets :

### GPS

- Tracking GPS continu via foreground service (`FusedLocationProvider`),
  fonctionne écran éteint
- Carte **OpenStreetMap** (osmdroid) avec polyline et marqueurs début/fin
- Stats temps réel : distance, durée, allure (min/km), vitesse (km/h)
- Historique avec aperçu mini et carte détaillée pleine taille
- Persistance JSON via DataStore

### Timer fractionné

Configurable de A à Z :
- **Préparation** (0-60 s, par tranches de 5)
- **Travail** (5-600 s)
- **Repos** (0-600 s)
- **Rounds par set** (1-50)
- **Nombre de sets** (1-20)
- **Repos entre sets** (si plusieurs sets, 0-600 s)

Affichage durée totale estimée. En cours, l'écran passe en plein écran
coloré (bleu prép · vert travail · orange repos · violet repos long) avec
chrono géant, compteur set/round, vibration aux transitions et boutons
pause / arrêt.

Config persistée en DataStore : on retrouve son setup au prochain
lancement.

## Build

```bash
cd android-app
./gradlew assembleDebug
```

Stack : Kotlin 1.9.22 · AGP 8.2 · Compose BOM 2024.02 · Material 3 ·
Navigation Compose · DataStore · kotlinx.serialization · Play Services
Location · osmdroid 6.1.18.

## 1RM de référence (Muscu)

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
