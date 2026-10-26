package com.faka.invisible

import com.faka.invisible.logickit.*

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import java.util.concurrent.TimeUnit

/** Bundled ML Kit language-id model: fully on-device, no network. Blocking: call off the main thread. */
class MlKitLangDetector : LangDetector {
    private val client by lazy {
        LanguageIdentification.getClient(LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.4f).build())
    }

    override fun detect(text: String): String {
        val code = try { Tasks.await(client.identifyLanguage(text.take(300)), 3, TimeUnit.SECONDS) } catch (e: Exception) { "und" }
        return if (code == "und") MessageLogic.guessLang(text) else code
    }
}
