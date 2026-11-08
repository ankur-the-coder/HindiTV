package com.example.hinditv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var leveler: LevelingAudioProcessor? = null

    private lateinit var playerView: PlayerView
    private lateinit var infoBox: View
    private lateinit var nameView: TextView
    private lateinit var techView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var errorView: TextView
    private lateinit var numberOverlay: TextView
    private lateinit var drawer: View
    private lateinit var drawerList: RecyclerView
    private lateinit var drawerManager: LinearLayoutManager
    private lateinit var drawerAdapter: DrawerAdapter
    private lateinit var numberEntry: NumberEntry

    private val handler = Handler(Looper.getMainLooper())
    private val hideInfo = Runnable { infoBox.visibility = View.GONE }
    private val hideNumber = Runnable { numberOverlay.visibility = View.GONE }
    private val skipRunnable = Runnable { zapInternal(zapDir) }
    private val sleepRunnable = Runnable { finish() }

    private var index = 0
    private var zapDir = 0
    private var skipped = 0
    private var longHandled = false
    private var drawerOpen = false
    private var sleepMinutes = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Session.channels.isEmpty()) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.playerView)
        playerView.useController = false
        infoBox = findViewById(R.id.infoBox)
        nameView = findViewById(R.id.channelName)
        techView = findViewById(R.id.techInfo)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.error)
        numberOverlay = findViewById(R.id.numberOverlay)
        drawer = findViewById(R.id.drawer)
        drawerList = findViewById(R.id.drawerList)

        drawerAdapter = DrawerAdapter(
            onClick = { pos -> onDrawerPick(pos) },
            onLongClick = { pos -> toggleFavAt(pos) }
        )
        drawerManager = LinearLayoutManager(this)
        drawerList.layoutManager = drawerManager
        drawerList.adapter = drawerAdapter
        drawerList.itemAnimator = null

        numberEntry = NumberEntry(
            handler,
            { UserData.numberSet() },
            { text -> showNumberOverlay(text, 0) },
            { n -> playNumber(n) }
        )

        index = Session.index.coerceIn(0, Session.channels.size - 1)
    }

    override fun onStart() {
        super.onStart()
        if (Session.channels.isEmpty() || !::playerView.isInitialized) return
        playerView.resizeMode = resizeModeFor(Settings.aspect)
        player = createPlayer()
        playerView.player = player
        playCurrent(zapDir)
    }

    override fun onStop() {
        super.onStop()
        if (!::playerView.isInitialized) return
        handler.removeCallbacks(hideInfo)
        handler.removeCallbacks(skipRunnable)
        playerView.player = null
        player?.release()
        player = null
        leveler = null
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    // ---------------------------------------------------------------- player

    private fun resizeModeFor(aspect: Int) = when (aspect) {
        1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    private fun createPlayer(): ExoPlayer {
        val http = OkHttpDataSource.Factory(Net.client(this)).setUserAgent("Mozilla/5.0 (Linux; Android 5.1) HindiTV/0.2")

        val b = when (Settings.buffering) {
            1 -> intArrayOf(6000, 30000, 2000, 4000)
            2 -> intArrayOf(15000, 60000, 3500, 8000)
            else -> intArrayOf(2500, 12000, 1000, 2000)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(b[0], b[1], b[2], b[3])
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val baseFactory = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        val renderersFactory: RenderersFactory
        if (Settings.leveling) {
            val proc = LevelingAudioProcessor()
            leveler = proc
            val sink = DefaultAudioSink.Builder(this)
                .setAudioProcessors(arrayOf<AudioProcessor>(proc))
                .build()
            val ctx = this
            renderersFactory = RenderersFactory { eventHandler, videoListener, audioListener, textOutput, metadataOutput ->
                val renderers = baseFactory.createRenderers(eventHandler, videoListener, audioListener, textOutput, metadataOutput)
                for (i in renderers.indices) {
                    if (renderers[i] is MediaCodecAudioRenderer) {
                        renderers[i] = MediaCodecAudioRenderer(
                            ctx, MediaCodecSelector.DEFAULT, true, eventHandler, audioListener, sink
                        )
                    }
                }
                renderers
            }
        } else {
            leveler = null
            renderersFactory = baseFactory
        }

        val p = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(loadControl)
            .build()

        // Follows the system volume; we never change the player/system volume ourselves.
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                progress.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_READY) {
                    skipped = 0
                    errorView.visibility = View.GONE
                    updateTech()
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                updateTech()
            }

            override fun onPlayerError(error: PlaybackException) {
                handlePlayerError()
            }
        })
        return p
    }

    private fun handlePlayerError() {
        progress.visibility = View.GONE
        if (Settings.skipDead && zapDir != 0 && skipped < 8 && Session.channels.size > 1) {
            skipped++
            errorView.text = "Channel unavailable - skipping..."
            errorView.visibility = View.VISIBLE
            handler.removeCallbacks(skipRunnable)
            handler.postDelayed(skipRunnable, 700)
        } else {
            errorView.text = "Channel unavailable.\nUse UP / DOWN to change channel, or LEFT to open the list."
            errorView.visibility = View.VISIBLE
            showInfo()
        }
    }

    private fun playCurrent(dir: Int) {
        zapDir = dir
        handler.removeCallbacks(skipRunnable)
        val ch = Session.channels[index]
        UserData.setLast(ch)
        errorView.visibility = View.GONE
        leveler?.resetLevel()
        showInfo()

        val builder = MediaItem.Builder().setUri(ch.url)
        val u = ch.url.lowercase()
        if (!u.contains(".mpd") && !u.contains(".mp4") && !u.contains(".mkv")) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        player?.apply {
            setMediaItem(builder.build())
            prepare()
            playWhenReady = true
        }
        if (drawerOpen) drawerAdapter.submitList(buildDrawerRows())
    }

    private fun zap(delta: Int) {
        skipped = 0
        zapInternal(delta)
    }

    private fun zapInternal(delta: Int) {
        val n = Session.channels.size
        index = ((index + delta) % n + n) % n
        Session.index = index
        playCurrent(delta)
    }

    private fun playNumber(n: Int) {
        val ch = UserData.channelFor(n)
        if (ch == null) {
            showNumberOverlay(n.toString(), 1500)
            Toast.makeText(this, "No channel on number $n. Hold the number key to give it to this channel.", Toast.LENGTH_LONG).show()
            return
        }
        showNumberOverlay(n.toString(), 1200)
        if (drawerOpen) closeDrawer()
        val i = Session.channels.indexOfFirst { it.url == ch.url }
        if (i >= 0) {
            index = i
        } else {
            Session.open(Session.all, ch)
            index = Session.index
        }
        Session.index = index
        skipped = 0
        playCurrent(0)
    }

    // ---------------------------------------------------------------- overlays

    private fun showInfo() {
        val ch = Session.channels.getOrNull(index) ?: return
        val num = UserData.numberOf(ch.url)
        val star = if (UserData.isFav(ch.url)) "  \u2605" else ""
        val prefix = if (num > 0) "[$num]  " else ""
        nameView.text = prefix + ch.name + star
        infoBox.visibility = View.VISIBLE
        updateTech()
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, Settings.infoSeconds * 1000L)
    }

    private fun updateTech() {
        if (!Settings.techInfo) {
            techView.visibility = View.GONE
            return
        }
        val p = player ?: return
        val parts = ArrayList<String>()
        val v = p.videoFormat
        if (v != null) {
            if (v.width > 0) parts.add(v.width.toString() + "x" + v.height)
            DecoderInfo.shortName(v.sampleMimeType)?.let { parts.add(it) }
            if (v.bitrate > 0) parts.add(String.format("%.1f Mbps", v.bitrate / 1000000.0))
            when (DecoderInfo.hasHardware(v.sampleMimeType)) {
                true -> parts.add("HW decoder")
                false -> parts.add("SOFTWARE decoder only")
                null -> {}
            }
        }
        val a = p.audioFormat
        if (a != null) {
            DecoderInfo.shortName(a.sampleMimeType)?.let { parts.add(it) }
            if (a.channelCount > 0) parts.add(a.channelCount.toString() + "ch")
        }
        if (Settings.leveling) parts.add("volume leveling on")
        techView.text = parts.joinToString("  |  ")
        techView.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
    }

    /** millis = 0 keeps it visible (waiting for the second digit). */
    private fun showNumberOverlay(text: String, millis: Long) {
        numberOverlay.text = text
        numberOverlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideNumber)
        if (millis > 0) handler.postDelayed(hideNumber, millis)
    }

    // ---------------------------------------------------------------- drawer

    private fun buildDrawerRows(): List<DRow> =
        Session.channels.mapIndexed { i, c ->
            DRow(c, UserData.isFav(c.url), UserData.numberOf(c.url), i == index)
        }

    private fun openDrawer() {
        if (drawerOpen) return
        drawerOpen = true
        handler.removeCallbacks(hideInfo)
        drawerAdapter.submitList(buildDrawerRows()) { positionDrawer() }
        drawer.animate().cancel()
        drawer.visibility = View.VISIBLE
        drawer.translationX = -(380 * resources.displayMetrics.density)
        drawer.animate().translationX(0f).setDuration(150).start()
    }

    /** Current channel sits low in the list: a few channels before it above, two after it below. */
    private fun positionDrawer() {
        drawerList.post {
            val itemH = (64 * resources.displayMetrics.density).toInt()
            val offset = (drawerList.height - 3 * itemH).coerceAtLeast(0)
            drawerManager.scrollToPositionWithOffset(index, offset)
            drawerList.postDelayed({
                val vh = drawerList.findViewHolderForAdapterPosition(index)
                if (vh != null) vh.itemView.requestFocus() else drawerList.requestFocus()
            }, 100)
        }
    }

    private fun closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        drawer.animate().cancel()
        drawer.animate()
            .translationX(-(380 * resources.displayMetrics.density))
            .setDuration(120)
            .withEndAction { if (!drawerOpen) drawer.visibility = View.GONE }
            .start()
    }

    private fun onDrawerPick(pos: Int) {
        if (pos !in Session.channels.indices) return
        index = pos
        Session.index = pos
        skipped = 0
        closeDrawer()
        playCurrent(0)
    }

    private fun toggleFavAt(pos: Int) {
        val ch = Session.channels.getOrNull(pos) ?: return
        val on = UserData.toggleFav(ch)
        Toast.makeText(this, if (on) "Added to favorites" else "Removed from favorites", Toast.LENGTH_SHORT).show()
        drawerAdapter.submitList(buildDrawerRows())
        showInfo()
    }

    // ---------------------------------------------------------------- options

    private fun toggleFavCurrent() {
        toggleFavAt(index)
    }

    private fun assignTarget(): Channel? {
        if (drawerOpen) {
            val v = drawerList.focusedChild
            if (v != null) {
                val p = drawerList.getChildAdapterPosition(v)
                if (p >= 0 && p < Session.channels.size) return Session.channels[p]
            }
        }
        return Session.channels.getOrNull(index)
    }

    private fun assignDigit(d: Int) {
        val ch = assignTarget() ?: return
        UserData.assign(d, ch)
        Toast.makeText(this, ch.name + " is now number " + d, Toast.LENGTH_LONG).show()
        showInfo()
        if (drawerOpen) drawerAdapter.submitList(buildDrawerRows())
    }

    private fun aspectName(a: Int) = when (a) { 1 -> "Zoom"; 2 -> "Stretch"; else -> "Fit" }

    private fun showOptions() {
        val ch = Session.channels.getOrNull(index) ?: return
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        labels.add(if (UserData.isFav(ch.url)) "Remove from favorites" else "Add to favorites")
        actions.add { toggleFavCurrent() }

        val num = UserData.numberOf(ch.url)
        labels.add(if (num > 0) "Change number (now $num)" else "Assign number")
        actions.add { Dialogs.assignNumber(this, ch) { showInfo() } }
        if (num > 0) {
            labels.add("Remove number $num")
            actions.add { UserData.removeNumber(num); showInfo() }
        }

        labels.add("Aspect ratio: " + aspectName(Settings.aspect))
        actions.add {
            Settings.aspect = (Settings.aspect + 1) % 3
            playerView.resizeMode = resizeModeFor(Settings.aspect)
            Toast.makeText(this, "Aspect ratio: " + aspectName(Settings.aspect), Toast.LENGTH_SHORT).show()
        }

        labels.add("Sleep timer: " + (if (sleepMinutes == 0) "Off" else "$sleepMinutes min"))
        actions.add { showSleepDialog() }

        labels.add("Settings")
        actions.add { startActivity(Intent(this, SettingsActivity::class.java)) }

        AlertDialog.Builder(this)
            .setTitle(ch.name)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun showSleepDialog() {
        val options = intArrayOf(0, 15, 30, 60, 90, 120)
        val labels = options.map { if (it == 0) "Off" else "$it minutes" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Sleep timer")
            .setItems(labels) { _, which ->
                sleepMinutes = options[which]
                handler.removeCallbacks(sleepRunnable)
                if (sleepMinutes > 0) {
                    handler.postDelayed(sleepRunnable, sleepMinutes * 60_000L)
                    Toast.makeText(this, "TV will close in $sleepMinutes minutes", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Sleep timer off", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    // ---------------------------------------------------------------- keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (digitOf(keyCode) >= 0) {
            event.startTracking()
            return true
        }

        if (drawerOpen) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { closeDrawer(); return true }
                KeyEvent.KEYCODE_MENU -> { closeDrawer(); showOptions(); return true }
            }
            return super.onKeyDown(keyCode, event)   // let focus move inside the drawer
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (event.repeatCount == 0) zap(+1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (event.repeatCount == 0) zap(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_GUIDE -> {
                openDrawer()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MENU -> {
                showOptions()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                event.startTracking()
                return true
            }
            KeyEvent.KEYCODE_INFO -> {
                showInfo()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                player?.let { it.playWhenReady = !it.playWhenReady }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { player?.playWhenReady = true; return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { player?.playWhenReady = false; return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        val d = digitOf(keyCode)
        if (d in 1..9) {
            longHandled = true
            numberEntry.cancel()
            assignDigit(d)
            return true
        }
        if (!drawerOpen && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            longHandled = true
            toggleFavCurrent()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val d = digitOf(keyCode)
        if (d >= 0) {
            if (!longHandled) numberEntry.digit(d)
            longHandled = false
            return true
        }
        if (!drawerOpen && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            if (!longHandled) {
                if (numberEntry.pending) numberEntry.commitNow() else openDrawer()
            }
            longHandled = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            numberEntry.pending -> {
                numberEntry.cancel()
                numberOverlay.visibility = View.GONE
            }
            drawerOpen -> closeDrawer()
            else -> super.onBackPressed()
        }
    }
}
