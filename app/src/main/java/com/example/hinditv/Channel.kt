package com.example.hinditv

data class Channel(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String?
) {
    /** 0 = SD/unknown, 2 = HD, 3 = Full HD, 4 = 4K. Guessed from the channel name, e.g. "Name (1080p)". */
    val quality: Int = Quality.detect(name)
}

object Quality {
    const val SD = 0
    const val HD = 2
    const val FHD = 3
    const val UHD = 4

    private val uhd = Regex("\\b(2160[pi]?|4k|uhd)\\b", RegexOption.IGNORE_CASE)
    private val fhd = Regex("\\b(1080[pi]?|1440p?|fhd|full ?hd)\\b", RegexOption.IGNORE_CASE)
    private val hd = Regex("\\b(720[pi]?|hd)\\b", RegexOption.IGNORE_CASE)

    fun detect(name: String): Int = when {
        uhd.containsMatchIn(name) -> UHD
        fhd.containsMatchIn(name) -> FHD
        hd.containsMatchIn(name) -> HD
        else -> SD
    }

    fun label(q: Int): String = when (q) {
        UHD -> "4K"
        FHD -> "FHD"
        HD -> "HD"
        else -> ""
    }
}

/** What the player is currently zapping through. */
object Session {
    /** The list the player zaps through (also shown in the side drawer). */
    var channels: List<Channel> = emptyList()
    var index: Int = 0

    /** Every channel of the loaded playlist. */
    var all: List<Channel> = emptyList()

    /** For the PRE-CH key: the channel watched before the current one. */
    var previous: Channel? = null
    var lastPlayed: Channel? = null

    /** Set by the player's SEARCH key: the list screen opens with the search bar active. */
    var openSearch: Boolean = false

    fun open(list: List<Channel>, ch: Channel) {
        val i = list.indexOfFirst { it.url == ch.url }
        if (i >= 0) {
            channels = list
            index = i
        } else {
            channels = listOf(ch) + all
            index = 0
        }
    }
}
