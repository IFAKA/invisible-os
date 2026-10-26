package com.faka.invisible.logickit

/** Find one song for a typed text: the request for YouTube Music's web search (songs only) and the first song in its answer. Pure; the network call is in `Actions`. */
object MusicSearch {
    class Hit(val videoId: String, val title: String, val artist: String)

    const val URL = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
    private const val SONGS_ONLY = "EgWKAQIIAWoQEAMQBBAJEAoQBRAQEBUQEA%3D%3D"

    fun body(query: String): String =
        """{"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"1.20241023.01.00","hl":"en"}},"query":"${query.replace("\\", "").replace("\"", "").squash()}","params":"$SONGS_ONLY"}"""

    private val text = Regex(""""text"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val id = Regex(""""videoId"\s*:\s*"([\w-]{11})"""")

    /** The first song after the "Songs" shelf header: its id, title and artist; null if the answer has none. */
    fun first(json: String): Hit? {
        val at = text.findAll(json).firstOrNull { it.groupValues[1] == "Songs" } ?: return null
        val rest = json.substring(at.range.last)
        val videoId = id.find(rest)?.groupValues?.get(1) ?: return null
        val words = text.findAll(rest).map { unescape(it.groupValues[1]) }.filter { it.isNotBlank() && it.trim() != "•" }.take(2).toList()
        val title = words.getOrNull(0) ?: return null
        val artist = words.getOrNull(1) ?: ""
        return Hit(videoId, title, artist)
    }

    private fun unescape(s: String): String = Regex("""\\u([0-9a-fA-F]{4})""").replace(s) { it.groupValues[1].toInt(16).toChar().toString() }.replace("\\\"", "\"").replace("\\\\", "\\")
}
