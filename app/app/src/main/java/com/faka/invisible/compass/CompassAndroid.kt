package com.faka.invisible.compass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import com.faka.invisible.voicekit.CompassVoice
import com.faka.invisible.voicekit.Pacing
import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.RawButton
import com.faka.invisible.voicekit.TellAudio
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** What the compass needs from the app (speech, the user's music). */
interface CompassHost {
    fun speak(text: Phrase)
    fun stopSpeech()
    fun pauseMusic()
    fun resumeMusic()
}

/**
 * Compass program. One click: say the heading. Two clicks: find north (a guide tone: it rises as you turn toward north,
 * sits on the side you must turn to, and holds one high note when you face north). Hold 3 s: leave. Only the motion sensors; no microphone.
 * The phone flat or upright (in a pocket) is detected from gravity; upright, the back of the phone is the direction.
 */
class CompassAndroid(private val context: Context, private val handler: Handler, private val host: CompassHost, private val onEnded: () -> Unit) : RawButton {
    private val sr = 44100
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    @Volatile private var running = false
    @Volatile private var heading = 0.0
    @Volatile private var haveHeading = false
    @Volatile private var finding = false
    @Volatile private var unsure = false
    private var phase = 0.0
    private var tick = 0
    private var clicks = 0
    private var downAt = 0L
    private val leave = Runnable { shutdown() }
    private val resolve = Runnable { val n = clicks; clicks = 0; if (running) onClicks(n) }
    private val audio = TellAudio(sr, sr * 5 / 1000, { buf, n -> render(buf, n) }, { running }, {})

    override val active get() = running

    private val listener = object : SensorEventListener {
        private val rot = FloatArray(9); private val mapped = FloatArray(9); private val ori = FloatArray(3)
        override fun onSensorChanged(e: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rot, e.values)
            val flat = abs(rot[8]) > 0.7   // the phone's Z axis points up or down: it lies flat
            if (flat) System.arraycopy(rot, 0, mapped, 0, 9) else SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, mapped)
            SensorManager.getOrientation(mapped, ori)
            val now = CompassCore.norm(Math.toDegrees(ori[0].toDouble()))
            heading = if (haveHeading) CompassCore.smooth(heading, now) else now
            haveHeading = true
        }
        override fun onAccuracyChanged(s: Sensor?, accuracy: Int) { unsure = accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW }
    }

    fun start() {
        if (running) return
        val s = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (s == null) { host.speak(CompassVoice.NO_SENSOR); onEnded(); return }
        running = true
        host.pauseMusic()
        sensors.registerListener(listener, s, SensorManager.SENSOR_DELAY_GAME, handler)
        if (audio.open()) audio.start()
        host.speak(CompassVoice.INTRO)
        host.speak(CompassVoice.LEAVE)
    }

    fun stop() = shutdown()

    override fun down(time: Long) { downAt = time; handler.removeCallbacks(resolve); handler.postDelayed(leave, LEAVE_MS) }

    override fun up(time: Long) {
        handler.removeCallbacks(leave)
        if (time - downAt < LEAVE_MS) { clicks++; handler.postDelayed(resolve, Pacing.GAP_MS) }
    }

    private fun onClicks(n: Int) {
        host.stopSpeech()
        if (n >= 2) {
            finding = !finding
            host.speak(if (finding) CompassVoice.FINDING else CompassVoice.STOPPED)
        } else if (!haveHeading) host.speak(CompassVoice.WAIT)
        else { host.speak(CompassVoice.heading(CompassCore.name(heading), heading.roundToInt() % 360)); if (unsure) host.speak(CompassVoice.UNSURE) }
    }

    /** Audio thread. Silent unless finding north; then a soft tone, pulsing while you turn and steady when you face north. */
    private fun render(buf: FloatArray, n: Int) {
        if (!finding || !haveHeading) { java.util.Arrays.fill(buf, 0, n * 2, 0f); return }
        val turn = CompassCore.turn(0.0, heading)
        val hz = CompassCore.pitch(turn)
        val a = (CompassCore.pan(turn) + 1) * PI / 4
        val l = (cos(a) * 1.414 * GAIN).toFloat(); val r = (sin(a) * 1.414 * GAIN).toFloat()
        val steady = CompassCore.locked(turn)
        for (i in 0 until n) {
            phase += 2 * PI * hz / sr; if (phase > 2 * PI) phase -= 2 * PI
            val gate = if (steady || (tick++ / (sr / 10)) % 2 == 0) 1f else 0f   // 5 pulses a second
            val v = sin(phase).toFloat() * gate
            buf[2 * i] = v * l; buf[2 * i + 1] = v * r
        }
    }

    private fun shutdown() {
        if (!running) return
        running = false
        handler.removeCallbacks(leave); handler.removeCallbacks(resolve)
        sensors.unregisterListener(listener)
        host.stopSpeech()
        audio.join()
        host.resumeMusic()
        onEnded()
    }

    private companion object { const val LEAVE_MS = 3000L; const val GAIN = 0.18 }
}
