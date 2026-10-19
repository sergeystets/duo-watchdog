# Installs or updates Duo Watchdog in IntelliJ IDEA and VS Code on this machine, asking where.
#
#   install.bat              pick from the IntelliJ IDEA and VS Code installs found
#   install.bat "<folder>"   offer that IntelliJ IDEA or VS Code too, e.g. one in an unusual place
#
# IntelliJ IDEA gets the plugin built by intellij\gradlew installPlugin (needs JDK 17+), VS Code the extension
# packaged from vscode\ (needs Node.js 20+). Runs on the Windows PowerShell 5.1 that Windows 10 and 11 ship.
param([string]$Folder)

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

$PluginVersion = (Select-String -Path intellij\build.gradle.kts -Pattern '^version = "(.*)"$').Matches[0].Groups[1].Value
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

# The folder VS Code is installed in, from its CLI: the folder above bin\code.cmd.
function Get-CodeFolder([string]$Cli) {
    $bin = Split-Path $Cli -Parent
    if ((Split-Path $bin -Leaf) -eq 'bin') { Split-Path $bin -Parent } else { $Cli }
}

# --- What is on this machine -----------------------------------------------------------------------------

$Targets = New-Object System.Collections.ArrayList

# Folders added with p or as the argument, listed again on the next run until forgotten with f.
$Remembered = Join-Path $env:APPDATA 'duo-watchdog\folders.txt'

function Get-Remembered { if (Test-Path $Remembered) { @(Get-Content $Remembered | Where-Object { $_ }) } else { @() } }

function Add-Remembered([string]$Place) {
    $places = Get-Remembered
    if ($places -notcontains $Place) {
        New-Item -ItemType Directory -Force (Split-Path $Remembered -Parent) | Out-Null
        Set-Content -Path $Remembered -Value (@($places) + $Place)
    }
}

function Remove-Remembered([string]$Place) {
    Set-Content -Path $Remembered -Value @(Get-Remembered | Where-Object { $_ -ne $Place })
}

function Get-NewVersion($Target) { if ($Target.Kind -eq 'idea') { $PluginVersion } else { $ExtensionVersion } }

# What installing does to the target, e.g. "update Duo Watchdog 0.2.0 -> 0.2.1".
function Get-Plan($Target) {
    $new = Get-NewVersion $Target
    if (-not $Target.Current) { "install Duo Watchdog $new" }
    elseif ($Target.Current -eq $new) { "reinstall Duo Watchdog $new" }
    else { "update Duo Watchdog $($Target.Current) -> $new" }
}

function Get-Status($Target) {
    $new = Get-NewVersion $Target
    if (-not $Target.Current) { 'Duo Watchdog is not installed yet.' }
    elseif ($Target.Current -eq $new) { "Duo Watchdog $($Target.Current) is installed (up to date)." }
    else { "Duo Watchdog $($Target.Current) is installed; $new is available." }
}

function Add-Idea([string]$Dir) {
    $info = Get-ProductInfo $Dir
    $name = "IntelliJ IDEA $($info.version)"
    $branch = [int](($info.buildNumber -replace '^[A-Z]+-', '') -replace '\..*', '')
    if ($branch -lt 243) {
        Say "  Skipping $name in ${Dir}: Duo Watchdog needs IntelliJ IDEA 2024.3 or later."
        return $false
    }
    [void]$Targets.Add([pscustomobject]@{
            Kind = 'idea'; Place = $Dir; Name = $name; Folder = $Dir; Current = (Get-PluginVersion $Dir); Added = $false
        })
    $true
}

function Add-Code([string]$Cli) {
    $version = (& $Cli --version 2>$null | Select-Object -First 1)
    [void]$Targets.Add([pscustomobject]@{
            Kind = 'code'; Place = $Cli; Name = "$(Get-CodeName $Cli) $version"; Folder = (Get-CodeFolder $Cli)
            Current = (Get-ExtensionVersion $Cli); Added = $false
        })
}

# The CLI of the VS Code (or Insiders, VSCodium, Cursor) in a folder, or that CLI itself, or nothing.
function Find-CodeCli([string]$Dir) {
    $candidates = @($Dir) + ('code', 'code-insiders', 'codium', 'cursor' | ForEach-Object { Join-Path $Dir "bin\$_.cmd" })
    foreach ($candidate in $candidates) {
        if ($candidate -like '*.cmd' -and (Test-Path $candidate -PathType Leaf)) { return (Resolve-Path $candidate).Path }
    }
}

