plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
// Claude bridge, a tool: answer Claude Code on the laptop from the earbud. A program depends only on :oskit.
android {
    namespace = "com.faka.invisible.programs.claude"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":oskit"))
    testImplementation("junit:junit:4.13.2")
}
