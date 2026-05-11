package com.pixelhero.app

import android.app.AlertDialog
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
    private val maxUndo = 80

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
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        project = Project()
        wireCanvas()
        wireTools()
        wireTopBar()
        wireRightPanel()
        wireFrames()

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
                    val bmp = Bitmap.createBitmap(f.width, f.height, Bitmap.Config.ARGB_8888)
                    bmp.setPixels(f.pixels, 0, f.width, 0, 0, f.width, f.height)
                    val drawable = android.graphics.drawable.BitmapDrawable(resources, bmp)
                    drawable.isFilterBitmap = false
                    binding.miniPreview.setImageDrawable(drawable)
                    // Advance using project play mode
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
    }

    // ---- Tools ----
    private fun wireTools() {
        val tools = mapOf(
            binding.toolPencil to Tool.PENCIL,
            binding.toolEraser to Tool.ERASER,
            binding.toolFill to Tool.FILL,
            binding.toolPicker to Tool.PICKER,
            binding.toolLine to Tool.LINE,
            binding.toolRect to Tool.RECT,
            binding.toolRectFill to Tool.RECT_FILL,
            binding.toolSelect to Tool.SELECT,
            binding.toolWand to Tool.WAND,
            binding.toolMove to Tool.MOVE
        )
        binding.toolPencil.isSelected = true
        tools.forEach { (btn, tool) ->
            btn.setOnClickListener {
                tools.keys.forEach { it.isSelected = false }
                btn.isSelected = true
                binding.canvas.tool = tool
                if (tool == Tool.SELECT || tool == Tool.WAND) showSelectionActions()
            }
            btn.setOnLongClickListener { l ->
                if (tool == Tool.SELECT || tool == Tool.WAND) showSelectionActions()
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
        binding.btnGrid.setOnClickListener {
            binding.canvas.showGrid = !binding.canvas.showGrid
            binding.btnGrid.isSelected = binding.canvas.showGrid
            binding.canvas.invalidate()
        }
        binding.btnGrid.isSelected = true

        binding.btnFlip.setOnClickListener { showFlipMenu() }
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

    private fun showSelectionActions() {
        if (!binding.canvas.selection.active) return
        val hasFloating = binding.canvas.selection.floating != null
        val items = mutableListOf<String>()
        items.add(getString(R.string.copy))
        items.add(getString(R.string.cut))
        if (clipboardPixels != null) items.add(getString(R.string.paste))
        items.add(getString(R.string.flip_h))
        items.add(getString(R.string.flip_v))
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
            getString(R.string.symmetry_both)
        )
        val current = when (project.symmetry) {
            SymmetryAxis.NONE -> 0
            SymmetryAxis.HORIZONTAL -> 1
            SymmetryAxis.VERTICAL -> 2
            SymmetryAxis.BOTH -> 3
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.symmetry)
            .setSingleChoiceItems(items, current) { dlg, which ->
                project.symmetry = when (which) {
                    1 -> SymmetryAxis.HORIZONTAL
                    2 -> SymmetryAxis.VERTICAL
                    3 -> SymmetryAxis.BOTH
                    else -> SymmetryAxis.NONE
                }
                binding.btnSymmetry.isSelected = project.symmetry != SymmetryAxis.NONE
                binding.canvas.invalidate()
                dlg.dismiss()
            }
            .show()
    }

    private fun showSmartGenerator() {
        val items = arrayOf(
            "Frames d'animation",
            "Décor / scène (statique ou animé)",
            "Élément animé (flambeau, feu de camp…)",
            "Template de pose"
        )
        AlertDialog.Builder(this)
            .setTitle("Générer…")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showAnimationGenerator()
                    1 -> showDecorGenerator()
                    2 -> showAnimatedElementGenerator()
                    3 -> showPoseTemplates()
                }
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
            "Décor statique → frame courante",
            "Décor statique → image de fond",
            "Décor ANIMÉ → 4 nouvelles frames",
            "Décor ANIMÉ → 8 nouvelles frames"
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

    private fun showAnimationGenerator() {
        val presets = AnimationGenerator.Preset.values()
        val labels = presets.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.generate_animation))
            .setItems(labels) { _, which ->
                val preset = presets[which]
                generateAnimation(preset)
            }
            .show()
    }

    private fun generateAnimation(preset: AnimationGenerator.Preset) {
        pushUndo()
        val base = project.currentFrame.copy()
        val newFrames = AnimationGenerator.generate(base, preset)
        var insertAt = project.currentIndex + 1
        for (nf in newFrames) {
            project.frames.add(insertAt++, nf)
        }
        project.currentFrame.tag = preset.name.lowercase()
        framesAdapter.notifyDataSetChanged()
        toast("${newFrames.size} frames générées (${preset.displayName})")
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
            .setItems(arrayOf("Frame courante", "Toutes les frames")) { _, scope ->
                pushUndo()
                val outlineColor = project.primaryColor
                if (scope == 0) {
                    val out = Filters.apply(project.currentFrame.pixels, project.width, project.height, filter, outlineColor)
                    out.copyInto(project.currentFrame.pixels)
                } else {
                    project.frames.forEach { f ->
                        val out = Filters.apply(f.pixels, f.width, f.height, filter, outlineColor)
                        out.copyInto(f.pixels)
                    }
                }
                binding.canvas.syncFrameBitmap()
                framesAdapter.notifyDataSetChanged()
                toast("Filtre appliqué")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
        // Use the most-used colors as source candidates, plus palette
        val sources = (ColorOps.mostUsedColors(project, 16) + project.palette).distinct()
        if (sources.isEmpty()) { toast("Aucune couleur à remplacer"); return }
        val labels = sources.map { String.format("#%06X", it and 0xFFFFFF) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Remplacer quelle couleur ?")
            .setItems(labels) { _, which ->
                val from = sources[which]
                val to = project.primaryColor
                AlertDialog.Builder(this)
                    .setTitle("Remplacer #${"%06X".format(from and 0xFFFFFF)} par #${"%06X".format(to and 0xFFFFFF)} ?")
                    .setMessage("Sur toutes les frames ?")
                    .setPositiveButton("Toutes") { _, _ ->
                        pushUndo()
                        val n = ColorOps.replaceColor(project, from, to, allFrames = true)
                        binding.canvas.syncFrameBitmap()
                        framesAdapter.notifyDataSetChanged()
                        toast("$n pixels remplacés")
                    }
                    .setNegativeButton("Frame actuelle") { _, _ ->
                        pushUndo()
                        val n = ColorOps.replaceColor(project, from, to, allFrames = false)
                        binding.canvas.syncFrameBitmap()
                        framesAdapter.notifyDataSetChanged()
                        toast("$n pixels remplacés")
                    }
                    .setNeutralButton(R.string.cancel, null)
                    .show()
            }
            .show()
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
            getString(R.string.new_project),
            getString(R.string.save),
            getString(R.string.load),
            getString(R.string.export_png),
            "Exporter chaque frame en PNG séparé",
            getString(R.string.export_sheet),
            getString(R.string.export_gif),
            "Importer un sprite sheet…",
            getString(R.string.resize),
            "Mode de lecture animation…",
            "Verrouiller couleurs…",
            "Filtres / effets…",
            "Tutoriel"
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.menu)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showNewProjectDialog()
                    1 -> saveProject()
                    2 -> showLoadDialog()
                    3 -> exportPng()
                    4 -> exportAllFrames()
                    5 -> exportSpriteSheet()
                    6 -> exportGif()
                    7 -> importSpriteSheet()
                    8 -> showResizeDialog()
                    9 -> showPlayModeMenu()
                    10 -> showColorLockMenu()
                    11 -> showFiltersMenu()
                    12 -> showTutorial(force = true)
                }
            }
            .show()
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
            val cw = minOf(old.width, w); val ch = minOf(old.height, h)
            for (y in 0 until ch) for (x in 0 until cw) nf.set(x, y, old.get(x, y))
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
                toast(getString(R.string.saved))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showLoadDialog() {
        val list = ProjectStorage.list(this)
        if (list.isEmpty()) { toast(getString(R.string.no_projects)); return }
        val labels = list.map { "${it.optString("name")}  •  ${it.optInt("width")}×${it.optInt("height")}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.saved_projects)
            .setItems(labels) { _, which ->
                ProjectStorage.load(this, list[which].optString("id"))?.let {
                    project = it
                    applyProject()
                    toast("Chargé")
                }
            }
            .setNeutralButton("Supprimer…") { _, _ -> showDeleteDialog(list) }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
                val bmp = BitmapFactory.decodeStream(input)
                binding.canvas.bgBitmap = bmp
                if (bmp != null) showImageImportOptions(bmp)
            }
        }
    }

    private fun showImageImportOptions(bmp: Bitmap) {
        val items = arrayOf(
            "Garder comme image de fond uniquement",
            "Pixeliser → frame courante (avec tramage)",
            "Pixeliser → frame courante (sans tramage)",
            "Extraire palette uniquement (16 couleurs)",
            "Pixeliser → frame + remplacer palette"
        )
        AlertDialog.Builder(this)
            .setTitle("Image importée")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {} // keep as bg only
                    1 -> pixelizeIntoFrame(bmp, dither = true, applyPalette = false)
                    2 -> pixelizeIntoFrame(bmp, dither = false, applyPalette = false)
                    3 -> extractPaletteFromBg(bmp)
                    4 -> pixelizeIntoFrame(bmp, dither = true, applyPalette = true)
                }
            }
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
        val bmp = Bitmap.createBitmap(frame.width * scale, frame.height * scale, Bitmap.Config.ARGB_8888)
        if (scale == 1) {
            bmp.setPixels(frame.pixels, 0, frame.width, 0, 0, frame.width, frame.height)
        } else {
            val small = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
            small.setPixels(frame.pixels, 0, frame.width, 0, 0, frame.width, frame.height)
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
                    encoder.addFrame(f.pixels, project.delayForFrame(i))
                }
                encoder.encodeToBytes()
            }
            savePublicImage(bytes, "${project.name}.gif", "image/gif")
            toast(getString(R.string.gif_done))
        }
    }

    private fun savePublicImage(bytes: ByteArray, filename: String, mime: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PixelHero")
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    resolver.openOutputStream(it)?.use { o -> o.write(bytes) }
                    toast("Enregistré dans Pictures/PixelHero/$filename")
                } ?: toast("Échec d'enregistrement")
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "PixelHero")
                dir.mkdirs()
                val f = File(dir, filename)
                FileOutputStream(f).use { it.write(bytes) }
                sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f)))
                toast("Enregistré dans ${f.absolutePath}")
            }
        } catch (e: Exception) {
            toast("Erreur: ${e.message}")
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
}
