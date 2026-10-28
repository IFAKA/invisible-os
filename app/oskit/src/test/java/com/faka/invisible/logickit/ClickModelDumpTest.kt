package com.faka.invisible.logickit

import java.io.File
import kotlin.math.ln
import org.junit.Test

/** Writes the posteriors after every click of fixed scripts to build/click-dump.txt, so tools/write_bench.py --verify-click can check its Python port of ClickModel. */
class ClickModelDumpTest {
    private val keys = listOf("A", "B", "C", "D", "E")
    private val noon = mapOf("A" to 0.0, "B" to 600.0, "C" to 1200.0, "D" to 1800.0, "E" to 2400.0)
    private val prior = mapOf("A" to 0.4, "B" to 0.25, "C" to 0.15, "D" to 0.1, "E" to 0.1)
    private val rounds = listOf(
        listOf(1300, 4300), listOf(100), listOf(1750, 2100, 5100), listOf(2650), listOf(1900), listOf(1300),
        listOf(1450), listOf(200), listOf(1350), listOf(1800, 4800), listOf(1400), listOf(2600), listOf(700, 1250, 1390),
    )

    @Test fun dump() {
        val out = StringBuilder()
        for ((name, params) in listOf("p99" to ClickModel.Params(), "p19" to ClickModel.Params(winDiff = ln(19.0)))) {
            val m = ClickModel<String>(3000.0, params)
            var k = 0
            for ((ri, r) in rounds.withIndex()) {
                m.start(prior, noon, winDiffs = mapOf("E" to m.params.winDiffHigh))
                for (c in r) {
                    val win = m.click(c.toDouble())
                    val got = m.posteriors()
                    out.append(name).append(' ').append(++k).append(' ').append(if (win) 1 else 0).append(' ')
                        .append(keys.joinToString(" ") { "%.10e".format(java.util.Locale.ROOT, got.getValue(it)) }).append('\n')
                    if (win) { m.learn(m.chosen()!!); if (ri == rounds.size - 2 && name == "p99") m.undo(); break }
                }
            }
        }
        File("build").mkdirs()
        File("build/click-dump.txt").writeText(out.toString())
    }
}
