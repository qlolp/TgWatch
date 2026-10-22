// Disposable emulator tooling only; this APK is never part of a TgWatch release.
plugins { id("com.android.application") }
android {
    namespace = "ru.tgwatch.testui"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "ru.tgwatch.testui"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
androidComponents { beforeVariants(selector().withBuildType("release")) { it.enable = false } }
