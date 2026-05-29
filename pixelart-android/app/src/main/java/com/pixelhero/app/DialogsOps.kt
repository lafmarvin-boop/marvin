package com.pixelhero.app

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * Small dialog handlers: color lock, palette library, extended palettes,
 * stabilizer, onion-skin color pickers, text inserter, play-mode selector,
 * per-frame edit (tag + delay), and the multi-page tutorial.
 *
 * The big complex dialogs (showReplaceColorDialog with its inner adapter
 * ColorPickerAdapter, showNewProjectDialog / showResizeDialog,
 * showLoadDialog with ProjectListAdapter) intentionally stay in
 * MainActivity — they declare inner classes that can't move cleanly.
 */

internal fun MainActivity.showColorLockMenu() {
    val palette = project.palette + project.recentColors
    val labels = palette.map {
        val locked = it in project.lockedColors
        (if (locked) "🔒 " else "  ") + String.format("#%06X", it and 0xFFFFFF)
    }.toTypedArray()
    val checked = palette.map { it in project.lockedColors }.toBooleanArray()
    AlertDialog.Builder(this)
        .setTitle("Verrouiller couleurs (impossible à repeindre)")
        .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
            val color = palette[which]
            if (isChecked) project.lockedColors.add(color) else project.lockedColors.remove(color)
        }
        .setPositiveButton("OK") { _, _ -> refreshStatusBadges() }
        .setNeutralButton("Tout déverrouiller") { _, _ ->
            project.lockedColors.clear()
            toast("Toutes les couleurs déverrouillées")
            refreshStatusBadges()
        }
        .show()
}

internal fun MainActivity.showPaletteLibrary() {
    val presets = PaletteLibrary.ALL
    val labels = presets.map { it.name }.toTypedArray()
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.palette_library))
        .setItems(labels) { _, which ->
            val preset = presets[which]
            project.palette.clear()
            project.palette.addAll(preset.colors)
            paletteAdapter.notifyDataSetChanged()
            toast("Palette « ${preset.name} » appliquée")
        }
        .show()
}

internal fun MainActivity.showExtendedPalettesDialog() {
    val groups = ExtendedPalettes.GROUPS
    val groupLabels = groups.keys.toTypedArray()
    AlertDialog.Builder(this)
        .setTitle("Catégorie de palette")
        .setItems(groupLabels) { _, gIdx ->
            val palettes = groups[groupLabels[gIdx]] ?: return@setItems
            val pLabels = palettes.map { it.name }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(groupLabels[gIdx])
                .setItems(pLabels) { _, pIdx ->
                    val palette = palettes[pIdx]
                    project.palette.clear()
                    project.palette.addAll(palette.colors)
                    paletteAdapter.notifyDataSetChanged()
                    toast("Palette « ${palette.name} » appliquée")
                }
                .show()
        }
        .show()
}

internal fun MainActivity.showStabilizerDialog() {
    val items = arrayOf("Désactivé", "Léger (1)", "Moyen (2)", "Fort (3)", "Très fort (5)")
    val values = intArrayOf(0, 1, 2, 3, 5)
    val currentIdx = values.indexOf(binding.canvas.stabilizerStrength).coerceAtLeast(0)
    AlertDialog.Builder(this)
        .setTitle("Stabilisateur de trait")
        .setSingleChoiceItems(items, currentIdx) { dlg, which ->
            binding.canvas.stabilizerStrength = values[which]
            toast("Stabilisateur: ${items[which]}")
            refreshStatusBadges()
            dlg.dismiss()
        }
        .show()
}

