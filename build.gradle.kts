plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "local.duowatchdog"
version = "0.2.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

/** Whether the folder is an IDE install: it has a product-info.json. */
fun isIdea(dir: File): Boolean =
    File(dir, "product-info.json").isFile || File(dir, "Contents/Resources/product-info.json").isFile

/** The newest IntelliJ IDEA in the usual install places (plain and Toolbox, on macOS, Windows, Linux), or null. */
fun findInstalledIdea(): String? {
    val home = System.getProperty("user.home")
    val roots = listOf(
        "/Applications", "$home/Applications",
        "C:/Program Files/JetBrains", "${System.getenv("LOCALAPPDATA")}/Programs",
        "/opt", "$home/.local/share/JetBrains/Toolbox/apps",
    )
    return roots.asSequence()
        .map(::File)
        .filter { it.isDirectory }
        .flatMap { it.listFiles().orEmpty().asSequence() }
        .filter { it.name.startsWith("IntelliJ IDEA") || it.name.startsWith("idea") || it.name.startsWith("intellij-idea") }
        .filter(::isIdea)
        .maxByOrNull { it.lastModified() }
        ?.path
}

// Compile against the IDEA already installed, never a downloaded one: -PideaPath=... (or IDEA_HOME) names it,
// otherwise the usual places are searched.
val installedIdea: String = providers.gradleProperty("ideaPath")
    .orElse(providers.environmentVariable("IDEA_HOME"))
    .orNull
    ?.also { require(isIdea(File(it))) { "Duo Watchdog: no IntelliJ IDEA at $it (no product-info.json there)." } }
    ?: findInstalledIdea()
    ?: throw GradleException(
        "Duo Watchdog: no installed IntelliJ IDEA found. Install IDEA 2024.3 or later, " +
            "or point to it with -PideaPath=\"/path/to/IDEA\" or the IDEA_HOME environment variable."
    )

/** The IDE's branch from its product-info.json, e.g. 253 for 2025.3, or null when it cannot be read. */
fun ideaBranch(idea: String): Int? =
    listOf("product-info.json", "Contents/Resources/product-info.json")
        .map { File(idea, it) }
        .firstOrNull { it.isFile }
        ?.readText()
        ?.let { Regex("\"buildNumber\"\\s*:\\s*\"(?:[A-Z]+-)?(\\d+)").find(it) }
        ?.groupValues?.get(1)?.toIntOrNull()

// IDEs from 2026.2 (branch 262) on are compiled for Java 25, which a JDK 21 javac cannot read, so they are
// compiled against with JDK 25. The classes stay Java 21 either way, so the plugin still installs on 2024.3.
val compileJdk = if ((ideaBranch(installedIdea) ?: 0) >= 262) 25 else 21

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(compileJdk)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

dependencies {
    implementation(project(":core"))
    intellijPlatform {
        logger.lifecycle("Duo Watchdog: compiling against the installed IDE at $installedIdea with JDK $compileJdk")
        local(installedIdea)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            // Installs on 2024.3 and every later IDE, whichever IDE it was compiled against.
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
}
