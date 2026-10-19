import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("io.gitlab.arturbosch.detekt")
}

val releaseStore = providers.environmentVariable("TGWATCH_KEYSTORE_PATH").orNull
val versionLines = rootProject.file("version.properties").readLines()
    .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
require(versionLines.size == 1 && versionLines.single().startsWith("version=")) {
    "version.properties requires exactly one version key and no overrides"
}
val sourceVersion = versionLines.single().removePrefix("version=")
require(sourceVersion.matches(Regex("(0|[1-9][0-9]{0,3})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})"))) {
    "Version must be canonical major.minor.patch with minor/patch in 0..999"
}
val versionParts = sourceVersion.split(".").map { it.toLong() }
val sourceVersionCode = versionParts[0] * 1_000_000L + versionParts[1] * 1_000L + versionParts[2]
require(sourceVersionCode in 1L..2147483647L) { "Version exceeds Android versionCode range" }
require(!providers.gradleProperty("versionCode").isPresent && !providers.gradleProperty("versionName").isPresent &&
    !providers.environmentVariable("TGWATCH_VERSION_CODE").isPresent &&
    !providers.environmentVariable("TGWATCH_VERSION_NAME").isPresent) { "Version overrides are unsupported" }
android {
    namespace = "ru.tgwatch.wear"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "ru.tgwatch.wear"
        minSdk = 30
        targetSdk = 35
        versionCode = sourceVersionCode.toInt()
        versionName = sourceVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        if (!releaseStore.isNullOrBlank()) create("production") {
            storeFile = file(releaseStore)
            storePassword = System.getenv("TGWATCH_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("TGWATCH_KEY_ALIAS")
            keyPassword = System.getenv("TGWATCH_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (!releaseStore.isNullOrBlank()) signingConfig = signingConfigs.getByName("production")
        }
    }
    packaging {
        dex {
            // AGP stores dex uncompressed for minSdk >= 28: on this watch that costs more than a
            // megabyte of transfer and storage for a small cold-start gain. Compressed dex keeps
            // the standalone APK close to its code size; revisit with R8 before changing this.
            useLegacyPackaging = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint { abortOnError = true; disable += "GradleDependency" }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":probe-core"))
    implementation("androidx.wear.tiles:tiles:1.6.2")
    implementation("androidx.wear.protolayout:protolayout:1.4.2")
    implementation("androidx.wear.watchface:watchface-complications-data-source:1.3.0")
    implementation("androidx.concurrent:concurrent-futures:1.3.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

// Wear sources use the same rules as the phone app and must pass without a baseline.
detekt {
    toolVersion = "1.23.8"
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    source.setFrom(files("src/main/java"))
}
configurations.matching { it.name == "detekt" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") useVersion(io.gitlab.arturbosch.detekt.getSupportedKotlinVersion())
    }
}

extra["auditSourceVersion"] = sourceVersion
apply(from = rootProject.file("gradle/runtime-audit.gradle.kts"))
