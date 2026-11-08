package com.example.hinditv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load

data class Row(val channel: Channel, val fav: Boolean, val num: Int, val logos: Boolean)

class ChannelAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit
) : ListAdapter<Row, ChannelAdapter.VH>(Diff) {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val logo: ImageView = v.findViewById(R.id.logo)
        val num: TextView = v.findViewById(R.id.num)
        val name: TextView = v.findViewById(R.id.name)
        val star: TextView = v.findViewById(R.id.star)
    }

    object Diff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(a: Row, b: Row) = a.channel.url == b.channel.url
        override fun areContentsTheSame(a: Row, b: Row) = a == b
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
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
        h.name.text = row.channel.name
        h.star.visibility = if (row.fav) View.VISIBLE else View.GONE
        if (row.num > 0) {
            h.num.text = row.num.toString()
            h.num.visibility = View.VISIBLE
        } else {
            h.num.visibility = View.GONE
        }
        val logo = row.channel.logo
        if (!row.logos || logo.isNullOrEmpty()) {
            h.logo.setImageResource(R.drawable.ic_tv)
        } else {
            h.logo.load(logo) {
                placeholder(R.drawable.ic_tv)
                error(R.drawable.ic_tv)
            }
        }
    }
}
