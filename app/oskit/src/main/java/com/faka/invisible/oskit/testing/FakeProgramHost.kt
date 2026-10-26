package com.faka.invisible.oskit.testing

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Handler
import com.faka.invisible.oskit.Program
import com.faka.invisible.oskit.ProgramHost
import com.faka.invisible.voicekit.Phrase

/**
 * For unit tests of a program (plain JVM tests, no phone). A host with a virtual clock:
 * - [spoken] lists every line the program said, [logs] its log lines, [endedCount] how often it called `ended()`.
 * - [click] and [hold] press the program's button; [advance] moves the clock (timers of the program run when due).
 * Needs `testOptions.unitTests.isReturnDefaultValues = true` in the module's build file (the starter has it).
 */
class FakeProgramHost : ProgramHost {
    val spoken = ArrayList<String>()
    val logs = ArrayList<String>()
    var endedCount = 0
    var speechStopped = 0
    var musicPaused = false
    /** The virtual clock, in ms. */
    var now = 0L
        private set

    private class Task(val at: Long, val r: Runnable)
    private val tasks = ArrayList<Task>()

    /** Not used by the fake: programs should call [later] and [cancel]. (A stub Handler cannot run timers on the JVM.) */
    override val handler: Handler = Handler()
    override fun later(r: Runnable, delayMs: Long) { tasks.add(Task(now + delayMs, r)) }
    override fun cancel(r: Runnable) { tasks.removeAll { it.r === r } }
    override val context: Context = ContextWrapper(null)
    override val prefs: SharedPreferences = MemoryPrefs()

    override fun waiting(): () -> Unit = {}
    override fun speak(text: Phrase, slow: Boolean) { spoken.add(text.text) }
    override fun stopSpeech() { speechStopped++ }
    override fun pauseMusic() { musicPaused = true }
    override fun resumeMusic() { musicPaused = false }
    override fun log(line: String) { logs.add(line) }
    override fun outputDevice() = "test"
    override fun ended() { endedCount++ }

    /** Moves the clock forward and runs the timers that are due, in order. */
    fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val next = tasks.filter { it.at <= end }.minByOrNull { it.at } ?: break
            tasks.remove(next); now = maxOf(now, next.at); next.r.run()
        }
        now = end
    }

    /** One click: down, 80 ms, up. Does not wait for the gap that ends a click burst: call [advance] for that. */
    fun click(p: Program) { p.down(now); advance(80); p.up(now) }

    /** A press held for [ms]. */
    fun hold(p: Program, ms: Long) { p.down(now); advance(ms); p.up(now) }

    /** Takes the lines spoken so far and clears the list. */
    fun takeSpoken(): List<String> { val l = spoken.toList(); spoken.clear(); return l }

    /** A small in-memory SharedPreferences. */
    class MemoryPrefs : SharedPreferences {
        private val m = HashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = HashMap(m)
        override fun getString(k: String, d: String?) = m[k] as? String ?: d
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(k: String, d: MutableSet<String>?) = m[k] as? MutableSet<String> ?: d
        override fun getInt(k: String, d: Int) = m[k] as? Int ?: d
        override fun getLong(k: String, d: Long) = m[k] as? Long ?: d
        override fun getFloat(k: String, d: Float) = m[k] as? Float ?: d
        override fun getBoolean(k: String, d: Boolean) = m[k] as? Boolean ?: d
        override fun contains(k: String) = m.containsKey(k)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>(); private val removed = HashSet<String>(); private var wipe = false
            override fun putString(k: String, v: String?) = apply { pending[k] = v }
            override fun putStringSet(k: String, v: MutableSet<String>?) = apply { pending[k] = v }
            override fun putInt(k: String, v: Int) = apply { pending[k] = v }
            override fun putLong(k: String, v: Long) = apply { pending[k] = v }
            override fun putFloat(k: String, v: Float) = apply { pending[k] = v }
            override fun putBoolean(k: String, v: Boolean) = apply { pending[k] = v }
            override fun remove(k: String) = apply { removed.add(k) }
            override fun clear() = apply { wipe = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() { if (wipe) m.clear(); removed.forEach { m.remove(it) }; m.putAll(pending) }
        }
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }
}
