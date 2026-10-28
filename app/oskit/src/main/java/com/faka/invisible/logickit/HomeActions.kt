package com.faka.invisible.logickit

import com.faka.invisible.voicekit.Menu
import com.faka.invisible.voicekit.Phrase

/** What a home action does. */
enum class ActionKind { OPEN, START, PLAY, REPLY, CALL }

/**
 * One thing the letters typed at home can ask for: "Open WhatsApp", "Play Billie Jean", "Reply to Ana", "Call Ana".
 * [name] is what the letters are matched against (an app, a song, a person); [target] is what the OS needs to run it
 * (an app id, a search text, a notification key, "name\nnumber"); [prior] is how likely it is before any letter (use counts), more = likelier.
 * A private chat is never an action beyond "<App>, new notification", so a reply action is only built from a non-private notification.
 */
data class HomeAction(val kind: ActionKind, val name: String, val target: String, val prior: Double, val lang: String? = null) {
    val key: String get() = "${kind.name.lowercase()}:$target"
    /** What is said when the action is offered (short: it is read alone, once per cycle). Never the key or a raw id. */
    val label: Phrase get() = when (kind) {
        ActionKind.OPEN -> Menu.openApp(ActionSpeech.short(name, 4))
        ActionKind.START -> Menu.startProgram(ActionSpeech.short(name, 4))
        ActionKind.PLAY -> Menu.playSong(ActionSpeech.short(name, 4))
        ActionKind.REPLY -> Menu.replyTo(ActionSpeech.person(name))
        ActionKind.CALL -> Menu.callName(ActionSpeech.person(name))
    }
}

/** Short names for speech. Pure. */
object ActionSpeech {
    /** [name] without (brackets), [brackets], "feat." tails and " - version" tails, at most [max] words ("La Maza (feat. Shakira)" -> "La Maza"). */
    fun short(name: String, max: Int): String {
        var t = name.replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        t = t.split(" - ", " – ", " | ")[0]
        t = t.replace(Regex("(?i)\\b(feat|ft|featuring)\\b.*$"), " ")
        val w = t.squash().split(' ').filter { it.isNotEmpty() }
        return (if (w.isEmpty()) name.squash() else w.take(max).joinToString(" "))
    }

    /** A person or chat title: at most 3 words; a raw id (digits, bars, no letters) is "the chat". */
    fun person(name: String): String {
        val s = short(name, 3)
        return if (s.count { it.isLetter() } < 2 || '|' in s) "the chat" else s
    }
}

/** A source of home actions (the plug-in of [ActionRanker], like Write's GuessSource): quick, from what the OS keeps; it may fill itself in the background and answer with what it has. */
interface ActionSource {
    val id: String
    fun actions(): List<HomeAction>
}

/** How well the typed letters fit a name. Pure. */
object ActionMatch {
    fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    const val PREFIX = 1.0
    const val WORD = 0.6
    const val INITIALS = 0.35

    /** 1 = the name starts with the letters; 0.6 = a later word starts with them; 0.35 = the first letters of the words start with them (2 letters or more); 0 = no fit. No letters: everything fits (1). */
    fun quality(name: String, letters: String): Double {
        val l = key(letters)
        if (l.isEmpty()) return 1.0
        if (key(name).startsWith(l)) return PREFIX
        val ws = name.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (ws.drop(1).any { key(it).startsWith(l) }) return WORD
        if (l.length >= 2 && ws.map { it.first().lowercaseChar() }.joinToString("").startsWith(l)) return INITIALS
        return 0.0
    }

    /** The letter that follows [letters] in [name] when the fit is a start of the name or of a word, else null. */
    fun next(name: String, letters: String): Char? {
        val l = key(letters); val k = key(name)
        if (k.startsWith(l)) return k.getOrNull(l.length)
        val ws = name.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        for (w in ws.drop(1)) { val wk = key(w); if (wk.startsWith(l)) return wk.getOrNull(l.length) }
        return null
    }
}

/**
 * The one ranker of the home letters (same mixing rule as Write's Mixer, Smart Compose style): the chance of an action is the sum over the
 * sources of share(source) x its chance inside the source. Inside a source the chance is prior x (1 + own picks) x fit of the letters, then
 * scaled to sum 1. The shares come from [trust] (learned from picks: a source that had the picked action in its top 3 earns trust, see [picked]).
 */
