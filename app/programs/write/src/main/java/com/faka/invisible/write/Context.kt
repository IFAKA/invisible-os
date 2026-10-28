package com.faka.invisible.write

import android.text.InputType

/** What the text field is for. It decides where the choices come from (Write) and what is never spoken or saved. */
enum class FieldKind { TEXT, NUMBER, DECIMAL, EMAIL, URL, SEARCH, PASSWORD, NAME }

/**
 * Everything Write knows about the place it types into, built each time a field has focus (PLAN-OS.md, "The one-button keyboard for every app").
 * Nothing here leaves the phone. [keys] are the learning buckets, most specific first.
 */
data class Frame(
    val app: String = "",
    val kind: FieldKind = FieldKind.TEXT,
    val field: String = "",          // the field id ("To", "Subject", "message_entry")
    val hint: String = "",
    val thread: String = "",         // who or what the screen is about (chat title)
    val around: List<String> = emptyList(),   // texts on the screen before the field (the last messages)
    val before: String = "",         // what is already in the field
    val hasField: Boolean = false,   // a real text field of another app has focus
    val private: Boolean = false,    // a private notification is answered: nothing about it ever leaves the phone, no AI sees it
) {
    /** May the letters typed here (and the chat) be sent to an AI? Never for a private place or a password; only plain text and search fields. */
    val aiAllowed get() = !private && (kind == FieldKind.TEXT || kind == FieldKind.SEARCH)

    fun keys(): List<String> = listOfNotNull(
        if (thread.isNotBlank()) "t:$app/$thread" else null,
        if (field.isNotBlank()) "f:$app/$field" else null,
        if (app.isNotBlank()) "a:$kind/$app" else null,
        "k:$kind",
    )

    /** One line for the big model: the screen the user is answering. Empty if there is nothing to say. */
    fun brief(): String = buildString {
        if (thread.isNotBlank()) append("Chat with: $thread\n")
        if (around.isNotEmpty()) append("Messages on the screen, oldest first (the last one is probably the one they answer):\n" + around.joinToString("\n") { "- $it" } + "\n")
        if (hint.isNotBlank()) append("The field is called: $hint\n")
    }

    val digits get() = kind == FieldKind.NUMBER || kind == FieldKind.DECIMAL

    companion object {
        /**
         * The place of a reply sent from a notification: the screen is off, so the place is the message being answered.
         * The same fields as a chat on the screen (app = package, thread = who wrote), so what is learned in one helps the other.
         * A private notification has no kept text: only the app is known.
         */
        fun reply(app: String, title: String, text: String, hidden: Boolean): Frame =
            Frame(app = app, thread = if (hidden) "" else title, around = if (hidden || text.isBlank()) emptyList() else listOf(text), private = hidden)
    }
}

object Fields {
    private val SEARCH = Regex("search|buscar|busca", RegexOption.IGNORE_CASE)

    /** [inputType] is `AccessibilityNodeInfo.getInputType()`; [hint] and [id] help where the type says only "text". */
    fun kind(inputType: Int, isPassword: Boolean, hint: String, id: String): FieldKind {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val v = inputType and InputType.TYPE_MASK_VARIATION
        return when {
            isPassword -> FieldKind.PASSWORD
            cls == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD -> FieldKind.PASSWORD
            cls == InputType.TYPE_CLASS_NUMBER && (inputType and InputType.TYPE_NUMBER_FLAG_DECIMAL) != 0 -> FieldKind.DECIMAL
            cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE -> FieldKind.NUMBER
            cls == InputType.TYPE_CLASS_TEXT && (v == InputType.TYPE_TEXT_VARIATION_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) -> FieldKind.PASSWORD
            cls == InputType.TYPE_CLASS_TEXT && (v == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || v == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) -> FieldKind.EMAIL
            cls == InputType.TYPE_CLASS_TEXT && v == InputType.TYPE_TEXT_VARIATION_URI -> FieldKind.URL
            cls == InputType.TYPE_CLASS_TEXT && v == InputType.TYPE_TEXT_VARIATION_PERSON_NAME -> FieldKind.NAME
            SEARCH.containsMatchIn(hint) || SEARCH.containsMatchIn(id) -> FieldKind.SEARCH
            else -> FieldKind.TEXT
        }
    }
}

