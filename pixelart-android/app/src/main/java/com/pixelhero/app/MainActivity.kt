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

    private lateinit var binding: ActivityMainBinding
    private lateinit var project: Project
    private lateinit var paletteAdapter: SwatchAdapter
    private lateinit var recentAdapter: SwatchAdapter
    private lateinit var framesAdapter: FramesAdapter

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
                val delay = (1000L / project.fps.coerceAtLeast(1)).coerceAtLeast(50L)
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
                ProjectStorage.save(this@MainActivity, project)
                runOnUiThread { isDirty = false; updateTitleDirty() }
                autosaveHandler.postDelayed(this, 30_000L)
            }
        }
        autosaveRunnable = r
        autosaveHandler.postDelayed(r, 30_000L)
    }

    override fun onPause() {
        super.onPause()
        if (isPlaying) stopPlay()
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
        binding.canvas.onStrokeStart = { pushUndo() }
        binding.canvas.onProjectChanged = {
            if (project.frames.indices.contains(project.currentIndex))
                framesAdapter.notifyItemChanged(project.currentIndex)
        }
        binding.canvas.onColorPicked = { c -> setColor(c) }
    }

    private fun pushUndo() {
        undoStack.addLast(UndoSnapshot(project.currentIndex, project.currentFrame.pixels.copyOf()))
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
            Triple(binding.toolPicker,    Tool.PICKER,    "picker"),
            Triple(binding.toolLine,      Tool.LINE,      "line"),
            Triple(binding.toolRect,      Tool.RECT,      "rect"),
            Triple(binding.toolRectFill,  Tool.RECT_FILL, "rectfill"),
            Triple(binding.toolSelect,    Tool.SELECT,    "select"),
            Triple(binding.toolWand,      Tool.WAND,      "wand"),
            Triple(binding.toolMove,      Tool.MOVE,      "move")
        )
        binding.toolPencil.isSelected = true
        tools.forEach { (btn, tool, helpKey) ->
            btn.setOnClickListener {
                tools.forEach { (b, _, _) -> b.isSelected = false }
                btn.isSelected = true
                binding.canvas.tool = tool
                if (tool == Tool.SELECT || tool == Tool.WAND) showSelectionActions()
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
        items.add(getString(R.string.copy))
        items.add(getString(R.string.cut))
        if (clipboardPixels != null) items.add(getString(R.string.paste))
        items.add(getString(R.string.flip_h))
        items.add(getString(R.string.flip_v))
        items.add("Recadrer le canvas à cette sélection")
        items.add("Valider / Désélectionner")
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.tool_select))
            .setItems(items.toTypedArray()) { _, which ->
                val sel = binding.canvas.selection
                when (items[which]) {
                    getString(R.string.copy) -> {
                        val pair = binding.canvas.copySelectionToClipboard()
                            ?: liftAndCopy()
                        pair?.let {
                            clipboardW = it.first
                            clipboardPixels = it.second
                            toast("Copié")
                        }
                    }
                    getString(R.string.cut) -> {
                        if (sel.floating == null) liftAndCopy()
                        binding.canvas.copySelectionToClipboard()?.let {
                            clipboardW = it.first; clipboardPixels = it.second
                        }
                        binding.canvas.cutSelectionToClipboard()
                        binding.canvas.invalidate()
                        toast("Coupé")
                    }
                    getString(R.string.paste) -> {
                        val pixels = clipboardPixels ?: return@setItems
                        pushUndo()
                        binding.canvas.pasteClipboard(clipboardW, pixels, sel.xMin.coerceAtLeast(0), sel.yMin.coerceAtLeast(0))
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
        binding.btnMenu.setOnClickListener { showMenu() }
        binding.btnSymmetry.setOnClickListener { showSymmetryMenu() }
        binding.btnMagic.setOnClickListener { showSmartGenerator() }
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
        val f = project.frames.getOrNull(snap.frameIndex) ?: return
        redoStack.addLast(UndoSnapshot(snap.frameIndex, f.pixels.copyOf()))
        snap.pixels.copyInto(f.pixels)
        project.currentIndex = snap.frameIndex
        refreshAfterFrameChange()
    }

    private fun doRedo() {
        if (redoStack.isEmpty()) return
        val snap = redoStack.removeLast()
        val f = project.frames.getOrNull(snap.frameIndex) ?: return
        undoStack.addLast(UndoSnapshot(snap.frameIndex, f.pixels.copyOf()))
        snap.pixels.copyInto(f.pixels)
        project.currentIndex = snap.frameIndex
        refreshAfterFrameChange()
    }

    private fun showSymmetryMenu() {
        val items = arrayOf(
            getString(R.string.symmetry_none),
            getString(R.string.symmetry_h),
            getString(R.string.symmetry_v),
            getString(R.string.symmetry_both),
            "Rotation 4× (kaléidoscope)",
            "🦴 Miroir autour du squelette"
        )
        val current = when (project.symmetry) {
            SymmetryAxis.NONE -> 0
            SymmetryAxis.HORIZONTAL -> 1
            SymmetryAxis.VERTICAL -> 2
            SymmetryAxis.BOTH -> 3
            SymmetryAxis.ROTATE_4 -> 4
            SymmetryAxis.SKELETON_H -> 5
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.symmetry)
            .setSingleChoiceItems(items, current) { dlg, which ->
                project.symmetry = when (which) {
                    1 -> SymmetryAxis.HORIZONTAL
                    2 -> SymmetryAxis.VERTICAL
                    3 -> SymmetryAxis.BOTH
                    4 -> SymmetryAxis.ROTATE_4
                    5 -> SymmetryAxis.SKELETON_H
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
            "🧍 Personnage IA (1 vue / 6 vues / aléatoire)",
            "🎬 Animation IA (8 actions, frame par frame)",
            "🏞️ Décor & scène",
            "✨ Effets (particules, filtres)",
            "🦴 Squelette & tween (fine-tuning avancé)"
        )
        AlertDialog.Builder(this)
            .setTitle("Générer…")
            .setItems(categories) { _, which ->
                when (which) {
                    0 -> showCharacterMenu()
                    1 -> showAnimationGenerator()
                    2 -> showDecorAndElementMenu()
                    3 -> showEffectsMenu()
                    4 -> showSkeletonAndTweenMenu()
                }
            }
            .show()
    }

    private fun showSkeletonAndTweenMenu() {
        val items = arrayOf(
            "Configurer le squelette",
            "Animation depuis squelette (utiliser AI pour le sprite)",
            "Interpolation entre 2 poses",
            "🚶 Mode de déplacement (marche/flottement/hover)",
            "🔀 Tween entre 2 frames (pixel blend)"
        )
        AlertDialog.Builder(this).setTitle("🦴 Squelette & tween")
            .setItems(items) { _, w ->
                when (w) {
                    0 -> showSkeletonEditor()
                    1 -> showSkeletalAnimationDialog()
                    2 -> showPoseTweenDialog()
                    3 -> showLocomotionPicker()
                    4 -> showTweenDialog()
                }
            }.show()
    }

    private fun showCharacterMenu() {
        val items = arrayOf(
            "🎬 IA: 6 vues + pixelisation + fond enlevé (1 clic)",
            "☁️ IA cloud (1 vue seule)",
            "Template de pose (silhouette de guide)",
            "Personnage aléatoire (rapide, hors ligne)"
        )
        AlertDialog.Builder(this).setTitle("🧍 Personnage")
            .setItems(items) { _, w ->
                when (w) {
                    0 -> showAIFullCharacterDialog()
                    1 -> showAICloudGeneratorDialog()
                    2 -> showPoseTemplates()
                    3 -> showProceduralCharacterDialog()
                }
            }.show()
    }

    private fun showDecorAndElementMenu() {
        val items = arrayOf("Décor / scène (procédural ou IA)", "Élément animé (flambeau, etc.)")
        AlertDialog.Builder(this).setTitle("🏞️ Décor & éléments")
            .setItems(items) { _, w ->
                when (w) { 0 -> showDecorGenerator(); 1 -> showAnimatedElementGenerator() }
            }.show()
    }

    private fun showEffectsMenu() {
        val items = arrayOf("✨ Particules (10 types)", "Filtres image")
        AlertDialog.Builder(this).setTitle("✨ Effets")
            .setItems(items) { _, w ->
                when (w) { 0 -> showParticlesDialog(); 1 -> showFiltersMenu() }
            }.show()
    }

    private fun showAICloudGeneratorDialog() {
        val styles = AIService.Style.values()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 24)
        }
        container.addView(TextView(this).apply {
            text = "Génération via IA en ligne. Le résultat sera téléchargé en image puis pixelisable. " +
                "Pollinations.ai est gratuit et anonyme. DALL-E 3 demande une clé OpenAI (payant)."
            setTextColor(0xFFE8E8F0.toInt()); textSize = 12f
        })
        container.addView(TextView(this).apply {
            text = "\nStyle"
            setTextColor(0xFFA5B4FF.toInt()); textSize = 13f
        })
        val styleSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                styles.map { it.displayName })
            setSelection(1) // KGC default
        }
        container.addView(styleSpinner)
        container.addView(TextView(this).apply {
            text = "\nDescription (anglais conseillé)"
            setTextColor(0xFFA5B4FF.toInt()); textSize = 13f
        })
        val promptEt = EditText(this).apply {
            hint = "knight with silver armor, blue cape, holding a sword"
            setText("knight with silver armor, blue cape, golden crown, holding a sword")
            setTextColor(0xFFE8E8F0.toInt())
            minLines = 2
        }
        container.addView(promptEt)
        container.addView(TextView(this).apply {
            text = "\nFournisseur"
            setTextColor(0xFFA5B4FF.toInt()); textSize = 13f
        })
        val savedKey = AIService.loadApiKey(this) ?: ""
        val providerLabels = arrayOf(
            "Pollinations.ai (gratuit)",
            "OpenAI (clé requise, auto: gpt-image-1 → dall-e-3)"
        )
        val providerSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item, providerLabels)
        }
        container.addView(providerSpinner)
        val keyLabel = TextView(this).apply {
            text = "\nClé OpenAI (stockée localement)"
            setTextColor(0xFFA5B4FF.toInt()); textSize = 13f
            visibility = View.GONE
        }
        val keyEt = EditText(this).apply {
            hint = "sk-..."
            setText(savedKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(0xFFE8E8F0.toInt())
            visibility = View.GONE
        }
        container.addView(keyLabel); container.addView(keyEt)
        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val show = if (pos == 1) View.VISIBLE else View.GONE
                keyLabel.visibility = show; keyEt.visibility = show
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        AlertDialog.Builder(this)
            .setTitle("☁️ Génération IA cloud")
            .setView(container)
            .setPositiveButton("Générer") { _, _ ->
                val style = styles[styleSpinner.selectedItemPosition]
                val rawPrompt = promptEt.text.toString().trim()
                if (rawPrompt.isBlank()) { toast("Entrez une description"); return@setPositiveButton }
                val finalPrompt = AIService.applyStyle(rawPrompt, style)
                val useOpenAI = providerSpinner.selectedItemPosition == 1
                val apiKey = keyEt.text.toString().trim()
                if (useOpenAI) {
                    if (apiKey.isBlank()) { toast("Clé OpenAI requise"); return@setPositiveButton }
                    AIService.saveApiKey(this, apiKey)
                }
                runAICloudGeneration(finalPrompt, useOpenAI, apiKey)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runAICloudGeneration(prompt: String, useOpenAI: Boolean, apiKey: String) {
        val progress = AlertDialog.Builder(this)
            .setTitle("Génération en cours…")
            .setMessage("Téléchargement de l'image IA puis suppression du fond. 10-60 s.")
            .setCancelable(false)
            .show()
        val w = project.width; val h = project.height
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                if (useOpenAI) AIService.generateOpenAI(prompt, apiKey)
                else AIService.generatePollinations(prompt)
            }
            if (bmp == null) {
                progress.dismiss()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Échec de génération")
                    .setMessage("Erreur : ${AIService.lastError ?: "inconnue"}\n\n" +
                        "Causes possibles :\n• Pas de connexion internet\n" +
                        "• Serveur surchargé (réessayez)\n" +
                        "• Clé OpenAI invalide / crédit épuisé / dépassement de quota\n" +
                        "• Prompt refusé par le filtre de contenu")
                    .setPositiveButton("Réessayer") { _, _ -> showAICloudGeneratorDialog() }
                    .setNegativeButton("OK", null)
                    .show()
                return@launch
            }
            // Auto-remove background on the source bitmap before showing it.
            val cleanedSource = withContext(Dispatchers.Default) {
                BackgroundRemoval.removeBackground(bmp, tolerance = 55, featherEdges = true)
            }
            progress.dismiss()
            binding.canvas.bgBitmap = cleanedSource
            binding.canvas.invalidate()
            toast("Image IA téléchargée, fond enlevé — choisissez maintenant")
            askBgFitModeThenAction(cleanedSource)
        }
    }

    private fun showAIFullCharacterDialog() {
        val charStyles = AIService.Style.values().filter { !it.isDecor && it != AIService.Style.FREE }
        val pixStyles = SmartPixelize.Style.values()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 24)
        }
        container.addView(TextView(this).apply {
            text = "1 clic = 6 frames (face/dos/profil G/D/3-quart G/D) générées par IA, pixelisées et fond enlevé. " +
                "Avec OpenAI : VOTRE sprite courant est envoyé comme référence pour que le perso reste identique. " +
                "Avec Pollinations : seul le texte est utilisé."
            setTextColor(0xFFE8E8F0.toInt()); textSize = 12f
        })
        container.addView(TextView(this).apply {
            text = "\nStyle de personnage"; setTextColor(0xFFA5B4FF.toInt()); textSize = 13f
        })
        val styleSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                charStyles.map { it.displayName })
        }
        container.addView(styleSpinner)
        container.addView(TextView(this).apply {
            text = "\nDescription (anglais conseillé)"
            setTextColor(0xFFA5B4FF.toInt()); textSize = 13f
        })
        val promptEt = EditText(this).apply {
            hint = "knight with silver armor, blue cape, golden crown, holding a sword"
            setText("knight with silver armor, blue cape, golden crown, holding a sword")
            setTextColor(0xFFE8E8F0.toInt()); minLines = 2
        }
        container.addView(promptEt)
        container.addView(TextView(this).apply {
            text = "\nStyle de pixelisation"; setTextColor(0xFFA5B4FF.toInt()); textSize = 13f
        })
        val pixSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                pixStyles.map { it.displayName })
            setSelection(pixStyles.indexOf(SmartPixelize.Style.CARTOON).coerceAtLeast(0))
        }
        container.addView(pixSpinner)
        container.addView(TextView(this).apply {
            text = "\nFournisseur"; setTextColor(0xFFA5B4FF.toInt()); textSize = 13f
        })
        val savedKey = AIService.loadApiKey(this) ?: ""
        val providerSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Pollinations.ai (gratuit, lent)", "OpenAI (clé requise, auto: gpt-image-1 → dall-e-3)"))
        }
        container.addView(providerSpinner)
        val keyLabel = TextView(this).apply {
            text = "\nClé OpenAI"; setTextColor(0xFFA5B4FF.toInt()); textSize = 13f
            visibility = View.GONE
        }
        val keyEt = EditText(this).apply {
            hint = "sk-..."
            setText(savedKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(0xFFE8E8F0.toInt()); visibility = View.GONE
        }
        container.addView(keyLabel); container.addView(keyEt)
        val qualityLabel = TextView(this).apply {
            text = "\nQualité OpenAI (haute = plus fidèle au sprite, plus chère)"
            setTextColor(0xFFA5B4FF.toInt()); textSize = 13f; visibility = View.GONE
        }
        val qualitySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Basse (~$0.01/image)", "Moyenne (~$0.04/image, défaut)", "Haute (~$0.17/image, fidèle++)"))
            setSelection(1)
            visibility = View.GONE
        }
        container.addView(qualityLabel); container.addView(qualitySpinner)
        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val show = if (pos == 1) View.VISIBLE else View.GONE
                keyLabel.visibility = show; keyEt.visibility = show
                qualityLabel.visibility = show; qualitySpinner.visibility = show
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        AlertDialog.Builder(this)
            .setTitle("🎬 Personnage IA — 6 vues (1 clic)")
            .setView(container)
            .setPositiveButton("Tout générer") { _, _ ->
                val style = charStyles[styleSpinner.selectedItemPosition]
                val pix = pixStyles[pixSpinner.selectedItemPosition]
                val raw = promptEt.text.toString().trim()
                if (raw.isBlank()) { toast("Description vide"); return@setPositiveButton }
                val useOpenAI = providerSpinner.selectedItemPosition == 1
                val key = keyEt.text.toString().trim()
                if (useOpenAI) {
                    if (key.isBlank()) { toast("Clé OpenAI requise"); return@setPositiveButton }
                    AIService.saveApiKey(this, key)
                }
                val quality = arrayOf("low", "medium", "high")[qualitySpinner.selectedItemPosition]
                runFullCharacterGeneration(raw, style, pix, useOpenAI, key, quality)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runFullCharacterGeneration(
        prompt: String, style: AIService.Style, pixStyle: SmartPixelize.Style,
        useOpenAI: Boolean, apiKey: String, quality: String = "medium"
    ) {
        val views = ViewTransform.View.values()
        val progress = AlertDialog.Builder(this)
            .setTitle("Génération 6 vues")
            .setMessage("Préparation…")
            .setCancelable(false)
            .show()
        val w = project.width; val h = project.height
        // Shared seed across all 6 views = Pollinations renders the SAME character
        // (only the camera angle changes via the prompt suffix).
        val seed = (System.currentTimeMillis() and 0xFFFF).toInt()
        // OpenAI edits endpoint takes the current sprite as reference so each
        // view shows the SAME pixelized character. Upscaled to 512×512 with
        // nearest-neighbor so the AI sees crisp pixels, not blur.
        val refBitmap: Bitmap? = if (useOpenAI) makeReferencePng(project.currentFrame) else null
        lifecycleScope.launch {
            val results = ArrayList<Pair<ViewTransform.View, IntArray?>>()
            for ((i, view) in views.withIndex()) {
                progress.setMessage("${i + 1}/${views.size} — ${view.displayName} en cours…")
                val viewPart = AIService.viewDescriptor(view)
                val viewPrompt = if (useOpenAI && refBitmap != null) {
                    // Edits mode: strict character preservation, only the camera angle may change.
                    "The reference image shows ONE character on a white background. " +
                        "Re-draw the SAME EXACT character from a different camera angle — pixel by pixel identical " +
                        "wherever the new angle still shows the element.\n\n" +
                        "ABSOLUTE RULES (treat as constraints, not suggestions):\n" +
                        "• Identity must be preserved: every visual element of the reference must remain visible " +
                        "in the new angle whenever physically possible.\n" +
                        "• Forbidden additions: no extra weapon, no extra accessory, no extra clothing layer, " +
                        "no new color, no shadow, no background element, no text, no second character, no border.\n" +
                        "• Forbidden removals: do not drop any clothing piece, armor part, cape, weapon, shield, " +
                        "helmet, hair element, accessory present in the reference (a back view naturally hides " +
                        "the face — that is the only acceptable omission).\n" +
                        "• Forbidden modifications: do not change hair style/color, skin tone, any clothing color, " +
                        "armor design, weapon shape, accessory shape, proportions, art style, line thickness.\n" +
                        "• ONLY permitted change: viewing angle → $viewPart.\n\n" +
                        "Output: full body, centered on plain background, same scale as reference, same art style."
                } else {
                    AIService.applyStyleWithView(prompt, style, view)
                }
                val bmp = withContext(Dispatchers.IO) {
                    if (useOpenAI && refBitmap != null)
                        AIService.editOpenAI(refBitmap, viewPrompt, apiKey, quality = quality)
                    else if (useOpenAI)
                        AIService.generateOpenAI(viewPrompt, apiKey)
                    else AIService.generatePollinations(viewPrompt, 512, 512, seed = seed)
                }
                if (bmp == null) {
                    results.add(view to null)
                    continue
                }
                val finalPixels = withContext(Dispatchers.Default) {
                    val (pixels, _) = SmartPixelize.pixelize(bmp, w, h, BgFitMode.FIT, pixStyle)
                    val canvasBmp = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
                    val cleaned = BackgroundRemoval.removeBackground(canvasBmp, tolerance = 55, featherEdges = false)
                    val out = IntArray(w * h)
                    cleaned.getPixels(out, 0, w, 0, 0, w, h)
                    canvasBmp.recycle(); cleaned.recycle()
                    out
                }
                results.add(view to finalPixels)
            }
            progress.dismiss()
            val successCount = results.count { it.second != null }
            if (successCount == 0) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Échec total")
                    .setMessage("Aucune vue n'a pu être générée.\n\n" +
                        "Dernière erreur : ${AIService.lastError ?: "inconnue"}\n\n" +
                        "Causes courantes DALL-E :\n" +
                        "• Clé invalide ou expirée\n" +
                        "• Compte sans crédit (vérifiez sur platform.openai.com/usage)\n" +
                        "• Quota d'images dépassé\n" +
                        "• Prompt refusé (filtre de contenu)")
                    .setPositiveButton("Réessayer") { _, _ -> showAIFullCharacterDialog() }
                    .setNegativeButton("OK", null)
                    .show()
                return@launch
            }
            pushUndo()
            // First successful view → replace current frame; the rest → append after
            var firstApplied = false
            var insertAt = project.currentIndex + 1
            for ((view, px) in results) {
                if (px == null) continue
                val tag = view.displayName.lowercase().replace(' ', '_').replace('/', '_')
                if (!firstApplied) {
                    px.copyInto(project.currentFrame.pixels)
                    project.currentFrame.tag = tag
                    firstApplied = true
                } else {
                    val nf = Frame(w, h, px)
                    nf.tag = tag
                    project.frames.add(insertAt++, nf)
                }
            }
            binding.canvas.syncFrameBitmap()
            framesAdapter.notifyDataSetChanged()
            binding.timeline.invalidate()
            val missing = results.filter { it.second == null }.map { it.first.displayName }
            val msg = if (missing.isEmpty())
                "Les 6 vues ont été générées, pixelisées et fond enlevé. Naviguez via la timeline."
            else
                "$successCount/${views.size} vues OK. Vues manquantes (à relancer) : ${missing.joinToString()}"
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Génération terminée")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
        }
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

    private fun showPoseTweenDialog() {
        val sk = project.skeleton
        if (sk == null || !sk.isComplete()) {
            toast("Configurez d'abord un squelette")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Interpolation de pose")
            .setMessage("Étape 1 : votre frame actuelle est la pose A.\nÉtape 2 : modifiez le squelette pour définir la pose B.\nÉtape 3 : touchez 'Générer' pour créer les frames intermédiaires.\n\nCela vous donne une vraie animation tween image par image.")
            .setPositiveButton("Configurer pose B") { _, _ ->
                // Save current skeleton as pose A in a temp field
                _poseA = sk.copy()
                showSkeletonEditor()
                // After editing, user must use the next dialog to generate
                AlertDialog.Builder(this)
                    .setTitle("Pose B définie ?")
                    .setMessage("Réglez le squelette, puis revenez ici pour générer les frames intermédiaires.")
                    .setPositiveButton("Générer maintenant") { _, _ -> performPoseTween() }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private var _poseA: Skeleton? = null

    private fun performPoseTween() {
        val a = _poseA ?: return
        val b = project.skeleton ?: return
        pushUndo()
        val frames = PoseTween.generate(project.currentFrame, a, b, 4)
        var insertAt = project.currentIndex + 1
        frames.forEach { project.frames.add(insertAt++, it) }
        framesAdapter.notifyDataSetChanged()
        binding.timeline.invalidate()
        _poseA = null
        toast("${frames.size} frames intermédiaires générées")
    }

    private fun showSkeletonEditor() {
        if (project.skeleton == null) {
            // Auto-place from bbox
            val bbox = computeProjectBoundingBox()
            if (bbox == null) {
                // Fallback to canvas
                project.skeleton = Skeleton.humanoidTemplate(0, 0, project.width - 1, project.height - 1)
            } else {
                project.skeleton = Skeleton.humanoidTemplate(bbox[0], bbox[1], bbox[2], bbox[3])
            }
            toast("Squelette créé. Touchez un joint à déplacer.")
        }
        showJointPicker()
    }

    private fun computeProjectBoundingBox(): IntArray? {
        val f = project.currentFrame
        val pixels = if (f.layers.size > 1) f.composited() else f.pixels
        var minX = f.width; var minY = f.height; var maxX = -1; var maxY = -1
        for (y in 0 until f.height) for (x in 0 until f.width) {
            if ((pixels[y * f.width + x] ushr 24) and 0xFF >= 128) {
                if (x < minX) minX = x; if (y < minY) minY = y
                if (x > maxX) maxX = x; if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return null
        return intArrayOf(minX, minY, maxX, maxY)
    }

    private var ikAutoEnabled: Boolean = true

    private val IK_END_EFFECTORS = setOf(
        JointType.HAND_L, JointType.HAND_R, JointType.FOOT_L, JointType.FOOT_R
    )

    private fun showJointPicker() {
        val sk = project.skeleton ?: return
        binding.canvas.skeletonOverlay = sk
        val joints = JointType.values()
        val labels = joints.map { jt ->
            val j = sk.get(jt)
            val pos = if (j != null) "(${j.x.toInt()}, ${j.y.toInt()})" else "non placé"
            val ikMark = if (ikAutoEnabled && jt in IK_END_EFFECTORS) " 🦴IK" else ""
            "${jt.displayName}  $pos$ikMark"
        }.toTypedArray()
        val ikLabel = if (ikAutoEnabled) "IK auto: ON" else "IK auto: OFF"
        AlertDialog.Builder(this)
            .setTitle("Squelette du personnage")
            .setItems(labels) { _, which ->
                val jt = joints[which]
                toast("Touchez le canvas pour placer « ${jt.displayName} »")
                binding.canvas.nextTapHandler = { x, y ->
                    sk.set(jt, x.toFloat(), y.toFloat())
                    // Auto-IK: if user moved an end-effector, solve the limb
                    if (ikAutoEnabled) IK.applyIfEndEffector(sk, jt)
                    binding.canvas.invalidate()
                    val tail = if (ikAutoEnabled && jt in IK_END_EFFECTORS) " • IK appliqué" else ""
                    toast("${jt.displayName} placé en ($x, $y)$tail")
                    showJointPicker()
                }
            }
            .setPositiveButton("Terminer") { _, _ ->
                binding.canvas.skeletonOverlay = null
            }
            .setNeutralButton(ikLabel) { _, _ ->
                ikAutoEnabled = !ikAutoEnabled
                showJointPicker()
            }
            .setNegativeButton("Outils…") { _, _ -> showSkeletonTools() }
            .show()
    }

    private fun showSkeletonTools() {
        val sk = project.skeleton ?: return
        val items = arrayOf(
            "🔧 Appliquer IK à tous les membres",
            "↺ Réinitialiser auto (selon bbox)",
            "🔄 Miroir gauche → droite",
            "🔄 Miroir droite → gauche",
            "Effacer le squelette"
        )
        AlertDialog.Builder(this)
            .setTitle("Outils du squelette")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        IK.applyAllLimbs(sk)
                        binding.canvas.invalidate()
                        toast("IK appliqué aux 4 membres")
                        showJointPicker()
                    }
                    1 -> {
                        val bbox = computeProjectBoundingBox()
                        project.skeleton = if (bbox != null)
                            Skeleton.humanoidTemplate(bbox[0], bbox[1], bbox[2], bbox[3])
                        else Skeleton.humanoidTemplate(0, 0, project.width - 1, project.height - 1)
                        showJointPicker()
                    }
                    2 -> { mirrorSkeleton(sk, leftToRight = true); showJointPicker() }
                    3 -> { mirrorSkeleton(sk, leftToRight = false); showJointPicker() }
                    4 -> {
                        project.skeleton = null
                        binding.canvas.skeletonOverlay = null
                        toast("Squelette effacé")
                    }
                }
            }
            .show()
    }

    private fun mirrorSkeleton(sk: Skeleton, leftToRight: Boolean) {
        val axis = sk.joints.values.map { it.x }.average().toFloat()
        val pairs = listOf(
            JointType.SHOULDER_L to JointType.SHOULDER_R,
            JointType.ELBOW_L to JointType.ELBOW_R,
            JointType.HAND_L to JointType.HAND_R,
            JointType.HIP_L to JointType.HIP_R,
            JointType.KNEE_L to JointType.KNEE_R,
            JointType.FOOT_L to JointType.FOOT_R
        )
        pairs.forEach { (l, r) ->
            val src = if (leftToRight) sk.get(l) else sk.get(r)
            val dst = if (leftToRight) r else l
            src?.let { sk.set(dst, 2 * axis - it.x, it.y) }
        }
        binding.canvas.invalidate()
        toast("Miroir appliqué")
    }

    private fun showSkeletalAnimationDialog() {
        val sk = project.skeleton
        if (sk == null || !sk.isComplete()) {
            AlertDialog.Builder(this)
                .setTitle("Squelette requis")
                .setMessage("Pour les animations pro, placez d'abord un squelette sur votre personnage.")
                .setPositiveButton("Configurer maintenant") { _, _ -> showSkeletonEditor() }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        val presets = SkeletalAnimation.Preset.values()
        val labels = presets.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Animation squelette")
            .setItems(labels) { _, which ->
                generateSkeletalAnimation(presets[which])
            }
            .show()
    }

    private fun generateSkeletalAnimation(preset: SkeletalAnimation.Preset) {
        val sk = project.skeleton ?: return
        pushUndo()
        val skin = PixelSkin(project.width, project.height, sk)
        val newFrames = SkeletalAnimation.generate(project.currentFrame, skin, preset, project.locomotion)
        var insertAt = project.currentIndex + 1
        newFrames.forEach { project.frames.add(insertAt++, it) }
        framesAdapter.notifyDataSetChanged()
        toast("${newFrames.size} frames (${preset.displayName} • ${project.locomotion.displayName})")
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

    private fun showLocomotionPicker() {
        val modes = LocomotionMode.values()
        val labels = modes.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Mode de déplacement")
            .setSingleChoiceItems(labels, project.locomotion.ordinal) { dlg, which ->
                project.locomotion = modes[which]
                toast("Mode: ${modes[which].displayName}")
                dlg.dismiss()
            }
            .show()
    }

    private fun showTweenDialog() {
        if (project.frames.size < 2) {
            toast("Il faut au moins 2 frames"); return
        }
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 24) }
        container.addView(TextView(this).apply {
            text = "Interpolation entre :\n• Frame de départ : actuelle (#${project.currentIndex + 1})\n• Frame d'arrivée : à choisir\n\nLes N frames générées seront insérées entre les deux."
            setTextColor(0xFFE8E8F0.toInt()); textSize = 13f
        })
        val etTarget = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Index de la frame d'arrivée (1..${project.frames.size})"
            setText("${project.currentIndex + 2}")
            setTextColor(0xFFE8E8F0.toInt())
        }
        val etCount = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Nombre de frames intermédiaires (1-20)"
            setText("3")
            setTextColor(0xFFE8E8F0.toInt())
        }
        container.addView(etTarget); container.addView(etCount)
        AlertDialog.Builder(this)
            .setTitle("Interpolation (Tween)")
            .setView(container)
            .setPositiveButton("Générer") { _, _ ->
                val target = (etTarget.text.toString().toIntOrNull() ?: 2) - 1
                val count = etCount.text.toString().toIntOrNull()?.coerceIn(1, 20) ?: 3
                if (target !in 0 until project.frames.size || target == project.currentIndex) {
                    toast("Index invalide"); return@setPositiveButton
                }
                pushUndo()
                val a = project.currentFrame
                val b = project.frames[target]
                val tweens = Tweening.generate(a, b, count)
                // Insert between current and target
                val insertAt = minOf(project.currentIndex, target) + 1
                tweens.forEachIndexed { idx, frame ->
                    project.frames.add(insertAt + idx, frame)
                }
                framesAdapter.notifyDataSetChanged()
                refreshAfterFrameChange()
                toast("$count frames intermédiaires créées")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showProceduralCharacterDialog() {
        val poses = PoseTemplates.Pose.values()
        val labels = poses.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Base du personnage")
            .setItems(labels) { _, which ->
                pushUndo()
                val pixels = ProceduralCharacter.generate(poses[which], project.width, project.height)
                pixels.copyInto(project.currentFrame.pixels)
                binding.canvas.syncFrameBitmap()
                framesAdapter.notifyItemChanged(project.currentIndex)
                AlertDialog.Builder(this)
                    .setTitle("Personnage généré")
                    .setMessage("Voulez-vous une autre variation ?")
                    .setPositiveButton("Régénérer") { _, _ -> showProceduralCharacterDialog() }
                    .setNegativeButton("Garder", null)
                    .show()
            }
            .show()
    }

    private fun showAnimatedElementGenerator() {
        val types = AnimatedElement.Type.values()
        val labels = types.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Élément animé à ajouter")
            .setItems(labels) { _, which ->
                showElementPositionPicker(types[which])
            }
            .show()
    }

    private fun showElementPositionPicker(type: AnimatedElement.Type) {
        val items = arrayOf(
            "Centre",
            "Centre haut",
            "Centre bas",
            "Coin haut-gauche",
            "Coin haut-droit",
            "Coin bas-gauche",
            "Coin bas-droit",
            "Position personnalisée (tap suivant)"
        )
        AlertDialog.Builder(this)
            .setTitle("Position pour « ${type.displayName} »")
            .setItems(items) { _, which ->
                val (cx, cy) = when (which) {
                    0 -> project.width / 2 to project.height / 2
                    1 -> project.width / 2 to project.height / 4
                    2 -> project.width / 2 to project.height * 3 / 4
                    3 -> project.width / 4 to project.height / 4
                    4 -> project.width * 3 / 4 to project.height / 4
                    5 -> project.width / 4 to project.height * 3 / 4
                    6 -> project.width * 3 / 4 to project.height * 3 / 4
                    else -> {
                        toast("Touchez le canvas pour placer l'élément")
                        waitForCanvasTapToPlaceElement(type)
                        return@setItems
                    }
                }
                placeAnimatedElement(type, cx, cy)
            }
            .show()
    }

    private fun waitForCanvasTapToPlaceElement(type: AnimatedElement.Type) {
        toast("Touchez le canvas pour placer « ${type.displayName} »")
        binding.canvas.nextTapHandler = { x, y ->
            placeAnimatedElement(type, x, y)
        }
    }

    private fun placeAnimatedElement(type: AnimatedElement.Type, cx: Int, cy: Int) {
        pushUndo()
        // Ensure we have enough frames for the animation
        val targetFrames = type.recommendedFrames
        val startIdx = project.currentIndex
        // If only 1 frame, duplicate the current frame to make targetFrames copies
        if (project.frames.size < targetFrames) {
            val needed = targetFrames - project.frames.size + startIdx
            val baseFrame = project.currentFrame.copy()
            while (project.frames.size <= needed) {
                project.frames.add(baseFrame.copy())
            }
        }
        // Apply element to next [targetFrames] frames (cycling)
        for (k in 0 until targetFrames) {
            val frameIdx = (startIdx + k).coerceAtMost(project.frames.size - 1)
            val targetFrame = project.frames[frameIdx]
            AnimatedElement.render(targetFrame, cx, cy, type, k, targetFrames)
        }
        binding.canvas.syncFrameBitmap()
        framesAdapter.notifyDataSetChanged()
        toast("« ${type.displayName} » placé en ($cx, $cy) sur $targetFrames frames")
    }

    private fun showPoseTemplates() {
        val poses = PoseTemplates.Pose.values()
        val labels = poses.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Template de pose (silhouette guide)")
            .setItems(labels) { _, which ->
                pushUndo()
                val pixels = PoseTemplates.render(poses[which], project.width, project.height)
                // Merge with existing frame (don't erase content, just add outline)
                val f = project.currentFrame
                for (i in pixels.indices) if (pixels[i] != 0) f.pixels[i] = pixels[i]
                binding.canvas.syncFrameBitmap()
                framesAdapter.notifyItemChanged(project.currentIndex)
                toast("Template ${poses[which].displayName} ajouté")
            }
            .show()
    }

    private fun showDecorGenerator() {
        val items = arrayOf(
            "Décor statique → frame courante (procédural)",
            "Décor statique → image de fond (procédural)",
            "Décor ANIMÉ → 4 nouvelles frames (procédural)",
            "Décor ANIMÉ → 8 nouvelles frames (procédural)",
            "☁️ IA cloud → image de fond (qualité, internet requis)"
        )
        AlertDialog.Builder(this)
            .setTitle("Générer un décor")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pickAndGenerateStaticDecor(replaceFrame = true)
                    1 -> pickAndGenerateStaticDecor(replaceFrame = false)
                    2 -> pickAndGenerateAnimatedDecor(frameCount = 4)
                    3 -> pickAndGenerateAnimatedDecor(frameCount = 8)
                    4 -> showAICloudDecorDialog()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAICloudDecorDialog() {
        val decorStyles = AIService.Style.values().filter { it.isDecor }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 24)
        }
        container.addView(TextView(this).apply {
            text = "Génère un décor via IA en ligne et le place comme image de fond. " +
                "Vous pourrez ensuite le pixeliser comme une image importée."
            setTextColor(0xFFE8E8F0.toInt()); textSize = 12f
        })
        container.addView(TextView(this).apply {
            text = "\nStyle"; setTextColor(0xFFA5B4FF.toInt()); textSize = 13f
        })
        val styleSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                decorStyles.map { it.displayName })
        }
        container.addView(styleSpinner)
        container.addView(TextView(this).apply {
            text = "\nDescription du décor (anglais conseillé)"
            setTextColor(0xFFA5B4FF.toInt()); textSize = 13f
        })
        val promptEt = EditText(this).apply {
            hint = "ancient ruined temple with vines and broken pillars"
            setText("ancient ruined temple with vines and broken pillars at sunset")
            setTextColor(0xFFE8E8F0.toInt()); minLines = 2
        }
        container.addView(promptEt)
        AlertDialog.Builder(this)
            .setTitle("☁️ Décor IA cloud")
            .setView(container)
            .setPositiveButton("Générer") { _, _ ->
                val style = decorStyles[styleSpinner.selectedItemPosition]
                val raw = promptEt.text.toString().trim()
                if (raw.isBlank()) { toast("Entrez une description"); return@setPositiveButton }
                runAICloudGeneration(AIService.applyStyle(raw, style), useOpenAI = false, apiKey = "")
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

    private fun showAnimationGenerator() {
        // Procedural animation removed: it produced unconvincing motion (warped pixels).
        // Only the AI per-frame mode remains.
        showAIAnimationDialog()
    }

    private fun showAIAnimationDialog() {
        val presets = AIService.AnimationPreset.values()
        val charStyles = AIService.Style.values().filter { !it.isDecor && it != AIService.Style.FREE }
        val pixStyles = SmartPixelize.Style.values()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 24)
        }
        container.addView(TextView(this).apply {
            text = "L'IA génère chaque frame en partant de VOTRE sprite courant (envoyé comme référence à OpenAI). " +
                "Le perso reste identique d'une frame à l'autre, seule la pose change. " +
                "Comptez 8-15 appels (~1-3 min). Avec Pollinations (gratuit), seul le texte est utilisé — moins fidèle."
            setTextColor(0xFFE8E8F0.toInt()); textSize = 12f
        })
        container.addView(TextView(this).apply {
            text = "\nAction"; setTextColor(0xFFA5B4FF.toInt()); textSize = 13f
        })
        val presetSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                presets.map { "${it.displayName} (${it.frameDescriptors.size} frames)" })
        }
        container.addView(presetSpinner)
        container.addView(TextView(this).apply {
            text = "\nStyle de personnage"; setTextColor(0xFFA5B4FF.toInt()); textSize = 13f
        })
        val styleSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item, charStyles.map { it.displayName })
        }
        container.addView(styleSpinner)
        container.addView(TextView(this).apply {
            text = "\nDescription du personnage (anglais)"
            setTextColor(0xFFA5B4FF.toInt()); textSize = 13f
        })
        val promptEt = EditText(this).apply {
            hint = "knight with silver armor and blue cape holding a longsword"
            setText("knight with silver armor and blue cape holding a longsword")
            setTextColor(0xFFE8E8F0.toInt()); minLines = 2
        }
        container.addView(promptEt)
        container.addView(TextView(this).apply {
            text = "\nStyle de pixelisation"; setTextColor(0xFFA5B4FF.toInt()); textSize = 13f
        })
        val pixSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item, pixStyles.map { it.displayName })
            setSelection(pixStyles.indexOf(SmartPixelize.Style.CARTOON).coerceAtLeast(0))
        }
        container.addView(pixSpinner)
        container.addView(TextView(this).apply {
            text = "\nFournisseur"; setTextColor(0xFFA5B4FF.toInt()); textSize = 13f
        })
        val savedKey = AIService.loadApiKey(this) ?: ""
        val providerSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Pollinations.ai (gratuit, lent)", "OpenAI (clé requise, auto: gpt-image-1 → dall-e-3)"))
        }
        container.addView(providerSpinner)
        val keyLabel = TextView(this).apply {
            text = "\nClé OpenAI"; setTextColor(0xFFA5B4FF.toInt()); textSize = 13f
            visibility = View.GONE
        }
        val keyEt = EditText(this).apply {
            hint = "sk-..."
            setText(savedKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(0xFFE8E8F0.toInt()); visibility = View.GONE
        }
        container.addView(keyLabel); container.addView(keyEt)
        val qualityLabel = TextView(this).apply {
            text = "\nQualité OpenAI (haute = plus fidèle au sprite, plus chère)"
            setTextColor(0xFFA5B4FF.toInt()); textSize = 13f; visibility = View.GONE
        }
        val qualitySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Basse (~$0.01/image)", "Moyenne (~$0.04/image, défaut)", "Haute (~$0.17/image, fidèle++)"))
            setSelection(1)
            visibility = View.GONE
        }
        container.addView(qualityLabel); container.addView(qualitySpinner)
        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val show = if (pos == 1) View.VISIBLE else View.GONE
                keyLabel.visibility = show; keyEt.visibility = show
                qualityLabel.visibility = show; qualitySpinner.visibility = show
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        AlertDialog.Builder(this)
            .setTitle("🎬 Animation IA")
            .setView(container)
            .setPositiveButton("Générer") { _, _ ->
                val preset = presets[presetSpinner.selectedItemPosition]
                val style = charStyles[styleSpinner.selectedItemPosition]
                val pix = pixStyles[pixSpinner.selectedItemPosition]
                val raw = promptEt.text.toString().trim()
                if (raw.isBlank()) { toast("Description vide"); return@setPositiveButton }
                val useOpenAI = providerSpinner.selectedItemPosition == 1
                val key = keyEt.text.toString().trim()
                if (useOpenAI) {
                    if (key.isBlank()) { toast("Clé OpenAI requise"); return@setPositiveButton }
                    AIService.saveApiKey(this, key)
                }
                val quality = arrayOf("low", "medium", "high")[qualitySpinner.selectedItemPosition]
                runAIAnimation(raw, style, preset, pix, useOpenAI, key, quality)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Build a 1024×1024 reference bitmap from a project frame for OpenAI's
     * edits endpoint. The character is tight-cropped to its bounding box,
     * upscaled with nearest-neighbor to preserve sharp pixels, then centered
     * on a solid white background. This focuses the AI on the character
     * itself (instead of letting it fill the canvas with extras) and keeps
     * transparent areas from confusing the model.
     */
    private fun makeReferencePng(frame: Frame): Bitmap {
        val w = frame.width; val h = frame.height
        val regions = BodyDetector.detect(frame.pixels, w, h)
        // Fallback: no opaque pixels -> upscale the whole frame on white
        val bbox = regions?.bbox ?: android.graphics.Rect(0, 0, w, h)
        val pad = 1
        val cx0 = (bbox.left - pad).coerceAtLeast(0)
        val cy0 = (bbox.top - pad).coerceAtLeast(0)
        val cx1 = (bbox.right + pad).coerceAtMost(w)
        val cy1 = (bbox.bottom + pad).coerceAtMost(h)
        val cw = cx1 - cx0; val ch = cy1 - cy0
        // Build a cropped bitmap of just the character
        val cropPixels = IntArray(cw * ch)
        for (y in 0 until ch) for (x in 0 until cw) {
            val src = frame.pixels[(cy0 + y) * w + (cx0 + x)]
            cropPixels[y * cw + x] = src
        }
        val crop = Bitmap.createBitmap(cropPixels, cw, ch, Bitmap.Config.ARGB_8888)
        // Upscale nearest-neighbor so the character fills ~80% of the 1024 canvas
        val target = 1024
        val maxDim = maxOf(cw, ch)
        val scale = ((target * 0.8f) / maxDim).toInt().coerceAtLeast(1)
        val sw = cw * scale; val sh = ch * scale
        val scaled = Bitmap.createScaledBitmap(crop, sw, sh, false)
        crop.recycle()
        // Compose centered on white 1024×1024
        val canvas = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(canvas)
        c.drawColor(0xFFFFFFFF.toInt())
        val ox = (target - sw) / 2; val oy = (target - sh) / 2
        c.drawBitmap(scaled, ox.toFloat(), oy.toFloat(), null)
        if (scaled !== canvas) scaled.recycle()
        return canvas
    }

    private fun runAIAnimation(
        basePrompt: String,
        style: AIService.Style,
        preset: AIService.AnimationPreset,
        pixStyle: SmartPixelize.Style,
        useOpenAI: Boolean,
        apiKey: String,
        quality: String = "medium"
    ) {
        val progress = AlertDialog.Builder(this)
            .setTitle("Animation IA — ${preset.displayName}")
            .setMessage("Préparation…")
            .setCancelable(false)
            .show()
        val w = project.width; val h = project.height
        val seed = (System.currentTimeMillis() and 0xFFFF).toInt()  // shared seed = same character
        // OpenAI edits endpoint: each frame is generated FROM the current sprite,
        // so the AI keeps the same character look pose-by-pose.
        val refBitmap: Bitmap? = if (useOpenAI) makeReferencePng(project.currentFrame) else null
        lifecycleScope.launch {
            val results = ArrayList<IntArray?>()
            for ((i, motion) in preset.frameDescriptors.withIndex()) {
                progress.setMessage("${i + 1}/${preset.frameDescriptors.size} — $motion")
                val framePrompt = if (useOpenAI && refBitmap != null) {
                    // Edits mode: strict character preservation, only the pose may change.
                    "The reference image shows ONE character on a white background. " +
                        "Re-draw the SAME EXACT character — pixel by pixel identical wherever the new pose allows — " +
                        "with ONLY the body pose changed.\n\n" +
                        "ABSOLUTE RULES (treat as constraints, not suggestions):\n" +
                        "• Identity must be preserved: every visual element of the reference must remain. " +
                        "If something is on the reference, it must be on the output. If something is NOT on the " +
                        "reference, it must NOT appear on the output.\n" +
                        "• Forbidden additions: no extra weapon, no extra accessory, no extra clothing layer, " +
                        "no new color, no shadow, no background element, no text, no second character, no border.\n" +
                        "• Forbidden removals: do not drop any clothing piece, armor part, cape, weapon, shield, " +
                        "helmet, hair element, facial feature, accessory present in the reference.\n" +
                        "• Forbidden modifications: do not change hair style/color, skin tone, eye color, mouth shape, " +
                        "any clothing color, armor design, weapon shape, accessory shape, proportions, art style, line thickness.\n" +
                        "• ONLY permitted change: body pose → $motion.\n\n" +
                        "Output: full body, side view, centered on plain background, same scale as reference, same art style."
                } else {
                    "${style.prefix}the exact same character: $basePrompt, " +
                        "same outfit, same colors, same proportions, currently in this pose: $motion" +
                        "${style.suffix}, side view, full body, isolated on plain white background, " +
                        "centered, no shadows, no text, no other characters"
                }
                val bmp = withContext(Dispatchers.IO) {
                    if (useOpenAI && refBitmap != null)
                        AIService.editOpenAI(refBitmap, framePrompt, apiKey, quality = quality)
                    else if (useOpenAI)
                        AIService.generateOpenAI(framePrompt, apiKey)
                    else AIService.generatePollinations(framePrompt, 512, 512, seed = seed)
                }
                if (bmp == null) { results.add(null); continue }
                val finalPixels = withContext(Dispatchers.Default) {
                    val (pixels, _) = SmartPixelize.pixelize(bmp, w, h, BgFitMode.FIT, pixStyle)
                    val canvasBmp = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
                    val cleaned = BackgroundRemoval.removeBackground(canvasBmp, tolerance = 55, featherEdges = false)
                    val out = IntArray(w * h)
                    cleaned.getPixels(out, 0, w, 0, 0, w, h)
                    canvasBmp.recycle(); cleaned.recycle()
                    out
                }
                results.add(finalPixels)
            }
            progress.dismiss()
            val successCount = results.count { it != null }
            if (successCount == 0) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Échec total")
                    .setMessage("Aucune frame n'a pu être générée.\n\n" +
                        "Dernière erreur : ${AIService.lastError ?: "inconnue"}")
                    .setPositiveButton("OK", null).show()
                return@launch
            }
            pushUndo()
            var firstApplied = false
            var insertAt = project.currentIndex + 1
            for ((i, px) in results.withIndex()) {
                if (px == null) continue
                val tag = "${preset.name.lowercase()}_${i + 1}"
                if (!firstApplied) {
                    px.copyInto(project.currentFrame.pixels)
                    project.currentFrame.tag = tag
                    firstApplied = true
                } else {
                    val nf = Frame(w, h, px)
                    nf.tag = tag
                    project.frames.add(insertAt++, nf)
                }
            }
            binding.canvas.syncFrameBitmap()
            framesAdapter.notifyDataSetChanged()
            binding.timeline.invalidate()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Animation générée")
                .setMessage("$successCount/${preset.frameDescriptors.size} frames créées. " +
                    "Lancez la prévisualisation pour voir le mouvement. Si une frame casse, " +
                    "supprimez-la depuis la timeline.")
                .setPositiveButton("OK", null).show()
        }
    }

    private fun generateAnimation(preset: AnimationGenerator.Preset) {
        pushUndo()
        val base = project.currentFrame.copy()
        val newFrames = AnimationGenerator.generate(base, preset, project.locomotion)
        var insertAt = project.currentIndex + 1
        for (nf in newFrames) {
            project.frames.add(insertAt++, nf)
        }
        project.currentFrame.tag = preset.name.lowercase()
        framesAdapter.notifyDataSetChanged()
        toast("${newFrames.size} frames • ${preset.displayName} • ${project.locomotion.displayName}")
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

    private fun sharePng() {
        val bmp = frameToBitmap(project.currentFrame, 8)
        val bytes = ByteArrayOutputStream().apply { bmp.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        savePublicImage(bytes, "${project.name}_frame${project.currentIndex + 1}.png", "image/png", share = true)
        bmp.recycle()
    }

    private fun shareGif() {
        toast(getString(R.string.generating_gif))
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.Default) {
                val encoder = GifEncoder(project.width, project.height)
                project.frames.forEachIndexed { i, f ->
                    val comp = if (f.layers.size > 1) f.composited() else f.pixels
                    encoder.addFrame(comp, project.delayForFrame(i))
                }
                encoder.encodeToBytes()
            }
            savePublicImage(bytes, "${project.name}.gif", "image/gif", share = true)
        }
    }

    private fun openTileMap() {
        // Save project first (the tile activity loads from storage)
        ProjectStorage.save(this, project)
        TileMapActivity.start(this, project.id)
    }

    private fun showLayersDialog() {
        val f = project.currentFrame
        val labels = f.layers.mapIndexed { i, l ->
            val active = if (i == f.activeLayer) "● " else "  "
            val vis = if (l.visible) "👁 " else "  "
            "$active$vis${l.name}  (op ${(l.opacity * 100).toInt()}%)"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Calques (frame #${project.currentIndex + 1})")
            .setSingleChoiceItems(labels, f.activeLayer) { _, which ->
                f.activeLayer = which
            }
            .setPositiveButton("Fermer", null)
            .setNeutralButton("+ Ajouter") { _, _ ->
                pushUndo()
                f.addLayer()
                binding.canvas.syncFrameBitmap()
                framesAdapter.notifyItemChanged(project.currentIndex)
                toast("Couche ajoutée")
                showLayersDialog()
            }
            .setNegativeButton("Actions…") { _, _ -> showLayerActions() }
            .show()
    }

    private fun showLayerActions() {
        val f = project.currentFrame
        val l = f.layers[f.activeLayer]
        val items = arrayOf(
            if (l.visible) "Masquer" else "Afficher",
            "Renommer…",
            "Opacité…",
            "Supprimer",
            "Monter (au-dessus)",
            "Descendre (en dessous)",
            "Fusionner avec la couche du dessous"
        )
        AlertDialog.Builder(this)
            .setTitle("« ${l.name} »")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { l.visible = !l.visible; binding.canvas.syncFrameBitmap() }
                    1 -> renameLayer(l)
                    2 -> showLayerOpacity(l)
                    3 -> {
                        pushUndo()
                        if (!f.removeLayer(f.activeLayer)) toast("Au moins 1 calque requis")
                        else binding.canvas.syncFrameBitmap()
                    }
                    4 -> moveLayer(+1)
                    5 -> moveLayer(-1)
                    6 -> mergeDown()
                }
                framesAdapter.notifyItemChanged(project.currentIndex)
            }
            .show()
    }

    private fun renameLayer(l: Layer) {
        val input = EditText(this).apply { setText(l.name); setTextColor(0xFFE8E8F0.toInt()) }
        AlertDialog.Builder(this)
            .setTitle("Nom du calque")
            .setView(input)
            .setPositiveButton("OK") { _, _ -> l.name = input.text.toString().ifBlank { "Couche" } }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showLayerOpacity(l: Layer) {
        val seek = SeekBar(this).apply { max = 100; progress = (l.opacity * 100).toInt() }
        AlertDialog.Builder(this)
            .setTitle("Opacité")
            .setView(seek)
            .setPositiveButton("OK") { _, _ ->
                l.opacity = seek.progress / 100f
                binding.canvas.syncFrameBitmap()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun moveLayer(direction: Int) {
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

    private fun mergeDown() {
        val f = project.currentFrame
        val active = f.activeLayer
        if (active == 0) { toast("Pas de calque en dessous"); return }
        pushUndo()
        val top = f.layers[active]
        val below = f.layers[active - 1]
        // Composite top onto below
        for (i in top.pixels.indices) {
            val src = top.pixels[i]
            if ((src ushr 24) and 0xFF >= 128) below.pixels[i] = src
        }
        f.layers.removeAt(active)
        f.activeLayer = active - 1
        binding.canvas.syncFrameBitmap()
        toast("Calques fusionnés")
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
        pushUndo()
        val outlineColor = project.primaryColor
        for (i in fromIdx..toIdx) {
            val f = project.frames.getOrNull(i) ?: continue
            val out = Filters.apply(f.pixels, f.width, f.height, filter, outlineColor)
            out.copyInto(f.pixels)
        }
        binding.canvas.syncFrameBitmap()
        framesAdapter.notifyDataSetChanged()
        binding.timeline.invalidate()
        toast("Filtre appliqué sur ${toIdx - fromIdx + 1} frame(s)")
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
    }

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
            "Bibliothèque palettes étendue…",
            "🎨 Variantes de couleur (recolor pour ennemis)"
        )
        AlertDialog.Builder(this).setTitle("🎨 Couleurs & palettes")
            .setItems(items) { _, w ->
                when (w) {
                    0 -> showColorLockMenu()
                    1 -> showExtendedPalettesDialog()
                    2 -> showPaletteSwapDialog()
                }
            }.show()
    }

    private fun showPaletteSwapDialog() {
        AlertDialog.Builder(this)
            .setTitle("🎨 Variantes de couleur")
            .setMessage("Génère 3 nouvelles frames à partir de la frame courante avec une rotation de teinte " +
                "(+90° / +180° / +270°). Utile pour créer un ennemi rouge/bleu/vert depuis un seul sprite, " +
                "ou des palette swaps pour boss / élite / armure améliorée.")
            .setPositiveButton("Générer 3 variantes") { _, _ ->
                pushUndo()
                val source = project.currentFrame.pixels.copyOf()
                val w = project.width; val h = project.height
                val degrees = intArrayOf(90, 180, 270)
                var insertAt = project.currentIndex + 1
                for (deg in degrees) {
                    val variant = IntArray(w * h)
                    for (i in source.indices) variant[i] = rotateHue(source[i], deg)
                    val nf = Frame(w, h, variant)
                    nf.tag = "variant_h$deg"
                    project.frames.add(insertAt++, nf)
                }
                framesAdapter.notifyDataSetChanged()
                binding.timeline.invalidate()
                toast("3 variantes ajoutées (hue +90 / +180 / +270)")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun rotateHue(argb: Int, deg: Int): Int {
        val a = (argb ushr 24) and 0xFF
        if (a < 128) return argb
        val hsv = FloatArray(3)
        Color.colorToHSV(argb, hsv)
        hsv[0] = (hsv[0] + deg) % 360f
        return (a shl 24) or (Color.HSVToColor(hsv) and 0x00FFFFFF)
    }

    private fun showToolsMenu() {
        val items = arrayOf(
            "Ajouter du texte (5×7 pixel font)…",
            "Stabilisateur de trait…",
            "Personnaliser couleurs onion skin…",
            "Fond global (partagé entre toutes les frames)…"
        )
        AlertDialog.Builder(this).setTitle("🔧 Outils")
            .setItems(items) { _, w ->
                when (w) {
                    0 -> showTextDialog()
                    1 -> showStabilizerDialog()
                    2 -> showOnionColorPicker()
                    3 -> showGlobalBackgroundDialog()
                }
            }.show()
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

    private fun exportAllFrames() {
        val scale = 8
        project.frames.forEachIndexed { i, f ->
            val bmp = frameToBitmap(f, scale)
            val bytes = ByteArrayOutputStream().apply { bmp.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
            savePublicImage(bytes, "${project.name}_frame${i + 1}.png", "image/png")
            bmp.recycle()
        }
        toast("${project.frames.size} frames exportées")
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
            "Bienvenue dans PixelHero ! 👋\n\nMenu (☰) → Nouveau projet pour démarrer avec une taille, ou utilisez une taille préréglée (16, 32, 64…).",
            "✏️ Outils (barre gauche)\n\nCrayon, gomme, pot de peinture, pipette, ligne, rectangle, sélection, déplacer. Touchez longuement pour glisser.\n\n💡 2 doigts = zoom + pan.",
            "🎨 Couleur (panneau droit)\n\nTouchez une couleur de la palette. Le bouton 🎨 ouvre un sélecteur RGB. Auto-shading ajoute des nuances.",
            "🖼️ Image de fond\n\nChargez une photo et l'app vous propose : pixeliser → frame courante avec dithering, extraire palette, etc.",
            "🎬 Frames (panneau droit)\n\nAjouter / dupliquer / supprimer. Glissez longuement pour réordonner. Aperçu auto en boucle.",
            "🪄 Baguette magique\n\nGénérateur intelligent : animations (marche, attaque…), décors (forêt, donjon…), éléments animés (flambeaux, feu de camp…), templates de pose.",
            "💾 Sauvegarde\n\nAuto-save toutes les 30s. Menu → Sauvegarder pour nommer. Menu → Charger pour récupérer un projet.",
            "📤 Export\n\nPNG (frame seule, ×8), sprite sheet, GIF animé, chaque frame en PNG séparé. Vers Pictures/PixelHero/."
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
        fun preset(w: Int, h: Int) { inputW.setText(w.toString()); inputH.setText(h.toString()) }
        // Square presets
        view.findViewById<View>(R.id.preset16).setOnClickListener  { preset(16, 16) }
        view.findViewById<View>(R.id.preset24).setOnClickListener  { preset(24, 24) }
        view.findViewById<View>(R.id.preset32).setOnClickListener  { preset(32, 32) }
        view.findViewById<View>(R.id.preset48).setOnClickListener  { preset(48, 48) }
        view.findViewById<View>(R.id.preset64).setOnClickListener  { preset(64, 64) }
        view.findViewById<View>(R.id.preset96).setOnClickListener  { preset(96, 96) }
        view.findViewById<View>(R.id.preset128).setOnClickListener { preset(128, 128) }
        view.findViewById<View>(R.id.preset256).setOnClickListener { preset(256, 256) }
        // Console formats
        view.findViewById<View>(R.id.presetGB).setOnClickListener   { preset(160, 144) }
        view.findViewById<View>(R.id.presetGBA).setOnClickListener  { preset(240, 160) }
        view.findViewById<View>(R.id.presetNES).setOnClickListener  { preset(256, 224) }
        view.findViewById<View>(R.id.presetSNES).setOnClickListener { preset(256, 240) }
        // Large formats
        view.findViewById<View>(R.id.preset320x240).setOnClickListener { preset(320, 240) }
        view.findViewById<View>(R.id.preset480x270).setOnClickListener { preset(480, 270) }
        view.findViewById<View>(R.id.preset512).setOnClickListener     { preset(512, 512) }
        view.findViewById<View>(R.id.preset640x360).setOnClickListener { preset(640, 360) }
        view.findViewById<View>(R.id.preset800x600).setOnClickListener { preset(800, 600) }
        view.findViewById<View>(R.id.preset1024).setOnClickListener    { preset(1024, 1024) }
        AlertDialog.Builder(this)
            .setTitle(R.string.new_project)
            .setView(view)
            .setPositiveButton(R.string.create) { _, _ ->
                val w = inputW.text.toString().toIntOrNull()?.coerceIn(1, 1024) ?: 32
                val h = inputH.text.toString().toIntOrNull()?.coerceIn(1, 1024) ?: 32
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
        AlertDialog.Builder(this)
            .setTitle(R.string.resize)
            .setView(view)
            .setPositiveButton(R.string.resize) { _, _ ->
                val w = view.findViewById<EditText>(R.id.inputW).text.toString().toIntOrNull()?.coerceIn(1, 1024) ?: return@setPositiveButton
                val h = view.findViewById<EditText>(R.id.inputH).text.toString().toIntOrNull()?.coerceIn(1, 1024) ?: return@setPositiveButton
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
                toast(getString(R.string.saved))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
        val labels = list.map { it.optString("name") }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Supprimer un projet")
            .setItems(labels) { _, which ->
                ProjectStorage.delete(this, list[which].optString("id"))
                toast("Supprimé")
            }
            .show()
    }

    // ---- Background image ----
    private fun loadBgImage(uri: Uri) {
        runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                val raw = BitmapFactory.decodeStream(input)
                if (raw != null) {
                    binding.canvas.bgBitmap = raw
                    project.bgFit = BgFitMode.FIT
                    binding.canvas.invalidate()
                    updateBgFitButtonLabel()
                    askBackgroundRemovalIntensity(raw)
                }
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
            "🤴 Convertir en perso style King God Castle",
            "🎨 Pixeliser intelligent (game-ready)",
            "Pixeliser basique avec tramage",
            "Pixeliser basique sans tramage",
            "Extraire palette (16 couleurs)"
        )
        AlertDialog.Builder(this)
            .setTitle("Que faire avec cette image ?")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {} // keep as bg only
                    1 -> removeBackgroundDialog(bmp)
                    2 -> convertToKingGodCastle(bmp)
                    3 -> showSmartPixelizeStyles(bmp)
                    4 -> pixelizeIntoFrame(bmp, dither = true, applyPalette = false)
                    5 -> pixelizeIntoFrame(bmp, dither = false, applyPalette = false)
                    6 -> extractPaletteFromBg(bmp)
                }
            }
            .setNegativeButton("← Retour adaptation") { _, _ -> askBgFitModeThenAction(bmp) }
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

    private fun convertToKingGodCastle(bmp: Bitmap) {
        convertToKgcViaAI(bmp)
    }

    private fun convertToKgcViaAI(bmp: Bitmap) {
        lifecycleScope.launch {
            val colors = withContext(Dispatchers.Default) { PhotoToCharacter.sampleColors(bmp) }
            val skin = AIService.describeColor(colors.skin)
            val hair = AIService.describeColor(colors.hair)
            val shirt = AIService.describeColor(colors.shirt)
            val pants = AIService.describeColor(colors.pants)
            val basePrompt = "knight character, $hair hair, $skin skin, $shirt armor or shirt, $pants pants"
            val et = EditText(this@MainActivity).apply {
                setText(basePrompt); minLines = 2
                setTextColor(0xFFE8E8F0.toInt())
            }
            val savedKey = AIService.loadApiKey(this@MainActivity) ?: ""
            val keyEt = EditText(this@MainActivity).apply {
                hint = "Clé OpenAI (vide = Pollinations gratuit)"
                setText(savedKey)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setTextColor(0xFFE8E8F0.toInt())
            }
            val container = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 24)
                addView(TextView(this@MainActivity).apply {
                    text = "Description auto-générée depuis les couleurs de votre photo. " +
                        "Vous pouvez la modifier (ajoutez : 'female', 'wizard', 'with sword and shield', etc.)."
                    setTextColor(0xFFE8E8F0.toInt()); textSize = 12f
                })
                addView(et)
                addView(TextView(this@MainActivity).apply {
                    text = "\nClé OpenAI optionnelle (vide = Pollinations gratuit)"
                    setTextColor(0xFFA5B4FF.toInt()); textSize = 12f
                })
                addView(keyEt)
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle("☁️ KGC via IA")
                .setView(container)
                .setPositiveButton("Générer") { _, _ ->
                    val raw = et.text.toString().trim()
                    if (raw.isBlank()) { toast("Description vide"); return@setPositiveButton }
                    val finalPrompt = AIService.applyStyle(raw, AIService.Style.KGC)
                    val key = keyEt.text.toString().trim()
                    val useOpenAI = key.isNotBlank()
                    if (useOpenAI) AIService.saveApiKey(this@MainActivity, key)
                    runAICloudGeneration(finalPrompt, useOpenAI, key)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
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

    private fun pixelizeIntoFrame(bmp: Bitmap, dither: Boolean, applyPalette: Boolean) {
        pushUndo()
        val pixels = ImageToPixelArt.pixelize(
            bitmap = bmp,
            w = project.width, h = project.height,
            paletteSize = 16,
            fit = project.bgFit,
            dither = dither
        )
        pixels.copyInto(project.currentFrame.pixels)
        binding.canvas.syncFrameBitmap()
        framesAdapter.notifyItemChanged(project.currentIndex)
        if (applyPalette) {
            val pal = ImageToPixelArt.extractPalette(pixels, 16)
            project.palette.clear()
            project.palette.addAll(pal.toList())
            paletteAdapter.notifyDataSetChanged()
        }
        toast("Pixel art généré (${pixels.size} pixels)")
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

    // ---- Export ----
    private fun frameToBitmap(frame: Frame, scale: Int = 1): Bitmap {
        val composite = if (frame.layers.size > 1) frame.composited() else frame.pixels
        val bmp = Bitmap.createBitmap(frame.width * scale, frame.height * scale, Bitmap.Config.ARGB_8888)
        if (scale == 1) {
            bmp.setPixels(composite, 0, frame.width, 0, 0, frame.width, frame.height)
        } else {
            val small = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
            small.setPixels(composite, 0, frame.width, 0, 0, frame.width, frame.height)
            val c = android.graphics.Canvas(bmp)
            val p = android.graphics.Paint().apply { isFilterBitmap = false; isAntiAlias = false }
            c.drawBitmap(small, null, android.graphics.Rect(0, 0, bmp.width, bmp.height), p)
            small.recycle()
        }
        return bmp
    }

    private fun exportPng() {
        val bmp = frameToBitmap(project.currentFrame, 8)
        val bytes = ByteArrayOutputStream().apply { bmp.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        savePublicImage(bytes, "${project.name}_frame${project.currentIndex + 1}.png", "image/png")
        bmp.recycle()
    }

    private fun exportSpriteSheet() {
        val scale = 4
        val cols = Math.ceil(Math.sqrt(project.frames.size.toDouble())).toInt().coerceAtLeast(1)
        val rows = (project.frames.size + cols - 1) / cols
        val fw = project.width * scale
        val fh = project.height * scale
        val sheet = Bitmap.createBitmap(cols * fw, rows * fh, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(sheet)
        val paint = android.graphics.Paint().apply { isFilterBitmap = false }
        project.frames.forEachIndexed { i, frame ->
            val b = frameToBitmap(frame, scale)
            canvas.drawBitmap(b, (i % cols * fw).toFloat(), (i / cols * fh).toFloat(), paint)
            b.recycle()
        }
        val bytes = ByteArrayOutputStream().apply { sheet.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        savePublicImage(bytes, "${project.name}_sheet.png", "image/png")
        sheet.recycle()
    }

    private fun exportGif() {
        toast(getString(R.string.generating_gif))
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.Default) {
                val encoder = GifEncoder(project.width, project.height)
                project.frames.forEachIndexed { i, f ->
                    val comp = if (f.layers.size > 1) f.composited() else f.pixels
                    encoder.addFrame(comp, project.delayForFrame(i))
                }
                encoder.encodeToBytes()
            }
            savePublicImage(bytes, "${project.name}.gif", "image/gif")
            toast(getString(R.string.gif_done))
        }
    }

    private fun savePublicImage(bytes: ByteArray, filename: String, mime: String, share: Boolean = false): Uri? {
        return try {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PixelHero")
                }
                val u = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                u?.let { contentResolver.openOutputStream(it)?.use { o -> o.write(bytes) } }
                u
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "PixelHero")
                dir.mkdirs()
                val f = File(dir, filename)
                FileOutputStream(f).use { it.write(bytes) }
                sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f)))
                Uri.fromFile(f)
            }
            if (uri == null) { toast("Échec d'enregistrement"); return null }
            if (share) shareUri(uri, mime, filename)
            else toast("Enregistré dans Pictures/PixelHero/$filename")
            uri
        } catch (e: Exception) {
            toast("Erreur: ${e.message}")
            null
        }
    }

    private fun shareUri(uri: Uri, mime: String, filename: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, filename)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "Partager $filename"))
        } catch (e: Exception) {
            toast("Aucune application pour partager")
        }
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
                val delay = project.delayForFrame(playIdx).toLong().coerceAtLeast(20L)
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

    private fun toast(msg: String) {
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