class ActionRanker(private val trust: (String) -> Double = { 0.5 }, private val scored: (String, Boolean) -> Unit = { _, _ -> }, private val picks: (String) -> Int = { 0 }) {
    class Ranked(val action: HomeAction, val chance: Double)
    private var lastTables: Map<String, List<String>> = emptyMap()   // source -> action keys, best first (for learning)
    private fun layer(id: String) = "home:$id"

    fun rank(sources: List<ActionSource>, letters: String): List<Ranked> {
        val tables = LinkedHashMap<String, Map<HomeAction, Double>>()
        for (s in sources) {
            val scores = LinkedHashMap<HomeAction, Double>()
            for (a in s.actions()) {
                val q = ActionMatch.quality(a.name, letters)
                if (q > 0) scores[a] = a.prior.coerceAtLeast(1e-6) * (1 + picks(a.key)) * q
            }
            val z = scores.values.sum()
            if (z > 0) tables[s.id] = scores.mapValues { it.value / z }
        }
        val shares = SourceMix.shares(tables.keys, 0.01) { trust(layer(it)) }
        val mixedBy = SourceMix.mix(tables.mapValues { (_, t) -> t.mapKeys { it.key.key } }, shares)
        val byKey = HashMap<String, HomeAction>(); tables.values.forEach { t -> t.keys.forEach { byKey.putIfAbsent(it.key, it) } }
        val mixed = mixedBy.mapValues { byKey.getValue(it.key) to it.value }
        lastTables = tables.mapValues { (_, t) -> SourceMix.ranks(t).map { it.key } }
        return mixed.values.sortedWith(compareByDescending<Pair<HomeAction, Double>> { it.second }.thenBy { it.first.name }).map { Ranked(it.first, it.second) }
    }

    /** [key] was picked: credit every source that had it in its top 3. Returns the best source and rank for the trace (null = nobody had it). */
    fun picked(key: String): Pair<String, Int>? {
        return SourceMix.credit(lastTables, { it.indexOf(key) }) { id, hit -> scored(layer(id), hit) }
    }

    /** The letters that can come next, with their chance: from the names of the ranked actions (weighted by chance). Empty when nothing fits. */
    fun nextLetters(ranked: List<Ranked>, letters: String): Map<Char, Double> {
        val m = HashMap<Char, Double>()
        for (r in ranked) ActionMatch.next(r.action.name, letters)?.takeIf { it.isLetterOrDigit() }?.let { m.merge(it, r.chance, Double::plus) }
        val z = m.values.sum()
        return if (z > 0) m.mapValues { it.value / z } else emptyMap()
    }
}

/** Songs the person played, newest and most played first. Fed from what the media sessions say is playing ("Title, Artist"). Pure; [save] persists the text form. */
class SongsPlayed(initial: String?, private val max: Int = 60, private val save: (String) -> Unit = {}) {
    data class Song(val title: String, val artist: String, val plays: Int, val at: Long) { val search get() = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ") }
    private val list = ArrayList<Song>()
    init { initial?.lines()?.forEach { l -> val p = l.split('\t'); if (p.size == 4) list.add(Song(p[0], p[1], p[2].toIntOrNull() ?: 1, p[3].toLongOrNull() ?: 0L)) } }

    /** [line] as the OS has it ("Title, Artist" or "Title"). A song heard again counts one more play; it is never counted twice in a row within 2 minutes. */
    @Synchronized fun record(line: String?, now: Long) {
        val t = line?.trim().orEmpty(); if (t.isEmpty()) return
        val title = t.substringBeforeLast(", ", t).trim(); val artist = if (", " in t) t.substringAfterLast(", ").trim() else ""
        if (title.isEmpty()) return
        val i = list.indexOfFirst { it.title.equals(title, true) && it.artist.equals(artist, true) }
        if (i >= 0) { val o = list.removeAt(i); list.add(0, if (now - o.at < 120_000) o.copy(at = now) else o.copy(plays = o.plays + 1, at = now)) }
        else list.add(0, Song(title, artist, 1, now))
        while (list.size > max) list.removeAt(list.size - 1)
        save(list.joinToString("\n") { "${it.title.replace('\t', ' ')}\t${it.artist.replace('\t', ' ')}\t${it.plays}\t${it.at}" })
    }

    @Synchronized fun all(): List<Song> = list.toList()
}
