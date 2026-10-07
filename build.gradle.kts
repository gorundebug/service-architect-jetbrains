plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.gorundebug.servicearchitect"
version = "0.1.9"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val localIde = providers.gradleProperty("localIde").orNull
        if (localIde != null) local(localIde) else goland("2026.1.3")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

val cliBundle by tasks.registering(org.gradle.api.tasks.bundling.Zip::class) {
    archiveFileName.set("sa-python-dsl.zip")
    destinationDirectory.set(layout.buildDirectory.dir("cli-bundle"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(providers.gradleProperty("cliSource").orElse("../sa-python-dsl")) {
        include("pyproject.toml", "README.md", "src/**")
        exclude("**/__pycache__/**", "**/*.pyc", "**/*.egg-info/**")
    }
    doFirst {
        val source = file(providers.gradleProperty("cliSource").orElse("../sa-python-dsl").get())
        require(source.resolve("pyproject.toml").isFile && source.resolve("src/sa_dsl/cli.py").isFile) {
            "CLI source is missing. Check out sa-python-dsl alongside this project or set -PcliSource=/path/to/sa-python-dsl"
        }
    }
}

tasks.named<ProcessResources>("processResources") {
    from(cliBundle.flatMap { it.archiveFile }) { into("cli") }
}
