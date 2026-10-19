plugins {
    // Downloads JDK 21 when the machine has none.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "duo-watchdog"

include("core")