internal fun MainActivity.showOnionColorPicker() {
    val trailLabel = if (project.onionTrailOnly) "Mode traînée: ON (passé seulement)" else "Mode traînée: OFF (passé+futur)"
    val items = arrayOf(
        "Couleur frame précédente (bleu)",
        "Couleur frame suivante (rouge)",
        trailLabel
    )
    AlertDialog.Builder(this)
        .setTitle("Onion skin")
        .setItems(items) { _, which ->
            when (which) {
                0 -> pickOnionColor(true)
                1 -> pickOnionColor(false)
                2 -> {
                    project.onionTrailOnly = !project.onionTrailOnly
                    toast(if (project.onionTrailOnly) "Mode traînée activé" else "Mode traînée désactivé")
                    binding.canvas.syncOnionBitmap()
                }
            }
        }
        .show()
}

private fun MainActivity.pickOnionColor(isPrev: Boolean) {
    val current = if (isPrev) project.onionColorPrev else project.onionColorNext
    val view = layoutInflater.inflate(R.layout.dialog_color_picker, null)
    val preview = view.findViewById<View>(R.id.preview)
    val seekR = view.findViewById<SeekBar>(R.id.seekR)
    val seekG = view.findViewById<SeekBar>(R.id.seekG)
    val seekB = view.findViewById<SeekBar>(R.id.seekB)
    seekR.progress = Color.red(current); seekG.progress = Color.green(current); seekB.progress = Color.blue(current)
    preview.setBackgroundColor(current)
    val update = { preview.setBackgroundColor(Color.rgb(seekR.progress, seekG.progress, seekB.progress) or 0xFF000000.toInt()) }
    listOf(seekR, seekG, seekB).forEach { it.setOnSeekBarChangeListener(simpleSeekListener { _ -> update() }) }
    AlertDialog.Builder(this)
        .setTitle(if (isPrev) "Couleur frame précédente" else "Couleur frame suivante")
        .setView(view)
        .setPositiveButton("OK") { _, _ ->
            val col = Color.rgb(seekR.progress, seekG.progress, seekB.progress) or 0xFF000000.toInt()
            if (isPrev) project.onionColorPrev = col else project.onionColorNext = col
            binding.canvas.invalidate()
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

internal fun MainActivity.showTextDialog() {
    val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 24) }
    container.addView(TextView(this).apply {
        text = "Texte à dessiner (police 5×7 pixels). Touchez ensuite le canvas pour positionner."
        setTextColor(0xFFE8E8F0.toInt()); textSize = 13f
    })
    val etText = EditText(this).apply {
        hint = "EX: HP, SCORE, LEVEL 1"
        setText("HELLO")
        setTextColor(0xFFE8E8F0.toInt())
    }
    container.addView(etText)
    AlertDialog.Builder(this)
        .setTitle("Ajouter du texte")
        .setView(container)
        .setPositiveButton("Positionner") { _, _ ->
            val text = etText.text.toString()
            if (text.isEmpty()) return@setPositiveButton
            toast("Touchez le canvas pour placer « $text »")
            binding.canvas.nextTapHandler = { x, y ->
                pushUndo()
                PixelFont.render(project.currentFrame, x, y, text, project.primaryColor)
                binding.canvas.syncFrameBitmap()
                framesAdapter.notifyItemChanged(project.currentIndex)
            }
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

internal fun MainActivity.showPlayModeMenu() {
    val modes = PlayMode.values()
    val labels = modes.map { it.label }.toTypedArray()
    AlertDialog.Builder(this)
        .setTitle("Mode de lecture")
        .setSingleChoiceItems(labels, project.playMode.ordinal) { dlg, which ->
            project.playMode = modes[which]
            dlg.dismiss()
            toast("Mode: ${modes[which].label}")
        }
        .show()
}

internal fun MainActivity.showFrameEditDialog(idx: Int) {
    val f = project.frames.getOrNull(idx) ?: return
    val view = layoutInflater.inflate(R.layout.dialog_frame_edit, null)
    val tagEt = view.findViewById<EditText>(R.id.frameTag)
    val delayEt = view.findViewById<EditText>(R.id.frameDelay)
    tagEt.setText(f.tag)
    delayEt.setText(f.delayMs.toString())

    // Add a horizontal row of Copy / Cut / Paste-after frame actions so the
    // user doesn't have to dig through other menus to reorganize frames.
    val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    outer.addView(view)
    val ops = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(24, 8, 24, 8)
    }
    val dlgIconSize = (resources.displayMetrics.density * 40).toInt()
    val btnCopy = android.widget.ImageButton(this).apply {
        setImageResource(R.drawable.ic_copy)
        contentDescription = "Copier la frame"
        layoutParams = LinearLayout.LayoutParams(dlgIconSize, dlgIconSize).apply {
            rightMargin = 8
        }
        setPadding(6, 6, 6, 6)
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        setOnClickListener {
            frameClipboard = f.copy()
            toast("Frame #${idx + 1} copiée")
        }
    }
    val btnCut = android.widget.ImageButton(this).apply {
        setImageResource(R.drawable.ic_cut)
        contentDescription = "Couper la frame"
        layoutParams = LinearLayout.LayoutParams(dlgIconSize, dlgIconSize).apply {
            rightMargin = 8
        }
        setPadding(6, 6, 6, 6)
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        setOnClickListener {
            if (project.frames.size <= 1) { toast("Au moins 1 frame requise"); return@setOnClickListener }
            pushUndo()
            frameClipboard = f.copy()
            project.frames.removeAt(idx)
            if (project.currentIndex >= project.frames.size) project.currentIndex = project.frames.size - 1
            framesAdapter.notifyDataSetChanged()
            refreshAfterFrameChange()
            toast("Frame coupée")
        }
    }
    val btnPaste = android.widget.Button(this).apply {
        text = "après"; textSize = 14f; isAllCaps = false
        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_pin, 0, 0, 0)
        compoundDrawablePadding = 6
        setOnClickListener {
            val src = frameClipboard ?: run { toast("Aucune frame copiée"); return@setOnClickListener }
            if (src.width != project.width || src.height != project.height) {
                toast("Taille incompatible — copie depuis un projet ${src.width}×${src.height}")
                return@setOnClickListener
            }
            pushUndo()
            project.frames.add(idx + 1, src.copy())
            project.currentIndex = idx + 1
            framesAdapter.notifyDataSetChanged()
            refreshAfterFrameChange()
            toast("Collée après frame #${idx + 1}")
        }
    }
    ops.addView(btnCopy); ops.addView(btnCut); ops.addView(btnPaste)
    outer.addView(ops)

    // Frame role: key / inbetween / hold / none — shown as colored top bar
    // in the timeline so the animator can see structure at a glance.
    outer.addView(TextView(this).apply {
        text = "Type de frame"
        setTextColor(0xFFA5B4FF.toInt()); textSize = 12f
        setPadding(24, 8, 24, 4)
    })
    val kinds = FrameKind.values()
    val kindLabels = arrayOf("(aucun)", "🟡 KEY (pose)", "⚪ INBETWEEN", "⬛ HOLD")
    val kindRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(24, 0, 24, 8)
    }
    kinds.forEachIndexed { i, kind ->
        kindRow.addView(android.widget.Button(this).apply {
            text = kindLabels[i].take(2)
            textSize = 14f; isAllCaps = false
            isSelected = (f.kind == kind)
            setOnClickListener {
                f.kind = kind
                framesAdapter.notifyItemChanged(idx)
                binding.timeline.invalidate()
                toast(kindLabels[i])
            }
        })
    }
    outer.addView(kindRow)

    AlertDialog.Builder(this)
        .setTitle("Frame #${idx + 1}")
        .setView(outer)
        .setPositiveButton("OK") { _, _ ->
            f.tag = tagEt.text.toString()
            f.delayMs = delayEt.text.toString().toIntOrNull()?.coerceIn(0, 10_000) ?: 0
            framesAdapter.notifyItemChanged(idx)
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

/**
 * Persistent reference card opened via the ? button in the top bar.
 * Lists every gesture, hidden feature, and shortcut so users don't have
 * to re-discover them. Scrollable single dialog.
 */
/**
 * Reference-layer manager: pick an image to overlay ABOVE the current frame
 * at adjustable opacity (rotoscoping), or clear the existing one. The
 * image picker uses the existing system file chooser via a one-shot
 * activity result registered separately.
 */
internal fun MainActivity.showReferenceLayerDialog() {
    val current = binding.canvas.referenceBitmap
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 24)
    }
    container.addView(TextView(this).apply {
        text = "Charge une image qui s'affiche en transparence AU-DESSUS de ton dessin. " +
            "Utile pour décalquer un modèle (photo, vidéo capturée, croquis) frame par frame."
        setTextColor(0xFFE8E8F0.toInt()); textSize = 13f
        setPadding(0, 0, 0, 12)
    })
    container.addView(TextView(this).apply {
        text = "Opacité : ${(binding.canvas.referenceOpacity * 100).toInt()}%"
        setTextColor(0xFFA5B4FF.toInt()); textSize = 13f
    })
    val opacitySeek = SeekBar(this).apply {
        max = 100
        progress = (binding.canvas.referenceOpacity * 100).toInt()
    }
    opacitySeek.setOnSeekBarChangeListener(simpleSeekListener { v ->
        binding.canvas.referenceOpacity = v / 100f
    })
    container.addView(opacitySeek)

    val builder = AlertDialog.Builder(this)
        .setTitle("🖼️ Calque référence")
        .setView(container)
        .setNegativeButton(R.string.cancel, null)
    if (current == null) {
        builder.setPositiveButton("Charger image…") { _, _ -> pickReferenceImage() }
    } else {
        builder
            .setPositiveButton("Remplacer…") { _, _ -> pickReferenceImage() }
            .setNeutralButton("Retirer") { _, _ ->
                binding.canvas.referenceBitmap = null
                toast("Référence retirée")
                refreshStatusBadges()
            }
    }
    builder.show()
}

