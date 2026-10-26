plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
// Write and Sum: the stereo-pair keyboard. A program depends only on :oskit.
android {
    namespace = "com.faka.invisible.programs.write"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":oskit"))
    implementation("com.google.mlkit:translate:17.0.3")
    testImplementation("junit:junit:4.13.2")
}
