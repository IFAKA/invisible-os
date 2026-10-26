plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android") version "2.2.20"
}
// Counter: the smallest working program of the OS. A program depends only on the framework, :oskit (com.faka.invisible:oskit).
val oskitVersion = "0.1.0"
android {
    namespace = "com.faka.invisible.programs.counter"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    // Unit tests run on your computer with a stub android.jar; this lets the stub classes (Handler, Context) be created.
    testOptions { unitTests.isReturnDefaultValues = true }
}
val kitRulesZip by configurations.creating
dependencies {
    implementation("com.faka.invisible:oskit:$oskitVersion")
    testImplementation("junit:junit:4.13.2")
    kitRulesZip("com.faka.invisible:oskit-kit-rules:$oskitVersion@zip")
}

// ---- checkKit: the same Kit rules the OS build runs (no Phrase( outside the kit, no own audio, no own list ...). ----
// The rules and the gate script come in the oskit-kit-rules artifact, so they are always the ones of the framework version you build against.
val kitDir = layout.buildDirectory.dir("kit").get().asFile
kitDir.mkdirs()
zipTree(kitRulesZip.singleFile).visit { if (!isDirectory) file.copyTo(File(kitDir, name), overwrite = true) }
extra["kitRulesFile"] = File(kitDir, "voicekit-rules.tsv")
extra["kitSources"] = fileTree(projectDir) { include("src/main/java/**/*.kt") }
extra["kitAllowFiles"] = listOf(file("kit-allow.tsv"))   // your own Sentences file may make fixed Phrases (see the file)
extra["kitTaskName"] = "checkKit"
apply(from = File(kitDir, "kit-rules.gradle.kts"))
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn("checkKit") }
