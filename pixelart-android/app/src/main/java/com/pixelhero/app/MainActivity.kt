package com.pixelhero.app

import androidx.appcompat.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pixelhero.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    internal lateinit var binding: ActivityMainBinding
    internal lateinit var project: Project
    internal lateinit var paletteAdapter: SwatchAdapter
    private lateinit var recentAdapter: SwatchAdapter
    internal lateinit var framesAdapter: FramesAdapter

    private val undoStack = ArrayDeque<UndoSnapshot>()
    private val redoStack = ArrayDeque<UndoSnapshot>()
    /** Undo cap scales with canvas size to keep memory bounded.
     * Targets ~30 MB total: ~size pixels/snapshot × 4 bytes × N. */
    private val maxUndo: Int get() {
        val pixelsPerSnapshot = project.width * project.height
        val targetBytes = 30 * 1024 * 1024
        return ((targetBytes / (pixelsPerSnapshot * 4)).coerceIn(8, 80))
    }

    // Animation playback
    private var animTimer: Runnable? = null
    private val animHandler = Handler(Looper.getMainLooper())
    private var isPlaying = false
    /** Speed multiplier applied to both Play and miniPreview (0.25× to 4×). */
    private var previewSpeed: Float = 1f
    private var savedFrameIdx = 0
    private var playIdx = 0

    // Autosave
    private val autosaveHandler = Handler(Looper.getMainLooper())
    private var autosaveRunnable: Runnable? = null

    // Mini preview loop
    private val miniPreviewHandler = Handler(Looper.getMainLooper())
    private var miniPreviewTask: Runnable? = null
    private var miniPreviewIdx = 0
    private var miniPreviewEnabled = true

    // Clipboard for selection paste (across tool resets)
    private var clipboardW = 0
    private var clipboardPixels: IntArray? = null

    private val pickBg = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadBgImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash screen API (animated fade-out). Auto on Android 12+, fallback theme below.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Apply Material You dynamic colors on Android 12+ (no-op on older)
        try {
            com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this)
        } catch (_: Throwable) {}
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Crash recovery: if previous session was abnormal, offer to restore
        if (CrashRecovery.hasUnsavedSession(this)) {
            val recovered = CrashRecovery.restore(this)
            if (recovered != null) {
                AlertDialog.Builder(this)
                    .setTitle("Récupération")
                    .setMessage("La session précédente s'est terminée sans sauvegarde. Restaurer le projet « ${recovered.name} » ?")
                    .setPositiveButton("Restaurer") { _, _ ->
                        project = recovered
                        applyProject()
                        toast("Projet restauré")
                    }
                    .setNegativeButton("Ignorer", null)
                    .setOnDismissListener { CrashRecovery.endSession(this) }
                    .show()
            } else CrashRecovery.endSession(this)
        }
        CrashRecovery.beginSession(this)

        project = Project()
        wireCanvas()
        wireTools()
        wireTopBar()
        wireRightPanel()
        wireFrames()
        wireTimeline()

        if (getPreferences(MODE_PRIVATE).getBoolean("firstRun", true)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("firstRun", false).apply()
            showTutorial(force = false)
            showNewProjectDialog()
        } else {
            applyProject()
        }
        scheduleAutosave()
        startMiniPreview()
    }

    private var miniPingPongForward = true
    private var miniPreviewBmp: Bitmap? = null

    private fun startMiniPreview() {
        stopMiniPreview()
        val task = object : Runnable {
            override fun run() {
                if (!miniPreviewEnabled || isPlaying) {
                    miniPreviewHandler.postDelayed(this, 500L)
                    return
                }
                if (project.frames.isNotEmpty()) {
                    miniPreviewIdx = miniPreviewIdx.coerceIn(0, project.frames.size - 1)
                    val f = project.frames[miniPreviewIdx]
                    val src = if (f.layers.size > 1) f.composited() else f.pixels
                    val bmp = miniPreviewBmp.takeIf { it != null && it.width == f.width && it.height == f.height && !it.isRecycled }
                        ?: Bitmap.createBitmap(f.width, f.height, Bitmap.Config.ARGB_8888).also { miniPreviewBmp = it }
                    bmp.setPixels(src, 0, f.width, 0, 0, f.width, f.height)
                    val drawable = android.graphics.drawable.BitmapDrawable(resources, bmp)
                    drawable.isFilterBitmap = false
                    binding.miniPreview.setImageDrawable(drawable)
                    miniPreviewIdx = miniNextIndex(miniPreviewIdx, project.frames.size, project.playMode)
                    if (miniPreviewIdx < 0) miniPreviewIdx = 0
                }
                // Respect per-frame delay if set; otherwise project FPS. Scaled by speed multiplier.
                val baseDelay = project.delayForFrame(miniPreviewIdx).toLong()
                val delay = (baseDelay / previewSpeed).toLong().coerceAtLeast(30L)
                miniPreviewHandler.postDelayed(this, delay)
            }
        }
        miniPreviewTask = task
        miniPreviewHandler.post(task)
    }

    private fun miniNextIndex(current: Int, size: Int, mode: PlayMode): Int {
        return when (mode) {
            PlayMode.LOOP, PlayMode.ONCE -> (current + 1) % size
            PlayMode.REVERSE -> if (current - 1 < 0) size - 1 else current - 1
            PlayMode.PING_PONG -> {
                if (miniPingPongForward) {
                    if (current + 1 >= size) { miniPingPongForward = false; (current - 1).coerceAtLeast(0) }
                    else current + 1
                } else {
                    if (current - 1 < 0) { miniPingPongForward = true; (current + 1).coerceAtMost(size - 1) }
                    else current - 1
                }
            }
        }
    }

    private fun stopMiniPreview() {
        miniPreviewTask?.let { miniPreviewHandler.removeCallbacks(it) }
        miniPreviewTask = null
    }

    private fun scheduleAutosave() {
        autosaveRunnable?.let { autosaveHandler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                // Only save if the user has actually done work since last save.
                // Avoids creating "Sans titre" entries on every fresh boot.
                if (isDirty) {
                    ProjectStorage.save(this@MainActivity, project)
                    runOnUiThread { isDirty = false; updateTitleDirty() }
                }
                autosaveHandler.postDelayed(this, 30_000L)
            }
        }
        autosaveRunnable = r
        autosaveHandler.postDelayed(r, 30_000L)
    }

    override fun onPause() {
        super.onPause()
        if (isPlaying) stopPlay()
        // Always save on pause — losing work after a phone call / Home press is
        // worse than an extra "Sans titre" entry.
        ProjectStorage.save(this, project)
        autosaveRunnable?.let { autosaveHandler.removeCallbacks(it) }
        stopMiniPreview()
        CrashRecovery.endSession(this)
    }

    override fun onResume() {
        super.onResume()
        scheduleAutosave()
        startMiniPreview()
    }

    // ---- Canvas wiring ----
    private fun wireCanvas() {
        binding.canvas.project = project
        binding.canvas.color = project.primaryColor
        binding.canvas.tool = Tool.PENCIL
        binding.canvas.palmRejection = getSharedPreferences("settings", MODE_PRIVATE)
            .getBoolean("palmRejection", true)
        binding.canvas.onStrokeStart = { pushUndo() }
        binding.canvas.onProjectChanged = {
            if (project.frames.indices.contains(project.currentIndex))
                framesAdapter.notifyItemChanged(project.currentIndex)
        }
        binding.canvas.onColorPicked = { c -> setColor(c) }
        // Right after the user finishes drawing a selection rectangle, open
        // the actions menu so they can pick Move / Copy / Cut / Paste etc.
        binding.canvas.onSelectionCreated = { refreshSelectionPalette() }
        binding.canvas.onSelectionStateChanged = { refreshSelectionPalette() }
        // 2-finger horizontal swipe on the canvas changes the active frame.
        binding.canvas.onFrameSwipe = { delta ->
            val n = project.frames.size
            if (n > 1) {
                val next = (project.currentIndex + delta).coerceIn(0, n - 1)
                if (next != project.currentIndex) {
                    project.currentIndex = next
                    refreshAfterFrameChange()
                }
            }
        }
    }

    /**
     * Build a contextual horizontal palette at the bottom of the canvas (just
     * above the timeline) with one-tap actions for the active selection.
     * Hidden when no selection is active.
     */
    private fun refreshSelectionPalette() {
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

        // Selection-tool switchers
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
        // Refine
        row.addView(btn("➕") {
            binding.canvas.selectionRefineMode = PixelCanvasView.SelectionRefineMode.ADD
            toast("Touche des pixels pour les AJOUTER à la sélection")
        })
        row.addView(btn("➖") {
            binding.canvas.selectionRefineMode = PixelCanvasView.SelectionRefineMode.SUB
            toast("Touche des pixels pour les RETIRER de la sélection")
        })
        // Clipboard
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
        // Flip / commit
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

    /** Helper that updates the toolbar selected state when switching tool from code. */
    private fun switchTool(tool: Tool, toastIt: Boolean = true) {
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

    internal fun pushUndo() {
        // Default: just the active layer of the current frame — cheap, fits
        // the common case (pencil, eraser, fill, line, rect).
        val f = project.currentFrame
        undoStack.addLast(UndoSnapshot.SingleLayer(
            project.currentIndex,
            f.activeLayer,
            f.layers[f.activeLayer].pixels.copyOf()
        ))
        afterPushUndo()
    }

    /** Snapshot every layer of the current frame — for operations that touch all layers. */
    private fun pushUndoFullFrame() {
        val f = project.currentFrame
        undoStack.addLast(UndoSnapshot.FullFrame(
            project.currentIndex,
            f.layers.map { it.pixels.copyOf() }
        ))
        afterPushUndo()
    }

    /** Snapshot every layer of every frame — for bulk operations (filters on all frames). */
    private fun pushUndoAllFrames() {
        undoStack.addLast(UndoSnapshot.AllFrames(
            project.frames.map { fr -> fr.layers.map { it.pixels.copyOf() } }
        ))
        afterPushUndo()
    }

    private fun afterPushUndo() {
        while (undoStack.size > maxUndo) undoStack.removeFirst()
        redoStack.clear()
        isDirty = true
        updateTitleDirty()
        // Crash recovery: snapshot the project on every undo push
        runCatching { CrashRecovery.snapshot(this, project) }
    }

    private var isDirty = false

    private fun updateTitleDirty() {
        // We don't have a dedicated title TextView - use the topbar text
        val v = (binding.topBar.getChildAt(1) as? TextView)
        v?.text = if (isDirty) "PixelHero •" else "PixelHero"
    }

    // ---- Tools ----
    private fun wireTools() {
        // Map button -> (tool, helpKey)
        val tools = listOf(
            Triple(binding.toolPencil,    Tool.PENCIL,    "pencil"),
            Triple(binding.toolEraser,    Tool.ERASER,    "eraser"),
            Triple(binding.toolFill,      Tool.FILL,      "fill"),
            Triple(binding.toolUnfill,    Tool.UNFILL,    "unfill"),
            Triple(binding.toolPicker,    Tool.PICKER,    "picker"),
            Triple(binding.toolLine,      Tool.LINE,      "line"),
            Triple(binding.toolRect,      Tool.RECT,      "rect"),
            Triple(binding.toolRectFill,  Tool.RECT_FILL, "rectfill"),
            Triple(binding.toolSelect,    Tool.SELECT,    "select"),
            Triple(binding.toolLasso,     Tool.LASSO,     "lasso"),
            Triple(binding.toolWand,      Tool.WAND,      "wand"),
            Triple(binding.toolMove,      Tool.MOVE,      "move")
        )
        binding.toolPencil.isSelected = true
        tools.forEach { (btn, tool, helpKey) ->
            btn.setOnClickListener {
                tools.forEach { (b, _, _) -> b.isSelected = false }
                btn.isSelected = true
                binding.canvas.tool = tool
                when (tool) {
                    Tool.SELECT -> toast("Trace un rectangle. Glisse-le ensuite pour déplacer l'élément.")
                    Tool.LASSO -> toast("Dessine le contour à main levée. Relâche pour valider la sélection.")
                    Tool.WAND -> toast("Touche un pixel. La zone connectée de même couleur sera sélectionnée.")
                    Tool.UNFILL -> toast("Touche une zone : tous les pixels connectés deviennent transparents.")
                    else -> {}
                }
                // Leaving lasso/refine modes resets the refine state.
                if (tool != Tool.SELECT && tool != Tool.LASSO && tool != Tool.WAND) {
                    binding.canvas.selectionRefineMode = PixelCanvasView.SelectionRefineMode.NONE
                }
                // No modal selection menu anymore — the bottom palette provides
                // all selection actions and is always visible when a selection
                // exists. The palette refreshes via onSelectionStateChanged.
                refreshSelectionPalette()
            }
            btn.setOnLongClickListener {
                showHelpFor(helpKey)
                true
            }
        }
        binding.btnClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.confirm_clear)
                .setPositiveButton(R.string.clear_frame) { _, _ ->
                    pushUndo()
                    binding.canvas.clearFrame()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        binding.btnClear.attachHelp("clear")
        binding.btnGrid.setOnClickListener {
            binding.canvas.showGrid = !binding.canvas.showGrid
            binding.btnGrid.isSelected = binding.canvas.showGrid
            binding.canvas.invalidate()
        }
        binding.btnGrid.isSelected = true
        binding.btnGrid.attachHelp("grid")

        binding.btnFlip.setOnClickListener { showFlipMenu() }
        binding.btnFlip.attachHelp("flip")
    }

    private fun showFlipMenu() {
        val items = arrayOf(
            getString(R.string.flip_h),
            getString(R.string.flip_v),
            "Décaler ↑ 1px", "Décaler ↓ 1px", "Décaler ← 1px", "Décaler → 1px"
        )
        AlertDialog.Builder(this)
            .setTitle("Transformer la frame")
            .setItems(items) { _, which ->
                pushUndo()
                val f = project.currentFrame
                val newFrame = when (which) {
                    0 -> f.flipHorizontal()
                    1 -> f.flipVertical()
                    2 -> f.shifted(0, -1)
                    3 -> f.shifted(0, 1)
                    4 -> f.shifted(-1, 0)
                    5 -> f.shifted(1, 0)
                    else -> f
                }
                FrameTransforms.applyInPlace(f, newFrame.pixels)
                binding.canvas.syncFrameBitmap()
                framesAdapter.notifyItemChanged(project.currentIndex)
            }
            .show()
    }

    private fun cropToSelection() {
        val sel = binding.canvas.selection
        if (!sel.active) { toast("Aucune sélection"); return }
        val newW = sel.width
        val newH = sel.height
        if (newW < 1 || newH < 1) return
        pushUndo()
        // Resize each frame to (newW, newH), keeping only the selected area
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

    private fun showSelectionActions() {
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
                        val pair = binding.canvas.copySelectionToClipboard()
                            ?: liftAndCopy()
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
                    getString(R.string.flip_h) -> {
                        flipSelection(horizontal = true)
                    }
                    getString(R.string.flip_v) -> {
                        flipSelection(horizontal = false)
                    }
                    "Recadrer le canvas à cette sélection" -> {
                        cropToSelection()
                    }
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

    /**
     * Pick a target layer (creating a new one if needed) and paste the
     * clipboard content into it at the current selection origin.
     */
    private fun showPasteIntoLayerPicker(sel: Selection) {
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

    private fun liftAndCopy(): Pair<Int, IntArray>? {
        val sel = binding.canvas.selection
        if (sel.floating == null && sel.active) {
            // No floating yet; manually lift from frame
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

    private fun flipSelection(horizontal: Boolean) {
        val sel = binding.canvas.selection
        val floating = sel.floating
        if (floating == null) {
            // Operate on selection in-place on the frame
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

    // ---- Top bar ----
    private fun wireTopBar() {
        binding.btnUndo.setOnClickListener { doUndo() }
        binding.btnRedo.setOnClickListener { doRedo() }
        binding.btnPlay.setOnClickListener { togglePlay() }
        // Right panel tabs: which group is visible. Default: Animation (frames + layers).
        binding.tabColors.setOnClickListener { switchRightTab(0) }
        binding.tabLayers.setOnClickListener { switchRightTab(1) }
        binding.tabAnim.setOnClickListener { switchRightTab(2) }
        switchRightTab(2)

        binding.btnMenu.setOnClickListener { showMenu() }
        binding.btnQuickSave.setOnClickListener { quickSaveProject() }
        binding.btnSymmetry.setOnClickListener { showSymmetryMenu() }
        binding.btnDecor.setOnClickListener { openDecorPalette() }
        binding.btnEffects.setOnClickListener { openEffectsPalette() }
        binding.btnMagic.setOnClickListener { openMagicPalette() }
        binding.btnTween.setOnClickListener { showTweenDialog() }
        binding.btnUndo.attachHelp("undo")
        binding.btnRedo.attachHelp("redo")
        binding.btnPlay.attachHelp("play")
        binding.btnMenu.attachHelp("menu")
        binding.btnSymmetry.attachHelp("symmetry")
        binding.btnMagic.attachHelp("magic")
    }

    private fun doUndo() {
        if (undoStack.isEmpty()) return
        val snap = undoStack.removeLast()
        redoStack.addLast(captureRedoFor(snap))
        applySnapshot(snap)
        refreshAfterFrameChange()
        refreshLayersStrip()
    }

    private fun doRedo() {
        if (redoStack.isEmpty()) return
        val snap = redoStack.removeLast()
        undoStack.addLast(captureRedoFor(snap))
        applySnapshot(snap)
        refreshAfterFrameChange()
        refreshLayersStrip()
    }

    /** Build a snapshot of the CURRENT state matching the shape of [match] (for redo). */
    private fun captureRedoFor(match: UndoSnapshot): UndoSnapshot = when (match) {
        is UndoSnapshot.SingleLayer -> {
            val f = project.frames.getOrNull(match.frameIndex)
            val layer = f?.layers?.getOrNull(match.layerIndex)
            UndoSnapshot.SingleLayer(match.frameIndex, match.layerIndex,
                layer?.pixels?.copyOf() ?: IntArray(0))
        }
        is UndoSnapshot.FullFrame -> {
            val f = project.frames.getOrNull(match.frameIndex)
            UndoSnapshot.FullFrame(match.frameIndex,
                f?.layers?.map { it.pixels.copyOf() } ?: emptyList())
        }
        is UndoSnapshot.AllFrames -> UndoSnapshot.AllFrames(
            project.frames.map { fr -> fr.layers.map { it.pixels.copyOf() } }
        )
    }

    private fun applySnapshot(snap: UndoSnapshot) {
        when (snap) {
            is UndoSnapshot.SingleLayer -> {
                val f = project.frames.getOrNull(snap.frameIndex) ?: return
                f.layers.getOrNull(snap.layerIndex)?.let { snap.pixels.copyInto(it.pixels) }
                project.currentIndex = snap.frameIndex
                f.activeLayer = snap.layerIndex.coerceIn(0, f.layers.size - 1)
            }
            is UndoSnapshot.FullFrame -> {
                val f = project.frames.getOrNull(snap.frameIndex) ?: return
                snap.layers.forEachIndexed { i, src ->
                    f.layers.getOrNull(i)?.let { src.copyInto(it.pixels) }
                }
                project.currentIndex = snap.frameIndex
            }
            is UndoSnapshot.AllFrames -> {
                snap.frames.forEachIndexed { fi, layerPx ->
                    val fr = project.frames.getOrNull(fi) ?: return@forEachIndexed
                    layerPx.forEachIndexed { li, src ->
                        fr.layers.getOrNull(li)?.let { src.copyInto(it.pixels) }
                    }
                }
            }
        }
    }

    private fun showSymmetryMenu() {
        val items = arrayOf(
            getString(R.string.symmetry_none),
            getString(R.string.symmetry_h),
            getString(R.string.symmetry_v),
            getString(R.string.symmetry_both),
            "Rotation 4× (kaléidoscope)"
        )
        val current = when (project.symmetry) {
            SymmetryAxis.NONE -> 0
            SymmetryAxis.HORIZONTAL -> 1
            SymmetryAxis.VERTICAL -> 2
            SymmetryAxis.BOTH -> 3
            SymmetryAxis.ROTATE_4 -> 4
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.symmetry)
            .setSingleChoiceItems(items, current) { dlg, which ->
                project.symmetry = when (which) {
                    1 -> SymmetryAxis.HORIZONTAL
                    2 -> SymmetryAxis.VERTICAL
                    3 -> SymmetryAxis.BOTH
                    4 -> SymmetryAxis.ROTATE_4
                    else -> SymmetryAxis.NONE
                }
                binding.btnSymmetry.isSelected = project.symmetry != SymmetryAxis.NONE
                binding.canvas.invalidate()
                dlg.dismiss()
            }
            .show()
    }

    private fun showSmartGenerator() {
        val categories = arrayOf(
            "🏞️ Décor & scène (procédural)",
            "✨ Effets (particules, filtres)",
            "🔀 Tween entre 2 frames (pixel blend)"
        )
        AlertDialog.Builder(this)
            .setTitle("Générer…")
            .setItems(categories) { _, which ->
                when (which) {
                    0 -> showDecorGenerator()
                    1 -> showEffectsMenu()
                    2 -> showTweenDialog()
                }
            }
            .show()
    }

    private fun showEffectsMenu() {
        val items = arrayOf("✨ Particules (10 types)", "Filtres image")
        AlertDialog.Builder(this).setTitle("✨ Effets")
            .setItems(items) { _, w ->
                when (w) { 0 -> showParticlesDialog(); 1 -> showFiltersMenu() }
            }.show()
    }


    private fun showParticlesDialog() {
        val types = Particles.Type.values()
        val labels = types.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Particules à ajouter")
            .setItems(labels) { _, which ->
                val type = types[which]
                toast("Touchez le canvas pour positionner « ${type.displayName} »")
                binding.canvas.nextTapHandler = { x, y ->
                    applyParticles(type, x, y)
                }
            }
            .show()
    }

    private fun applyParticles(type: Particles.Type, cx: Int, cy: Int) {
        pushUndo()
        // Apply to current frame + the next few; if only 1 frame, create 4 frames first
        if (project.frames.size < 4) {
            val base = project.currentFrame
            while (project.frames.size < 4) project.frames.add(base.copy())
        }
        val targetFrames = project.frames.subList(project.currentIndex, minOf(project.currentIndex + 8, project.frames.size))
        val withParticles = Particles.apply(targetFrames.toList(), type, cx, cy)
        withParticles.forEachIndexed { i, f ->
            val idx = project.currentIndex + i
            if (idx < project.frames.size) {
                f.pixels.copyInto(project.frames[idx].pixels)
            }
        }
        binding.canvas.syncFrameBitmap()
        framesAdapter.notifyDataSetChanged()
        binding.timeline.invalidate()
        toast("Particules « ${type.displayName} » appliquées")
    }

    private fun showGlobalBackgroundDialog() {
        val items = mutableListOf<String>()
        items.add("Capturer la frame actuelle comme fond global")
        if (project.globalBackground != null) {
            items.add("Retirer le fond global")
            items.add("Aperçu du fond (passer en frame fond)")
        }
        AlertDialog.Builder(this)
            .setTitle("Fond global")
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        // Capture current frame's active layer as global background
                        val l = Layer(project.width, project.height, "Fond global")
                        project.currentFrame.pixels.copyInto(l.pixels)
                        project.globalBackground = l
                        toast("Fond global défini (composé sous toutes les frames)")
                        binding.canvas.syncFrameBitmap()
                    }
                    1 -> {
                        project.globalBackground = null
                        toast("Fond global retiré")
                        binding.canvas.syncFrameBitmap()
                    }
                    2 -> {
                        // Show the bg as a new frame (read-only preview, no insert)
                        val bg = project.globalBackground ?: return@setItems
                        val tmp = Frame(project.width, project.height)
                        bg.pixels.copyInto(tmp.pixels)
                        project.frames.add(project.currentIndex + 1, tmp.also { it.tag = "global_bg_preview" })
                        framesAdapter.notifyDataSetChanged()
                        binding.timeline.invalidate()
                    }
                }
            }
            .show()
    }

    private fun exportBackupZip() {
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.Default) { Backup.exportAll(this@MainActivity) }
            val name = "pixelhero_backup_${System.currentTimeMillis()}.zip"
            savePublicImage(bytes, name, "application/zip", share = true)
            toast("Sauvegarde créée: $name")
        }
    }

    private val pickBackupZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                val count = withContext(Dispatchers.Default) {
                    runCatching { Backup.importAll(this@MainActivity, uri) }.getOrDefault(0)
                }
                toast("$count projet(s) restauré(s)")
            }
        }
    }

    private fun pickAndImportBackup() {
        pickBackupZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
    }

    private fun exportGameDevPackage() {
        lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                // 1. Sprite sheet PNG (one row per tag, cells per frame)
                val scale = 1
                val cols = project.frames.size
                val sheet = Bitmap.createBitmap(project.width * cols, project.height, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(sheet)
                val paint = android.graphics.Paint().apply { isFilterBitmap = false }
                project.frames.forEachIndexed { i, f ->
                    val b = frameToBitmap(f, scale)
                    canvas.drawBitmap(b, (i * project.width).toFloat(), 0f, paint)
                    b.recycle()
                }
                val pngBytes = ByteArrayOutputStream().apply { sheet.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
                sheet.recycle()
                savePublicImage(pngBytes, "${project.name}_atlas.png", "image/png")
                // 2. JSON atlas describing frames + tags (Aseprite-like format)
                val atlas = org.json.JSONObject().apply {
                    put("frames", org.json.JSONArray().apply {
                        project.frames.forEachIndexed { i, f ->
                            put(org.json.JSONObject().apply {
                                put("filename", "${project.name}_${i}")
                                put("frame", org.json.JSONObject().apply {
                                    put("x", i * project.width); put("y", 0)
                                    put("w", project.width); put("h", project.height)
                                })
                                put("duration", project.delayForFrame(i))
                                if (f.tag.isNotEmpty()) put("tag", f.tag)
                            })
                        }
                    })
                    put("meta", org.json.JSONObject().apply {
                        put("app", "PixelHero")
                        put("version", "1.0")
                        put("image", "${project.name}_atlas.png")
                        put("format", "RGBA8888")
                        put("size", org.json.JSONObject().apply {
                            put("w", project.width * project.frames.size)
                            put("h", project.height)
                        })
                        put("frameTags", org.json.JSONArray().apply {
                            val tagGroups = project.frames.withIndex().groupBy { it.value.tag }
                                .filter { it.key.isNotEmpty() }
                            tagGroups.forEach { (tag, frames) ->
                                put(org.json.JSONObject().apply {
                                    put("name", tag)
                                    put("from", frames.first().index)
                                    put("to", frames.last().index)
                                    put("direction", "forward")
                                })
                            }
                        })
                    })
                }
                val jsonBytes = atlas.toString(2).toByteArray()
                savePublicImage(jsonBytes, "${project.name}_atlas.json", "application/json")
            }
            toast("Exporté: ${project.name}_atlas.png + .json (Pictures/PixelHero)")
        }
    }

    private fun showExtendedPalettesDialog() {
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

    private fun showStabilizerDialog() {
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

    private fun showTweenDialog() {
        if (project.frames.size < 2) {
            toast("Il faut au moins 2 frames. Crée une frame d'arrivée d'abord.")
            return
        }
        val startIdx = project.currentIndex
        // Default target = frame just after the current one (or just before if current is last)
        var targetIdx = if (startIdx + 1 < project.frames.size) startIdx + 1 else startIdx - 1

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }
        outer.addView(TextView(this).apply {
            text = "Crée des frames intermédiaires entre 2 frames clés. " +
                "L'app interpole couleur par couleur pour servir de guide à ton dessin."
            setTextColor(0xFFE8E8F0.toInt()); textSize = 13f
            setPadding(0, 0, 0, 16)
        })

        // --- DÉPART (current frame, info only) ---
        outer.addView(TextView(this).apply {
            text = "🟢 Départ : frame #${startIdx + 1} (la frame courante)"
            setTextColor(0xFFA5B4FF.toInt()); textSize = 15f
            setPadding(0, 0, 0, 8)
        })

        // --- ARRIVÉE (target picker with prev/next stepper) ---
        val targetLabel = TextView(this).apply {
            text = "🔴 Arrivée : frame #${targetIdx + 1}"
            setTextColor(0xFFA5B4FF.toInt()); textSize = 15f
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val targetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }
        val btnPrev = Button(this).apply {
            text = "◀"; textSize = 18f; isAllCaps = false
            setOnClickListener {
                val candidates = (0 until project.frames.size).filter { it != startIdx }
                val cur = candidates.indexOf(targetIdx)
                targetIdx = candidates[if (cur <= 0) candidates.size - 1 else cur - 1]
                targetLabel.text = "🔴 Arrivée : frame #${targetIdx + 1}"
            }
        }
        val btnNext = Button(this).apply {
            text = "▶"; textSize = 18f; isAllCaps = false
            setOnClickListener {
                val candidates = (0 until project.frames.size).filter { it != startIdx }
                val cur = candidates.indexOf(targetIdx)
                targetIdx = candidates[if (cur >= candidates.size - 1) 0 else cur + 1]
                targetLabel.text = "🔴 Arrivée : frame #${targetIdx + 1}"
            }
        }
        targetRow.addView(btnPrev); targetRow.addView(targetLabel); targetRow.addView(btnNext)
        outer.addView(targetRow)

        // --- NOMBRE DE FRAMES INTERMÉDIAIRES ---
        val countLabel = TextView(this).apply {
            text = "Frames intermédiaires : 3"
            setTextColor(0xFFE8E8F0.toInt()); textSize = 15f
            setPadding(0, 16, 0, 4)
        }
        val countSeek = SeekBar(this).apply {
            max = 19  // → 1..20
            progress = 2  // → 3 by default
        }
        countSeek.setOnSeekBarChangeListener(simpleSeekListener { v ->
            countLabel.text = "Frames intermédiaires : ${v + 1}"
        })
        outer.addView(countLabel); outer.addView(countSeek)

        // --- COURBE D'EASING ---
        outer.addView(TextView(this).apply {
            text = "Courbe (timing du mouvement)"
            setTextColor(0xFFE8E8F0.toInt()); textSize = 15f
            setPadding(0, 16, 0, 4)
        })
        val curves = Easing.Curve.values()
        val curveSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item, curves.map { it.displayName })
            setSelection(curves.indexOf(Easing.Curve.LINEAR).coerceAtLeast(0))
        }
        outer.addView(curveSpinner)

        AlertDialog.Builder(this)
            .setTitle("🔀 Interpolation entre 2 frames")
            .setView(ScrollView(this).apply { addView(outer) })
            .setPositiveButton("Générer") { _, _ ->
                val count = (countSeek.progress + 1).coerceIn(1, 20)
                val curve = curves[curveSpinner.selectedItemPosition]
                if (targetIdx !in 0 until project.frames.size || targetIdx == startIdx) {
                    toast("Choisis une frame d'arrivée différente"); return@setPositiveButton
                }
                pushUndo()
                val a = project.frames[startIdx]
                val b = project.frames[targetIdx]
                val tweens = Tweening.generate(a, b, count, curve)
                val insertAt = minOf(startIdx, targetIdx) + 1
                tweens.forEachIndexed { idx, frame ->
                    project.frames.add(insertAt + idx, frame)
                }
                framesAdapter.notifyDataSetChanged()
                refreshAfterFrameChange()
                toast("$count frames intermédiaires créées (${curve.displayName})")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDecorGenerator() {
        val items = arrayOf(
            "Décor statique → frame courante",
            "Décor statique → image de fond",
            "🎬 Décor animé → 4 nouvelles frames",
            "🎬 Décor animé → 8 nouvelles frames"
        )
        AlertDialog.Builder(this)
            .setTitle("Générer un décor")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pickAndGenerateStaticDecor(replaceFrame = true)
                    1 -> pickAndGenerateStaticDecor(replaceFrame = false)
                    2 -> pickAndGenerateAnimatedDecor(frameCount = 4)
                    3 -> pickAndGenerateAnimatedDecor(frameCount = 8)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickAndGenerateStaticDecor(replaceFrame: Boolean) {
        val decors = DecorGenerator.Decor.values()
        val labels = decors.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (replaceFrame) "Décor → frame courante" else "Décor → image de fond")
            .setItems(labels) { _, which -> generateDecor(decors[which], replaceFrame) }
            .show()
    }

    private fun pickAndGenerateAnimatedDecor(frameCount: Int) {
        val decors = DecorGenerator.Decor.values()
        val labels = decors.map {
            val animated = it in listOf(
                DecorGenerator.Decor.SKY, DecorGenerator.Decor.WATER, DecorGenerator.Decor.SNOW,
                DecorGenerator.Decor.STARS, DecorGenerator.Decor.FOREST, DecorGenerator.Decor.CAVE,
                DecorGenerator.Decor.GRASS, DecorGenerator.Decor.DESERT
            )
            it.displayName + if (animated) " 🎬" else " (statique)"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Décor animé $frameCount frames")
            .setItems(labels) { _, which -> generateAnimatedDecor(decors[which], frameCount) }
            .show()
    }

    private fun generateAnimatedDecor(decor: DecorGenerator.Decor, frameCount: Int) {
        pushUndo()
        val seed = System.currentTimeMillis()
        val frames = DecorGenerator.generateFrames(project.width, project.height, decor, frameCount, seed)
        val tag = "decor_${decor.name.lowercase()}"
        // Insert generated frames AFTER current frame
        var insertAt = project.currentIndex + 1
        frames.forEachIndexed { _, pixels ->
            val nf = Frame(project.width, project.height, pixels)
            nf.tag = tag
            project.frames.add(insertAt++, nf)
        }
        framesAdapter.notifyDataSetChanged()
        toast("${frames.size} frames « ${decor.displayName} » générées")
        // Offer re-roll
        AlertDialog.Builder(this)
            .setTitle("Décor animé « ${decor.displayName} »")
            .setMessage("${frames.size} frames ajoutées. Régénérer avec une nouvelle variation ?")
            .setPositiveButton("Régénérer") { _, _ ->
                // Remove the just-added frames and regenerate
                val removeStart = project.currentIndex + 1
                for (i in 0 until frames.size) {
                    if (removeStart < project.frames.size) project.frames.removeAt(removeStart)
                }
                framesAdapter.notifyDataSetChanged()
                generateAnimatedDecor(decor, frameCount)
            }
            .setNegativeButton("Garder", null)
            .show()
    }

    private fun generateDecor(decor: DecorGenerator.Decor, replaceFrame: Boolean) {
        if (replaceFrame) {
            pushUndo()
            val pixels = DecorGenerator.generate(project.width, project.height, decor)
            pixels.copyInto(project.currentFrame.pixels)
            binding.canvas.syncFrameBitmap()
            framesAdapter.notifyItemChanged(project.currentIndex)
        } else {
            // Generate at canvas resolution, set as bg reference
            val pixels = DecorGenerator.generate(project.width, project.height, decor)
            val bmp = Bitmap.createBitmap(project.width, project.height, Bitmap.Config.ARGB_8888)
            bmp.setPixels(pixels, 0, project.width, 0, 0, project.width, project.height)
            binding.canvas.bgBitmap = bmp
        }
        // Offer re-roll
        AlertDialog.Builder(this)
            .setTitle("Décor « ${decor.displayName} »")
            .setMessage(if (replaceFrame) "Frame remplacée. Voulez-vous une autre variante ?" else "Image de fond définie. Voulez-vous une autre variante ?")
            .setPositiveButton("Régénérer") { _, _ -> generateDecor(decor, replaceFrame) }
            .setNegativeButton("Garder", null)
            .show()
    }


    // ---- Right panel ----
    private fun wireRightPanel() {
        binding.paletteList.layoutManager = GridLayoutManager(this, 8)
        binding.recentList.layoutManager = GridLayoutManager(this, 8)
        paletteAdapter = SwatchAdapter(project.palette, { setColor(it) }, { it == binding.canvas.color })
        recentAdapter = SwatchAdapter(project.recentColors, { setColor(it) }, { it == binding.canvas.color })
        binding.paletteList.adapter = paletteAdapter
        binding.recentList.adapter = recentAdapter

        binding.btnPickColor.setOnClickListener { showColorPicker() }
        binding.currentColorSwatch.setOnClickListener { showColorPicker() }
        binding.btnPickColor.attachHelp("pickColor")
        binding.btnSwap.attachHelp("swap")
        binding.btnAutoShade.attachHelp("autoShade")
        binding.btnPaletteLib.attachHelp("paletteLib")
        binding.btnReplaceColor.attachHelp("replaceColor")
        binding.btnBgLoad.attachHelp("bgLoad")
        binding.btnBgClear.attachHelp("bgClear")
        binding.btnBgFit.attachHelp("bgFit")
        binding.btnDither.attachHelp("dither")
        binding.cbPixelPerfect.attachHelp("pixelPerfect")
        binding.brushSize.attachHelp("brushSize")
        binding.brushSizeLabel.attachHelp("brushSize")
        binding.cbSketchMode.attachHelp("sketch")
        binding.btnSketchBake.attachHelp("sketch")
        binding.btnSketchClear.attachHelp("sketch")
        binding.btnZoomOut.attachHelp("zoomOut")
        binding.btnZoomIn.attachHelp("zoomIn")
        binding.btnZoomFit.attachHelp("zoomFit")
        binding.btnZoom100.attachHelp("zoom100")
        binding.btnZoom400.attachHelp("zoom400")
        binding.btnSwap.setOnClickListener {
            project.swapColors()
            binding.canvas.color = project.primaryColor
            refreshCurrentColorUI()
        }
        binding.secondaryColorSwatch.setOnClickListener {
            // Swap to make secondary primary
            project.swapColors()
            binding.canvas.color = project.primaryColor
            refreshCurrentColorUI()
        }

        binding.btnAutoShade.setOnClickListener {
            val ramp = AutoShading.ramp(project.primaryColor)
            // Insert the 4 non-base shades into the palette near the base color
            val baseIdx = project.palette.indexOf(project.primaryColor).coerceAtLeast(0)
            ramp.filter { it != project.primaryColor }.forEach { c ->
                if (!project.palette.contains(c)) project.palette.add(c)
            }
            paletteAdapter.notifyDataSetChanged()
            toast("4 nuances ajoutées à la palette")
        }
        binding.btnPaletteLib.setOnClickListener { showPaletteLibrary() }
        binding.btnReplaceColor.setOnClickListener { showReplaceColorDialog() }

        binding.btnBgLoad.setOnClickListener { pickBg.launch(arrayOf("image/*")) }
        binding.btnBgClear.setOnClickListener { binding.canvas.bgBitmap = null }
        binding.btnBgFit.setOnClickListener { cycleBgFitMode() }
        updateBgFitButtonLabel()

        binding.bgOpacity.setOnSeekBarChangeListener(simpleSeekListener { v ->
            binding.canvas.bgOpacity = v / 100f
            binding.canvas.invalidate()
        })
        binding.onionRange.setOnSeekBarChangeListener(simpleSeekListener { v ->
            project.onionRange = v
            binding.canvas.rebuildOnionBitmaps()
            binding.canvas.invalidate()
        })

        binding.cbPixelPerfect.setOnCheckedChangeListener { _, checked ->
            project.pixelPerfect = checked
        }

        // Brush size mapping: SeekBar 0-9 -> sizes [1, 2, 3, 4, 5, 6, 8, 10, 12, 16]
        val brushSizes = intArrayOf(1, 2, 3, 4, 5, 6, 8, 10, 12, 16)
        binding.brushSize.setOnSeekBarChangeListener(simpleSeekListener { v ->
            project.brushSize = brushSizes[v.coerceIn(0, brushSizes.size - 1)]
            binding.brushSizeLabel.text = "${project.brushSize}px"
        })

        binding.btnDither.setOnClickListener { showDitherMenu() }

        // Zoom buttons
        binding.btnZoomOut.setOnClickListener { binding.canvas.zoomBy(1f / 1.5f) }
        binding.btnZoomIn.setOnClickListener { binding.canvas.zoomBy(1.5f) }
        binding.btnZoomFit.setOnClickListener { binding.canvas.zoomReset() }
        binding.btnZoom100.setOnClickListener { binding.canvas.setZoom(1f) }
        binding.btnZoom400.setOnClickListener { binding.canvas.setZoom(4f) }

        // Mini preview toggle
        binding.btnPreviewToggle.setOnClickListener {
            miniPreviewEnabled = !miniPreviewEnabled
            binding.btnPreviewToggle.text = if (miniPreviewEnabled) "Stop" else "Play"
        }

        // Speed multiplier: SeekBar 0..16 mapped to 0.25× … 4× (mid=4 → 1×)
        binding.speedSeek.setOnSeekBarChangeListener(simpleSeekListener { v ->
            // exponential mapping so 1× sits at the middle and the slider feels natural
            val ratio = Math.pow(2.0, (v - 4) / 4.0).toFloat()
            previewSpeed = ratio.coerceIn(0.25f, 4f)
            binding.speedLabel.text = "Vitesse ${String.format("%.2f", previewSpeed)}×"
        })
        binding.btnFrameDelay.setOnClickListener {
            showFrameEditDialog(project.currentIndex)
        }

        // Sketch mode
        binding.cbSketchMode.setOnCheckedChangeListener { _, checked ->
            binding.canvas.sketchMode = checked
        }
        binding.btnSketchBake.setOnClickListener {
            pushUndo()
            binding.canvas.bakeSketchIntoFrame()
            framesAdapter.notifyItemChanged(project.currentIndex)
            toast("Croquis intégré à la frame")
        }
        binding.btnSketchClear.setOnClickListener {
            binding.canvas.clearSketch()
        }

        binding.fpsInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) project.fps = binding.fpsInput.text.toString().toIntOrNull()?.coerceIn(1, 60) ?: 8
        }
    }

    private fun showDitherMenu() {
        val items = arrayOf(
            "Aucun (uniforme)",
            "Damier (50%/50%)",
            "Lignes verticales",
            "Lignes horizontales",
            "Sparse (1/4 pixels)",
            "Mix primaire+secondaire (damier)",
            "Pattern personnalisé 4×4…"
        )
        AlertDialog.Builder(this)
            .setTitle("Tramage")
            .setSingleChoiceItems(items, project.ditherPattern) { dlg, which ->
                if (which == 6) {
                    dlg.dismiss()
                    showCustomDitherEditor()
                } else {
                    project.ditherPattern = which
                    binding.btnDither.text = items[which].substringBefore(" ")
                    dlg.dismiss()
                }
            }
            .show()
    }

    private fun showCustomDitherEditor() {
        // Build a 4x4 grid of toggleable cells
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val tv = TextView(this).apply {
            text = "Touchez les cases : la trame se répète sur le canvas (4×4)."
            setTextColor(0xFFE8E8F0.toInt()); textSize = 13f
        }
        container.addView(tv)
        val cellSize = (40 * resources.displayMetrics.density).toInt()
        val rowsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 16)
        }
        for (r in 0..3) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0..3) {
                val v = android.widget.ToggleButton(this).apply {
                    layoutParams = LinearLayout.LayoutParams(cellSize, cellSize).apply {
                        setMargins(2, 2, 2, 2)
                    }
                    textOn = "■"; textOff = "□"
                    isChecked = project.customDither[r][c]
                    text = if (isChecked) "■" else "□"
                    textSize = 18f
                    setOnCheckedChangeListener { _, checked ->
                        project.customDither[r][c] = checked
                        text = if (checked) "■" else "□"
                    }
                }
                row.addView(v)
            }
            rowsContainer.addView(row)
        }
        container.addView(rowsContainer)
        AlertDialog.Builder(this)
            .setTitle("Pattern personnalisé 4×4")
            .setView(container)
            .setPositiveButton("Utiliser") { _, _ ->
                project.ditherPattern = 6
                binding.btnDither.text = "Custom"
            }
            .setNeutralButton("Préréglages") { _, _ -> applyDitherPreset() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyDitherPreset() {
        val presets = arrayOf(
            "Bayer 4×4" to arrayOf(
                booleanArrayOf(true,  false, true,  false),
                booleanArrayOf(false, true,  false, true),
                booleanArrayOf(true,  false, true,  false),
                booleanArrayOf(false, true,  false, true)
            ),
            "Vertical fade" to arrayOf(
                booleanArrayOf(true, true, true, true),
                booleanArrayOf(true, false, true, false),
                booleanArrayOf(true, false, false, false),
                booleanArrayOf(false, false, false, false)
            ),
            "Crosshatch" to arrayOf(
                booleanArrayOf(true, false, false, false),
                booleanArrayOf(false, true, false, false),
                booleanArrayOf(false, false, true, false),
                booleanArrayOf(false, false, false, true)
            ),
            "Bricks" to arrayOf(
                booleanArrayOf(true, true, true, false),
                booleanArrayOf(false, false, false, false),
                booleanArrayOf(true, false, true, true),
                booleanArrayOf(false, false, false, false)
            )
        )
        val labels = presets.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Préréglages tramage")
            .setItems(labels) { _, which ->
                project.customDither = presets[which].second
                project.ditherPattern = 6
                binding.btnDither.text = labels[which]
            }
            .show()
    }

    private fun showOnionColorPicker() {
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

    private fun pickOnionColor(isPrev: Boolean) {
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

    private fun showTextDialog() {
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


    private fun openTileMap() {
        // Save project first (the tile activity loads from storage)
        ProjectStorage.save(this, project)
        TileMapActivity.start(this, project.id)
    }


    private fun showFiltersMenu() {
        val filters = Filters.Filter.values()
        val labels = filters.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Filtre à appliquer")
            .setItems(labels) { _, which ->
                askFilterScope(filters[which])
            }
            .show()
    }

    private fun askFilterScope(filter: Filters.Filter) {
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

    private fun askFilterRange(filter: Filters.Filter) {
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

    private fun applyFilterRange(filter: Filters.Filter, fromIdx: Int, toIdx: Int) {
        if (fromIdx == toIdx) pushUndoFullFrame() else pushUndoAllFrames()
        val outlineColor = project.primaryColor
        val totalFrames = toIdx - fromIdx + 1
        val progress = AlertDialog.Builder(this)
            .setTitle("Filtre « ${filter.displayName} »")
            .setMessage("Préparation…")
            .setCancelable(false)
            .show()
        // Run on Default dispatcher so the UI stays responsive even on a
        // 600×600 canvas × N frames × M layers.
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

    private fun showColorLockMenu() {
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

    private fun cycleBgFitMode() {
        project.bgFit = when (project.bgFit) {
            BgFitMode.FIT -> BgFitMode.COVER
            BgFitMode.COVER -> BgFitMode.STRETCH
            BgFitMode.STRETCH -> BgFitMode.FIT
        }
        updateBgFitButtonLabel()
        binding.canvas.invalidate()
    }

    private fun updateBgFitButtonLabel() {
        binding.btnBgFit.text = "Mode : ${project.bgFit.label}"
    }

    private fun simpleSeekListener(callback: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { callback(progress) }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private fun showPaletteLibrary() {
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

    private fun showReplaceColorDialog() {
        val usedCounts = ColorOps.colorHistogram(project)
        val sources = (usedCounts.entries.sortedByDescending { it.value }.map { it.key } +
                       project.palette).distinct()
        if (sources.isEmpty()) { toast("Aucune couleur à remplacer"); return }
        val toColor = project.primaryColor

        val listView = ListView(this).apply { divider = null }
        listView.adapter = ColorPickerAdapter(sources, usedCounts)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "Touchez la couleur à remplacer  →  #%06X".format(toColor and 0xFFFFFF)
                setTextColor(0xFFE8E8F0.toInt())
                setPadding(48, 16, 48, 4); textSize = 13f
            })
            // Small swatch showing destination
            addView(View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 24).apply {
                    setMargins(48, 0, 48, 8)
                }
                setBackgroundColor(toColor)
            })
            addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f })
        }

        val dlg = AlertDialog.Builder(this)
            .setTitle("Remplacer couleur")
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .create()

        listView.setOnItemClickListener { _, _, which, _ ->
            val from = sources[which]
            dlg.dismiss()
            confirmColorReplace(from, toColor)
        }
        dlg.show()
    }

    private fun confirmColorReplace(from: Int, to: Int) {
        val preview = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(48, 24, 48, 24)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        // From swatch
        preview.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(60, 60).apply { setMargins(0, 0, 8, 0) }
            setBackgroundColor(from)
        })
        preview.addView(TextView(this).apply {
            text = "#%06X".format(from and 0xFFFFFF)
            setTextColor(0xFFE8E8F0.toInt()); textSize = 13f
        })
        preview.addView(TextView(this).apply {
            text = "  →  "
            setTextColor(0xFFA5B4FF.toInt()); textSize = 18f
        })
        preview.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(60, 60).apply { setMargins(8, 0, 8, 0) }
            setBackgroundColor(to)
        })
        preview.addView(TextView(this).apply {
            text = "#%06X".format(to and 0xFFFFFF)
            setTextColor(0xFFE8E8F0.toInt()); textSize = 13f
        })

        AlertDialog.Builder(this)
            .setTitle("Appliquer sur :")
            .setView(preview)
            .setPositiveButton("Toutes les frames") { _, _ ->
                pushUndo()
                val n = ColorOps.replaceColor(project, from, to, allFrames = true)
                binding.canvas.syncFrameBitmap()
                framesAdapter.notifyDataSetChanged()
                binding.timeline.invalidate()
                toast("$n pixels remplacés (toutes frames)")
            }
            .setNeutralButton("Cette frame") { _, _ ->
                pushUndo()
                val n = ColorOps.replaceColor(project, from, to, allFrames = false)
                binding.canvas.syncFrameBitmap()
                framesAdapter.notifyItemChanged(project.currentIndex)
                binding.timeline.invalidate()
                toast("$n pixels remplacés (frame courante)")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** ListView adapter showing color swatch + hex + usage count. */
    inner class ColorPickerAdapter(
        private val colors: List<Int>,
        private val counts: Map<Int, Int>
    ) : android.widget.BaseAdapter() {
        override fun getCount(): Int = colors.size
        override fun getItem(position: Int): Any = colors[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_color_swatch_row, parent, false)
            val c = colors[position]
            v.findViewById<View>(R.id.swatch).setBackgroundColor(c)
            v.findViewById<TextView>(R.id.colorHex).text = "#%06X".format(c and 0xFFFFFF)
            val n = counts[c] ?: 0
            v.findViewById<TextView>(R.id.colorNote).text =
                if (n > 0) "$n px utilisés" else "palette"
            return v
        }
    }

    private fun wireTimeline() {
        binding.timeline.project = project
        binding.timeline.onFrameSelected = { idx ->
            project.currentIndex = idx
            refreshAfterFrameChange()
        }
    }

    // ---- Frames ----
    private fun wireFrames() {
        framesAdapter = FramesAdapter(project,
            onSelect = { idx -> project.currentIndex = idx; refreshAfterFrameChange() },
            onMove = { from, to ->
                val item = project.frames.removeAt(from)
                project.frames.add(to, item)
                if (project.currentIndex == from) project.currentIndex = to
                else if (project.currentIndex == to) project.currentIndex = from
                refreshAfterFrameChange()
            },
            onLongPress = { idx -> showFrameEditDialog(idx) }
        )
        binding.framesList.layoutManager = LinearLayoutManager(this)
        binding.framesList.adapter = framesAdapter
        attachFrameDragHelper()

        binding.btnLayers.setOnClickListener { showLayersDialog() }
        binding.btnLayers.attachHelp("layers")
        binding.btnFrameAdd.attachHelp("frameAdd")
        binding.btnFrameDup.attachHelp("frameDup")
        binding.btnFrameDel.attachHelp("frameDel")
        binding.fpsInput.attachHelp("fps")
        binding.btnPreviewToggle.attachHelp("previewToggle")

        binding.btnFrameAdd.setOnClickListener {
            project.frames.add(Frame(project.width, project.height))
            project.currentIndex = project.frames.size - 1
            refreshAfterFrameChange()
        }
        binding.btnFrameDup.setOnClickListener {
            val copy = project.currentFrame.copy()
            project.frames.add(project.currentIndex + 1, copy)
            project.currentIndex++
            refreshAfterFrameChange()
        }
        binding.btnFrameDel.setOnClickListener {
            if (project.frames.size <= 1) { toast("Au moins une frame requise"); return@setOnClickListener }
            AlertDialog.Builder(this)
                .setMessage(R.string.confirm_delete_frame)
                .setPositiveButton(R.string.delete) { _, _ ->
                    project.frames.removeAt(project.currentIndex)
                    project.currentIndex = project.currentIndex.coerceAtMost(project.frames.size - 1)
                    refreshAfterFrameChange()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun showFrameEditDialog(idx: Int) {
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

    private fun attachFrameDragHelper() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from < 0 || to < 0) return false
                val item = project.frames.removeAt(from)
                project.frames.add(to, item)
                if (project.currentIndex == from) project.currentIndex = to
                else if (project.currentIndex in min(from, to)..max(from, to)) {
                    project.currentIndex += if (from < to) -1 else 1
                }
                framesAdapter.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun isLongPressDragEnabled(): Boolean = true
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.framesList)
    }

    private fun refreshAfterFrameChange() {
        binding.canvas.syncFrameBitmap()
        binding.canvas.syncOnionBitmap()
        framesAdapter.notifyDataSetChanged()
        binding.timeline.invalidate()
        refreshLayersStrip()
    }

    /**
     * Rebuild the inline layers strip in the right panel. Layers that share
     * a [Layer.groupName] are rendered together under a group header whose
     * eye toggles every member's visibility at once. Ungrouped layers render
     * one row each.
     *
     * Tap eye = show/hide layer (or whole group on a header).
     * Tap name = make that layer active.
     */

    // ---- Color ----
    private fun setColor(c: Int) {
        project.primaryColor = c
        binding.canvas.color = c
        project.setColor(c)
        refreshCurrentColorUI()
    }

    private fun refreshCurrentColorUI() {
        binding.currentColorSwatch.setBackgroundColor(project.primaryColor)
        binding.secondaryColorSwatch.setBackgroundColor(project.secondaryColor)
        binding.currentColorHex.text = String.format("#%08X", project.primaryColor)
        paletteAdapter.notifyDataSetChanged()
        recentAdapter.notifyDataSetChanged()
    }

    private fun showColorPicker() {
        val view = layoutInflater.inflate(R.layout.dialog_color_picker, null)
        val preview = view.findViewById<View>(R.id.preview)
        val seekR = view.findViewById<SeekBar>(R.id.seekR)
        val seekG = view.findViewById<SeekBar>(R.id.seekG)
        val seekB = view.findViewById<SeekBar>(R.id.seekB)
        val hex = view.findViewById<EditText>(R.id.hexInput)
        val c = project.primaryColor
        seekR.progress = Color.red(c); seekG.progress = Color.green(c); seekB.progress = Color.blue(c)
        hex.setText(String.format("#%02X%02X%02X", Color.red(c), Color.green(c), Color.blue(c)))
        preview.setBackgroundColor(c)
        val update = {
            val col = Color.rgb(seekR.progress, seekG.progress, seekB.progress) or 0xFF000000.toInt()
            preview.setBackgroundColor(col)
            hex.setText(String.format("#%02X%02X%02X", Color.red(col), Color.green(col), Color.blue(col)))
        }
        listOf(seekR, seekG, seekB).forEach { it.setOnSeekBarChangeListener(simpleSeekListener { _ -> update() }) }
        hex.setOnFocusChangeListener { _, has ->
            if (!has) runCatching {
                val v = Integer.parseInt(hex.text.toString().removePrefix("#"), 16)
                val full = 0xFF000000.toInt() or v
                seekR.progress = Color.red(full); seekG.progress = Color.green(full); seekB.progress = Color.blue(full)
                preview.setBackgroundColor(full)
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Choisir une couleur")
            .setView(view)
            .setPositiveButton("OK") { _, _ ->
                val col = Color.rgb(seekR.progress, seekG.progress, seekB.progress) or 0xFF000000.toInt()
                setColor(col)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- Menu ----
    private fun showMenu() {
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

    private fun showProjectMenu() {
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

    private fun showExportMenu() {
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

    private fun showColorAndPaletteMenu() {
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

    private fun showToolsMenu() {
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

    private fun togglePalmRejection() {
        binding.canvas.palmRejection = !binding.canvas.palmRejection
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putBoolean("palmRejection", binding.canvas.palmRejection).apply()
        val state = if (binding.canvas.palmRejection) "activé" else "désactivé"
        toast("Rejet de la paume $state")
    }

    private fun showPlayModeMenu() {
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


    private val pickSpriteSheet = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) processSpriteSheet(uri)
    }

    private fun importSpriteSheet() {
        // Ask user for cols/rows
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val tv = TextView(this).apply {
            text = "Combien de colonnes × lignes contient le sprite sheet ?\nL'image sera divisée en cellules égales."
            setTextColor(0xFFE8E8F0.toInt()); textSize = 13f
        }
        val rowCols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val etCols = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setText("4"); hint = "Colonnes"
            setTextColor(0xFFE8E8F0.toInt())
        }
        val etRows = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setText("1"); hint = "Lignes"
            setTextColor(0xFFE8E8F0.toInt())
        }
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        etCols.layoutParams = lp; etRows.layoutParams = lp
        rowCols.addView(etCols); rowCols.addView(etRows)
        container.addView(tv); container.addView(rowCols)

        AlertDialog.Builder(this)
            .setTitle("Importer un sprite sheet")
            .setView(container)
            .setPositiveButton("Choisir image") { _, _ ->
                pendingSheetCols = etCols.text.toString().toIntOrNull()?.coerceIn(1, 32) ?: 4
                pendingSheetRows = etRows.text.toString().toIntOrNull()?.coerceIn(1, 32) ?: 1
                pickSpriteSheet.launch(arrayOf("image/*"))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private var pendingSheetCols = 4
    private var pendingSheetRows = 1

    private fun processSpriteSheet(uri: Uri) {
        runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                val bmp = BitmapFactory.decodeStream(input) ?: return@use
                val cols = pendingSheetCols
                val rows = pendingSheetRows
                val cellW = bmp.width / cols
                val cellH = bmp.height / rows
                if (cellW < 1 || cellH < 1) { toast("Cellules trop petites"); return@use }
                pushUndo()
                // Create N frames, one per cell
                val newFrames = mutableListOf<Frame>()
                for (r in 0 until rows) for (c in 0 until cols) {
                    val cellBmp = Bitmap.createBitmap(bmp, c * cellW, r * cellH, cellW, cellH)
                    // Downscale to project resolution
                    val pixels = ImageToPixelArt.downscale(cellBmp, project.width, project.height, BgFitMode.FIT)
                    newFrames.add(Frame(project.width, project.height, pixels))
                    cellBmp.recycle()
                }
                // Replace current frames
                project.frames.clear()
                project.frames.addAll(newFrames)
                project.currentIndex = 0
                framesAdapter.notifyDataSetChanged()
                refreshAfterFrameChange()
                toast("${newFrames.size} frames importées")
            }
        }.onFailure { toast("Erreur: ${it.message}") }
    }

    private fun showTutorial(force: Boolean = false) {
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

    private fun showTutorialPage(pages: List<String>, idx: Int, seenKey: String) {
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

    private fun showNewProjectDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_new_project, null)
        val inputW = view.findViewById<EditText>(R.id.inputW)
        val inputH = view.findViewById<EditText>(R.id.inputH)
        // Default to 64×64 — the canvas size this app is now optimized for.
        inputW.setText("64"); inputH.setText("64")
        fun preset(w: Int, h: Int) { inputW.setText(w.toString()); inputH.setText(h.toString()) }
        // Square presets (≤ 600)
        view.findViewById<View>(R.id.preset16)?.setOnClickListener  { preset(16, 16) }
        view.findViewById<View>(R.id.preset24)?.setOnClickListener  { preset(24, 24) }
        view.findViewById<View>(R.id.preset32)?.setOnClickListener  { preset(32, 32) }
        view.findViewById<View>(R.id.preset48)?.setOnClickListener  { preset(48, 48) }
        view.findViewById<View>(R.id.preset64)?.setOnClickListener  { preset(64, 64) }
        view.findViewById<View>(R.id.preset96)?.setOnClickListener  { preset(96, 96) }
        view.findViewById<View>(R.id.preset128)?.setOnClickListener { preset(128, 128) }
        view.findViewById<View>(R.id.preset256)?.setOnClickListener { preset(256, 256) }
        view.findViewById<View>(R.id.presetGB)?.setOnClickListener   { preset(160, 144) }
        view.findViewById<View>(R.id.presetGBA)?.setOnClickListener  { preset(240, 160) }
        view.findViewById<View>(R.id.presetNES)?.setOnClickListener  { preset(256, 224) }
        view.findViewById<View>(R.id.presetSNES)?.setOnClickListener { preset(256, 240) }
        view.findViewById<View>(R.id.preset320x240)?.setOnClickListener { preset(320, 240) }
        view.findViewById<View>(R.id.preset480x270)?.setOnClickListener { preset(480, 270) }
        view.findViewById<View>(R.id.preset512)?.setOnClickListener     { preset(512, 512) }
        // Hide presets above the 600 cap (640x360, 800x600, 1024).
        view.findViewById<View>(R.id.preset640x360)?.visibility = View.GONE
        view.findViewById<View>(R.id.preset800x600)?.visibility = View.GONE
        view.findViewById<View>(R.id.preset1024)?.visibility = View.GONE
        AlertDialog.Builder(this)
            .setTitle(R.string.new_project)
            .setView(view)
            .setPositiveButton(R.string.create) { _, _ ->
                val w = inputW.text.toString().toIntOrNull()?.coerceIn(1, 600) ?: 64
                val h = inputH.text.toString().toIntOrNull()?.coerceIn(1, 600) ?: 64
                project = Project(width = w, height = h, frames = mutableListOf(Frame(w, h)))
                applyProject()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showResizeDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_new_project, null)
        view.findViewById<EditText>(R.id.inputW).setText(project.width.toString())
        view.findViewById<EditText>(R.id.inputH).setText(project.height.toString())
        view.findViewById<View>(R.id.preset640x360)?.visibility = View.GONE
        view.findViewById<View>(R.id.preset800x600)?.visibility = View.GONE
        view.findViewById<View>(R.id.preset1024)?.visibility = View.GONE
        AlertDialog.Builder(this)
            .setTitle(R.string.resize)
            .setView(view)
            .setPositiveButton(R.string.resize) { _, _ ->
                val w = view.findViewById<EditText>(R.id.inputW).text.toString().toIntOrNull()?.coerceIn(1, 600) ?: return@setPositiveButton
                val h = view.findViewById<EditText>(R.id.inputH).text.toString().toIntOrNull()?.coerceIn(1, 600) ?: return@setPositiveButton
                resizeProject(w, h)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun resizeProject(w: Int, h: Int) {
        val newFrames = project.frames.map { old ->
            val nf = Frame(w, h).apply { tag = old.tag; delayMs = old.delayMs }
            // Preserve all layers
            nf.layers.clear()
            val cw = minOf(old.width, w); val ch = minOf(old.height, h)
            old.layers.forEach { oldLayer ->
                val newLayer = Layer(w, h, oldLayer.name).apply {
                    visible = oldLayer.visible; opacity = oldLayer.opacity
                }
                for (y in 0 until ch) for (x in 0 until cw) {
                    newLayer.pixels[y * w + x] = oldLayer.pixels[y * old.width + x]
                }
                nf.layers.add(newLayer)
            }
            nf.activeLayer = old.activeLayer.coerceAtMost(nf.layers.size - 1)
            nf
        }.toMutableList()
        project.width = w; project.height = h
        project.frames.clear()
        project.frames.addAll(newFrames)
        applyProject()
    }

    private fun applyProject() {
        binding.canvas.project = project
        framesAdapter = FramesAdapter(project,
            onSelect = { idx -> project.currentIndex = idx; refreshAfterFrameChange() },
            onMove = { from, to ->
                val item = project.frames.removeAt(from)
                project.frames.add(to, item)
                if (project.currentIndex == from) project.currentIndex = to
                refreshAfterFrameChange()
            },
            onLongPress = { idx -> showFrameEditDialog(idx) }
        )
        binding.framesList.adapter = framesAdapter
        paletteAdapter = SwatchAdapter(project.palette, { setColor(it) }, { it == binding.canvas.color })
        recentAdapter = SwatchAdapter(project.recentColors, { setColor(it) }, { it == binding.canvas.color })
        binding.paletteList.adapter = paletteAdapter
        binding.recentList.adapter = recentAdapter
        binding.fpsInput.setText(project.fps.toString())
        binding.onionRange.progress = project.onionRange
        binding.cbPixelPerfect.isChecked = project.pixelPerfect
        binding.btnSymmetry.isSelected = project.symmetry != SymmetryAxis.NONE
        binding.canvas.color = project.primaryColor
        refreshCurrentColorUI()
        updateBgFitButtonLabel()
        binding.timeline.project = project
        binding.timeline.invalidate()
        undoStack.clear(); redoStack.clear()
        refreshLayersStrip()
    }

    // ---- Save / Load ----
    private fun saveProject() {
        val input = EditText(this).apply {
            setText(project.name)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.project_name)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                project.name = input.text.toString().ifBlank { "Sans titre" }
                ProjectStorage.save(this, project)
                isDirty = false; updateTitleDirty()
                // Expose the quick-save disk button now that the project has a name.
                binding.btnQuickSave.visibility = View.VISIBLE
                toast(getString(R.string.saved))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- Top palette ----
    /**
     * Populate the inline top palette (above the canvas) with one button per
     * entry. Replaces modal sub-menu dialogs for Décor / Effets / Magie.
     * Tapping a button runs its action and closes the palette.
     */
    private fun showTopPalette(title: String, entries: List<Pair<String, () -> Unit>>) {
        val row = binding.topPaletteRow
        row.removeAllViews()
        // Title chip on the left
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
        // Close ✕
        row.addView(Button(this).apply {
            text = "✕"; textSize = 14f; isAllCaps = false
            setPadding(16, 8, 16, 8)
            setOnClickListener { closeTopPalette() }
        })
        binding.topPalette.visibility = View.VISIBLE
    }

    private fun closeTopPalette() {
        binding.topPalette.visibility = View.GONE
        binding.topPaletteRow.removeAllViews()
    }

    private fun openDecorPalette() {
        showTopPalette("🏞️ Décor", listOf(
            "Statique → frame" to { pickAndGenerateStaticDecor(replaceFrame = true) },
            "Statique → fond" to { pickAndGenerateStaticDecor(replaceFrame = false) },
            "🎬 Animé 4 frames" to { pickAndGenerateAnimatedDecor(frameCount = 4) },
            "🎬 Animé 8 frames" to { pickAndGenerateAnimatedDecor(frameCount = 8) }
        ))
    }

    private fun openEffectsPalette() {
        showTopPalette("✨ Effets", listOf(
            "Particules" to { showParticlesDialog() },
            "Filtres image" to { showFiltersMenu() }
        ))
    }

    private fun openMagicPalette() {
        showTopPalette("🪄 Générer", listOf(
            "🏞️ Décor" to { openDecorPalette() },
            "✨ Effets" to { openEffectsPalette() },
            "🔀 Tween 2 frames" to { showTweenDialog() }
        ))
    }

    /**
     * Show one tab section of the right panel and hide the others.
     *  0 = 🎨 Couleurs    → palette + recent
     *  1 = 🧱 Outils      → BG / opacity / onion / brush / dither / sketch
     *  2 = 🎬 Animation   → zoom + frames + layers strip + speed + preview
     */
    private fun switchRightTab(idx: Int) {
        binding.groupColors.visibility = if (idx == 0) View.VISIBLE else View.GONE
        binding.groupBg.visibility     = if (idx == 1) View.VISIBLE else View.GONE
        binding.groupZoom.visibility   = if (idx == 2) View.VISIBLE else View.GONE
        binding.groupFrames.visibility = if (idx == 2) View.VISIBLE else View.GONE
        // Visually mark the active tab
        binding.tabColors.isSelected = idx == 0
        binding.tabLayers.isSelected = idx == 1
        binding.tabAnim.isSelected   = idx == 2
    }

    /** One-tap save reusing the existing project name (no dialog). */
    private fun quickSaveProject() {
        ProjectStorage.save(this, project)
        isDirty = false; updateTitleDirty()
        toast("Sauvegardé : ${project.name}")
    }

    private fun showLoadDialog() {
        val list = ProjectStorage.list(this)
        if (list.isEmpty()) { toast(getString(R.string.no_projects)); return }
        // Build a custom listview with thumbnails + name + meta
        val listView = ListView(this).apply { divider = null }
        val items = list.map { obj ->
            ProjectListItem(
                id = obj.optString("id"),
                name = obj.optString("name", "Sans titre"),
                width = obj.optInt("width"),
                height = obj.optInt("height"),
                frameCount = obj.optJSONArray("frames")?.length() ?: 0,
                updatedAt = obj.optLong("updatedAt", 0)
            )
        }
        listView.adapter = ProjectListAdapter(this, items)

        val dlg = AlertDialog.Builder(this)
            .setTitle(R.string.saved_projects)
            .setView(listView)
            .setNeutralButton("Supprimer…") { _, _ -> showDeleteDialog(list) }
            .setNegativeButton(R.string.cancel, null)
            .create()
        listView.setOnItemClickListener { _, _, which, _ ->
            ProjectStorage.load(this, items[which].id)?.let {
                project = it
                applyProject()
                binding.btnQuickSave.visibility = View.VISIBLE
                toast("Chargé")
            }
            dlg.dismiss()
        }
        dlg.show()
    }

    data class ProjectListItem(
        val id: String, val name: String, val width: Int, val height: Int,
        val frameCount: Int, val updatedAt: Long
    )

    inner class ProjectListAdapter(
        ctx: android.content.Context, val items: List<ProjectListItem>
    ) : android.widget.BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_project, parent, false)
            val item = items[position]
            v.findViewById<TextView>(R.id.projectName).text = item.name
            val date = java.text.SimpleDateFormat("dd/MM/yy HH:mm", java.util.Locale.FRENCH)
                .format(java.util.Date(item.updatedAt))
            v.findViewById<TextView>(R.id.projectMeta).text =
                "${item.width}×${item.height} • ${item.frameCount} image(s) • $date"
            val iv = v.findViewById<ImageView>(R.id.projectThumb)
            val thumb = ProjectStorage.thumbnailFile(this@MainActivity, item.id)
            if (thumb != null) {
                val bmp = BitmapFactory.decodeFile(thumb.absolutePath)
                val drawable = if (bmp != null) {
                    android.graphics.drawable.BitmapDrawable(resources, bmp).apply { isFilterBitmap = false }
                } else null
                iv.setImageDrawable(drawable)
            } else iv.setImageDrawable(null)
            return v
        }
    }

    private fun showDeleteDialog(list: List<org.json.JSONObject>) {
        if (list.isEmpty()) { toast("Aucun projet"); return }
        val labels = list.map { it.optString("name") }.toTypedArray()
        val checked = BooleanArray(list.size)
        AlertDialog.Builder(this)
            .setTitle("Supprimer des projets (coche tout ce qui doit partir)")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Supprimer") { _, _ ->
                val toRemove = list.filterIndexed { i, _ -> checked[i] }
                if (toRemove.isEmpty()) { toast("Rien de coché"); return@setPositiveButton }
                // Confirmation since this is destructive and bulk
                AlertDialog.Builder(this)
                    .setTitle("Confirmer")
                    .setMessage("Supprimer ${toRemove.size} projet(s) ?\n" +
                        toRemove.joinToString("\n") { "• " + it.optString("name") })
                    .setPositiveButton("Oui, supprimer") { _, _ ->
                        toRemove.forEach { ProjectStorage.delete(this, it.optString("id")) }
                        toast("${toRemove.size} projet(s) supprimé(s)")
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNeutralButton("Tout cocher") { _, _ ->
                showDeleteDialogAllChecked(list)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeleteDialogAllChecked(list: List<org.json.JSONObject>) {
        val labels = list.map { it.optString("name") }.toTypedArray()
        val checked = BooleanArray(list.size) { true }
        AlertDialog.Builder(this)
            .setTitle("Supprimer des projets")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Supprimer") { _, _ ->
                val toRemove = list.filterIndexed { i, _ -> checked[i] }
                if (toRemove.isEmpty()) return@setPositiveButton
                AlertDialog.Builder(this)
                    .setTitle("Confirmer")
                    .setMessage("Supprimer ${toRemove.size} projet(s) ?")
                    .setPositiveButton("Oui") { _, _ ->
                        toRemove.forEach { ProjectStorage.delete(this, it.optString("id")) }
                        toast("${toRemove.size} projet(s) supprimé(s)")
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- Background image ----
    private fun loadBgImage(uri: Uri) {
        runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                val raw = BitmapFactory.decodeStream(input) ?: return@use
                // Cap the working bitmap to canvas × 4 so a huge photo doesn't
                // sit in memory forever — keeps a generous resolution for the
                // pixelize pipeline without burning ~10 MB on a 4000×3000 phone
                // photo.
                val capW = (project.width * 4).coerceAtLeast(512)
                val capH = (project.height * 4).coerceAtLeast(512)
                val scaled = if (raw.width > capW || raw.height > capH) {
                    val ratio = minOf(capW.toFloat() / raw.width, capH.toFloat() / raw.height)
                    val tw = (raw.width * ratio).toInt().coerceAtLeast(1)
                    val th = (raw.height * ratio).toInt().coerceAtLeast(1)
                    val s = Bitmap.createScaledBitmap(raw, tw, th, true)
                    if (s !== raw) raw.recycle()
                    s
                } else raw
                binding.canvas.bgBitmap = scaled
                project.bgFit = BgFitMode.FIT
                binding.canvas.invalidate()
                updateBgFitButtonLabel()
                askBackgroundRemovalIntensity(scaled)
            }
        }
    }

    /**
     * On image load: ask the user how aggressively to remove the background
     * (or skip). Tolerance 0 = nothing removed, 100 = very aggressive.
     */
    private fun askBackgroundRemovalIntensity(raw: Bitmap) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 24)
        }
        container.addView(TextView(this).apply {
            text = "Suppression du fond ?\n\nGlissez le curseur pour choisir l'intensité. " +
                "Faible = ne retire que les zones très uniformes aux 4 coins. " +
                "Fort = retire plus, mais risque de manger le sujet."
            setTextColor(0xFFE8E8F0.toInt()); textSize = 12f
        })
        val seek = SeekBar(this).apply { max = 100; progress = 25 }
        val label = TextView(this).apply {
            text = "Tolérance : 25"
            setTextColor(0xFFA5B4FF.toInt()); textSize = 14f
        }
        seek.setOnSeekBarChangeListener(simpleSeekListener { v -> label.text = "Tolérance : $v" })
        container.addView(label); container.addView(seek)
        AlertDialog.Builder(this)
            .setTitle("🪄 Charger une image")
            .setView(container)
            .setPositiveButton("Appliquer") { _, _ ->
                val tol = seek.progress
                if (tol == 0) {
                    askBgFitModeThenAction(raw)
                } else {
                    lifecycleScope.launch {
                        val cleaned = withContext(Dispatchers.Default) {
                            BackgroundRemoval.removeBackground(raw, tolerance = tol, featherEdges = true)
                        }
                        binding.canvas.bgBitmap = cleaned
                        binding.canvas.invalidate()
                        toast("Fond enlevé (tolérance $tol)")
                        askBgFitModeThenAction(cleaned)
                    }
                }
            }
            .setNeutralButton("Sans suppression") { _, _ -> askBgFitModeThenAction(raw) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askBgFitModeThenAction(bmp: Bitmap) {
        val modes = BgFitMode.values()
        val labels = modes.map { it.label + " - " + when (it) {
            BgFitMode.FIT -> "garde l'image entière (bandes vides)"
            BgFitMode.COVER -> "remplit le canvas (rogne les bords)"
            BgFitMode.STRETCH -> "déforme pour remplir exactement"
        } }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Adaptation de l'image (aperçu en direct)")
            .setSingleChoiceItems(labels, project.bgFit.ordinal) { _, which ->
                project.bgFit = modes[which]
                updateBgFitButtonLabel()
                binding.canvas.invalidate()
            }
            .setPositiveButton("Suivant →") { _, _ ->
                showImageImportOptions(bmp)
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                // User cancelled: keep image as bg, no further processing
            }
            .show()
    }

    private fun showImageImportOptions(bmp: Bitmap) {
        val items = arrayOf(
            "Garder uniquement comme image de fond",
            "🪄 Supprimer le fond automatiquement",
            "🎨 Pixeliser intelligent (canvas actuel)",
            "🎯 Pixeliser à une résolution choisie (32 / 48 / 64 / …)",
            "Extraire palette (16 couleurs)"
        )
        AlertDialog.Builder(this)
            .setTitle("Que faire avec cette image ?")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {} // keep as bg only
                    1 -> removeBackgroundDialog(bmp)
                    2 -> showSmartPixelizeStyles(bmp)
                    3 -> showTargetResolutionPicker(bmp)
                    4 -> extractPaletteFromBg(bmp)
                }
            }
            .setNegativeButton("← Retour adaptation") { _, _ -> askBgFitModeThenAction(bmp) }
            .show()
    }

    /**
     * Let the user pick a target canvas resolution, then a pixelize style.
     * Smaller resolutions => simpler / blockier output. Larger => more detail
     * preserved. The project canvas is resized to match, then SmartPixelize
     * runs on the source bitmap to fill the new frame.
     */
    private fun showTargetResolutionPicker(bmp: Bitmap) {
        val sizes = intArrayOf(16, 24, 32, 48, 64, 96, 128, 192)
        val labels = sizes.map {
            val tag = when {
                it <= 24 -> "très simple"
                it <= 48 -> "simple"
                it <= 96 -> "détaillé"
                else -> "très détaillé"
            }
            "${it}×${it}  ($tag)"
        }.toTypedArray()
        // Custom-view dialog so the size buttons always render — bypassing the
        // AlertDialog setItems / setMessage incompatibility.
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }
        container.addView(TextView(this).apply {
            text = "Choisissez la résolution. Plus elle est petite, plus le rendu est simplifié. " +
                "Le canvas sera redimensionné automatiquement."
            setTextColor(0xFFE8E8F0.toInt()); textSize = 13f
            setPadding(16, 8, 16, 24)
        })
        val dialog = AlertDialog.Builder(this)
            .setTitle("🎯 Résolution cible")
            .setView(ScrollView(this).apply { addView(container) })
            .setNegativeButton(R.string.cancel, null)
            .create()
        sizes.forEachIndexed { i, sz ->
            container.addView(Button(this).apply {
                text = labels[i]
                textSize = 16f
                isAllCaps = false
                setPadding(24, 24, 24, 24)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 8, 0, 8) }
                setOnClickListener {
                    dialog.dismiss()
                    showStyleForTargetResolution(bmp, sz)
                }
            })
        }
        dialog.show()
    }

    private fun showStyleForTargetResolution(bmp: Bitmap, size: Int) {
        val styles = SmartPixelize.Style.values()
        val labels = styles.map { it.displayName }.toTypedArray()
        // PRO is index 0 and recommended — pre-select it.
        AlertDialog.Builder(this)
            .setTitle("Style à appliquer en ${size}×${size}")
            .setSingleChoiceItems(labels, 0) { dlg, which ->
                pixelizeAtTargetResolution(bmp, size, styles[which])
                dlg.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pixelizeAtTargetResolution(bmp: Bitmap, size: Int, style: SmartPixelize.Style) {
        pushUndo()
        // Resize project canvas to the target size (square — 32×32, 48×48, …)
        if (project.width != size || project.height != size) resizeProject(size, size)
        // Now run SmartPixelize against the current (resized) canvas dimensions.
        val (pixels, palette) = SmartPixelize.pixelize(bmp, project.width, project.height, project.bgFit, style)
        pixels.copyInto(project.currentFrame.pixels)
        project.palette.clear()
        project.palette.addAll(palette.toList())
        paletteAdapter.notifyDataSetChanged()
        binding.canvas.syncFrameBitmap()
        framesAdapter.notifyItemChanged(project.currentIndex)
        AlertDialog.Builder(this)
            .setTitle("Pixelisé en ${size}×${size}")
            .setMessage("Style ${style.displayName} • palette ${palette.size} couleurs. Essayer un autre style ou une autre résolution ?")
            .setPositiveButton("Autre style") { _, _ -> showStyleForTargetResolution(bmp, size) }
            .setNeutralButton("Autre résolution") { _, _ -> showTargetResolutionPicker(bmp) }
            .setNegativeButton("Garder", null)
            .show()
    }

    private fun removeBackgroundDialog(originalBmp: Bitmap) {
        // Show a slider for tolerance (default 40)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 24) }
        container.addView(TextView(this).apply {
            text = "Tolérance de couleur (0-100). Plus haut = plus de fond supprimé mais risque de manger le sujet."
            setTextColor(0xFFE8E8F0.toInt()); textSize = 12f
        })
        val seek = SeekBar(this).apply { max = 100; progress = 40 }
        val label = TextView(this).apply {
            text = "Tolérance : 40"
            setTextColor(0xFFA5B4FF.toInt()); textSize = 14f
        }
        seek.setOnSeekBarChangeListener(simpleSeekListener { v -> label.text = "Tolérance : $v" })
        container.addView(label); container.addView(seek)
        AlertDialog.Builder(this)
            .setTitle("Supprimer le fond")
            .setView(container)
            .setPositiveButton("Appliquer") { _, _ ->
                applyBackgroundRemoval(originalBmp, seek.progress)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyBackgroundRemoval(bmp: Bitmap, tolerance: Int) {
        lifecycleScope.launch {
            val cleaned = withContext(Dispatchers.Default) {
                BackgroundRemoval.removeBackground(bmp, tolerance = tolerance, featherEdges = true)
            }
            binding.canvas.bgBitmap = cleaned
            binding.canvas.invalidate()
            toast("Fond supprimé (tolérance $tolerance)")
            // Re-open the post-import dialog so user can pick what to do next
            showImageImportOptions(cleaned)
        }
    }

    private fun showSmartPixelizeStyles(bmp: Bitmap) {
        val styles = SmartPixelize.Style.values()
        val labels = styles.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Style de pixelisation")
            .setItems(labels) { _, which ->
                smartPixelizeWithStyle(bmp, styles[which])
            }
            .show()
    }

    private fun smartPixelizeWithStyle(bmp: Bitmap, style: SmartPixelize.Style) {
        pushUndo()
        val (pixels, palette) = SmartPixelize.pixelize(bmp, project.width, project.height, project.bgFit, style)
        pixels.copyInto(project.currentFrame.pixels)
        // Auto-replace palette with the extracted one (it's been tuned to the result)
        project.palette.clear()
        project.palette.addAll(palette.toList())
        paletteAdapter.notifyDataSetChanged()
        binding.canvas.syncFrameBitmap()
        framesAdapter.notifyItemChanged(project.currentIndex)
        // Offer re-run with different style
        AlertDialog.Builder(this)
            .setTitle("Pixelisation « ${style.displayName} »")
            .setMessage("Frame remplie + palette ${palette.size} couleurs extraite. Essayer un autre style ?")
            .setPositiveButton("Autre style") { _, _ -> showSmartPixelizeStyles(bmp) }
            .setNegativeButton("Garder", null)
            .show()
    }

    private fun extractPaletteFromBg(bmp: Bitmap) {
        val downscaled = ImageToPixelArt.downscale(bmp, project.width, project.height, project.bgFit)
        val pal = ImageToPixelArt.extractPalette(downscaled, 16)
        if (pal.isEmpty()) { toast("Aucune couleur extraite"); return }
        project.palette.clear()
        project.palette.addAll(pal.toList())
        paletteAdapter.notifyDataSetChanged()
        toast("${pal.size} couleurs extraites")
    }


    // ---- Animation playback ----
    private fun togglePlay() {
        if (isPlaying) stopPlay() else startPlay()
    }

    private var pingPongForward = true

    private fun startPlay() {
        if (project.frames.size < 2) { toast("Ajoutez au moins 2 frames"); return }
        isPlaying = true
        savedFrameIdx = project.currentIndex
        playIdx = when (project.playMode) {
            PlayMode.REVERSE -> project.frames.size - 1
            else -> 0
        }
        pingPongForward = true
        binding.btnPlay.setImageResource(R.drawable.ic_stop)
        val r = object : Runnable {
            override fun run() {
                if (!isPlaying) return
                project.currentIndex = playIdx
                binding.canvas.syncFrameBitmap()
                val delay = (project.delayForFrame(playIdx) / previewSpeed).toLong().coerceAtLeast(20L)
                playIdx = nextPlayIndex(playIdx, project.frames.size, project.playMode)
                if (playIdx < 0) { stopPlay(); return }
                animHandler.postDelayed(this, delay)
            }
        }
        animTimer = r
        animHandler.post(r)
    }

    private fun nextPlayIndex(current: Int, size: Int, mode: PlayMode): Int {
        return when (mode) {
            PlayMode.LOOP -> (current + 1) % size
            PlayMode.REVERSE -> if (current - 1 < 0) size - 1 else current - 1
            PlayMode.ONCE -> if (current + 1 >= size) -1 else current + 1
            PlayMode.PING_PONG -> {
                if (pingPongForward) {
                    if (current + 1 >= size) { pingPongForward = false; (current - 1).coerceAtLeast(0) }
                    else current + 1
                } else {
                    if (current - 1 < 0) { pingPongForward = true; (current + 1).coerceAtMost(size - 1) }
                    else current - 1
                }
            }
        }
    }

    private fun stopPlay() {
        isPlaying = false
        animTimer?.let { animHandler.removeCallbacks(it) }
        animTimer = null
        binding.btnPlay.setImageResource(R.drawable.ic_play)
        project.currentIndex = savedFrameIdx
        binding.canvas.syncFrameBitmap()
        binding.canvas.syncOnionBitmap()
    }

    internal fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** Attach a long-press listener that shows the help entry [helpKey] for this view. */
    private fun View.attachHelp(helpKey: String) {
        setOnLongClickListener {
            showHelpFor(helpKey)
            true
        }
    }

    private fun showHelpFor(key: String) {
        val entry = ToolHelp.get(key) ?: return
        AlertDialog.Builder(this)
            .setTitle(entry.title)
            .setMessage(ToolHelp.format(entry))
            .setPositiveButton("Compris", null)
            .show()
    }
}
