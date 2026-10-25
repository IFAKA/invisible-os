package com.faka.invisible.voicekit

/** Keyed one-shot timers (injected so the kit and the screens are testable). Setting a key replaces the old timer with that key. */
interface Timers {
    fun set(key: String, delayMs: Long, task: () -> Unit)
    fun clear(key: String)
}
