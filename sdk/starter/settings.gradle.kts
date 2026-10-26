pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    // Same Android Gradle Plugin as the OS build (8.13.0). Resolved by its jar, so it also works offline.
    resolutionStrategy { eachPlugin { if (requested.id.id == "com.android.library") useModule("com.android.tools.build:gradle:8.13.0") } }
}
dependencyResolutionManagement {
    repositories {
        // Where the published :oskit lives. Inside our repo this is app/build/sdk-repo (made by `cd app && ./gradlew :oskit:publishAllPublicationsToSdkRepoRepository`).
        // Outside, give your own folder with -PsdkRepo=PATH, or publish to mavenLocal.
        val sdkRepo = providers.gradleProperty("sdkRepo").orNull ?: "../../app/build/sdk-repo"
        maven { url = uri(file(sdkRepo)) }
        mavenLocal()
        google(); mavenCentral()
    }
}
rootProject.name = "counter"