function Test-Listed([string]$Place) {
    [bool]($Targets | Where-Object { $_.Place -eq $Place })
}

# Adds and remembers the IntelliJ IDEA or VS Code in a folder someone typed; returns whether it did, saying why
# not. With -Quiet, an editor listed already is no news (a remembered folder the search found too).
function Add-Folder([string]$Typed, [switch]$Quiet) {
    $path = $Typed.Trim().Trim('"').TrimEnd('\')
    if (-not $path) { return $false }
    $place = Resolve-IdeaFolder $path
    $kind = 'idea'
    if (-not $place) {
        $kind = 'code'
        $place = Find-CodeCli $path
        if (-not $place -and (Split-Path $path -Leaf) -eq 'bin') { $place = Find-CodeCli (Split-Path $path -Parent) }
    }
    if (-not $place) {
        Say "  $path is neither an IntelliJ IDEA folder (with product-info.json) nor a VS Code folder (with bin\code.cmd)."
        return $false
    }
    if (Test-Listed $place) {
        if (-not $Quiet) { Say '  That one is listed already.' }
        return $false
    }
    if ($kind -eq 'idea') {
        if (-not (Add-Idea $place)) { return $false }
    } else {
        Add-Code $place
    }
    $Targets[$Targets.Count - 1].Added = $true
    Add-Remembered $place
    $true
}

function Show-Target([int]$Index) {
    $target = $Targets[$Index]
    Say
    Bold "  [$($Index + 1)] $($target.Name)"
    Say "      $($target.Folder)"
    Say "      $(Get-Status $target)"
    if ($target.Kind -eq 'idea' -and (Test-IdeaRunning $target.Place)) {
        Say "      It is running now; you'll be asked to close it before installing."
    }
    if ($target.Added) { Say '      You added this folder; it is remembered (f forgets it).' }
}

function Show-All {
    Say
    if ($Targets.Count -eq 0) {
        Say 'Found on this computer: nothing yet.'
    } else {
        Say 'Found on this computer:'
        for ($i = 0; $i -lt $Targets.Count; $i++) { Show-Target $i }
    }
    Say
    $hasIdea = [bool]($Targets | Where-Object Kind -eq 'idea')
    $hasCode = [bool]($Targets | Where-Object Kind -eq 'code')
    if (-not $hasIdea) { Say '  IntelliJ IDEA: none found in the usual places. If you have one, type p and its folder.' }
    if (-not $hasCode) { Say '  VS Code: none found on the PATH or in the usual places. If you have it, type p and its folder.' }
    if (-not ($hasIdea -and $hasCode)) { Say }
}

# The logo without the margin all its lines share and without blank lines above and below it.
function Get-Logo {
    $lines = @(Get-Content "$PSScriptRoot/docs/images/logo.ascii" -ErrorAction SilentlyContinue | ForEach-Object { $_.TrimEnd() })
    $drawn = @(for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i]) { $i } })
    if ($drawn.Count -eq 0) { return @() }
    $margin = ($lines[$drawn] | ForEach-Object { $_.Length - $_.TrimStart().Length } | Measure-Object -Minimum).Minimum
    @($lines[$drawn[0]..$drawn[-1]] | ForEach-Object { if ($_.Length -gt $margin) { $_.Substring($margin) } else { '' } })
}

function Get-Widest($Lines) { [int]($Lines | ForEach-Object { $_.Length } | Measure-Object -Maximum).Maximum }

# The dog's lines with $Text to their right, starting at its row $Row (from 1), $Gap columns clear of the widest
# dog line the text sits beside.
function Join-Beside([string[]]$Dog, [int]$Row, [string[]]$Text, [int]$Gap) {
    $column = $Gap + (Get-Widest @(for ($k = 0; $k -lt $Text.Count; $k++) { if ($Row - 1 + $k -lt $Dog.Count) { $Dog[$Row - 1 + $k] } }))
    for ($i = 0; $i -lt $Dog.Count; $i++) {
        $k = $i - ($Row - 1)
        if ($k -ge 0 -and $k -lt $Text.Count -and $Text[$k]) { $Dog[$i].PadRight($column) + $Text[$k] } else { $Dog[$i] }
    }
}

