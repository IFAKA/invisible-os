plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
// The framework: Voice Kit, Logic Kit, UI Kit and the interfaces of programs and adapters. It depends on nothing in this project.
android {
    namespace = "com.faka.invisible.oskit"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17"; freeCompilerArgs += "-Xskip-metadata-version-check" }
}
dependencies {
    // The neural voice engine uses sherpa-onnx. A library cannot bundle a local AAR, so it is compile-only here and the OS app packs it.
    compileOnly(files("../libs/sherpa-onnx-1.12.11.aar"))
    compileOnly(files("../libs/commons-compress-1.26.1.jar", "../libs/commons-io-2.15.1.jar", "../libs/commons-lang3-3.14.0.jar"))
    testImplementation("junit:junit:4.13.2")
}