/**
 * Quick-recents popup: long-press any palette swatch opens a small floating
 * horizontal row of the 8 most-recent colors right at the touch position.
 * Tap one to instantly switch the active color — no menu / dialog detour.
 */
internal fun MainActivity.showQuickRecentsPopup(anchor: View) {
    val recents = project.recentColors.take(8)
    if (recents.isEmpty()) { toast("Aucune couleur récente"); return }
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(8, 8, 8, 8)
        setBackgroundColor(0xFF1A1A22.toInt())
    }
    val swatchSize = (resources.displayMetrics.density * 32).toInt()
    val popup = android.widget.PopupWindow(
        row,
        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        true
    )
    recents.forEach { c ->
        val tile = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(swatchSize, swatchSize).apply {
                setMargins(4, 0, 4, 0)
            }
            setBackgroundColor(c)
            setOnClickListener { setColor(c); popup.dismiss() }
        }
        row.addView(tile)
    }
    popup.showAsDropDown(anchor, 0, -swatchSize * 2 - 16)
}

internal fun MainActivity.showCheatsheet() {
    val text = """
        ✋ GESTES SUR LE CANVAS
        • 2 doigts = zoom + pan
        • 2 doigts swipe horizontal = frame suivante / précédente
        • Double-tap = adapter (zoom fit)
        • Triple-tap = zoom 100%
        • Stylet immobile 0,4 s = pan tant que le stylet est posé
        • Paume rejetée automatiquement quand le stylet est actif

        ✏️ OUTILS (barre gauche, long-press = aide détaillée)
        • Crayon, Gomme : symétrie + pression stylet
        • Pot : remplit zone connectée
        • Pot inverse : rend transparente une zone connectée
        • Pipette : prend la couleur sous le doigt
        • Ligne / Rectangle plein ou vide / Cercle
        • Sélection rectangle : trace, puis glisse pour déplacer
        • Lasso : trace contour main levée, n'importe quelle forme
        • Baguette : sélection par couleur

        🪢 PALETTE SÉLECTION (apparaît en bas dès qu'une sélection existe)
        • ▭ 🪢 🪄 : change de mode de sélection
        • ➕ ➖ : ajoute / retire des pixels au pinceau
        • 📋 ✂ : copier / couper
        • 📌 : coller à la même position
        • 📌→ : coller dans un AUTRE calque (au choix ou nouveau)
        • ↔ ↕ : miroir horizontal / vertical
        • ✓ : valider et désélectionner

        🧱 CALQUES (onglet 🧱 du panneau droit)
        • Bande latérale : 👁/🚫 pour masquer, ▲/▼ pour réorganiser
        • Tap nom = activer ce calque
        • Long-tap nom = mettre dans un groupe
        • Groupes (Vue face / Vue dos / Corps / Arme…) persistent entre frames
        • Œil du groupe = bascule TOUS les calques du groupe

        🎬 ANIMATION (onglet 🎬 du panneau droit)
        • Bouton ▶ canvas = lecture taille réelle, modes LOOP/PING-PONG/REVERSE/ONCE
        • Curseur vitesse 0,25× → 4× (multiplie tous les délais)
        • ⏱ Délai par frame = zones lentes/rapides (acceleration)
        • 🔀 Tween entre 2 frames = N frames intermédiaires avec courbes d'easing
        • Onion skin = frames précédentes (bleu) + suivantes (rouge)

        🎨 COULEURS (onglet 🎨 du panneau droit)
        • Palette projet + Récentes (auto)
        • Auto-shading = 4 nuances depuis la couleur active
        • Bibliothèque palettes (NES, GameBoy, PICO-8, …)
        • Verrou couleur, Remplacer couleur (global ou frame seule)

        🏞️ DÉCOR & ✨ EFFETS (boutons barre du haut)
        • Décor procédural : statique frame/fond ou animé 4/8 frames
        • 29 filtres dont feu, glace, électrique, arc-en-ciel
        • S'appliquent à TOUS les calques, undo unique annule tout

        🖼️ IMPORT IMAGE
        • Menu → 🔧 Outils n'importe quoi : choisis intensité suppression de fond
        • 🎯 Pixeliser à résolution choisie : 16/24/32/48/64/96/128/192
        • Style ⭐ Pro avec downscale par moyenne d'aire

        💾 SAUVEGARDE
        • Auto-save 30 s dès qu'une modif a lieu
        • Première sauvegarde nomme le projet
        • Disquette 💾 dans barre du haut = save sans dialogue
        • Menu → Sauvegarde .zip = backup complet hors app

        📤 EXPORT
        • PNG frame seule (×8), PNG séquence, Sprite sheet, GIF animé
        • Unity/Godot package = JSON + atlas
        • Tous dans Pictures/PixelHero/
    """.trimIndent()
    val scroll = android.widget.ScrollView(this)
    scroll.addView(TextView(this).apply {
        this.text = text
        setTextColor(0xFFE8E8F0.toInt())
        textSize = 13f
        setPadding(48, 24, 48, 24)
        setLineSpacing(0f, 1.15f)
    })
    AlertDialog.Builder(this)
        .setTitle("❔ Raccourcis & gestes")
        .setView(scroll)
        .setPositiveButton("Fermer", null)
        .setNeutralButton("Revoir le tutoriel") { _, _ -> showTutorial(force = true) }
        .show()
}

