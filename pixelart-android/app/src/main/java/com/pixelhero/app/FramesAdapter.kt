package com.pixelhero.app

import android.graphics.Bitmap
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import androidx.recyclerview.widget.RecyclerView

class FramesAdapter(
    private val project: Project,
    private val onSelect: (Int) -> Unit,
    private val onMove: (Int, Int) -> Unit,
    private val onLongPress: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<FramesAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val root: LinearLayout = view as LinearLayout
        val thumb: ImageView = view.findViewById(R.id.frameThumb)
        val label: TextView = view.findViewById(R.id.frameLabel)
        val btnUp: View = view.findViewById(R.id.btnUp)
        val btnDown: View = view.findViewById(R.id.btnDown)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_frame, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val frame = project.frames[position]
        val tagText = if (frame.tag.isNotBlank()) "  • ${frame.tag}" else ""
        val delayText = if (frame.delayMs > 0) "  (${frame.delayMs}ms)" else ""
        holder.label.text = "#${position + 1}$tagText$delayText"

        val bmp = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val src = if (frame.layers.size > 1) frame.composited() else frame.pixels
        bmp.setPixels(src, 0, frame.width, 0, 0, frame.width, frame.height)
        val drawable = bmp.toDrawable(holder.thumb.resources)
        drawable.setAntiAlias(false)
        drawable.isFilterBitmap = false
        holder.thumb.setImageDrawable(drawable)
        holder.thumb.background = null

        val selected = position == project.currentIndex
        holder.root.setBackgroundResource(R.drawable.panel_bg)
        holder.root.alpha = if (selected) 1f else 0.7f
        holder.label.setTextColor(if (selected) Color.WHITE else 0xFFB4B4C8.toInt())

        holder.itemView.setOnClickListener { onSelect(position) }
        holder.itemView.setOnLongClickListener {
            onLongPress?.invoke(position)
            true
        }
        holder.btnUp.setOnClickListener { if (position > 0) onMove(position, position - 1) }
        holder.btnDown.setOnClickListener { if (position < project.frames.size - 1) onMove(position, position + 1) }
    }

    override fun getItemCount(): Int = project.frames.size
}
