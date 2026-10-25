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

    /** Up to 3 short reply sentences for [message] (the whole "Sender: text" line), in [lang] ("es" or "en"). */
    fun replies(ctx: Context, message: String, lang: String): List<String> {
        val t0 = System.currentTimeMillis()
        val e = engine(ctx)
        val t1 = System.currentTimeMillis()
        val es = lang == "es"
        val prompt = "Someone sent you this message:\n\"$message\"\nWrite 3 short replies you could send, in ${if (es) "Spanish" else "English"}, as full sentences, one per line:\n" +
            "1. an agreeing reply\n2. a declining reply\n3. a not-sure reply\n" +
            "Example for \"${if (es) "Luis: ¿Necesitas que te recoja?" else "Dad: Do you need a ride?"}\":\n" +
            (if (es) "1. Sí, por favor, a las seis\n2. No hace falta, gracias\n3. Todavía no sé, te aviso luego" else "1. Yes please, at six\n2. No thanks, I'm fine\n3. Not sure yet, I'll let you know") + "\nNow write the 3 replies."
        val text = e.createConversation().use { it.sendMessage(prompt).toString() }
        val numbered = Regex("^(?:line\\s*)?\\d\\s*[.=:)]\\s*=?\\s*(.+)$", RegexOption.IGNORE_CASE)   // the model adds a preface line; keep only the numbered ones
        val out = text.lines().mapNotNull { numbered.find(it.trim())?.groupValues?.get(1)?.trim('*', ' ', '"') }.filter { it.isNotEmpty() }.distinct().take(3)
        Log.d(TAG, "load=${t1 - t0}ms generate=${System.currentTimeMillis() - t1}ms -> $out")
        return out
    }
}
