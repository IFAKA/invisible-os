package com.faka.invisible

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Screens use only the UI Kit (Ui.kt) and the Audio Kit (Voice.kt). A missing part is added to the kit first. */
class KitRulesTest {
    private val src = listOf("src/main/java/com/faka/invisible", "app/src/main/java/com/faka/invisible")
        .map { File(it) }.first { it.isDirectory }
        .listFiles { f -> f.extension == "kt" }!!.toList()

    private fun offenders(skip: String, bad: Regex) =
        src.filter { it.name != skip }.flatMap { f -> f.readLines().withIndex().filter { bad.containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}" } }

    @Test fun noColorsOutsideUiKit() {
        val o = offenders("Ui.kt", Regex("""Color\.(parseColor|WHITE|BLACK|TRANSPARENT|RED|GREEN|BLUE|GRAY)|0x[Ff]{2}[0-9A-Fa-f]{6}"""))
        assertTrue("Colors must come from Ui (Ui.kt): $o", o.isEmpty())
    }

    @Test fun noSpokenSentencesOutsideAudioKit() {
        val o = offenders("Voice.kt", Regex("""\b(say|speak)\(\s*"""") )
        assertTrue("Spoken sentences must come from Voice (Voice.kt): $o", o.isEmpty())
    }

    @Test fun noRawViewsOutsideUiKit() {
        val o = offenders("Ui.kt", Regex("""GradientDrawable\(|\bTextView\(|\bButton\("""))
        assertTrue("Build views with Kit (Ui.kt): $o", o.isEmpty())
    }

    /** Every list is a ScanList (ScanList.kt): no second class keeps its own item index (scanIdx, menuIdx...) or loop count. */
    @Test fun noListsOutsideScanList() {
        val o = offenders("ScanList.kt", Regex("""\b(scanIdx|menuIdx|menuSteps|itemIdx|listIdx)\b|\bvar\s+loops\b"""))
        assertTrue("Build lists with ScanList (ScanList.kt): $o", o.isEmpty())
    }
}
