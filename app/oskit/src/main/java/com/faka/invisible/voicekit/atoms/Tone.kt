package com.faka.invisible.voicekit

/** One earcon per screen, plus feedback tones. The sound of each is defined in Earcons.kt. */
enum class Tone { EXIT, HUB, MESSAGE, TIMER, MORE, VOLUME, SENT, CANCEL, UNDO, ARRIVE, ARRIVE_FAV, LEAVE, UNKNOWN, TICK, DING, TAP, PICK, AGAIN, STEP_BACK, BACK, ALARM, RESTART, WAIT, LVL1, LVL2, LVL3, LVL4, LVL5, CLAUDE }

/** The short name the screen shows for a played tone (the screen earcons share one name, the exit sound is "close"). */
val Tone.cueName: String get() = when (this) {
    Tone.EXIT -> "close"
    Tone.HUB, Tone.MESSAGE, Tone.TIMER, Tone.MORE, Tone.VOLUME, Tone.CLAUDE, Tone.UNKNOWN -> "screen tone"
    else -> name.lowercase().replace('_', ' ')
}
