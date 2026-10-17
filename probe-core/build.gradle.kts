plugins { id("org.jetbrains.kotlin.jvm") }

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
