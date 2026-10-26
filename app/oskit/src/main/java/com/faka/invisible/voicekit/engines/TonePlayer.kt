@file:OptIn(KitOnly::class)
package com.faka.invisible.voicekit

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.util.Log

/** ENGINE. The only place that plays short sounds (earcons, cues, the stereo word pair, the find-phone loop). */
class TonePlayer(private val app: Context, private val handler: Handler) : TonePort {
    private val tag = "Invisible"
    private var pairTrack: AudioTrack? = null
    private var findTrack: AudioTrack? = null
    private var findOldVolume = -1

    /** Plays PCM on the accessibility stream: same level as speech, not tied to the music volume. */
    fun play(pcm: ShortArray, rate: Int = Earcons.SR, vol: Float = 1f, onDone: (() -> Unit)? = null): AudioTrack? = try {
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
        t.write(pcm, 0, pcm.size); t.setVolume(vol); t.play()
        handler.postDelayed({ try { t.release() } catch (e: Exception) {}; onDone?.invoke() }, pcm.size * 1000L / rate + 80)
        t
    } catch (e: Exception) { Log.e(tag, "play failed", e); onDone?.invoke(); null }

    /** Earcons: see SOUND-DESIGN.md. */
    override fun beep(k: Tone, vol: Float) { play(Earcons.pcm(k), vol = vol) }

    override fun playPcm(pcm: ShortArray) { play(pcm) }

    override fun playStereo(pcm: ShortArray, rate: Int) {
        stopStereo()
        pairTrack = try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
            t.write(pcm, 0, pcm.size); t.play()
            val frames = pcm.size / 2
            handler.postDelayed({ if (pairTrack === t) pairTrack = null; try { t.release() } catch (e: Exception) {} }, frames * 1000L / rate + 100)
            t
        } catch (e: Exception) { Log.e(tag, "pair failed", e); null }
    }

    override fun stopStereo() { pairTrack?.let { try { it.stop(); it.release() } catch (e: Exception) {} }; pairTrack = null }

    /** Find my phone: the alarm sound loops on the alarm stream at full volume; the old alarm volume comes back after. */
    override fun findLoop(on: Boolean) {
        val am = app.getSystemService(AudioManager::class.java)
        findTrack?.let { try { it.stop(); it.release() } catch (e: Exception) {} }; findTrack = null
        if (!on) { if (findOldVolume >= 0) am.setStreamVolume(AudioManager.STREAM_ALARM, findOldVolume, 0); findOldVolume = -1; return }
        if (findOldVolume < 0) findOldVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        val pcm = Earcons.pcm(Tone.ALARM)
        findTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(Earcons.SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build().also {
                    it.write(pcm, 0, pcm.size); it.setLoopPoints(0, pcm.size, -1); it.setVolume(1f); it.play()
                }
        } catch (e: Exception) { Log.e(tag, "find ring failed", e); null }
    }
}
