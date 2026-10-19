// The VS Code extension builds with npm (see package.json); these tasks run npm so that the root build's
// test, build and clean cover it too. Needs Node.js 20+ with npm on the PATH.
plugins {
    base
}

val windows = System.getProperty("os.name").lowercase().startsWith("windows")
val npm = if (windows) "npm.cmd" else "npm"

version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(file("package.json").readText())!!.groupValues[1]
val vsix = layout.projectDirectory.file("duo-watchdog-$version.vsix")

val npmCi by tasks.registering(Exec::class) {
    group = "vs code"
    description = "Installs the extension's npm dependencies (npm ci)."
    inputs.files("package.json", "package-lock.json")
    outputs.dir("node_modules")
    commandLine(npm, "ci", "--no-audit", "--no-fund", "--loglevel=error")
}

val test by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the extension's tests (npm test), no VS Code needed."
    dependsOn(npmCi)
    commandLine(npm, "test")
}

tasks.check {
    dependsOn(test)
}

val packageExtension by tasks.registering(Exec::class) {
    group = "vs code"
    description = "Packages the extension into vscode/duo-watchdog-<version>.vsix (npm run package)."
    dependsOn(npmCi)
    inputs.dir("src")
    inputs.dir("media")
    inputs.files("package.json", "package-lock.json", "tsconfig.json", ".vscodeignore", "README.md")
    outputs.file(vsix)
    commandLine(npm, "run", "package")
}

tasks.assemble {
    dependsOn(packageExtension)
}

// -PcodePath=... picks another CLI, e.g. code-insiders, codium or cursor.
tasks.register<Exec>("installExtension") {
    group = "vs code"
    description = "Packages the extension and installs it into VS Code (code --install-extension)."
    dependsOn(packageExtension)
    commandLine(
        providers.gradleProperty("codePath").getOrElse(if (windows) "code.cmd" else "code"),
        "--install-extension", vsix.asFile.path, "--force",
    )
}

tasks.clean {
    delete("out", vsix)
}
