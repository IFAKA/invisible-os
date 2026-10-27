plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
}

// ---- Kit gate, over EVERY module (oskit, os, programs/*, adapters). Runs before every build; KitRulesTest applies the same rules. ----
// The check itself lives in gradle/kit-rules.gradle.kts, shared with the SDK starter (sdk/starter). Here we only say what to check.
extra["kitRulesFile"] = layout.projectDirectory.file("voicekit-rules.tsv").asFile
extra["kitSources"] = fileTree(rootDir) {
    include("oskit/src/main/java/**/*.kt", "os/src/main/java/**/*.kt", "programs/*/src/main/java/**/*.kt", "adapters/src/main/java/**/*.kt")
}
extra["kitBuildFiles"] = fileTree(rootDir) { include("programs/*/build.gradle.kts", "adapters/build.gradle.kts") }
apply(from = "gradle/kit-rules.gradle.kts")
subprojects { tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(rootProject.tasks.named("checkVoiceKit")) } }

// ---- `invisible sim NAME`: run a program on the laptop (SimMain in :oskit testing). This task writes what the CLI needs to start the JVM:
// build/sim-classpath.txt = line 1 java.home, line 2 the unit-test classpath of the program module (its classes, :oskit, android stubs).
subprojects {
    if (path.startsWith(":programs:")) {
        plugins.withId("com.android.library") {
            afterEvaluate {
                (extensions.getByName("android") as com.android.build.gradle.LibraryExtension).testOptions.unitTests.isReturnDefaultValues = true
                val testTask = tasks.named("testDebugUnitTest", Test::class)
                tasks.register("simClasspath") {
                    dependsOn(testTask.map { it.classpath })
                    val outFile = layout.buildDirectory.file("sim-classpath.txt")
                    doLast { outFile.get().asFile.writeText(System.getProperty("java.home") + "\n" + testTask.get().classpath.asPath + "\n") }
                }
            }
        }
    }
}
