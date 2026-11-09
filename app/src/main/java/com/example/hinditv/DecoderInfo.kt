package com.example.hinditv

import android.media.MediaCodecList

object DecoderInfo {
    /** true = a hardware decoder exists for this mime type, false = only software, null = unknown. */
    fun hasHardware(mime: String?): Boolean? {
        if (mime == null) return null
        return try {
            var found = false
            var hw = false
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (info.isEncoder) continue
                if (info.supportedTypes.any { it.equals(mime, true) }) {
                    found = true
                    val n = info.name.lowercase()
                    if (!(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw."))) hw = true
                }
            }
            if (found) hw else null
        } catch (e: Exception) {
            null
        }
    }

    fun shortName(mime: String?): String? = when (mime) {
        null -> null
        "video/avc" -> "H.264"
        "video/hevc" -> "H.265"
        "video/mpeg2" -> "MPEG-2"
        "audio/mp4a-latm" -> "AAC"
        "audio/ac3" -> "AC3"
        "audio/eac3" -> "E-AC3"
        "audio/mpeg" -> "MP3"
        else -> mime.substringAfter('/')
    }
}
