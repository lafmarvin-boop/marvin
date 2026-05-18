package com.pixelhero.app

import android.graphics.BitmapFactory
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * Project lifecycle dialogs: New / Resize / Save / Load / Multi-delete.
 * The [ProjectListAdapter] used by the load dialog is a top-level class
 * (previously an inner class of MainActivity) so it could move here.
 */

internal fun MainActivity.showNewProjectDialog() {
    val view = layoutInflater.inflate(R.layout.dialog_new_project, null)
    val inputW = view.findViewById<EditText>(R.id.inputW)
    val inputH = view.findViewById<EditText>(R.id.inputH)
    inputW.setText("64"); inputH.setText("64")
    fun preset(w: Int, h: Int) { inputW.setText(w.toString()); inputH.setText(h.toString()) }
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

internal fun MainActivity.showResizeDialog() {
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

internal fun MainActivity.resizeProject(w: Int, h: Int) {
    val newFrames = project.frames.map { old ->
        val nf = Frame(w, h).apply { tag = old.tag; delayMs = old.delayMs }
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

internal fun MainActivity.applyProject() {
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
    refreshStatusBadges()
    binding.frameIndexLabel.text = "Frame ${project.currentIndex + 1}/${project.frames.size}"
}

internal fun MainActivity.saveProject() {
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
            binding.btnQuickSave.visibility = View.VISIBLE
            rememberLastProject(project.id)
            toast(getString(R.string.saved))
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

/** One-tap save reusing the existing project name (no dialog). */
internal fun MainActivity.quickSaveProject() {
    ProjectStorage.save(this, project)
    isDirty = false; updateTitleDirty()
    rememberLastProject(project.id)
    toast("Sauvegardé : ${project.name}")
}

internal fun MainActivity.showLoadDialog() {
    val list = ProjectStorage.list(this)
    if (list.isEmpty()) { toast(getString(R.string.no_projects)); return }
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
        loadProjectById(items[which].id)
        dlg.dismiss()
    }
    dlg.show()
}

/** Load a project by id and apply it, also remembering it as last-opened. */
internal fun MainActivity.loadProjectById(id: String) {
    ProjectStorage.load(this, id)?.let {
        project = it
        applyProject()
        binding.btnQuickSave.visibility = View.VISIBLE
        rememberLastProject(id)
        toast("Chargé : ${it.name}")
    }
}

/** Store the most recently opened project id so we can offer to resume. */
internal fun MainActivity.rememberLastProject(id: String) {
    getSharedPreferences("settings", MODE_PRIVATE).edit()
        .putString("lastProjectId", id).apply()
}

internal fun MainActivity.lastProjectId(): String? =
    getSharedPreferences("settings", MODE_PRIVATE).getString("lastProjectId", null)

/**
 * On startup, if a previous project was open, show a non-intrusive prompt
 * to resume. The user can dismiss it (default new project stays loaded)
 * or accept (the saved project replaces the default).
 */
internal fun MainActivity.maybeOfferResumeLastProject() {
    val id = lastProjectId() ?: return
    val list = ProjectStorage.list(this)
    val entry = list.firstOrNull { it.optString("id") == id } ?: return
    val name = entry.optString("name", "?")
    val dateStr = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.FRENCH)
        .format(java.util.Date(entry.optLong("updatedAt", 0)))
    AlertDialog.Builder(this)
        .setTitle("Continuer ?")
        .setMessage("Dernier projet ouvert :\n\n« $name » — $dateStr\n\nLe rouvrir maintenant ?")
        .setPositiveButton("Continuer") { _, _ -> loadProjectById(id) }
        .setNegativeButton("Nouveau projet", null)
        .show()
}

internal fun MainActivity.showDeleteDialog(list: List<org.json.JSONObject>) {
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
        .setNeutralButton("Tout cocher") { _, _ -> showDeleteDialogAllChecked(list) }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

internal fun MainActivity.showDeleteDialogAllChecked(list: List<org.json.JSONObject>) {
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

/** Snapshot of one saved project entry — used in the load list. */
data class ProjectListItem(
    val id: String, val name: String, val width: Int, val height: Int,
    val frameCount: Int, val updatedAt: Long
)

/** ListView adapter rendering project rows in the load dialog. */
class ProjectListAdapter(
    private val activity: MainActivity,
    private val items: List<ProjectListItem>
) : BaseAdapter() {
    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Any = items[position]
    override fun getItemId(position: Int): Long = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val v = convertView ?: activity.layoutInflater.inflate(R.layout.item_project, parent, false)
        val item = items[position]
        v.findViewById<TextView>(R.id.projectName).text = item.name
        val date = java.text.SimpleDateFormat("dd/MM/yy HH:mm", java.util.Locale.FRENCH)
            .format(java.util.Date(item.updatedAt))
        v.findViewById<TextView>(R.id.projectMeta).text =
            "${item.width}×${item.height} • ${item.frameCount} image(s) • $date"
        val iv = v.findViewById<ImageView>(R.id.projectThumb)
        val thumb = ProjectStorage.thumbnailFile(activity, item.id)
        if (thumb != null) {
            val bmp = BitmapFactory.decodeFile(thumb.absolutePath)
            val drawable = if (bmp != null) {
                android.graphics.drawable.BitmapDrawable(activity.resources, bmp).apply { isFilterBitmap = false }
            } else null
            iv.setImageDrawable(drawable)
        } else iv.setImageDrawable(null)
        return v
    }
}
