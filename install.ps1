# Installs or updates Duo Watchdog in IntelliJ IDEA and VS Code on this machine, asking where.
#
#   install.bat                 pick from the IntelliJ IDEA installs and VS Code CLIs found
#   install.bat "<IDEA folder>" offer that IntelliJ IDEA too, e.g. one in an unusual place
#
# IntelliJ IDEA gets the plugin built by gradlew installPlugin (needs JDK 17+), VS Code the extension
# packaged from vscode\ (needs Node.js 20+). Runs on the Windows PowerShell 5.1 that Windows 10 and 11 ship.
param([string]$IdeaPath)

# No $ErrorActionPreference = 'Stop': on PowerShell 5.1 it turns any stderr of code, npm or gradlew into a
# terminating error. Each call is checked by its result instead.
Set-Location $PSScriptRoot

function Say([string]$Text = '') { Write-Host $Text }
function Bold([string]$Text) { Write-Host $Text -ForegroundColor White }
# Stops at the end of the input, so a script fed by a pipe doesn't take every default forever.
function Ask([string]$Prompt) {
    $answer = Read-Host $Prompt
    if ($null -eq $answer) { Say; Say 'No more input; stopping.'; exit 1 }
    $answer
}
function YesDefault([string]$Prompt) { (Ask "$Prompt [Y/n]") -notmatch '^[nN]' }

$PluginVersion = (Select-String -Path build.gradle.kts -Pattern '^version = "(.*)"$').Matches[0].Groups[1].Value
$ExtensionVersion = ''
if (Test-Path vscode\package.json) { $ExtensionVersion = (Get-Content -Raw vscode\package.json | ConvertFrom-Json).version }

# --- IntelliJ IDEA ---------------------------------------------------------------------------------------

function Get-ProductInfo([string]$Dir) {
    $file = Join-Path $Dir 'product-info.json'
    if (Test-Path $file) { Get-Content -Raw $file | ConvertFrom-Json }
}

function Test-IntelliJ([string]$Dir) {
    $info = Get-ProductInfo $Dir
    $info -and ($info.productCode -in 'IU', 'IC')
}

function Get-PluginsDir([string]$Dir) {
    Join-Path $env:APPDATA "JetBrains\$((Get-ProductInfo $Dir).dataDirectoryName)\plugins"
}

function Get-PluginVersion([string]$Dir) {
    $jar = Get-ChildItem (Join-Path (Get-PluginsDir $Dir) 'duo-watchdog\lib') -Filter 'duo-watchdog-*.jar' -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($jar) { $jar.BaseName -replace '^duo-watchdog-', '' }
}

# A process started from the IDE's folder, other than its bundled JBR, which may be running Gradle.
function Test-IdeaRunning([string]$Dir) {
    $prefix = $Dir.TrimEnd('\') + '\'
    [bool](Get-Process | Where-Object {
            $_.Path -and $_.Path.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase) -and $_.Path -notmatch '\\jbr\\'
        })
}

function Find-Ideas {
    $roots = @("$env:ProgramFiles\JetBrains", "$env:LOCALAPPDATA\Programs", "$env:LOCALAPPDATA\JetBrains\Toolbox\apps") |
        Where-Object { Test-Path $_ }
    if (-not $roots) { return }
    Get-ChildItem $roots -Filter product-info.json -Recurse -Depth 3 -ErrorAction SilentlyContinue |
        ForEach-Object { $_.DirectoryName } |
        Where-Object { Test-IntelliJ $_ } |
        Sort-Object -Unique
}

