@file:OptIn(KitOnly::class)
package com.faka.invisible.voicekit

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue

/**
 * ENGINE. The on-device neural voice (sherpa-onnx running ONNX models): one voice per language, for fixed and content phrases alike.
 * English = Piper en_US-ljspeech-medium (Kokoro was tried: RTF 2.4 to 4.7 on the Redmi Note 14, 2.5 s for one word, so it is not used). Spanish = Piper es_MX-claude-high. Any other language, or a model that is not on the phone yet,
 * is not handled here ([handles] = false): TtsSpeech then uses the Google voice.
 * Models are NOT in the APK: [NeuralModels] downloads them to filesDir/tts on first run (Wi-Fi), silently.
 *
 * Licences: sherpa-onnx Apache-2.0. Piper en_US-ljspeech-medium: MODEL_CARD "License: public domain" (LJ Speech dataset).
 * Piper es_MX-claude-high: MODEL_CARD "License: apache-2.0" (dataset HirCoir/Piper-TTS-Spanish).
 * (Avoid en_US-hfc_female: CC BY-NC-SA, non-commercial only.)
 * The espeak-ng-data folder inside the archives is GPL-3.0-or-later phoneme data (read at run time by the sherpa library; it is downloaded, not in the APK).
 *
 * Threads: one worker thread makes the sound (never the main thread). A new non-queued speak or [stop] cuts the sound at once
 * (the track is paused and flushed from the caller's thread; the worker sees the new [epoch] and drops the old work).
 */
