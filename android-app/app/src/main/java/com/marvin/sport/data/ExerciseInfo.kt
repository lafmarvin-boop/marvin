package com.marvin.sport.data

data class ExerciseInfo(
    val name: String,
    val description: String,
    val searchQuery: String,
)

/**
 * Fiches techniques par exercice. Le bouton info ouvre une fiche avec la
 * description écrite + un lien vers une recherche YouTube pour visualiser
 * le mouvement (animation / vidéo de démonstration).
 *
 * Pour un exercice absent de la banque, un fallback générique est utilisé.
 */
object ExerciseInfoBank {

    private val entries: Map<String, ExerciseInfo> = listOf(
        // ---- Musculation ----
        ExerciseInfo(
            "Back Squat",
            "Barre sur les trapèzes hauts. Pieds écartés largeur épaules, pointes légèrement vers l'extérieur. Descendre en contrôle hanches en arrière, dos droit, genoux alignés sur les pieds, jusqu'à la cuisse parallèle au sol. Remonter en poussant le sol.",
            "back squat technique haltérophilie",
        ),
        ExerciseInfo(
            "Barbell Row",
            "Buste penché à ~45°, dos plat, gainage actif. Tirer la barre vers le bas du sternum / nombril en serrant les omoplates. Descente contrôlée. Pas de mouvement parasite du buste.",
            "barbell row pendlay technique",
        ),
        ExerciseInfo(
            "Barbell row",
            "Buste penché à ~45°, dos plat, gainage actif. Tirer la barre vers le bas du sternum / nombril en serrant les omoplates. Descente contrôlée.",
            "barbell row pendlay technique",
        ),
        ExerciseInfo(
            "DVP Incliné",
            "Banc incliné à 30-45°. Omoplates rétractées et collées au banc, pieds au sol. Descendre la barre vers le haut des pectoraux, remonter en poussant légèrement vers les pieds. Coudes ~70°.",
            "développé incliné barre technique",
        ),
        ExerciseInfo(
            "DVP couché",
            "Allongé, omoplates rétractées, voûte naturelle, pieds ancrés. Descendre la barre au niveau du sternum, coudes ~70°. Pousser en poussée verticale, pas de rebond.",
            "développé couché technique",
        ),
        ExerciseInfo(
            "Deadlift",
            "Barre près du tibia. Hanches plus hautes que les genoux. Dos plat, gainage. Pousser le sol avec les jambes en gardant la barre proche du corps, verrouillage hanches + genoux en haut. Pas d'hyperextension.",
            "soulevé de terre conventionnel technique",
        ),
        ExerciseInfo(
            "Tirage poulie haute",
            "Assis, cuisses bloquées. Saisie large pronation. Tirer la barre vers la poitrine en abaissant les omoplates. Coudes vers l'arrière, buste légèrement penché.",
            "tirage poulie haute lat pulldown",
        ),
        ExerciseInfo(
            "Tirage poulie basse",
            "Assis, jambes semi-fléchies, dos droit. Tirer vers le nombril en serrant les omoplates. Pas de balancement du buste.",
            "tirage horizontal poulie basse technique",
        ),
        ExerciseInfo(
            "Fente",
            "Grand pas en avant, genou avant à 90°, genou arrière proche du sol sans le toucher. Buste droit, gainage. Remonter en poussant sur le talon avant.",
            "fente avant haltères technique",
        ),
        ExerciseInfo(
            "Écarté poulie",
            "Debout entre les poulies, mains hautes. Bras semi-fléchis. Ramener les mains devant la poitrine en grand arc de cercle. Squeeze pectoral en fin de mouvement.",
            "écarté poulie pec technique",
        ),
        ExerciseInfo(
            "Frappe latérale",
            "Frappe explosive sur médecine-ball lourd (sol ou mur). Pivot du bassin et des hanches, rotation du tronc, terminer par les bras.",
            "med ball side slam exercise",
        ),
        ExerciseInfo(
            "Dragon flag",
            "Allongé sur banc, mains aux montants au-dessus de la tête. Soulever tout le corps en gainage, descendre lentement les jambes tendues jusqu'à parallèle au sol. Pas de flexion à la taille.",
            "dragon flag bruce lee technique",
        ),
        ExerciseInfo(
            "Abdos en rotation",
            "Allongé / russian twist au sol ou banc déclin. Pieds décollés, mains jointes ou lestées. Rotation contrôlée du tronc, le centre reste stable.",
            "russian twist rotation abdos",
        ),

        // ---- Striking ----
        ExerciseInfo(
            "Box jump",
            "Box en face. Bras armés en arrière, hanches en arrière. Sauter en projetant les bras vers l'avant et en triple-extension. Atterrir genoux fléchis, posture absorbante.",
            "box jump pliometrie technique",
        ),
        ExerciseInfo(
            "Back squat dynamique",
            "Charge ~70-75% du 1RM. Descente contrôlée, remontée le plus rapide possible. La vitesse de la barre est la priorité. Pas de rebond.",
            "speed squat dynamic effort",
        ),
        ExerciseInfo(
            "Fente sautée alternée",
            "Position de fente. Sauter en l'air, échanger les jambes en l'air, atterrir en fente sur l'autre jambe. Réception silencieuse, gainage actif.",
            "fente sautée jumping lunge",
        ),
        ExerciseInfo(
            "Sprint navette 20 m",
            "Sprinter 20 m, demi-tour, sprinter 20 m retour. Accélération maximale, freinage et changement de direction explosifs.",
            "sprint navette shuttle run",
        ),
        ExerciseInfo(
            "DVP couché balistique",
            "Développé couché avec intention explosive : la barre est projetée, pas seulement poussée. Charge modérée (~60-70%). Coudes ne se verrouillent pas trop fort en haut.",
            "ballistic bench press dynamic effort",
        ),
        ExerciseInfo(
            "Lancer médecine-ball poitrine",
            "Debout face au mur (~2 m). Médecine-ball à hauteur poitrine. Pousser fort le ball vers le mur en triple-extension, rattraper et enchaîner.",
            "med ball chest pass throw",
        ),
        ExerciseInfo(
            "Pompes claquées",
            "Pompe explosive : se propulser du sol assez haut pour claquer des mains avant la réception. Coudes ~45°, gainage actif.",
            "clap push up pliometrie",
        ),
        ExerciseInfo(
            "DVP incliné contrôlé",
            "Banc incliné 30-45°. Mouvement contrôlé, descente 2 s, montée 1 s. Travail technique.",
            "incline bench press technique",
        ),
        ExerciseInfo(
            "Frappe sac (combos 1-2-3-2)",
            "Sur sac de frappe : jab (1), cross (2), uppercut/crochet (3), cross (2). Rotation des hanches sur chaque coup, pieds ancrés, retour à la garde après chaque frappe.",
            "boxe combo jab cross hook combinaison",
        ),
        ExerciseInfo(
            "Triceps corde / Élévation latérale",
            "Triceps : extension à la corde, coudes fixes près du corps. Élévation latérale : haltères, montée latérale à la hauteur des épaules, coudes très légèrement fléchis.",
            "extension triceps corde élévation latérale",
        ),
        ExerciseInfo(
            "Soulevé de terre vitesse",
            "Deadlift à 60-70% avec intention explosive sur le concentrique. Setup identique au deadlift classique, tirée la plus rapide possible.",
            "speed deadlift dynamic effort",
        ),
        ExerciseInfo(
            "Lancer rotatif médecine-ball",
            "Face au mur. Médecine-ball près de la hanche. Pivot du bassin et des hanches, lancer le ball latéralement vers le mur. Réception et enchaîner.",
            "rotational med ball throw oblique",
        ),
        ExerciseInfo(
            "Russian twist lesté",
            "Assis, pieds décollés, buste penché en arrière. Médecine-ball ou disque tenu en main. Rotation contrôlée à gauche puis à droite. Le centre reste stable.",
            "russian twist abdos médecine ball",
        ),
        ExerciseInfo(
            "Burpees + sprawl",
            "Burpee classique + descente brutale en sprawl (façon lutte). Reprise rapide, saut en haut. Cadence soutenue.",
            "burpee sprawl conditionnement",
        ),
        ExerciseInfo(
            "Corde à sauter (rapide)",
            "Sauts pieds joints, cadence élevée. Garder les coudes près du corps, sauts bas, rotation des poignets.",
            "corde à sauter rapide boxe",
        ),

        // ---- Grappling ----
        ExerciseInfo(
            "Power clean",
            "Setup deadlift, première phase identique. À mi-cuisse, extension explosive (hanches, genoux, chevilles), tirage des coudes vers le haut puis rotation rapide pour rattraper la barre sur les épaules. Pieds légèrement réceptifs.",
            "power clean technique haltérophilie",
        ),
        ExerciseInfo(
            "Traction lestée explosive",
            "Tractions strictes avec ceinture lestée. Concentrique le plus rapide possible (tirer le menton au-dessus de la barre rapidement), descente contrôlée.",
            "weighted pull up explosive",
        ),
        ExerciseInfo(
            "Dead-hang lesté",
            "Suspendu à la barre, pouces engagés, gainage actif. Tenir le temps prescrit. Ajouter du lest avec une ceinture pour augmenter la difficulté.",
            "dead hang technique grip",
        ),
        ExerciseInfo(
            "Fermières (gi-grip simulé)",
            "Suspendu à la barre ou à une serviette épaisse pliée. Tenir avec la prise simulant le grip du gi. Travail isométrique.",
            "gi grip dead hang towel pull",
        ),
        ExerciseInfo(
            "Curl marteau",
            "Haltères, prise neutre (pouce vers le haut). Flexion du coude sans bouger le coude. Travail brachial + avant-bras.",
            "curl marteau haltères technique",
        ),
        ExerciseInfo(
            "Deadlift vitesse",
            "Deadlift conventionnel à ~65-70% du 1RM, concentrique le plus explosif possible. Setup et technique identiques au DL classique.",
            "speed deadlift dynamic effort",
        ),
        ExerciseInfo(
            "KB swing russe",
            "Kettlebell entre les jambes, hanches en arrière, dos plat. Propulsion explosive des hanches, kb monte jusqu'à hauteur poitrine (russe) ou au-dessus de la tête (américain).",
            "russian kettlebell swing technique",
        ),
        ExerciseInfo(
            "Glute-ham raise / Nordic curl",
            "Genoux fixés, descente contrôlée du buste vers l'avant en gardant le corps droit. Remontée par contraction des ischios. Variante : assistance partenaire / bandes élastiques.",
            "nordic curl glute ham raise",
        ),
        ExerciseInfo(
            "Planche bras tendus",
            "Position de pompe haute. Corps aligné de la tête aux chevilles, gainage abdominal, pas de cambrure. Tenir le temps prescrit.",
            "planche bras tendus high plank",
        ),
        ExerciseInfo(
            "Hollow body hold",
            "Allongé sur le dos. Décoller épaules, bras tendus derrière la tête, jambes tendues décollées du sol. Bas du dos reste plaqué. Maintenir.",
            "hollow body hold gymnastique",
        ),
        ExerciseInfo(
            "Wall sit lesté",
            "Dos plaqué au mur, cuisses parallèles au sol, genoux à 90°. Disque ou sac sur les cuisses. Tenir le temps prescrit.",
            "wall sit lesté chaise",
        ),
        ExerciseInfo(
            "Front squat puissance",
            "Barre en rack avant (épaules, coudes hauts). Descente verticale, dos droit, profondeur cuisses parallèles. Remontée explosive.",
            "front squat technique",
        ),
        ExerciseInfo(
            "Push press",
            "Barre en rack avant. Dip court des jambes (5-10 cm), extension explosive, la barre est propulsée au-dessus de la tête. Verrouillage coudes.",
            "push press épaulé jeté technique",
        ),
        ExerciseInfo(
            "Tirage poulie haute explosif",
            "Tirage à la poulie haute, concentrique très rapide (tirée explosive), excentrique lente. Pas d'élan du buste.",
            "explosive lat pulldown",
        ),
        ExerciseInfo(
            "Tirage poulie basse explosif",
            "Tirage horizontal poulie basse, concentrique explosif. Serrer les omoplates en fin de mouvement.",
            "explosive seated cable row",
        ),
        ExerciseInfo(
            "Bear crawl + sprawl",
            "Bear crawl : marche à 4 pattes, genoux à 5 cm du sol, dos plat. Sprawl : descente brutale en planche jambes projetées. Enchaîner.",
            "bear crawl sprawl conditionnement",
        ),
        ExerciseInfo(
            "Shadow grappling intense",
            "Travail technique en l'air : drops, sprawls, level changes, shots. Intensité élevée, cadence soutenue, technique propre.",
            "shadow wrestling grappling drill",
        ),

        // ---- Communs ----
        ExerciseInfo(
            "Biceps / Deltoïde / Triceps",
            "Circuit bras : curls (biceps) + élévations latérales / développé (deltoïde) + extensions (triceps). Charges modérées, focus connexion muscle.",
            "circuit biceps deltoide triceps",
        ),
        ExerciseInfo(
            "Cardio",
            "Effort intense en intervalle (vélo / rameur / course). Vitesse maximale 3 minutes, 1 minute de récupération entre les blocs.",
            "cardio intervalle hiit",
        ),
        ExerciseInfo(
            "Mollets / proprioception",
            "Extension mollets debout (avec poids) + travail d'équilibre unipodal. Contrôle de la cheville, posture stable.",
            "extension mollets proprioception cheville",
        ),
        ExerciseInfo(
            "Étirements actifs",
            "Routine d'étirements dynamiques : leg swings, hip openers, T-spine rotations, cat-cow. Maintenir 5 minutes après la séance.",
            "étirements actifs récupération",
        ),
    ).associateBy { it.name }

    fun lookup(name: String): ExerciseInfo {
        return entries[name] ?: ExerciseInfo(
            name = name,
            description = "Mouvement non documenté dans la fiche technique. Consulte une démonstration vidéo pour la technique correcte.",
            searchQuery = "$name technique exécution",
        )
    }
}
