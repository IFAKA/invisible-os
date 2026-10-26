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

    // ---- waiting (Narrator.waiting) ----
    /** Work faster than this makes no sound at all (under about 1 s people do not feel a wait). */
    const val WAIT_DELAY_MS = 600L
    /** One heartbeat this often while the work goes on: slow enough to stay calm, fast enough that silence means something broke. */
    const val WAIT_BEAT_MS = 1400L
    /** After this long, one spoken "Still working." */
    const val WAIT_SAY_MS = 10000L
    /** The heartbeat stops here even if no one stopped it (a bug must never beat forever). */
    const val WAIT_MAX_MS = 60000L

    // ---- the scan ----
    /** Fast scroll: the least time one word gets before the next one starts. A word is never cut off by the next. */
    const val FAST_MS = 650L
    /** Time for one fast-scroll word: 650 ms, more for a long word (60 ms per letter above 3). */
    fun fastStepMs(word: String) = FAST_MS + 60L * maxOf(0, word.length - 3)
    /** Pause between scanned items, from the end of one item's speech to the start of the next. */
    const val ITEM_GAP_MS = 1600L
    /** Adaptive gap (logickit/ReactionGap): keep this many reaction samples, need this many before adapting, add this margin to the p90, never go under the floor. */
    const val REACTION_KEEP = 40
    const val REACTION_MIN_SAMPLES = 8
    const val REACTION_MARGIN_MS = 300L
    const val REACTION_FLOOR_MS = 700L
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
