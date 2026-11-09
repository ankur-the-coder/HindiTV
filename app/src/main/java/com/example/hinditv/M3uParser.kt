package com.example.hinditv

object M3uParser {

    private val logoRe = Regex("tvg-logo=\"([^\"]*)\"")
    private val groupRe = Regex("group-title=\"([^\"]*)\"")

    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        var name: String? = null
        var logo: String? = null
        var group: String? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF")) {
                name = line.substringAfterLast(",").trim()
                logo = logoRe.find(line)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
                group = groupRe.find(line)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
            } else if (line.startsWith("#")) {
                continue
            } else if (name != null) {
                out.add(Channel(name.ifEmpty { "Channel" }, line, logo, group))
                name = null
                logo = null
                group = null
            }
        }
        return out.distinctBy { it.url }
    }
}
