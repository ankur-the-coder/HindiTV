package com.example.hinditv

import android.content.Context
import android.content.SharedPreferences

object Settings {
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    private fun putBool(key: String, v: Boolean) { prefs.edit().putBoolean(key, v).apply() }
    private fun putInt(key: String, v: Int) { prefs.edit().putInt(key, v).apply() }

    /** "hin", "eng", "ind" or "custom" */
    var source: String
        get() = prefs.getString("source", "hin") ?: "hin"
        set(v) { prefs.edit().putString("source", v).apply() }

    var customUrl: String
        get() = prefs.getString("customUrl", "") ?: ""
        set(v) { prefs.edit().putString("customUrl", v).apply() }

    /** 0 = low, 1 = medium, 2 = high */
    var buffering: Int
        get() = prefs.getInt("buffering", 0)
        set(v) = putInt("buffering", v)

    var leveling: Boolean
        get() = prefs.getBoolean("leveling", true)
        set(v) = putBool("leveling", v)

    /** 0 = fit, 1 = zoom, 2 = stretch */
    var aspect: Int
        get() = prefs.getInt("aspect", 0)
        set(v) = putInt("aspect", v)

    var skipDead: Boolean
        get() = prefs.getBoolean("skipDead", true)
        set(v) = putBool("skipDead", v)

    var resumeLast: Boolean
        get() = prefs.getBoolean("resumeLast", false)
        set(v) = putBool("resumeLast", v)

    var autoUpdate: Boolean
        get() = prefs.getBoolean("autoUpdate", true)
        set(v) = putBool("autoUpdate", v)

    /** 0 = playlist order, 1 = A-Z */
    var sort: Int
        get() = prefs.getInt("sort", 0)
        set(v) = putInt("sort", v)

    var logos: Boolean
        get() = prefs.getBoolean("logos", true)
        set(v) = putBool("logos", v)

    var infoSeconds: Int
        get() = prefs.getInt("infoSeconds", 5)
        set(v) = putInt("infoSeconds", v)

    var techInfo: Boolean
        get() = prefs.getBoolean("techInfo", true)
        set(v) = putBool("techInfo", v)

    var relaxedSsl: Boolean
        get() = prefs.getBoolean("relaxedSsl", true)
        set(v) = putBool("relaxedSsl", v)

    /** 0 = Auto (up to the best the stream and TV allow), 1 = Highest always, 2 = max 1080p, 3 = max 720p */
    var quality: Int
        get() = prefs.getInt("quality", 0)
        set(v) = putInt("quality", v)

    /** 0 = Auto (leave the TV alone), 1 = ask for HDR output, 2 = force SDR output (safe colours) */
    var hdrMode: Int
        get() = prefs.getInt("hdrMode", 0)
        set(v) = putInt("hdrMode", v)

    /** Switching the TV's refresh rate per channel makes the screen blink, so it is off by default. */
    var matchFps: Boolean
        get() = prefs.getBoolean("matchFps", false)
        set(v) = putBool("matchFps", v)
}
