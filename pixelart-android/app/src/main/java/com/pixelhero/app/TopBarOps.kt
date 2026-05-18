package com.pixelhero.app

import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * Top-level menus reachable from the hamburger (☰) and the inline top
 * palette that opens above the canvas for tool sub-menus (Décor, Effets,
 * Magie). Also the 3-tab switcher for the right panel.
 */

internal fun MainActivity.showMenu() {
    val items = arrayOf(
        "📂 Projet (nouveau / sauver / charger / backup)",
        "📤 Exporter (PNG, GIF, sprite sheet, Unity/Godot)",
        "📥 Importer un sprite sheet…",
        "📐 Redimensionner le canvas",
        "▶️ Mode de lecture animation…",
        "🎨 Palettes & couleurs",
        "🔧 Outils (texte, stabilisateur, onion, fond global)",
        "✨ Filtres / effets",
        "🗺️ Mode tuiles / carte",
        "📖 Tutoriel"
    )
    AlertDialog.Builder(this)
        .setTitle(R.string.menu)
        .setItems(items) { _, which ->
            when (which) {
                0 -> showProjectMenu()
                1 -> showExportMenu()
                2 -> importSpriteSheet()
                3 -> showResizeDialog()
                4 -> showPlayModeMenu()
                5 -> showColorAndPaletteMenu()
                6 -> showToolsMenu()
                7 -> showFiltersMenu()
                8 -> openTileMap()
                9 -> showTutorial(force = true)
            }
        }
        .show()
}

internal fun MainActivity.showProjectMenu() {
    val items = arrayOf(
        getString(R.string.new_project),
        getString(R.string.save),
        getString(R.string.load),
        "💾 Sauvegarde complète (.zip)",
        "📥 Restaurer depuis .zip"
    )
    AlertDialog.Builder(this).setTitle("📂 Projet")
        .setItems(items) { _, w ->
            when (w) {
                0 -> showNewProjectDialog()
                1 -> saveProject()
                2 -> showLoadDialog()
                3 -> exportBackupZip()
                4 -> pickAndImportBackup()
            }
        }.show()
}

internal fun MainActivity.showExportMenu() {
    val items = arrayOf(
        getString(R.string.export_png) + " (frame courante)",
        "Exporter chaque frame en PNG séparé",
        getString(R.string.export_sheet) + " (toutes frames en grille)",
        getString(R.string.export_gif) + " animé",
        "Exporter projet Unity/Godot (JSON + sprite sheet)",
        "Partager PNG actuel",
        "Partager GIF animé"
    )
    AlertDialog.Builder(this).setTitle("📤 Exporter")
        .setItems(items) { _, w ->
            when (w) {
                0 -> exportPng()
                1 -> exportAllFrames()
                2 -> exportSpriteSheet()
                3 -> exportGif()
                4 -> exportGameDevPackage()
                5 -> sharePng()
                6 -> shareGif()
            }
        }.show()
}

internal fun MainActivity.showColorAndPaletteMenu() {
    val items = arrayOf(
        "Verrouiller couleurs…",
        "Bibliothèque palettes étendue…"
    )
    AlertDialog.Builder(this).setTitle("🎨 Couleurs & palettes")
        .setItems(items) { _, w ->
            when (w) {
                0 -> showColorLockMenu()
                1 -> showExtendedPalettesDialog()
            }
        }.show()
}

internal fun MainActivity.showToolsMenu() {
    val palmOn = binding.canvas.palmRejection
    val items = arrayOf(
        "Ajouter du texte (5×7 pixel font)…",
        "Stabilisateur de trait…",
        "Personnaliser couleurs onion skin…",
        "Fond global (partagé entre toutes les frames)…",
        "✋ Rejet de la paume (stylet) : " + if (palmOn) "ON" else "OFF"
    )
    AlertDialog.Builder(this).setTitle("🔧 Outils")
        .setItems(items) { _, w ->
            when (w) {
                0 -> showTextDialog()
                1 -> showStabilizerDialog()
                2 -> showOnionColorPicker()
                3 -> showGlobalBackgroundDialog()
                4 -> togglePalmRejection()
            }
        }.show()
}

internal fun MainActivity.showTopPalette(title: String, entries: List<Pair<String, () -> Unit>>) {
    val row = binding.topPaletteRow
    row.removeAllViews()
    row.addView(TextView(this).apply {
        text = title
        setTextColor(0xFFA5B4FF.toInt())
        textSize = 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(12, 12, 16, 12)
    })
    for ((label, action) in entries) {
        row.addView(Button(this).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            setPadding(16, 8, 16, 8)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(4, 4, 4, 4) }
            layoutParams = lp
            setOnClickListener { closeTopPalette(); action() }
        })
    }
    row.addView(Button(this).apply {
        text = "✕"; textSize = 14f; isAllCaps = false
        setPadding(16, 8, 16, 8)
        setOnClickListener { closeTopPalette() }
    })
    binding.topPalette.visibility = View.VISIBLE
}

internal fun MainActivity.closeTopPalette() {
    binding.topPalette.visibility = View.GONE
    binding.topPaletteRow.removeAllViews()
}

internal fun MainActivity.openDecorPalette() {
    showTopPalette("🏞️ Décor", listOf(
        "Statique → frame" to { pickAndGenerateStaticDecor(replaceFrame = true) },
        "Statique → fond" to { pickAndGenerateStaticDecor(replaceFrame = false) },
        "🎬 Animé 4 frames" to { pickAndGenerateAnimatedDecor(frameCount = 4) },
        "🎬 Animé 8 frames" to { pickAndGenerateAnimatedDecor(frameCount = 8) }
    ))
}

internal fun MainActivity.openEffectsPalette() {
    showTopPalette("✨ Effets", listOf(
        "Particules" to { showParticlesDialog() },
        "Filtres image" to { showFiltersMenu() }
    ))
}

internal fun MainActivity.openMagicPalette() {
    showTopPalette("🪄 Générer", listOf(
        "🏞️ Décor" to { openDecorPalette() },
        "✨ Effets" to { openEffectsPalette() },
        "🔀 Tween 2 frames" to { showTweenDialog() }
    ))
}

/**
 * Show one tab section of the right panel and hide the others.
 *  0 = 🎨 Couleurs  1 = 🧱 Outils  2 = 🎬 Animation
 */
internal fun MainActivity.switchRightTab(idx: Int) {
    binding.groupColors.visibility = if (idx == 0) View.VISIBLE else View.GONE
    binding.groupBg.visibility     = if (idx == 1) View.VISIBLE else View.GONE
    binding.groupZoom.visibility   = if (idx == 2) View.VISIBLE else View.GONE
    binding.groupFrames.visibility = if (idx == 2) View.VISIBLE else View.GONE
    binding.tabColors.isSelected = idx == 0
    binding.tabLayers.isSelected = idx == 1
    binding.tabAnim.isSelected   = idx == 2
}
