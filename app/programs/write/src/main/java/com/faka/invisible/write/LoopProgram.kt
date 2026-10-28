package com.faka.invisible.write

import com.faka.invisible.logickit.ClickModel
import com.faka.invisible.logickit.ClickProfile
import com.faka.invisible.logickit.LoopLevel
import com.faka.invisible.oskit.Trace
import com.faka.invisible.voicekit.*

/** What the shell needs of a keyboard: the raw button, a tap on the screen, a stop. Both [TypeProgram] (numbers) and [LoopProgram] (text) are one. */
interface Typer : RawButton {
    fun tapRow(row: Int)
    fun stop()
    fun speechDone() {}
}

/** Where the learned click density of this person is kept between runs. */
interface LoopStore {
    fun load(): List<Double>
    fun save(offsets: List<Double>)
    /** What the Click test measured (prefs "click_profile"), if the person did it. */
    fun profile(): ClickProfile? = null
    /** The beginner pace level of this person ([com.faka.invisible.logickit.LoopLevel]), shared by Write and the home letters. */
    fun level(): Int = 0
    fun saveLevel(level: Int) {}
}

/** Where the loop's click model starts: Write's own learned clicks win (real typing); else the Click test; else Nomon's values. */
object LoopSeed {
    const val MIN_LEARNED = 8
    fun choose(learned: List<Double>, profile: ClickProfile?): Pair<String, ClickModel.Params> = when {
        learned.size >= MIN_LEARNED -> "write" to ClickModel.Params()
        profile != null && profile.count >= ClickProfile.MIN_COUNT -> "profile" to profile.params()
        else -> "default" to ClickModel.Params()
    }
}

/** Click offsets in seconds as a small JSON list, e.g. "[0.12,0.08]". */
object LoopDensity {
    fun encode(x: List<Double>) = x.joinToString(",", "[", "]") { "%.3f".format(java.util.Locale.ROOT, it) }
    fun decode(s: String?): List<Double> = s?.trim()?.removePrefix("[")?.removeSuffix("]")?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() } ?: emptyList()
}

/**
 * Write for text: one letter loop per step ([LetterLoop], layout of Ticker). The loop offers, in the order of their chance, up to 2 whole
 * texts (answers from the big model, only at the start), up to 3 words for the letters so far, then every letter, then Space, Undo and Send.
 * A pick of a word adds the word and a space; a letter extends the word being spelled and a new loop starts; Space ends the word as spelled; Undo takes back
 * the last pick; Send reads the text back and waits for a click (a translation is read back instead). No menu: a click is the only thing the button does.
 * After a few cycles without a click the text so far is read. Hold 3 s leaves (the OS). It owns no menu; time and sound are injected.
 */
