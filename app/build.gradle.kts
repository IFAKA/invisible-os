plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
}

// ---- Kit gate, over EVERY module (oskit, os, programs/*, adapters). Runs before every build; KitRulesTest applies the same rules. ----
// 1. Voice Kit: nothing that speaks, plays a sound, reads the button or reads a list may live outside voicekit/ (rules: voicekit-rules.tsv).
// 2. Borders: a program or an adapter depends on :oskit only. It must not use :os, another program or an adapter.
val moduleSources = fileTree(rootDir) {
    include("oskit/src/main/java/**/*.kt", "os/src/main/java/**/*.kt", "programs/*/src/main/java/**/*.kt", "adapters/src/main/java/**/*.kt")
}
val checkVoiceKit by tasks.registering {
    group = "verification"
    description = "Fails if code outside voicekit/ speaks, plays sound, reads the button or keeps its own list; or if a program or adapter reaches outside :oskit"
    val rulesFile = layout.projectDirectory.file("voicekit-rules.tsv")
    val sources = moduleSources.matching { exclude("**/voicekit/**") }
    val all = moduleSources
    val buildFiles = fileTree(rootDir) { include("programs/*/build.gradle.kts", "adapters/build.gradle.kts") }
    inputs.file(rulesFile); inputs.files(all); inputs.files(buildFiles)
    doLast {
        val lines = rulesFile.asFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
        val rules = lines.filter { it[0] != "allow" }.map { Triple(it[0], Regex(it[1]), it[2]) }
        val allowed = lines.filter { it[0] == "allow" }.map { it[1] to it[2] }.toSet()
        val bad = ArrayList<String>()
        sources.forEach { f ->
            f.readLines().forEachIndexed { i, raw ->
                val t = raw.trim()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) return@forEachIndexed
                val code = raw.substringBefore(" // ")
                for ((id, re, why) in rules) if (re.containsMatchIn(code) && (f.name to id) !in allowed) bad += "${f.relativeTo(rootDir)}:${i + 1}  [$id] $why"
            }
        }
        if (bad.isNotEmpty()) throw GradleException("Voice Kit rules broken (move it into voicekit/ or use the kit):\n" + bad.joinToString("\n"))

        // Borders. (a) the build files: only project(":oskit") is allowed.
        val border = ArrayList<String>()
        buildFiles.forEach { b ->
            b.readLines().forEachIndexed { i, l ->
                Regex("project\\(\"(:[^\"]+)\"\\)").findAll(l).forEach { m ->
                    if (m.groupValues[1] != ":oskit") border += "${b.relativeTo(rootDir)}:${i + 1}  depends on ${m.groupValues[1]}: a program or an adapter may depend only on :oskit"
                }
            }
        }
        // (b) the sources: a package declared by :os, another program or an adapter must not be used by a program or an adapter.
        fun moduleOf(f: File): String = f.relativeTo(rootDir).invariantSeparatorsPath.substringBefore("/src/")
        val owners = HashMap<String, MutableSet<String>>()
        all.forEach { f -> f.useLines { ls -> ls.firstOrNull { it.startsWith("package ") }?.removePrefix("package ")?.trim()?.let { owners.getOrPut(it) { HashSet() } += moduleOf(f) } } }
        val pkgRe = Regex("com\\.faka\\.invisible(\\.[a-z][A-Za-z0-9_]*)*")
        all.filter { moduleOf(it).let { m -> m.startsWith("programs/") || m == "adapters" } }.forEach { f ->
            val me = moduleOf(f)
            f.readLines().forEachIndexed { i, raw ->
                val t = raw.trim()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("package ")) return@forEachIndexed
                pkgRe.findAll(raw).forEach { m ->
                    val o = owners[m.value] ?: return@forEach
                    if (me !in o && "oskit" !in o) border += "${f.relativeTo(rootDir)}:${i + 1}  uses ${m.value} from ${o.joinToString()}: a program or an adapter may use only :oskit (ask the OS through the Program host)"
                }
            }
        }
        if (border.isNotEmpty()) throw GradleException("Module borders broken:\n" + border.joinToString("\n"))
    }
}
subprojects { tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(rootProject.tasks.named("checkVoiceKit")) } }
