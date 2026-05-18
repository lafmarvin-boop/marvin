package com.pixelhero.app

import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * Layers UI & operations extracted from MainActivity. Touches:
 *  - the inline layers strip in the right panel
 *  - the Calques dialog and per-layer actions
 *  - group assignment, rename, opacity, reorder, merge
 *
 * All functions are extension functions on MainActivity so they can reach
 * project / binding / framesAdapter / pushUndo / toast (all internal).
 */

internal fun MainActivity.showLayersDialog() {
    val f = project.currentFrame
    val scroll = ScrollView(this)
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(24, 24, 24, 24)
    }
    scroll.addView(container)
    fun rebuild() {
        container.removeAllViews()
        f.layers.forEachIndexed { i, l ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(8, 12, 8, 12)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val eye = TextView(this).apply {
                text = if (l.visible) "👁" else "🚫"
                textSize = 22f
                setPadding(16, 8, 24, 8)
                isClickable = true; isFocusable = true
                setOnClickListener {
                    l.visible = !l.visible
                    f.invalidateComposite()
                    text = if (l.visible) "👁" else "🚫"
                    binding.canvas.syncFrameBitmap()
                    framesAdapter.notifyItemChanged(project.currentIndex)
                }
            }
            row.addView(eye)
            val activeDot = if (i == f.activeLayer) "● " else "  "
            val label = TextView(this).apply {
                text = "$activeDot${l.name}   (op ${(l.opacity * 100).toInt()}%)"
                setTextColor(0xFFE8E8F0.toInt())
                textSize = 16f
                setPadding(16, 8, 16, 8)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                isClickable = true; isFocusable = true
                setOnClickListener {
                    f.activeLayer = i
                    rebuild()
                }
            }
            row.addView(label)
            container.addView(row)
        }
    }
    rebuild()
    AlertDialog.Builder(this)
        .setTitle("Calques (frame #${project.currentIndex + 1})")
        .setView(scroll)
        .setPositiveButton("Fermer", null)
        .setNeutralButton("+ Ajouter") { _, _ ->
            pushUndo()
            f.addLayer()
            binding.canvas.syncFrameBitmap()
            framesAdapter.notifyItemChanged(project.currentIndex)
            refreshLayersStrip()
            toast("Couche ajoutée")
            showLayersDialog()
        }
        .setNegativeButton("Actions…") { _, _ -> showLayerActions() }
        .show()
}

internal fun MainActivity.showLayerActions() {
    val f = project.currentFrame
    val l = f.layers[f.activeLayer]
    val groupAction = if (l.groupName == null) "📁 Mettre dans un groupe…" else "📁 Sortir du groupe « ${l.groupName} »"
    val items = arrayOf(
        if (l.visible) "Masquer" else "Afficher",
        "Renommer…",
        "Opacité…",
        groupAction,
        "Supprimer",
        "Monter (au-dessus)",
        "Descendre (en dessous)",
        "Fusionner avec la couche du dessous"
    )
    AlertDialog.Builder(this)
        .setTitle("« ${l.name} »")
        .setItems(items) { _, which ->
            when (which) {
                0 -> { l.visible = !l.visible; f.invalidateComposite(); binding.canvas.syncFrameBitmap() }
                1 -> renameLayer(l)
                2 -> showLayerOpacity(l)
                3 -> if (l.groupName == null) showAssignGroupDialog(l) else { l.groupName = null; toast("Sorti du groupe") }
                4 -> {
                    pushUndo()
                    if (!f.removeLayer(f.activeLayer)) toast("Au moins 1 calque requis")
                    else binding.canvas.syncFrameBitmap()
                }
                5 -> moveLayer(+1)
                6 -> moveLayer(-1)
                7 -> mergeDown()
            }
            framesAdapter.notifyItemChanged(project.currentIndex)
            refreshLayersStrip()
        }
        .show()
}