/**
 * What you wrote in each place, kept on the phone: next-word counts per bucket (see [Frame.keys]) and the whole values you used there.
 * It also keeps how often each layer had the picked word in its top 2, so the layers that help get more weight.
 * Lines: `N key prev word count`, `V key value count`, `H layer hits tries` (tab separated). Passwords are never learned.
 */
class BucketStore(lines: List<String>, private val save: (List<String>) -> Unit = {}) {
    private val next = HashMap<String, HashMap<String, Int>>()      // "key\u0001prev" -> word -> count
    private val values = HashMap<String, LinkedHashMap<String, Int>>() // key -> whole value -> count
    private val hits = HashMap<String, IntArray>()                  // layer -> [hits, tries]
    private val spelledCounts = HashMap<String, Int>()              // words that were spelled letter by letter (names, new words) -> times

    init {
        for (l in lines) {
            val p = l.split('\t')
            try {
                when (p[0]) {
                    "N" -> next.getOrPut(p[1] + "\u0001" + p[2]) { HashMap() }[p[3]] = p[4].toInt()
                    "V" -> values.getOrPut(p[1]) { LinkedHashMap() }[p[2]] = p[3].toInt()
                    "H" -> hits[p[1]] = intArrayOf(p[2].toInt(), p[3].toInt())
                    "S" -> spelledCounts[p[1]] = p[2].toInt()
                }
            } catch (_: Exception) {}
        }
    }

    fun counts(key: String, prev: String): Map<String, Int>? = next[key + "\u0001" + prev]

    /** Call when a text is finished: [words] are the words written, [whole] the text. */
    @Synchronized fun learn(frame: Frame, words: List<String>, whole: String) {
        if (frame.kind == FieldKind.PASSWORD || words.isEmpty()) return
        val w = words.map { it.lowercase() }
        for (key in frame.keys()) {
            var prev = "<s>"
            for (x in w) { next.getOrPut(key + "\u0001" + prev) { HashMap() }.merge(x, 1, Int::plus); prev = x }
            val v = values.getOrPut(key) { LinkedHashMap() }
            v.merge(whole, 1, Int::plus)
            if (v.size > MAX_VALUES) v.entries.sortedBy { it.value }.take(v.size - MAX_VALUES).forEach { v.remove(it.key) }
        }
        persist()
    }

    /** Words spelled letter by letter in a text that was sent (a name, a new word): offered again by their first letters. Passwords are never learned. */
    @Synchronized fun learnSpelled(frame: Frame, words: List<String>) {
        if (frame.kind == FieldKind.PASSWORD) return
        for (w in words.map { it.lowercase() }.filter { it.length >= 2 }) spelledCounts.merge(w, 1, Int::plus)
        if (spelledCounts.size > MAX_VALUES * 4) spelledCounts.entries.sortedBy { it.value }.take(spelledCounts.size - MAX_VALUES * 4).forEach { spelledCounts.remove(it.key) }
        persist()
    }

    /** Spelled words that start with [prefix], most often first. */
    @Synchronized fun spelled(prefix: String, n: Int = 8): List<String> =
        spelledCounts.entries.filter { it.key.startsWith(prefix.lowercase()) && it.key != prefix.lowercase() }.sortedByDescending { it.value }.take(n).map { it.key }

    /** Whole values used in this place before, most specific place first, then most used. */
    fun suggestions(frame: Frame, n: Int = 6): List<String> {
        val out = LinkedHashSet<String>()
        for (key in frame.keys()) values[key]?.entries?.sortedByDescending { it.value }?.forEach { out.add(it.key) }
        return out.take(n)
    }

    /** How much to trust a layer: its hit rate with a gentle start, so one lucky pick does not decide. */
    fun trust(layer: String): Double { val h = hits[layer]; return ((h?.get(0) ?: 0) + 1.0) / ((h?.get(1) ?: 0) + 2.0) }

    @Synchronized fun scored(layer: String, hit: Boolean) {
        val h = hits.getOrPut(layer) { IntArray(2) }; if (hit) h[0]++; h[1]++
    }

