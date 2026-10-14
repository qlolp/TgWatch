import groovy.json.JsonOutput
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
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
    namespace = "ru.tgwatch"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "ru.tgwatch"
        minSdk = 26
        targetSdk = 35
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
        // Existing native UI still has inline copy; new Android target is checked in source.
        disable += setOf("GradleDependency", "HardcodedText")
    }
}
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

// Resolve all audit scopes once. The runtime artifact inventory and OSV graph
// share the same resolution; declarations are never treated as selected versions.
tasks.register("dependencyInventory") {
    val resolvedReport = layout.buildDirectory.file("reports/dependencies/resolved.json")
    val runtimeInventory = layout.buildDirectory.file("reports/sbom/release-runtime-inventory.json")
    outputs.files(resolvedReport, runtimeInventory)
    outputs.upToDateWhen { false }
    doLast {
        val scopes = listOf("releaseRuntimeClasspath", "debugUnitTestRuntimeClasspath", "debugAndroidTestRuntimeClasspath")
        val packages = sortedMapOf<String, MutableMap<String, Any>>()
        val graphs = sortedMapOf<String, List<Map<String, Any>>>()
        for (scope in scopes) {
            val graph = configurations.getByName(scope).incoming.resolutionResult
            check(graph.allDependencies.none { it is UnresolvedDependencyResult }) { "Unresolved dependencies in $scope" }
            fun key(id: org.gradle.api.artifacts.component.ComponentIdentifier): String {
                if (id == graph.root.id) return "ru.tgwatch:$scope"
                check(id is ModuleComponentIdentifier) { "Unsupported non-Maven dependency in $scope: $id" }
                return "${id.group}:${id.module}:${id.version}"
            }
            configurations.getByName(scope).incoming.artifacts.artifacts.forEach { artifact ->
                // Unit/Android test classpaths include :app's own classes.jar.
                // It is represented by the graph root, not an external package.
                check(artifact.id.componentIdentifier is ModuleComponentIdentifier || artifact.id.componentIdentifier == graph.root.id) {
                    "Cannot silently omit a project/file artifact in $scope: ${artifact.id}"
                }
            }
            for (component in graph.allComponents) {
                val id = component.id as? ModuleComponentIdentifier ?: continue
                val entry = packages.getOrPut(key(id)) { mutableMapOf("group" to id.group, "name" to id.module,
                    "version" to id.version, "scopes" to mutableListOf<String>()) }
                @Suppress("UNCHECKED_CAST")
                (entry["scopes"] as MutableList<String>).add(scope)
            }
            graphs[scope] = graph.allComponents.map { component -> mapOf<String, Any>(
                "ref" to key(component.id),
                "dependsOn" to component.dependencies.filterIsInstance<ResolvedDependencyResult>()
                    .map { key(it.selected.id) }.distinct().sorted()) }.sortedBy { it["ref"].toString() }
        }
        check(packages.values.any { "releaseRuntimeClasspath" in (it["scopes"] as List<*>) }) { "Empty runtime graph" }
        fun write(output: java.io.File, data: Any) {
            output.parentFile.mkdirs()
            output.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(data)) + "\n")
        }
        write(resolvedReport.get().asFile, mapOf("packages" to packages.values.toList(), "graphs" to graphs))
        val artifacts = configurations.getByName("releaseRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
        val resolved = artifacts.sortedBy { it.file.absolutePath }.map { artifact ->
            val id = artifact.moduleVersion.id
            check(artifact.id.componentIdentifier is ModuleComponentIdentifier) { "Unsupported runtime artifact: ${artifact.id}" }
            mapOf("group" to id.group, "name" to id.name, "version" to id.version,
                "type" to artifact.extension, "classifier" to (artifact.classifier ?: ""), "file" to artifact.file.absolutePath)
        }
        write(runtimeInventory.get().asFile, mapOf("applicationVersion" to android.defaultConfig.versionName,
            "artifacts" to resolved, "runtimeGraph" to graphs.getValue("releaseRuntimeClasspath")))
    }
}

// Hash the exact resolved JAR/AAR bytes and validate the CycloneDX runtime graph.
tasks.register("generateReleaseSbom") {
    dependsOn("dependencyInventory")
    val inventory = layout.buildDirectory.file("reports/sbom/release-runtime-inventory.json")
    val sbom = layout.buildDirectory.file("reports/sbom/TgWatch.sbom.cdx.json")
    inputs.file(inventory)
    inputs.files(configurations.named("releaseRuntimeClasspath"))
    inputs.files(rootProject.fileTree("scripts") { include("*.py") })
    inputs.dir(rootProject.file("scripts/schemas"))
    outputs.file(sbom)
    doLast {
        project.exec {
            commandLine("python3", rootProject.file("scripts/generate-sbom.py"), inventory.get().asFile, sbom.get().asFile)
        }
    }
}

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
