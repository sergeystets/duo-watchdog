# Installs or updates Duo Watchdog in IntelliJ IDEA and VS Code on this machine, asking where.
#
#   install.bat                the latest release from GitHub, ready-made: nothing is built
#   install.bat "<folder>"     offer that IntelliJ IDEA or VS Code too, e.g. one in an unusual place
#   install.bat -FromSource    build this clone instead: the plugin with gradlew installPlugin (JDK 17+), the
#                              extension packaged from vscode\ (Node.js 20+)
#
# Without a clone, in PowerShell:
#   iwr -useb https://raw.githubusercontent.com/sergeystets/duo-watchdog/main/install.ps1 -OutFile $env:TEMP\dw.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File $env:TEMP\dw.ps1
# Runs on the Windows PowerShell 5.1 that Windows 10 and 11 ship, with nothing else needed for the release.
param([string]$Folder, [switch]$FromSource)

# No $ErrorActionPreference = 'Stop': on PowerShell 5.1 it turns any stderr of code, npm or gradlew into a
# terminating error. Each call is checked by its result instead.
Set-Location $PSScriptRoot
$Repository = 'sergeystets/duo-watchdog'
# A clone has the sources beside the script. Run on its own, the script installs the release and needs nothing else.
$Clone = (Test-Path (Join-Path $PSScriptRoot 'intellij\build.gradle.kts')) -and (Test-Path (Join-Path $PSScriptRoot 'gradlew.bat'))
# GitHub takes TLS 1.2 only, which Windows PowerShell 5.1 may not offer unless asked; its progress bars slow
# downloads down many times over.
try { [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12 } catch { }
$ProgressPreference = 'SilentlyContinue'

function Say([string]$Text = '') { Write-Host $Text }
function Bold([string]$Text) { Write-Host $Text -ForegroundColor White }
# Stops at the end of the input, so a script fed by a pipe doesn't take every default forever.
function Ask([string]$Prompt) {
    $answer = Read-Host $Prompt
    if ($null -eq $answer) { Say; Say 'No more input; stopping.'; exit 1 }
    $answer
}
function YesDefault([string]$Prompt) { (Ask "$Prompt [Y/n]") -notmatch '^[nN]' }

# The menu's arrows and the redrawn progress bar need a console. Without one (input or output redirected) the
# menu asks for a number and each step prints a line.
$Live = $false
$Interactive = $false
try {
    $Live = -not [Console]::IsOutputRedirected
    $Interactive = $Live -and -not [Console]::IsInputRedirected
} catch { }
# Characters every Windows console font has; the script itself stays ASCII for Windows PowerShell 5.1.
$Full = [string][char]0x2588
$Empty = [string][char]0x2591
$OkMark = [string][char]0x221A
$Spinner = '|', '/', '-', '\'

function Get-Width { try { [Console]::WindowWidth - 1 } catch { 79 } }

# The text cut to $Width columns.
function Limit-Text([string]$Text, [int]$Width) {
    if ($Width -lt 10) { $Width = 10 }
    if ($Text.Length -gt $Width) { $Text.Substring(0, $Width - 3) + '...' } else { $Text }
}

# The text cut or padded to $Width columns, so it covers what the line held before.
function Format-Line([string]$Text, [int]$Width) { (Limit-Text $Text $Width).PadRight([Math]::Max($Width, 10)) }

if ($FromSource -and -not $Clone) {
    Say '-FromSource builds a clone of the repository, and this script is not in one. Either:'
    Say "  git clone https://github.com/$Repository.git, then duo-watchdog\install.bat -FromSource"
    Say 'or leave -FromSource out to install the latest release, which needs no build.'
    exit 1
}

# --- Where the files come from ---------------------------------------------------------------------------

$WebHeaders = @{ 'User-Agent' = 'duo-watchdog-installer' }

# A file of the release: its address, its size (0 when not known) and its SHA-256 (empty when GitHub gives none).
function New-ReleaseFile([string]$Url, [long]$Size, [string]$Sha) {
    [pscustomobject]@{ Url = $Url; Size = $Size; Sha = ($Sha -replace '^sha256:', '').ToLower() }
}

# The latest release and its two files, from GitHub's API. When the API turns it down (it limits how often an
# address may ask), the release page's redirect names the tag and the files go by their usual names, unchecked.
function Find-Release {
    $tag = ''
    $zip = $null
    $vsix = $null
    $release = $null
    try {
        $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repository/releases/latest" -UseBasicParsing `
            -Headers ($WebHeaders + @{ 'Accept' = 'application/vnd.github+json' }) -TimeoutSec 30 -ErrorAction Stop
    } catch { }
    if ($release) {
        $tag = [string]$release.tag_name
        foreach ($asset in @($release.assets)) {
            $url = [string]$asset.browser_download_url
            if (-not $url.StartsWith("https://github.com/$Repository/releases/download/")) { continue }
            $file = New-ReleaseFile $url ([long]$asset.size) ([string]$asset.digest)
            if ($asset.name -like 'duo-watchdog-*.zip') { $zip = $file }
            elseif ($asset.name -like 'duo-watchdog-*.vsix') { $vsix = $file }
        }
    } else {
        try {
            $page = Invoke-WebRequest -Uri "https://github.com/$Repository/releases/latest" -Method Head -UseBasicParsing `
                -Headers $WebHeaders -TimeoutSec 30 -ErrorAction Stop
            # Windows PowerShell 5.1 says where the redirects ended in ResponseUri, PowerShell 7 in RequestMessage.
            $final = $page.BaseResponse.ResponseUri
            if (-not $final) { $final = $page.BaseResponse.RequestMessage.RequestUri }
            if ("$final" -match '/releases/tag/([^/?#]+)$') { $tag = $Matches[1] }
        } catch { }
    }
    $version = $tag -replace '^v', ''
    if ($version -notmatch '^\d[0-9A-Za-z.+-]*$') { return $null }
    $download = "https://github.com/$Repository/releases/download/$tag"
    if (-not $release) {
        $zip = New-ReleaseFile "$download/duo-watchdog-$version.zip" 0 ''
        $vsix = New-ReleaseFile "$download/duo-watchdog-$version.vsix" 0 ''
    }
    [pscustomobject]@{ Tag = $tag; Version = $version; Zip = $zip; Vsix = $vsix }
}

# Downloads a file of the release to $Path and checks it against the release: its size and SHA-256, when known.
# Throws why it is not the file.
function Save-ReleaseFile($File, [string]$Path) {
    Write-Output "Downloading $($File.Url)"
    Invoke-WebRequest -Uri $File.Url -OutFile $Path -UseBasicParsing -Headers $WebHeaders -TimeoutSec 120 -ErrorAction Stop
    $size = (Get-Item $Path).Length
    if ($File.Size -gt 0 -and $size -ne $File.Size) { throw "It has $size bytes, but the release lists $($File.Size)." }
    if (-not $File.Sha) {
        Write-Output 'GitHub gave no SHA-256 for it, so only its size is checked.'
        return
    }
    $actual = (Get-FileHash -Algorithm SHA256 -Path $Path).Hash.ToLower()
    if ($actual -ne $File.Sha) { throw "Its SHA-256 is $actual, but the release lists $($File.Sha)." }
    Write-Output "SHA-256 matches the release: $actual"
}

# Throws unless the zip is this plugin of this version, everything in its duo-watchdog folder.
function Test-PluginZip([string]$Path, [string]$Version) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try { $names = @($archive.Entries | ForEach-Object { $_.FullName -replace '\\', '/' }) } finally { $archive.Dispose() }
    if ($names -notcontains "duo-watchdog/lib/duo-watchdog-$Version.jar") {
        throw "The zip has no duo-watchdog/lib/duo-watchdog-$Version.jar, so it is not this plugin."
    }
    if ($names | Where-Object { -not $_.StartsWith('duo-watchdog/') -or $_ -match '(^|/)\.\.(/|$)' }) {
        throw 'The zip holds files outside its duo-watchdog folder.'
    }
}

$Release = $null
if ($FromSource) {
    $PluginVersion = (Select-String -Path intellij\build.gradle.kts -Pattern '^version = "(.*)"$').Matches[0].Groups[1].Value
    $ExtensionVersion = ''
    if (Test-Path vscode\package.json) { $ExtensionVersion = (Get-Content -Raw vscode\package.json | ConvertFrom-Json).version }
    $SourceLine = 'Building Duo Watchdog from this clone (-FromSource).'
    $SourceText = 'built from this clone'
} else {
    $Release = Find-Release
    if (-not $Release) {
        Say "Could not find Duo Watchdog's latest release on GitHub (https://github.com/$Repository/releases)."
        Say 'Check the connection or the proxy, and run again.'
        if ($Clone) { Say 'Or build this clone instead: install.bat -FromSource' }
        exit 1
    }
    $PluginVersion = $Release.Version
    $ExtensionVersion = $Release.Version
    $SourceLine = "Installing the ready-made files of release $($Release.Tag) from GitHub; nothing is built."
    $SourceText = "from release $($Release.Tag) on GitHub"
}

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

# What installing does to the target, e.g. "update Duo Watchdog 0.4.0 -> 0.5.0".
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
    if ($target.Added) { Say '      You added this folder; it is remembered until you forget it in the menu below.' }
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
    if (-not $hasIdea) { Say '  IntelliJ IDEA: none found in the usual places. If you have one, add it in the menu below.' }
    if (-not $hasCode) { Say '  VS Code: none found on the PATH or in the usual places. If you have it, add it in the menu below.' }
    if (-not ($hasIdea -and $hasCode)) { Say }
}

# The drawing: the clone's, or else the one of the release it installs; without it, the title shows alone.
$LogoPath = Join-Path $PSScriptRoot 'docs\images\logo.ascii'
$LogoDownloaded = $false
if (-not $Clone) {
    $LogoPath = Join-Path ([IO.Path]::GetTempPath()) "duo-watchdog-logo-$PID.txt"
    try {
        Invoke-WebRequest -Uri "https://raw.githubusercontent.com/$Repository/$($Release.Tag)/docs/images/logo.ascii" `
            -OutFile $LogoPath -UseBasicParsing -Headers $WebHeaders -TimeoutSec 15 -ErrorAction Stop
        $LogoDownloaded = $true
    } catch { }
}

# The logo without the margin all its lines share and without blank lines above and below it.
function Get-Logo {
    $lines = @(Get-Content $LogoPath -ErrorAction SilentlyContinue | ForEach-Object { $_.TrimEnd() })
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
    $title = @('D U O   W A T C H D O G', '', 'Guards your GitLab Duo credits', 'in IntelliJ IDEA and VS Code.', '', "Installer, version $Version")
    # The title goes beside the dog's head, from its 8th row: below the ears, above the nose.
    $row = 8
    # As wide a gap between dog and title as the console allows, from 4 to 30 columns.
    $gap = 0
    if ($art.Count -gt 0) { $gap = [Math]::Min(30, $columns - (Get-Widest @(Join-Beside $dog $row $title 0))) }
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
if ($LogoDownloaded) { Remove-Item -Force $LogoPath -ErrorAction SilentlyContinue }
Say $SourceLine
if ($Clone -and -not $FromSource) {
    $cloneVersion = (Select-String -Path intellij\build.gradle.kts -Pattern '^version = "(.*)"$').Matches[0].Groups[1].Value
    if ($cloneVersion -ne $Release.Version) { Say "This clone is at $cloneVersion; install.bat -FromSource builds that instead." }
}
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

# --- Choosing --------------------------------------------------------------------------------------------

# How the menu names the targets of a kind: the name of the only one, or how many there are.
function Get-KindLabel([string]$Kind) {
    $ofKind = @($Targets | Where-Object Kind -eq $Kind)
    if ($ofKind.Count -eq 1) { $ofKind[0].Name }
    elseif ($Kind -eq 'idea') { "$($ofKind.Count) IntelliJ IDEAs" }
    else { "$($ofKind.Count) VS Codes" }
}

# Offers the options as a menu and returns the index of the one chosen: the arrows (or j and k) move, Enter
# chooses, Esc and q choose the last option, which is Exit. Without a console the options are numbered and a
# number is read instead.
function Select-Option([string[]]$Options) {
    $count = $Options.Count
    if (-not $Interactive) {
        for ($i = 0; $i -lt $count; $i++) { Say "  $($i + 1)) $($Options[$i])" }
        while ($true) {
            $number = (Ask 'Number').Trim()
            if ($number -match '^\d+$' -and [int]$number -ge 1 -and [int]$number -le $count) { return [int]$number - 1 }
            Say "  Type a number from 1 to $count."
        }
    }
    $width = Get-Width
    for ($i = 0; $i -lt $count; $i++) { Say }
    $top = [Console]::CursorTop - $count
    $current = 0
    $chosen = $false
    [Console]::CursorVisible = $false
    try {
        while ($true) {
            for ($i = 0; $i -lt $count; $i++) {
                [Console]::SetCursorPosition(0, $top + $i)
                if ($i -eq $current) {
                    Write-Host (Format-Line "  > $($Options[$i])" $width) -ForegroundColor Cyan -NoNewline
                } else {
                    Write-Host (Format-Line "    $($Options[$i])" $width) -NoNewline
                }
            }
            [Console]::SetCursorPosition(0, $top + $count)
            if ($chosen) { break }
            $key = [Console]::ReadKey($true)
            if ($key.Key -eq 'UpArrow' -or $key.KeyChar -eq 'k') { $current = ($current + $count - 1) % $count }
            elseif ($key.Key -eq 'DownArrow' -or $key.KeyChar -eq 'j') { $current = ($current + 1) % $count }
            elseif ($key.Key -eq 'Enter') { $chosen = $true }
            elseif ($key.Key -eq 'Escape' -or $key.KeyChar -eq 'q') { $current = $count - 1; $chosen = $true }
        }
    } finally {
        [Console]::CursorVisible = $true
    }
    $current
}

$Chosen = @()
while ($Chosen.Count -eq 0) {
    $ideaIndexes = @(for ($i = 0; $i -lt $Targets.Count; $i++) { if ($Targets[$i].Kind -eq 'idea') { $i } })
    $codeIndexes = @(for ($i = 0; $i -lt $Targets.Count; $i++) { if ($Targets[$i].Kind -eq 'code') { $i } })
    $added = @(for ($i = 0; $i -lt $Targets.Count; $i++) { if ($Targets[$i].Added) { $i } })
    $labels = @()
    $actions = @()
    if ($ideaIndexes.Count -gt 0 -and $codeIndexes.Count -gt 0) {
        $labels += "Both: $(Get-KindLabel 'idea') and $(Get-KindLabel 'code')"; $actions += 'all'
        $labels += "IntelliJ IDEA only: $(Get-KindLabel 'idea')"; $actions += 'idea'
        $labels += "VS Code only: $(Get-KindLabel 'code')"; $actions += 'code'
        if ($Targets.Count -gt 2) { $labels += 'Some of them, by their numbers in the list above...'; $actions += 'numbers' }
    } elseif ($Targets.Count -eq 1) {
        $labels += $Targets[0].Name; $actions += 'all'
    } elseif ($Targets.Count -gt 1) {
        $labels += "All of them: $(Get-KindLabel $Targets[0].Kind)"; $actions += 'all'
        $labels += 'Some of them, by their numbers in the list above...'; $actions += 'numbers'
    }
    $labels += "An IntelliJ IDEA or VS Code that isn't listed (you type its folder)..."; $actions += 'add'
    if ($added.Count -gt 0) { $labels += 'Forget a folder you added...'; $actions += 'forget' }
    $labels += 'Exit without installing'; $actions += 'exit'

    Bold 'Where should Duo Watchdog go?'
    if ($Interactive) { Write-Host '  Up/Down to move, Enter to choose' -ForegroundColor DarkGray }
    $action = $actions[(Select-Option $labels)]
    Say
    switch ($action) {
        'all' { $Chosen = @(0..($Targets.Count - 1)) }
        'idea' { $Chosen = $ideaIndexes }
        'code' { $Chosen = $codeIndexes }
        'numbers' {
            $picked = @()
            foreach ($n in ((Ask 'Their numbers in the list above, e.g. 1 3') -split '[\s,]+' | Where-Object { $_ })) {
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
        'add' {
            $count = $Targets.Count
            if ((Add-Folder (Ask 'Folder of the IntelliJ IDEA or VS Code')) -and $Targets.Count -gt $count) { Show-Target $count }
            Say
        }
        'forget' {
            if ($added.Count -eq 1) { $number = "$($added[0] + 1)" } else { $number = (Ask 'Number of the one to forget, in the list above').Trim() }
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
        'exit' { exit 0 }
    }
}
$Selected = $Chosen | ForEach-Object { $Targets[$_] }

# --- Check the tools for a build ------------------------------------------------------------------------

if ($FromSource -and ($Selected | Where-Object Kind -eq 'idea') -and -not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
    Say 'IntelliJ IDEA needs a JDK 17 or later to build the plugin, and none was found.'
    Say 'Install one with: winget install --id EclipseAdoptium.Temurin.21.JDK -e'
    Say 'then open a new terminal and run install.bat -FromSource again, or leave -FromSource out to install the release.'
    exit 1
}
if ($FromSource -and ($Selected | Where-Object Kind -eq 'code') -and ((Get-NodeMajor) -lt 20 -or -not (Get-Command npm -ErrorAction SilentlyContinue))) {
    Say 'VS Code needs Node.js 20 or later (with npm) to package the extension.'
    Say 'Install it with: winget install --id OpenJS.NodeJS.LTS -e'
    Say 'then open a new terminal and run install.bat -FromSource again, or leave -FromSource out to install the release.'
    exit 1
}

Say
Say "Plan, ${SourceText}:"
foreach ($target in $Selected) {
    $note = ''
    if ($target.Kind -eq 'idea' -and (Test-IdeaRunning $target.Place)) { $note = " (you'll be asked to close it first)" }
    Say "  - $($target.Name): $(Get-Plan $target)$note"
}
if (-not (YesDefault 'Continue?')) { exit 0 }

# --- Install, with a progress bar ------------------------------------------------------------------------

$Results = New-Object System.Collections.ArrayList
$Starts = New-Object System.Collections.ArrayList   # the IntelliJ IDEAs to offer to start at the end
$Ideas = @()
$Codes = @()
# IntelliJ IDEA replaces plugins only while it is closed. That is settled here, so no question interrupts the bar.
foreach ($target in $Selected) {
    if ($target.Kind -eq 'code') { $Codes += $target; continue }
    $skipped = $false
    while (Test-IdeaRunning $target.Place) {
        Say "$($target.Name) is running. Plugins can be replaced only while it is closed."
        if ((Ask 'Close it (File > Exit), then press Enter. s = skip it') -match '^[sS]$') { $skipped = $true; break }
    }
    if ($skipped) { [void]$Results.Add("SKIPPED  $($target.Name): it was running.") } else { $Ideas += $target }
}

# Each step's share of the bar: building the plugin is most of the wait, or else downloading the release.
$WPrepare = 10; $WBuild = 45; $WNpm = 20; $WPackage = 10; $WCode = 5; $WDownload = 30; $WUnpack = 10
$StepCount = 0
$TotalWeight = 0
if ($FromSource) {
    if ($Ideas.Count -gt 0) { $StepCount += 1 + $Ideas.Count; $TotalWeight += $WPrepare + $WBuild * $Ideas.Count }
    if ($Codes.Count -gt 0) { $StepCount += 2 + $Codes.Count; $TotalWeight += $WNpm + $WPackage + $WCode * $Codes.Count }
} elseif ($Ideas.Count + $Codes.Count -gt 0) {
    $StepCount = 1 + $Ideas.Count + $Codes.Count
    $TotalWeight = $WDownload + $WUnpack * $Ideas.Count + $WCode * $Codes.Count
}
$StepNumber = 0
$DoneWeight = 0
$GradleTasks = 0
$StepLog = ''
$LiveTop = 0
$KeepLogs = $false
$Work = Join-Path ([IO.Path]::GetTempPath()) ('duo-watchdog-' + [guid]::NewGuid().ToString('N').Substring(0, 8))

# m:ss
function Format-Clock([int]$Seconds) { '{0}:{1:00}' -f [int][Math]::Floor($Seconds / 60), ($Seconds % 60) }

# A step's log as far as it is written; the command still holds it open.
function Read-Log([string]$Path) {
    try {
        $stream = [IO.File]::Open($Path, 'Open', 'Read', 'ReadWrite, Delete')
        try { (New-Object IO.StreamReader($stream)).ReadToEnd() } finally { $stream.Dispose() }
    } catch { '' }
}

function Get-BarWidth([int]$Width) { [Math]::Max(10, [Math]::Min(40, $Width - 30)) }

# The bar for $Percent, $Width characters wide.
function Write-Bar([int]$Percent, [int]$Width) {
    $filled = [int][Math]::Floor($Percent * $Width / 100)
    Write-Host ($Full * $filled) -ForegroundColor Cyan -NoNewline
    Write-Host ($Empty * ($Width - $filled)) -ForegroundColor DarkGray -NoNewline
}

# The running step with a spinner, the Gradle task it is on and its time; under it the bar of the whole install.
# How far the step is: the build by Gradle's tasks against the count the dry run planned, the other steps by time
# against how long they usually take (half of it at $Tau seconds). It waits at 95% for the step to end.
function Show-Live([string]$Label, [string]$Kind, [int]$Weight, [int]$Tau, [int]$Elapsed, [int]$Frame) {
    $text = Read-Log $script:StepLog
    if ($Kind -eq 'gradle' -and $script:GradleTasks -gt 0) {
        $percent = [int][Math]::Floor(([regex]::Matches($text, '(?m)^> Task ')).Count * 100 / ($script:GradleTasks + 1))
    } else {
        $percent = [int][Math]::Floor($Elapsed * 100 / ($Elapsed + $Tau))
    }
    $percent = [Math]::Min($percent, 95)
    $overall = [int][Math]::Floor(($script:DoneWeight * 100 + $percent * $Weight) / $TotalWeight)
    $task = ''
    if ($Kind -eq 'gradle') {
        $tasks = [regex]::Matches($text, '(?m)^> Task :(\S+)')
        if ($tasks.Count -gt 0) { $task = ' - ' + ($tasks[$tasks.Count - 1].Groups[1].Value -split ':')[-1] }
    }
    $width = Get-Width
    $barWidth = Get-BarWidth $width
    [Console]::SetCursorPosition(0, $script:LiveTop)
    Write-Host '  ' -NoNewline
    Write-Host $Spinner[$Frame % $Spinner.Count] -ForegroundColor Cyan -NoNewline
    Write-Host (Format-Line (' ' + (Limit-Text $Label ($width - 14 - $task.Length)) + "$task  $(Format-Clock $Elapsed)") ($width - 3))
    Write-Host '    ' -NoNewline
    Write-Bar $overall $barWidth
    Write-Host (Format-Line (' {0,3}%  step {1} of {2}' -f $overall, $script:StepNumber, $StepCount) ($width - 4 - $barWidth))
    $script:LiveTop = [Console]::CursorTop - 2
}

# A step that ended: its mark in colour, its name, and how long it took or why it didn't run. The bar's line
# under it is cleared for the next step.
function Write-StepEnd([string]$Label, [string]$Mark, [ConsoleColor]$Color, [string]$Note) {
    if (-not $Live) {
        Write-Host "  $Mark [$($script:StepNumber)/$StepCount] $Label ($Note)"
        return
    }
    $width = Get-Width
    [Console]::SetCursorPosition(0, $script:LiveTop)
    Write-Host '  ' -NoNewline
    Write-Host $Mark -ForegroundColor $Color -NoNewline
    Write-Host (Format-Line (' ' + (Limit-Text $Label ($width - 8 - $Note.Length)) + "  $Note") ($width - 3))
    Write-Host (' ' * $width) -NoNewline
    [Console]::SetCursorPosition(0, [Console]::CursorTop)
    $script:LiveTop = [Console]::CursorTop
}

# Runs the command line of a step through cmd.exe with its output in a log while the bar moves; then marks the
# step done, or failed with the end of its log. Returns whether it worked.
function Invoke-Step([string]$Label, [string]$Kind, [int]$Weight, [int]$Tau, [string]$CommandLine, [string]$Directory = $PSScriptRoot) {
    $script:StepNumber++
    $script:StepLog = Join-Path $Work "step-$($script:StepNumber).log"
    $started = Get-Date
    $process = Start-Process -FilePath $env:ComSpec -WorkingDirectory $Directory -NoNewWindow -PassThru `
        -ArgumentList "/d /s /c `"$CommandLine > `"$($script:StepLog)`" 2>&1 < NUL`""
    $null = $process.Handle   # without it the exit code is lost once the process has exited
    $frame = 0
    while (-not $process.HasExited) {
        if ($Live) { Show-Live $Label $Kind $Weight $Tau ([int]((Get-Date) - $started).TotalSeconds) $frame }
        $frame++
        Start-Sleep -Milliseconds 200
    }
    $process.WaitForExit()
    $elapsed = Format-Clock ([int]((Get-Date) - $started).TotalSeconds)
    $script:DoneWeight += $Weight
    if ($process.ExitCode -eq 0) {
        Write-StepEnd $Label $OkMark Green $elapsed
        return $true
    }
    $script:KeepLogs = $true
    Write-StepEnd $Label 'x' Red "failed after $elapsed"
    Get-Content $script:StepLog -Tail 20 -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "      $_" -ForegroundColor DarkGray }
    Say "      The whole output is in $($script:StepLog)"
    $false
}

# Runs a step here, in this PowerShell, with what it writes in a log; then marks it done, or failed with the end
# of its log. A step fails by throwing. Returns whether it worked.
function Invoke-InlineStep([string]$Label, [int]$Weight, [scriptblock]$Action) {
    $script:StepNumber++
    $script:StepLog = Join-Path $Work "step-$($script:StepNumber).log"
    $started = Get-Date
    if ($Live) { Show-Live $Label 'inline' $Weight 2 0 0 }
    $worked = $true
    try {
        & $Action *>&1 | ForEach-Object { "$_" } | Out-File -FilePath $script:StepLog -Encoding utf8
    } catch {
        $worked = $false
        "$_" | Out-File -FilePath $script:StepLog -Append -Encoding utf8
    }
    $elapsed = Format-Clock ([int]((Get-Date) - $started).TotalSeconds)
    $script:DoneWeight += $Weight
    if ($worked) {
        Write-StepEnd $Label $OkMark Green $elapsed
        return $true
    }
    $script:KeepLogs = $true
    Write-StepEnd $Label 'x' Red "failed after $elapsed"
    Get-Content $script:StepLog -Tail 20 -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "      $_" -ForegroundColor DarkGray }
    Say "      The whole output is in $($script:StepLog)"
    $false
}

# A step that can't run because one before it failed.
function Skip-Step([string]$Label, [int]$Weight, [string]$Why) {
    $script:StepNumber++
    $script:DoneWeight += $Weight
    Write-StepEnd $Label '-' DarkGray "skipped: $Why"
}

if ($StepCount -gt 0) {
    New-Item -ItemType Directory -Force $Work | Out-Null
    Say
    Bold "Installing ($StepCount steps)"
    if ($Live) {
        $LiveTop = [Console]::CursorTop
        [Console]::CursorVisible = $false
    }
}
$ZipFile = Join-Path $Work "duo-watchdog-$PluginVersion.zip"
$VsixFile = Join-Path $Work "duo-watchdog-$ExtensionVersion.vsix"
try {
    if (-not $FromSource -and $StepCount -gt 0) {
        # The release's files that the chosen editors need, each checked against the release.
        $downloaded = Invoke-InlineStep "Downloading Duo Watchdog $($Release.Version) from GitHub" $WDownload {
            if ($Ideas.Count -gt 0) {
                if (-not $Release.Zip) { throw "Release $($Release.Tag) has no duo-watchdog-$($Release.Version).zip." }
                Save-ReleaseFile $Release.Zip $ZipFile
                Test-PluginZip $ZipFile $Release.Version
            }
            if ($Codes.Count -gt 0) {
                if (-not $Release.Vsix) { throw "Release $($Release.Tag) has no duo-watchdog-$($Release.Version).vsix." }
                Save-ReleaseFile $Release.Vsix $VsixFile
            }
        }
        foreach ($target in $Ideas) {
            if (-not $downloaded) {
                Skip-Step "Installing the plugin into $($target.Name)" $WUnpack 'the download failed'
                [void]$Results.Add("FAILED   $($target.Name): the download failed, see its output above.")
                continue
            }
            # Where Install Plugin from Disk would put it, in place of an older Duo Watchdog, as gradlew installPlugin does.
            $pluginsDir = Get-PluginsDir $target.Place
            if (Invoke-InlineStep "Installing the plugin into $($target.Name)" $WUnpack {
                    Write-Output "Unpacking into $pluginsDir"
                    New-Item -ItemType Directory -Force $pluginsDir -ErrorAction Stop | Out-Null
                    $old = Join-Path $pluginsDir 'duo-watchdog'
                    if (Test-Path $old) { Remove-Item -Recurse -Force $old -ErrorAction Stop }
                    Expand-Archive -Path $ZipFile -DestinationPath $pluginsDir -Force -ErrorAction Stop
                }) {
                [void]$Results.Add("OK       $($target.Name): Duo Watchdog $PluginVersion installed.")
                [void]$Starts.Add($target)
            } else {
                [void]$Results.Add("FAILED   $($target.Name): the plugin didn't unpack, see its output above.")
            }
        }
        foreach ($target in $Codes) {
            if (-not $downloaded) {
                Skip-Step "Installing the extension into $($target.Name)" $WCode 'the download failed'
                [void]$Results.Add("FAILED   $($target.Name): the download failed, see its output above.")
            } elseif (Invoke-Step "Installing the extension into $($target.Name)" 'code' $WCode 8 `
                    "`"$($target.Place)`" --install-extension `"$VsixFile`" --force") {
                [void]$Results.Add("OK       $($target.Name): Duo Watchdog $ExtensionVersion installed. In open windows run 'Developer: Reload Window'.")
            } else {
                [void]$Results.Add("FAILED   $($target.Name): '$($target.Place) --install-extension' failed.")
            }
        }
    }
    if ($FromSource -and $Ideas.Count -gt 0) {
        $gradle = "`"$PSScriptRoot\gradlew.bat`" :intellij:installPlugin --console=plain"
        # The dry run lists the tasks the build will run, so the bar can follow it task by task.
        if (Invoke-Step 'Preparing Gradle (the first run downloads Gradle and a JDK)' 'prepare' $WPrepare 20 `
                "$gradle `"-PideaPath=$($Ideas[0].Place)`" --dry-run") {
            $GradleTasks = ([regex]::Matches((Read-Log $StepLog), '(?m) SKIPPED\s*$')).Count
            foreach ($target in $Ideas) {
                if (Invoke-Step "Building and installing the plugin into $($target.Name)" 'gradle' $WBuild 60 `
                        "$gradle `"-PideaPath=$($target.Place)`"") {
                    [void]$Results.Add("OK       $($target.Name): Duo Watchdog $PluginVersion installed.")
                    [void]$Starts.Add($target)
                } else {
                    [void]$Results.Add("FAILED   $($target.Name): the build failed, see its output above.")
                }
            }
        } else {
            foreach ($target in $Ideas) {
                Skip-Step "Building and installing the plugin into $($target.Name)" $WBuild "Gradle didn't start"
                [void]$Results.Add("FAILED   $($target.Name): Gradle didn't start, see its output above.")
            }
        }
    }
    if ($FromSource -and $Codes.Count -gt 0) {
        $vscode = Join-Path $PSScriptRoot 'vscode'
        $vsix = Join-Path $vscode "duo-watchdog-$ExtensionVersion.vsix"
        $why = ''
        if (-not (Invoke-Step "Downloading the extension's npm packages" 'npm' $WNpm 25 'npm ci --no-audit --no-fund --loglevel=error' $vscode)) {
            $why = "its npm packages didn't download"
            Skip-Step 'Packaging the VS Code extension' $WPackage $why
        } elseif (-not (Invoke-Step 'Packaging the VS Code extension' 'package' $WPackage 15 'npm run package' $vscode) -or -not (Test-Path $vsix)) {
            $why = "the extension didn't package"
        }
        foreach ($target in $Codes) {
            if ($why) {
                Skip-Step "Installing the extension into $($target.Name)" $WCode $why
                [void]$Results.Add("FAILED   $($target.Name): $why, see the output above.")
            } elseif (Invoke-Step "Installing the extension into $($target.Name)" 'code' $WCode 8 `
                    "`"$($target.Place)`" --install-extension `"$vsix`" --force") {
                [void]$Results.Add("OK       $($target.Name): Duo Watchdog $ExtensionVersion installed. In open windows run 'Developer: Reload Window'.")
            } else {
                [void]$Results.Add("FAILED   $($target.Name): '$($target.Place) --install-extension' failed.")
            }
        }
    }
    if ($StepCount -gt 0 -and $Live) {
        $width = Get-Width
        $barWidth = Get-BarWidth $width
        [Console]::SetCursorPosition(0, $LiveTop)
        Write-Host '    ' -NoNewline
        Write-Bar 100 $barWidth
        Write-Host (Format-Line ' 100%' ($width - 4 - $barWidth))
    }
} finally {
    if ($Live) { try { [Console]::CursorVisible = $true } catch { } }
    if (-not $KeepLogs -and (Test-Path $Work)) { Remove-Item -Recurse -Force $Work -ErrorAction SilentlyContinue }
}

Say
Bold 'Summary'
$Results | ForEach-Object { Say "  $_" }
Say
foreach ($target in $Starts) {
    if (YesDefault "Start $($target.Name) now?") { Start-Process (Join-Path $target.Place 'bin\idea64.exe') }
}
if ($Results | Where-Object { $_ -like 'FAILED*' }) { exit 1 }
Say 'Next: add your GitLab token. IntelliJ IDEA: Settings > Tools > Duo Watchdog.'
Say "VS Code: Ctrl+Shift+P > 'Duo Watchdog: Add or Replace a Token'."
Say 'From here on, Duo Watchdog offers each new release itself.'
