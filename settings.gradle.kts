plugins {
    // Downloads JDK 21 when the machine has none.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "duo-watchdog"

// The IntelliJ plugin and its IDE-free core. The VS Code extension in vscode/ builds with npm.
include(":intellij", ":intellij:core")
