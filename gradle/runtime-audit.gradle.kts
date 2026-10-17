import groovy.json.JsonOutput
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.process.ExecOperations
import javax.inject.Inject

val auditApplicationId = when (project.path) {
    ":app" -> "ru.tgwatch"
    ":wear" -> "ru.tgwatch.wear"
    else -> error("Runtime audits support only the reviewed phone and watch applications")
}
val auditPrefix = if (project.path == ":app") "TgWatch" else "TgWatch-wear"
val auditSourceVersion = project.extra["auditSourceVersion"] as String

// Resolve all audit scopes once. The runtime artifact inventory and OSV graph
// share the same resolution; declarations are never treated as selected versions.
tasks.register("dependencyInventory") {
    dependsOn(":probe-core:jar")
    val resolvedReport = layout.buildDirectory.file("reports/dependencies/resolved.json")
    val runtimeInventory = layout.buildDirectory.file("reports/sbom/release-runtime-inventory.json")
    outputs.files(resolvedReport, runtimeInventory)
    outputs.upToDateWhen { false }
    doLast {
        val scopes = listOf("releaseRuntimeClasspath", "debugUnitTestRuntimeClasspath", "debugAndroidTestRuntimeClasspath")
        fun localCore(id: org.gradle.api.artifacts.component.ComponentIdentifier): Boolean =
            id is ProjectComponentIdentifier && id.projectPath == ":probe-core" && id.build.buildPath == ":"
        fun localFields(): MutableMap<String, Any> = mutableMapOf("group" to "ru.tgwatch", "name" to "probe-core",
            "version" to auditSourceVersion, "kind" to "project", "projectPath" to ":probe-core", "buildPath" to ":")
        val packages = sortedMapOf<String, MutableMap<String, Any>>()
        val graphs = sortedMapOf<String, List<Map<String, Any>>>()
        for (scope in scopes) {
            val graph = configurations.getByName(scope).incoming.resolutionResult
            check(graph.allDependencies.none { it is UnresolvedDependencyResult }) { "Unresolved dependencies in $scope" }
            fun key(id: org.gradle.api.artifacts.component.ComponentIdentifier): String {
                if (id == graph.root.id) return "$auditApplicationId:$scope"
                if (localCore(id)) return "project::probe-core:$auditSourceVersion"
                check(id is ModuleComponentIdentifier) { "Unsupported non-Maven dependency in $scope: $id" }
                return "${id.group}:${id.module}:${id.version}"
            }
            configurations.getByName(scope).incoming.artifacts.artifacts.forEach { artifact ->
                // Unit/Android test classpaths include :app's own classes.jar.
                // It is represented by the graph root, not an external package.
                check(artifact.id.componentIdentifier is ModuleComponentIdentifier ||
                    artifact.id.componentIdentifier == graph.root.id || localCore(artifact.id.componentIdentifier)) {
                    "Cannot silently omit a project/file artifact in $scope: ${artifact.id}"
                }
            }
            for (component in graph.allComponents) {
                val id = component.id
                if (id == graph.root.id) continue
                val fields = if (localCore(id)) {
                    val actual = component.moduleVersion
                    check(actual?.group == "ru.tgwatch" && actual.name == "probe-core" && actual.version == auditSourceVersion) {
                        "Local core identity/version must match the application source"
                    }
                    localFields()
                } else {
                    check(id is ModuleComponentIdentifier) { "Unsupported dependency origin: $id" }
                    mutableMapOf<String, Any>("group" to id.group, "name" to id.module, "version" to id.version)
                }
                val entry = packages.getOrPut(key(id)) { fields.apply { put("scopes", mutableListOf<String>()) } }
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
        write(resolvedReport.get().asFile, mapOf("applicationVersion" to auditSourceVersion, "applicationId" to auditApplicationId,
            "packages" to packages.values.toList(), "graphs" to graphs))
        val artifacts = configurations.getByName("releaseRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
        val resolved = artifacts.sortedBy { it.file.absolutePath }.map { artifact ->
            val id = artifact.moduleVersion.id
            val local = localCore(artifact.id.componentIdentifier)
            check(artifact.id.componentIdentifier is ModuleComponentIdentifier || local) {
                "Unsupported runtime artifact: ${artifact.id}"
            }
            val fields = if (local) localFields() else mutableMapOf<String, Any>("group" to id.group,
                "name" to id.name, "version" to id.version)
            fields + mapOf("type" to artifact.extension, "classifier" to (artifact.classifier ?: ""),
                "file" to artifact.file.absolutePath)
        }
        write(runtimeInventory.get().asFile, mapOf("applicationVersion" to auditSourceVersion, "applicationId" to auditApplicationId,
            "artifacts" to resolved, "runtimeGraph" to graphs.getValue("releaseRuntimeClasspath")))
    }
}

// Project.exec is deprecated and removed in Gradle 9; tasks get ExecOperations injected instead.
interface InjectedExec { @get:Inject val exec: ExecOperations }
val injectedExec = objects.newInstance<InjectedExec>()

// Hash the exact resolved JAR/AAR bytes and validate the CycloneDX runtime graph.
tasks.register("generateReleaseSbom") {
    dependsOn("dependencyInventory")
    val inventory = layout.buildDirectory.file("reports/sbom/release-runtime-inventory.json")
    val sbom = layout.buildDirectory.file("reports/sbom/$auditPrefix.sbom.cdx.json")
    inputs.file(inventory)
    inputs.files(configurations.named("releaseRuntimeClasspath"))
    inputs.files(rootProject.fileTree("scripts") { include("*.py") })
    inputs.dir(rootProject.file("scripts/schemas"))
    outputs.file(sbom)
    doLast {
        injectedExec.exec.exec {
            commandLine("python3", rootProject.file("scripts/generate-sbom.py"), inventory.get().asFile, sbom.get().asFile)
        }
    }
}
