package com.example.hinditv

import android.content.Context
import okhttp3.Request
import java.io.File

object Repository {

    class Source(
        val key: String,
        val name: String,
        val label: String,
        val url: String,
        val section: String = "Top",
        val code: String = ""
    )

    private const val BASE = "https://iptv-org.github.io/iptv/"

    private fun lang(code: String, name: String, badge: String = code.uppercase()) =
        Source("l_$code", name, "$name channels", BASE + "languages/$code.m3u", "Languages", badge)

    private fun country(code: String, name: String) =
        Source("c_$code", name, "$name channels", BASE + "countries/$code.m3u", "Countries", code.uppercase())

    private fun category(code: String, name: String) =
        Source("k_$code", name, "$name channels", BASE + "categories/$code.m3u", "Categories", name.take(3).uppercase())

    /** Hindi and English always stay at the top. Keys "hin", "eng", "ind" are kept so saved settings still work. */
    val PRESETS: List<Source> = listOf(
        Source("hin", "Hindi", "Hindi channels", BASE + "languages/hin.m3u", "Top", "HI"),
        Source("eng", "English", "English channels", BASE + "languages/eng.m3u", "Top", "EN"),
        Source("ind", "India", "India - all languages", BASE + "countries/in.m3u", "Top", "IN"),

        country("us", "United States"), country("uk", "United Kingdom"), country("ca", "Canada"),
        country("au", "Australia"), country("ae", "UAE"), country("pk", "Pakistan"),
        country("bd", "Bangladesh"), country("np", "Nepal"), country("lk", "Sri Lanka"),
        country("sa", "Saudi Arabia"), country("qa", "Qatar"), country("sg", "Singapore"),
        country("my", "Malaysia"), country("id", "Indonesia"), country("th", "Thailand"),
        country("jp", "Japan"), country("kr", "South Korea"), country("cn", "China"),
        country("ru", "Russia"), country("tr", "Turkey"), country("ir", "Iran"),
        country("eg", "Egypt"), country("za", "South Africa"), country("ng", "Nigeria"),
        country("de", "Germany"), country("fr", "France"), country("es", "Spain"),
        country("it", "Italy"), country("pt", "Portugal"), country("nl", "Netherlands"),
        country("ie", "Ireland"), country("gr", "Greece"), country("pl", "Poland"),
        country("ua", "Ukraine"), country("br", "Brazil"), country("mx", "Mexico"),
        country("ar", "Argentina"), country("nz", "New Zealand"),

        lang("urd", "Urdu", "UR"), lang("tam", "Tamil", "TA"), lang("tel", "Telugu", "TE"),
        lang("ben", "Bengali", "BN"), lang("pan", "Punjabi", "PA"), lang("mar", "Marathi", "MR"),
        lang("guj", "Gujarati", "GU"), lang("mal", "Malayalam", "ML"), lang("kan", "Kannada", "KN"),
        lang("ara", "Arabic", "AR"), lang("spa", "Spanish", "ES"), lang("fra", "French", "FR"),
        lang("deu", "German", "DE"), lang("por", "Portuguese", "PT"), lang("rus", "Russian", "RU"),
        lang("zho", "Chinese", "ZH"), lang("jpn", "Japanese", "JA"), lang("kor", "Korean", "KO"),

        category("news", "News"), category("entertainment", "Entertainment"),
        category("movies", "Movies"), category("sports", "Sports"), category("music", "Music"),
        category("kids", "Kids"), category("documentary", "Documentary"),
        category("religious", "Religious"), category("comedy", "Comedy"),
        category("education", "Education"), category("lifestyle", "Lifestyle")
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
