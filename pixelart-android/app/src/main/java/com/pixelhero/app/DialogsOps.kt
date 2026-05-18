package com.pixelhero.app

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
        .setPositiveButton("OK", null)
        .setNeutralButton("Tout déverrouiller") { _, _ ->
            project.lockedColors.clear()
            toast("Toutes les couleurs déverrouillées")
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
    AlertDialog.Builder(this)
        .setTitle("Frame #${idx + 1}")
        .setView(view)
        .setPositiveButton("OK") { _, _ ->
            f.tag = tagEt.text.toString()
            f.delayMs = delayEt.text.toString().toIntOrNull()?.coerceIn(0, 10_000) ?: 0
            framesAdapter.notifyItemChanged(idx)
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

internal fun MainActivity.showTutorial(force: Boolean = false) {
    val seenKey = "tutorialSeen"
    val prefs = getPreferences(MODE_PRIVATE)
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
        getPreferences(MODE_PRIVATE).edit().putBoolean(seenKey, true).apply()
        return
    }
    AlertDialog.Builder(this)
        .setTitle("Tutoriel (${idx + 1}/${pages.size})")
        .setMessage(pages[idx])
        .setPositiveButton(if (idx == pages.size - 1) "Compris" else "Suivant") { _, _ ->
            showTutorialPage(pages, idx + 1, seenKey)
        }
        .setNeutralButton("Passer") { _, _ ->
            getPreferences(MODE_PRIVATE).edit().putBoolean(seenKey, true).apply()
        }
        .show()
}
