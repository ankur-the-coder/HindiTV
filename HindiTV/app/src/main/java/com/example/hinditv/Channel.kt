package com.example.hinditv

data class Channel(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String?
)

/** Holds the list currently shown, so the player can zap through it. */
object ChannelStore {
    var channels: List<Channel> = emptyList()
    var index: Int = 0
}
