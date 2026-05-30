package com.pixelhero.app

import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
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
            val dlgIcon = (resources.displayMetrics.density * 36).toInt()
            val eye = ImageButton(this).apply {
                setImageResource(if (l.visible) R.drawable.ic_visibility else R.drawable.ic_visibility_off)
                contentDescription = if (l.visible) "Masquer" else "Afficher"
                background = null
                layoutParams = LinearLayout.LayoutParams(dlgIcon, dlgIcon).apply {
                    rightMargin = 12
                }
                setPadding(6, 6, 6, 6)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setOnClickListener {
                    l.visible = !l.visible
                    f.invalidateComposite()
                    setImageResource(if (l.visible) R.drawable.ic_visibility else R.drawable.ic_visibility_off)
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
            val mergeBtn = ImageButton(this).apply {
                setImageResource(R.drawable.ic_merge)
                contentDescription = "Fusionner avec le calque du dessous"
                background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.tool_button_bg)
                alpha = if (f.layers.size >= 2) 1f else 0.3f
                layoutParams = LinearLayout.LayoutParams(dlgIcon, dlgIcon)
                setPadding(6, 6, 6, 6)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setOnClickListener {
                    if (f.layers.size < 2) { toast("Un seul calque"); return@setOnClickListener }
                    mergeLayerDown(i)
                    rebuild()
                }
            }
            row.addView(mergeBtn)
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
    val groupAction = if (l.groupName == null) "Mettre dans un groupe…" else "Sortir du groupe « ${l.groupName} »"
    val items = arrayOf(
        if (l.visible) "Masquer" else "Afficher",
        "Renommer…",
        "Opacité…",
        groupAction,
        "Supprimer",
        "Monter (au-dessus)",
        "Descendre (en dessous)",
        "Fusionner avec la couche du dessous",
        "Fusionner sélection… (choisir)",
        "Tout aplatir (calques visibles)"
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
                8 -> showMergeSelectionDialog()
                9 -> flattenVisibleLayers()
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
    mergeLayerDown(project.currentFrame.activeLayer)
}

/** src-over alpha blending of one pixel; layerOp is 0..255. */
internal fun blendSrcOver(src: Int, dst: Int, layerOp: Int): Int {
    val srcA = ((src ushr 24) and 0xFF) * layerOp / 255
    if (srcA == 0) return dst
    val dstA = (dst ushr 24) and 0xFF
    if (dstA == 0 || srcA == 255) {
        return (srcA shl 24) or (src and 0xFFFFFF)
    }
    val sa = srcA / 255f
    val da = dstA / 255f * (1f - sa)
    val outA = (sa + da).coerceIn(0f, 1f)
    val sr = ((src shr 16) and 0xFF) * sa
    val sg = ((src shr 8) and 0xFF) * sa
    val sb = (src and 0xFF) * sa
    val dr = ((dst shr 16) and 0xFF) * da
    val dg = ((dst shr 8) and 0xFF) * da
    val db = (dst and 0xFF) * da
    val rr = ((sr + dr) / outA).toInt().coerceIn(0, 255)
    val gg = ((sg + dg) / outA).toInt().coerceIn(0, 255)
    val bb = ((sb + db) / outA).toInt().coerceIn(0, 255)
    return ((outA * 255).toInt() shl 24) or (rr shl 16) or (gg shl 8) or bb
}

/**
 * Merge layer [idx] into the one immediately below it (or above if idx == 0,
 * so the action never blocks at the bottom). Honors the source layer's
 * opacity and visibility — the result on the surviving layer matches what
 * was on screen.
 */
internal fun MainActivity.mergeLayerDown(idx: Int) {
    val f = project.currentFrame
    android.util.Log.d("PixelHero.Merge", "mergeLayerDown(idx=$idx) size=${f.layers.size}")
    if (f.layers.size < 2) { toast("Un seul calque, rien à fusionner"); return }
    val active = idx.coerceIn(0, f.layers.size - 1)
    val targetIdx = if (active == 0) 1 else active - 1
    val topIdx = maxOf(active, targetIdx)
    val belowIdx = minOf(active, targetIdx)
    val sizeBefore = f.layers.size
    pushUndo()
    val top = f.layers[topIdx]
    val below = f.layers[belowIdx]
    if (top.visible) {
        val layerOp = (top.opacity * 255).toInt().coerceIn(0, 255)
        for (i in top.pixels.indices) {
            below.pixels[i] = blendSrcOver(top.pixels[i], below.pixels[i], layerOp)
        }
    }
    below.visible = true
    below.opacity = 1f
    val removed = f.layers.remove(top)
    f.activeLayer = f.layers.indexOf(below).coerceAtLeast(0)
    f.invalidateComposite()
    binding.canvas.syncFrameBitmap()
    framesAdapter.notifyItemChanged(project.currentIndex)
    refreshLayersStrip()
    val sizeAfter = f.layers.size
    android.util.Log.d("PixelHero.Merge", "done removed=$removed size $sizeBefore→$sizeAfter active=${f.activeLayer}")
    toast("Fusion #${topIdx + 1}→#${belowIdx + 1} ($sizeBefore→$sizeAfter calques)")
}

/**
 * Merge the layers at the given indices into one. The lowest-indexed layer
 * (visually bottom-most) keeps its slot; the others are blended on top of it
 * in their existing stacking order, then removed. Respects per-layer opacity
 * and visibility.
 */
internal fun MainActivity.mergeSelectedLayers(indices: List<Int>) {
    val f = project.currentFrame
    val unique = indices.distinct().sorted()
    if (unique.size < 2) { toast("Choisis au moins 2 calques"); return }
    pushUndo()
    val baseIdx = unique.first()
    val base = f.layers[baseIdx]
    for (j in 1 until unique.size) {
        val top = f.layers[unique[j]]
        if (!top.visible) continue
        val layerOp = (top.opacity * 255).toInt().coerceIn(0, 255)
        for (i in top.pixels.indices) {
            base.pixels[i] = blendSrcOver(top.pixels[i], base.pixels[i], layerOp)
        }
    }
    base.visible = true
    base.opacity = 1f
    // Remove the merged layers (skip the base) in descending order so
    // earlier indices stay valid.
    unique.drop(1).sortedDescending().forEach { f.layers.removeAt(it) }
    f.activeLayer = f.layers.indexOf(base).coerceAtLeast(0)
    f.invalidateComposite()
    binding.canvas.syncFrameBitmap()
    framesAdapter.notifyItemChanged(project.currentIndex)
    refreshLayersStrip()
    toast("${unique.size} calques fusionnés")
}

/** Multi-choice picker: tick the layers to merge, hit Fusionner. */
internal fun MainActivity.showMergeSelectionDialog() {
    val f = project.currentFrame
    if (f.layers.size < 2) { toast("Un seul calque"); return }
    // List top-to-bottom (matches what the user sees in the side strip).
    val displayOrder = (f.layers.size - 1 downTo 0).toList()
    val labels = displayOrder.map { i ->
        val l = f.layers[i]
        val grp = l.groupName?.let { " [$it]" } ?: ""
        val vis = if (l.visible) "👁" else "🚫"
        val active = if (i == f.activeLayer) " ●" else ""
        "$vis ${l.name}$grp  (op ${(l.opacity * 100).toInt()}%)$active"
    }.toTypedArray()
    val checked = BooleanArray(displayOrder.size) { displayOrder[it] == f.activeLayer }
    AlertDialog.Builder(this)
        .setTitle("Fusionner les calques choisis")
        .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
            checked[which] = isChecked
        }
        .setPositiveButton("Fusionner") { _, _ ->
            val sel = checked.toList().mapIndexedNotNull { i, c -> if (c) displayOrder[i] else null }
            mergeSelectedLayers(sel)
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

/** Flatten all visible layers of the current frame into one. */
internal fun MainActivity.flattenVisibleLayers() {
    val f = project.currentFrame
    if (f.layers.size < 2) { toast("Un seul calque"); return }
    pushUndo()
    val composite = f.composited().copyOf()
    f.layers.clear()
    val merged = f.addLayer("Fusionné")
    System.arraycopy(composite, 0, merged.pixels, 0, composite.size)
    merged.visible = true; merged.opacity = 1f
    f.activeLayer = 0
    f.invalidateComposite()
    binding.canvas.syncFrameBitmap()
    framesAdapter.notifyItemChanged(project.currentIndex)
    refreshLayersStrip()
    toast("Calques aplatis")
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
    val groupIconSize = (resources.displayMetrics.density * 28).toInt()
    val eye = ImageButton(this).apply {
        setImageResource(if (anyVisible) R.drawable.ic_visibility else R.drawable.ic_visibility_off)
        contentDescription = if (anyVisible) "Masquer le groupe" else "Afficher le groupe"
        background = null
        layoutParams = LinearLayout.LayoutParams(groupIconSize, groupIconSize)
        setPadding(4, 4, 4, 4)
        scaleType = ImageView.ScaleType.FIT_CENTER
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
        text = groupName
        setTextColor(0xFFA5B4FF.toInt())
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_folder, 0, 0, 0)
        compoundDrawablePadding = 8
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
    val iconSize = (resources.displayMetrics.density * 28).toInt()
    val eye = ImageButton(this).apply {
        setImageResource(if (layer.visible) R.drawable.ic_visibility else R.drawable.ic_visibility_off)
        contentDescription = if (layer.visible) "Masquer" else "Afficher"
        background = null
        layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
        setPadding(4, 4, 4, 4)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setOnClickListener {
            layer.visible = !layer.visible
            f.invalidateComposite()
            setImageResource(if (layer.visible) R.drawable.ic_visibility else R.drawable.ic_visibility_off)
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
        setPadding(8, 0, 4, 0)
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
    val upBtn = ImageButton(this).apply {
        setImageResource(R.drawable.ic_arrow_upward)
        contentDescription = "Monter"
        background = null
        alpha = if (i < f.layers.size - 1) 1f else 0.3f
        layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
        setPadding(4, 4, 4, 4)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setOnClickListener {
            if (i >= f.layers.size - 1) return@setOnClickListener
            f.activeLayer = i
            moveLayer(+1)
            refreshLayersStrip()
        }
    }
    val downBtn = ImageButton(this).apply {
        setImageResource(R.drawable.ic_arrow_downward)
        contentDescription = "Descendre"
        background = null
        alpha = if (i > 0) 1f else 0.3f
        layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
        setPadding(4, 4, 4, 4)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setOnClickListener {
            if (i <= 0) return@setOnClickListener
            f.activeLayer = i
            moveLayer(-1)
            refreshLayersStrip()
        }
    }
    val mergeBtn = ImageButton(this).apply {
        setImageResource(R.drawable.ic_merge)
        contentDescription = "Fusionner avec le calque du dessous"
        background = null
        alpha = if (f.layers.size >= 2) 1f else 0.3f
        layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
        setPadding(4, 4, 4, 4)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setOnClickListener {
            toast("Fusion calque #${i + 1}")
            if (f.layers.size < 2) { toast("Un seul calque"); return@setOnClickListener }
            val before = f.layers.size
            mergeLayerDown(i)
            val after = f.layers.size
            if (after == before) toast("⚠ rien retiré ($before → $after)")
        }
        setOnLongClickListener {
            f.activeLayer = i
            showMergeSelectionDialog()
            true
        }
    }
    row.addView(eye); row.addView(name); row.addView(upBtn); row.addView(downBtn); row.addView(mergeBtn)
    strip.addView(row)
}
