package com.faka.invisible

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File

/**
 * The local model (Gemma 3 1B, runs on the phone, no network, no microphone). Slow to load, so call [replies] on a worker thread.
 * The model file is `files/gemma.litertlm` (not in the app: see sim/README or AGENTS.md).
 */
object LocalAi {
    private const val TAG = "INVISIBLE_AI"
    @Volatile private var engine: Engine? = null

    /** Newest model first: Gemma 4 E2B (files/gemma4.litertlm), else Gemma 3 1B (files/gemma.litertlm). */
    private fun modelFile(ctx: Context) = listOf("gemma4.litertlm", "gemma.litertlm").map { File(ctx.filesDir, it) }.firstOrNull { it.exists() }

    fun available(ctx: Context) = modelFile(ctx) != null

    @Synchronized private fun engine(ctx: Context): Engine = engine ?: Engine(EngineConfig(
        modelPath = modelFile(ctx)!!.path, backend = Backend.CPU(), cacheDir = ctx.cacheDir.path)).also { it.initialize(); engine = it }

    private val numbered = Regex("^(?:line\\s*)?\\d\\s*[.=:)]\\s*=?\\s*(.+)$", RegexOption.IGNORE_CASE)   // the model adds a preface line; keep only the numbered ones

    private fun ask(ctx: Context, prompt: String, what: String): List<String> {
        val t0 = System.currentTimeMillis()
        val e = engine(ctx)
        val t1 = System.currentTimeMillis()
        val text = synchronized(this) { e.createConversation().use { it.sendMessage(prompt).toString() } }
        val out = text.lines().mapNotNull { numbered.find(it.trim())?.groupValues?.get(1)?.trim('*', ' ', '"') }.filter { it.isNotEmpty() }.distinct().take(3)
        Log.d(TAG, "$what load=${t1 - t0}ms generate=${System.currentTimeMillis() - t1}ms -> $out")
        return out
    }

    private fun langName(lang: String) = if (lang == "es") "Spanish" else "English"

    /** Up to 3 short reply sentences for [message] (the whole "Sender: text" line), in [lang] ("es" or "en"). */
    fun replies(ctx: Context, message: String, lang: String): List<String> {
        val es = lang == "es"
        return ask(ctx, "Someone sent you this message:\n\"$message\"\nWrite 3 short replies you could send, in ${langName(lang)}, as full sentences, one per line:\n" +
            "1. an agreeing reply\n2. a declining reply\n3. a not-sure reply\n" +
            "Example for \"${if (es) "Luis: ¿Necesitas que te recoja?" else "Dad: Do you need a ride?"}\":\n" +
            (if (es) "1. Sí, por favor, a las seis\n2. No hace falta, gracias\n3. Todavía no sé, te aviso luego" else "1. Yes please, at six\n2. No thanks, I'm fine\n3. Not sure yet, I'll let you know") + "\nNow write the 3 replies.", "replies")
    }

    /** Up to 3 short to-do items the message asks of you (empty if it asks nothing), in [lang]. */
    fun tasks(ctx: Context, message: String, lang: String): List<String> {
        val es = lang == "es"
        return ask(ctx, "Someone sent you this message:\n\"$message\"\nIf it asks you to do something, write 1 to 3 short to-do items for yourself, in ${langName(lang)}, " +
            "each starting with a verb, max 8 words, numbered 1. 2. 3. If it asks for nothing, write only: none\n" +
            "Example for \"${if (es) "Luis: ¿Puedes enviarme el informe el viernes?" else "Kim: Can you send me the report by Friday?"}\":\n" +
            (if (es) "1. Enviar el informe a Luis el viernes" else "1. Send Kim the report by Friday") + "\nNow write the to-do items.", "tasks")
    }

    /** Up to 3 ways to finish a message that starts with [text], in [lang]: only the new words, short. [brief] says what the screen is about. */
    fun continuations(ctx: Context, text: String, lang: String, brief: String = ""): List<String> {
        val es = lang == "es"
        val start = if (text.isBlank()) "They have not written anything yet. Write 3 different short things they could write first" else "So far they wrote:\n\"$text\"\nWrite 3 different short endings for it"
        return ask(ctx, "Someone is writing a message.\n" + brief + start + ", in ${langName(lang)}. " +
            (if (text.isBlank()) "" else "Write only the new words that come next, not the words already written. ") + "Max 6 words each. One per line, numbered 1. 2. 3.\n" +
            "Example for \"${if (es) "Voy a" else "I will"}\":\n" +
            (if (es) "1. llegar tarde hoy\n2. salir en cinco minutos\n3. llamarte luego" else "1. be late today\n2. leave in five minutes\n3. call you later") + "\nNow write the 3 endings.", "endings")
    }
}
