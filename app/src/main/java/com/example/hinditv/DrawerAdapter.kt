package com.example.hinditv

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

data class DRow(val channel: Channel, val fav: Boolean, val num: Int, val current: Boolean)

class DrawerAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit
) : ListAdapter<DRow, DrawerAdapter.VH>(Diff) {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val num: TextView = v.findViewById(R.id.dNum)
        val name: TextView = v.findViewById(R.id.dName)
        val star: TextView = v.findViewById(R.id.dStar)
    }

    object Diff : DiffUtil.ItemCallback<DRow>() {
        override fun areItemsTheSame(a: DRow, b: DRow) = a.channel.url == b.channel.url
        override fun areContentsTheSame(a: DRow, b: DRow) = a == b
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_drawer, parent, false)
        val holder = VH(v)
        v.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p)
        }
        v.setOnLongClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onLongClick(p)
            true
        }
        return holder
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val row = getItem(position)
        val ctx = h.itemView.context
        h.name.text = row.channel.name
        h.name.setTextColor(ContextCompat.getColor(ctx, if (row.current) R.color.accent else R.color.text))
        h.name.setTypeface(null, if (row.current) Typeface.BOLD else Typeface.NORMAL)
        h.num.text = if (row.num > 0) row.num.toString() else ""
        h.star.visibility = if (row.fav) View.VISIBLE else View.GONE
    }
}
