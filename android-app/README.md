# CryptoBot — bot multi-stratégie Binance pour Android

Application Android Kotlin / Jetpack Compose qui gère plusieurs **portefeuilles
indépendants** (wallets), chacun pilotant sa propre stratégie sur Binance Spot :

- **DCA** (Dollar Cost Averaging) : achat régulier d'un montant fixe
- **Grid Trading** : achat/revente automatique sur des paliers de prix (en %)

Chaque wallet a son propre cash, ses propres holdings, son propre mode (paper
ou live). Tu peux **transférer du cash** d'un wallet à l'autre directement
dans l'app.

## ⚠️ Avertissements importants

**Lis ceci avant d'activer le mode LIVE.**

- Le trading crypto est **risqué**. Tu peux perdre une partie ou la totalité de
  tes fonds. Cette app est un outil pédagogique, pas un conseil financier.
- **Démarre toujours en mode PAPER** (simulation) pendant au moins quelques
  semaines pour vérifier que la stratégie correspond à ce que tu veux.
- Sur Binance, crée une clé API **uniquement avec la permission "Spot Trading"
  activée**. **N'active JAMAIS la permission "Withdrawals" (retraits)**. Si la
  clé est compromise, l'attaquant ne pourra que trader, pas vider ton compte.
- Restreins l'IP de la clé si tu peux (option Binance "IP access restriction").
- Garde toujours un **plafond cumulé** (`maxTotalSpend`) défini pour limiter
  l'exposition en cas de bug ou d'oubli.
- Le téléphone doit rester allumé et connecté pour que le bot s'exécute.
  WorkManager s'efforce de respecter l'intervalle, mais Android peut retarder
  l'exécution (Doze, économie batterie). Pour du vrai 24/7, utilise un VPS.

## Architecture

```
android-app/
├── app/src/main/java/com/marvin/cryptobot/
│   ├── CryptoBotApp.kt              Application + DI container
│   ├── MainActivity.kt              Navigation Compose
│   ├── data/
│   │   ├── AppContainer.kt          Conteneur de dépendances
│   │   ├── ConfigStore.kt           Persistance config (SharedPrefs)
│   │   ├── SecureKeyStore.kt        Clés API chiffrées (Android Keystore)
│   │   ├── db/                      Room database (TradeEntity/Dao)
│   │   └── remote/BinanceClient.kt  Client REST + signature HMAC-SHA256
│   ├── domain/
│   │   ├── model/                   BotConfig, TradingMode
│   │   └── strategy/DcaStrategy.kt  Logique DCA (paper + live)
│   ├── worker/DcaWorker.kt          WorkManager périodique
│   ├── viewmodel/MainViewModel.kt
│   └── ui/                          Écrans Compose (Dashboard, History, Settings)
└── README.md
```

## Compiler

### Prérequis

- **Android Studio** Hedgehog (2023.1) ou plus récent (Iguana / Koala recommandés)
- **JDK 17**
- Connexion Internet pour télécharger Gradle 8.7 et les dépendances

### Étapes

1. Ouvre `android-app/` dans Android Studio (`File → Open`).
2. Android Studio télécharge automatiquement Gradle, le SDK et les
   dépendances. Compte ~5 min la première fois.
3. Branche ton téléphone Android en USB (mode développeur + débogage USB
   activés) ou crée un émulateur (API ≥ 26).
4. `Run ▶` (`Shift+F10`). L'APK s'installe et l'app se lance.

### Build APK en ligne de commande

Depuis `android-app/`, après avoir généré le wrapper Gradle (Android Studio
le fait automatiquement à l'ouverture) :

```bash
./gradlew assembleDebug
# APK généré : app/build/outputs/apk/debug/app-debug.apk
```

## Premier lancement

1. **Onglet Réglages** : laisse `Mode = Paper`. Définis :
   - Symbole : `BTCEUR` (Bitcoin contre Euro) — vérifie que la paire existe sur
     Binance Spot
   - Montant par achat : ex. `10` (10 EUR par achat)
   - Intervalle : `24` heures
   - Plafond cumulé : ex. `500` (le bot s'arrête après 500 EUR dépensés)
2. **Tableau de bord** : active le switch "Bot actif". Le worker s'enregistre
   et exécute le premier achat dans les minutes qui suivent (Android applique
   un petit délai initial).
3. Tu peux aussi cliquer **"Acheter maintenant"** pour exécuter immédiatement.
4. **Onglet Historique** : vérifie que les trades simulés apparaissent.

## Passer en mode LIVE

⚠️ **À ne faire qu'après avoir vérifié la stratégie en paper trading.**

1. Crée une clé API Binance ici :
   <https://www.binance.com/en/my/settings/api-management>
   - Permissions : **Spot Trading** ✅, **Withdrawals** ❌
2. Dans l'app, onglet **Réglages → Clés API Binance** : colle la clé et le
   secret, puis **Enregistrer**. Les valeurs sont chiffrées par
   `EncryptedSharedPreferences` (clé maître protégée par Android Keystore).
3. Bascule **Mode = LIVE** (un dialogue de confirmation s'affiche).
4. Vérifie que le solde EUR sur Binance est suffisant pour au moins un achat.
5. Active le bot.

## Stratégie : DCA

DCA = Dollar Cost Averaging. À chaque tick :

1. Lit le prix actuel du symbole.
2. Achète **un montant fixe en quote-currency** (ex. 10 EUR de BTC), peu
   importe le prix.
3. Persiste le trade en base.
4. Si le plafond cumulé est atteint, l'achat est ignoré.

Avantages : simple, lisse le prix d'achat, ne demande aucune prédiction.
Limite : ne profite pas des baisses brutales (un bot "buy the dip" ferait
mieux dans ces moments-là, au prix de plus de complexité).

## Sécurité technique

- Clés API stockées via `EncryptedSharedPreferences` + `MasterKey`
  (`AES256_SIV` pour les noms, `AES256_GCM` pour les valeurs). La clé maître
  est dans Android Keystore, donc liée au matériel.
- Backup cloud désactivé pour le fichier de prefs (`data_extraction_rules.xml`).
- Toutes les requêtes Binance sont signées HMAC-SHA256 et passent en HTTPS
  uniquement (`usesCleartextTraffic="false"`).
- ProGuard configuré (release) ; n'oublie pas de tester un build release avant
  de distribuer.

## Limites connues

- WorkManager impose un intervalle minimum de 15 minutes pour les tâches
  périodiques. Pour du DCA quotidien c'est largement suffisant.
- Pas (encore) de stratégie autre que DCA achat-uniquement. Pas de
  take-profit ni de stop-loss. Si tu veux ces features, ouvre une issue.
- Le bot ne trade **qu'à l'achat** (DCA classique). Les reventes sont à faire
  manuellement sur Binance (ou ajouter une stratégie de sortie).
- Pas de tests unitaires inclus — à ajouter avant un usage sérieux.

## Évolutions possibles

- Stratégies : grid trading, RSI/MACD, take-profit / stop-loss
- Migration backend : déplacer le moteur sur un VPS (Python/Node) et garder
  l'app comme tableau de bord, pour un vrai 24/7
- Graphique des prix et de la performance du portefeuille
- Support multi-paires en parallèle
- Intégration Binance Testnet pour tester sans utiliser le mainnet
