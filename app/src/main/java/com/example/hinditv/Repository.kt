package com.example.hinditv

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object Repository {
    const val PLAYLIST_URL = "https://iptv-org.github.io/iptv/languages/hin.m3u"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "playlist.m3u")

    /** Blocking: call from a background thread. */
    fun readCache(ctx: Context): List<Channel> {
        val f = cacheFile(ctx)
        if (!f.exists()) return emptyList()
        return try { M3uParser.parse(f.readText()) } catch (e: Exception) { emptyList() }
    }

    /** Blocking: call from a background thread. Throws on failure. */
    fun download(ctx: Context): List<Channel> {
        val req = Request.Builder().url(PLAYLIST_URL).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw RuntimeException("Empty response")
            val list = M3uParser.parse(body)
            if (list.isEmpty()) throw RuntimeException("No channels found")
            cacheFile(ctx).writeText(body)
            return list
        }
    }
}
