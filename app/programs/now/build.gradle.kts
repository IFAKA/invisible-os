plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
// Now and Quick switches, two tools. A program depends only on :oskit.
android {
    namespace = "com.faka.invisible.programs.now"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":oskit"))
    testImplementation("junit:junit:4.13.2")
}
