plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.gorundebug.servicearchitect"
version = "0.1.0"

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
