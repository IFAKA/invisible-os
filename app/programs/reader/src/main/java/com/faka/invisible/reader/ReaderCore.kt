package com.faka.invisible.reader

import android.content.SharedPreferences
import com.faka.invisible.logickit.MessageLogic
import com.faka.invisible.logickit.Paragraphs

/**
 * Which text the Reader reads and where it stopped. Pure over [SharedPreferences] (tests use a fake map).
 * The Share target (`ReaderShareActivity` in :os) calls [share]; the program calls [pick].
 */
object ReaderStore {
    const val SHARED = "reader_shared"   // a text waiting to be read (consumed once)
    const val LAST = "reader_last"       // the text read last
    private fun pos(key: String) = "reader_pos_$key"

    fun share(p: SharedPreferences, text: String) { p.edit().putString(SHARED, text.take(Paragraphs.MAX_CHARS)).apply() }

    /** The text to read: the shared one (once), else [clipboard] if it has words, else the last one. Null = nothing. */
    fun pick(p: SharedPreferences, clipboard: String?): String? {
        val shared = p.getString(SHARED, null)?.takeIf { Paragraphs.split(it).isNotEmpty() }
        val text = shared ?: clipboard?.takeIf { Paragraphs.split(it).isNotEmpty() } ?: p.getString(LAST, null)?.takeIf { Paragraphs.split(it).isNotEmpty() }
        val e = p.edit().remove(SHARED)
        if (text != null) e.putString(LAST, text.take(Paragraphs.MAX_CHARS))
        e.apply()
        return text?.take(Paragraphs.MAX_CHARS)
    }

    fun position(p: SharedPreferences, text: String, parts: Int): Int = p.getInt(pos(Paragraphs.key(text)), 0).coerceIn(0, maxOf(0, parts - 1))
    fun save(p: SharedPreferences, text: String, index: Int) { p.edit().putInt(pos(Paragraphs.key(text)), index).apply() }
    /** Read to the end: next time it starts over. */
    fun finished(p: SharedPreferences, text: String) { p.edit().remove(pos(Paragraphs.key(text))).apply() }

    /** "es" or "en" when clear; else null (the default voice). */
    fun lang(text: String): String? = MessageLogic.voiceLang(MessageLogic.guessLang(text))
}
