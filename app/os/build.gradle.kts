import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
// Build stamp shown at the bottom of the app: short commit, "+" when tracked files have uncommitted edits, build time.
fun git(vararg a: String) = try { ProcessBuilder("git", *a).directory(rootDir).redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim() } catch (e: Exception) { "" }
val stamp = (git("rev-parse", "--short", "HEAD").ifEmpty { "nogit" } + (if (git("status", "--porcelain", "-uno").isNotEmpty()) "+" else "") +
    " " + SimpleDateFormat("MMM d HH:mm").format(Date()))
android {
    namespace = "com.faka.invisible"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.faka.invisible"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "BUILD_STAMP", "\"$stamp\"")
        ndk { abiFilters += "arm64-v8a" }   // the phone is arm64; keeps sherpa-onnx + onnxruntime to one ABI
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17"; freeCompilerArgs += "-Xskip-metadata-version-check" }   // litertlm is built with Kotlin 2.4
}
dependencies {
    implementation(project(":oskit"))
    implementation(project(":programs:echo"))
    implementation(project(":programs:guard"))
    implementation(project(":programs:duel"))
    implementation(project(":programs:compass"))
    implementation(project(":programs:practice"))
    implementation(project(":programs:write"))
    implementation(project(":adapters"))
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    // On-device neural voice (sherpa-onnx, Apache-2.0). The AAR is the official release build, kept in libs/.
    implementation(files("../libs/sherpa-onnx-1.12.11.aar"))
    // Unpacks the downloaded model archives (.tar.bz2). Jars are from the Gradle distribution (Apache-2.0).
    implementation(files("../libs/commons-compress-1.26.1.jar", "../libs/commons-io-2.15.1.jar", "../libs/commons-lang3-3.14.0.jar"))
    testImplementation("junit:junit:4.13.2")
}
