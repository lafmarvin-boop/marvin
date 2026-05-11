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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pixelhero.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var project: Project
    private lateinit var paletteAdapter: SwatchAdapter
    private lateinit var recentAdapter: SwatchAdapter
    private lateinit var framesAdapter: FramesAdapter

    private val undoStack = ArrayDeque<UndoSnapshot>()
    private val redoStack = ArrayDeque<UndoSnapshot>()
    private val maxUndo = 80

    private var animTimer: Runnable? = null
    private val animHandler = Handler(Looper.getMainLooper())
    private var isPlaying = false
    private var savedFrameIdx = 0
    private var playIdx = 0

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

        // Show new project dialog on first launch
        if (getPreferences(MODE_PRIVATE).getBoolean("firstRun", true)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("firstRun", false).apply()
            showNewProjectDialog()
        } else {
            applyProject()
        }
    }

    // ---- Canvas wiring ----
    private fun wireCanvas() {
        binding.canvas.project = project
        binding.canvas.color = 0xFFFF5577.toInt()
        binding.canvas.tool = Tool.PENCIL
        binding.canvas.onStrokeStart = { pushUndo() }
        binding.canvas.onProjectChanged = {
            framesAdapter.notifyItemChanged(project.currentIndex)
        }
        binding.canvas.onColorPicked = { c ->
            setColor(c)
        }
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
            binding.toolMove to Tool.MOVE
        )
        binding.toolPencil.isSelected = true
        tools.forEach { (btn, tool) ->
            btn.setOnClickListener {
                tools.keys.forEach { it.isSelected = false }
                btn.isSelected = true
                binding.canvas.tool = tool
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
    }

    // ---- Top bar ----
    private fun wireTopBar() {
        binding.btnUndo.setOnClickListener { doUndo() }
        binding.btnRedo.setOnClickListener { doRedo() }
        binding.btnPlay.setOnClickListener { togglePlay() }
        binding.btnMenu.setOnClickListener { showMenu() }
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

        binding.btnBgLoad.setOnClickListener {
            pickBg.launch(arrayOf("image/*"))
        }
        binding.btnBgClear.setOnClickListener {
            binding.canvas.bgBitmap = null
        }
        binding.bgOpacity.setOnSeekBarChangeListener(simpleSeekListener { v ->
            binding.canvas.bgOpacity = v / 100f
            binding.canvas.invalidate()
        })
        binding.onionOpacity.setOnSeekBarChangeListener(simpleSeekListener { v ->
            binding.canvas.onionOpacity = v / 100f
            binding.canvas.showOnion = v > 0
            binding.canvas.invalidate()
        })

        binding.fpsInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) project.fps = binding.fpsInput.text.toString().toIntOrNull()?.coerceIn(1, 60) ?: 8
        }
    }

    private fun simpleSeekListener(callback: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { callback(progress) }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    // ---- Frames ----
    private fun wireFrames() {
        framesAdapter = FramesAdapter(project,
            onSelect = { idx ->
                project.currentIndex = idx
                refreshAfterFrameChange()
            },
            onMove = { from, to ->
                val item = project.frames.removeAt(from)
                project.frames.add(to, item)
                if (project.currentIndex == from) project.currentIndex = to
                else if (project.currentIndex == to) project.currentIndex = from
                framesAdapter.notifyDataSetChanged()
                refreshAfterFrameChange()
            }
        )
        binding.framesList.layoutManager = LinearLayoutManager(this)
        binding.framesList.adapter = framesAdapter

        binding.btnFrameAdd.setOnClickListener {
            project.frames.add(Frame(project.width, project.height))
            project.currentIndex = project.frames.size - 1
            framesAdapter.notifyDataSetChanged()
            refreshAfterFrameChange()
        }
        binding.btnFrameDup.setOnClickListener {
            val copy = project.currentFrame.copy()
            project.frames.add(project.currentIndex + 1, copy)
            project.currentIndex++
            framesAdapter.notifyDataSetChanged()
            refreshAfterFrameChange()
        }
        binding.btnFrameDel.setOnClickListener {
            if (project.frames.size <= 1) {
                toast("Au moins une frame requise")
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setMessage(R.string.confirm_delete_frame)
                .setPositiveButton(R.string.delete) { _, _ ->
                    project.frames.removeAt(project.currentIndex)
                    project.currentIndex = project.currentIndex.coerceAtMost(project.frames.size - 1)
                    framesAdapter.notifyDataSetChanged()
                    refreshAfterFrameChange()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun refreshAfterFrameChange() {
        binding.canvas.project = project
        binding.canvas.syncFrameBitmap()
        binding.canvas.syncOnionBitmap()
        framesAdapter.notifyDataSetChanged()
    }

    // ---- Color ----
    private fun setColor(c: Int) {
        binding.canvas.color = c
        project.setColor(c)
        binding.currentColorSwatch.setBackgroundColor(c)
        binding.currentColorHex.text = String.format("#%08X", c)
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
        val c = binding.canvas.color
        seekR.progress = Color.red(c)
        seekG.progress = Color.green(c)
        seekB.progress = Color.blue(c)
        hex.setText(String.format("#%02X%02X%02X", Color.red(c), Color.green(c), Color.blue(c)))
        preview.setBackgroundColor(c)
        val update = {
            val col = Color.rgb(seekR.progress, seekG.progress, seekB.progress) or 0xFF000000.toInt()
            preview.setBackgroundColor(col)
            hex.setText(String.format("#%02X%02X%02X", Color.red(col), Color.green(col), Color.blue(col)))
        }
        listOf(seekR, seekG, seekB).forEach {
            it.setOnSeekBarChangeListener(simpleSeekListener { _ -> update() })
        }
        hex.setOnFocusChangeListener { _, has ->
            if (!has) {
                val txt = hex.text.toString().removePrefix("#")
                runCatching {
                    val v = Integer.parseInt(txt, 16)
                    val full = 0xFF000000.toInt() or v
                    seekR.progress = Color.red(full)
                    seekG.progress = Color.green(full)
                    seekB.progress = Color.blue(full)
                    preview.setBackgroundColor(full)
                }
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
            getString(R.string.export_sheet),
            getString(R.string.export_gif),
            getString(R.string.resize)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.menu)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showNewProjectDialog()
                    1 -> saveProject()
                    2 -> showLoadDialog()
                    3 -> exportPng()
                    4 -> exportSpriteSheet()
                    5 -> exportGif()
                    6 -> showResizeDialog()
                }
            }
            .show()
    }

    private fun showNewProjectDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_new_project, null)
        val inputW = view.findViewById<EditText>(R.id.inputW)
        val inputH = view.findViewById<EditText>(R.id.inputH)
        fun preset(w: Int, h: Int) {
            inputW.setText(w.toString())
            inputH.setText(h.toString())
        }
        view.findViewById<View>(R.id.preset16).setOnClickListener { preset(16, 16) }
        view.findViewById<View>(R.id.preset24).setOnClickListener { preset(24, 24) }
        view.findViewById<View>(R.id.preset32).setOnClickListener { preset(32, 32) }
        view.findViewById<View>(R.id.preset48).setOnClickListener { preset(48, 48) }
        view.findViewById<View>(R.id.preset64).setOnClickListener { preset(64, 64) }
        view.findViewById<View>(R.id.preset128).setOnClickListener { preset(128, 128) }
        AlertDialog.Builder(this)
            .setTitle(R.string.new_project)
            .setView(view)
            .setPositiveButton(R.string.create) { _, _ ->
                val w = inputW.text.toString().toIntOrNull()?.coerceIn(1, 512) ?: 32
                val h = inputH.text.toString().toIntOrNull()?.coerceIn(1, 512) ?: 32
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
                val w = view.findViewById<EditText>(R.id.inputW).text.toString().toIntOrNull()?.coerceIn(1, 512) ?: return@setPositiveButton
                val h = view.findViewById<EditText>(R.id.inputH).text.toString().toIntOrNull()?.coerceIn(1, 512) ?: return@setPositiveButton
                resizeProject(w, h)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun resizeProject(w: Int, h: Int) {
        val newFrames = project.frames.map { old ->
            val nf = Frame(w, h)
            val cw = minOf(old.width, w); val ch = minOf(old.height, h)
            for (y in 0 until ch) for (x in 0 until cw) {
                nf.set(x, y, old.get(x, y))
            }
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
            }
        )
        binding.framesList.adapter = framesAdapter
        paletteAdapter = SwatchAdapter(project.palette, { setColor(it) }, { it == binding.canvas.color })
        recentAdapter = SwatchAdapter(project.recentColors, { setColor(it) }, { it == binding.canvas.color })
        binding.paletteList.adapter = paletteAdapter
        binding.recentList.adapter = recentAdapter
        binding.fpsInput.setText(project.fps.toString())
        setColor(binding.canvas.color)
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
        if (list.isEmpty()) {
            toast(getString(R.string.no_projects))
            return
        }
        val labels = list.map { "${it.optString("name")}  •  ${it.optInt("width")}×${it.optInt("height")}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.saved_projects)
            .setItems(labels) { _, which ->
                val id = list[which].optString("id")
                ProjectStorage.load(this, id)?.let {
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
            }
        }
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
                val delay = (1000 / project.fps.coerceIn(1, 60))
                project.frames.forEach { encoder.addFrame(it.pixels, delay) }
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
                // Notify gallery
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

    private fun startPlay() {
        if (project.frames.size < 2) {
            toast("Ajoutez au moins 2 frames")
            return
        }
        isPlaying = true
        savedFrameIdx = project.currentIndex
        playIdx = 0
        binding.btnPlay.setImageResource(R.drawable.ic_stop)
        val delay = (1000L / max(1, project.fps))
        val r = object : Runnable {
            override fun run() {
                if (!isPlaying) return
                project.currentIndex = playIdx
                binding.canvas.syncFrameBitmap()
                playIdx = (playIdx + 1) % project.frames.size
                animHandler.postDelayed(this, delay)
            }
        }
        animTimer = r
        animHandler.post(r)
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

    override fun onPause() {
        super.onPause()
        if (isPlaying) stopPlay()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
