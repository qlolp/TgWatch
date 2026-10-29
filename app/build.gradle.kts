import org.gradle.api.tasks.testing.Test
import groovy.json.JsonOutput
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    id("com.android.application")
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
    namespace = "ru.tgwatch"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "ru.tgwatch"
        minSdk = 26
        targetSdk = 36
        versionCode = sourceVersionCode.toInt()
        versionName = sourceVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { buildConfig = true }
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
    testOptions { unitTests.isReturnDefaultValues = true }
    lint {
        abortOnError = true
        // Dependency versions are pinned and audited separately.
        disable += setOf("GradleDependency")
    }
}
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
dependencies {
    implementation(project(":probe-core"))
    implementation("androidx.core:core:1.19.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

extra["auditSourceVersion"] = sourceVersion
apply(from = rootProject.file("gradle/runtime-audit.gradle.kts"))

// Existing findings are recorded, not silently disabled or reformatted wholesale.
// Run detekt on new changes; regenerate the baseline only after reviewing its diff.
detekt {
    toolVersion = "1.23.8"
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    baseline = rootProject.file("config/detekt/baseline.xml")
    source.setFrom(files("src/main/java"))
}
// detekt 1.23 runs its own embedded Kotlin compiler; keep the Kotlin Gradle plugin's
// version alignment from pulling the project's newer Kotlin onto detekt's classpath.
configurations.matching { it.name == "detekt" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") useVersion(io.gitlab.arturbosch.detekt.getSupportedKotlinVersion())
    }
}

// A separate fork tests the real maximum v3 archive without running the entire unit suite.
tasks.register<Test>("targetBackupHeapTest") {
    group = "verification"
    description = "Round-trip the largest v3 backup in a 128 MiB JVM"
    dependsOn("compileDebugUnitTestSources")
    val unit = tasks.named<Test>("testDebugUnitTest").get()
    testClassesDirs = unit.testClassesDirs
    classpath = unit.classpath
    maxHeapSize = "128m"
    maxParallelForks = 1
    systemProperty("tgwatch.backup.heap.limit", 128L * 1024 * 1024)
    filter { includeTestsMatching("ru.tgwatch.TargetBackupCodecTest.maximumBackupFits128MiB") }
}

// Canonical 65k retention must remain cheap even when the history cache is invalidated.
tasks.register<Test>("historyRetentionHeapTest") {
    group = "verification"
    description = "Verify bounded 65k history retention in a 64 MiB JVM"
    dependsOn("compileDebugUnitTestSources")
    val unit = tasks.named<Test>("testDebugUnitTest").get()
    testClassesDirs = unit.testClassesDirs
    classpath = unit.classpath
    maxHeapSize = "64m"
    maxParallelForks = 1
    filter {
        includeTestsMatching("ru.tgwatch.TimelineRetentionTest")
        includeTestsMatching("ru.tgwatch.HistoryPreparationAllocationTest")
    }
}