# docs\images\logo.ascii, the dog with the title beside it, with "version x.y.z" in it set to this version.
# A console too narrow for the drawing gets the dog with a narrower title beside it, then the dog with the
# title under it, then the title alone, since wrapped lines would tear the dog apart.
function Show-Banner([string]$Version) {
    $columns = 80
    try { $columns = $Host.UI.RawUI.WindowSize.Width } catch { }
    if (-not $columns) { $columns = 80 }
    $art = @(Get-Logo)
    Say
    if ($art.Count -gt 0 -and (Get-Widest $art) -le $columns) {
        $art | ForEach-Object { Say ($_ -replace 'version [0-9][0-9.]*', "version $Version") }
        Say
        return
    }
    # The text beside the dog starts after a gap of 10 spaces or more; gaps inside the dog are narrower.
    $dog = @($art | ForEach-Object { ($_ -replace '(?<=[^ ]) {10,}[^ ].*$', '').TrimEnd() })
    $row = 1
    for ($i = 0; $i -lt $art.Count; $i++) { if ($art[$i] -match '[^ ] {10,}[^ ]') { $row = $i + 1; break } }
    $title = @('D U O   W A T C H D O G', '', 'Guards your GitLab Duo credits', 'in IntelliJ IDEA and VS Code.', '', "Installer, version $Version")
    # As wide a gap between dog and title as the console allows, from 4 to 10 columns.
    $gap = 0
    if ($art.Count -gt 0) { $gap = [Math]::Min(10, $columns - (Get-Widest @(Join-Beside $dog $row $title 0))) }
    if ($art.Count -gt 0 -and $gap -ge 4) {
        Join-Beside $dog $row $title $gap | ForEach-Object { Say $_ }
    } else {
        if ($dog.Count -gt 0 -and (Get-Widest $dog) -le $columns) {
            $dog | ForEach-Object { Say $_ }
            Say
        }
        Say 'D U O   W A T C H D O G'
        Say 'Guards your GitLab Duo credits in IntelliJ IDEA and VS Code.'
        Say "Installer, version $Version"
    }
    Say
}

if ($PluginVersion -eq $ExtensionVersion) { Show-Banner $PluginVersion } else { Show-Banner "$PluginVersion (VS Code $ExtensionVersion)" }
Say 'Looking for IntelliJ IDEA and VS Code...'

foreach ($place in Get-Remembered) {
    if (Test-Path $place) {
        [void](Add-Folder $place -Quiet)
    } else {
        Say "  $place, which you added earlier, is gone; forgetting it."
        Remove-Remembered $place
    }
}
if ($Folder) { [void](Add-Folder $Folder) }
foreach ($dir in Find-Ideas) {
    if (-not (Test-Listed $dir)) { [void](Add-Idea $dir) }
}
foreach ($cli in Find-Codes) {
    if (-not (Test-Listed $cli)) { Add-Code $cli }
}

Show-All

