plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}
// The framework: Voice Kit, Logic Kit, UI Kit and the interfaces of programs and adapters. It depends on nothing in this project.
android {
    namespace = "com.faka.invisible.oskit"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    publishing { singleVariant("release") { withSourcesJar() } }
    kotlinOptions { jvmTarget = "17"; freeCompilerArgs += "-Xskip-metadata-version-check" }
}
dependencies {
    // The neural voice engine uses sherpa-onnx. A library cannot bundle a local AAR, so it is compile-only here and the OS app packs it.
    compileOnly(files("../libs/sherpa-onnx-1.12.11.aar"))
    compileOnly(files("../libs/commons-compress-1.26.1.jar", "../libs/commons-io-2.15.1.jar", "../libs/commons-lang3-3.14.0.jar"))
    testImplementation("junit:junit:4.13.2")
}

// ---- SDK: publish the framework so other people can write programs (see sdk/GUIDE.md). ----
// com.faka.invisible:oskit:<version>           the library (release AAR + sources)
// com.faka.invisible:oskit-kit-rules:<version>  a zip with voicekit-rules.tsv and kit-rules.gradle.kts, so an outside build can run the Kit gate
// The sherpa AAR and the commons jars are compileOnly above, so they are NOT in the POM: the OS app packs them at runtime.
// A program never needs them to build or test.
val sdkVersion = "0.1.0"
val kitRulesZip by tasks.registering(Zip::class) {
    archiveBaseName.set("oskit-kit-rules"); archiveVersion.set(sdkVersion)
    destinationDirectory.set(layout.buildDirectory.dir("sdk-zip"))
    from(rootProject.file("voicekit-rules.tsv")); from(rootProject.file("gradle/kit-rules.gradle.kts"))
}
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = "com.faka.invisible"; artifactId = "oskit"; version = sdkVersion
                from(components["release"])
            }
            create<MavenPublication>("kitRules") {
                groupId = "com.faka.invisible"; artifactId = "oskit-kit-rules"; version = sdkVersion
                artifact(kitRulesZip)
            }
        }
        repositories { maven { name = "sdkRepo"; url = uri(rootProject.layout.buildDirectory.dir("sdk-repo")) } }
    }
}
