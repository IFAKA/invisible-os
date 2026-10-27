package com.faka.invisible.voicekit

/**
 * One language: [table] maps the English sentence (the key) to the sentence in this language.
 * A key with `{0}`, `{1}` is a pattern: the parts it catches are translated too ("Timer {0} minutes"). [numbers] = the words zero..thirty.
 */
class LangPack(val code: String, val table: Map<String, String>, val numbers: List<String>, val screen: Map<String, String> = emptyMap()) {
    /** Spoken and screen entries together. */
    val all: Map<String, String> by lazy { table + screen }
}

/** Looks up fixed sentences for the menu language. English is the key itself, so English needs no table. */
object Translations {
    /** Add a language here. */
    val packs: List<LangPack> get() = listOf(SPANISH)

    private val EN_NUMBERS = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen",
        "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "twenty one", "twenty two", "twenty three", "twenty four",
        "twenty five", "twenty six", "twenty seven", "twenty eight", "twenty nine", "thirty")

    private class Rule(val key: String, val regex: Regex, val value: String, val weight: Int)
    private val rules = HashMap<String, List<Rule>>()

    private fun rulesFor(p: LangPack): List<Rule> = synchronized(rules) {
        rules.getOrPut(p.code) {
            p.all.filterKeys { '{' in it }.map { (k, v) ->
                val parts = Regex("\\{\\d}").split(k).map { Regex.escape(it) }
                Rule(k, Regex("^" + parts.joinToString("(.+?)") + "$"), v, k.replace(Regex("\\{\\d}"), "").length)
            }.sortedByDescending { it.weight }
        }
    }

    /** The sentence in [code], or null when there is no translation (the caller then keeps the English). */
    fun translate(code: String, text: String): String? {
        val p = packs.firstOrNull { it.code == code } ?: return null
        return lookup(p, text, 0)
    }

    private fun lookup(p: LangPack, text: String, depth: Int): String? {
        p.all[text]?.let { return it }
        val n = EN_NUMBERS.indexOf(text.lowercase())
        if (n >= 0) return p.numbers[n].let { if (text.first().isUpperCase()) it.replaceFirstChar { c -> c.uppercase() } else it }
        if (depth > 3) return null
        for (r in rulesFor(p)) {
            val m = r.regex.matchEntire(text) ?: continue
            var out = r.value
            for (i in 1 until m.groupValues.size) {
                val g = m.groupValues[i]
                out = out.replace("{${i - 1}}", lookup(p, g, depth + 1) ?: g)
            }
            return out
        }
        return null
    }

    /** The language tag and text a fixed sentence gets in the menu language; null language = English. */
    fun resolve(text: String): Pair<String, String?> {
        val c = MenuLanguage.code
        if (c == "en" || text.isEmpty()) return text to null
        val t = translate(c, text) ?: return text to null
        return t to c
    }

    /** A screen label in the menu language (the same table). Unknown text stays as it is. */
    fun label(text: String): String = resolve(text).first
}
