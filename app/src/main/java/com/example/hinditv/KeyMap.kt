package com.example.hinditv

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import org.json.JSONObject

/** Everything a remote key can be linked to. */
enum class Act(val label: String) {
    SEARCH("Search (jump to search bar)"),
    FAVORITE("Add / remove favorite"),
    PLAYLISTS("Playlists & countries"),
    CHANNEL_LIST("Channel list"),
    OPTIONS("Options menu"),
    STREAM_INFO("Stream info (resolution check)"),
    QUALITY("Video quality (Auto / Highest / 1080p / 720p)"),
    PREV_CHANNEL("Previous channel (back and forth)"),
    AUDIO("Audio language / track"),
    SUBTITLES("Subtitles"),
    ASPECT("Aspect ratio"),
    PLAY_PAUSE("Play / pause"),
    SKIP_FWD("Jump forward 10 channels"),
    SKIP_BACK("Jump back 10 channels"),
    STOP("Stop and go back to the list"),
    SLEEP("Sleep timer"),
    NUMBERS("Numbered channels tab"),
    HD_FILTER("HD filter on / off"),
    SETTINGS("Settings")
}

/**
 * Links remote keys to actions. Defaults match the TCL-style remote
 * (colour keys, GUIDE, INFO, SUBT., LANG, TEXT, PRE-CH, REC, transport keys).
 * Any key can be re-linked in Settings > Remote keys, because different remotes send different codes.
 */
object KeyMap {
    private lateinit var prefs: SharedPreferences
    private val map = LinkedHashMap<Int, Act>()

    private val defaults: List<Pair<Int, Act>> = listOf(
        KeyEvent.KEYCODE_PROG_YELLOW to Act.SEARCH,
        KeyEvent.KEYCODE_PROG_RED to Act.FAVORITE,
        KeyEvent.KEYCODE_MEDIA_RECORD to Act.FAVORITE,
        KeyEvent.KEYCODE_PROG_GREEN to Act.PLAYLISTS,
        KeyEvent.KEYCODE_MEDIA_TOP_MENU to Act.PLAYLISTS,
        KeyEvent.KEYCODE_TV_MEDIA_CONTEXT_MENU to Act.PLAYLISTS,
        KeyEvent.KEYCODE_PROG_BLUE to Act.QUALITY,
        KeyEvent.KEYCODE_GUIDE to Act.CHANNEL_LIST,
        KeyEvent.KEYCODE_TV_CONTENTS_MENU to Act.CHANNEL_LIST,
        KeyEvent.KEYCODE_INFO to Act.STREAM_INFO,
        KeyEvent.KEYCODE_LAST_CHANNEL to Act.PREV_CHANNEL,
        KeyEvent.KEYCODE_LANGUAGE_SWITCH to Act.AUDIO,
        KeyEvent.KEYCODE_CAPTIONS to Act.SUBTITLES,
        KeyEvent.KEYCODE_TV_TELETEXT to Act.HD_FILTER,
        KeyEvent.KEYCODE_MENU to Act.OPTIONS,
        KeyEvent.KEYCODE_SETTINGS to Act.SETTINGS,
        KeyEvent.KEYCODE_MEDIA_REWIND to Act.SKIP_BACK,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to Act.SKIP_FWD,
        KeyEvent.KEYCODE_MEDIA_PLAY to Act.PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PAUSE to Act.PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to Act.PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_STOP to Act.STOP
    )

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("keymap", Context.MODE_PRIVATE)
        load()
    }

    private fun loadDefaults() {
        map.clear()
        for ((k, a) in defaults) map[k] = a
    }

    private fun load() {
        val s = prefs.getString("map", null)
        if (s == null) {
            loadDefaults()
            return
        }
        try {
            map.clear()
            val o = JSONObject(s)
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                val code = k.toIntOrNull() ?: continue
                val a = try { Act.valueOf(o.getString(k)) } catch (e: Exception) { null } ?: continue
                map[code] = a
            }
        } catch (e: Exception) {
            loadDefaults()
        }
    }

    private fun save() {
        val o = JSONObject()
        for ((k, a) in map) o.put(k.toString(), a.name)
        prefs.edit().putString("map", o.toString()).apply()
    }

    fun actionFor(keyCode: Int): Act? = map[keyCode]

    fun keysFor(a: Act): List<Int> = map.filterValues { it == a }.keys.toList()

    /** One key = one action. */
    fun set(keyCode: Int, a: Act) {
        map[keyCode] = a
        save()
    }

    fun clear(a: Act) {
        val it = map.entries.iterator()
        while (it.hasNext()) if (it.next().value == a) it.remove()
        save()
    }

    fun reset() {
        loadDefaults()
        save()
    }

    /** Keys the app needs for itself: they cannot be re-linked. */
    fun isReserved(code: Int): Boolean = when (code) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_UNKNOWN -> true
        else -> digitOf(code) >= 0
    }

    fun label(code: Int): String = when (code) {
        KeyEvent.KEYCODE_PROG_RED -> "Red"
        KeyEvent.KEYCODE_PROG_GREEN -> "Green"
        KeyEvent.KEYCODE_PROG_YELLOW -> "Yellow"
        KeyEvent.KEYCODE_PROG_BLUE -> "Blue"
        KeyEvent.KEYCODE_GUIDE -> "Guide"
        KeyEvent.KEYCODE_INFO -> "Info"
        KeyEvent.KEYCODE_CAPTIONS -> "Subt."
        KeyEvent.KEYCODE_LANGUAGE_SWITCH -> "Lang"
        KeyEvent.KEYCODE_TV_TELETEXT -> "Text"
        KeyEvent.KEYCODE_LAST_CHANNEL -> "Pre-Ch"
        KeyEvent.KEYCODE_MEDIA_RECORD -> "Rec"
        KeyEvent.KEYCODE_MEDIA_REWIND -> "Rewind"
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "Fast-fwd"
        KeyEvent.KEYCODE_MEDIA_PLAY -> "Play"
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "Pause"
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "Play/Pause"
        KeyEvent.KEYCODE_MEDIA_STOP -> "Stop"
        KeyEvent.KEYCODE_MENU -> "Menu"
        KeyEvent.KEYCODE_SETTINGS -> "Gear"
        KeyEvent.KEYCODE_TV_CONTENTS_MENU -> "List"
        KeyEvent.KEYCODE_TV_MEDIA_CONTEXT_MENU, KeyEvent.KEYCODE_MEDIA_TOP_MENU -> "Media"
        else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace('_', ' ')
            .lowercase().replaceFirstChar { c -> c.uppercase() } + " ($code)"
    }

    /** e.g. "Yellow" or "Rec, Red" or "not set". */
    fun summary(a: Act): String {
        val keys = keysFor(a)
        return if (keys.isEmpty()) "not set" else keys.joinToString(", ") { label(it) }
    }

    /** The first key linked to the action, for on-screen hints. */
    fun first(a: Act): String? = keysFor(a).firstOrNull()?.let { label(it) }
}
