package com.faka.invisible.logickit

/**
 * Pure: turns a notification's raw title and text into the one short line that is spoken AND shown (same string, so
 * what you hear is what you see). Code runs first and instantly; the AI short version only replaces it when the
 * cleaned text is still over [AI_WORDS] words (see AiAssist.isLongClean).
 *
 * Rules: a reaction ("Reacted ❤️ to "old message"") becomes "Lisandro reacted with a heart." (the quoted message is
 * dropped); links become "a link" / "a YouTube link"; emoji are dropped; the sender is said once; repeated
 * punctuation and "..." cuts are removed.
 */
object NotifSpeech {
    /** A cleaned text longer than this many words is worth an AI short version. */
    const val AI_WORDS = 25

    private val reactionRe = Regex("""(?is)^\s*(?:reacted|reaccion[oó]|reagiu)\s+(?:con\s+|com\s+|with\s+)?(\S+)\s+(?:to|a|à|ao)\b.*$""")
    private val reactEs = Regex("""(?i)^\s*reaccion[oó]""")
    private val emojiRe = Regex("""[\p{So}\p{Sk}\p{Cs}‍️⃣]+""")
    private val durationRe = Regex("""\s*\(\d{1,2}:\d{2}(?::\d{2})?\)""")
    private val ellipsisRe = Regex("""\s*(?:\.{2,}|…+)""")
    private val repeatRe = Regex("""([.!?,;:\-¡¿])\1+""")
    private val spaceBeforeRe = Regex("""\s+([.,;:!?])""")
    private val senderRe = Regex("""^([^:\n]{1,30}?):\s+(\S.*)$""", RegexOption.DOT_MATCHES_ALL)

    private val hearts = setOf("❤", "❤️", "♥", "♥️", "💙", "💚", "💛", "🧡", "💜", "🖤", "🤍", "💕", "💖", "😍")
    private val thumbs = setOf("👍", "👍🏻", "👍🏼", "👍🏽", "👍🏾", "👍🏿")
    private val laughs = setOf("😂", "🤣", "😆", "😹")
    private val cries = setOf("😢", "😭", "🥲")
    private val prays = setOf("🙏", "🙏🏻", "🙏🏼", "🙏🏽", "🙏🏾", "🙏🏿")
    private val fires = setOf("🔥")

    private fun emojiWord(e: String, es: Boolean): String? = when (e) {
        in hearts -> if (es) "un corazón" else "a heart"
        in thumbs -> if (es) "un me gusta" else "a thumbs up"
        in laughs -> if (es) "una risa" else "a laugh"
        in cries -> if (es) "una lágrima" else "a cry"
        in prays -> if (es) "unas gracias" else "a prayer"
        in fires -> if (es) "fuego" else "fire"
        else -> null
    }

    /** "Lisandro reacted with a heart." when [text] is a reaction, else null. [title] (the person) names who reacted. */
    fun reaction(title: String, text: String): String? {
        val m = reactionRe.matchEntire(text) ?: return null
        val es = reactEs.containsMatchIn(text)
        val who = MessageLogic.stableName(title).words().firstOrNull().orEmpty()
        val w = emojiWord(m.groupValues[1].trim(), es)
        val verb = if (es) "reaccionó" else "reacted"
        val with = if (es) "con" else "with"
        return listOf(who, verb).filter { it.isNotEmpty() }.joinToString(" ") + (if (w != null) " $with $w" else "") + "."
    }

    /** The text alone, cleaned: links named, emoji dropped, placeholders plain, punctuation calm. */
    fun cleanText(text: String, lang: String = ""): String {
        var t = MessageLogic.spoken(text, lang)
        t = durationRe.replace(t, "")
        t = emojiRe.replace(t, " ")
        t = ellipsisRe.replace(t, ".")
        t = repeatRe.replace(t, "$1")
        t = spaceBeforeRe.replace(t.squash(), "$1").trimStart('.', ',', ';', ':', ' ')
        val cut = text.trimEnd().let { it.endsWith("...") || it.endsWith("…") }
        return if (cut) t.trimEnd('.', ',', ' ') else t
    }

    /**
     * The line for a notification: "[title][sep][cleaned text]". The title is not repeated if the text starts with
     * it. In a chat ([chat]) a group message "Ana: hi" under the title "Group" is said "Ana in Group: hi", and a
     * reaction is one short sentence.
     */
    fun read(title: String, text: String, lang: String = "", sep: String = ": ", chat: Boolean = false): String {
        val name = cleanText(MessageLogic.stableName(title), lang)
        var body = text.trim()
        while (name.isNotEmpty() && body.startsWith("$name:", ignoreCase = true)) body = body.substring(name.length + 1).trim()
        var head = name
        var who = name
        if (chat && name.isNotEmpty()) {
            val m = senderRe.matchEntire(body)
            val s = m?.groupValues?.get(1)?.trim().orEmpty()
            if (m != null && s.words().size <= 3 && s.none { it.isDigit() || it == '/' } && !s.equals(name, true)) {
                who = s
                head = s + (if (MessageLogic.wordingLang(lang) == "es") " en " else " in ") + name
                body = m.groupValues[2]
            }
        }
        if (chat) reaction(who, body)?.let { return it }
        return listOf(head, cleanText(body, lang)).filter { it.isNotBlank() }.joinToString(sep)
    }

    /** True when the cleaned [text] is long enough that the AI short version should replace it. */
    fun isLong(cleaned: String): Boolean = cleaned.words().size > AI_WORDS
}
