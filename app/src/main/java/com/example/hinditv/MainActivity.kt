package com.example.hinditv

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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

    private enum class Tab { ALL, FAV, NUM, RECENT }

    private lateinit var adapter: ChannelAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var searchBox: EditText
    private lateinit var btnAll: TextView
    private lateinit var btnFav: TextView
    private lateinit var btnNum: TextView
    private lateinit var btnRecent: TextView
    private lateinit var btnHd: TextView
    private lateinit var btn4k: TextView
    private lateinit var btnPlaylist: TextView
    private lateinit var numberEntry: NumberEntry

    private val handler = Handler(Looper.getMainLooper())
    private var rawAll: List<Channel> = emptyList()
    private var all: List<Channel> = emptyList()
    private var shown: List<Channel> = emptyList()
    private var tab = Tab.ALL
    private var query = ""
    private var qualityFilter = 0          // 0 = off, 1 = HD and better, 2 = 4K only
    private var loadedKey = ""
    private var loadJob: Job? = null
    private var returnedFromPlayer = false
    private var resumeChecked = false
    private var firstFocusDone = false
    private var coldStart = true
    private var crashedLastTime = false
    private var longDigit = false
    private var lastBack = 0L
    private var lastActKey = -1

    private val searchRunnable = Runnable {
        query = searchBox.text.toString().trim()
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        coldStart = savedInstanceState == null

        titleView = findViewById(R.id.title)
        subtitleView = findViewById(R.id.subtitle)
        status = findViewById(R.id.status)
        hint = findViewById(R.id.hint)
        searchBox = findViewById(R.id.search)
        btnAll = findViewById(R.id.btnAll)
        btnFav = findViewById(R.id.btnFav)
        btnNum = findViewById(R.id.btnNum)
        btnRecent = findViewById(R.id.btnRecent)
        btnHd = findViewById(R.id.btnHd)
        btn4k = findViewById(R.id.btn4k)
        btnPlaylist = findViewById(R.id.btnPlaylist)
        val btnSettings: View = findViewById(R.id.btnSettings)
        val btnRefresh: View = findViewById(R.id.btnRefresh)

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
        val span = ((dp - 80f) / 205f).toInt().coerceIn(3, 7)
        recycler.layoutManager = GridLayoutManager(this, span)
        recycler.adapter = adapter
        recycler.setHasFixedSize(true)
        recycler.itemAnimator = null
        recycler.setItemViewCacheSize(20)

        btnAll.setOnClickListener { tab = Tab.ALL; render() }
        btnFav.setOnClickListener { tab = Tab.FAV; render() }
        btnNum.setOnClickListener { tab = Tab.NUM; render() }
        btnRecent.setOnClickListener { tab = Tab.RECENT; render() }
        btnHd.setOnClickListener { toggleQuality(1) }
        btn4k.setOnClickListener { toggleQuality(2) }
        btnPlaylist.setOnClickListener { showPlaylists() }
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

        // Tell the user why the app closed last time instead of failing silently.
        val crash = if (coldStart) CrashLog.consume(this) else null
        if (crash != null) {
            crashedLastTime = true
            showCrashReport(crash)
        }

        buildHint()
        load(Settings.autoUpdate, true)
    }

    override fun onResume() {
        super.onResume()
        buildHint()
        if (Session.openSearch) {
            Session.openSearch = false
            handler.postDelayed({ openSearch() }, 400)
        }
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
                    if (i >= 0 && !searchBox.hasFocus()) focusPosition(i)
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
        titleView.text = "Hindi TV"
        subtitleView.text = src.name
        btnPlaylist.text = src.name
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
                status.text = "Loading " + src.name + " channels..."
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
            // After a crash we do not auto-open the last channel again (it could crash in a loop).
            if (Settings.resumeLast && coldStart && last != null && !crashedLastTime) {
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

    private fun matches(c: Channel): Boolean {
        if (query.isNotEmpty() &&
            !(c.name.contains(query, true) || (c.group?.contains(query, true) == true))
        ) return false
        return when (qualityFilter) {
            1 -> c.quality >= Quality.HD
            2 -> c.quality == Quality.UHD
            else -> true
        }
    }

    private fun render(after: (() -> Unit)? = null) {
        // Search and the HD / 4K filters only apply to the "All" list.
        // Favorites, Numbers and Recent always show everything in them.
        val filtering = tab == Tab.ALL
        shown = when (tab) {
            Tab.ALL -> if (query.isEmpty() && qualityFilter == 0) all else all.filter { matches(it) }
            Tab.FAV -> UserData.favoriteList()
            Tab.NUM -> UserData.numberedList()
            Tab.RECENT -> UserData.recentList()
        }
        val logos = Settings.logos
        val rows = shown.map { Row(it, UserData.isFav(it.url), UserData.numberOf(it.url), logos) }
        adapter.submitList(rows) { after?.invoke() }

        btnAll.isSelected = tab == Tab.ALL
        btnFav.isSelected = tab == Tab.FAV
        btnNum.isSelected = tab == Tab.NUM
        btnRecent.isSelected = tab == Tab.RECENT
        btnHd.isSelected = qualityFilter == 1
        btn4k.isSelected = qualityFilter == 2
        val dim = if (filtering) 1f else 0.4f
        searchBox.alpha = dim
        btnHd.alpha = dim
        btn4k.alpha = dim
        searchBox.hint = if (filtering) "Search channels" else "Search works in All channels"

        btnFav.text = "\u2605  Favorites  " + UserData.favoriteList().size
        btnNum.text = "Numbers  " + UserData.numberSet().size

        status.text = when {
            shown.isEmpty() && tab == Tab.FAV -> "No favorites yet. Hold OK on a channel and choose Add to favorites."
            shown.isEmpty() && tab == Tab.NUM -> "No numbers yet. Hold OK on a channel and choose Assign number, or hold a number key (1-9) on a channel."
            shown.isEmpty() && tab == Tab.RECENT -> "Channels you watch will appear here."
            shown.isEmpty() && query.isNotEmpty() -> "No channels match \"$query\""
            shown.isEmpty() && qualityFilter != 0 && all.isNotEmpty() -> "No " + (if (qualityFilter == 2) "4K" else "HD") + " channels in this playlist"
            shown.isEmpty() -> status.text.toString()
            else -> {
                val sb = StringBuilder()
                sb.append(shown.size).append(" channels")
                if (filtering && qualityFilter == 1) sb.append("  |  HD and better")
                if (filtering && qualityFilter == 2) sb.append("  |  4K only")
                if (filtering && query.isNotEmpty()) sb.append("  |  search: \"").append(query).append("\"")
                sb.toString()
            }
        }
    }

    private fun toggleQuality(mode: Int) {
        qualityFilter = if (qualityFilter == mode) 0 else mode
        tab = Tab.ALL
        render()
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

    private fun focusedPos(): Int {
        val v = currentFocus ?: return -1
        val item = recycler.findContainingItemView(v) ?: return -1
        return recycler.getChildAdapterPosition(item)
    }

    /** Search bar gets focus and the on-screen keyboard opens, as if you had clicked into it. */
    @Suppress("DEPRECATION")
    private fun openSearch() {
        if (tab != Tab.ALL) {
            tab = Tab.ALL
            render()
        }
        searchBox.requestFocus()
        searchBox.setSelection(searchBox.text.length)
        handler.postDelayed({
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(searchBox, InputMethodManager.SHOW_FORCED)
        }, 120)
    }

    private fun showPlaylists() {
        PlaylistPicker.show(this) { src ->
            if (src.key != Settings.source) {
                Settings.source = src.key
                query = ""
                qualityFilter = 0
                handler.removeCallbacks(searchRunnable)
                searchBox.setText("")
                tab = Tab.ALL
                firstFocusDone = false
                load(Settings.autoUpdate, true)
            }
        }
    }

    private fun hintPart(sb: SpannableStringBuilder, key: String?, text: String, color: Int) {
        if (key == null) return
        val s = sb.length
        sb.append("\u25CF ").append(key)
        sb.setSpan(ForegroundColorSpan(color), s, s + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(" ").append(text).append("      ")
    }

    private fun buildHint() {
        val sb = SpannableStringBuilder()
        hintPart(sb, KeyMap.first(Act.SEARCH), "Search", Color.parseColor("#FFD43B"))
        hintPart(sb, KeyMap.first(Act.PLAYLISTS), "Playlists", Color.parseColor("#51CF66"))
        hintPart(sb, KeyMap.first(Act.FAVORITE), "Favorite", Color.parseColor("#FF6B6B"))
        hintPart(sb, KeyMap.first(Act.HD_FILTER), "HD filter", Color.parseColor("#74C0FC"))
        sb.append("OK Play      Hold OK Menu      0-9 Jump to number")
        hint.text = sb
    }

    private fun showCrashReport(report: String) {
        val oom = report.contains("OOM=true")
        if (oom) Settings.buffering = 0
        val first = report.lines().firstOrNull { it.startsWith("Channel:") } ?: ""
        val msg = (if (oom)
            "The TV ran out of memory (usually a very high-bitrate 4K stream).\nI lowered the buffer size to prevent it.\n\n"
        else
            "The app stopped unexpectedly.\n\n") +
            first + "\n\nDetails:\n" + report.lines().take(6).joinToString("\n") +
            "\n\nTip: press the Quality key (Blue) while watching to try Max 1080p on heavy channels."
        AlertDialog.Builder(this)
            .setTitle("App closed last time")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
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
        playChannel(ch)
    }

    private fun playChannel(ch: Channel) {
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
        val pos = focusedPos()
        val ch = shown.getOrNull(pos) ?: return
        UserData.assign(d, ch)
        Toast.makeText(this, ch.name + " is now number " + d, Toast.LENGTH_LONG).show()
        render()
    }

    // ---------------------------------------------------------------- keys

    private fun runAct(act: Act): Boolean {
        when (act) {
            Act.SEARCH -> openSearch()
            Act.FAVORITE -> {
                val ch = shown.getOrNull(focusedPos())
                if (ch == null) {
                    Toast.makeText(this, "Move to a channel first", Toast.LENGTH_SHORT).show()
                } else {
                    val on = UserData.toggleFav(ch)
                    Toast.makeText(this, if (on) "Added to favorites" else "Removed from favorites", Toast.LENGTH_SHORT).show()
                    render()
                }
            }
            Act.PLAYLISTS -> showPlaylists()
            Act.CHANNEL_LIST -> if (shown.isNotEmpty()) focusPosition(0)
            Act.OPTIONS -> {
                val pos = focusedPos()
                if (pos >= 0) showChannelMenu(pos) else return false
            }
            Act.NUMBERS -> { tab = Tab.NUM; render() }
            Act.HD_FILTER -> toggleQuality(1)
            Act.SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
            Act.PREV_CHANNEL -> {
                val prev = Session.previous ?: Session.lastPlayed ?: UserData.last
                if (prev == null) Toast.makeText(this, "No previous channel yet", Toast.LENGTH_SHORT).show()
                else playChannel(prev)
            }
            else -> return false
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // While typing in the search box the keyboard owns the keys.
        val typing = currentFocus is EditText
        if (digitOf(keyCode) >= 0 && !typing) {
            event.startTracking()
            return true
        }
        if (!typing) {
            val act = KeyMap.actionFor(keyCode)
            if (act != null) {
                if (event.repeatCount == 0) {
                    if (runAct(act)) {
                        lastActKey = keyCode
                        return true
                    }
                } else if (keyCode == lastActKey) {
                    return true
                }
            }
        } else if (keyCode == KeyEvent.KEYCODE_PROG_YELLOW || KeyMap.actionFor(keyCode) == Act.SEARCH) {
            // Pressing the search key while already in the search box just opens the keyboard again.
            if (event.repeatCount == 0) {
                openSearch()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        val d = digitOf(keyCode)
        if (d in 1..9 && currentFocus !is EditText) {
            longDigit = true
            assignFocused(d)
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val d = digitOf(keyCode)
        if (d >= 0 && currentFocus !is EditText) {
            if (!longDigit) numberEntry.digit(d)
            longDigit = false
            return true
        }
        if (keyCode == lastActKey) {
            lastActKey = -1
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (searchBox.text.isNotEmpty()) {
            searchBox.setText("")
            handler.removeCallbacks(searchRunnable)
            query = ""
            render()
            return
        }
        if (qualityFilter != 0) {
            qualityFilter = 0
            render()
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
