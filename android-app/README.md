# Marvin Sport — App Android

Application Android (Kotlin + Jetpack Compose) construite à partir du planning
d'entraînement de Marvin (`entrainement_marvin.xlsx`). Deux modules :

1. **Musculation** — programme complet 12 semaines sous forme de tableaux
2. **Course à pied** — tracking GPS façon Strava (running uniquement)

## Navigation

Barre de navigation inférieure à deux onglets :
- **Musculation** (icône haltère)
- **Course** (icône coureur)

## Module Musculation

### Programme

- **3 phases** : Technique → Volume → Force max
- **4 semaines** par phase, **3 séances** par semaine
- Charges calculées via les 1RM et le coefficient associé aux répétitions
  (5 reps = 85%, 8 = 80%, 10 = 75%, 12 = 70%, 15 = 65%)

### Tableau d'une séance

Colonnes : **Exercice · Séries · Reps · Charge · Repos · Annotation**.
Les paires en superset sont précédées de `↳` et signalées par un fond contrasté.

### Progression automatique

**+1,5 kg à la fin de chaque phase de 4 semaines** :

| Phase | Loads affichées |
| ----- | --------------- |
| 1     | base            |
| 2     | base + 1.5 kg   |
| 3     | base + 3.0 kg   |

Un compteur de "cycles" permet de répéter le programme avec un nouveau palier
de +1,5 kg à chaque cycle complet.

### Annotations

Chaque séance possède un champ libre (ressenti, RPE, ajustements) sauvegardé
localement via DataStore.

## Module Course à pied

- **Démarrer une course** depuis l'onglet → demande automatique des permissions
  GPS et notification → un service en avant-plan capture les positions toutes
  les ~1.5 s même écran éteint
- **Suivi en temps réel** : distance, durée, allure (min/km), vitesse (km/h),
  tracé visualisé sur un Canvas
- **Enregistrer** ou **Abandonner** à la fin de la séance
- **Historique** : liste des courses sauvegardées avec aperçu du tracé,
  détail accessible par clic, suppression possible

### Filtrage GPS

- Points avec accuracy > 30 m écartés
- Sauts > 80 m en moins de 5 s ignorés (anti-jitter)

### Permissions

Demandées à l'exécution la première fois :
- `ACCESS_FINE_LOCATION` (obligatoire)
- `POST_NOTIFICATIONS` (Android 13+)

## Build

```bash
cd android-app
./gradlew assembleDebug
```

> Ouvrir le dossier dans Android Studio (Hedgehog+). Le wrapper Gradle est
> généré à l'ouverture ou via `gradle wrapper`.

Stack : Kotlin 1.9.22 · AGP 8.2 · Compose BOM 2024.02 · Material 3 ·
Navigation Compose · DataStore · kotlinx.serialization · Play Services Location.

## 1RM de référence

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
