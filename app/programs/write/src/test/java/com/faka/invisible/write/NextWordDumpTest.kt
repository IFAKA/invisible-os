package com.faka.invisible.write

import java.io.File
import org.junit.Test

/** Writes the top-5 words (and log probabilities) of 3 contexts to build/lm-top5.txt, so tools/write_bench.py can check its Python port of the LSTM. */
class NextWordDumpTest {
    @Test fun dumpTop5() {
        val dir = File("src/main/assets/lm")
        val lm = NextWord(File(dir, "lm.bin").inputStream(), File(dir, "vocab.txt").readText())
        val out = StringBuilder()
        for (ctx in listOf("", "thank", "can you call me")) {
            val top = lm.top(NextWord.words(ctx), 5)
            out.append("ctx=").append(ctx).append('\t').append(top.joinToString(" ") { "${it.first}:${"%.4f".format(java.util.Locale.ROOT, it.second)}" }).append('\n')
        }
        File("build").mkdirs()
        File("build/lm-top5.txt").writeText(out.toString())
        print(out)
    }
}
