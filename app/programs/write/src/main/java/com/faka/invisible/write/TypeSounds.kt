package com.faka.invisible.write

import com.faka.invisible.voicekit.Earcons
import com.faka.invisible.voicekit.Earcons.Timbre

/** Short sounds of the Write program. */
enum class TypeCue { PICK_LEFT, PICK_RIGHT, NEXT, LETTERS, WORDS, DELETE, DONE, ARMED, MENU, LEAVE1, LEAVE2 }

/** Sounds of the Write program (its own: the OS tones are not used inside a program). Same material as the OS earcons; pitch says which side. */
object TypeSounds {
    private fun seq(t: Timbre, stepMs: Int, ms: Int, vararg hz: Double, reverb: Boolean, db: Double) = Earcons.sequence(t, stepMs, ms, *hz, reverb = reverb, peakDb = db)

    fun pcm(c: TypeCue): ShortArray = when (c) {
        TypeCue.PICK_LEFT -> seq(Timbre.MALLET, 0, 120, 880.0, reverb = true, db = -17.0)
        TypeCue.PICK_RIGHT -> seq(Timbre.MALLET, 0, 120, 660.0, reverb = true, db = -17.0)
        TypeCue.NEXT -> seq(Timbre.WOOD, 0, 25, 440.0, reverb = false, db = -24.0)
        TypeCue.LETTERS -> seq(Timbre.MALLET, 70, 130, 330.0, 494.0, reverb = true, db = -20.0)   // up
        TypeCue.WORDS -> seq(Timbre.MALLET, 70, 130, 494.0, 330.0, reverb = true, db = -20.0)     // down
        TypeCue.DELETE -> seq(Timbre.LOW, 70, 160, 392.0, 262.0, reverb = true, db = -17.0)
        TypeCue.DONE -> seq(Timbre.MALLET, 85, 400, 523.0, 659.0, 784.0, reverb = true, db = -14.0)
        TypeCue.ARMED -> seq(Timbre.MALLET, 0, 100, 330.0, reverb = false, db = -18.0)   // 0.5 s: the press is now a hold; then 1 s, 2 s rising; 3 s = the close sound
        TypeCue.LEAVE1 -> seq(Timbre.MALLET, 0, 100, 392.0, reverb = false, db = -18.0)
        TypeCue.LEAVE2 -> seq(Timbre.MALLET, 0, 100, 494.0, reverb = false, db = -18.0)
        TypeCue.MENU -> seq(Timbre.MALLET, 60, 110, 392.0, 392.0, reverb = true, db = -20.0)
    }
}
