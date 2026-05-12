# Marvin Budget — Android

Application Android **lecture seule** d'agrégation de comptes bancaires :
toutes les transactions, tous tes comptes (personnels et communs), un graphique
entrées / dépenses, et une répartition par catégorie.

> **Aucune transaction n'est jamais initiée par l'application.**
> La couche réseau n'expose que les endpoints **lecture** de l'API Open Banking.

## Stack

- **Kotlin + Jetpack Compose** (Material 3, dynamic color)
- Architecture **MVVM** (`ViewModel` + `StateFlow`)
- **Room** pour le cache local (Account, Transaction)
- **Retrofit + kotlinx-serialization** pour la couche réseau
- **Navigation Compose** (3 onglets : Vue d'ensemble, Comptes, Transactions)
- Graphique entrées/dépenses dessiné en **Canvas Compose** (aucune dépendance graphique externe)

## Pourquoi pas d'accès aux apps bancaires installées ?

Sous Android, **aucune app ne peut lire les données d'une autre app bancaire** :
chaque application est sandboxée et ses données chiffrées par le système. Les
permissions susceptibles d'y donner accès (SMS, accessibilité, notifications)
sont soit refusées par Google Play, soit légalement interdites en Europe pour
des données financières.

La seule voie **légale, fiable et conforme DSP2** est l'**Open Banking** : un
agrégateur AISP certifié se connecte aux banques via leur API officielle, avec
le consentement explicite du titulaire du compte. L'app n'a accès qu'à la
lecture (soldes + transactions) — c'est exactement ce que tu demandais.

Cette app utilise **GoCardless Bank Account Data** (anciennement *Nordigen*),
gratuit pour un usage personnel, et qui couvre toutes les banques européennes
(BNP, Crédit Agricole, Société Générale, Boursorama, Revolut, N26, etc.).

## Mise en route

### 1. Ouvrir le projet

```bash
cd android-app
# Ouvrir le dossier dans Android Studio (Iguana ou plus récent)
```

Android Studio téléchargera Gradle 8.10.2 et les dépendances automatiquement.

> **Gradle wrapper :** ce dépôt ne contient pas le binaire `gradle-wrapper.jar`
> (fichier binaire non versionnable proprement). À la première ouverture du
> projet dans Android Studio, ce fichier sera généré automatiquement. En CLI :
> `gradle wrapper --gradle-version 8.10.2`.

### 2. Lancer l'app

`Run > app` (émulateur ou appareil API 26+). Au premier démarrage l'app charge
un **jeu de données fictif** (5 comptes, ~100 transactions sur 6 mois) pour
illustrer toutes les vues :

| Onglet | Contenu |
|---|---|
| **Vue d'ensemble** | Patrimoine consolidé · entrées/dépenses du mois · **graphe colonnes** 6 mois · répartition par catégorie |
| **Comptes** | Section *Comptes personnels* + section *Comptes communs* · solde · IBAN · indication "carte différée" |
| **Transactions** | Liste complète, ou liste filtrée d'un compte (cliquer une carte) · badge "Différé" pour les opérations non effectives |

### 3. Brancher tes vraies banques (GoCardless)

1. Crée un compte gratuit sur <https://bankaccountdata.gocardless.com>
2. Récupère `SECRET_ID` + `SECRET_KEY` dans *User secrets*
3. Stocke-les dans `local.properties` (jamais commité) :
   ```properties
   GOCARDLESS_SECRET_ID=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
   GOCARDLESS_SECRET_KEY=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
   ```
4. Implémente le flux d'auth dans `BudgetRepository.syncFromProvider()`
   (à ajouter) en utilisant `GoCardlessApi` déjà câblé :
   - `POST token/new` → access token
   - `GET institutions?country=FR` → choix de la banque
   - `POST requisitions` → lien de consentement à ouvrir dans un Custom Tab
   - après le retour utilisateur : `GET requisitions/{id}` puis `GET accounts/{id}/transactions`

Le contrat API est défini dans
[`data/remote/GoCardlessApi.kt`](app/src/main/kotlin/com/marvin/budget/data/remote/GoCardlessApi.kt).

## Architecture

```
com.marvin.budget
├── BudgetApp.kt          Application + DI (AppContainer)
├── MainActivity.kt       Compose host
├── di/
│   └── AppContainer.kt   DI manuel (Room, Retrofit, Repository)
├── data/
│   ├── model/            Account, Transaction, TxCategory, AccountKind
│   ├── local/            Room database, DAOs, type converters
│   ├── remote/           GoCardlessApi + DTOs (read-only)
│   ├── mock/             Données fictives (5 comptes, 6 mois)
│   └── repository/       BudgetRepository (flux Kotlin, agrégations)
└── ui/
    ├── theme/            Material 3 (light/dark + dynamic color)
    ├── format/           Format.money, Format.shortDate, ...
    ├── navigation/       NavHost + bottom bar
    ├── overview/         Vue d'ensemble + graphe Canvas
    ├── accounts/         Liste des comptes (perso / commun)
    └── transactions/     Liste des transactions (toutes ou par compte)
```

## Sécurité & confidentialité

- Permissions : `INTERNET` + `ACCESS_NETWORK_STATE` (rien d'autre).
- Backup auto-désactivé sur la base SQLite (`backup_rules.xml`).
- Aucun endpoint d'écriture / paiement n'est appelé.
- Les secrets GoCardless doivent vivre dans `local.properties` (gitignored).
- Pour un usage production : stocker l'access token dans `EncryptedSharedPreferences`.

## Tests

- `androidx.compose.ui.test` est câblé dans `app/build.gradle.kts`.
- Aucun test écrit pour l'instant — squelette uniquement.
