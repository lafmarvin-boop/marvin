# Pixel Realm

Action-RPG en vue de dessus, en pixel art. Ouvre `index.html` dans un navigateur.

## Contenu
- Création du héros : corps, race (Humain, Elfe, Nain, Orc), peau, coiffure, couleurs, arme, 2 compétences.
- Monde ouvert en 4 directions. Régions : Prairie, Marais, Ruines. Puis le cycle recommence, plus dur.
- Ennemis : slimes, serpents, abeilles, chauves-souris, yeux, fantômes, gobelins, zombies, squelettes, loups-garous, orcs.
- Boss : Chef Orc, Roi Citrouille, Minotaure.
- Butin : or, potions, coffres. L'équipement change l'apparence (armure de plates, cape, casque, lance dorée).
- Camp : feu de camp (touche E) avec boutique et gestion des compétences.
- Sauvegarde automatique (localStorage).

## Touches
ZQSD / WASD / flèches : bouger · K, J, Espace : attaquer · 1 2 3 : compétences · R : potion · E : interagir.

## Graphismes
Liberated Pixel Cup (CC-BY-SA 3.0 / GPL 3.0 / OGA-BY 3.0). Crédits : `assets/CREDITS.md`.

Reconstruire les assets :
```
python3 tools/build_assets.py <clone Universal-LPC-Spritesheet-Character-Generator> "<LPC Base Assets>" assets
python3 tools/bundle_assets.py .
```
