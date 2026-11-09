package com.example.hinditv

import android.app.ActivityManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
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
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
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
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import java.util.Locale

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var bandwidth: DefaultBandwidthMeter? = null
    private var leveler: LevelingAudioProcessor? = null

    private lateinit var playerView: PlayerView
    private lateinit var infoBox: View
    private lateinit var nameView: TextView
    private lateinit var numView: TextView
    private lateinit var techView: TextView
    private lateinit var playerHint: TextView
    private lateinit var logoView: ImageView
    private lateinit var badgeRes: TextView
    private lateinit var badgeHdr: TextView
    private lateinit var progress: ProgressBar
    private lateinit var errorView: TextView
    private lateinit var statusPill: TextView
    private lateinit var numberOverlay: TextView
    private lateinit var drawer: View
    private lateinit var drawerList: RecyclerView
    private lateinit var drawerManager: LinearLayoutManager
    private lateinit var drawerAdapter: DrawerAdapter
    private lateinit var numberEntry: NumberEntry

    private val handler = Handler(Looper.getMainLooper())
    private val hideInfo = Runnable { infoBox.visibility = View.GONE }
    private val hideNumber = Runnable { numberOverlay.visibility = View.GONE }
    private val skipRunnable = Runnable { zapInternal(if (zapDir >= 0) 1 else -1) }
    private val sleepRunnable = Runnable { finish() }
    private val retryRunnable = Runnable { loadMedia() }

    private var index = 0
    private var zapDir = 0
    private var skipped = 0
    private var longHandled = false
    private var drawerOpen = false
    private var sleepMinutes = 0
    private var lastActKey = -1

    // ---- reconnect state
    private var retryCount = 0
    private var retryPending = false
    private var everPlayed = false
    private var lastErrorAt = 0L
    private var bufferingSince = 0L
    private var decoderCap = false
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private val watchdog = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && p.playWhenReady && p.playbackState == Player.STATE_BUFFERING &&
                bufferingSince > 0 && System.currentTimeMillis() - bufferingSince > 25_000
            ) {
                // Stuck buffering for 25 s: reconnect instead of waiting forever.
                bufferingSince = System.currentTimeMillis()
                retryCount++
                showReconnecting()
                loadMedia()
            }
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Session.channels.isEmpty()) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Always follow the TV's own brightness setting; the app never pushes it up or down.
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        if (Build.VERSION.SDK_INT >= 26) {
            // Chosen once for the whole screen, so the TV never switches HDR/SDR mode between channels.
            when (Settings.hdrMode) {
                1 -> window.colorMode = ActivityInfo.COLOR_MODE_HDR
                2 -> window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
            }
        }

        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.playerView)
        playerView.useController = false
        infoBox = findViewById(R.id.infoBox)
        nameView = findViewById(R.id.channelName)
        numView = findViewById(R.id.channelNum)
        techView = findViewById(R.id.techInfo)
        playerHint = findViewById(R.id.playerHint)
        logoView = findViewById(R.id.channelLogo)
        badgeRes = findViewById(R.id.badgeRes)
        badgeHdr = findViewById(R.id.badgeHdr)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.error)
        statusPill = findViewById(R.id.statusPill)
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
        registerNetwork()
        handler.postDelayed(watchdog, 5000)
        playCurrent(zapDir)
    }

    override fun onStop() {
        super.onStop()
        if (!::playerView.isInitialized) return
        handler.removeCallbacks(hideInfo)
        handler.removeCallbacks(skipRunnable)
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(watchdog)
        unregisterNetwork()
        playerView.player = null
        player?.release()
        player = null
        trackSelector = null
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
        val http = OkHttpDataSource.Factory(Net.client(this)).setUserAgent("Mozilla/5.0 (Linux; Android 5.1) HindiTV/0.3")

        val b = when (Settings.buffering) {
            1 -> intArrayOf(6000, 30000, 2000, 4000)
            2 -> intArrayOf(15000, 50000, 3500, 8000)
            else -> intArrayOf(2500, 12000, 1000, 2000)
        }
        // CRASH FIX: the buffer is capped by SIZE as well as time. A 4K stream is ~2.5 MB/s, so a time-only
        // buffer of 30-60 s used to grow to hundreds of MB and the TV killed the app (out of memory).
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val bytes = (am.memoryClass * 1024L * 1024L * 3L / 10L).toInt()
            .coerceIn(24 * 1024 * 1024, 80 * 1024 * 1024)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(b[0], b[1], b[2], b[3])
            .setTargetBufferBytes(bytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(0, false)
            .build()

        // Start the bandwidth estimate high so 4K / Full HD variants are tried first, then adapt down if needed.
        val meter = DefaultBandwidthMeter.Builder(this).setInitialBitrateEstimate(20_000_000L).build()
        bandwidth = meter

        val selector = DefaultTrackSelector(this)
        trackSelector = selector

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

        // RELIABILITY FIX: many more automatic retries for every segment / playlist download.
        val sourceFactory = DefaultMediaSourceFactory(http)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(8))

        val p = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(sourceFactory)
            .setTrackSelector(selector)
            .setBandwidthMeter(meter)
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
        // Changing the TV's refresh rate per channel makes the picture blink; off unless enabled in Settings.
        p.setVideoChangeFrameRateStrategy(
            if (Settings.matchFps) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
            else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        )

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                progress.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_BUFFERING) {
                    if (bufferingSince == 0L) bufferingSince = System.currentTimeMillis()
                } else {
                    bufferingSince = 0L
                }
                if (state == Player.STATE_READY) onRecovered()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                updateTech()
            }

            override fun onTracksChanged(tracks: Tracks) {
                updateTech()
            }

            override fun onPlayerError(error: PlaybackException) {
                handleError(error)
            }
        })
        applyQuality(true)
        return p
    }

    /** Video quality limits + track selection. Never caps by the TV's reported screen size (often wrongly 1080p). */
    private fun applyQuality(clearOverrides: Boolean) {
        val ts = trackSelector ?: return
        val b = ts.buildUponParameters()
        b.clearViewportSizeConstraints()
        b.setAllowVideoMixedMimeTypeAdaptiveness(true)
        var w = Int.MAX_VALUE
        var h = Int.MAX_VALUE
        when (Settings.quality) {
            2 -> { w = 1920; h = 1088 }
            3 -> { w = 1280; h = 720 }
        }
        if (decoderCap && h > 1088) { w = 1920; h = 1088 }
        b.setMaxVideoSize(w, h)
        b.setForceHighestSupportedBitrate(Settings.quality == 1)
        if (clearOverrides) b.clearOverrides()
        ts.setParameters(b.build())
    }

    private fun buildItem(ch: Channel): MediaItem {
        val builder = MediaItem.Builder().setUri(ch.url)
        val u = ch.url.lowercase()
        if (!u.contains(".mpd") && !u.contains(".mp4") && !u.contains(".mkv")) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        // Stay a little further behind the live edge (more safety margin) and never speed up to catch up.
        val offset = when (Settings.buffering) {
            1 -> 12000L
            2 -> 20000L
            else -> C.TIME_UNSET
        }
        builder.setLiveConfiguration(
            MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(offset)
                .setMinPlaybackSpeed(1f)
                .setMaxPlaybackSpeed(1f)
                .build()
        )
        return builder.build()
    }

    /** (Re)loads the current channel. Used for the first start and for every automatic reconnect. */
    private fun loadMedia() {
        handler.removeCallbacks(retryRunnable)
        retryPending = false
        val ch = Session.channels.getOrNull(index) ?: return
        val p = player ?: return
        try {
            p.setMediaItem(buildItem(ch))
            p.prepare()
            p.playWhenReady = true
        } catch (e: Exception) {
            scheduleRetry()
        }
    }

    private fun onRecovered() {
        skipped = 0
        errorView.visibility = View.GONE
        statusPill.visibility = View.GONE
        if (!everPlayed) {
            everPlayed = true
            Session.channels.getOrNull(index)?.let { UserData.addRecent(it) }
        }
        if (System.currentTimeMillis() - lastErrorAt > 20_000) retryCount = 0
        updateTech()
    }

    private fun handleError(e: PlaybackException) {
        lastErrorAt = System.currentTimeMillis()
        val p = player ?: return
        val code = e.errorCode

        // Live window moved on while buffering: just jump to the live edge again.
        if (code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            p.seekToDefaultPosition()
            p.prepare()
            return
        }
        // The TV's video chip cannot decode this (typically a 4K variant): continue at Full HD instead of failing.
        val decoderTrouble = code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            code == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            code == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
            code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        if (decoderTrouble && !decoderCap) {
            decoderCap = true
            applyQuality(false)
            Toast.makeText(this, "This TV cannot decode that stream size - continuing in Full HD", Toast.LENGTH_LONG).show()
            loadMedia()
            return
        }

        retryCount++
        // Zapping onto a channel that never started: after a few tries move on to the next one.
        if (!everPlayed && zapDir != 0 && Settings.skipDead && retryCount >= 3 && skipped < 8 && Session.channels.size > 1) {
            skipped++
            retryCount = 0
            statusPill.text = "No signal - trying the next channel..."
            statusPill.visibility = View.VISIBLE
            handler.removeCallbacks(skipRunnable)
            handler.postDelayed(skipRunnable, 600)
            return
        }
        showReconnecting()
        scheduleRetry()
    }

    private fun showReconnecting() {
        progress.visibility = View.VISIBLE
        if (!everPlayed && retryCount >= 8) {
            errorView.text = "This channel is not responding right now.\nStill trying in the background.\nUP / DOWN: change channel     LEFT: channel list"
            errorView.visibility = View.VISIBLE
            statusPill.visibility = View.GONE
        } else {
            statusPill.text = if (retryCount <= 1) "Reconnecting..." else "Reconnecting...  (try $retryCount)"
            statusPill.visibility = View.VISIBLE
        }
    }

    private fun scheduleRetry() {
        retryPending = true
        handler.removeCallbacks(retryRunnable)
        val delay = (retryCount * 1000L).coerceIn(1000L, 6000L)
        handler.postDelayed(retryRunnable, delay)
    }

    private fun registerNetwork() {
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // Internet is back: reconnect right away instead of waiting for the next retry timer.
                    handler.post { if (retryPending) loadMedia() }
                }
            }
            val req = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            cm.registerNetworkCallback(req, cb)
            netCallback = cb
        } catch (e: Exception) {
            netCallback = null
        }
    }

    private fun unregisterNetwork() {
        val cb = netCallback ?: return
        try {
            (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb)
        } catch (e: Exception) {
        }
        netCallback = null
    }

    private fun playCurrent(dir: Int) {
        zapDir = dir
        handler.removeCallbacks(skipRunnable)
        handler.removeCallbacks(retryRunnable)
        val ch = Session.channels[index]
        UserData.setLast(ch)
        val prev = Session.lastPlayed
        if (prev != null && prev.url != ch.url) Session.previous = prev
        Session.lastPlayed = ch
        errorView.visibility = View.GONE
        statusPill.visibility = View.GONE
        retryCount = 0
        everPlayed = false
        bufferingSince = 0L
        leveler?.resetLevel()
        applyQuality(true)
        showInfo()
        loadMedia()
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

    private fun openChannel(ch: Channel) {
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

    private fun playNumber(n: Int) {
        val ch = UserData.channelFor(n)
        if (ch == null) {
            showNumberOverlay(n.toString(), 1500)
            Toast.makeText(this, "No channel on number $n. Hold the number key to give it to this channel.", Toast.LENGTH_LONG).show()
            return
        }
        showNumberOverlay(n.toString(), 1200)
        if (drawerOpen) closeDrawer()
        openChannel(ch)
    }

    private fun previousChannel() {
        val prev = Session.previous
        if (prev == null) {
            Toast.makeText(this, "No previous channel yet", Toast.LENGTH_SHORT).show()
            return
        }
        if (drawerOpen) closeDrawer()
        openChannel(prev)
    }

    // ---------------------------------------------------------------- stream info

    private class Offer(val count: Int, val maxH: Int, val maxW: Int, val supH: Int, val mime: String?)

    private fun scanVideo(): Offer {
        val p = player ?: return Offer(0, 0, 0, 0, null)
        var count = 0
        var maxH = 0
        var maxW = 0
        var supH = 0
        var mime: String? = null
        for (g in p.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                count++
                if (f.height > maxH) {
                    maxH = f.height
                    maxW = f.width
                    mime = f.sampleMimeType
                }
                if (g.isTrackSupported(i) && f.height > supH) supH = f.height
            }
        }
        return Offer(count, maxH, maxW, supH, mime)
    }

    private fun hdrName(): String? {
        val v = player?.videoFormat ?: return null
        if (v.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) return "Dolby Vision"
        return when (v.colorInfo?.colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> "HDR10"
            C.COLOR_TRANSFER_HLG -> "HLG"
            else -> null
        }
    }

    private fun playingHeight(): Int {
        val p = player ?: return 0
        val vs = p.videoSize
        if (vs.height > 0) return vs.height
        return p.videoFormat?.height?.coerceAtLeast(0) ?: 0
    }

    private fun resName(h: Int): String = when {
        h >= 2000 -> "4K"
        h >= 1400 -> "1440p"
        h >= 1000 -> "1080p"
        h >= 700 -> "720p"
        h > 0 -> h.toString() + "p"
        else -> ""
    }

    private fun qualityName(q: Int) = when (q) {
        1 -> "Highest"
        2 -> "Max 1080p"
        3 -> "Max 720p"
        else -> "Auto"
    }

    private fun updateTech() {
        if (!::techView.isInitialized) return
        val p = player ?: return
        val h = playingHeight()
        val rn = resName(h)
        if (rn.isEmpty()) {
            badgeRes.visibility = View.GONE
        } else {
            badgeRes.text = rn
            badgeRes.visibility = View.VISIBLE
        }
        val hdr = hdrName()
        if (hdr == null) {
            badgeHdr.visibility = View.GONE
        } else {
            badgeHdr.text = hdr
            badgeHdr.visibility = View.VISIBLE
        }
        if (!Settings.techInfo) {
            techView.visibility = View.GONE
            return
        }
        val parts = ArrayList<String>()
        val v = p.videoFormat
        val vs = p.videoSize
        if (vs.width > 0 && vs.height > 0) parts.add(vs.width.toString() + "x" + vs.height)
        else if (v != null && v.width > 0) parts.add(v.width.toString() + "x" + v.height)
        if (v != null) {
            DecoderInfo.shortName(v.sampleMimeType)?.let { parts.add(it) }
            val br = if (v.bitrate > 0) v.bitrate else v.averageBitrate
            if (br > 0) parts.add(String.format(Locale.US, "%.1f Mbps", br / 1000000.0))
            when (DecoderInfo.hasHardware(v.sampleMimeType)) {
                true -> parts.add("HW decoder")
                false -> parts.add("SOFTWARE decoder")
                null -> {}
            }
        }
        val a = p.audioFormat
        if (a != null) {
            DecoderInfo.shortName(a.sampleMimeType)?.let { parts.add(it) }
            if (a.channelCount > 0) parts.add(a.channelCount.toString() + "ch")
        }
        parts.add("Quality: " + qualityName(Settings.quality))
        techView.text = parts.joinToString("  |  ")
        techView.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showStreamInfo() {
        val p = player ?: return
        val ch = Session.channels.getOrNull(index) ?: return
        val o = scanVideo()
        val vs = p.videoSize
        val v = p.videoFormat
        val h = playingHeight()
        val sb = StringBuilder()
        sb.append("Channel: ").append(ch.name).append("\n\n")

        sb.append("PLAYING NOW\n")
        if (vs.width > 0) sb.append(vs.width).append("x").append(vs.height) else sb.append("(not started)")
        DecoderInfo.shortName(v?.sampleMimeType)?.let { sb.append("   ").append(it) }
        hdrName()?.let { sb.append("   ").append(it) } ?: sb.append("   SDR")
        sb.append("\n")
        if (v != null && v.height > 0 && vs.height > 0 && v.height != vs.height) {
            sb.append("(playlist declares ").append(v.width).append("x").append(v.height).append(")\n")
        }

        sb.append("\nTHE STREAM OFFERS\n")
        if (o.count == 0) {
            sb.append("Waiting for the stream to start...\n")
        } else if (o.maxH > 0) {
            sb.append(o.count).append(if (o.count == 1) " video version, " else " video versions, ")
                .append("best: ").append(o.maxW).append("x").append(o.maxH)
            DecoderInfo.shortName(o.mime)?.let { sb.append(" ").append(it) }
            sb.append("\n")
        } else {
            sb.append("It does not declare a resolution.\n")
        }

        sb.append("\nYOUR TV CAN DECODE\n")
        if (o.count == 0) sb.append("-\n")
        else if (o.supH <= 0) sb.append("unknown\n")
        else sb.append("up to ").append(o.supH).append("p of this stream\n")

        sb.append("\nSETTINGS\n")
        sb.append("Quality: ").append(qualityName(Settings.quality))
        if (decoderCap) sb.append("  (limited to Full HD after a decoder problem)")
        sb.append("\n")
        bandwidth?.let {
            sb.append("Network speed estimate: ")
                .append(String.format(Locale.US, "%.1f Mbps", it.bitrateEstimate / 1000000.0)).append("\n")
        }

        sb.append("\nVERDICT\n")
        val limited = Settings.quality >= 2 || decoderCap
        val verdict = when {
            o.count == 0 -> "Stream has not started yet."
            o.maxH <= 0 -> "The stream does not say its size; the number above is what the TV actually decodes."
            o.maxH <= 1088 -> "This stream itself is only " + o.maxH + "p. The app is not limiting it - there is no 4K version of this channel in the playlist."
            o.supH in 1 until o.maxH -> "The stream has " + o.maxH + "p but this TV's decoder can only play up to " + o.supH + "p, so it plays that. This is a TV hardware limit."
            limited && h in 1 until o.maxH -> "Limited by your Quality setting (or Full HD fallback). Press the Quality key to switch to Auto / Highest."
            h in 1 until o.maxH -> "The stream has " + o.maxH + "p and the TV supports it, but adaptive streaming chose " + h + "p because of network speed. Set Quality to Highest to force it."
            else -> "You are watching the best quality this stream offers."
        }
        sb.append(verdict)

        AlertDialog.Builder(this)
            .setTitle("Stream info")
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
            .setNeutralButton("Quality: " + qualityName(Settings.quality)) { _, _ -> cycleQuality() }
            .show()
    }

    private fun cycleQuality() {
        Settings.quality = (Settings.quality + 1) % 4
        applyQuality(false)
        Toast.makeText(this, "Video quality: " + qualityName(Settings.quality), Toast.LENGTH_SHORT).show()
        showInfo()
    }

    private fun showTracks(type: Int, title: String) {
        val p = player ?: return
        val ts = trackSelector ?: return
        val groups = p.currentTracks.groups.filter { it.type == type && it.isSupported }
        if (groups.isEmpty()) {
            Toast.makeText(this, "No $title options in this stream", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = ArrayList<String>()
        val isText = type == C.TRACK_TYPE_TEXT
        if (isText) labels.add("Off")
        for ((i, g) in groups.withIndex()) {
            val f = g.getTrackFormat(0)
            val lang = f.language
            val raw: String? = f.label
            val name: String = when {
                !raw.isNullOrEmpty() -> raw
                !lang.isNullOrEmpty() && lang != "und" -> Locale(lang).displayLanguage
                else -> "$title ${i + 1}"
            }
            labels.add(if (g.isSelected) "$name   (current)" else name)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which ->
                val b = ts.buildUponParameters()
                b.clearOverridesOfType(type)
                if (isText) {
                    if (which == 0) {
                        b.setTrackTypeDisabled(type, true)
                    } else {
                        b.setTrackTypeDisabled(type, false)
                        b.setOverrideForType(TrackSelectionOverride(groups[which - 1].mediaTrackGroup, 0))
                    }
                } else {
                    b.setOverrideForType(TrackSelectionOverride(groups[which].mediaTrackGroup, 0))
                }
                ts.setParameters(b.build())
            }
            .show()
    }

    // ---------------------------------------------------------------- overlays

    private fun showInfo() {
        val ch = Session.channels.getOrNull(index) ?: return
        val num = UserData.numberOf(ch.url)
        val star = if (UserData.isFav(ch.url)) "  \u2605" else ""
        nameView.text = ch.name + star
        if (num > 0) {
            numView.text = num.toString()
            numView.visibility = View.VISIBLE
        } else {
            numView.visibility = View.GONE
        }
        val logo = ch.logo
        if (!Settings.logos || logo.isNullOrEmpty()) {
            logoView.setImageResource(R.drawable.ic_tv)
        } else {
            logoView.load(logo) {
                placeholder(R.drawable.ic_tv)
                error(R.drawable.ic_tv)
            }
        }
        playerHint.text = "LEFT / OK: channels   UP / DOWN: change   " +
            (KeyMap.first(Act.SEARCH)?.let { "$it: search   " } ?: "") +
            (KeyMap.first(Act.PREV_CHANNEL)?.let { "$it: previous   " } ?: "") +
            (KeyMap.first(Act.STREAM_INFO)?.let { "$it: stream info   " } ?: "") +
            (KeyMap.first(Act.QUALITY)?.let { "$it: quality" } ?: "")
        infoBox.visibility = View.VISIBLE
        updateTech()
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, Settings.infoSeconds * 1000L)
    }

    /** millis = 0 keeps it visible (waiting for the second digit). */
    private fun showNumberOverlay(text: String, millis: Long) {
        numberOverlay.text = text
        numberOverlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideNumber)
        if (millis > 0) handler.postDelayed(hideNumber, millis)
    }

    // ---------------------------------------------------------------- drawer

    private fun buildDrawerRows(): List<DRow> {
        val logos = Settings.logos
        return Session.channels.mapIndexed { i, c ->
            DRow(c, UserData.isFav(c.url), UserData.numberOf(c.url), i == index, logos)
        }
    }

    private fun openDrawer() {
        if (drawerOpen) return
        drawerOpen = true
        handler.removeCallbacks(hideInfo)
        infoBox.visibility = View.GONE
        drawerAdapter.submitList(buildDrawerRows()) { positionDrawer() }
        drawer.animate().cancel()
        drawer.visibility = View.VISIBLE
        drawer.translationX = -(440 * resources.displayMetrics.density)
        drawer.animate().translationX(0f).setDuration(150).start()
    }

    /** Current channel sits low in the list: a few channels before it above, two after it below. */
    private fun positionDrawer() {
        drawerList.post {
            val itemH = (72 * resources.displayMetrics.density).toInt()
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
            .translationX(-(440 * resources.displayMetrics.density))
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
        if (drawerOpen) drawerAdapter.submitList(buildDrawerRows())
        else showInfo()
    }

    // ---------------------------------------------------------------- options

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

    private fun cycleAspect() {
        Settings.aspect = (Settings.aspect + 1) % 3
        playerView.resizeMode = resizeModeFor(Settings.aspect)
        Toast.makeText(this, "Aspect ratio: " + aspectName(Settings.aspect), Toast.LENGTH_SHORT).show()
    }

    private fun showOptions() {
        val ch = Session.channels.getOrNull(index) ?: return
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        labels.add(if (UserData.isFav(ch.url)) "Remove from favorites" else "Add to favorites")
        actions.add { toggleFavAt(index) }

        val num = UserData.numberOf(ch.url)
        labels.add(if (num > 0) "Change number (now $num)" else "Assign number")
        actions.add { Dialogs.assignNumber(this, ch) { showInfo() } }
        if (num > 0) {
            labels.add("Remove number $num")
            actions.add { UserData.removeNumber(num); showInfo() }
        }

        labels.add("Stream info & resolution check")
        actions.add { showStreamInfo() }
        labels.add("Video quality: " + qualityName(Settings.quality))
        actions.add { cycleQuality() }
        labels.add("Audio language / track")
        actions.add { showTracks(C.TRACK_TYPE_AUDIO, "Audio") }
        labels.add("Subtitles")
        actions.add { showTracks(C.TRACK_TYPE_TEXT, "Subtitles") }
        labels.add("Playlists & countries")
        actions.add { choosePlaylist() }
        labels.add("Aspect ratio: " + aspectName(Settings.aspect))
        actions.add { cycleAspect() }
        labels.add("Sleep timer: " + (if (sleepMinutes == 0) "Off" else "$sleepMinutes min"))
        actions.add { showSleepDialog() }
        labels.add("Settings")
        actions.add { startActivity(Intent(this, SettingsActivity::class.java)) }

        AlertDialog.Builder(this)
            .setTitle(ch.name)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun choosePlaylist() {
        PlaylistPicker.show(this) { src ->
            if (src.key != Settings.source) {
                Settings.source = src.key
                Toast.makeText(this, "Loading " + src.name + "...", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
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

    /** Runs the action linked to a remote key. Returns false when the action makes no sense here. */
    private fun runAct(act: Act, keyCode: Int): Boolean {
        when (act) {
            Act.SEARCH -> { Session.openSearch = true; finish() }
            Act.FAVORITE -> {
                val target = assignTarget() ?: return true
                toggleFavAt(Session.channels.indexOfFirst { it.url == target.url }.coerceAtLeast(0))
            }
            Act.PLAYLISTS -> choosePlaylist()
            Act.CHANNEL_LIST -> if (drawerOpen) closeDrawer() else openDrawer()
            Act.OPTIONS -> { closeDrawer(); showOptions() }
            Act.STREAM_INFO -> {
                if (infoBox.visibility == View.VISIBLE) showStreamInfo() else showInfo()
            }
            Act.QUALITY -> cycleQuality()
            Act.PREV_CHANNEL -> previousChannel()
            Act.AUDIO -> showTracks(C.TRACK_TYPE_AUDIO, "Audio")
            Act.SUBTITLES -> showTracks(C.TRACK_TYPE_TEXT, "Subtitles")
            Act.ASPECT -> cycleAspect()
            Act.PLAY_PAUSE -> {
                val p = player
                if (p != null) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY -> p.playWhenReady = true
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> p.playWhenReady = false
                        else -> p.playWhenReady = !p.playWhenReady
                    }
                }
            }
            Act.SKIP_FWD -> zap(10)
            Act.SKIP_BACK -> zap(-10)
            Act.STOP -> finish()
            Act.SLEEP -> showSleepDialog()
            Act.SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
            Act.NUMBERS, Act.HD_FILTER -> return false
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (digitOf(keyCode) >= 0) {
            event.startTracking()
            return true
        }

        val act = KeyMap.actionFor(keyCode)
        if (act != null) {
            if (event.repeatCount == 0) {
                if (runAct(act, keyCode)) {
                    lastActKey = keyCode
                    return true
                }
            } else if (keyCode == lastActKey) {
                return true
            }
        }

        if (drawerOpen) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                closeDrawer()
                return true
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
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                openDrawer()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                showOptions()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                event.startTracking()
                return true
            }
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                player?.let { it.playWhenReady = !it.playWhenReady }
                return true
            }
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
            toggleFavAt(index)
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
        if (keyCode == lastActKey) {
            lastActKey = -1
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
