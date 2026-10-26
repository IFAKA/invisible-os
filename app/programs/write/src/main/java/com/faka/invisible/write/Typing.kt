package com.faka.invisible.write

import com.faka.invisible.voicekit.*

/** What the Write program needs to know about the words: all words, most likely first, after the given words. */
interface Ranker {
    fun ranked(context: List<String>): List<Pair<String, Double>>
    /** True when short words and contractions are too hard to hear alone, so the first pair skips them (English voice). Spanish words are short and clear. */
    val skipShortFirst: Boolean get() = false
    /** Words the big model thinks fit this place (most likely first): a layer of the ranker can use them. */
    fun hint(words: List<String>) {}
    /** [word] was chosen after [context]: a ranker that learns by place can credit its layers. */
    fun picked(context: List<String>, word: String) {}
}

/** What the Write program asks of the app. The words of a pair are heard one after the other: the left word in the left ear, then the right word in the right ear. */
interface TypeOut {
    fun pair(left: String, right: String?)
    fun hush()   // a press stops the words that are playing
    fun prefetch(words: List<String>)
    fun say(text: Phrase)
    fun cue(c: TypeCue)
    /** One line for the usage file (event, then fields); lets the layout be tuned from real use. */
    fun log(event: String, vararg fields: Any?) {}
    fun finished(text: String)   // the written text; "" = nothing written
    /** Ask the big model (slow, in the background) for ways to finish [text]; call [then] with them when ready. Default: none. */
    fun phrases(text: String, then: (List<String>) -> Unit) {}
    /** Whole values you used in this place before (addresses, names, searches, numbers). Default: none. */
    fun suggestions(): List<String> = emptyList()
    /** Ask the big model (slow, background) for the next words after [text]; call [then] always, with the words or an empty list. Default: never. */
    fun nextWords(text: String, then: (List<String>) -> Unit) {}
    /** Turn the English [text] into the language of the place. Call [then] exactly once; a failure is a [Translation.Failed], never the plain text. Default: no translation needed. */
    fun translate(text: String, then: (Translation) -> Unit) { then(Translation.Same) }
    /** Say the translation aloud in its own language so the player can check it before it is sent. */
    fun readBack(text: String) {}
    /** A press cuts a sentence that is being spoken (the sentence so far can be long). */
    fun silence() {}
    /** The English text the player wrote (what the models learn from), even when the translation is what is sent. */
    fun written(text: String) {}
}

/** The answer of [TypeOut.translate]. */
sealed class Translation {
    object Same : Translation()                         // the place uses the writing language: send as written
    data class Done(val text: String) : Translation()   // translated: read it back, send only after a click
    data class Failed(val say: Phrase) : Translation()  // could not translate: say why, send nothing
}

/**
 * Write: a one-button keyboard (PLAN-OS.md). The phone plays two likely items one after the other, the first in the left ear, the second in the right.
 * click = left, hold (0.5 s up to 3 s) = right, 2 clicks = next pair, 4 clicks = the pair before, 3 clicks = the small menu: Done, Delete, Letters (or Words).
 * The menu is read one item at a time; a click picks the item just spoken; 2 clicks = the previous item; a hold or 3 clicks closes it.
 * Hold 3 s always leaves. It gets the raw button ([down]/[up]) and owns no menu. Time and timers are injected.
 */
/** For places that offer no words (digits only). */
object NoWords : Ranker { override fun ranked(context: List<String>) = emptyList<Pair<String, Double>>() }

