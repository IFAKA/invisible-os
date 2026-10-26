@file:OptIn(KitOnly::class)
package com.faka.invisible.voicekit

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * ENGINE (dev tool). Speaks [text] 3 times through a TTS engine and logs the time to the first sound and the total time (it plays aloud). Result goes to logcat tag TTSBENCH.
 * `adb shell am broadcast -n com.faka.invisible/.AiTestReceiver --es ttsbench com.k2fsa.sherpa.onnx.tts.engine --es lang es --es text "Hola"`
 */
object TtsBench {
    fun run(ctx: Context, engine: String?, lang: String, text: String) {
        val t0 = System.currentTimeMillis()
        var tts: TextToSpeech? = null
        tts = TextToSpeech(ctx.applicationContext, { s ->
            val t = tts ?: return@TextToSpeech
            Log.d("TTSBENCH", "engine=$engine init=${System.currentTimeMillis() - t0} ms status=$s voice=${t.defaultEngine}")
            if (s != TextToSpeech.SUCCESS) return@TextToSpeech
            val r = t.setLanguage(Locale(lang)); Log.d("TTSBENCH", "lang=$lang available=$r")
            var start = 0L; var round = 0; var first = 0L
            fun go() { start = System.currentTimeMillis(); t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "b${++round}") }
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { first = System.currentTimeMillis() - start }
                override fun onError(id: String?) { Log.d("TTSBENCH", "error $id") }
                override fun onDone(id: String?) {
                    val total = System.currentTimeMillis() - start
                    Log.d("TTSBENCH", "round=$round first_sound=$first ms total=$total ms chars=${text.length} (round 1 = cold)")
                    if (round < 3) go() else t.shutdown()
                }
            })
            go()
        }, engine)
    }
}
