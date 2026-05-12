# Marvin Sport — App Android

Application Android (Kotlin + Jetpack Compose) construite à partir du planning d'entraînement de Marvin (fichier `entrainement_marvin.xlsx`).

## Programme

- **3 phases** : Technique → Volume → Force max
- **4 semaines** par phase, **3 séances** par semaine
- **9 exercices principaux** + accessoires + cardio
- Charges calculées à partir des 1RM et du % associé aux répétitions
  (5 reps = 85%, 8 = 80%, 10 = 75%, 12 = 70%, 15 = 65%)

## Tableau d'une séance

Chaque séance s'affiche sous forme de tableau avec les colonnes :
**Exercice · Séries · Reps · Charge · Repos · Annotation**

Les paires en superset sont indiquées par `↳` et un fond contrasté.

## Progression automatique

Toutes les **3 séances complétées sur un même exercice**, la charge affichée
augmente de **+1,5 kg** (compteur stocké via DataStore). Une indication
*"+1.5 dans N"* est affichée sous la charge pour visualiser le prochain palier.

Marquer / démarquer une séance se fait via le bouton "Marquer la séance comme
terminée" en bas de l'écran séance. Les compteurs sont automatiquement
incrémentés / décrémentés.

## Annotations

Chaque séance possède un champ libre d'annotations (ressenti, RPE,
ajustements) sauvegardé localement.

## Build

Ouvrir le dossier `android-app/` dans Android Studio (Hedgehog ou +).
Gradle 8.5 / AGP 8.2 / Kotlin 1.9.22 / Compose BOM 2024.02.

```bash
cd android-app
./gradlew assembleDebug
```

> Le wrapper Gradle (`gradlew`, `gradlew.bat`, `gradle-wrapper.jar`) est généré
> par Android Studio à l'ouverture du projet, ou via `gradle wrapper`.

## Données de référence (1RM)

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
