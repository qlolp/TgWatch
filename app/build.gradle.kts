plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun resolveAppVersion(root: java.io.File): Pair<Int, String> {
    val envName = System.getenv("TGWATCH_VERSION_NAME")
    val envCode = System.getenv("TGWATCH_VERSION_CODE")?.toIntOrNull()
    if (!envName.isNullOrBlank() && envCode != null) return envCode to envName

    val tag = try {
        ProcessBuilder("git", "describe", "--tags", "--match", "v*", "--abbrev=0")
            .directory(root)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .inputStream.bufferedReader().readText().trim()
    } catch (_: Exception) {
        ""
    }
    val match = Regex("""v(\d+)\.(\d+)\.(\d+)""").matchEntire(tag)
    if (match != null) {
        val (maj, min, pat) = match.destructured
        val code = maj.toInt() * 10_000 + min.toInt() * 100 + pat.toInt()
        return code to "$maj.$min.$pat"
    }
    return 10_700 to "1.7.0"
}

val (resolvedVersionCode, resolvedVersionName) = resolveAppVersion(rootProject.projectDir)
val releaseStore = providers.environmentVariable("TGWATCH_KEYSTORE_PATH").orNull

android {
    namespace = "ru.tgwatch"
    compileSdk = 35
    defaultConfig {
        applicationId = "ru.tgwatch"
        minSdk = 26
        targetSdk = 35
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (!releaseStore.isNullOrBlank()) {
            create("production") {
                storeFile = file(releaseStore)
                storePassword = System.getenv("TGWATCH_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TGWATCH_KEY_ALIAS")
                keyPassword = System.getenv("TGWATCH_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // Unsigned local assembly is allowed; publication requires the production key.
            if (!releaseStore.isNullOrBlank()) signingConfig = signingConfigs.getByName("production")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint {
        abortOnError = true
        disable += setOf("GradleDependency")
    }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
