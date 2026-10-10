import groovy.json.JsonOutput
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult

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
        versionCode = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull()?.plus(100) ?: 10
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
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// Selected Maven versions, including transitives: declarations alone are not an inventory.
tasks.register("dependencyInventory") {
    val reportDir = layout.buildDirectory.dir("reports/dependencies")
    outputs.dir(reportDir)
    outputs.upToDateWhen { false }
    doLast {
        val scopes = listOf("releaseRuntimeClasspath", "debugUnitTestRuntimeClasspath", "debugAndroidTestRuntimeClasspath")
        val packages = sortedMapOf<String, MutableMap<String, Any>>()
        val runtime = configurations.getByName("releaseRuntimeClasspath").incoming.resolutionResult
        for (scope in scopes) {
            val graph = configurations.getByName(scope).incoming.resolutionResult
            check(graph.allDependencies.none { it is UnresolvedDependencyResult }) { "Unresolved dependencies in $scope" }
            for (component in graph.allComponents) {
                val id = component.id as? ModuleComponentIdentifier ?: continue
                val key = "${id.group}:${id.module}:${id.version}"
                val entry = packages.getOrPut(key) { mutableMapOf("group" to id.group, "name" to id.module,
                    "version" to id.version, "scopes" to mutableListOf<String>()) }
                @Suppress("UNCHECKED_CAST")
                (entry["scopes"] as MutableList<String>).add(scope)
            }
        }
        check(packages.values.any { "releaseRuntimeClasspath" in (it["scopes"] as List<*>) }) { "Empty runtime graph" }
        val directory = reportDir.get().asFile.apply { mkdirs() }
        fun write(name: String, data: Any) = directory.resolve(name).writeText(JsonOutput.prettyPrint(JsonOutput.toJson(data)) + "\n")
        write("resolved.json", mapOf("packages" to packages.values.toList()))
        fun ref(id: ModuleComponentIdentifier) = "pkg:maven/${id.group}/${id.module}@${id.version}"
        val modules = runtime.allComponents.filter { it.id is ModuleComponentIdentifier }
        val edges = runtime.allComponents.map { component -> mapOf(
            "ref" to ((component.id as? ModuleComponentIdentifier)?.let(::ref) ?: "tgwatch"),
            "dependsOn" to component.dependencies.filterIsInstance<ResolvedDependencyResult>()
                .mapNotNull { (it.selected.id as? ModuleComponentIdentifier)?.let(::ref) }.distinct().sorted()) }
        write("runtime.cdx.json", mapOf("bomFormat" to "CycloneDX", "specVersion" to "1.5", "version" to 1,
            "metadata" to mapOf("component" to mapOf("type" to "application", "bom-ref" to "tgwatch", "name" to "TgWatch", "version" to android.defaultConfig.versionName)),
            "components" to modules.map { component -> val id = component.id as ModuleComponentIdentifier
                mapOf("type" to "library", "bom-ref" to ref(id), "group" to id.group, "name" to id.module, "version" to id.version, "purl" to ref(id)) },
            "dependencies" to edges))
    }
}
