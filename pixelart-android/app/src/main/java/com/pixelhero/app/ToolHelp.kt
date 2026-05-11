package com.pixelhero.app

/**
 * Centralised help texts for every tool and button. Each entry has:
 *   - title (with optional shortcut)
 *   - description: what the tool does
 *   - usage: step-by-step
 *   - example: a concrete simple scenario
 *
 * Used by long-press listeners across the UI to display an info dialog.
 */
object ToolHelp {

    data class Entry(
        val title: String,
        val description: String,
        val usage: String,
        val example: String
    )

    private val entries = mutableMapOf<String, Entry>()

    init {
        // ===== Outils de dessin =====
        entries["pencil"] = Entry(
            "✏️ Crayon (P)",
            "Dessine pixel par pixel avec la couleur active. La taille de la brosse, le tramage et la symétrie s'appliquent automatiquement.",
            "• Touchez et glissez sur le canvas pour tracer\n• Soulevez le doigt pour terminer le trait\n• Activez « Pixel parfait » pour des diagonales sans escalier",
            "Pour dessiner une silhouette : taille = 1 px, couleur noire, tracez le contour du personnage en un trait continu."
        )
        entries["eraser"] = Entry(
            "🧽 Gomme (E)",
            "Efface les pixels (les rend transparents). Respecte la taille de brosse et la symétrie.",
            "• Touchez et glissez pour effacer\n• Une zone effacée laisse voir la couleur de fond ou les calques en dessous",
            "Pour corriger un pixel mal placé : taille = 1 px, touchez le pixel en trop."
        )
        entries["fill"] = Entry(
            "🪣 Pot de peinture (B)",
            "Remplit une zone connectée de pixels de la même couleur avec la couleur active. Algorithme de flood-fill (4 voisins).",
            "• Touchez une zone\n• Tous les pixels touchant celui-ci (et de même couleur) deviennent la couleur active\n• Ne franchit PAS un contour de couleur différente",
            "Vous avez dessiné un cercle vide en noir : touchez l'intérieur avec le pot bleu pour le remplir d'un coup."
        )
        entries["picker"] = Entry(
            "💧 Pipette (I)",
            "Récupère la couleur du pixel touché et la met comme couleur active.",
            "• Touchez n'importe quel pixel coloré\n• Sa couleur devient votre couleur principale\n• Pratique pour réutiliser une teinte déjà posée",
            "Vous voulez ajouter du détail dans la même nuance de peau : pipette sur la peau, puis dessinez."
        )
        entries["line"] = Entry(
            "📏 Ligne (L)",
            "Trace une ligne droite entre 2 points avec l'algorithme de Bresenham (pixel-perfect).",
            "• Touchez le point de départ\n• Glissez jusqu'au point d'arrivée\n• Relâchez : la ligne est dessinée",
            "Pour dessiner un horizon parfait : glissez du bord gauche au bord droit à la même hauteur."
        )
        entries["rect"] = Entry(
            "⬜ Rectangle vide (R)",
            "Dessine le contour d'un rectangle (vide à l'intérieur).",
            "• Touchez un coin\n• Glissez jusqu'au coin opposé\n• Relâchez pour fixer",
            "Pour cadrer une fenêtre : glissez en diagonale, vous obtenez un cadre."
        )
        entries["rectfill"] = Entry(
            "⬛ Rectangle plein",
            "Dessine un rectangle entièrement rempli avec la couleur active.",
            "• Touchez un coin et glissez jusqu'à l'opposé\n• Idéal pour des aplats de couleur",
            "Pour faire un sol uniforme : glissez en bas de votre scène."
        )
        entries["select"] = Entry(
            "▭ Sélection rectangulaire",
            "Sélectionne une zone rectangulaire de pixels. La sélection devient flottante : vous pouvez la déplacer, copier, couper, mirroirer, coller.",
            "• Glissez pour créer un rectangle\n• Les pixels sont 'soulevés' (flottants)\n• Faites un appui long sur l'outil pour ouvrir le menu d'actions\n• Tapez ailleurs pour déposer la sélection",
            "Vous voulez bouger une jambe : sélection autour, glissez pour la déplacer, tapez ailleurs pour valider."
        )
        entries["wand"] = Entry(
            "🪄 Baguette magique (sélection par couleur)",
            "Sélectionne tous les pixels connectés de la MÊME couleur. Le résultat est une sélection flottante.",
            "• Touchez un pixel\n• Tous les pixels adjacents de cette couleur sont sélectionnés\n• Appui long pour menu copier/coller/miroir",
            "Pour changer la couleur d'un t-shirt rouge : baguette sur le rouge, puis menu → Coller (avec autre couleur préchargée)."
        )
        entries["move"] = Entry(
            "✋ Déplacer la vue (M / Espace)",
            "Déplace la vue (caméra) sans dessiner. Utile pour naviguer sur un grand canvas.",
            "• Glissez à un doigt pour bouger la vue\n• Combiné avec le zoom : ergonomie naturelle\n• 2 doigts (pinch) marche depuis n'importe quel outil",
            "Vous avez zoomé fortement et perdu votre personnage de vue : sélectionnez Déplacer et glissez pour le retrouver."
        )

        // ===== Outils annexes =====
        entries["clear"] = Entry(
            "🗑️ Effacer la frame",
            "Efface tous les pixels de la frame courante (la rend complètement transparente). Undo disponible.",
            "• Touchez le bouton\n• Confirmez dans la pop-up\n• Tous les pixels du calque actif sont supprimés",
            "Pour recommencer une frame ratée : Effacer → puis redessiner depuis zéro."
        )
        entries["grid"] = Entry(
            "▦ Afficher / masquer la grille",
            "Active ou désactive la grille pixel (visible seulement à partir d'un certain zoom).",
            "• Touchez pour basculer\n• La grille majeure (tous les 8 px) est plus visible que la grille mineure (chaque pixel)\n• Désactivée automatiquement à petit zoom",
            "Pour mieux voir les pixels en zoom 400 % : grille activée."
        )
        entries["flip"] = Entry(
            "🔄 Transformer la frame",
            "Applique une transformation à la frame entière : miroir horizontal/vertical ou décalage de 1 px.",
            "• Touchez le bouton\n• Choisissez la transformation\n• Toute la frame est modifiée\n• Undo disponible",
            "Vous avez dessiné un perso qui marche à droite : Miroir horizontal pour le faire marcher à gauche."
        )

        // ===== Barre du haut =====
        entries["menu"] = Entry(
            "☰ Menu",
            "Accès aux fonctions principales : nouveau projet, sauvegarder, charger, exporter, etc.",
            "• Touchez pour ouvrir\n• Faites défiler la liste\n• Sélectionnez l'action",
            "Pour exporter votre animation en GIF : Menu → Exporter GIF."
        )
        entries["undo"] = Entry(
            "↶ Annuler (Ctrl+Z)",
            "Annule la dernière action de dessin. Le nombre d'annulations possibles dépend de la taille du canvas (8 à 80).",
            "• Touchez pour revenir en arrière d'une action\n• Touchez plusieurs fois pour reculer plus\n• Ctrl+Z avec un clavier Bluetooth",
            "Vous avez accidentellement gribouillé : Annuler 2-3 fois pour récupérer."
        )
        entries["redo"] = Entry(
            "↷ Refaire (Ctrl+Y / Ctrl+Shift+Z)",
            "Refait une action annulée. Reset si vous dessinez après un Annuler.",
            "• Touchez pour ré-appliquer\n• Inverse de Annuler",
            "Annuler trop loin : Refaire pour récupérer."
        )
        entries["play"] = Entry(
            "▶ Lecture animation",
            "Joue l'animation en boucle dans le canvas principal. Touchez à nouveau (■) pour arrêter.",
            "• Touchez ▶ pour lancer\n• L'icône devient ■\n• Touchez ■ pour arrêter et revenir à la frame courante\n• Le mode de lecture (boucle/ping-pong/inverse) se règle dans le menu",
            "Pour voir votre cycle de marche : ▶ → ajustez le FPS si nécessaire (8 FPS typique)."
        )
        entries["symmetry"] = Entry(
            "✥ Mode symétrie",
            "Active la symétrie : ce que vous dessinez d'un côté apparaît automatiquement de l'autre.",
            "• Touchez pour ouvrir le menu\n• Choisir : aucune / horizontale / verticale / les deux\n• Une ligne bleue marque l'axe de symétrie\n• Tous les outils de dessin respectent la symétrie",
            "Pour dessiner un personnage de face : symétrie horizontale → dessinez la moitié gauche, la droite se fait toute seule."
        )
        entries["magic"] = Entry(
            "🪄 Générateur intelligent",
            "Ouvre les 4 générateurs : frames d'animation, décor/scène, élément animé, template de pose.",
            "• Touchez pour choisir un générateur\n• Animation : génère N frames suivantes à partir de votre frame\n• Décor : ciel, forêt, donjon… statique ou animé\n• Élément animé : flambeau, feu de camp, lanterne…\n• Pose : silhouette guide humanoïde, dragon…",
            "Pour ajouter un feu de camp animé : 🪄 → Élément animé → Feu de camp → Centre."
        )

        // ===== Panneau droit =====
        entries["pickColor"] = Entry(
            "🎨 Sélecteur de couleur",
            "Ouvre un mini-éditeur RGB pour choisir n'importe quelle couleur.",
            "• Touchez le bouton …\n• Ajustez Rouge, Vert, Bleu avec les sliders\n• Ou tapez un code hex (#RRGGBB)\n• OK valide",
            "Pour un rose pastel : R=255 G=200 B=200, OK."
        )
        entries["swap"] = Entry(
            "🔄 Échanger les couleurs",
            "Échange la couleur primaire (dessin) avec la couleur secondaire (utilisée pour les tramages mixtes).",
            "• Touchez pour basculer\n• Utile pour alterner rapidement entre 2 couleurs en dessinant",
            "Vous dessinez en alternant peau et yeux : swap rapide entre les 2 teintes."
        )
        entries["autoShade"] = Entry(
            "✨ Auto-ombrage",
            "Ajoute 4 nuances de la couleur active à la palette : 2 ombres + 2 highlights.",
            "• Sélectionnez votre couleur de base\n• Touchez Auto-shading\n• 4 nouvelles couleurs apparaissent dans la palette",
            "Vous dessinez une tunique rouge : Auto-shading vous donne automatiquement le rouge sombre (ombre) et le rouge clair (highlight)."
        )
        entries["paletteLib"] = Entry(
            "📚 Bibliothèque de palettes",
            "Charge une palette pré-faite : PICO-8, Game Boy, NES, Sweetie 16, Endesga 32, tons chair.",
            "• Touchez pour ouvrir la liste\n• Sélectionnez une palette\n• Remplace la palette actuelle (couleurs récentes préservées)",
            "Pour un jeu rétro Game Boy : Bibliothèque → Game Boy DMG (4 verts seulement)."
        )
        entries["replaceColor"] = Entry(
            "🎨 Remplacer une couleur",
            "Change toutes les occurrences d'une couleur par votre couleur active (frame courante OU toutes les frames).",
            "• Sélectionnez votre nouvelle couleur d'abord\n• Touchez Remplacer\n• Choisissez la couleur à remplacer dans la liste\n• Choisissez la portée (frame seule / toutes)",
            "Tester un perso en bleu au lieu de rouge : couleur active = bleu, Remplacer → rouge → Toutes les frames."
        )
        entries["bgLoad"] = Entry(
            "🖼️ Charger image de référence",
            "Importe une photo ou un dessin comme image de référence (affichée en dessous du calque actif).",
            "• Touchez pour ouvrir le sélecteur de fichiers\n• Choisissez une image\n• Le dialog vous propose : garder comme fond / pixéliser vers la frame / extraire la palette",
            "Pour redessiner un personnage en pixel art : charger sa photo → opacité du fond à 50 % → tracer par-dessus."
        )
        entries["bgClear"] = Entry(
            "❌ Retirer l'image de fond",
            "Enlève l'image de référence du canvas.",
            "• Touchez le bouton",
            "Vous avez fini de tracer par-dessus la photo : Retirer."
        )
        entries["bgFit"] = Entry(
            "🔲 Mode d'adaptation du fond",
            "Comment l'image de référence s'adapte à la taille du canvas. Cycle entre 3 modes.",
            "• Touchez pour faire défiler : Adapter (bandes vides) / Remplir (rogne les bords) / Étirer (déforme)\n• « Remplir » est le défaut (mieux pour tracer)",
            "Photo paysage 16:9 sur canvas carré : Remplir = utilise le centre. Adapter = bandes noires en haut/bas."
        )
        entries["dither"] = Entry(
            "▦ Tramage",
            "Pattern qui alterne pixels remplis / vides pour simuler des demi-tons (technique classique du pixel art).",
            "• Touchez pour ouvrir le menu\n• Choisissez : aucun / damier / lignes / sparse / mix 2 couleurs / personnalisé 4×4\n• Le pattern s'applique à TOUS les outils de dessin",
            "Pour ombrer une zone à 50 % : sélectionnez damier, dessinez : 1 pixel sur 2 est posé."
        )
        entries["pixelPerfect"] = Entry(
            "✓ Pixel parfait",
            "Élimine les pixels en coin (escalier en L) sur les lignes diagonales tracées au crayon.",
            "• Cochez la case\n• Tracez une diagonale lente : les pixels en coin sont automatiquement effacés\n• Résultat : ligne propre 1 pixel d'épaisseur",
            "Pour un contour de personnage net : cocher Pixel parfait, tracer le contour. Sans la case, les diagonales font 2 px d'épaisseur."
        )
        entries["brushSize"] = Entry(
            "🖌️ Taille de la brosse",
            "Largeur du carré peint par chaque toucher : 1, 2, 3, 4, 5, 6, 8, 10, 12 ou 16 pixels.",
            "• Glissez le slider\n• Plus grand = plus rapide pour remplir, moins précis\n• La symétrie s'applique aussi à la brosse",
            "Pour un aplat de fond : taille 16 px → glissez en zigzag, c'est rempli en 3 secondes."
        )
        entries["sketch"] = Entry(
            "📝 Mode croquis",
            "Tous vos tracés vont sur un CALQUE FANTÔME semi-transparent (50 % opacité), séparé de la frame. Utile pour planifier avant de finaliser.",
            "• Cochez Mode croquis\n• Dessinez normalement\n• Vos traits sont en surimpression à 50 %\n• Bouton « Valider » : intègre le croquis à la frame\n• Bouton « ✕ » : efface le croquis sans toucher la frame",
            "Esquisser la pose d'un perso : croquis au crayon rouge, puis Valider, puis dessiner par-dessus en couleurs finales."
        )

        // ===== Frames =====
        entries["frameAdd"] = Entry(
            "+ Ajouter frame",
            "Ajoute une nouvelle frame vide à la fin de la séquence d'animation.",
            "• Touchez le bouton\n• Une frame transparente est créée\n• Elle devient la frame active",
            "Pour démarrer une animation : dessinez frame 1 → + → dessinez frame 2."
        )
        entries["frameDup"] = Entry(
            "⎘ Dupliquer frame",
            "Crée une copie exacte de la frame courante, insérée juste après.",
            "• Touchez le bouton\n• La copie devient la frame active\n• Modifiez légèrement pour créer une étape d'animation",
            "Workflow classique d'anim : Dup → bouger d'un pixel → Dup → bouger encore."
        )
        entries["frameDel"] = Entry(
            "🗑 Supprimer frame",
            "Supprime la frame courante. Il faut au moins 1 frame.",
            "• Touchez le bouton\n• Confirmez dans la pop-up\n• Undo disponible",
            "Vous avez 5 frames mais seulement 4 sont bonnes : sélectionnez la mauvaise → Supprimer."
        )
        entries["fps"] = Entry(
            "⏱️ Images par seconde (FPS)",
            "Vitesse de l'animation globale : 1 à 60 images par seconde.",
            "• Tapez la valeur souhaitée\n• Validez en sortant du champ\n• Une frame avec un délai spécifique (appui long sur la frame) override le FPS global",
            "Marche fluide : 12 FPS. Idle lent : 4 FPS. Combat rapide : 24 FPS."
        )
        entries["previewToggle"] = Entry(
            "▶/■ Aperçu animation auto",
            "Active/désactive le mini aperçu qui boucle automatiquement à côté de la liste des frames.",
            "• Touchez pour basculer Play/Stop\n• Ne nécessite pas d'arrêter le travail principal\n• Respecte le mode de lecture du projet",
            "Vous travaillez une frame de marche : l'aperçu tourne en permanence pour voir le rendu immédiat."
        )
        entries["layers"] = Entry(
            "📚 Calques",
            "Une frame peut être composée de plusieurs couches empilées (chacune avec sa visibilité et opacité).",
            "• Touchez Calques\n• Tap sur un calque pour l'activer (le suivant que vous dessinez)\n• + Ajouter pour créer\n• Actions : masquer, renommer, opacité, supprimer, déplacer, fusionner",
            "Séparer outline et couleurs : Calque 1 = silhouette noire. Calque 2 = aplats de couleur. Bouger l'un sans l'autre."
        )

        // ===== Zoom =====
        entries["zoomOut"] = Entry(
            "− Zoom arrière",
            "Réduit le zoom de 33 %.",
            "• Touchez pour dézoomer\n• Pinch à 2 doigts plus rapide",
            "Pour voir l'ensemble : touchez 3-4 fois."
        )
        entries["zoomIn"] = Entry(
            "+ Zoom avant",
            "Augmente le zoom de 50 %.",
            "• Touchez pour zoomer\n• Pinch à 2 doigts plus rapide",
            "Pour détailler un œil : touchez plusieurs fois."
        )
        entries["zoomFit"] = Entry(
            "⛶ Adapter le zoom",
            "Recentre et ajuste le zoom pour voir tout le canvas.",
            "• Touchez pour réinitialiser la vue",
            "Vous vous êtes perdu en zoomant : touchez Adapter, vous revenez à la vue d'ensemble."
        )
        entries["zoom100"] = Entry(
            "1× Zoom 100 %",
            "Zoom à 1 pixel art = 1 pixel écran (sans agrandissement). Vue minuscule pour un petit canvas.",
            "• Touchez le bouton\n• Utile pour voir le rendu final tel qu'il sera dans un jeu",
            "Voir votre sprite de 16×16 à sa taille réelle dans un jeu."
        )
        entries["zoom400"] = Entry(
            "4× Zoom 400 %",
            "Zoom à 4 pixels écran par pixel art.",
            "• Touchez pour passer en zoom 4×\n• Confortable pour dessiner sur tablette",
            "Niveau de zoom recommandé pour dessiner sur Samsung Tab : 4×."
        )
    }

    fun get(key: String): Entry? = entries[key]

    /** Combine fields into a single readable message (for AlertDialog.setMessage). */
    fun format(entry: Entry): String =
        "${entry.description}\n\n" +
        "▸ Comment l'utiliser :\n${entry.usage}\n\n" +
        "▸ Exemple :\n${entry.example}"
}
