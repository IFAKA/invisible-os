package com.faka.invisible.logickit

/**
 * The accuracy guard and the prompts of the notification AI, the SAME as the laptop's (bridge/story.py check_story, bridge/aitext.py),
 * so the phone model is held to the same rules: no invented number, name, file or quote; short; and on any doubt the answer is ""
 * and the original text is read. Pure: the model is a function `(prompt) -> text`. Keep this file and the bridge in step.
 */
object AiGuard {
    const val SHORT_WORDS = 45          // the guard limit; the prompt asks for 30
    const val ASK_SHORT_WORDS = 30
    const val STORY_LIMIT = 30
    const val CONTEXT_CHARS = 1000
    const val MAX_GROUPS = 4

    /** label -> (one, many). The model only picks a label per item; the code counts, so the numbers always add up. */
    val LABELS = linkedMapOf("chat" to ("chat" to "chats"), "delivery" to ("delivery" to "deliveries"), "news" to ("news item" to "news items"),
        "work" to ("work message" to "work messages"), "payment" to ("payment" to "payments"), "reminder" to ("reminder" to "reminders"),
        "junk" to ("junk" to "junk"), "other" to ("other" to "other"))

    private const val W = "[\\p{L}\\p{N}_-]"
    private val number = Regex("\\d+(?:[.,:]\\d+)*")
    private val file = Regex("\\b$W+(?:\\.$W+)*\\.[A-Za-z][A-Za-z0-9]{0,5}\\b")
    private val quote = Regex("\"([^\"\\n]+)\"|\u201c([^\u201d\\n]+)\u201d|`([^`\\n]+)`")
    private val cap = Regex("\\b[A-Z]$W*")
    private val sentenceEnd = Regex("[.!?:]\\s+|\\n+")
    private val free = setOf("ai", "claude", "ok", "okay")

    /** The tokens of [text] that must come from the source: numbers, file names, quoted strings, capitalised words that do not start a sentence. */
    fun riskyTokens(text: String): List<String> {
        val starts = HashSet<Int>().apply { add(0); sentenceEnd.findAll(text).forEach { add(it.range.last + 1) } }
        val out = ArrayList<String>()
        number.findAll(text).forEach { out += it.value }
        file.findAll(text).forEach { out += it.value }
        quote.findAll(text).forEach { m -> out += m.groupValues.drop(1).first { it.isNotEmpty() } }
        cap.findAll(text).filter { it.range.first !in starts }.forEach { out += it.value }
        return out
    }

    private fun inSource(token: String, sourceLower: String): Boolean {
        val t = token.lowercase().trim { it in ".,:;" }
        if (t.isEmpty() || t in free) return true
        if (t[0].isDigit()) return Regex("(?<![\\d.])" + Regex.escape(t) + "(?![\\d])").containsMatchIn(sourceLower)
        return t in sourceLower
    }

    /** (ok, reason). Short, shorter than the source, and nothing invented: every risky token is in [source] or [context] (earlier messages, titles). */
    fun checkStory(story: String, source: String, context: String = "", limit: Int = STORY_LIMIT): Pair<Boolean, String> {
        val s = story.squash()
        if (s.isEmpty()) return false to "empty"
        if (s.words().size > limit) return false to "too long"
        if (s.length >= source.squash().length) return false to "not shorter than the source"
        val hay = (source + "\n" + context).lowercase()
        riskyTokens(s).firstOrNull { !inSource(it, hay) }?.let { return false to "invented: $it" }
        return true to ""
    }

    /** The earlier messages of the chat, cut like the bridge does. */
    fun earlierBlock(context: String): String {
        val c = context.squash().take(CONTEXT_CHARS)
        return if (c.isEmpty()) "" else "Earlier messages in the same chat (oldest first, for background only):\n---\n${context.trim().take(CONTEXT_CHARS)}\n---\n"
    }

    fun shortPrompt(text: String, context: String = ""): String =
        "${earlierBlock(context)}Below is a message sent to a person.\n---\n${text.take(3000)}\n---\n" +
            "Write what it says for a blind listener, as JSON: {\"short\": \"...\"}.\n" +
            "short: one or two sentences, at most $ASK_SHORT_WORDS words, plain words, no markdown. Say who wants what, when, and any number or place. " +
            "Use only facts, numbers and names that are in the message or the earlier messages. Never invent.\nOutput only the JSON."

    const val TASK_WORDS = 12

