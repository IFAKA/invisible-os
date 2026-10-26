@file:OptIn(KitOnly::class)
package com.faka.invisible.voicekit

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.util.Log

/**
 * ENGINE. Plays a game: a low-latency stereo float stream (5 ms blocks). [render] fills a block (the caller guards it with
 * its own lock); [running] says when to stop; [onLatency] gets the measured delay in ms about every 250 ms.
 */
class GameAudio(
    private val sr: Int,
    private val block: Int,
    private val render: (FloatArray, Int) -> Unit,
    private val running: () -> Boolean,
    private val onLatency: (Double) -> Unit,
) {
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    /** False if the audio output could not be built. */
    fun open(): Boolean {
        track = try { buildTrack() } catch (e: Exception) { Log.e("TELL", "AudioTrack failed", e); null }
        return track != null
    }

    fun start() { val t = track ?: return; thread = Thread({ pump(t) }, "tell-audio").also { it.start() } }

    fun join() { thread?.let { try { it.join(500) } catch (e: InterruptedException) {} }; thread = null }

    private fun buildTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val size = maxOf(min, block * 2 * 4 * 2)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(size).build()
    }

    private fun pump(track: AudioTrack) {
        val buf = FloatArray(block * 2)
        val ts = AudioTimestamp()
        var written = 0L
        var n = 0
        try {
            track.play()
            while (running()) {
                render(buf, block)
                track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
                written += block
                if (++n % 50 == 0 && track.getTimestamp(ts)) {   // every ~250 ms
                    val playedNow = ts.framePosition + (System.nanoTime() - ts.nanoTime) * sr / 1_000_000_000L
                    val ms = (written - playedNow) * 1000.0 / sr
                    if (ms in 0.0..1000.0) onLatency(ms)
                }
            }
        } catch (e: Throwable) {
            Log.e("TELL", "audio thread failed", e)
        } finally {
            try { track.stop() } catch (e: Exception) {}
            track.release()
        }
    }
}
