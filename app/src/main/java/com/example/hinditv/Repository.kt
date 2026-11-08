package com.example.hinditv

import android.content.Context
import okhttp3.Request
import java.io.File

object Repository {

    class Source(val key: String, val name: String, val label: String, val url: String)

    val PRESETS = listOf(
        Source("hin", "Hindi", "Hindi channels", "https://iptv-org.github.io/iptv/languages/hin.m3u"),
        Source("eng", "English", "English channels", "https://iptv-org.github.io/iptv/languages/eng.m3u"),
        Source("ind", "India", "India - all languages", "https://iptv-org.github.io/iptv/countries/in.m3u")
    )

    fun current(): Source {
        val key = Settings.source
        if (key == "custom") {
            val u = Settings.customUrl.trim()
            return Source("custom_" + Math.abs(u.hashCode()), "Custom", "Custom URL", u)
        }
        return PRESETS.firstOrNull { it.key == key } ?: PRESETS[0]
    }

    private fun cacheFile(ctx: Context, src: Source) = File(ctx.filesDir, "playlist_" + src.key + ".m3u")

    /** Blocking: call from a background thread. */
    fun readCache(ctx: Context): List<Channel> {
        val f = cacheFile(ctx, current())
        if (!f.exists()) return emptyList()
        return try { M3uParser.parse(f.readText()) } catch (e: Exception) { emptyList() }
    }

    /** Blocking: call from a background thread. Throws on failure. */
    fun download(ctx: Context): List<Channel> {
        val src = current()
        if (src.url.isBlank()) throw RuntimeException("No playlist URL set")
        val req = Request.Builder().url(src.url).build()
        Net.client(ctx).newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP " + resp.code)
            val body = resp.body?.string() ?: throw RuntimeException("Empty response")
            val list = M3uParser.parse(body)
            if (list.isEmpty()) throw RuntimeException("No channels found")
            cacheFile(ctx, src).writeText(body)
            return list
        }
    }
}
