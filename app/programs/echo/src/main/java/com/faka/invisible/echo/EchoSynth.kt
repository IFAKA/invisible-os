package com.faka.invisible.echo

import com.faka.invisible.voicekit.Synth
import com.faka.invisible.voicekit.Synth.then
import com.faka.invisible.voicekit.Synth.tone

/** The sounds of ECHO, built once (pure maths, no Android). One sound always means one thing. */
object EchoSynth {
    const val SR = Synth.SR

    /** The steady pulse while the robot plays: low. */
    val tick by lazy { tone(1300.0, 40, 9.0) }
    val tickAccent by lazy { tone(1700.0, 50, 12.0) }
    /** The pulse while it is your turn: higher and brighter, so you hear the turn change. */
    val tock by lazy { tone(2200.0, 40, 9.0, 0.3) }
    val tockAccent by lazy { tone(2800.0, 50, 12.0, 0.3) }
    /** The robot's hit: a wood block. */
    val robot by lazy { tone(430.0, 160, 55.0, 0.6) }
    /** Your hits: spot on = bell, a little off = soft note, nothing there = low buzz. */
    val perfect by lazy { tone(988.0, 260, 90.0, 0.5) }
    val good by lazy { tone(587.0, 200, 70.0) }
    val extra by lazy { tone(110.0, 200, 80.0, 0.9) }
    /** A bar cleared (rises), an all-perfect bar (rises higher), a bar failed (falls), the end. */
    val cleared by lazy { then(tone(659.0, 180, 80.0), tone(880.0, 260, 110.0), 130) }
    val flawless by lazy { then(then(tone(659.0, 160, 70.0), tone(880.0, 160, 70.0), 110), tone(1319.0, 320, 140.0, 0.4), 110) }
    val failed by lazy { then(tone(247.0, 220, 100.0), tone(165.0, 380, 160.0), 150) }
    val over by lazy { then(then(tone(330.0, 200, 90.0), tone(247.0, 200, 90.0), 160), tone(165.0, 600, 260.0), 160) }

    fun warm() { tick; tickAccent; tock; tockAccent; robot; perfect; good; extra; cleared; flawless; failed; over }
}
