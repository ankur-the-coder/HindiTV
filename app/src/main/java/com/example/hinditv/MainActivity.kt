package com.example.hinditv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private enum class Tab { ALL, FAV, NUM }

    private lateinit var adapter: ChannelAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var titleView: TextView
    private lateinit var status: TextView
    private lateinit var searchBox: EditText
    private lateinit var btnAll: Button
    private lateinit var btnFav: Button
    private lateinit var btnNum: Button
    private lateinit var numberEntry: NumberEntry

    private val handler = Handler(Looper.getMainLooper())
    private var rawAll: List<Channel> = emptyList()
    private var all: List<Channel> = emptyList()
    private var shown: List<Channel> = emptyList()
    private var tab = Tab.ALL
    private var query = ""
    private var loadedKey = ""
    private var loadJob: Job? = null
    private var returnedFromPlayer = false
    private var resumeChecked = false
    private var firstFocusDone = false
    private var coldStart = true
    private var longDigit = false
    private var lastBack = 0L

    private val searchRunnable = Runnable {
        query = searchBox.text.toString().trim()
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        coldStart = savedInstanceState == null

        titleView = findViewById(R.id.title)
        status = findViewById(R.id.status)
        searchBox = findViewById(R.id.search)
        btnAll = findViewById(R.id.btnAll)
        btnFav = findViewById(R.id.btnFav)
        btnNum = findViewById(R.id.btnNum)
        val btnSettings: Button = findViewById(R.id.btnSettings)
        val btnRefresh: Button = findViewById(R.id.btnRefresh)

        numberEntry = NumberEntry(
            handler,
            { UserData.numberSet() },
            { text -> status.text = "Channel number: $text" },
            { n -> playNumber(n) }
        )

        adapter = ChannelAdapter(
            onClick = { pos -> play(pos) },
            onLongClick = { pos -> showChannelMenu(pos) }
        )
        recycler = findViewById(R.id.recycler)
        val dp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val span = (dp / 240f).toInt().coerceIn(3, 6)
        recycler.layoutManager = GridLayoutManager(this, span)
        recycler.adapter = adapter
        recycler.setHasFixedSize(true)
        recycler.itemAnimator = null
        recycler.setItemViewCacheSize(20)

        btnAll.setOnClickListener { tab = Tab.ALL; render() }
        btnFav.setOnClickListener { tab = Tab.FAV; render() }
        btnNum.setOnClickListener { tab = Tab.NUM; render() }
        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnRefresh.setOnClickListener { load(true, false) }

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                handler.removeCallbacks(searchRunnable)
                handler.postDelayed(searchRunnable, 250)
            }
        })
        searchBox.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
                handler.removeCallbacks(searchRunnable)
                query = searchBox.text.toString().trim()
                render { if (shown.isNotEmpty()) focusPosition(0) }
                true
            } else false
        }

        load(Settings.autoUpdate, true)
    }

    override fun onResume() {
        super.onResume()
        if (Repository.current().key != loadedKey) {
            load(true, true)
            return
        }
        if (rawAll.isNotEmpty()) {
            applySort()
            if (returnedFromPlayer) {
                returnedFromPlayer = false
                render {
                    val url = Session.channels.getOrNull(Session.index)?.url
                    val i = shown.indexOfFirst { it.url == url }
                    if (i >= 0) focusPosition(i)
                }
            } else {
                render()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    // ---------------------------------------------------------------- loading

    private fun load(download: Boolean, clear: Boolean) {
        loadJob?.cancel()
        val src = Repository.current()
        loadedKey = src.key
        titleView.text = src.name + " TV"
        if (clear) {
            rawAll = emptyList()
            all = emptyList()
            render()
        }
        loadJob = lifecycleScope.launch {
            val cached = withContext(Dispatchers.IO) { Repository.readCache(applicationContext) }
            if (cached.isNotEmpty()) {
                setAll(cached)
                status.text = status.text.toString() + "   (updating...)"
            } else {
                status.text = "Loading channels..."
            }
            if (!download && cached.isNotEmpty()) return@launch

            val result = withContext(Dispatchers.IO) { runCatching { Repository.download(applicationContext) } }
            result.onSuccess {
                setAll(it)
            }.onFailure { e ->
                val msg = e.message ?: e.javaClass.simpleName
                val hint = if (msg.contains("Trust anchor") || msg.contains("SSL"))
                    " Turn on 'Relaxed SSL' in Settings." else ""
                status.text = if (rawAll.isEmpty())
                    "Could not load playlist: $msg.$hint Check internet and press Refresh."
                else
                    "Offline - showing saved list (${rawAll.size} channels)"
            }
        }
    }

    private fun setAll(list: List<Channel>) {
        rawAll = list
        applySort()
        if (!resumeChecked) {
            resumeChecked = true
            val last = UserData.last
            if (Settings.resumeLast && coldStart && last != null) {
                render()
                Session.open(all, last)
                returnedFromPlayer = true
                startActivity(Intent(this, PlayerActivity::class.java))
                return
            }
        }
        render {
            if (!firstFocusDone && shown.isNotEmpty() && currentFocus !is EditText) {
                firstFocusDone = true
                focusPosition(0)
            }
        }
    }

    private fun applySort() {
        all = if (Settings.sort == 1) rawAll.sortedBy { it.name.lowercase() } else rawAll
        Session.all = all
    }

    // ---------------------------------------------------------------- list

    private fun render(after: (() -> Unit)? = null) {
        val base: List<Channel> = when (tab) {
            Tab.ALL -> all
            Tab.FAV -> UserData.favoriteList()
            Tab.NUM -> UserData.numberedList()
        }
        shown = if (query.isEmpty()) base else base.filter {
            it.name.contains(query, true) || (it.group?.contains(query, true) == true)
        }
        val logos = Settings.logos
        val rows = shown.map { Row(it, UserData.isFav(it.url), UserData.numberOf(it.url), logos) }
        adapter.submitList(rows) { after?.invoke() }

        btnAll.isSelected = tab == Tab.ALL
        btnFav.isSelected = tab == Tab.FAV
        btnNum.isSelected = tab == Tab.NUM

        status.text = when {
            shown.isEmpty() && query.isNotEmpty() -> "No channels match \"$query\""
            shown.isEmpty() && tab == Tab.FAV -> "No favorites yet. Hold OK on a channel and choose Add to favorites."
            shown.isEmpty() && tab == Tab.NUM -> "No numbers yet. Hold OK on a channel and choose Assign number, or hold a number key (1-9) on a channel."
            shown.isEmpty() -> status.text.toString()
            else -> "${shown.size} channels   |   OK: play   Hold OK: menu   0-9: jump to channel number"
        }
    }

    private fun focusPosition(pos: Int) {
        if (pos !in shown.indices) return
        recycler.scrollToPosition(pos)
        recycler.post {
            val vh = recycler.findViewHolderForAdapterPosition(pos)
            if (vh != null) {
                vh.itemView.requestFocus()
            } else {
                recycler.postDelayed({
                    recycler.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                }, 120)
            }
        }
    }

    // ---------------------------------------------------------------- actions

    private fun play(pos: Int) {
        if (pos !in shown.indices) return
        Session.channels = shown
        Session.index = pos
        returnedFromPlayer = true
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun playNumber(n: Int) {
        val ch = UserData.channelFor(n)
        if (ch == null) {
            Toast.makeText(this, "No channel on number $n", Toast.LENGTH_SHORT).show()
            render()
            return
        }
        Session.open(all, ch)
        returnedFromPlayer = true
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun showChannelMenu(pos: Int) {
        val ch = shown.getOrNull(pos) ?: return
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        labels.add("Play")
        actions.add { play(pos) }
        labels.add(if (UserData.isFav(ch.url)) "Remove from favorites" else "Add to favorites")
        actions.add { UserData.toggleFav(ch); render() }
        val n = UserData.numberOf(ch.url)
        labels.add(if (n > 0) "Change number (now $n)" else "Assign number")
        actions.add { Dialogs.assignNumber(this, ch) { render() } }
        if (n > 0) {
            labels.add("Remove number $n")
            actions.add { UserData.removeNumber(n); render() }
        }
        AlertDialog.Builder(this)
            .setTitle(ch.name)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    /** Hold a number key (1-9) on a focused channel to give it that number. */
    private fun assignFocused(d: Int) {
        val v = currentFocus ?: return
        val pos = recycler.getChildAdapterPosition(v)
        val ch = shown.getOrNull(pos) ?: return
        UserData.assign(d, ch)
        Toast.makeText(this, ch.name + " is now number " + d, Toast.LENGTH_LONG).show()
        render()
    }

    // ---------------------------------------------------------------- keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (digitOf(keyCode) >= 0) {
            event.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        val d = digitOf(keyCode)
        if (d in 1..9) {
            longDigit = true
            assignFocused(d)
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val d = digitOf(keyCode)
        if (d >= 0) {
            if (!longDigit) numberEntry.digit(d)
            longDigit = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (searchBox.text.isNotEmpty()) {
            searchBox.setText("")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastBack < 2000) {
            super.onBackPressed()
        } else {
            lastBack = now
            Toast.makeText(this, "Press BACK again to exit", Toast.LENGTH_SHORT).show()
        }
    }
}
