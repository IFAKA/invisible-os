package com.faka.invisible.write

import com.faka.invisible.logickit.words
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** What Write asks its sources: where the person is ([frame]), the words picked so far ([written]) and the letters of the word being spelled ([prefix]). */
data class Request(val frame: Frame, val written: List<String>, val prefix: String) {
    /** Everything typed so far, as the AI reads it: "meet me t". */
    val typed: String get() = (written + listOfNotNull(prefix.ifEmpty { null })).joinToString(" ")
    /** Two requests with the same key want the same answer. */
    val key: String get() = typed.lowercase()
}

/** A whole word (for the prefix) or a whole text, and how good the source thinks it is (only the order and the ratios count). */
data class Guess(val text: String, val score: Double = 1.0) {
    companion object {
        /** Guesses from a list that is already in order, best first. */
        fun ranked(texts: List<String>) = texts.mapIndexed { i, t -> Guess(t, 1.0 / (i + 1)) }
    }
}

/** A source of guesses (the plug-in of the mixer, [Mixer]). It may be slow: [ask] returns at once and calls [done] (on any thread) when it has an answer; no answer = never or an empty list. */
interface GuessSource {
    val id: String
    fun ask(req: Request, done: (List<Guess>) -> Unit)
}

/** Whole values used in this place before (a search, an address, a name): the history of the place. Instant. */
class HistorySource(private val store: BucketStore) : GuessSource {
    override val id = "history"
    override fun ask(req: Request, done: (List<Guess>) -> Unit) { done(Guess.ranked(store.suggestions(req.frame, 20))) }
}

/** Words spelled letter by letter before (names, new words), by their first letters. Instant. */
class SpelledSource(private val store: BucketStore) : GuessSource {
    override val id = "spelled"
    override fun ask(req: Request, done: (List<Guess>) -> Unit) { if (req.prefix.isNotEmpty()) done(Guess.ranked(store.spelled(req.prefix))) }
}

/** A list the OS keeps (contacts, apps, songs played): loaded once on the worker, then matched by the typed letters. */
class ListSource(override val id: String, private val load: () -> List<String>, private val run: (() -> Unit) -> Unit = { Thread(it, "write-list").apply { isDaemon = true }.start() }) : GuessSource {
    @Volatile private var items: List<String>? = null
    override fun ask(req: Request, done: (List<Guess>) -> Unit) {
        val have = items
        if (have != null) { if (req.typed.isNotEmpty()) done(match(have, req)); return }
        run { val l = try { load() } catch (_: Throwable) { emptyList() }; items = l; if (req.typed.isNotEmpty()) done(match(l, req)) }
    }
    private fun match(l: List<String>, req: Request) = Guess.ranked(l.filter { it.words().size == 1 && it.lowercase().startsWith(req.prefix.lowercase()) && req.prefix.isNotEmpty() || it.words().size > 1 && com.faka.invisible.logickit.Completion.fits(it, req.written, req.prefix, true) }.take(10))
}

/**
 * The first letters of the next words ("hay" -> "how are you"), by constrained beam search with the word model ([NextWord.initials]), on this phone and
 * without the AI. For 2 letters or more; plain prefix completion stays with the word model itself (source "lm"). Runs on a background thread, capped by [budgetMs].
 */
class InitialsSource(private val lm: NextWord, private val budgetMs: Long = 150, private val run: (() -> Unit) -> Unit = { Thread(it, "write-initials").apply { isDaemon = true }.start() }) : GuessSource {
    override val id = "initials"
    override fun ask(req: Request, done: (List<Guess>) -> Unit) {
        val letters = req.prefix.lowercase()
        if (letters.length < 2 || !letters.all { it.isLetter() }) return
        run { val r = try { lm.initials(req.written, letters, 8, 5, budgetMs) } catch (_: Throwable) { emptyList() }; if (r.isNotEmpty()) done(Guess.ranked(r.map { it.first })) }
    }
}

/** What the app shows on its own screen as suggestions (a search box lists them): read through the OS accessibility service. Never for a password. */
class ScreenSource(private val read: () -> List<String>, private val run: (() -> Unit) -> Unit = { Thread(it, "write-screen").apply { isDaemon = true }.start() }) : GuessSource {
    override val id = "screen"
    override fun ask(req: Request, done: (List<Guess>) -> Unit) {
        if (req.frame.kind == FieldKind.PASSWORD || req.frame.private) return
        run { val l = try { read() } catch (_: Throwable) { emptyList() }; if (l.isNotEmpty()) done(Guess.ranked(l.take(10))) }
    }
}

/**
 * The AI "complete" job (SpeakFaster style): the typed letters are read as the start of the current word OR as the first letters of the next words
 * ("hay" -> "how are you"); up to 5 whole texts. The AI never sees a private place, a password or a field that is not plain text or a search ([Frame.aiAllowed]).
 * The finished words and the letters go apart ([Request.written], [Request.prefix]). Gives up after [timeoutMs] (asked at each request: until the next cycle of the
 * loop plus one cycle, 1 to 3 s): no answer = no guesses from it. [call] blocks (the OS asks the computer, then the phone model); [trace] gets `ai complete 312ms 3`.
 */
class CompleteSource(
    private val call: (done: String, letters: String, context: String, timeoutMs: Long) -> List<String>,
    private val timeoutMs: () -> Long = { 1000L },
    private val trace: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val run: (() -> Unit) -> Unit = { Thread(it, "write-complete").apply { isDaemon = true }.start() },
) : GuessSource {
    override val id = "complete"
    private val pool = Executors.newCachedThreadPool { Thread(it, "write-complete-call").apply { isDaemon = true } }

    override fun ask(req: Request, done: (List<Guess>) -> Unit) {
        if (!req.frame.aiAllowed || req.prefix.isBlank()) return
        val context = if (req.frame.kind == FieldKind.TEXT) req.frame.around.joinToString("\n") else ""
        run {
            val t0 = now(); val limit = timeoutMs()
            val r = try { pool.submit<List<String>> { call(req.written.joinToString(" "), req.prefix, context, limit) }.get(limit, TimeUnit.MILLISECONDS) }
            catch (_: TimeoutException) { emptyList() } catch (_: ExecutionException) { emptyList() } catch (_: InterruptedException) { emptyList() }
            trace("ai complete ${now() - t0}ms ${r.size}")
            if (r.isNotEmpty()) done(Guess.ranked(r))
        }
    }
}
