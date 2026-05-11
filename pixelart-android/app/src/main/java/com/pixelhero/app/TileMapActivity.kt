package com.pixelhero.app

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayOutputStream

/**
 * Tile map painter. Reads the current project's frames as TILES (each frame = one tile)
 * and lets you paint a grid of tile indices to compose a level/map.
 *
 * The tile map is saved as a JSON blob in the project file with a list of frame indices.
 */
class TileMapActivity : AppCompatActivity() {

    private lateinit var project: Project
    private var mapCols = 16
    private var mapRows = 12
    private var map: IntArray = IntArray(mapCols * mapRows) { -1 }
    private var selectedTile: Int = 0

    private lateinit var tilesetGrid: TileSetView
    private lateinit var mapView: TileMapView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pid = intent.getStringExtra("projectId") ?: return finish()
        project = ProjectStorage.load(this, pid) ?: return finish()
        loadMapFromIntent()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1E1E2A.toInt())
        }
        // Top bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF15151F.toInt())
        }
        val btnBack = Button(this).apply { text = "← Retour"; setOnClickListener { finish() } }
        val title = TextView(this).apply {
            text = "Carte de tuiles • ${mapCols}×${mapRows}"
            setTextColor(0xFFE8E8F0.toInt()); textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(16, 16, 16, 16)
        }
        val btnResize = Button(this).apply { text = "Taille…"; setOnClickListener { resizeMap() } }
        val btnExport = Button(this).apply { text = "Exporter PNG"; setOnClickListener { exportMap() } }
        val btnClear = Button(this).apply { text = "Effacer"; setOnClickListener { map.fill(-1); mapView.invalidate() } }
        top.addView(btnBack); top.addView(title); top.addView(btnResize); top.addView(btnExport); top.addView(btnClear)
        root.addView(top)

        // Map view
        mapView = TileMapView(this)
        mapView.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        root.addView(mapView)

        // Tileset (horizontal scroll)
        val tilesetLabel = TextView(this).apply {
            text = "Tuiles disponibles (chaque frame du projet est une tuile) :"
            setTextColor(0xFFB4B4C8.toInt()); textSize = 12f
            setPadding(16, 8, 16, 4)
        }
        root.addView(tilesetLabel)
        tilesetGrid = TileSetView(this)
        tilesetGrid.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 80)
        root.addView(tilesetGrid)

        setContentView(root)
    }

    private fun loadMapFromIntent() {
        // Try to load saved map from preferences
        val prefs = getSharedPreferences("tilemaps", MODE_PRIVATE)
        val savedJson = prefs.getString("map_${project.id}", null)
        if (savedJson != null) {
            runCatching {
                val obj = org.json.JSONObject(savedJson)
                mapCols = obj.getInt("cols")
                mapRows = obj.getInt("rows")
                val arr = obj.getJSONArray("data")
                map = IntArray(arr.length()) { arr.getInt(it) }
            }
        } else {
            map = IntArray(mapCols * mapRows) { -1 }
        }
    }

    private fun saveMap() {
        val obj = org.json.JSONObject()
        obj.put("cols", mapCols)
        obj.put("rows", mapRows)
        val arr = org.json.JSONArray()
        map.forEach { arr.put(it) }
        obj.put("data", arr)
        getSharedPreferences("tilemaps", MODE_PRIVATE).edit()
            .putString("map_${project.id}", obj.toString())
            .apply()
    }

    override fun onPause() {
        super.onPause()
        saveMap()
    }

    private fun resizeMap() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(48, 24, 48, 24)
        }
        val etCols = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText("$mapCols"); setTextColor(0xFFE8E8F0.toInt()) }
        val etRows = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText("$mapRows"); setTextColor(0xFFE8E8F0.toInt()) }
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        etCols.layoutParams = lp; etRows.layoutParams = lp
        container.addView(etCols); container.addView(etRows)
        AlertDialog.Builder(this)
            .setTitle("Taille de la carte (cols × lignes)")
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                val c = etCols.text.toString().toIntOrNull()?.coerceIn(1, 128) ?: mapCols
                val r = etRows.text.toString().toIntOrNull()?.coerceIn(1, 128) ?: mapRows
                val newMap = IntArray(c * r) { -1 }
                for (y in 0 until minOf(r, mapRows)) for (x in 0 until minOf(c, mapCols)) {
                    newMap[y * c + x] = map[y * mapCols + x]
                }
                mapCols = c; mapRows = r; map = newMap
                mapView.invalidate()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun exportMap() {
        val tileW = project.width
        val tileH = project.height
        val bmp = Bitmap.createBitmap(mapCols * tileW, mapRows * tileH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint().apply { isFilterBitmap = false }
        for (y in 0 until mapRows) for (x in 0 until mapCols) {
            val tileIdx = map[y * mapCols + x]
            if (tileIdx < 0 || tileIdx >= project.frames.size) continue
            val frame = project.frames[tileIdx]
            val tileBmp = Bitmap.createBitmap(tileW, tileH, Bitmap.Config.ARGB_8888)
            val src = if (frame.layers.size > 1) frame.composited() else frame.pixels
            tileBmp.setPixels(src, 0, tileW, 0, 0, tileW, tileH)
            canvas.drawBitmap(tileBmp, (x * tileW).toFloat(), (y * tileH).toFloat(), paint)
            tileBmp.recycle()
        }
        // Save via standard MediaStore - need a callback into the activity
        val bytes = ByteArrayOutputStream().apply { bmp.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        bmp.recycle()
        ExportHelper.savePublicImage(this, bytes, "${project.name}_tilemap.png", "image/png")
        Toast.makeText(this, "Carte exportée", Toast.LENGTH_SHORT).show()
    }

    // ----- Inner views -----

    inner class TileMapView(context: android.content.Context) : View(context) {
        private val gridPaint = Paint().apply { color = 0x33FFFFFF; style = Paint.Style.STROKE }
        private val emptyPaint = Paint().apply { color = 0xFF2A2A3A.toInt() }
        private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                val cellW = width.toFloat() / mapCols
                val cellH = height.toFloat() / mapRows
                val cx = (event.x / cellW).toInt()
                val cy = (event.y / cellH).toInt()
                if (cx in 0 until mapCols && cy in 0 until mapRows) {
                    map[cy * mapCols + cx] = selectedTile
                    invalidate()
                }
                return true
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            val cellW = width.toFloat() / mapCols
            val cellH = height.toFloat() / mapRows
            for (y in 0 until mapRows) for (x in 0 until mapCols) {
                val rx = x * cellW; val ry = y * cellH
                val tileIdx = map[y * mapCols + x]
                if (tileIdx < 0 || tileIdx >= project.frames.size) {
                    canvas.drawRect(rx, ry, rx + cellW, ry + cellH, emptyPaint)
                } else {
                    val f = project.frames[tileIdx]
                    val src = if (f.layers.size > 1) f.composited() else f.pixels
                    val bmp = Bitmap.createBitmap(f.width, f.height, Bitmap.Config.ARGB_8888)
                    bmp.setPixels(src, 0, f.width, 0, 0, f.width, f.height)
                    canvas.drawBitmap(bmp, null, android.graphics.RectF(rx, ry, rx + cellW, ry + cellH), paint)
                    bmp.recycle()
                }
            }
            // Grid lines
            for (x in 0..mapCols) canvas.drawLine(x * cellW, 0f, x * cellW, height.toFloat(), gridPaint)
            for (y in 0..mapRows) canvas.drawLine(0f, y * cellH, width.toFloat(), y * cellH, gridPaint)
        }
    }

    inner class TileSetView(context: android.content.Context) : View(context) {
        private val paint = Paint().apply { isFilterBitmap = false }
        private val selPaint = Paint().apply { color = 0xFF5566FF.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN) {
                val cellW = height // square cells
                val idx = (event.x / cellW).toInt()
                if (idx in 0 until project.frames.size) {
                    selectedTile = idx
                    invalidate()
                }
                return true
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            val cellW = height
            project.frames.forEachIndexed { i, f ->
                val src = if (f.layers.size > 1) f.composited() else f.pixels
                val bmp = Bitmap.createBitmap(f.width, f.height, Bitmap.Config.ARGB_8888)
                bmp.setPixels(src, 0, f.width, 0, 0, f.width, f.height)
                val rx = (i * cellW).toFloat()
                canvas.drawBitmap(bmp, null, android.graphics.RectF(rx, 0f, rx + cellW, height.toFloat()), paint)
                bmp.recycle()
                if (i == selectedTile) {
                    canvas.drawRect(rx, 0f, rx + cellW, height.toFloat(), selPaint)
                }
            }
        }
    }

    companion object {
        fun start(context: android.content.Context, projectId: String) {
            context.startActivity(Intent(context, TileMapActivity::class.java).apply {
                putExtra("projectId", projectId)
            })
        }
    }
}
