package com.faka.invisible.voicekit

/** What a [Scanner] needs from its owner. The owner decides how an item is spoken and what leaving means. */
interface ScanOut {
    /** Item [index] starts: speak [label] ([spearcon] = as the sped-up word). */
    fun say(index: Int, label: Phrase, spearcon: Boolean)
    /** Fast scroll: speak only this first word. */
    fun sayFast(word: Phrase)
    fun tone(t: Tone)
    /** The silent loops are used up: leave. */
    fun leave()
    /** A list with a title starts over: speak [title]; the owner calls [Scanner.itemDone] when it is spoken, and the first item follows. */
    fun sayTitle(title: Phrase) {}
}

/**
 * How a [Scanner] paces itself. [ceremony] = the list is a menu of the OS: a tick before each next item, a falling sound at the
 * end, a rising sound before it starts over, and one more silent pause before leaving. Without it the next step comes after one pause.
 */
class ScanCadence(
    val pauseMs: () -> Long,
    val loops: () -> Int,
    val tickLeadMs: Long = 200,
    val restartLeadMs: Long = 450,
    val ceremony: Boolean = true,
)

/**
 * ORGANISM. The one spoken list of the OS. It holds the [ScanList] and the timers that read it: each item, a pause, the
 * next item, the end of the list, the loops without a click (then leave), and the click-then-hold fast scroll.
 * The owner says when an item's speech is over ([itemDone]) and what a click does; this class never reads the button.
 */
class Scanner<T>(
    private val timers: Timers,
    private val label: (T) -> Phrase,
    private val out: ScanOut,
    private val cadence: ScanCadence,
    private val spearcon: (T) -> Boolean = { false },
    private val scanKey: String = "scan",
    private val tickKey: String = "tick",
    private val fastKey: String = "fast",
) {
    var list = ScanList<T>(emptyList())
        private set
    /** True while a click-then-hold is down. */
    var fasting = false
        private set

    val isEmpty get() = list.isEmpty

    /** The title of the list (null = none): read again each time the list starts over after its last item. */
    private var title: Phrase? = null
    private var readingTitle = false

    fun load(items: List<T>, title: Phrase? = null) { list = ScanList(items); this.title = title; readingTitle = false }

    /** The loop restart: the title first if the list has one, then the first item. */
    private fun startOver() {
        val t = title
        if (t == null) { speakItem(0); return }
        readingTitle = true
        ScreenMirror.show(list.items.map { label(it).text }, 0)
        out.sayTitle(t)
    }

    /** Item [i] starts now. */
    fun speakItem(i: Int) {
        readingTitle = false
        list.moveTo(i)
        val o = list.items[i]
        ScreenMirror.show(list.items.map { label(it).text }, i)
        out.say(i, label(o), spearcon(o))
    }

    /** Item [i] starts after [delayMs] (a newer call replaces it). */
    fun speakItemIn(delayMs: Long, i: Int) { timers.set(scanKey, delayMs) { speakItem(i) } }

    /** The speech of the current item is over: wait, then the next item, or the end of the list. */
    fun itemDone() {
        if (readingTitle) { readingTitle = false; speakItemIn(cadence.pauseMs(), 0); return }
        val pause = cadence.pauseMs()
        if (!list.isLast) {
            if (cadence.ceremony) timers.set(tickKey, maxOf(0L, pause - cadence.tickLeadMs)) { out.tone(Tone.TICK) }
            speakItemIn(pause, list.index + 1)
        } else timers.set(scanKey, pause) {
            if (cadence.ceremony) out.tone(Tone.DING)   // falls: the list ended
            if (list.endLoop() >= cadence.loops()) {
                if (cadence.ceremony) timers.set(scanKey, pause) { out.leave() } else out.leave()
            } else if (cadence.ceremony) {
                timers.set(tickKey, maxOf(0L, pause - cadence.restartLeadMs)) { out.tone(Tone.RESTART) }   // rises: it starts over
                timers.set(scanKey, pause) { startOver() }
            } else startOver()
        }
    }

    /** A click: the item after the current one, after [delayMs]; the silent loops count again from zero. */
    fun nextIn(delayMs: Long) { list.resetLoops(); speakItemIn(delayMs, list.nextIndex()) }

    /** Two clicks: the item before the current one, after [delayMs]; before the first, the last. The silent loops count again from zero. */
    fun prevIn(delayMs: Long) { list.resetLoops(); speakItemIn(delayMs, list.prevIndex()) }

    /** From the first item, after [delayMs]; the silent loops count again from zero. */
    fun restartIn(delayMs: Long) { list.resetLoops(); speakItemIn(delayMs, 0) }

    /** The current item again, after [delayMs] (where fast scroll stopped). */
    fun resumeIn(delayMs: Long) { list.resetLoops(); speakItemIn(delayMs, list.index) }

    fun stop() { readingTitle = false; timers.clear(scanKey); timers.clear(tickKey); timers.clear(fastKey) }

    // ---- fast scroll: click, then hold ----
    fun startFast() { fasting = true; fastTick() }

    /** One fast step: a tick (the rising sound at the wrap), then only the first word of the option. */
    private fun fastTick() {
        if (!fasting) return
        val s = list.fastStep { label(it).text }
        out.tone(if (s.wrapped) Tone.RESTART else Tone.TICK)
        out.sayFast(Phrase.content(s.text, label(list.current).lang))
        timers.set(fastKey, Pacing.fastStepMs(s.text)) { fastTick() }
    }

    /** Released. False if no fast scroll was on. The owner stops its speech and calls [resumeIn]. */
    fun endFast(): Boolean { if (!fasting) return false; fasting = false; return true }
}
