package com.faka.invisible.clicktest

import com.faka.invisible.voicekit.Synth
import com.faka.invisible.voicekit.Synth.tone

/** The sounds of the Click test (pure maths). One sound always means one thing: the high bell is the one to click on. */
object ClickTestSynth {
    const val SR = Synth.SR
    /** The sound to click on. */
    val target by lazy { tone(1320.0, 140, 70.0, 0.5) }
    /** The other sounds, lower; the beat is steady, only the pitch changes. */
    val others by lazy { listOf(330.0, 392.0, 440.0, 523.0).map { tone(it, 110, 55.0, 0.2) } }
    /** A hit: a clear short note. */
    val hit by lazy { tone(784.0, 200, 80.0, 0.4) }
    /** A miss: soft and low. */
    val miss by lazy { tone(165.0, 160, 70.0, 0.1) }
    val done by lazy { Synth.then(Synth.then(tone(523.0, 160, 70.0), tone(659.0, 160, 70.0), 100), tone(784.0, 320, 140.0), 100) }

    fun warm() { target; others; hit; miss; done }
}
