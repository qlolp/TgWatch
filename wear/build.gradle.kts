import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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

extra["auditSourceVersion"] = sourceVersion
apply(from = rootProject.file("gradle/runtime-audit.gradle.kts"))
