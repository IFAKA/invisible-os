package com.faka.invisible.write

import android.content.Context
import android.util.Log
import com.faka.invisible.logickit.ChatLang
import com.faka.invisible.logickit.PriceSum
import com.faka.invisible.oskit.Program
import com.faka.invisible.oskit.ProgramEntry
import com.faka.invisible.oskit.ProgramHost
import com.faka.invisible.voicekit.*
import java.io.File
import java.util.concurrent.Executors

/**
 * What Write needs from the OS besides the [ProgramHost]. The OS implements it (it owns the accessibility service, the
 * on-device AI and the language detector); the program never reaches into the OS.
 */
interface WriteServices {
    /** The place that has the keyboard focus now (an empty [Frame] if none). */
    fun focusFrame(): Frame
    /** Put [text] into the focused field; false if it could not. */
    fun setFieldText(text: String): Boolean
    /** Is the big on-device model switched on and available? */
    fun aiEnabled(): Boolean
    /** Blocking: next words after [text]. */
    fun nextWords(text: String, brief: String): List<String>
    /** Blocking: ways to finish [text]. */
    fun continuations(text: String, brief: String): List<String>
    /** Blocking: the language code of [text] ("und" if unknown). */
    fun detectLanguage(text: String): String
}

/** State shared by every run of Write: the word model (loaded once, slow on a phone) and what was learned by place. */
private object WriteShared {
    private var model: Ranker? = null
    private var store: BucketStore? = null
    val worker = Executors.newSingleThreadExecutor()

    @Synchronized fun model(ctx: Context): Ranker? = model ?: try {
        val t0 = System.currentTimeMillis()
        val m: Ranker = NextWord(ctx.assets.open("lm/lm.bin"), ctx.assets.open("lm/vocab.txt").bufferedReader().readText())   // you write in English; the chat gets the translation
        Log.d(TAG, "WRITE model loaded in ${System.currentTimeMillis() - t0} ms")
        m.also { model = it }
    } catch (e: Throwable) { Log.e(TAG, "word model failed", e); null }

    @Synchronized fun buckets(ctx: Context): BucketStore = store ?: run {
        val bf = File(ctx.filesDir, "buckets.txt")
        BucketStore(if (bf.exists()) bf.readLines() else emptyList()) { lines -> try { bf.writeText(lines.joinToString("\n")) } catch (e: Exception) { Log.e(TAG, "buckets.txt write failed", e) } }.also { store = it }
    }
    const val TAG = "Invisible"
}

private const val TAG = WriteShared.TAG

/** Timers on the host's handler, one set per run. */
private class HandlerTimers(private val handler: android.os.Handler) : Timers {
    private val pending = HashMap<String, Runnable>()
    override fun set(key: String, delayMs: Long, task: () -> Unit) { clear(key); val r = Runnable { pending.remove(key); task() }; pending[key] = r; handler.postDelayed(r, delayMs) }
    override fun clear(key: String) { pending.remove(key)?.let { handler.removeCallbacks(it) } }
}

/** The words the player speaks and hears, on the narrator; the usage file goes to files/write_usage.tsv. */
private class WriteOut(val host: ProgramHost) : TypeOut {
    override fun pair(left: String, right: String?) { VoiceKit.narrator.pair(left, right) }
    override fun hush() { VoiceKit.narrator.hush() }
    override fun screen(rows: List<String>, mark: Int) { host.screen(rows, mark) }
    override fun silence() { VoiceKit.narrator.stopSpeech() }
    override fun prefetch(words: List<String>) { VoiceKit.narrator.prefetch(words) }
    override fun say(text: Phrase) { VoiceKit.narrator.say(text, true) }
    override fun cue(c: TypeCue) { Log.d(TAG, "WRITE cue $c"); VoiceKit.narrator.play(TypeSounds.pcm(c)) }
    override fun finished(text: String) {}
    override fun log(event: String, vararg fields: Any?) {
        try { File(host.context.filesDir, "write_usage.tsv").appendText((listOf(System.currentTimeMillis(), event) + fields.map { it.toString().replace('\t', ' ').replace('\n', ' ') }).joinToString("\t") + "\n") } catch (e: Throwable) { Log.e(TAG, "usage log failed", e) }
    }
}

