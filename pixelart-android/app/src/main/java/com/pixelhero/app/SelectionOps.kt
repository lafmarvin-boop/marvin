package com.pixelhero.app

import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog

/**
 * Selection-related UI extracted from MainActivity:
 *  - the always-visible bottom palette (refreshSelectionPalette)
 *  - the legacy modal selection-actions dialog (kept as fallback)
 *  - clipboard helpers (liftAndCopy)
 *  - geometric operations (flipSelection, cropToSelection)
 *  - paste-into-arbitrary-layer picker
 *  - switchTool helper (used by both top toolbar and bottom palette)
 */

internal fun MainActivity.refreshSelectionPalette() {
    val sel = binding.canvas.selection
    val row = binding.selectionPaletteRow
    val container = binding.selectionPalette
    if (!sel.active) {
        container.visibility = View.GONE
        row.removeAllViews()
        return
    }
    container.visibility = View.VISIBLE
    row.removeAllViews()

    fun btn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        minWidth = (resources.displayMetrics.density * 56).toInt()
        setPadding(16, 8, 16, 8)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(4, 0, 4, 0) }
        layoutParams = lp
        setOnClickListener { onClick() }
    }

    row.addView(btn("▭") {
        binding.canvas.selectionRefineMode = PixelCanvasView.SelectionRefineMode.NONE
        switchTool(Tool.SELECT, toastIt = false)
        toast("Trace un rectangle.")
    })
    row.addView(btn("🪢") {
        binding.canvas.selectionRefineMode = PixelCanvasView.SelectionRefineMode.NONE
        switchTool(Tool.LASSO, toastIt = false)
        toast("Trace le contour à main levée.")
    })
    row.addView(btn("🪄") {
        binding.canvas.selectionRefineMode = PixelCanvasView.SelectionRefineMode.NONE
        switchTool(Tool.WAND, toastIt = false)
        toast("Touche une zone à sélectionner par couleur.")
    })
    row.addView(btn("🖐") {
        binding.canvas.selectionRefineMode = PixelCanvasView.SelectionRefineMode.NONE
        switchTool(Tool.SELECT, toastIt = false)
        toast("Glisse la sélection pour la déplacer.")
    })
    row.addView(btn("➕") {
        binding.canvas.selectionRefineMode = PixelCanvasView.SelectionRefineMode.ADD
        toast("Touche des pixels pour les AJOUTER à la sélection")
    })
    row.addView(btn("➖") {
        binding.canvas.selectionRefineMode = PixelCanvasView.SelectionRefineMode.SUB
        toast("Touche des pixels pour les RETIRER de la sélection")
    })
    row.addView(btn("📋") {
        val pair = binding.canvas.copySelectionToClipboard() ?: liftAndCopy()
        pair?.let { clipboardW = it.first; clipboardPixels = it.second; toast("Copié") }
    })
    row.addView(btn("✂") {
        if (sel.floating == null) liftAndCopy()
        binding.canvas.copySelectionToClipboard()?.let { clipboardW = it.first; clipboardPixels = it.second }
        binding.canvas.cutSelectionToClipboard()
        binding.canvas.invalidate()
        toast("Coupé")
    })
    if (clipboardPixels != null) {
        row.addView(btn("📌") {
            val pixels = clipboardPixels ?: return@btn
            pushUndo()
            binding.canvas.pasteClipboard(clipboardW, pixels,
                sel.xMin.coerceAtLeast(0), sel.yMin.coerceAtLeast(0))
        })
        row.addView(btn("📌→") { showPasteIntoLayerPicker(sel) })
    }
    row.addView(btn("↔") { flipSelection(horizontal = true) })
    row.addView(btn("↕") { flipSelection(horizontal = false) })
    row.addView(btn("✓") {
        pushUndo()
        binding.canvas.commitFloatingSelection()
        binding.canvas.selection.clear()
        binding.canvas.selectionRefineMode = PixelCanvasView.SelectionRefineMode.NONE
        binding.canvas.invalidate()
        refreshSelectionPalette()
    })
}

