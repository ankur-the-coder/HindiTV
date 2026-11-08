package com.example.hinditv

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Favorites, channel numbers (1-99) and last watched channel. Stored with full channel info. */
object UserData {
    private lateinit var prefs: SharedPreferences
    private val favs = LinkedHashMap<String, Channel>()
    private val numToChannel = HashMap<Int, Channel>()
    private val urlToNum = HashMap<String, Int>()
    var last: Channel? = null
        private set

    private fun toJson(c: Channel): JSONObject =
        JSONObject().put("n", c.name).put("u", c.url).put("l", c.logo ?: "").put("g", c.group ?: "")

    private fun fromJson(o: JSONObject): Channel =
        Channel(
            o.getString("n"),
            o.getString("u"),
            o.optString("l", "").ifEmpty { null },
            o.optString("g", "").ifEmpty { null }
        )

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("userdata", Context.MODE_PRIVATE)
        try {
            val arr = JSONArray(prefs.getString("fav", "[]"))
            for (i in 0 until arr.length()) {
                val c = fromJson(arr.getJSONObject(i))
                favs[c.url] = c
            }
        } catch (e: Exception) { }
        try {
            val o = JSONObject(prefs.getString("num", "{}"))
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val n = k.toIntOrNull() ?: continue
                val c = fromJson(o.getJSONObject(k))
                numToChannel[n] = c
                urlToNum[c.url] = n
            }
        } catch (e: Exception) { }
        last = try {
            val s = prefs.getString("last", null)
            if (s == null) null else fromJson(JSONObject(s))
        } catch (e: Exception) { null }
    }

    private fun saveFavs() {
        val arr = JSONArray()
        for (c in favs.values) arr.put(toJson(c))
        prefs.edit().putString("fav", arr.toString()).apply()
    }

    private fun saveNums() {
        val o = JSONObject()
        for ((n, c) in numToChannel) o.put(n.toString(), toJson(c))
        prefs.edit().putString("num", o.toString()).apply()
    }

    // ---- favorites
    fun isFav(url: String) = favs.containsKey(url)

    /** Returns true if the channel is now a favorite. */
    fun toggleFav(ch: Channel): Boolean {
        val now: Boolean
        if (favs.containsKey(ch.url)) { favs.remove(ch.url); now = false } else { favs[ch.url] = ch; now = true }
        saveFavs()
        return now
    }

    fun favoriteList(): List<Channel> = favs.values.toList()

    fun clearFavs() { favs.clear(); saveFavs() }

    // ---- numbers
    fun numberOf(url: String): Int = urlToNum[url] ?: 0

    fun channelFor(n: Int): Channel? = numToChannel[n]

    fun numberSet(): Set<Int> = numToChannel.keys.toSet()

    fun numberEntries(): List<Pair<Int, Channel>> =
        numToChannel.entries.sortedBy { it.key }.map { Pair(it.key, it.value) }

    fun numberedList(): List<Channel> = numberEntries().map { it.second }

    /** One channel = one number, one number = one channel. */
    fun assign(n: Int, ch: Channel) {
        urlToNum[ch.url]?.let { numToChannel.remove(it) }
        numToChannel[n]?.let { urlToNum.remove(it.url) }
        numToChannel[n] = ch
        urlToNum[ch.url] = n
        saveNums()
    }

    fun removeNumber(n: Int) {
        val c = numToChannel.remove(n) ?: return
        urlToNum.remove(c.url)
        saveNums()
    }

    fun clearNumbers() {
        numToChannel.clear(); urlToNum.clear(); saveNums()
    }

    fun nextFree(): Int {
        for (n in 1..99) if (!numToChannel.containsKey(n)) return n
        return 0
    }

    /** Gives every favorite without a number the next free number. Returns how many were numbered. */
    fun autoNumberFavorites(): Int {
        var count = 0
        for (c in favs.values) {
            if (urlToNum.containsKey(c.url)) continue
            val n = nextFree()
            if (n == 0) break
            numToChannel[n] = c
            urlToNum[c.url] = n
            count++
        }
        if (count > 0) saveNums()
        return count
    }

    // ---- last watched
    fun setLast(ch: Channel) {
        last = ch
        prefs.edit().putString("last", toJson(ch).toString()).apply()
    }
}
