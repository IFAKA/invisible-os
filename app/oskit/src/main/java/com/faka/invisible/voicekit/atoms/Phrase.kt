package com.faka.invisible.voicekit

/**
 * Marks code that may only be used inside the voice kit. Using it elsewhere is a compile error
 * (and checkVoiceKit also fails the build when a file outside voicekit/ mentions it).
 */
@RequiresOptIn(level = RequiresOptIn.Level.ERROR, message = "Only the voice kit (voicekit/) may do this. Add a sentence to Sentences.kt instead.")
@Retention(AnnotationRetention.BINARY)
annotation class KitOnly

/**
 * Something the phone may say. Two kinds, and nothing else can be spoken:
 *  - a FIXED sentence: made only inside voicekit (`Phrase.fixed`, which needs [KitOnly]) so every fixed sentence lives in Sentences.kt;
 *  - CONTENT: text that belongs to the user or the world (a message, a task, a song title): `Phrase.content(text, lang)`.
 * [lang] is the voice language; null = the menu voice (English).
 */
class Phrase private constructor(val text: String, val lang: String?, val isFixed: Boolean) {
    override fun equals(other: Any?) = other is Phrase && other.text == text && other.lang == lang
    override fun hashCode() = text.hashCode() * 31 + (lang?.hashCode() ?: 0)
    override fun toString() = text

    val isEmpty get() = text.isEmpty()
    val isNotEmpty get() = text.isNotEmpty()
    operator fun plus(o: Phrase) = Phrase(text + o.text, lang ?: o.lang, isFixed && o.isFixed)

    companion object {
        @KitOnly fun fixed(text: String): Phrase { val (t, l) = Translations.resolve(text); return Phrase(t, l, true) }
        /** A sentence with a name or other content inside ("Calling Ana"): translated like a fixed one, but counted as content. */
        @KitOnly fun mixed(text: String): Phrase { val (t, l) = Translations.resolve(text); return Phrase(t, l, false) }
        /** User or world text, spoken as it is. Never pass a literal sentence here (checkVoiceKit rejects it): add it to Sentences.kt. */
        fun content(text: String, lang: String? = null): Phrase = Phrase(text, lang, false)
        /** Phrases joined with a space. */
        fun join(parts: List<Phrase>): Phrase = Phrase(parts.joinToString(" ") { it.text }, parts.firstOrNull { it.lang != null }?.lang, parts.all { it.isFixed })
    }
}
