package com.faka.invisible.write

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.TimeUnit

/** English to the language of the chat, on the phone. All calls block: use a background thread. A missing model is an answer, not a silent skip. */
object MlKitTranslator {
    /** The ML Kit code for a language-id code ("es"), or null when it cannot be translated to. */
    fun code(lang: String): String? = TranslateLanguage.fromLanguageTag(lang)

    fun hasModel(lang: String): Boolean = try {
        Tasks.await(RemoteModelManager.getInstance().isModelDownloaded(TranslateRemoteModel.Builder(lang).build()), 5, TimeUnit.SECONDS)
    } catch (e: Exception) { false }

    /** Starts the one-time download (about 30 MB, Wi-Fi only) for English and [lang]; returns at once. */
    fun download(lang: String) {
        val cond = DownloadConditions.Builder().requireWifi().build()
        for (l in listOf(TranslateLanguage.ENGLISH, lang)) {
            RemoteModelManager.getInstance().download(TranslateRemoteModel.Builder(l).build(), cond)
        }
    }

    /** The translation, or null when it failed (the caller must say so). */
    fun translate(text: String, to: String): String? {
        val t = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(to).build())
        return try { Tasks.await(t.translate(text), 15, TimeUnit.SECONDS).takeIf { it.isNotBlank() } } catch (e: Exception) { null } finally { t.close() }
    }
}
