package com.example.hinditv

data class Channel(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String?
)

/** What the player is currently zapping through. */
object Session {
    /** The list the player zaps through (also shown in the side drawer). */
    var channels: List<Channel> = emptyList()
    var index: Int = 0

    /** Every channel of the loaded playlist. */
    var all: List<Channel> = emptyList()

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
