plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStore = providers.environmentVariable("TGWATCH_KEYSTORE_PATH").orNull
android {
    namespace = "ru.tgwatch"
    compileSdk = 35
    defaultConfig {
        applicationId = "ru.tgwatch"
        minSdk = 26
        targetSdk = 34
        versionCode = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull()?.plus(100) ?: 8
        versionName = "1.7"
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
        // Existing native Russian UI intentionally targets API 34 and has inline copy.
        disable += setOf("OldTargetApi", "GradleDependency", "HardcodedText")
    }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