/** Updates the left-toolbar selected state when switching tool from code. */
internal fun MainActivity.switchTool(tool: Tool, toastIt: Boolean = true) {
    val btnMap = mapOf(
        Tool.PENCIL to binding.toolPencil,
        Tool.ERASER to binding.toolEraser,
        Tool.FILL to binding.toolFill,
        Tool.UNFILL to binding.toolUnfill,
        Tool.PICKER to binding.toolPicker,
        Tool.LINE to binding.toolLine,
        Tool.RECT to binding.toolRect,
        Tool.RECT_FILL to binding.toolRectFill,
        Tool.SELECT to binding.toolSelect,
        Tool.LASSO to binding.toolLasso,
        Tool.WAND to binding.toolWand,
        Tool.MOVE to binding.toolMove
    )
    btnMap.values.forEach { it.isSelected = false }
    btnMap[tool]?.isSelected = true
    binding.canvas.tool = tool
}

internal fun MainActivity.cropToSelection() {
    val sel = binding.canvas.selection
    if (!sel.active) { toast("Aucune sélection"); return }
    val newW = sel.width
    val newH = sel.height
    if (newW < 1 || newH < 1) return
    pushUndo()
    project.frames.forEach { f ->
        val newLayers = f.layers.map { layer ->
            val n = Layer(newW, newH, layer.name)
            n.visible = layer.visible; n.opacity = layer.opacity
            for (y in 0 until newH) for (x in 0 until newW) {
                val sx = sel.xMin + x
                val sy = sel.yMin + y
                if (sx in 0 until layer.width && sy in 0 until layer.height) {
                    n.pixels[y * newW + x] = layer.pixels[sy * layer.width + sx]
                }
            }
            n
        }
        f.layers.clear()
        f.layers.addAll(newLayers)
    }
    project.width = newW; project.height = newH
    sel.clear()
    applyProject()
    toast("Recadré à ${newW}×${newH}")
}

internal fun MainActivity.showSelectionActions() {
    if (!binding.canvas.selection.active) return
    val hasFloating = binding.canvas.selection.floating != null
    val items = mutableListOf<String>()
    if (hasFloating) items.add("🖐 Déplacer (glisse-la sur la feuille)")
    items.add("➕ Ajouter des pixels (pinceau)")
    items.add("➖ Retirer des pixels (pinceau)")
    items.add(getString(R.string.copy))
    items.add(getString(R.string.cut))
    if (clipboardPixels != null) items.add(getString(R.string.paste))
    if (clipboardPixels != null) items.add("📋➡ Coller dans un autre calque…")
    items.add(getString(R.string.flip_h))
    items.add(getString(R.string.flip_v))
    items.add("Recadrer le canvas à cette sélection")
    items.add("Valider / Désélectionner")
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.tool_select))
        .setItems(items.toTypedArray()) { _, which ->
            val sel = binding.canvas.selection
            when (items[which]) {
                "🖐 Déplacer (glisse-la sur la feuille)" -> {
                    toast("Glisse à l'écran pour positionner. Tape ailleurs pour valider.")
                }
                "➕ Ajouter des pixels (pinceau)" -> {
                    binding.canvas.selectionRefineMode = PixelCanvasView.SelectionRefineMode.ADD
                    toast("Touche des pixels pour les ajouter à la sélection. Change d'outil pour sortir.")
                }
                "➖ Retirer des pixels (pinceau)" -> {
                    binding.canvas.selectionRefineMode = PixelCanvasView.SelectionRefineMode.SUB
                    toast("Touche des pixels pour les retirer de la sélection. Change d'outil pour sortir.")
                }
                getString(R.string.copy) -> {
                    val pair = binding.canvas.copySelectionToClipboard() ?: liftAndCopy()
                    pair?.let {
                        clipboardW = it.first
                        clipboardPixels = it.second
                        toast("Copié — vous pouvez maintenant changer de calque puis coller")
                    }
                }
                getString(R.string.cut) -> {
                    if (sel.floating == null) liftAndCopy()
                    binding.canvas.copySelectionToClipboard()?.let {
                        clipboardW = it.first; clipboardPixels = it.second
                    }
                    binding.canvas.cutSelectionToClipboard()
                    binding.canvas.invalidate()
                    toast("Coupé — vous pouvez maintenant changer de calque puis coller")
                }
                getString(R.string.paste) -> {
                    val pixels = clipboardPixels ?: return@setItems
                    pushUndo()
                    binding.canvas.pasteClipboard(clipboardW, pixels, sel.xMin.coerceAtLeast(0), sel.yMin.coerceAtLeast(0))
                }
                "📋➡ Coller dans un autre calque…" -> {
                    showPasteIntoLayerPicker(sel)
                }
                getString(R.string.flip_h) -> flipSelection(horizontal = true)
                getString(R.string.flip_v) -> flipSelection(horizontal = false)
                "Recadrer le canvas à cette sélection" -> cropToSelection()
                else -> {
                    pushUndo()
                    binding.canvas.commitFloatingSelection()
                    binding.canvas.selection.clear()
                    binding.canvas.invalidate()
                }
            }
        }
        .show()
}

