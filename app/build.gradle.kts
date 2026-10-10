plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStore = providers.environmentVariable("TGWATCH_KEYSTORE_PATH").orNull
val ciRunNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull
val ciVersionCode = ciRunNumber?.let { value ->
    require(value.matches(Regex("[1-9][0-9]*"))) {
        "GITHUB_RUN_NUMBER must be a positive decimal integer"
    }
    val run = value.toLongOrNull()
    require(run != null && run <= 2147483547L) {
        "GITHUB_RUN_NUMBER + 100 exceeds the Android versionCode range"
    }
    (run + 100L).toInt()
}
android {
    namespace = "ru.tgwatch"
    compileSdk = 35
    defaultConfig {
        applicationId = "ru.tgwatch"
        minSdk = 26
        targetSdk = 34
        versionCode = ciVersionCode ?: 11
        versionName = "1.10"
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
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// Export actual resolved JAR/AAR artifacts, including transitives. The Python
// helper hashes these exact bytes and validates against the official local schema.
tasks.register("generateReleaseSbom") {
    val inventory = layout.buildDirectory.file("reports/sbom/release-runtime-inventory.json")
    val sbom = layout.buildDirectory.file("reports/sbom/TgWatch.sbom.cdx.json")
    inputs.files(configurations.named("releaseRuntimeClasspath"))
    inputs.files(rootProject.fileTree("scripts") { include("*.py") })
    inputs.dir(rootProject.file("scripts/schemas"))
    inputs.property("applicationVersion", android.defaultConfig.versionName ?: "")
    outputs.file(sbom)
    doLast {
        val artifacts = configurations.getByName("releaseRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
        val resolved = artifacts.sortedBy { it.file.absolutePath }.map { artifact ->
            val id = artifact.moduleVersion.id
            require(artifact.id.componentIdentifier is org.gradle.api.artifacts.component.ModuleComponentIdentifier) {
                "SBOM cannot silently omit a project or file dependency: ${artifact.id}"
            }
            mapOf("group" to id.group, "name" to id.name, "version" to id.version,
                "type" to artifact.extension, "classifier" to (artifact.classifier ?: ""),
                "file" to artifact.file.absolutePath)
        }
        val output = inventory.get().asFile
        output.parentFile.mkdirs()
        output.writeText(groovy.json.JsonOutput.toJson(mapOf(
            "applicationVersion" to android.defaultConfig.versionName,
            "artifacts" to resolved)))
        project.exec {
            commandLine("python3", rootProject.file("scripts/generate-sbom.py"), output, sbom.get().asFile)
        }
    }
}
