package com.example.hinditv

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var favs: FavoritesStore
    private lateinit var adapter: ChannelAdapter
    private lateinit var status: TextView
    private lateinit var btnAll: Button
    private lateinit var btnFav: Button

    private var all: List<Channel> = emptyList()
    private var shown: List<Channel> = emptyList()
    private var favOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        favs = FavoritesStore(this)
        status = findViewById(R.id.status)
        btnAll = findViewById(R.id.btnAll)
        btnFav = findViewById(R.id.btnFav)
        val btnRefresh: Button = findViewById(R.id.btnRefresh)

        adapter = ChannelAdapter(
            onClick = { pos -> play(pos) },
            onLongClick = { pos -> toggleFavorite(pos) }
        )
        val recycler: RecyclerView = findViewById(R.id.recycler)
        recycler.layoutManager = GridLayoutManager(this, 4)
        recycler.adapter = adapter
        recycler.setHasFixedSize(true)
        recycler.itemAnimator = null
        recycler.setItemViewCacheSize(20)

        btnAll.setOnClickListener { favOnly = false; render() }
        btnFav.setOnClickListener { favOnly = true; render() }
        btnRefresh.setOnClickListener { load() }

        load()
    }

    override fun onResume() {
        super.onResume()
        // Favorites may have changed in the player
        if (all.isNotEmpty()) render()
    }

    private fun load() {
        lifecycleScope.launch {
            val cached = withContext(Dispatchers.IO) { Repository.readCache(applicationContext) }
            if (cached.isNotEmpty()) {
                all = cached
                render()
                status.text = "${status.text}  (updating...)"
            } else {
                status.text = "Loading channels..."
            }

            val result = withContext(Dispatchers.IO) {
                runCatching { Repository.download(applicationContext) }
            }
            result.onSuccess {
                all = it
                render()
            }.onFailure {
                status.text = if (all.isEmpty())
                    "Could not load playlist: ${it.message}. Check internet and press Refresh."
                else
                    "Offline - showing saved list (${all.size} channels)"
            }
        }
    }

    private fun render() {
        shown = if (favOnly) all.filter { favs.isFav(it.url) } else all
        adapter.submitList(shown.map { Row(it, favs.isFav(it.url)) })
        btnAll.isSelected = !favOnly
        btnFav.isSelected = favOnly
        status.text = if (favOnly && shown.isEmpty())
            "No favorites yet. Long-press OK on a channel to add one."
        else
            "${shown.size} channels  |  OK: play   Hold OK: favorite"
    }

    private fun play(pos: Int) {
        if (pos !in shown.indices) return
        ChannelStore.channels = shown
        ChannelStore.index = pos
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun toggleFavorite(pos: Int) {
        if (pos !in shown.indices) return
        val ch = shown[pos]
        val now = favs.toggle(ch.url)
        Toast.makeText(this, if (now) "Added to favorites" else "Removed from favorites", Toast.LENGTH_SHORT).show()
        render()
    }
}
