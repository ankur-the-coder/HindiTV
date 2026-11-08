package com.example.hinditv

object M3uParser {

    private fun attr(line: String, key: String): String? {
        val m = Regex(key + "=\"([^\"]*)\"").find(line) ?: return null
        return m.groupValues[1].trim().ifEmpty { null }
    }

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
                logo = attr(line, "tvg-logo")
                group = attr(line, "group-title")
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
