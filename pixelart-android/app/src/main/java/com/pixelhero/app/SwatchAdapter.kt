package com.pixelhero.app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

class SwatchAdapter(
    private val colors: List<Int>,
    private val onClick: (Int) -> Unit,
    private val isSelected: (Int) -> Boolean
) : RecyclerView.Adapter<SwatchAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val swatch: View = view.findViewById(R.id.swatch)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_swatch, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = colors[position]
        holder.swatch.setBackgroundColor(c)
        holder.itemView.setOnClickListener { onClick(c) }
        holder.swatch.alpha = if (isSelected(c)) 1f else 0.85f
        holder.swatch.scaleX = if (isSelected(c)) 1.1f else 1f
        holder.swatch.scaleY = if (isSelected(c)) 1.1f else 1f
    }

    override fun getItemCount(): Int = colors.size
}
