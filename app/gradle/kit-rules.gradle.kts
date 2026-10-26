// The Kit gate, shared by app/ and the SDK starter (apply with apply(from = ...)). Set these extra properties first:
//   kitRulesFile   File          the rules (voicekit-rules.tsv)
//   kitSources     FileTree      every .kt file to check (the kit's own voicekit/ folder is skipped for the rules, not for the borders)
//   kitBuildFiles  FileTree      build files of programs and adapters (only project(":oskit") is allowed there); may be empty
//   kitAllowFiles  List<File>    optional: more "allow" lines (same format), for example a program's own Sentences file
//   kitTaskName    String        optional: name of the task (default checkVoiceKit)
@Suppress("UNCHECKED_CAST")
fun <T> kitProp(name: String): T? = if (extra.has(name)) extra[name] as T else null
val kitRulesFile = kitProp<File>("kitRulesFile") ?: throw GradleException("kit-rules: set extra kitRulesFile first")
val moduleSources = kitProp<FileTree>("kitSources") ?: throw GradleException("kit-rules: set extra kitSources first")
val kitBuildFilesTree = kitProp<FileTree>("kitBuildFiles")
val kitAllowFiles = kitProp<List<File>>("kitAllowFiles") ?: emptyList()
val kitTaskName = kitProp<String>("kitTaskName") ?: "checkVoiceKit"
// 1. Voice Kit: nothing that speaks, plays a sound, reads the button or reads a list may live outside voicekit/.
// 2. Borders: a program or an adapter depends on :oskit only. It must not use :os, another program or an adapter.
tasks.register(kitTaskName) {
    group = "verification"
    description = "Fails if code outside voicekit/ speaks, plays sound, reads the button or keeps its own list; or if a program or adapter reaches outside :oskit"
    val rulesFile = kitRulesFile
    val sources = moduleSources.matching { exclude("**/voicekit/**") }
    val all = moduleSources
    val buildFiles = kitBuildFilesTree ?: fileTree(rootDir) { include("nothing") }
    inputs.file(rulesFile); inputs.files(all); inputs.files(buildFiles); inputs.files(kitAllowFiles)
    doLast {
        val lines = (listOf(rulesFile) + kitAllowFiles).flatMap { it.readLines() }.filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
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