internal fun MainActivity.showAssignGroupDialog(l: Layer) {
    val existing = project.frames
        .flatMap { it.layers }
        .mapNotNull { it.groupName }
        .distinct()
        .sorted()
    val items = (existing + listOf("➕ Nouveau groupe…")).toTypedArray()
    AlertDialog.Builder(this)
        .setTitle("Mettre « ${l.name} » dans un groupe")
        .setItems(items) { _, which ->
            if (which == existing.size) {
                val input = EditText(this).apply {
                    hint = "Nom du groupe (ex: Vue face, Vue dos, Corps, Arme)"
                    setTextColor(0xFFE8E8F0.toInt())
                }
                AlertDialog.Builder(this)
                    .setTitle("Nouveau groupe")
                    .setView(input)
                    .setPositiveButton("OK") { _, _ ->
                        val name = input.text.toString().trim()
                        if (name.isNotBlank()) {
                            l.groupName = name
                            refreshLayersStrip()
                            toast("Ajouté au groupe « $name »")
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } else {
                l.groupName = existing[which]
                refreshLayersStrip()
                toast("Ajouté au groupe « ${existing[which]} »")
            }
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

internal fun MainActivity.renameLayer(l: Layer) {
    val input = EditText(this).apply { setText(l.name); setTextColor(0xFFE8E8F0.toInt()) }
    AlertDialog.Builder(this)
        .setTitle("Nom du calque")
        .setView(input)
        .setPositiveButton("OK") { _, _ -> l.name = input.text.toString().ifBlank { "Couche" } }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

internal fun MainActivity.showLayerOpacity(l: Layer) {
    val seek = SeekBar(this).apply { max = 100; progress = (l.opacity * 100).toInt() }
    AlertDialog.Builder(this)
        .setTitle("Opacité")
        .setView(seek)
        .setPositiveButton("OK") { _, _ ->
            l.opacity = seek.progress / 100f
            project.currentFrame.invalidateComposite()
            binding.canvas.syncFrameBitmap()
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

internal fun MainActivity.moveLayer(direction: Int) {
    val f = project.currentFrame
    val from = f.activeLayer
    val to = (from + direction).coerceIn(0, f.layers.size - 1)
    if (from == to) return
    pushUndo()
    val item = f.layers.removeAt(from)
    f.layers.add(to, item)
    f.activeLayer = to
    binding.canvas.syncFrameBitmap()
}

internal fun MainActivity.mergeDown() {
    val f = project.currentFrame
    val active = f.activeLayer
    if (f.layers.size < 2) { toast("Un seul calque, rien à fusionner"); return }
    // If active is the bottom, merge UP instead so the action never blocks.
    val targetIdx = if (active == 0) 1 else active - 1
    pushUndo()
    val top = f.layers[maxOf(active, targetIdx)]
    val below = f.layers[minOf(active, targetIdx)]
    for (i in top.pixels.indices) {
        val src = top.pixels[i]
        if ((src ushr 24) and 0xFF >= 128) below.pixels[i] = src
    }
    f.layers.remove(top)
    f.activeLayer = f.layers.indexOf(below).coerceAtLeast(0)
    f.invalidateComposite()
    binding.canvas.syncFrameBitmap()
    refreshLayersStrip()
    toast("Calques fusionnés")
}

/**
 * Rebuild the inline layers strip in the right panel. Groups (Layer.groupName)
 * become headers; ungrouped layers render one row each.
 */
internal fun MainActivity.refreshLayersStrip() {
    val strip = binding.layersStrip
    strip.removeAllViews()
    val f = project.currentFrame
    var i = f.layers.size - 1
    val seenGroups = HashSet<String>()
    while (i >= 0) {
        val layer = f.layers[i]
        val grp = layer.groupName
        if (grp != null && grp !in seenGroups) {
            seenGroups.add(grp)
            addGroupHeader(strip, f, grp)
            f.layers.indices.reversed()
                .filter { f.layers[it].groupName == grp }
                .forEach { addLayerRow(strip, f, it, indented = true) }
            while (i >= 0 && f.layers[i].groupName == grp) i--
        } else if (grp != null) {
            i--
        } else {
            addLayerRow(strip, f, i, indented = false)
            i--
        }
    }
}

private fun MainActivity.addGroupHeader(strip: LinearLayout, f: Frame, groupName: String) {
    val members = f.layers.filter { it.groupName == groupName }
    val anyVisible = members.any { it.visible }
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(4, 6, 4, 6)
        setBackgroundColor(0x22FFFFFF)
        gravity = android.view.Gravity.CENTER_VERTICAL
    }
    val eye = TextView(this).apply {
        text = if (anyVisible) "👁" else "🚫"
        textSize = 16f
        setPadding(8, 4, 12, 4)
        isClickable = true; isFocusable = true
        setOnClickListener {
            val turnOn = !anyVisible
            members.forEach { it.visible = turnOn }
            f.invalidateComposite()
            binding.canvas.syncFrameBitmap()
            framesAdapter.notifyItemChanged(project.currentIndex)
            refreshLayersStrip()
        }
    }
    val name = TextView(this).apply {
        text = "📁 $groupName"
        setTextColor(0xFFA5B4FF.toInt())
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    row.addView(eye); row.addView(name)
    strip.addView(row)
}

private fun MainActivity.addLayerRow(strip: LinearLayout, f: Frame, i: Int, indented: Boolean) {
    val layer = f.layers[i]
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(if (indented) 28 else 4, 4, 4, 4)
        gravity = android.view.Gravity.CENTER_VERTICAL
        if (i == f.activeLayer) setBackgroundColor(0x33A5B4FF)
    }
    val eye = TextView(this).apply {
        text = if (layer.visible) "👁" else "🚫"
        textSize = 16f
        setPadding(8, 4, 12, 4)
        isClickable = true; isFocusable = true
        setOnClickListener {
            layer.visible = !layer.visible
            f.invalidateComposite()
            text = if (layer.visible) "👁" else "🚫"
            binding.canvas.syncFrameBitmap()
            framesAdapter.notifyItemChanged(project.currentIndex)
        }
    }
    val name = TextView(this).apply {
        text = layer.name
        setTextColor(0xFFE8E8F0.toInt())
        textSize = 13f
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        isClickable = true; isFocusable = true
        setOnClickListener {
            f.activeLayer = i
            refreshLayersStrip()
            binding.canvas.invalidate()
        }
        setOnLongClickListener {
            f.activeLayer = i
            showAssignGroupDialog(layer)
            true
        }
    }
    val upBtn = TextView(this).apply {
        text = "▲"
        setTextColor(if (i < f.layers.size - 1) 0xFFE8E8F0.toInt() else 0x44888888)
        textSize = 14f
        setPadding(8, 4, 8, 4)
        isClickable = true; isFocusable = true
        setOnClickListener {
            if (i >= f.layers.size - 1) return@setOnClickListener
            f.activeLayer = i
            moveLayer(+1)
            refreshLayersStrip()
        }
    }
    val downBtn = TextView(this).apply {
        text = "▼"
        setTextColor(if (i > 0) 0xFFE8E8F0.toInt() else 0x44888888)
        textSize = 14f
        setPadding(8, 4, 8, 4)
        isClickable = true; isFocusable = true
        setOnClickListener {
            if (i <= 0) return@setOnClickListener
            f.activeLayer = i
            moveLayer(-1)
            refreshLayersStrip()
        }
    }
    row.addView(eye); row.addView(name); row.addView(upBtn); row.addView(downBtn)
    strip.addView(row)
}