/** Both Write and Sum: a [TypeProgram] is the keyboard; this class wires it to the host. The raw button goes to it while it is active. */
abstract class TypeShell : Program {
    protected var typer: TypeProgram? = null
    protected lateinit var host: ProgramHost
    @Volatile protected var stopped = false
    override val active get() = typer?.active == true
    override val exitOnClickHold = false   // click then hold picks the right word; Write is left from its menu
    override fun down(time: Long) { typer?.down(time) }
    override fun up(time: Long) { typer?.up(time) }
    override fun assistHold() { typer?.assistHold() }
    override fun tap(row: Int) { typer?.tapRow(row) }
    /** Replacing a run: no close sound (the next program starts at once). */
    override fun stop() { stopped = true; typer?.stop() }
}

/** Write: the stereo-pair keyboard into the focused field, or (with [sendTo]) into a notification reply or a search. */
class WriteProgram(
    private val services: WriteServices,
    /** Where the text goes instead of the focused field (notification reply, music search); true if sent. */
    private val sendTo: ((String) -> Boolean)? = null,
    /** The place of that reply (the message being answered); the screen is off and shows no chat. */
    private val replyFrame: Frame? = null,
) : TypeShell() {
    @Volatile private var chatLang: String? = null   // the language of the place being written into; null = not known
    @Volatile private var phraseReq = 0

    override fun start(host: ProgramHost) {
        this.host = host
        val w = host.waiting()
        Thread({
            val lm = WriteShared.model(host.context)
            host.handler.post {
                w()
                if (stopped) return@post
                if (lm == null) { host.speak(TypeVoice.NO_MODEL, false); host.ended(); return@post }
                begin(lm)
            }
        }, "write-load").start()
    }

    private fun begin(lm: Ranker) {
        val ctx = host.context
        val buckets = WriteShared.buckets(ctx)
        val frame = if (sendTo != null) (replyFrame ?: Frame()) else services.focusFrame()   // the place that has focus: app, kind of field, what is on the screen
        Log.d(TAG, "WRITE frame app=${frame.app} kind=${frame.kind} field=${frame.field} thread=${frame.thread} around=${frame.around.size} before=${frame.before.length}")
        chatLang = null
        Thread({   // the language of the place, from what is on the screen and in the field; unknown means nothing to translate
            val seen = (frame.around + frame.before).joinToString(" ")
            val lang = ChatLang.of(seen) { services.detectLanguage(it) }
            host.handler.post { chatLang = lang }
            if (lang != null && ChatLang.needsTranslation(lang) && MlKitTranslator.code(lang) != null && frame.kind == FieldKind.TEXT && !MlKitTranslator.hasModel(lang)) MlKitTranslator.download(lang)
            Log.d(TAG, "WRITE chat language $lang")
        }, "write-lang").start()
        val base = WriteOut(host)
        val out = object : TypeOut by base {
            override fun suggestions() = buckets.suggestions(frame)
            override fun nextWords(text: String, then: (List<String>) -> Unit) {
                if (!services.aiEnabled()) return
                WriteShared.worker.execute {
                    val r = try { services.nextWords(text, frame.brief()) } catch (e: Throwable) { Log.e(TAG, "next words failed", e); emptyList() }
                    host.handler.post { then(r) }
                }
            }
            override fun pair(left: String, right: String?) {
                Log.d(TAG, "WRITE pair $left | $right")
                base.pair(left, right)
            }
            override fun phrases(text: String, then: (List<String>) -> Unit) {
                if (!services.aiEnabled()) return
                val my = ++phraseReq; val frameNow = frame
                WriteShared.worker.execute {   // one at a time; a request for an older text is dropped before it starts
                    if (my != phraseReq) return@execute
                    val r = try { services.continuations(text, frameNow.brief()) } catch (e: Throwable) { Log.e(TAG, "endings failed", e); emptyList() }
                    if (r.isNotEmpty()) host.handler.post { then(r) }
                }
            }
            override fun written(text: String) { buckets.learn(frame, text.split(' ').filter { it.isNotEmpty() }, text) }
            override fun translate(text: String, then: (Translation) -> Unit) {
                val target = chatLang
                if (frame.kind != FieldKind.TEXT || target == null || !ChatLang.needsTranslation(target)) { then(Translation.Same); return }
                WriteShared.worker.execute {
                    val r: Translation = when {
                        MlKitTranslator.code(target) == null -> Translation.Failed(TypeVoice.TRANSLATION_FAILED)
                        !MlKitTranslator.hasModel(target) || !MlKitTranslator.hasModel("en") -> { MlKitTranslator.download(target); Translation.Failed(TypeVoice.TRANSLATION_MISSING) }
                        else -> MlKitTranslator.translate(text, target)?.let { Translation.Done(it) } ?: Translation.Failed(TypeVoice.TRANSLATION_FAILED)
                    }
                    Log.d(TAG, "WRITE translate to $target: ${r::class.simpleName}")
                    host.handler.post { then(r) }
                }
            }
            override fun readBack(text: String) { host.speak(Phrase.content(text, chatLang), false) }
            override fun finished(text: String) {
                Log.d(TAG, "WRITE result [${frame.app} ${frame.kind}]: ${if (frame.kind == FieldKind.PASSWORD) "-" else text}"); host.log("write: ${frame.app} ${text.length} chars")
                if (text.isNotEmpty()) {
                    if (sendTo != null) { if (!sendTo(text)) Log.d(TAG, "WRITE reply was not sent") }
                    else if (frame.hasField) { val all = if (frame.before.isBlank()) text else frame.before.trimEnd() + (if (frame.digits) "" else " ") + text; if (!services.setFieldText(all)) Log.d(TAG, "WRITE could not set the field") }
                }
                host.handler.postDelayed({ if (text.isEmpty()) host.speak(TypeVoice.EMPTY, false) else host.speak(Phrase.content(text), false) }, 700)
            }
        }
        typer = TypeProgram(BucketRanker(lm, buckets, frame), out, HandlerTimers(host.handler), frame) { typer = null; VoiceKit.narrator.hush(); host.ended() }.also { it.start() }
    }
}

