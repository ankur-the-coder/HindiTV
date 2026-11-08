package com.example.hinditv

import android.content.Context

class FavoritesStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    private fun load(): Set<String> = prefs.getStringSet("urls", emptySet()) ?: emptySet()

    fun isFav(url: String) = load().contains(url)

    /** Returns true if the channel is now a favorite. */
    fun toggle(url: String): Boolean {
        val set = HashSet(load())
        val nowFav = if (set.contains(url)) { set.remove(url); false } else { set.add(url); true }
        prefs.edit().putStringSet("urls", set).apply()
        return nowFav
    }
}