internal fun MainActivity.showPasteIntoLayerPicker(sel: Selection) {
    val pixels = clipboardPixels ?: return
    val f = project.currentFrame
    val items = (f.layers.indices.map { i -> "Calque ${i + 1} : ${f.layers[i].name}" }
        + listOf("➕ Nouveau calque")).toTypedArray()
    AlertDialog.Builder(this)
        .setTitle("Coller dans quel calque ?")
        .setItems(items) { _, which ->
            pushUndo()
            if (which == f.layers.size) {
                f.addLayer()
                f.activeLayer = f.layers.size - 1
                refreshLayersStrip()
            } else {
                f.activeLayer = which
                refreshLayersStrip()
            }
            binding.canvas.pasteClipboard(
                clipboardW, pixels,
                sel.xMin.coerceAtLeast(0), sel.yMin.coerceAtLeast(0)
            )
            toast("Collé dans « ${f.layers[f.activeLayer].name} »")
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

internal fun MainActivity.liftAndCopy(): Pair<Int, IntArray>? {
    val sel = binding.canvas.selection
    if (sel.floating == null && sel.active) {
        val w = sel.width; val h = sel.height
        if (w > 0 && h > 0) {
            val out = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w)
                out[y * w + x] = project.currentFrame.get(sel.xMin + x, sel.yMin + y)
            return w to out
        }
    }
    return binding.canvas.copySelectionToClipboard()
}

internal fun MainActivity.flipSelection(horizontal: Boolean) {
    val sel = binding.canvas.selection
    val floating = sel.floating
    if (floating == null) {
        pushUndo()
        val w = sel.width; val h = sel.height
        val tmp = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val sx = if (horizontal) w - 1 - x else x
            val sy = if (horizontal) y else h - 1 - y
            tmp[sy * w + sx] = project.currentFrame.get(sel.xMin + x, sel.yMin + y)
        }
        for (y in 0 until h) for (x in 0 until w) {
            project.currentFrame.set(sel.xMin + x, sel.yMin + y, tmp[y * w + x])
        }
        binding.canvas.syncFrameBitmap()
    } else {
        val w = sel.floatW; val h = sel.floatH
        val tmp = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val sx = if (horizontal) w - 1 - x else x
            val sy = if (horizontal) y else h - 1 - y
            tmp[sy * w + sx] = floating[y * w + x]
        }
        sel.floating = tmp
        binding.canvas.invalidate()
    }
}