    private fun persist() {
        val out = ArrayList<String>()
        for ((k, m) in next) { val (key, prev) = k.split('\u0001'); for ((w, c) in m) out.add("N\t$key\t$prev\t$w\t$c") }
        for ((k, m) in values) for ((v, c) in m) out.add("V\t$k\t$v\t$c")
        for ((l, h) in hits) out.add("H\t$l\t${h[0]}\t${h[1]}")
        for ((w, c) in spelledCounts) out.add("S\t$w\t$c")
        save(out)
    }

    companion object { const val MAX_VALUES = 50 }
}

/**
 * The word model plus what you wrote in this place. Each layer (thread, field, app, kind of field) that has seen the last word
 * adds its own guess; the layers are mixed by how much each one has earned (see [BucketStore.trust]).
 * Words the base model does not know (names, new words) can come from the layers, so they become reachable by learning.
 */
class BucketRanker(private val base: Ranker, private val store: BucketStore, private val frame: Frame) : Ranker {
    override val skipShortFirst get() = base.skipShortFirst
    private var cacheKey: String? = null
    private var cache: List<Pair<String, Double>> = emptyList()

    private val prior = mapOf("t" to 1.2, "f" to 1.0, "a" to 0.8, "k" to 0.6, "s" to 0.5, "g" to 1.5)
    /** Words on the screen (the chat you answer): likely to come back in the reply ("cenar" -> "cenar"). */
    /** What the big model said fits this place (the newest answers first): a topic that is kept while you write. */
    private var topic: List<String> = emptyList()
    private fun topicLayer(): Map<String, Int> = topic.withIndex().associate { (i, w) -> w to maxOf(1, 40 - i) }

    override fun hint(words: List<String>) { topic = (words + topic).distinct().take(40); cacheKey = null }

    private val screen: Map<String, Int> = frame.around.flatMap { Regex("[\\p{L}']+").findAll(it).map { m -> m.value.lowercase() }.toList() }
        .filter { it.length >= 3 }.let { ws -> HashMap<String, Int>().also { m -> for (w in ws) m.merge(w, 1, Int::plus) } }

    private fun layers(prev: String): List<Pair<String, Map<String, Int>>> =
        frame.keys().mapNotNull { k -> store.counts(k, prev)?.let { k.substringBefore(':') to it } } + (if (screen.isEmpty()) emptyList() else listOf("s" to screen)) + (if (topic.isEmpty()) emptyList() else listOf("g" to topicLayer()))

    override fun ranked(context: List<String>): List<Pair<String, Double>> {
        val prev = context.lastOrNull()?.lowercase() ?: "<s>"
        val ctxKey = context.takeLast(2).joinToString(" ")
        if (ctxKey == cacheKey) return cache
        val b = base.ranked(context)
        val ls = layers(prev)
        val out = if (ls.isEmpty()) b else {
            val wBase = store.trust("base")
            val ws = ls.map { (l, _) -> prior.getValue(l) * store.trust(l) }
            val z = wBase + ws.sum()
            val extra = HashMap<String, Double>()
            ls.forEachIndexed { i, (_, m) -> val s = m.values.sum().toDouble(); for ((w, c) in m) extra.merge(w, ws[i] * c / s, Double::plus) }
            val seen = HashSet<String>(b.size)
            val mixed = ArrayList<Pair<String, Double>>(b.size + extra.size)
            for ((w, lp) in b) { seen.add(w); mixed.add(w to Math.log((wBase * Math.exp(lp) + (extra[w] ?: 0.0)) / z)) }
            for ((w, e) in extra) if (w !in seen) mixed.add(w to Math.log((e + 1e-9) / z))
            mixed.sortByDescending { it.second }
            mixed
        }
        cacheKey = ctxKey; cache = out
        return out
    }

    /** The word [picked] after [context] was chosen: each layer is credited when it had the word in its top 2. */
    override fun picked(context: List<String>, word: String) {
        val prev = context.lastOrNull()?.lowercase() ?: "<s>"
        val w = word.lowercase()
        store.scored("base", base.ranked(context).asSequence().filter { it.first.length >= 1 }.take(2).any { it.first == w })
        for ((l, m) in layers(prev)) store.scored(l, if (l == "s" || l == "g") (m[w] ?: 0) >= if (l == "g") 37 else 1 else m.entries.sortedByDescending { it.value }.take(2).any { it.key == w })
        cacheKey = null
    }
}