$Chosen = @()
while ($Chosen.Count -eq 0) {
    Say 'Which of these should get Duo Watchdog?'
    if ($Targets.Count -gt 0) {
        Say '  Enter   all of them'
        Say '  1 2     only the ones with these numbers'
    }
    Say "  p       an IntelliJ IDEA or VS Code that isn't listed (you'll type its folder; it's remembered)"
    $added = @(for ($i = 0; $i -lt $Targets.Count; $i++) { if ($Targets[$i].Added) { $i } })
    if ($added.Count -gt 0) { Say '  f       forget a folder you added' }
    Say '  q       quit without installing'
    $answer = (Ask 'Your choice').Trim()
    switch -Regex ($answer) {
        '^$' {
            if ($Targets.Count -gt 0) { $Chosen = @(0..($Targets.Count - 1)) }
            else { Say '  Nothing is listed yet: type p to add a folder, or q.' }
        }
        '^[qQ]$' { exit 0 }
        '^[pP]$' {
            $count = $Targets.Count
            if ((Add-Folder (Ask 'Folder of the IntelliJ IDEA or VS Code')) -and $Targets.Count -gt $count) { Show-Target $count }
            Say
        }
        '^[fF]$' {
            if ($added.Count -eq 0) { Say "  You haven't added any folder."; break }
            if ($added.Count -eq 1) { $number = "$($added[0] + 1)" } else { $number = (Ask 'Number of the one to forget').Trim() }
            if ($number -match '^\d+$' -and $added -contains ([int]$number - 1)) {
                $target = $Targets[[int]$number - 1]
                Remove-Remembered $target.Place
                Say "  Forgot $($target.Folder)."
                $Targets.RemoveAt([int]$number - 1)
                Show-All
            } else {
                Say "  [$number] isn't a folder you added."
            }
        }
        default {
            $picked = @()
            foreach ($n in ($answer -split '[\s,]+' | Where-Object { $_ })) {
                if ($n -match '^\d+$' -and [int]$n -ge 1 -and [int]$n -le $Targets.Count) {
                    $picked += [int]$n - 1
                } else {
                    Say "  There is no [$n]."
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
Say 'Plan:'
foreach ($target in $Selected) {
    $note = ''
    if ($target.Kind -eq 'idea' -and (Test-IdeaRunning $target.Place)) { $note = " (you'll be asked to close it first)" }
    Say "  - $($target.Name): $(Get-Plan $target)$note"
}
if (-not (YesDefault 'Continue?')) { exit 0 }

# --- Install ---------------------------------------------------------------------------------------------

$Results = New-Object System.Collections.ArrayList
$script:Vsix = ''

function Install-Idea($Target) {
    $dir = $Target.Place
    while (Test-IdeaRunning $dir) {
        Say "$($Target.Name) is running. Plugins can be replaced only while it is closed."
        if ((Ask 'Close it (File > Exit), then press Enter. s = skip it') -match '^[sS]$') {
            [void]$Results.Add("SKIPPED  $($Target.Name): it was running.")
            return
        }
    }
    Say "Building the plugin for $($Target.Name) (the first build downloads Gradle and a JDK and can take a few minutes)..."
    & .\intellij\gradlew.bat -p intellij installPlugin "-PideaPath=$dir" --quiet --console=plain
    if ($LASTEXITCODE -eq 0) {
        [void]$Results.Add("OK       $($Target.Name): Duo Watchdog $PluginVersion installed.")
        if (YesDefault "Start $($Target.Name) now?") { Start-Process (Join-Path $dir 'bin\idea64.exe') }
    } else {
        [void]$Results.Add("FAILED   $($Target.Name): the build failed, see the Gradle output above.")
    }
}

function Build-Extension {
    Say 'Packaging the VS Code extension...'
    Push-Location vscode
    try {
        & npm.cmd ci --no-audit --no-fund --loglevel=error
        if ($LASTEXITCODE -eq 0) { & npm.cmd run --silent package | Out-Null }
        if ($LASTEXITCODE -eq 0 -and (Test-Path "duo-watchdog-$ExtensionVersion.vsix")) {
            $script:Vsix = (Resolve-Path "duo-watchdog-$ExtensionVersion.vsix").Path
        } else {
            $script:Vsix = 'failed'
        }
    } finally {
        Pop-Location
    }
}

function Install-Code($Target) {
    if (-not $script:Vsix) { Build-Extension }
    if ($script:Vsix -eq 'failed') {
        [void]$Results.Add("FAILED   $($Target.Name): packaging the extension failed, see the npm output above.")
        return
    }
    Say "Installing into $($Target.Name)..."
    & $Target.Place --install-extension $script:Vsix --force | Out-Null
    if ($LASTEXITCODE -eq 0) {
        [void]$Results.Add("OK       $($Target.Name): Duo Watchdog $ExtensionVersion installed. In open windows run 'Developer: Reload Window'.")
    } else {
        [void]$Results.Add("FAILED   $($Target.Name): '$($Target.Place) --install-extension' failed.")
    }
}

foreach ($target in $Selected) {
    Say
    if ($target.Kind -eq 'idea') { Install-Idea $target } else { Install-Code $target }
}

Say
Bold 'Summary'
$Results | ForEach-Object { Say "  $_" }
if ($Results | Where-Object { $_ -like 'FAILED*' }) { exit 1 }
Say
Say 'Next: set your GitLab token. IntelliJ IDEA: Settings > Tools > Duo Watchdog.'
Say "VS Code: Ctrl+Shift+P > 'Duo Watchdog: Set GitLab Token'."
