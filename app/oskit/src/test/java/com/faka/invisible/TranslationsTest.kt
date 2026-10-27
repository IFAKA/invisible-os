package com.faka.invisible

import com.faka.invisible.voicekit.MenuLanguage
import com.faka.invisible.voicekit.Phrase
import com.faka.invisible.voicekit.Translations
import com.faka.invisible.voicekit.Voice
import com.faka.invisible.voicekit.Menu
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(com.faka.invisible.voicekit.KitOnly::class)
class TranslationsTest {
    @After fun back() { MenuLanguage.use("en") }

    private val packs get() = Translations.packs
    private fun holes(s: String) = Regex("\\{\\d}").findAll(s).map { it.value }.sorted().toList()
    private fun maxWords(s: String) = s.split(Regex("[.!?]+")).maxOf { it.trim().split(Regex("\\s+")).count { w -> w.isNotEmpty() } }

    @Test fun everyLanguageHasEveryKey() {
        val all = packs.flatMap { it.all.keys }.toSet()
        for (p in packs) assertEquals("missing in ${p.code}", emptySet<String>(), all - p.all.keys)
        for (p in packs) assertEquals("${p.code} number words", 31, p.numbers.size)
    }

    @Test fun placeholdersMatch() {
        for (p in packs) for ((k, v) in p.all) assertEquals("${p.code}: $k", holes(k).distinct(), holes(v).distinct())
    }

    @Test fun spokenSentencesKeepSevenWords() {
        for (p in packs) for ((k, v) in p.table) assertTrue("${p.code}: '$v' has ${maxWords(v)} words in a sentence", maxWords(v) <= 7)
    }

    /** Every whole sentence written in a Sentences.kt file has a key (so a new English sentence cannot be forgotten). Dynamic ones (with $ or +) are patterns. */
    @Test fun everyFixedSentenceInTheSourcesIsTranslated() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val files = root.walkTopDown().filter { it.name.endsWith("Sentences.kt") && "/build/" !in it.path }.toList()
        assertTrue(files.size >= 8)
        val skip = setOf("<click1>", "<click2>", "<notes>", "")
        val missing = mutableListOf<String>()
        for (f in files) Regex("""\b(?:f|mixed)\("([^"$]*)"\)""").findAll(f.readText()).forEach { m ->
            val t = m.groupValues[1]
            if (t !in skip) for (p in packs) if (Translations.translate(p.code, t) == null) missing += "${p.code}: $t (${f.name})"
        }
        assertEquals(emptyList<String>(), missing)
    }

    @Test fun menuIsEnglishUnlessTheOwnerChoosesAnother() {
        assertEquals("en", MenuLanguage.resolve(MenuLanguage.AUTO))
        assertEquals("en", MenuLanguage.resolve(null))
        assertEquals("es", MenuLanguage.resolve("es"))
        assertEquals("en", MenuLanguage.resolve("xx"))
    }

    @Test fun englishIsUntouchedAndSpanishIsTaggedForTheVoice() {
        MenuLanguage.use("en")
        assertEquals("Timer done", Voice.TIMER_DONE.text); assertNull(Voice.TIMER_DONE.lang)
        MenuLanguage.use("es")
        assertEquals("Temporizador listo", Voice.TIMER_DONE.text); assertEquals("es", Voice.TIMER_DONE.lang)
        assertEquals("Temporizador de 5 minutos", Voice.timerSet(5).text)
        assertEquals("Mensajes, 3 sin leer", Menu.messages(3).text)
        assertEquals("Siete", Menu.digit(7).text)
        assertEquals("Cinco minutos", Menu.minutes(5).text)
        assertEquals("Hablar", Menu.treeName("Talk").text)
        assertEquals("WhatsApp, 2 notificaciones", Menu.treeCount("WhatsApp", 2).text)
        assertEquals("Hablar, 1 notificación", Menu.treeCount("Talk", 1).text)
        assertEquals("site, listo", Voice.roomDone("site").text)
        MenuLanguage.use("en")
        assertEquals("WhatsApp, 2 notifications", Menu.treeCount("WhatsApp", 2).text)
        assertEquals("WhatsApp, 1 notification", Menu.treeCount("WhatsApp", 1).text)
        assertEquals("site, done. 2 more windows done", Voice.roomsDone("site", 2).text)
        MenuLanguage.use("es")
    }

    @Test fun partsOfASentenceAreTranslated() {
        MenuLanguage.use("es")
        assertEquals("Ahora menos 3 grados, lluvia en 2 horas.", Voice.weather(-3, 2).text)
        assertEquals("5 03 de la tarde, batería 80 por ciento, sin mensajes sin leer, próxima alarma 7 00 de la mañana",
            Voice.status(com.faka.invisible.voicekit.Status(17, 3, 80, 7, 0), 0).text)
        assertEquals("Paso 2 de 12. Acceso a notificaciones. Toca dos veces Activar.", Voice.setupStep(2, 12, "Notification access", "Turn on").text)
    }

    @Test fun unknownTextStaysEnglishWithTheEnglishVoice() {
        MenuLanguage.use("es")
        val p = Phrase.mixed("Some brand new sentence")
        assertEquals("Some brand new sentence", p.text); assertNull(p.lang)
    }
}
