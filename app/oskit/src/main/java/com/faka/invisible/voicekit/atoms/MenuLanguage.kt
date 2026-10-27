package com.faka.invisible.voicekit

/**
 * The language of the fixed sentences and screen labels (the menu). Content (messages, names) keeps its own language.
 * To add a language: one file `Translations<Xx>.kt` with a [LangPack], and one line in [Translations.packs]. The unit test then lists every missing key.
 */
object MenuLanguage {
    const val AUTO = "auto"
    val supported: List<String> get() = listOf("en") + Translations.packs.map { it.code }
    /** The names a person sees in the language choice (each in its own language). */
    val names = mapOf("en" to "English", "es" to "Español")

    @Volatile var code: String = "en"; private set

    /** [setting] is a code the owner chose. [AUTO] or nothing -> English (the phone's language is not used). */
    fun resolve(setting: String?): String {
        val s = setting?.lowercase()
        return if (s != null && s != AUTO && s in supported) s else "en"
    }

    fun apply(setting: String?) { code = resolve(setting) }
    fun use(code: String) { this.code = if (code in supported) code else "en" }
}
