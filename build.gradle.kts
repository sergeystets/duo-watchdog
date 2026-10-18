plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "local.duowatchdog"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

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
        .filter { File(it, "product-info.json").isFile || File(it, "Contents/Resources/product-info.json").isFile }
        .maxByOrNull { it.lastModified() }
        ?.path
}

// Compile against the IDEA already installed rather than download one: -PideaPath=... (or IDEA_HOME) names it,
// otherwise the usual places are searched. Only when none is found is IDEA 2024.3 downloaded (~1 GB).
val installedIdea: String? = providers.gradleProperty("ideaPath")
    .orElse(providers.environmentVariable("IDEA_HOME"))
    .orNull
    ?: findInstalledIdea()

dependencies {
    implementation(project(":core"))
    intellijPlatform {
        if (installedIdea != null) {
            logger.lifecycle("Duo Watchdog: compiling against the installed IDE at $installedIdea")
            local(installedIdea)
        } else {
            intellijIdeaCommunity("2024.3")
        }
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
