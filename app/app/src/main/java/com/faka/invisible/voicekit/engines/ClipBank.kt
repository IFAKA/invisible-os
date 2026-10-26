package com.faka.invisible.voicekit

import android.content.Context
import java.security.MessageDigest

/**
 * ENGINE part. The recorded voice. A fixed sentence is played from `assets/voice/<key>.wav` (made on the laptop by
 * tools/voice_bakeoff/make_clips.py from the same list as the Audio Kit). A text is played only if EVERY sentence of it
 * has a clip; otherwise the phone's own voice says the whole text, so one text never mixes two voices.
 * Texts that had no clip are written to `spoken_miss.log`: add them to the list and make the missing clips.
 */
internal object ClipBank {
    const val GAP_MS = 180
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Spearcon.Pcm>()
    private val missed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Same rule as manifest.py: sha1 of "lang|text", first 12 hex digits. [lang] is "es" or anything else = "en". */
    fun key(text: String, lang: String?): String {
        val l = if (lang?.startsWith("es") == true) "es" else "en"
        return MessageDigest.getInstance("SHA-1").digest("$l|${text.trim()}".toByteArray()).take(6).joinToString("") { "%02x".format(it) }
    }

    /** "Both jab. Nothing." -> ["Both jab.", "Nothing."] */
    fun sentences(text: String): List<String> = text.trim().split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }

    private fun clip(ctx: Context, text: String, lang: String?): Spearcon.Pcm? {
        val k = key(text, lang)
        cache[k]?.let { return it }
        val pushed = java.io.File(ctx.filesDir, "voice/$k.wav")   // clips pushed to the phone while they are being made (tools/voice_bakeoff/sync_clips.sh)
        val pcm = try { (if (pushed.exists()) pushed.inputStream() else ctx.assets.open("voice/$k.wav")).use { Spearcon.readWav(it.readBytes()) } } catch (e: java.io.IOException) { null }
        return pcm?.also { cache[k] = it }
    }

    /** The whole [text] as one PCM (sentence clips joined by a short gap), or null if any sentence has no clip. */
    fun load(ctx: Context, text: String, lang: String?): Spearcon.Pcm? {
        val whole = clip(ctx, text, lang)
        if (whole != null) return whole
        val parts = sentences(text).map { clip(ctx, it, lang) ?: return null }
        if (parts.isEmpty()) return null
        val rate = parts[0].rate
        if (parts.any { it.rate != rate }) return null
        val gap = rate * GAP_MS / 1000
        val out = ShortArray(parts.sumOf { it.samples.size } + gap * (parts.size - 1))
        var p = 0
        for ((i, c) in parts.withIndex()) { c.samples.copyInto(out, p); p += c.samples.size + if (i < parts.size - 1) gap else 0 }
        return Spearcon.Pcm(rate, out)
    }

    /** True the first time [text] is reported as missing (the caller logs it once). */
    fun firstMiss(text: String, lang: String?) = missed.add(key(text, lang))
}
