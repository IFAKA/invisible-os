package com.faka.invisible.logickit

/**
 * Which language a reply should be translated into. Pure: the detector is passed in (ML Kit on the phone, a fake in tests).
 * The place is the text around the reply: the chat on the screen, or the message being answered.
 */
object ChatLang {
    const val MIN_CHARS = 12

    /** The language code of [seen], or null when the text is too short or the detector does not know ("und"). */
    fun of(seen: String, detect: (String) -> String): String? =
        if (seen.length < MIN_CHARS) null else detect(seen).takeIf { it != "und" && it.isNotEmpty() }

    /** True when a reply written in English must be translated into [target]. */
    fun needsTranslation(target: String?): Boolean = target != null && target != "en"
}