# The IDEA folder from what was typed: quotes, a trailing \ and a \bin at the end are dropped.
function Resolve-IdeaFolder([string]$Typed) {
    $dir = $Typed.Trim().Trim('"').TrimEnd('\')
    if ((Split-Path $dir -Leaf) -eq 'bin') { $dir = Split-Path $dir -Parent }
    if ($dir -and (Test-IntelliJ $dir)) { (Resolve-Path $dir).Path }
}

# --- VS Code ---------------------------------------------------------------------------------------------

# The CLIs of VS Code and its forks that install .vsix files, one per real file.
function Find-Codes {
    $found = @()
    foreach ($name in 'code', 'code-insiders', 'codium', 'cursor') {
        $command = Get-Command "$name.cmd" -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($command) { $found += $command.Source }
    }
    foreach ($candidate in "$env:LOCALAPPDATA\Programs\Microsoft VS Code\bin\code.cmd", "$env:ProgramFiles\Microsoft VS Code\bin\code.cmd") {
        if (Test-Path $candidate) { $found += $candidate }
    }
    $found | ForEach-Object { (Resolve-Path $_).Path } | Sort-Object -Unique
}

function Get-CodeName([string]$Cli) {
    switch ([IO.Path]::GetFileNameWithoutExtension($Cli)) {
        'code-insiders' { 'VS Code Insiders' }
        'codium' { 'VSCodium' }
        'cursor' { 'Cursor' }
        default { 'VS Code' }
    }
}

function Get-ExtensionVersion([string]$Cli) {
    $line = & $Cli --list-extensions --show-versions 2>$null | Where-Object { $_ -like 'local.duo-watchdog@*' } | Select-Object -First 1
    if ($line) { $line -replace '^local\.duo-watchdog@', '' }
}

function Get-NodeMajor {
    if (Get-Command node -ErrorAction SilentlyContinue) { [int]((& node --version) -replace '^v(\d+).*', '$1') } else { 0 }
}

# --- What is on this machine -----------------------------------------------------------------------------

$Targets = New-Object System.Collections.ArrayList

function Add-Idea([string]$Dir) {
    $info = Get-ProductInfo $Dir
    $label = "IntelliJ IDEA $($info.version)  $Dir"
    $branch = [int](($info.buildNumber -replace '^[A-Z]+-', '') -replace '\..*', '')
    if ($branch -lt 243) {
        Say "  Skipping ${label}: Duo Watchdog needs IntelliJ IDEA 2024.3 or later."
        return
    }
    $installed = Get-PluginVersion $Dir
    if ($installed) { $label += "  (Duo Watchdog $installed installed)" } else { $label += '  (not installed)' }
    if (Test-IdeaRunning $Dir) { $label += '  [running]' }
    [void]$Targets.Add([pscustomobject]@{ Kind = 'idea'; Place = $Dir; Label = $label })
}

Bold "Duo Watchdog installer: plugin $PluginVersion, VS Code extension $ExtensionVersion"
Say 'Looking for IntelliJ IDEA and VS Code...'

$ArgumentIdea = ''
if ($IdeaPath) {
    $ArgumentIdea = Resolve-IdeaFolder $IdeaPath
    if ($ArgumentIdea) { Add-Idea $ArgumentIdea } else { Say "  $IdeaPath is not an IntelliJ IDEA folder (no product-info.json)." }
}
foreach ($dir in Find-Ideas) {
    if ($dir -ne $ArgumentIdea) { Add-Idea $dir }
}
foreach ($cli in Find-Codes) {
    $installed = Get-ExtensionVersion $cli
    $state = if ($installed) { "Duo Watchdog $installed installed" } else { 'not installed' }
    $version = (& $cli --version 2>$null | Select-Object -First 1)
    [void]$Targets.Add([pscustomobject]@{ Kind = 'code'; Place = $cli; Label = "$(Get-CodeName $cli) $version  $cli  ($state)" })
}

Say
if ($Targets.Count -eq 0) {
    Say 'No IntelliJ IDEA (2024.3+) or VS Code found.'
    Say 'Run install.bat "C:\path\to\IntelliJ IDEA" for an IDEA in an unusual place,'
    Say "or reinstall VS Code with 'Add to PATH' checked."
    exit 1
}

Bold 'Where should Duo Watchdog go?'
for ($i = 0; $i -lt $Targets.Count; $i++) { Say "  $($i + 1)) $($Targets[$i].Label)" }
Say '  a) all of the above'
Say '  p) another IntelliJ IDEA folder'

$Chosen = @()
while ($Chosen.Count -eq 0) {
    $answer = Ask 'Numbers separated by spaces, a, p or q to quit [1]'
    if (-not $answer) { $answer = '1' }
    switch -Regex ($answer) {
        '^[qQ]$' { exit 0 }
        '^[aA]$' { $Chosen = @(0..($Targets.Count - 1)) }
        '^[pP]$' {
            $typed = Ask 'IntelliJ IDEA folder (the one with product-info.json)'
            $dir = Resolve-IdeaFolder $typed
            if ($dir) {
                $count = $Targets.Count
                Add-Idea $dir
                if ($Targets.Count -gt $count) { $Chosen = @($count) }
            } else {
                Say "  $typed is not an IntelliJ IDEA folder."
            }
        }
        default {
            $picked = @()
            foreach ($n in ($answer -split '[\s,]+' | Where-Object { $_ })) {
                if ($n -match '^\d+$' -and [int]$n -ge 1 -and [int]$n -le $Targets.Count) {
                    $picked += [int]$n - 1
                } else {
                    Say "  No option $n."
                    $picked = @()
                    break
                }
            }
            $Chosen = $picked
        }
    }
}
$Selected = $Chosen | ForEach-Object { $Targets[$_] }

# --- Check the tools before building ---------------------------------------------------------------------

if (($Selected | Where-Object Kind -eq 'idea') -and -not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
    Say 'IntelliJ IDEA needs a JDK 17 or later to build the plugin, and none was found.'
    Say 'Install one with: winget install --id EclipseAdoptium.Temurin.21.JDK -e'
    Say 'then open a new terminal and run install.bat again.'
    exit 1
}
if (($Selected | Where-Object Kind -eq 'code') -and ((Get-NodeMajor) -lt 20 -or -not (Get-Command npm -ErrorAction SilentlyContinue))) {
    Say 'VS Code needs Node.js 20 or later (with npm) to package the extension.'
    Say 'Install it with: winget install --id OpenJS.NodeJS.LTS -e'
    Say 'then open a new terminal and run install.bat again.'
    exit 1
}

Say
Bold 'About to install:'
$Selected | ForEach-Object { Say "  $($_.Label)" }
if (-not (YesDefault 'Continue?')) { exit 0 }

# --- Install ---------------------------------------------------------------------------------------------

$Results = New-Object System.Collections.ArrayList
$script:Vsix = ''

function Install-Idea([string]$Dir) {
    while (Test-IdeaRunning $Dir) {
        Say "IntelliJ IDEA at $Dir is running. Plugins can be replaced only while it is closed."
        if ((Ask 'Close it (File > Exit), then press Enter. s to skip it') -match '^[sS]$') {
            [void]$Results.Add("skipped   IntelliJ IDEA  $Dir  (it was running)")
            return
        }
    }
    Say "Building the plugin against $Dir (the first build downloads Gradle and a JDK and can take a few minutes)..."
    & .\gradlew.bat installPlugin "-PideaPath=$Dir" --quiet --console=plain
    if ($LASTEXITCODE -eq 0) {
        [void]$Results.Add("installed IntelliJ IDEA  $Dir  -> Duo Watchdog $PluginVersion")
        if (YesDefault 'Start IntelliJ IDEA now?') { Start-Process (Join-Path $Dir 'bin\idea64.exe') }
    } else {
        [void]$Results.Add("FAILED    IntelliJ IDEA  $Dir  (see the Gradle output above)")
    }
}

function Build-Extension {
    Say 'Packaging the VS Code extension (npm ci && npm run package)...'
    Push-Location vscode
    try {
        & npm.cmd ci --no-audit --no-fund --loglevel=error
        if ($LASTEXITCODE -eq 0) { & npm.cmd run --silent package }
        if ($LASTEXITCODE -eq 0 -and (Test-Path "duo-watchdog-$ExtensionVersion.vsix")) {
            $script:Vsix = (Resolve-Path "duo-watchdog-$ExtensionVersion.vsix").Path
        } else {
            $script:Vsix = 'failed'
        }
    } finally {
        Pop-Location
    }
}

function Install-Code([string]$Cli) {
    $name = Get-CodeName $Cli
    if (-not $script:Vsix) { Build-Extension }
    if ($script:Vsix -eq 'failed') {
        [void]$Results.Add("FAILED    $name  $Cli  (packaging failed, see the npm output above)")
        return
    }
    & $Cli --install-extension $script:Vsix --force
    if ($LASTEXITCODE -eq 0) {
        [void]$Results.Add("installed $name  $Cli  -> Duo Watchdog $ExtensionVersion (run 'Developer: Reload Window' in open windows)")
    } else {
        [void]$Results.Add("FAILED    $name  $Cli")
    }
}

foreach ($target in $Selected) {
    Say
    if ($target.Kind -eq 'idea') { Install-Idea $target.Place } else { Install-Code $target.Place }
}

Say
Bold 'Done:'
$Results | ForEach-Object { Say "  $_" }
if ($Results | Where-Object { $_ -like 'FAILED*' }) { exit 1 }
Say
Say "Next: open Settings > Tools > Duo Watchdog in IntelliJ IDEA, or run 'Duo Watchdog: Set GitLab Token' in VS Code."