    private const val TASK_EXAMPLES = "Example. item: Juego de cables puente macho-macho, 3,29 EUR, Diotronic -> {\"short\": \"Comprar cables puente macho-macho\"}\n" +
        "Example. item: Call the bank about the card statement before Friday -> {\"short\": \"Call the bank about the card\"}\n"
    private val price = Regex("[,;]?\\s*~?\\d+(?:[.,]\\d+)?\\s*(?:\u20ac|eur(?:os?)?)\\.?", RegexOption.IGNORE_CASE)

    /** A task line without prices (the model is told to leave them out, small models forget). */
    fun cleanTask(s: String): String = s.replace(price, "").squash().trimEnd(',', ';', '.', ' ')

    /** One short action phrase for a to-do item, in its own language. */
    fun taskPrompt(text: String): String =
        "Below is a to-do item, in any language.\n---\n${text.take(1500)}\n---\n" +
            "Write the action to do, as JSON: {\"short\": \"...\"}.\n" +
            "short: a short action phrase, 3 to 7 words, in the SAME language as the item (never translate: Spanish stays Spanish), starting with a verb " +
            "(Comprar, Llamar, Buy, Call). Say only what to do and the thing; leave out prices, shop names, sizes and details. " +
            "Use only names and numbers that are in the item. Never invent.\n${TASK_EXAMPLES}Output only the JSON."

    const val TASKS_WORDS = 20

    /** What a whole to-do list is mostly about (2-3 themes, no counts: the code counts). */
    fun tasksPrompt(items: List<String>): String =
        "Below is a person's to-do list, one item per line.\n---\n${items.joinToString("\n") { it.squash() }.take(3000)}\n---\n" +
            "Say what the list is mostly about, for a blind listener, as JSON: {\"short\": \"...\"}.\n" +
            "short: a phrase of 4 to 10 words that starts with \"Mostly\", in English, no counts, no markdown. Example: Mostly buying electronics parts, and a few calls. " +
            "Use only things that are in the list. Never invent.\nOutput only the JSON."

    private val markdown = Regex("(\\*\\*|__|`|^#+\\s*)")

    /** The string value of [key] in the JSON-ish [raw] (the model may add text or fences around it), unescaped; "" if none. */
    private fun stringField(raw: String, key: String): String {
        val m = Regex("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(raw) ?: return ""
        return unescape(m.groupValues[1])
    }

    private fun unescape(s: String): String {
        val b = StringBuilder(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                when {
                    n == 'n' || n == 't' || n == 'r' -> b.append(' ')
                    n == 'u' && i + 6 <= s.length && s.substring(i + 2, i + 6).all { it.isLetterOrDigit() } ->
                        { b.append(s.substring(i + 2, i + 6).toIntOrNull(16)?.toChar() ?: '?'); i += 4 }
                    else -> b.append(n)
                }
                i += 2
            } else { b.append(c); i++ }
        }
        return b.toString()
    }

    fun parseShort(raw: String): String = stringField(raw, "short").squash().replace(markdown, "").squash()

    const val COMPLETE_WORDS = 12

    fun completePrompt(typed: String, lang: String, context: String = ""): String {
        val language = if (lang == "es") "Spanish" else "English"
        return "${earlierBlock(context)}Someone is typing a message on a phone with one button, so every letter is slow. So far they typed: \"${typed.take(200)}\"\n" +
            "The typed letters are EITHER the start of the word they mean (\"hel\" = \"help\") OR the first letters of the next words (\"hay\" = \"how are you\"). " +
            "Write up to 5 whole texts they most likely mean, most likely first, in $language, as JSON: {\"completions\": [...]}. " +
            "Each text starts with the words already typed, is natural and short, at most $COMPLETE_WORDS words. " +
            "Use only facts, numbers and names that are in the earlier messages. Never invent.\nOutput only the JSON."
    }

