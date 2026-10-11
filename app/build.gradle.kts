import groovy.json.JsonOutput
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult

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
        versionCode = ciVersionCode ?: 12
        versionName = "1.11"
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
