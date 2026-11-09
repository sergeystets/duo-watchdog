import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "local.duowatchdog"
version = "0.7.0"

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

// Compile against the IDEA already installed: -PideaPath=... (or IDEA_HOME) names it, otherwise the usual places are
// searched. CI, which has none, names a version to download instead: -PideaVersion=2025.3.1.
val downloadedIdea: String? = providers.gradleProperty("ideaVersion").orNull
val requestedIdea: String? = providers.gradleProperty("ideaPath").orElse(providers.environmentVariable("IDEA_HOME")).orNull
val installedIdea: String? = when {
    downloadedIdea != null -> null
    requestedIdea != null -> requestedIdea.takeIf { isIdea(File(it)) }
    else -> findInstalledIdea()
}

// Without an IDEA only this module's tasks fail, so the rest of the build (the VS Code module) still runs.
if (installedIdea == null && downloadedIdea == null) {
    val why = if (requestedIdea != null) {
        "Duo Watchdog: no IntelliJ IDEA at $requestedIdea (no product-info.json there)."
    } else {
        "Duo Watchdog: no installed IntelliJ IDEA found. Install IDEA 2024.3 or later, " +
            "or point to it with -PideaPath=\"/path/to/IDEA\" or the IDEA_HOME environment variable."
    }
    gradle.taskGraph.whenReady {
        if (allTasks.any { it.project == project }) throw GradleException(why)
    }
}

/** The IDE's product-info.json as text, or null when it has none. */
fun productInfo(idea: String): String? =
    listOf("product-info.json", "Contents/Resources/product-info.json")
        .map { File(idea, it) }
        .firstOrNull { it.isFile }
        ?.readText()

/** The IDE's branch from its product-info.json, e.g. 253 for 2025.3, or null when it cannot be read. */
fun ideaBranch(idea: String): Int? =
    productInfo(idea)
        ?.let { Regex("\"buildNumber\"\\s*:\\s*\"(?:[A-Z]+-)?(\\d+)").find(it) }
        ?.groupValues?.get(1)?.toIntOrNull()

/** The folder the IDE loads plugins from: <config>/plugins on Windows and macOS, the data folder itself on Linux. */
fun pluginsDir(idea: String): File {
    val dataDir = productInfo(idea)
        ?.let { Regex("\"dataDirectoryName\"\\s*:\\s*\"([^\"]+)\"").find(it) }
        ?.groupValues?.get(1)
        ?: throw GradleException("Duo Watchdog: cannot read dataDirectoryName from the product-info.json of $idea.")
    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.startsWith("windows") -> File(System.getenv("APPDATA") ?: "$home/AppData/Roaming", "JetBrains/$dataDir/plugins")
        os.startsWith("mac") -> File(home, "Library/Application Support/JetBrains/$dataDir/plugins")
        else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "JetBrains/$dataDir")
    }
}

/** The branch of a version, 253 for 2025.3.1, or null when it is not one. */
fun versionBranch(version: String): Int? =
    Regex("^20(\\d\\d)\\.(\\d)").find(version)?.let { it.groupValues[1].toInt() * 10 + it.groupValues[2].toInt() }

// IDEs from 2026.2 (branch 262) on are compiled for Java 25, which a JDK 21 javac cannot read, so they are
// compiled against with JDK 25. The classes stay Java 21 either way, so the plugin still installs on 2024.3.
val compileBranch = downloadedIdea?.let(::versionBranch) ?: installedIdea?.let(::ideaBranch) ?: 0
val compileJdk = if (compileBranch >= 262) 25 else 21

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(compileJdk)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    // The sources are UTF-8. Left unsaid, javac on Windows may read them as Windows-1252, and every "·" shows as "Â·".
    options.encoding = "UTF-8"
}

// What's New: the page and its pictures live with the VS Code extension's media, and both show them.
tasks.processResources {
    from(rootProject.file("vscode/media/whats-new")) {
        into("whatsnew")
    }
}

