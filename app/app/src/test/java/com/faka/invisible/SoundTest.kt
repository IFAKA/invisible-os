package com.faka.invisible

import com.faka.invisible.voicekit.*

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class SoundTest {
    @Test fun everyToneHasShortQuietPcm() {
        for (t in Tone.values()) {
            val p = Earcons.pcm(t)
            assertTrue("$t empty", p.isNotEmpty())
            assertTrue("$t clips", p.maxOf { abs(it.toInt()) } < 0.9 * 32767)
            assertTrue("$t too long", p.size * 1000L / Earcons.SR <= 1600)
        }
        assertTrue("tap is a click", Earcons.pcm(Tone.TAP).size * 1000L / Earcons.SR <= 30)
    }

    /** Audition files: WAV_DIR=../../sound/wav ./gradlew testDebugUnitTest. The shipped sound is exactly what you hear. */
    @Test fun writeAuditionFiles() {
        val dir = System.getenv("WAV_DIR")?.let { java.io.File(it) } ?: return
        dir.mkdirs()
        for (t in Tone.values()) write(java.io.File(dir, t.name.lowercase() + ".wav"), Earcons.pcm(t))
        for (n in 1..4) write(java.io.File(dir, "volume_$n.wav"), Earcons.level(n))
        val z = ShortArray(Earcons.SR / 2)   // a short session; silence stands for speech
        val demo = ArrayList<Short>()
        for ((t, gap) in listOf(Tone.TAP to 200, Tone.TAP to 600, Tone.HUB to 1200, Tone.TICK to 1400, Tone.TICK to 1400, Tone.TAP to 150, Tone.PICK to 1500,
            Tone.MESSAGE to 1200, Tone.TICK to 1400, Tone.TAP to 150, Tone.PICK to 900, Tone.UNDO to 3000, Tone.SENT to 1500, Tone.LEAVE to 0)) {
            demo.addAll(Earcons.pcm(t).toList()); repeat(gap * Earcons.SR / 1000) { demo.add(0) }
        }
        write(java.io.File(dir, "_demo.wav"), demo.toShortArray())
    }

    private fun write(f: java.io.File, s: ShortArray) = f.writeBytes(wav(Earcons.SR, s))

    @Test fun resultTonesDiffer() {
        val a = Earcons.pcm(Tone.PICK).toList(); val b = Earcons.pcm(Tone.AGAIN).toList(); val c = Earcons.pcm(Tone.BACK).toList()
        assertNotEquals(a, b); assertNotEquals(b, c); assertNotEquals(a, c)
    }

    private fun wav(rate: Int, s: ShortArray): ByteArray {
        val bb = ByteBuffer.allocate(44 + s.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + s.size * 2).put("WAVE".toByteArray()).put("fmt ".toByteArray())
        bb.putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        bb.put("data".toByteArray()).putInt(s.size * 2); s.forEach { bb.putShort(it) }
        return bb.array()
    }

    private fun crossings(x: ShortArray) = (1 until x.size).count { (x[it - 1] < 0) != (x[it] < 0) }

    @Test fun readsWav() {
        val s = ShortArray(100) { (it * 10).toShort() }
        val p = Spearcon.readWav(wav(22050, s))!!
        assertEquals(22050, p.rate); assertArrayEquals(s, p.samples)
        assertNull(Spearcon.readWav(ByteArray(10)))
    }

    @Test fun compressesToRatioKeepingPitch() {
        val rate = 22050
        // 0.1 s silence, 1.5 s of a 200 Hz tone with a slow beat (speech-like energy), 0.1 s silence
        val x = ShortArray(rate * 17 / 10) { i -> val t = i.toDouble() / rate
            if (i < rate / 10 || i > rate * 16 / 10) 0 else (9000 * sin(2 * Math.PI * 200 * t) * (0.6 + 0.4 * sin(2 * Math.PI * 3 * t))).toInt().toShort() }
        val y = Spearcon.compress(x, rate, 0.4)
        val speechSec = 1.5
        assertEquals(speechSec * 0.4, y.size.toDouble() / rate, 0.08)
        val hzIn = crossings(x) / 2.0 / speechSec
        val hzOut = crossings(y) / 2.0 / (y.size.toDouble() / rate)
        assertEquals(hzIn, hzOut, hzIn * 0.1)
    }

    @Test fun keyIsStablePerTextAndLang() {
        assertEquals(Spearcon.key("Mensajes", "es"), Spearcon.key("Mensajes", "es"))
        assertNotEquals(Spearcon.key("Mensajes", "es"), Spearcon.key("Mensajes", null))
    }
}