internal fun MainActivity.showTutorial(force: Boolean = false) {
    val seenKey = "tutorialSeen"
    val prefs = getPreferences(Context.MODE_PRIVATE)
    if (!force && prefs.getBoolean(seenKey, false)) return
    val pages = listOf(
        "Bienvenue dans PixelHero ! 👋\n\nApp pixel-art frame-by-frame, optimisée tablette + stylet. Par défaut canvas 64×64, max 600×600. Menu (☰) → Nouveau projet pour choisir une taille.",
        "✏️ Outils (barre gauche)\n\nCrayon, gomme, pot de peinture, pot inverse (efface zone), pipette, ligne, rectangle, sélection rectangle, lasso main levée, baguette magique, déplacer.",
        "✋ Gestes canvas\n• 2 doigts = pan + zoom\n• 2 doigts swipe horizontal = frame suivante/précédente\n• Double-tap = adapter (zoom fit)\n• Triple-tap = zoom 100%\n• Stylet appui long 0,4 s = pan",
        "🪢 Sélection avancée\nRectangle, lasso main levée, ou baguette magique. Quand une sélection est active, une palette apparaît en bas du canvas : déplacer, copier, couper, coller (dans un autre calque !), ajouter/retirer des pixels au pinceau, miroir, valider.\n\nLe contour 'marching ants' noir/blanc reste visible sur tout fond.",
        "🧱 Calques (onglet 🧱 + onglet 🎬 du panneau droit)\nChaque frame a ses calques. Bande latérale : 👁/🚫 pour masquer, ▲/▼ pour réorganiser, tap sur nom = actif, long-tap = mettre dans un groupe.\n\nGroupes (Vue face / Vue dos / Corps / Arme…) persistent entre toutes les frames.",
        "🎬 Animation\nOnglet 🎬 du panneau droit : timeline en bas, FPS, curseur de vitesse 0,25×–4×, ⏱ délai par frame (zones lente/rapide), bouton ▶ pour lecture taille canvas.\n\n🔀 Tween : crée des frames intermédiaires entre 2 frames clés avec courbes d'easing.",
        "🎨 Couleurs (onglet 🎨)\nPalette projet + Récentes. Auto-shading génère 4 nuances. Bibliothèque palettes étendue (NES, GameBoy, PICO-8…). Verrou couleur, Remplacer couleur.",
        "🏞️ Décor & ✨ Effets (boutons barre du haut)\nDécor procédural en image de fond ou en animation 4/8 frames (ciel, eau, neige, forêt…). 29 effets / filtres dont feu, glace, électrique, arc-en-ciel — appliqués à tous les calques.",
        "🖼️ Charger image\nMenu → Outils → Charger. Choisis l'intensité de suppression du fond, puis 🎯 Pixeliser à une résolution choisie (32 / 48 / 64…). Style ⭐ Pro avec downscale par moyenne d'aire = rendu propre.",
        "💾 Sauvegarde\nAuto-save toutes les 30 s dès que tu modifies quelque chose. Première sauvegarde → nommer. Disquette dans la barre du haut apparaît = sauve sans dialogue. Menu → Sauvegarde .zip = backup complet.",
        "📤 Export\nPNG frame seule (×8), chaque frame séparée, sprite sheet en grille, GIF animé, package Unity/Godot prêt à intégrer.\n\nTous dans Pictures/PixelHero/."
    )
    showTutorialPage(pages, 0, seenKey)
}

private fun MainActivity.showTutorialPage(pages: List<String>, idx: Int, seenKey: String) {
    if (idx >= pages.size) {
        getPreferences(Context.MODE_PRIVATE).edit().putBoolean(seenKey, true).apply()
        return
    }
    AlertDialog.Builder(this)
        .setTitle("Tutoriel (${idx + 1}/${pages.size})")
        .setMessage(pages[idx])
        .setPositiveButton(if (idx == pages.size - 1) "Compris" else "Suivant") { _, _ ->
            showTutorialPage(pages, idx + 1, seenKey)
        }
        .setNeutralButton("Passer") { _, _ ->
            getPreferences(Context.MODE_PRIVATE).edit().putBoolean(seenKey, true).apply()
        }
        .show()
}
