package com.faka.invisible.voicekit

/**
 * Pacing atom: every timing number of the voice kit in ONE place (button timing, scan speed, the coach's pauses).
 * Pure, no Android. Change a number here and it changes everywhere.
 */
object Pacing {
    // ---- the button ----
    /** A press this long is a hold. */
    const val HOLD_MS = 500L
    /** The gesture is counted this long after its last press (the gap that joins clicks). */
    const val GAP_MS = 350L
    /** Holding this long always leaves a program. */
    const val LEAVE_MS = 3000L

    // ---- the scan ----
    /** About 4 fast-scroll steps per second while the button is held. */
    const val FAST_MS = 250L
    /** Pause between scanned items, from the end of one item's speech to the start of the next. */
    const val ITEM_GAP_MS = 1600L
    /** One click while scanning: how soon the next option starts. */
    const val SKIP_MS = 60L

    // ---- the Duel coach: the voice runs at rate 0.75, about 110 words a minute = 550 ms a word, plus 400 ms to start. Used when no done-callback comes. ----
    const val SPEAK_BASE_MS = 400L
    const val SPEAK_PER_WORD_MS = 550L
    /** Time to take a sentence in: 600 ms to settle plus 250 ms for each word just heard (a 5-word sentence = 1.85 s). */
    const val DIGEST_BASE_MS = 600L
    const val DIGEST_PER_WORD_MS = 250L
    /** After a sound the player has not heard yet (cue, click, notes, result): at least this much quiet before speaking. */
    const val SOUND_QUIET_MS = 1500L
    /** After the instruction of a step that needs the thumb: time to get ready. */
    const val READY_MS = 800L
    /** Silence without a press: a hint every this long. */
    const val HINT_MS = 10000L
    /** Space between the six rising energy notes. */
    const val NOTE_SPACING_MS = 600L

    // ---- Write: two words in the two ears ----
    /** Slow, clear, a long gap (the ear needs time to take a word in). */
    const val WORD_RATE = 0.75f
    const val PAIR_GAP_MS = 800
    /** The coach voice rate (the Duel talks slowly). */
    const val COACH_RATE = 0.75f

    fun words(text: String) = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
    fun words(p: Phrase) = words(p.text)
    fun speakMs(text: String) = SPEAK_BASE_MS + SPEAK_PER_WORD_MS * words(text)
    fun speakMs(p: Phrase) = speakMs(p.text)
    /** The quiet after a spoken sentence. */
    fun pauseAfter(text: String) = DIGEST_BASE_MS + DIGEST_PER_WORD_MS * words(text)
    fun pauseAfter(p: Phrase) = pauseAfter(p.text)
    /** The quiet after a sound of [soundMs] length. */
    fun quietAfterSound(soundMs: Long) = soundMs + SOUND_QUIET_MS
}