class NeuralSpeech(
    private val app: Context,
    private val handler: Handler,
    private val rate: () -> Float,
    private val listener: SpeechListener,
    /** Called (on the main thread) when a model fails to load: the phrase is spoken by the Google voice instead. */
    private val fallback: (Phrase, Boolean, Boolean) -> Unit,
) {
    private companion object {
        const val TAG = "NEURALTTS"
        const val THREADS = 4
        const val IDLE_UNLOAD_MS = 10 * 60_000L
        const val GAP_MS = 120      // silence between two sentences of one text
    }

    private class Utt(val id: Int, val epoch: Int, val p: Phrase, val slow: Boolean, val lang: String)

    private val models = HashMap<String, OfflineTts>()
    private val broken = HashSet<String>()
    private val lock = Object()
    private val q = LinkedBlockingQueue<Utt>()
    @Volatile private var epoch = 0
    @Volatile private var seq = 0
    @Volatile private var newest = 0
    @Volatile private var busy = false
    @Volatile private var cur: AudioTrack? = null
    @Volatile private var lastUse = System.currentTimeMillis()
    /** Dev hook: gets every chunk of samples (and the rate) that is played. */
    @Volatile private var speedOverride = 0f
    @Volatile var tap: ((FloatArray, Int) -> Unit)? = null

    val speaking get() = busy
    private var worker: Thread? = null

    fun start() {
        NeuralModels.ensureAll(app, handler) { l -> warm(l) }
        worker = Thread({ loop() }, "neural-tts").also { it.isDaemon = true; it.start() }
        // load both models now and make one silent sound with each (a background thread), so the first real sentence does not wait
        Thread({ for (l in listOf("en", "es")) if (NeuralModels.ready(app, l)) warm(l) }, "neural-load").start()
        handler.postDelayed(object : Runnable { override fun run() { unloadIdle(); handler.postDelayed(this, 60_000) } }, 60_000)
    }

    /** Which model speaks this language: "en", "es" or null (Google voice). */
    fun langOf(lang: String?): String? = when {
        lang == null || lang.startsWith("en", true) -> "en"
        lang.startsWith("es", true) -> "es"
        else -> null
    }

    /** True if this neural voice will say [p] (its model is on the phone and works). */
    fun handles(p: Phrase): Boolean { val l = langOf(p.lang) ?: return false; return l !in broken && NeuralModels.ready(app, l) }

    fun speak(p: Phrase, queue: Boolean, slow: Boolean) {
        val l = langOf(p.lang) ?: return
        if (!queue) cut()
        busy = true
        val id = ++seq; newest = id
        Log.d(TAG, "SPEAK[$l] id=$id queue=$queue ${p.text.take(60)}")
        q.add(Utt(id, epoch, p, slow, l))
    }

    /** Cut the sound now and forget everything waiting. */
    fun stop() { cut(); busy = false; newest = 0 }

    private fun cut() {
        epoch++; q.clear()
        cur?.let { try { it.pause(); it.flush() } catch (e: Exception) {} }
    }

    // ---- worker ----
    private var trk: AudioTrack? = null
    private var trkRate = 0
    private var frames = 0L
    private var lastWrite = 0L
    private var waited = 0L   // ms this thread slept waiting for the speaker (not synthesis time)

    private fun loop() {
        while (true) {
            val u = q.take()
            if (u.epoch != epoch) continue
            try { synchronized(lock) { play(u) } } catch (e: Throwable) {
                Log.e(TAG, "speak failed", e); dropTrack(); broken += u.lang
                handler.post { fallback(u.p, false, u.slow) }
            }
            if (q.isEmpty() && u.epoch == epoch) busy = false
        }
    }

    private fun stale(u: Utt) = u.epoch != epoch

    /** The neural voice spells a lone capitalised "Go" (the menu group) as "G O"; in lower case it says the word. */
    private fun ear(s: String) = if (s.trim().trimEnd('.', ',') == "Go") "go" else s

    private fun play(u: Utt) {
        lastUse = System.currentTimeMillis()
        val tts = load(u.lang) ?: run { broken += u.lang; handler.post { fallback(u.p, false, u.slow) }; return }
        val speed = if (speedOverride > 0f) speedOverride else if (u.slow) Pacing.COACH_RATE else rate()
        val sid = 0
        val t0 = System.currentTimeMillis(); var first = -1L; var samples = 0L; waited = 0
        val sr = tts.sampleRate()
        if (trk != null && trkRate != sr) drain(u)
        val parts = SentenceSplit.split(u.p.text)
        for ((i, s) in parts.withIndex()) {
            if (stale(u)) break
            // an explicit class, not a lambda: the sherpa JNI code looks up a method named invoke([F)Integer, which d8 does not keep on a lambda
            tts.generateWithCallback(ear(s), sid, speed, object : Function1<FloatArray, Int> {
                override fun invoke(chunk: FloatArray): Int {
                    if (stale(u)) return 0
                    if (trk == null) { trk = newTrack(sr); trkRate = sr; frames = 0; cur = trk; trk?.play() }
                    if (first < 0) { first = System.currentTimeMillis() - t0; if (u.id == newest) handler.post { if (u.id == newest) listener.started() } }
                    samples += chunk.size; tap?.invoke(chunk, sr)
                    return if (write(u, chunk)) 1 else 0
                }
            })
            if (!stale(u) && i < parts.size - 1) write(u, FloatArray(sr * GAP_MS / 1000))
        }
        if (stale(u)) { dropTrack(); return }
        val gen = System.currentTimeMillis() - t0 - waited   // synthesis time only
        if (q.isEmpty()) { drain(u); if (!stale(u) && u.id == newest) handler.post { if (u.id == newest) listener.ended() } }
        val audio = samples * 1000.0 / sr
        Log.d(TAG, "DONE[${u.lang}] id=${u.id} first_sound=$first ms synth=$gen ms audio=${audio.toLong()} ms rtf=${"%.2f".format(gen / maxOf(audio, 1.0))} chars=${u.p.text.length} speed=$speed")
    }

    private fun newTrack(sr: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())   // 16-bit like the tones: float tracks never started on this phone
            .setBufferSizeInBytes(maxOf(min, sr)).setTransferMode(AudioTrack.MODE_STREAM).build()   // about 0.5 s of buffer
            .also { if (android.os.Build.VERSION.SDK_INT >= 31) it.setStartThresholdInFrames(sr / 50) }   // start after 20 ms: a short word (less than the buffer) used to wait for more data and was never heard
    }

    /** Non-blocking writes in a loop, so a cut (pause + flush from another thread) can never leave this thread stuck. */
    private fun write(u: Utt, a: FloatArray): Boolean {
        val t = trk ?: return false
        val pcm = ShortArray(a.size) { (a[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
        var off = 0
        while (off < pcm.size) {
            if (stale(u)) return false
            val n = t.write(pcm, off, pcm.size - off, AudioTrack.WRITE_NON_BLOCKING)
            if (n < 0) return false
            off += n; frames += n; lastWrite = System.currentTimeMillis()
            if (n == 0) { Thread.sleep(10); waited += 10 }
        }
        return true
    }

    /** Wait until the last sample was played, then free the track. */
    private fun drain(u: Utt) {
        val t = trk ?: return
        try { t.stop() } catch (e: Exception) {}   // streaming: plays out what is queued, so a short word cannot stay unplayed
        val end = System.currentTimeMillis() + 15_000
        while (!stale(u) && t.playbackHeadPosition < frames && System.currentTimeMillis() < end) {
            if (t.playbackHeadPosition == 0 && System.currentTimeMillis() > lastWrite + 700) break   // the head position never moves on some phones: what is queued is at most 0.5 s
            Thread.sleep(15)
        }
        if (!stale(u) && t.playbackHeadPosition in 1 until frames) Log.w(TAG, "drain timed out: head=${t.playbackHeadPosition} written=$frames state=${t.playState}")
        dropTrack()
    }

    private fun dropTrack() { trk?.let { try { it.stop() } catch (e: Exception) {}; try { it.release() } catch (e: Exception) {} }; trk = null; cur = null }

    // ---- models ----
    private fun load(l: String): OfflineTts? {
        models[l]?.let { return it }
        val dir = NeuralModels.dir(app, l)
        val t0 = System.currentTimeMillis()
        return try {
            val onnx = dir.listFiles { f -> f.name.endsWith(".onnx") }?.firstOrNull() ?: return null
            val dataDir = File(dir, "espeak-ng-data").path
            val mc = OfflineTtsModelConfig(vits = OfflineTtsVitsModelConfig(model = onnx.path, tokens = File(dir, "tokens.txt").path, dataDir = dataDir), numThreads = THREADS)
            OfflineTts(null, OfflineTtsConfig(model = mc)).also { models[l] = it; Log.d(TAG, "LOADED $l in ${System.currentTimeMillis() - t0} ms sr=${it.sampleRate()}") }
        } catch (e: Throwable) { Log.e(TAG, "load $l failed", e); null }
    }

    /** Loads the model and runs one throw-away synthesis (the first run of a model is slower: memory is mapped and caches fill). */
    private fun warm(l: String) = synchronized(lock) {
        val t0 = System.currentTimeMillis()
        val tts = load(l) ?: return@synchronized
        try { tts.generate(if (l == "es") "Hola." else "Ready.", 0, 1f) } catch (e: Throwable) { Log.e(TAG, "warm $l failed", e) }
        lastUse = System.currentTimeMillis(); Log.d(TAG, "WARM $l in ${lastUse - t0} ms")
    }

    private fun unloadIdle() {
        if (busy || models.isEmpty() || System.currentTimeMillis() - lastUse < IDLE_UNLOAD_MS) return
        Thread({ synchronized(lock) { if (!busy && System.currentTimeMillis() - lastUse >= IDLE_UNLOAD_MS) { models.values.forEach { it.release() }; models.clear(); Log.d(TAG, "UNLOADED (idle)") } } }, "neural-unload").start()
    }

    // ---- dev tool: `am broadcast ... --es neural en|es --es text "..."` ----
    /** Speaks [text] through this voice [rounds] times; logs timings (tag NEURALTTS); the last round is saved as cache/[out].wav. */
    fun bench(lang: String, text: String, rounds: Int = 2, out: String = "neural.wav", speed: Float = 0f) {
        Log.d(TAG, "BENCH start $lang rounds=$rounds speed=$speed"); speedOverride = speed
        Thread({
            repeat(rounds) { r ->
                val acc = java.io.ByteArrayOutputStream(); var sr = 0
                tap = { c, s -> sr = s; val b = java.nio.ByteBuffer.allocate(c.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN); c.forEach { f -> b.putShort((f.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }; acc.write(b.array()) }
                val t0 = System.currentTimeMillis()
                speak(Phrase.content(text, lang), false, false)
                while (!busy) Thread.sleep(5)
                while (busy) Thread.sleep(20)
                Log.d(TAG, "BENCH[$lang] round=${r + 1}${if (r == 0) " (cold)" else " (warm)"} wall=${System.currentTimeMillis() - t0} ms")
                tap = null
                val pcm = acc.toByteArray(); val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sr).putInt(sr * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size)
                File(app.cacheDir, out).writeBytes(h.array() + pcm)
                Thread.sleep(500)
            }
            Log.d(TAG, "BENCH done, wav in cache/$out")
        }, "neural-bench").start()
    }
}

/** ENGINE helper. Model files: where they are, and the silent first-run download (Wi-Fi only, resumes, retries later). */
object NeuralModels {
    private const val TAG = "NEURALTTS"
    private const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"
    private const val RETRY_MS = 15 * 60_000L

    /** language -> name of the model folder (also the name of its archive on the release page). */
    private val names = mapOf("en" to "vits-piper-en_US-ljspeech-medium", "es" to "vits-piper-es_MX-claude-high")
    private val running = HashSet<String>()

    fun dir(ctx: Context, l: String) = File(File(ctx.filesDir, "tts"), names.getValue(l))
    fun ready(ctx: Context, l: String) = File(dir(ctx, l), ".ok").exists()

    private fun unmetered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val c = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Starts the download of every missing model; tries again later while something is missing. Never speaks. */
    fun ensureAll(ctx: Context, handler: Handler, onReady: (String) -> Unit) {
        var missing = false
        for (l in names.keys) if (!ready(ctx, l)) { missing = true; fetch(ctx, l, onReady) }
        if (missing) handler.postDelayed({ ensureAll(ctx, handler, onReady) }, RETRY_MS)
    }

    @Synchronized private fun fetch(ctx: Context, l: String, onReady: (String) -> Unit) {
        if (!running.add(l)) return
        if (!unmetered(ctx)) { Log.d(TAG, "download $l waits: no unmetered network"); running.remove(l); return }
        Thread({
            val name = names.getValue(l)
            val root = File(ctx.filesDir, "tts").apply { mkdirs() }
            val part = File(root, "$name.tar.bz2.part")
            try {
                var have = if (part.exists()) part.length() else 0L
                val c = URL("$BASE$name.tar.bz2").openConnection() as HttpURLConnection
                c.connectTimeout = 20000; c.readTimeout = 30000; c.instanceFollowRedirects = true
                if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                c.connect()
                if (c.responseCode == 200) have = 0 else if (c.responseCode != 206) throw java.io.IOException("HTTP ${c.responseCode}")
                val total = have + c.contentLengthLong; var lastPct = -1
                RandomAccessFile(part, "rw").use { f ->
                    f.seek(have); if (have == 0L) f.setLength(0)
                    val buf = ByteArray(256 * 1024)
                    c.inputStream.use { ins ->
                        while (true) {
                            val n = ins.read(buf); if (n < 0) break
                            f.write(buf, 0, n); have += n
                            val pct = (have * 100 / maxOf(total, 1)).toInt()
                            if (pct / 10 != lastPct / 10) { lastPct = pct; Log.d(TAG, "download $l $pct% ($have of $total)") }
                            if (!unmetered(ctx)) throw java.io.IOException("Wi-Fi lost")
                        }
                    }
                }
                if (have < total) throw java.io.IOException("stopped early")
                Log.d(TAG, "unpacking $l")
                unpack(part, root)
                File(File(root, name), ".ok").writeText("ok"); part.delete()
                Log.d(TAG, "model $l ready"); onReady(l)
            } catch (e: Throwable) { Log.e(TAG, "download $l stopped: ${e.message}; will retry later") }
            finally { synchronized(this) { running.remove(l) } }
        }, "neural-download-$l").start()
    }

    private fun unpack(archive: File, root: File) {
        org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
            org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(archive.inputStream().buffered(1 shl 20))).use { tar ->
            val rootPath = root.canonicalPath + File.separator
            while (true) {
                val e = tar.nextEntry ?: break
                val f = File(root, e.name)
                if (!f.canonicalPath.startsWith(rootPath)) continue   // never write outside the folder
                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().buffered().use { tar.copyTo(it) } }
            }
        }
    }
}