class TypeProgram(
    private val lm: Ranker,
    private val out: TypeOut,
    private val timers: Timers,
    private val frame: Frame = Frame(),   // the place being typed into; the default is a plain text place
    private val onEnded: () -> Unit,
) : RawButton {
    companion object {
        const val POINT = "point"   // the decimal point is offered and spoken as this word
        const val REPLAY_MS = 7000L
        const val MAX_REPLAYS = 3
        const val MENU_GAP_MS = 2000L
        const val MENU_LOOPS = 2
        const val WORD_PAGES = 4
        const val ALPHABET = "abcdefghijklmnopqrstuvwxyzñ"
        const val ALPHABET_BY_USE = "eaosrnidltcumpbgvyqhfzjñxkw"   // common letters in Spanish and English
        val LETTER_RE = Regex("[a-záéíóúüñ']+")
    }

    private val written = ArrayList<String>()
    private val spelledWords = HashSet<String>()   // words made of spelled letters: not learned
    private var prefix = ""
    private var letters = false
    private var page = 0
    private var items: List<String> = emptyList()
    private var replays = 0
    private var clicks = 0
    private var downAt = -1L
    private var armed = false
    private var menu = false
    private var recap = false   // the next pair is a new word: first say the sentence so far
    /** The small menu, read one item at a time by the kit's Scanner: one pause per item, no OS ceremony. */
    private val menuScan: Scanner<Phrase> = Scanner(timers, { it }, object : ScanOut {
        override fun say(index: Int, label: Phrase, spearcon: Boolean) { out.say(label); menuScan.itemDone() }
        override fun sayFast(word: Phrase) {}
        override fun tone(t: Tone) {}
        override fun leave() { closeMenu() }
    }, ScanCadence(pauseMs = { MENU_GAP_MS }, loops = { MENU_LOOPS }, ceremony = false), scanKey = "t:menu")
    private var phrases: List<String> = emptyList()   // ways to finish the text, from the big model, when ready
    private var phraseMode = false
    private var phraseGen = 0
    private var guessing = false
    private var pressedSinceShow = false
    private var touched = false   // the player has pressed since the start
    private var waiting = false   // Done was picked: the translation is being made
    private var pending: String? = null   // the translation, read back, waiting for a click to send it
    override val exitOnClickHold = false   // click then hold is "left word, then right word"; Write is left by holding 3 s
    override var active = false; private set
    var intro: List<Phrase> = listOf(TypeVoice.START, TypeVoice.START2, TypeVoice.START_NEXT, TypeVoice.START3)   // said once at the start

    fun start() {
        if (frame.kind == FieldKind.PASSWORD) { out.say(TypeVoice.PASSWORD); onEnded(); return }   // never spoken, never saved
        active = true
        intro.forEach { out.say(it) }
        askPhrases()
        if (frame.kind != FieldKind.TEXT && phrases.isNotEmpty()) phraseMode = true   // addresses, names, searches: what you used here comes first
        show()
    }

    fun stop() { if (active) { active = false; waiting = false; pending = null; menu = false; menuScan.stop(); timers.clear("t:gap"); timers.clear("t:hold"); timers.clear("t:replay") } }

    fun text(): String = if (frame.digits) written.joinToString("") { if (it == POINT) "." else it } else written.joinToString(" ").replaceFirstChar { it.uppercase() }

    // ---- what is offered ----
    private fun candidates(): List<String> {
        if (frame.digits) return "1234567890".map { it.toString() } + (if (frame.kind == FieldKind.DECIMAL) listOf(POINT) else emptyList())
        val recent = written.takeLast(3).toSet()   // a word just written is not offered again at once
        val all = lm.ranked(written.takeLast(8))   // at the start of a word, a short word or a contraction is hard to hear alone: those come through letters
        return if (letters) {
            val mass = LinkedHashMap<Char, Double>()
            for ((w, lp) in all) if (w.length > prefix.length && w.startsWith(prefix) && LETTER_RE.matches(w)) mass.merge(w[prefix.length], Math.exp(lp), Double::plus)
            if (mass.isEmpty()) return ALPHABET.toList().sorted().map { it.toString() }   // no word to guess from: A to Z, so where a letter is can be predicted
            // the whole alphabet is ranked: the model's chance first, a small prior for common letters breaks ties and orders letters it has no word for
            ALPHABET.toList().sortedByDescending { (mass[it] ?: 0.0) + 1e-6 * (ALPHABET.length - ALPHABET_BY_USE.indexOf(it).let { i -> if (i < 0) ALPHABET.length else i }) }.map { it.toString() }
        } else all.asSequence().filter { it.first !in recent }.filter { it.first.startsWith(prefix) && LETTER_RE.matches(it.first) && (prefix.isNotEmpty() || !lm.skipShortFirst || (it.first.length >= 3 && '\'' !in it.first)) }.take(WORD_PAGES * 2).map { it.first }.toList()
    }

    private fun show(again: Boolean = false) {
        if (!again) replays = 0
        items = if (phraseMode) phrases else candidates()
        if (phraseMode && items.isEmpty()) phraseMode = false
        if (items.isEmpty()) { if (prefix.isNotEmpty() && !letters && !frame.digits) { letters = true; page = 0; items = candidates() }   // no word starts so: keep spelling, never drop the letters
        else if (letters) { letters = false; page = 0; items = candidates() } else { items = candidates() } }
        val n = (items.size + 1) / 2
        if (n > 0) page %= n else page = 0
        val l = items.getOrNull(page * 2) ?: return
        val r = items.getOrNull(page * 2 + 1)
        // a new word, or a long silence (replay): the sentence so far first, then the letters of the word being spelled
        if ((recap || again) && written.isNotEmpty() && !frame.digits && !phraseMode) out.readBack(written.joinToString(" "))
        if (letters && prefix.isNotEmpty()) out.say(TypeVoice.spelled(prefix))   // every letter pair says the letters so far
        recap = false   // every letter pair, replays too: you always hear what you have so far
        out.prefetch(items.drop(page * 2 + 2).take(4) )
        out.pair(l, r)
        out.log("pair", l, r, page, if (phraseMode) "phrases" else if (letters) "letters" else "words", prefix, written.size)
        pressedSinceShow = false
        timers.set("t:replay", REPLAY_MS) { if (active && replays < MAX_REPLAYS) { replays++; show(true) } else if (active && replays == MAX_REPLAYS) { replays++; out.say(TypeVoice.STILL_HERE) } }   // then silence, so say where you are once
    }

    // ---- the raw button ----
    override fun down(time: Long) {
        if (!active) return
        touched = true; pressedSinceShow = true
        out.silence(); out.hush(); timers.clear("t:gap"); timers.clear("t:replay"); menuScan.stop()
        downAt = time; armed = false
        // The hold acts when it reaches HOLD_MS, while the button is still down: the pick sound is the feedback, and holding on does nothing more.
        timers.set("t:hold", Pacing.HOLD_MS) { if (active && downAt >= 0) { armed = true; clicks = 0; holdAction() } }
    }

    override fun up(time: Long) {
        if (!active || downAt < 0) return
        timers.clear("t:hold")
        val long = time - downAt >= Pacing.HOLD_MS
        val acted = armed
        downAt = -1; armed = false
        if (acted) return   // the hold already acted while the button was down
        if (long) { clicks = 0; holdAction(); return }   // the timer was late
        clicks++
        timers.set("t:gap", Pacing.GAP_MS) { val n = clicks; clicks = 0; if (active) many(n) }
    }

    override fun assistHold() {
        if (!active || downAt >= 0) return
        touched = true; pressedSinceShow = true
        out.hush(); timers.clear("t:gap"); timers.clear("t:replay"); menuScan.stop()
        clicks = 0; holdAction()
    }

    /** A hold (one press of HOLD_MS): the right word; in the menu or while a translation waits, a way back. */
    private fun holdAction() { if (waiting) return; if (pending != null) backToEdit() else if (menu) closeMenu() else choose(false) }

    private fun many(n: Int) {
        if (waiting) return
        pending?.let { t -> if (n == 1) send(t) else { out.readBack(t); out.say(TypeVoice.CONFIRM) }; return }
        if (menu) { when (n) { 1 -> pickMenu(); 2 -> menuScan.prevIn(Pacing.SKIP_MS); else -> closeMenu() }; return }
        when (n) {
            1 -> choose(true)
            2 -> { out.log("next", page); out.cue(TypeCue.NEXT); page++; show() }
            3 -> { out.log("menu"); openMenu() }
            4 -> { val n = (items.size + 1) / 2; out.log("back", page); out.cue(TypeCue.NEXT); page = if (page > 0) page - 1 else maxOf(n - 1, 0); show() }   // one pair back (before the first: the last)
            else -> show(true)   // 5 or more clicks mean nothing: play the pair again
        }
    }

    // ---- the small menu: Done, Delete, Letters/Words, read one at a time ----
    /** What Delete will remove, said in its name: a letter while spelling, a word otherwise. */
    private fun deleteLabel() = when {
        frame.digits || (prefix.isEmpty() && written.isEmpty()) -> TypeVoice.MENU_DELETE
        letters && prefix.isNotEmpty() -> TypeVoice.DELETE_LETTER
        else -> TypeVoice.DELETE_WORD
    }

    private fun menuLabels(): List<Phrase> =   // Done is last: it is used once, at the end; the first items are used while typing
        (if (frame.digits) (if (phraseMode) listOf(TypeVoice.DIGITS) else emptyList()) else listOf(if (letters || phraseMode) TypeVoice.WORDS else TypeVoice.LETTERS)) +
        (if (prefix.isNotEmpty()) listOf(TypeVoice.SPACE) else emptyList()) + listOf(deleteLabel()) + (if (phrases.isNotEmpty() && !phraseMode) listOf(TypeVoice.PHRASES) else emptyList()) + listOf(TypeVoice.MENU_DONE, TypeVoice.MENU_LEAVE) +
        (if (prefix.isNotEmpty() || written.isNotEmpty()) listOf(TypeVoice.READ) else emptyList())   // Read is last: two clicks on the first item reach it

    /** The big model gets the text so far; a late answer for an older text is ignored. */
    private fun askPhrases() {
        phrases = if (written.isEmpty()) out.suggestions() else emptyList()
        val g = ++phraseGen
        if (!frame.digits && written.isEmpty() && frame.around.isNotEmpty()) out.phrases(text()) { r ->
            if (!active || g != phraseGen) return@phrases
            phrases = r
            // answering a chat: the replies that fit it come first, as soon as they are ready, unless the player is already busy
            if (r.isNotEmpty() && !touched && !menu) { phraseMode = true; page = 0; out.cue(TypeCue.NEXT); show() }
        }
        if (!frame.digits) guess()
    }

    /** One request at a time; when the answer comes and the text moved on, ask again at once. The words are kept as a topic of this place, even when a little late. */
    private fun guess() {
        if (guessing) return
        guessing = true
        val asked = written.joinToString(" ")
        out.nextWords(asked) { words ->
            guessing = false
            if (!active) return@nextWords
            if (words.isNotEmpty()) lm.hint(words)
            if (written.joinToString(" ") != asked) guess() else refreshIfIdle()
        }
    }

    /** New guesses arrived: if the pair now playing is stale and the player has not pressed, play the better one. */
    private fun refreshIfIdle() {
        if (pressedSinceShow || menu || phraseMode || letters || frame.digits) return
        if (candidates().take(2) != items.drop(page * 2).take(2)) show()
    }

    private fun openMenu() { menu = true; menuScan.load(menuLabels()); timers.clear("t:replay"); out.cue(TypeCue.MENU); menuScan.speakItem(0) }

    private fun closeMenu() { menu = false; menuScan.stop(); out.cue(TypeCue.NEXT); show() }

    private fun pickMenu() {
        val label = menuScan.list.current
        menu = false; menuScan.stop()
        out.log("menupick", when (label) { TypeVoice.MENU_DONE -> "done"; TypeVoice.MENU_LEAVE -> "leave"; TypeVoice.MENU_DELETE, TypeVoice.DELETE_LETTER, TypeVoice.DELETE_WORD -> "delete"; TypeVoice.PHRASES -> "phrases"; TypeVoice.SPACE -> "space"; TypeVoice.READ -> "read"; TypeVoice.DIGITS -> "digits"; else -> "letters/words" })
        when (label) {
            TypeVoice.MENU_DONE -> leave(true)
            TypeVoice.MENU_LEAVE -> leave(false)
            TypeVoice.MENU_DELETE, TypeVoice.DELETE_LETTER, TypeVoice.DELETE_WORD -> {   // spelling: one letter; words: the word. The mode stays.
                out.cue(TypeCue.DELETE)
                if (prefix.isNotEmpty()) prefix = if (letters) prefix.dropLast(1) else "" else if (written.isNotEmpty()) { written.removeAt(written.size - 1); askPhrases() }
                phraseMode = false; page = 0; recap = true; show()
            }
            TypeVoice.SPACE -> { spelledWords.add(prefix); written.add(prefix); prefix = ""; letters = false; phraseMode = false; page = 0; recap = true; askPhrases(); out.cue(TypeCue.WORDS); show() }
            TypeVoice.READ -> { out.readBack((written + listOfNotNull(prefix.ifEmpty { null })).joinToString(" ")); show() }
            TypeVoice.PHRASES -> { phraseMode = true; letters = false; page = 0; out.cue(TypeCue.WORDS); show() }
            else -> { phraseMode = false; if (!frame.digits) letters = !letters; page = 0; out.cue(if (letters) TypeCue.LETTERS else TypeCue.WORDS); show() }
        }
    }

    /** In letters mode a pick adds a letter and stays on letters until the menu says Words. */
    private fun choose(left: Boolean) {
        val item = items.getOrNull(page * 2 + if (left) 0 else 1)
        if (item == null) { out.cue(TypeCue.NEXT); show(true); return }
        out.cue(if (left) TypeCue.PICK_LEFT else TypeCue.PICK_RIGHT)
        out.log("pick", if (left) "L" else "R", item, page, if (phraseMode) "phrases" else if (letters) "letters" else "words", prefix)
        if (phraseMode) { written.addAll(item.split(' ').filter { it.isNotEmpty() }); phraseMode = false; prefix = ""; recap = true; askPhrases() }
        else if (letters) { prefix += item } else { lm.picked(written.takeLast(8), item); written.add(item); prefix = ""; recap = true; askPhrases() }
        page = 0
        show()
    }

    private fun leave(done: Boolean) {
        if (done && prefix.isNotEmpty()) { spelledWords.add(prefix); written.add(prefix); prefix = "" }   // letters spelled but not yet a word: keep them
        val t = if (done) text() else ""
        out.log("end", if (done) "done" else "leave", written.size, t.length)
        if (t.isEmpty()) { send(""); return }
        waiting = true; timers.clear("t:replay")
        out.translate(t) { r ->
            if (!active || !waiting) return@translate
            waiting = false
            when (r) {
                is Translation.Same -> send(t)
                is Translation.Done -> { pending = r.text; out.readBack(r.text); out.say(TypeVoice.CONFIRM) }
                is Translation.Failed -> { out.say(r.say); show() }   // nothing is sent; the text is kept and you can try again
            }
        }
    }

    /** Hold while the translation waits for a click: go back to writing, nothing is sent. */
    private fun backToEdit() { pending = null; out.cue(TypeCue.NEXT); show() }

    private fun send(sent: String) {
        val original = if (sent.isEmpty()) "" else if (spelledWords.isEmpty()) text() else written.filter { it !in spelledWords }.joinToString(" ")   // spelled words are not learned
        stop()
        if (sent.isNotEmpty()) { out.cue(TypeCue.DONE); if (original.isNotEmpty()) out.written(original) }
        out.finished(sent)
        onEnded()
    }
}
