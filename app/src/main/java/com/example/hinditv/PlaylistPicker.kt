package com.example.hinditv

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Full-screen playlist chooser: Hindi and English first, then countries, languages and categories. */
object PlaylistPicker {

    fun show(ctx: Context, onPick: (Repository.Source) -> Unit) {
        val dlg = Dialog(ctx, R.style.PickerDialog)
        val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_playlists, null)
        dlg.setContentView(v)

        val rows = ArrayList<Any>()
        val order = listOf("Top", "Countries", "Languages", "Categories")
        val titles = mapOf(
            "Top" to "Featured",
            "Countries" to "Countries",
            "Languages" to "Languages",
            "Categories" to "Categories"
        )
        for (sec in order) {
            val items = Repository.PRESETS.filter { it.section == sec }
            if (items.isEmpty()) continue
            rows.add(titles[sec] ?: sec)
            rows.addAll(items)
        }

        val dm = ctx.resources.displayMetrics
        val dp = dm.widthPixels / dm.density
        val span = ((dp - 120f) / 210f).toInt().coerceIn(3, 6)
        val currentKey = Repository.current().key
        val currentIdx = rows.indexOfFirst { it is Repository.Source && it.key == currentKey }.coerceAtLeast(1)

        val rv = v.findViewById<RecyclerView>(R.id.pickList)
        val gm = GridLayoutManager(ctx, span)
        gm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (rows[position] is String) span else 1
        }
        rv.layoutManager = gm
        rv.itemAnimator = null
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemViewType(position: Int) = if (rows[position] is String) 0 else 1

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val inf = LayoutInflater.from(parent.context)
                return if (viewType == 0) {
                    object : RecyclerView.ViewHolder(inf.inflate(R.layout.item_playlist_header, parent, false)) {}
                } else {
                    val view = inf.inflate(R.layout.item_playlist, parent, false)
                    val h = object : RecyclerView.ViewHolder(view) {}
                    view.setOnClickListener {
                        val p = h.bindingAdapterPosition
                        val src = rows.getOrNull(p) as? Repository.Source ?: return@setOnClickListener
                        dlg.dismiss()
                        onPick(src)
                    }
                    view.setOnFocusChangeListener { fv, has ->
                        fv.animate().scaleX(if (has) 1.05f else 1f).scaleY(if (has) 1.05f else 1f).setDuration(110).start()
                    }
                    h
                }
            }

            override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
                val row = rows[position]
                if (row is String) {
                    (h.itemView as TextView).text = row.uppercase()
                } else if (row is Repository.Source) {
                    val view = h.itemView
                    view.findViewById<TextView>(R.id.pCode).text = row.code
                    view.findViewById<TextView>(R.id.pName).text = row.name
                    view.findViewById<TextView>(R.id.pSub).text =
                        if (row.key == currentKey) "Now playing from here" else row.section.removeSuffix("ies").removeSuffix("s")
                    view.findViewById<View>(R.id.pCheck).visibility = if (row.key == currentKey) View.VISIBLE else View.GONE
                }
            }

            override fun getItemCount() = rows.size
        }

        dlg.show()
        dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        rv.post {
            gm.scrollToPositionWithOffset(currentIdx, 200)
            rv.postDelayed({
                rv.findViewHolderForAdapterPosition(currentIdx)?.itemView?.requestFocus()
                    ?: rv.findViewHolderForAdapterPosition(1)?.itemView?.requestFocus()
            }, 150)
        }
    }
}
