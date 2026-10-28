package com.faka.invisible.oskit.testing

import com.faka.invisible.voicekit.LoopAudio
import com.faka.invisible.voicekit.Timers

/**
 * Fake time and a fake sound output for tests of the [com.faka.invisible.voicekit.LetterLoop] (and the programs on it):
 * timers that fire in order when [advance] moves the clock, a mixer that only records what was scheduled, and a
 * output delay of [latencyMs] between a frame being scheduled and being heard. A clip is as long as [lengthMs] says for its text.
 */
class FakeLoopAudio(var latencyMs: Double = 0.0, var lengthMs: (String) -> Int = { 200 }) : LoopAudio, Timers {
    override val sampleRate = 24000
    var now = 0L
    private val tasks = HashMap<String, Pair<Long, () -> Unit>>()
    private val textOf = java.util.IdentityHashMap<FloatArray, String>()
    private val made = HashMap<String, FloatArray>()
    /** What was scheduled: text, frame, pan. */
    class Played(val text: String, val frame: Long, val pan: Double)
    val played = ArrayList<Played>()
    var confirms = 0
    var cleared = 0
    /** Texts the voice cannot make. */
    val missing = HashSet<String>()
    var prepared = 0

    override fun set(key: String, delayMs: Long, task: () -> Unit) { tasks[key] = now + delayMs to task }
    override fun clear(key: String) { tasks.remove(key) }

    fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val next = tasks.entries.filter { it.value.first <= end }.minByOrNull { it.value.first } ?: break
            tasks.remove(next.key); now = maxOf(now, next.value.first); next.value.second()
        }
        now = end
    }

    /** The speed asked for each text in the last calls (1.0 = a guess read alone). */
    val speeds = HashMap<String, Double>()
    override fun prepare(texts: List<String>, speed: Double, then: (Map<String, FloatArray>) -> Unit) {
        prepared++; texts.forEach { speeds[it] = speed }
        then(texts.filter { it !in missing }.associateWith { t ->
            made.getOrPut(t) { FloatArray(lengthMs(t) * sampleRate / 1000).also { textOf[it] = t } }
        })
    }
    override fun nowFrame() = now * sampleRate / 1000
    override fun nowMs() = now
    override fun wallMsOf(frame: Long) = frame * 1000.0 / sampleRate + latencyMs
    override fun play(clip: FloatArray, atFrame: Long, pan: Double) { played.add(Played(textOf[clip] ?: "?", atFrame, pan)) }
    override fun clear() { cleared++; played.clear() }   // what is not played yet is dropped
    override fun confirm() { confirms++ }

    /** When the item of [text] in cycle [cycle] was heard (ms on the clock of the button). */
    fun heardAt(text: String, cycle: Int = 0): Double = played.filter { it.text == text }.sortedBy { it.frame }[cycle].let { wallMsOf(it.frame) }
}
