package com.pixelhero.app

import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Filter UI extracted from MainActivity. The actual pixel math lives in
 * [Filters]; this file just handles the dialogs (pick filter, pick scope,
 * pick range) and the async apply pipeline.
 */

internal fun MainActivity.showFiltersMenu() {
    val filters = Filters.Filter.values()
    val labels = filters.map { it.displayName }.toTypedArray()
    AlertDialog.Builder(this)
        .setTitle("Filtre à appliquer")
        .setItems(labels) { _, which -> askFilterScope(filters[which]) }
        .show()
}

internal fun MainActivity.askFilterScope(filter: Filters.Filter) {
    AlertDialog.Builder(this)
        .setTitle("Appliquer « ${filter.displayName} » sur :")
        .setItems(arrayOf("Frame courante", "Toutes les frames", "Plage de frames…")) { _, scope ->
            when (scope) {
                0 -> applyFilterRange(filter, project.currentIndex, project.currentIndex)
                1 -> applyFilterRange(filter, 0, project.frames.size - 1)
                2 -> askFilterRange(filter)
            }
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

internal fun MainActivity.askFilterRange(filter: Filters.Filter) {
    val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 24) }
    container.addView(TextView(this).apply {
        text = "Plage de frames (1 à ${project.frames.size})"
        setTextColor(0xFFE8E8F0.toInt())
    })
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val etFrom = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER; setText("1"); setTextColor(0xFFE8E8F0.toInt())
    }
    val etTo = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER; setText("${project.frames.size}"); setTextColor(0xFFE8E8F0.toInt())
    }
    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    etFrom.layoutParams = lp; etTo.layoutParams = lp
    row.addView(etFrom); row.addView(etTo)
    container.addView(row)
    AlertDialog.Builder(this)
        .setTitle("Plage pour « ${filter.displayName} »")
        .setView(container)
        .setPositiveButton("Appliquer") { _, _ ->
            val from = (etFrom.text.toString().toIntOrNull() ?: 1).coerceIn(1, project.frames.size) - 1
            val to = (etTo.text.toString().toIntOrNull() ?: project.frames.size).coerceIn(1, project.frames.size) - 1
            applyFilterRange(filter, minOf(from, to), maxOf(from, to))
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

internal fun MainActivity.applyFilterRange(filter: Filters.Filter, fromIdx: Int, toIdx: Int) {
    if (fromIdx == toIdx) pushUndoFullFrame() else pushUndoAllFrames()
    val outlineColor = project.primaryColor
    val totalFrames = toIdx - fromIdx + 1
    val progress = AlertDialog.Builder(this)
        .setTitle("Filtre « ${filter.displayName} »")
        .setMessage("Préparation…")
        .setCancelable(false)
        .show()
    lifecycleScope.launch {
        var nLayers = 0
        withContext(Dispatchers.Default) {
            for (i in fromIdx..toIdx) {
                val f = project.frames.getOrNull(i) ?: continue
                nLayers = f.layers.size
                for (layer in f.layers) {
                    val out = Filters.apply(layer.pixels, f.width, f.height, filter, outlineColor)
                    out.copyInto(layer.pixels)
                }
                val done = i - fromIdx + 1
                withContext(Dispatchers.Main) {
                    progress.setMessage("Frame $done / $totalFrames…")
                }
            }
        }
        progress.dismiss()
        binding.canvas.syncFrameBitmap()
        framesAdapter.notifyDataSetChanged()
        binding.timeline.invalidate()
        toast("Filtre appliqué : $totalFrames frame(s) × $nLayers calque(s)")
    }
}