class LoopProgram(
    private val lm: Ranker,
    private val out: TypeOut,
    private val audio: LoopAudio,
    private val timers: Timers,
    private val frame: Frame = Frame(),
    private val store: LoopStore,
    private val mixer: Mixer = Mixer(),
    private val onEnded: () -> Unit,
) : Typer {
    private enum class Phase { LOOP, SPEAKING, CONFIRM, WAITING }
    private class Snap(val written: List<String>, val prefix: String, val endsSentence: Boolean)

    private val written = ArrayList<String>()
    private val spelledWords = HashSet<String>()   // words made of spelled letters: not learned
    private val history = ArrayList<Snap>()
    private var prefix = ""
    private var endsSentence = false   // the last pick was a whole text: Send is likely
    private var phrases: List<String> = emptyList()
    private var phraseGen = 0
    private var guessing = false
    private var bigrams: LetterBigrams? = null
    private var phase = Phase.SPEAKING
    private var downAt = -1L
    private var pending: String? = null   // the text (or its translation), read back, waiting for a click to send it
    private var roundClicks = 0
    private var after: (() -> Unit)? = null
    private var touched = false
    override val exitOnClickHold = false   // Write is left by holding 3 s
    override var active = false; private set

    private val learned = store.load()
    private val seeded = LoopSeed.choose(learned, store.profile())
    private val level = LoopLevel(store.level()) { store.saveLevel(it) }
    private val loop = LetterLoop<String>(audio, timers, pace = level.pace, seed = learned, params = seeded.second,
        log = { event, fields -> out.log(event, *fields.toTypedArray()) },
        onChosen = { chosen(it) }, onIdle = { idle() })

    fun start() {
        if (frame.kind == FieldKind.PASSWORD) { out.say(TypeVoice.PASSWORD); onEnded(); return }   // never spoken, never saved
        active = true
        out.log("seed", "from=" + seeded.first)
        Trace.ev("loop", level.describe())
        askPhrases()
        speakThen(listOf(TypeVoice.LOOP_START, TypeVoice.LOOP_START2)) { round() }
    }

    override fun stop() {
        if (!active) return
        active = false; pending = null; loop.stop(); timers.clear(AFTER); timers.clear(REFRESH)
    }

    // ---- what is said, and when the loop goes on ----
    /** Say [lines] (queued), then run [then] when the voice is done (or after the time they take, if the OS does not tell). */
    private fun speakThen(lines: List<Phrase>, then: () -> Unit) {
        phase = Phase.SPEAKING; loop.stop(); after = then
        lines.forEach { out.say(it) }
        waitThen(lines.sumOf { Pacing.speakMs(it) + 300 })
    }

    private fun waitThen(ms: Long) { timers.set(AFTER, ms) { goOn() } }

    private fun goOn() { timers.clear(AFTER); val a = after; after = null; if (active && a != null) a() }

    override fun speechDone() { if (active && phase == Phase.SPEAKING && after != null) goOn() }

    // ---- one round of the loop ----
    private fun words(): List<Pair<String, Double>> = lm.ranked(written.takeLast(8))

    private fun round() {
        if (!active) return
        timers.clear(REFRESH)
        phase = Phase.LOOP; roundClicks = 0
        val mixed = mixer.mix(Request(frame, written.toList(), prefix), words())   // what the sources said so far; a later answer waits for the next round
        val shown = mixed.phrases.take(if (written.isEmpty() && prefix.isEmpty() && frame.kind != FieldKind.TEXT) 3 else 2)
        val offered = shown.map { it.first }; phrases = offered
        val all = mixed.words
        val items = LoopItems.build(all, written, prefix, lm.skipShortFirst, offered, history.isNotEmpty(), endsSentence, { bigrams ?: LetterBigrams(all.map { it.first }).also { bigrams = it } }, shown.map { LoopItems.phraseChance(it.second) })
        loop.start(items)
        showScreen(items)
        out.log("round", prefix, written.size, items.size, items.take(5).joinToString("/") { it.key })
    }

    private fun showScreen(items: List<LoopItem<String>>) {
        val rows = ArrayList<String>()
        rows.add(text().ifEmpty { "Nothing written yet" })
        if (prefix.isNotEmpty()) rows.add("Spelling: $prefix")
        val mark = rows.size
        rows.add("Hearing: " + items.take(6).joinToString(", ") { it.say.text } + "...")
        out.screen(rows, mark)
    }

    /** What Send sends: the written words plus the letters spelled so far. */
    private fun willSend(): String = (written + listOfNotNull(prefix.ifEmpty { null })).joinToString(" ").replaceFirstChar { it.uppercase() }
    fun text(): String = written.joinToString(" ").replaceFirstChar { it.uppercase() }

    private fun push() { history.add(Snap(written.toList(), prefix, endsSentence)) }

    private fun chosen(c: LoopChoice<String>) {
        if (!active) return
        out.log("choice", c.key, c.rank, c.cycles, c.ms, c.clicks)
        store.save(loop.offsets())
        val key = c.key
        if (level.chose(key == LoopItems.UNDO)) { loop.pace = level.pace; Trace.ev("loop", level.describe()) }
        when {
            key.startsWith("l:") -> { push(); prefix += key.substring(2); endsSentence = false; askSources(); say(TypeVoice.spelled(prefix.takeLast(5))) }
            key.startsWith("w:") -> {
                val w = key.substring(2); push(); traceSource(w, true)
                lm.picked(written.takeLast(8), w); written.add(w); prefix = ""; endsSentence = false
                askPhrases(); say(Phrase.content(w))
            }
            key.startsWith("p:") -> {
                push(); val t = phrases[key.substring(2).toInt()]; traceSource(t, false)
                written.clear(); written.addAll(t.split(' ').filter { it.isNotEmpty() }); prefix = ""; endsSentence = true; askSources()
                out.cue(TypeCue.PICK_LEFT); readThenRound()
            }
            key == LoopItems.SPACE -> { push(); val w = prefix; spelledWords.add(w); written.add(w); prefix = ""; endsSentence = false; askPhrases(); say(Phrase.content(w)) }
            key == LoopItems.UNDO -> undo()
            key == LoopItems.SEND -> sendNow()
        }
    }

    /** `write source pick=<source> rank=N`: which source had the picked word or text, and where. */
    private fun traceSource(text: String, isWord: Boolean) { val b = mixer.picked(text, isWord); out.log("source", "pick=${b?.first ?: "none"}", "rank=${b?.second ?: -1}") }

    private fun say(p: Phrase) { speakThen(listOf(p)) { round() } }

    private fun undo() {
        val s = history.removeAt(history.size - 1)
        written.clear(); written.addAll(s.written); prefix = s.prefix; endsSentence = s.endsSentence
        out.cue(TypeCue.DELETE); askPhrases()
        val last = if (prefix.isNotEmpty()) TypeVoice.spelled(prefix.takeLast(5)) else written.lastOrNull()?.let { Phrase.content(it) }
        if (last != null) say(last) else round()
    }

    /** The whole text is read back; then the loop goes on. */
    private fun readThenRound() {
        val t = willSend()
        phase = Phase.SPEAKING; loop.stop(); after = { round() }
        if (t.isEmpty()) { out.say(TypeVoice.LOOP_HINT); waitThen(Pacing.speakMs(TypeVoice.LOOP_HINT) + 300); return }
        out.readBack(t); waitThen(Pacing.speakMs(t) + 600)
    }

    /** No click for some cycles: say what is written so far (or how to click), then go on. */
    private fun idle() { if (active) { out.log("idle", written.size, prefix); readThenRound() } }

    // ---- send ----
    private fun sendNow() {
        if (prefix.isNotEmpty()) { spelledWords.add(prefix); written.add(prefix); prefix = "" }   // letters spelled but not yet a word: keep them
        val t = text()
        out.log("end", "send", written.size, t.length)
        if (t.isEmpty()) { finish(""); return }
        phase = Phase.WAITING; loop.stop()
        out.translate(t) { r ->
            if (!active || phase != Phase.WAITING) return@translate
            when (r) {
                is Translation.Same -> confirm(t)
                is Translation.Done -> confirm(r.text)
                is Translation.Failed -> speakThen(listOf(r.say)) { round() }   // nothing is sent; the text is kept
            }
        }
    }

    /** The text to send is read back; a click sends it, a hold goes back to writing. */
    private fun confirm(t: String) {
        pending = t; phase = Phase.CONFIRM
        out.screen(listOf(t, "Click to send, hold to edit"), 0)
        out.readBack(t); out.say(TypeVoice.CONFIRM)
    }

    private fun finish(sent: String) {
        val original = if (sent.isEmpty()) "" else written.filter { it !in spelledWords }.joinToString(" ")   // spelled words are not learned
        stop()
        if (sent.isNotEmpty()) { out.cue(TypeCue.DONE); if (original.isNotEmpty()) out.written(original); out.spelledWords(spelledWords.toList()) }
        out.finished(sent)
        onEnded()
    }

    // ---- phrases from the big model, and what the next words may be ----
    private fun askPhrases() {
        if (written.isEmpty()) mixer.set("history", "", Guess.ranked(out.suggestions()))
        val g = ++phraseGen
        if (written.isEmpty() && frame.around.isNotEmpty()) out.phrases(text()) { r ->
            if (!active || g != phraseGen) return@phrases
            mixer.set("replies", "", Guess.ranked(r))
            if (r.isNotEmpty() && phase == Phase.LOOP && !touched) round()   // answering a chat: the replies that fit it come first, as soon as they are ready, unless the player is already busy
        }
        askSources()
        guess()
    }

    /** Ask the sources about what is typed now. An answer for a text that is no longer the one typed is dropped; one that fits waits for the next cycle of the loop (a cycle that is heard never changes). */
    private fun askSources() {
        val req = Request(frame, written.toList(), prefix)
        out.sources(req) { id, g ->
            if (!active || req.key != Request(frame, written.toList(), prefix).key || g.isEmpty()) return@sources
            mixer.set(id, req.key, g)
            if (phase == Phase.LOOP && roundClicks == 0 && loop.running) timers.set(REFRESH, loop.msToNextCycle()) { if (active && phase == Phase.LOOP && roundClicks == 0) round() }
        }
    }

    /** How long an AI answer may take: until the next cycle of the loop plus one more (an answer waits for a cycle anyway), 1 to 3 s. */
    fun aiBudgetMs(): Long = (loop.msToNextCycle() + loop.cycleMs.toLong()).coerceIn(1000L, 3000L)

    private fun guess() {
        if (guessing) return
        guessing = true
        val asked = written.joinToString(" ")
        out.nextWords(asked) { w ->
            guessing = false
            if (!active) return@nextWords
            if (w.isNotEmpty()) lm.hint(w)
            if (written.joinToString(" ") != asked) guess() else if (phase == Phase.LOOP && roundClicks == 0 && w.isNotEmpty() && prefix.isEmpty() && !touched) round()
        }
    }

    // ---- the raw button: a click is the time the button went down, counted when it comes up ----
    override fun down(time: Long) {
        if (!active) return
        downAt = time
        out.silence()   // a press cuts the sentence being spoken
        if (phase == Phase.SPEAKING) { touched = true; goOnNow() }
    }

    private fun goOnNow() { if (after != null) goOn() else if (phase == Phase.SPEAKING) round() }

    override fun up(time: Long) {
        if (!active || downAt < 0) return
        val at = downAt; downAt = -1
        val held = time - at >= Pacing.HOLD_MS
        out.log("press", if (held) "hold" else "click", time - at, at)
        if (time - at >= Pacing.LEAVE_MS) { finish(""); return }   // hold 3 s leaves, in every phase
        if (held) { hold(); return }
        click(at)
    }

    override fun assistHold() { if (active && downAt < 0) hold() }

    private fun hold() { if (phase == Phase.CONFIRM) { pending = null; out.cue(TypeCue.NEXT); round() } }

    private fun click(at: Long) {
        touched = true
        when (phase) {
            Phase.LOOP -> { roundClicks++; loop.click(at) }
            Phase.CONFIRM -> { val t = pending; pending = null; if (t != null) finish(t) }
            else -> {}
        }
    }

    override fun tapRow(row: Int) {}

    private companion object { const val AFTER = "t:after"; const val REFRESH = "t:refresh" }
}