dependencies {
    implementation(project(":intellij:core"))
    intellijPlatform {
        if (downloadedIdea != null) {
            logger.lifecycle("Duo Watchdog: compiling against IntelliJ IDEA $downloadedIdea, downloaded, with JDK $compileJdk")
            intellijIdea(downloadedIdea)
        } else if (installedIdea != null) {
            logger.lifecycle("Duo Watchdog: compiling against the installed IDE at $installedIdea with JDK $compileJdk")
            local(installedIdea)
        }
        // ./gradlew verifyPlugin checks the built plugin against the IDEs below with JetBrains' Plugin Verifier.
        pluginVerifier()
        // Usage's Duo CLI quick actions open a terminal tab; an optional dependency in plugin.xml.
        bundledPlugin("org.jetbrains.plugins.terminal")
    }
}

// The jar and the plugin zip keep the product's name rather than this module's (intellij); the installers
// look for duo-watchdog-<version>.jar and a duo-watchdog folder.
base {
    archivesName = "duo-watchdog"
}

// The plugin's final jar takes its whole file name from the module, not from archivesName.
tasks.named<AbstractArchiveTask>("composedJar") {
    archiveFileName = "duo-watchdog-${project.version}.jar"
}

intellijPlatform {
    projectName = "duo-watchdog"
    pluginConfiguration {
        ideaVersion {
            // Installs on 2024.3 and every later IDE, whichever IDE it was compiled against.
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
    // The oldest IntelliJ IDEA the plugin installs on (sinceBuild) and the newest released, checked by
    // ./gradlew verifyPlugin: a call to anything either of them lacks fails it. CI runs it before every release.
    // -PverifyOldest / -PverifyNewest name others.
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaCommunity, providers.gradleProperty("verifyOldest").getOrElse("2024.3.7"))
            create(IntelliJPlatformType.IntellijIdea, providers.gradleProperty("verifyNewest").getOrElse("2026.3"))
        }
    }
}

/** Unpacks the built plugin where Install Plugin from Disk would, replacing an older Duo Watchdog. */
abstract class InstallPlugin : DefaultTask() {
    @get:InputFile
    abstract val pluginZip: RegularFileProperty

    @get:Internal
    abstract val idea: Property<String>

    @get:Internal
    abstract val pluginsDir: DirectoryProperty

    @get:Inject
    abstract val files: FileSystemOperations

    @get:Inject
    abstract val archives: ArchiveOperations

    @TaskAction
    fun install() {
        val ideaDir = File(idea.get()).canonicalFile.toPath()
        // The IDE's own process, but not its bundled JBR, which may be running this very Gradle.
        val running = ProcessHandle.allProcesses().anyMatch { process ->
            process.info().command()
                .map { File(it).toPath().let { command -> command.startsWith(ideaDir) && "jbr" !in command.toString() } }
                .orElse(false)
        }
        if (running) {
            throw GradleException("Duo Watchdog: close the IDE at ${idea.get()} first; its plugins can be replaced only while it is closed.")
        }
        val target = pluginsDir.get().asFile
        files.delete { delete(File(target, "duo-watchdog")) }
        files.copy {
            from(archives.zipTree(pluginZip))
            into(target)
        }
        logger.lifecycle("Duo Watchdog: installed into $target. Start the IDE to use it.")
    }
}

// install.sh and install.bat run this after asking which IDE; ./gradlew installPlugin -PideaPath=... runs it directly.
tasks.register<InstallPlugin>("installPlugin") {
    group = "intellij platform"
    description = "Builds Duo Watchdog and installs it into the IntelliJ IDEA it is compiled against."
    pluginZip = tasks.buildPlugin.flatMap { it.archiveFile }
    if (installedIdea != null) idea = installedIdea
    // -PpluginsDir=... for an IDE whose idea.properties moves its plugins (idea.plugins.path).
    pluginsDir = layout.dir(providers.gradleProperty("pluginsDir").map(::File).orElse(provider { pluginsDir(installedIdea!!) }))
}
