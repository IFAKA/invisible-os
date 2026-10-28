plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
// Breathe, a tool: guided breathing and a sleep timer. A program depends only on :oskit.
android {
    namespace = "com.faka.invisible.programs.breathe"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation(project(":oskit"))
    testImplementation("junit:junit:4.13.2")
}
