plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
// Reader, a tool: reads a shared or copied text aloud, paragraph by paragraph. A program depends only on :oskit.
android {
    namespace = "com.faka.invisible.programs.reader"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":oskit"))
    testImplementation("junit:junit:4.13.2")
}
