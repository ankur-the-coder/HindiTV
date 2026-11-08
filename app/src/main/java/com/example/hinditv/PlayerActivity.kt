package com.example.hinditv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var infoBox: View
    private lateinit var nameView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var errorView: TextView
    private lateinit var favs: FavoritesStore

    private val handler = Handler(Looper.getMainLooper())
    private val hideInfo = Runnable { infoBox.visibility = View.GONE }
    private var index = 0
    private var longPressed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ChannelStore.channels.isEmpty()) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        favs = FavoritesStore(this)
        playerView = findViewById(R.id.playerView)
        playerView.useController = false
        infoBox = findViewById(R.id.infoBox)
        nameView = findViewById(R.id.channelName)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.error)

        index = ChannelStore.index.coerceIn(0, ChannelStore.channels.size - 1)
    }

    override fun onStart() {
        super.onStart()
        if (ChannelStore.channels.isEmpty()) return

        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
            .setUserAgent("Mozilla/5.0 (Linux; Android 5.1) HindiTV/0.1")

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(5000, 30000, 1500, 3000)
            .build()

        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(loadControl)
            .build()

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                progress.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            }

            override fun onPlayerError(error: PlaybackException) {
                progress.visibility = View.GONE
                errorView.text = "Channel unavailable.\nPress UP / DOWN to change channel."
                errorView.visibility = View.VISIBLE
                showInfo()
            }
        })

        player = p
        playerView.player = p
        playCurrent()
    }

    private fun playCurrent() {
        val ch = ChannelStore.channels[index]
        errorView.visibility = View.GONE
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
    }

    private fun zap(delta: Int) {
        val n = ChannelStore.channels.size
        index = ((index + delta) % n + n) % n
        ChannelStore.index = index
        playCurrent()
    }

    private fun showInfo() {
        val ch = ChannelStore.channels[index]
        val star = if (favs.isFav(ch.url)) "  \u2605" else ""
        nameView.text = "${index + 1}. ${ch.name}$star"
        infoBox.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 4000)
    }

    private fun toggleFav() {
        favs.toggle(ChannelStore.channels[index].url)
        showInfo()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (event.repeatCount == 0) zap(+1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (event.repeatCount == 0) zap(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                event.startTracking()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (event.repeatCount == 0) toggleFav()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            longPressed = true
            toggleFav()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (!longPressed) showInfo()
            longPressed = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(hideInfo)
        playerView.player = null
        player?.release()
        player = null
    }
}
