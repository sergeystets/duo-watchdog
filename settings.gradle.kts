plugins {
    // Downloads JDK 21 when the machine has none.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "duo-watchdog"

// The IntelliJ plugin with its IDE-free core, and the VS Code extension, whose npm build :vscode runs.
include(":intellij", ":intellij:core", ":vscode")
