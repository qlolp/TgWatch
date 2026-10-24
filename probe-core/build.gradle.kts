plugins {
    id("org.jetbrains.kotlin.jvm")
    id("io.gitlab.arturbosch.detekt")
}

group = "ru.tgwatch"
version = rootProject.file("version.properties").readLines()
    .single { it.startsWith("version=") }.removePrefix("version=")
kotlin { jvmToolchain(17) }
dependencies { testImplementation("junit:junit:4.13.2") }
tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    // Signing runs with umask077. Archive metadata must match the audited JAR
    // even when the compiler created private files/directories on that runner.
    filePermissions { unix("rw-r--r--") }
    dirPermissions { unix("rwxr-xr-x") }
}

// Transport code is analyzed with the same rules and baseline as the phone app.
detekt {
    toolVersion = "1.23.8"
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    baseline = rootProject.file("config/detekt/baseline.xml")
    source.setFrom(files("src/main/kotlin"))
}
configurations.matching { it.name == "detekt" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") useVersion(io.gitlab.arturbosch.detekt.getSupportedKotlinVersion())
    }
}