    /** The strings of {"completions": [...]} in the model's reply; empty if it is not the JSON asked for. */
    fun parseCompletions(raw: String): List<String> {
        val m = Regex("\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL).find(raw.substringAfter("\"completions\"", "")) ?: return emptyList()
        return Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(m.groupValues[1]).map { unescape(it.groupValues[1]).squash().replace(markdown, "").squash() }.filter { it.isNotEmpty() }.toList()
    }

    /** Up to 5 completions of [typed] that fit it, are short and invent nothing (checked against [typed] and [context]); empty on any failure. */
    fun makeCompletions(typed: String, context: String, lang: String, model: (String) -> String): List<String> {
        val t = typed.squash()
        if (t.isEmpty()) return emptyList()
        val items = try { parseCompletions(model(completePrompt(t, lang, context))) } catch (_: Exception) { return emptyList() }
        val hay = (t + "\n" + context.squash().take(CONTEXT_CHARS)).lowercase()
        val out = ArrayList<String>()
        for (x in items) {
            val c = x.squash().trim(' ', '"', '\'')
            if (c.isEmpty() || c.words().size > COMPLETE_WORDS || out.any { it.equals(c, true) } || !Completion.fits(c, t)) continue
            if (riskyTokens(c).any { !inSource(it, hay) }) continue
            out += c
        }
        return out.take(5)
    }

    /** A 1-2 sentence version of [text] that passes the guard (names and numbers are checked against [text] and [context] together), or "". */
    fun makeShort(text: String, context: String, model: (String) -> String): String {
        if (text.squash().isEmpty()) return ""
        val s = try { parseShort(model(shortPrompt(text, context))) } catch (_: Exception) { return "" }
        return if (checkStory(s, text, context.squash().take(CONTEXT_CHARS), SHORT_WORDS).first) s else ""
    }

    /** The short action phrase for a to-do item that passes the guard, or "". */
    fun makeTaskLine(text: String, model: (String) -> String): String {
        if (text.squash().isEmpty()) return ""
        val s = try { cleanTask(parseShort(model(taskPrompt(text)))) } catch (_: Exception) { return "" }
        return if (checkStory(s, text, "", TASK_WORDS).first) s else ""
    }

    /** The themes line for a to-do list that passes the guard, or "". */
    fun makeTasksSummary(items: List<String>, model: (String) -> String): String {
        if (items.isEmpty()) return ""
        val s = try { parseShort(model(tasksPrompt(items))) } catch (_: Exception) { return "" }
        return if (checkStory(s, items.joinToString("\n"), "", TASKS_WORDS).first) s else ""
    }

    // ---- digest ----

    fun digestPrompt(items: List<Triple<String, String, String>>): String {
        val lines = items.mapIndexed { i, it -> "${i + 1}. ${it.first.squash().take(30)} | ${it.second.squash().take(60)} | ${it.third.squash().take(120)}" }
        return "Below are ${items.size} phone notifications (app | title | text).\n---\n${lines.joinToString("\n")}\n---\n" +
            "Give each one a label, in order. Allowed labels: ${LABELS.keys.joinToString(", ")}.\n" +
            "chat = a person writing to the user; delivery = a parcel, food or ride; junk = ads, promotions, marketing.\n" +
            "Output only JSON: {\"labels\": [one label per notification, exactly ${items.size}]}."
    }

    private val synonyms = mapOf("deliverie" to "delivery", "parcel" to "delivery", "message" to "chat", "ad" to "junk", "promotion" to "junk", "spam" to "junk")

    /** The list of [n] labels from the model's reply, or null. */
    fun parseLabels(raw: String, n: Int): List<String>? {
        val arr = Regex("\"labels\"\\s*:\\s*\\[([^\\]]*)\\]").find(raw)?.groupValues?.get(1) ?: return null
        val vals = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(arr).map { it.groupValues[1] }.toList()
        if (vals.size != n) return null
        return vals.map { v ->
            val l = v.squash().lowercase().trimEnd('s').let { synonyms[it] ?: it }
            if (l !in LABELS) return null
            l
        }
    }

    /** "3 chats, 2 deliveries, 3 junk": groups by count (most first, ties by first seen), at most [MAX_GROUPS] (the rest joins "other"). The numbers add up to labels.size. */
    fun digestLine(labels: List<String>): String {
        val order = labels.distinct()
        val count = labels.groupingBy { it }.eachCount()
        val ranked = order.sortedWith(compareBy({ -count.getValue(it) }, { order.indexOf(it) }))
        val parts = ranked.take(MAX_GROUPS).map { count.getValue(it) to it }.toMutableList()
        val rest = ranked.drop(MAX_GROUPS).sumOf { count.getValue(it) }
        if (rest > 0) parts += rest to "other"
        return parts.joinToString(", ") { (c, l) -> "$c " + LABELS.getValue(l).let { if (c == 1) it.first else it.second } }
    }

    /** True when the numbers in [line] add up to [n]. */
    fun checkDigest(line: String, n: Int): Boolean {
        val nums = Regex("\\d+").findAll(line).map { it.value.toInt() }.toList()
        return nums.isNotEmpty() && nums.sum() == n && line.words().size <= 24
    }

    fun makeDigest(items: List<Triple<String, String, String>>, model: (String) -> String): String {
        if (items.isEmpty()) return ""
        val line = try { parseLabels(model(digestPrompt(items)), items.size)?.let { digestLine(it) }.orEmpty() } catch (_: Exception) { "" }
        return if (checkDigest(line, items.size)) line else ""
    }
}
