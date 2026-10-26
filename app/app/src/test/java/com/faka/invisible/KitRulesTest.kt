package com.faka.invisible

import com.faka.invisible.voicekit.*

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Screens use only the UI Kit (Ui.kt) and the Voice Kit (voicekit/). A missing part is added to the kit first.
 * The Voice Kit rules are in voicekit-rules.tsv; the Gradle task checkVoiceKit applies the same file before every build.
 */
class KitRulesTest {
    private val appDir = listOf(File("."), File("app")).first { File(it, "voicekit-rules.tsv").isFile }
    private val root = File(appDir, "src/main/java/com/faka/invisible")
    private val all = root.walkTopDown().filter { it.extension == "kt" }.toList()
    private val outside = all.filter { !it.path.contains("/voicekit/") }

    private fun offenders(files: List<File>, bad: Regex) =
        files.flatMap { f -> f.readLines().withIndex().filter { bad.containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}" } }

    @Test fun noColorsOutsideUiKit() {
        val o = offenders(all.filter { it.name != "Ui.kt" }, Regex("""Color\.(parseColor|WHITE|BLACK|TRANSPARENT|RED|GREEN|BLUE|GRAY)|0x[Ff]{2}[0-9A-Fa-f]{6}"""))
        assertTrue("Colors must come from Ui (Ui.kt): $o", o.isEmpty())
    }

    @Test fun noRawViewsOutsideUiKit() {
        val o = offenders(all.filter { it.name != "Ui.kt" }, Regex("""GradientDrawable\(|\bTextView\(|\bButton\("""))
        assertTrue("Build views with Kit (Ui.kt): $o", o.isEmpty())
    }

    private val rules = File(appDir, "voicekit-rules.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
    private val allowed = rules.filter { it[0] == "allow" }.map { it[1] to it[2] }.toSet()

    private fun broken(id: String): List<String> {
        val re = Regex(rules.first { it[0] == id }[1])
        return outside.filter { (it.name to id) !in allowed }.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) ->
                val t = l.trim()
                !(t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) && re.containsMatchIn(l.substringBefore(" // "))
            }.map { "${f.name}:${it.index + 1}" }
        }
    }

    private fun rule(id: String) { val o = broken(id); assertTrue("[$id] ${rules.first { it[0] == id }[2]}: $o", o.isEmpty()) }

    @Test fun noOwnSpeechEngine() = rule("tts")
    @Test fun noOwnSoundPlayback() = rule("audio-play")
    @Test fun noAudioFocusOrStreamPlaying() = rule("audio-focus")
    @Test fun buttonComesInOnlyThroughTheKit() = rule("button-intake")
    @Test fun fixedSentencesAreMadeOnlyInTheKit() = rule("phrase-make")
    @Test fun noFixedSentenceInDisguise() = rule("phrase-literal")
    @Test fun noSpokenStringLiterals() = rule("say-literal")
    @Test fun noListsOutsideTheScanner() = rule("second-list")
    @Test fun noHandRolledButtonTiming() = rule("hand-timing")
    @Test fun whiteSpaceCleaningIsInTheLogicKit() = rule("logic-squash")

    @Test fun everyRuleInTheFileHasATestHere() {
        val ids = rules.filter { it[0] != "allow" }.map { it[0] }.toSet()
        val tested = setOf("tts", "audio-play", "audio-focus", "button-intake", "phrase-make", "phrase-literal", "say-literal", "second-list", "hand-timing", "logic-squash")
        assertTrue("rules without a test: ${ids - tested}", ids == tested)
    }

    /** Every sentence in the kit's word lists is a fixed Phrase made in Sentences.kt; the Duel and the screens can only use these. */
    @Test fun everyKitSentenceIsFixed() {
        val bad = ArrayList<String>()
        fun check(owner: Any) {
            for (f in owner.javaClass.declaredFields) {
                f.isAccessible = true
                val v = f.get(owner)
                val phrases = when (v) { is Phrase -> listOf(v); is List<*> -> v.filterIsInstance<Phrase>(); else -> emptyList() }
                for (p in phrases) if (!p.isFixed) bad += "${owner.javaClass.simpleName}.${f.name}: ${p.text}"
            }
        }
        listOf<Any>(Voice, Menu, TellVoice, TypeVoice).forEach { check(it) }
        assertTrue("sentences made outside Sentences.kt: $bad", bad.isEmpty())
    }

    /** The Duel's spoken lines (narration, summary, mistake) are built by TellVoice from fixed parts only. */
    @Test fun duelNarrationIsFixed() {
        val s = com.faka.invisible.tell.State(3, 3, 4, 4)
        val r = com.faka.invisible.tell.TellRules.resolve(s, com.faka.invisible.tell.Move.GUARD, com.faka.invisible.tell.Move.JAB)
        assertTrue(TellVoice.narration(r).isFixed)
        assertTrue(TellVoice.narrationL1(r).isFixed)
        assertTrue(TellVoice.narrationL2(r).isFixed)
        assertTrue(costliestMistake(emptyList()).isFixed)
    }
}
