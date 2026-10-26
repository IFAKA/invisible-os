plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.faka.invisible"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.faka.invisible"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }   // the phone is arm64; keeps sherpa-onnx + onnxruntime to one ABI
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17"; freeCompilerArgs += "-Xskip-metadata-version-check" }   // litertlm is built with Kotlin 2.4
}
dependencies {
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    // On-device neural voice (sherpa-onnx, Apache-2.0). The AAR is the official release build, kept in libs/.
    implementation(files("libs/sherpa-onnx-1.12.11.aar"))
    // Unpacks the downloaded model archives (.tar.bz2). Jars are from the Gradle distribution (Apache-2.0).
    implementation(files("libs/commons-compress-1.26.1.jar", "libs/commons-io-2.15.1.jar", "libs/commons-lang3-3.14.0.jar"))
    testImplementation("junit:junit:4.13.2")
}

// ---- Voice Kit gate: nothing that speaks, plays a sound, reads the button or reads a list may live outside voicekit/. ----
// The rules are in voicekit-rules.tsv (also read by KitRulesTest). The build stops and lists file:line for every break.
val checkVoiceKit by tasks.registering {
    group = "verification"
    description = "Fails if code outside voicekit/ speaks, plays sound, reads the button or keeps its own list"
    val rulesFile = layout.projectDirectory.file("voicekit-rules.tsv")
    val sources = fileTree("src/main/java") { include("**/*.kt"); exclude("**/voicekit/**") }
    inputs.file(rulesFile); inputs.files(sources)
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
                for ((id, re, why) in rules) if (re.containsMatchIn(code) && (f.name to id) !in allowed) bad += "${f.relativeTo(projectDir)}:${i + 1}  [$id] $why"
            }
        }
        if (bad.isNotEmpty()) throw GradleException("Voice Kit rules broken (move it into voicekit/ or use the kit):\n" + bad.joinToString("\n"))
    }
}
tasks.matching { it.name == "preBuild" || it.name == "check" }.configureEach { dependsOn(checkVoiceKit) }