/** Sum prices: the number keyboard (digits and a point) in a loop. Each price is added; empty Done or a long hold ends and reads the total. */
class SumProgram : TypeShell() {
    private val sum = PriceSum()

    override fun start(host: ProgramHost) { this.host = host; round(listOf(TypeVoice.SUM_START, TypeVoice.SUM_START2, TypeVoice.SUM_START3)) }

    private fun round(intro: List<Phrase>) {
        var last = false
        val base = WriteOut(host)
        val out = object : TypeOut by base {
            override fun finished(text: String) {
                if (sum.add(text)) host.handler.postDelayed({ host.speak(TypeVoice.total(sum.spoken()), false) }, 400)
                else { last = true; host.handler.postDelayed({ host.speak(if (sum.isEmpty) TypeVoice.NO_PRICES else TypeVoice.total(sum.spoken()), false) }, 400) }
            }
        }
        typer = TypeProgram(NoWords, out, HandlerTimers(host.handler), Frame(kind = FieldKind.DECIMAL)) {
            typer = null; VoiceKit.narrator.hush()
            if (last) host.ended() else host.handler.postDelayed({ if (!stopped) round(listOf(TypeVoice.SUM_NEXT)) }, 2200)
        }.also { it.intro = intro; it.start() }
    }
}

/** The registry entries of Write and Sum (menu ids hub:write, hub:sum). Loading the word model is the warm-up. */
object WriteEntries {
    fun write(services: WriteServices, ctx: () -> Context) = ProgramEntry("hub:write", TypeVoice.TITLE, warm = { Thread({ WriteShared.model(ctx()) }, "write-warm").start() }) { WriteProgram(services) }
    val sum = ProgramEntry("hub:sum", TypeVoice.SUM_TITLE) { SumProgram() }
}
