# CI, on Windows: install.ps1, in the Windows PowerShell 5.1 that Windows ships, installs the latest release into
# stand-in editors (two IntelliJ IDEA folders and a VS Code code.cmd that only keeps what it is asked to install),
# from the clone and then on its own, as the one-line install runs it. Everything happens under a temporary
# APPDATA and LOCALAPPDATA; the machine's own editors are left alone.
$ErrorActionPreference = 'Continue'
$Repository = 'sergeystets/duo-watchdog'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$work = Join-Path $env:RUNNER_TEMP ('duo-watchdog-smoke-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force $work | Out-Null

function Fail([string]$Message) {
    Write-Host "::error::$Message"
    exit 1
}

# The latest release, as the release page's redirect names it (GitHub's API limits how often a runner may ask).
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$page = Invoke-WebRequest -Uri "https://github.com/$Repository/releases/latest" -Method Head -UseBasicParsing
$version = ([string]$page.BaseResponse.ResponseUri) -replace '^.*/releases/tag/v', ''
Write-Host "Latest release: $version"
Write-Host "PowerShell $($PSVersionTable.PSVersion) ($($PSVersionTable.PSEdition))"

function New-Idea([string]$Name, [string]$Version, [string]$Branch) {
    $dir = Join-Path $work $Name
    New-Item -ItemType Directory -Force $dir | Out-Null
    $info = '{ "name": "IntelliJ IDEA", "version": "' + $Version + '", "buildNumber": "' + $Branch + '.100", ' +
        '"productCode": "IU", "dataDirectoryName": "DuoWatchdogSmoke-' + $Name + '" }'
    Set-Content -Path (Join-Path $dir 'product-info.json') -Value $info -Encoding ASCII
    $dir
}
$oldest = New-Idea 'idea-oldest' '2024.3.7' '243'
$newest = New-Idea 'idea-newest' '2026.3' '263'

$code = Join-Path $work 'code'
New-Item -ItemType Directory -Force (Join-Path $code 'bin') | Out-Null
Set-Content -Path (Join-Path $code 'bin\code.cmd') -Encoding ASCII -Value @'
@echo off
if "%~1"=="--version" (echo 1.99.0-smoke& exit /b 0)
if "%~1"=="--list-extensions" (if exist "%~dp0installed.txt" type "%~dp0installed.txt"
    exit /b 0)
if "%~1"=="--install-extension" (copy /y "%~2" "%~dp0installed.vsix" >nul
    echo local.duo-watchdog@smoke> "%~dp0installed.txt"
    exit /b 0)
exit /b 0
'@

Add-Type -AssemblyName System.IO.Compression.FileSystem

# $How: clone, or alone (the script on its own, as the one-line install runs it).
function Test-Install([string]$How) {
    $env:APPDATA = Join-Path $work "appdata-$How"
    $env:LOCALAPPDATA = Join-Path $work "local-$How"
    New-Item -ItemType Directory -Force (Join-Path $env:APPDATA 'duo-watchdog'), $env:LOCALAPPDATA | Out-Null
    Remove-Item -Force (Join-Path $code 'bin\installed.*') -ErrorAction SilentlyContinue
    # Remembered folders come first in the list: [1] the VS Code, [2] and [3] the IntelliJ IDEAs.
    Set-Content -Path (Join-Path $env:APPDATA 'duo-watchdog\folders.txt') -Value @($code, $oldest, $newest)
    # 4 = Some of them, by their numbers; then the plan; then twice "Start ... now?".
    $answers = Join-Path $work 'answers.txt'
    Set-Content -Path $answers -Encoding ASCII -Value @('4', '1 2 3', 'y', 'n', 'n')
    $script = Join-Path $repo 'install.ps1'
    if ($How -eq 'alone') {
        $alone = Join-Path $work 'alone'
        New-Item -ItemType Directory -Force $alone | Out-Null
        Copy-Item $script $alone
        $script = Join-Path $alone 'install.ps1'
    }
    $log = Join-Path $work "install-$How.log"
    cmd /d /c "powershell -NoProfile -ExecutionPolicy Bypass -File `"$script`" < `"$answers`" > `"$log`" 2>&1"
    $status = $LASTEXITCODE
    $output = Get-Content -Raw $log
    Write-Host $output
    if ($status -ne 0) { Fail "install.ps1 ($How) exited with $status" }
    if ($output -notmatch [regex]::Escape("Installing the ready-made files of release v$version")) {
        Fail "install.ps1 ($How) did not take release v$version"
    }
    $jars = @(Get-ChildItem (Join-Path $env:APPDATA 'JetBrains') -Recurse -Filter "duo-watchdog-$version.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '\\plugins\\duo-watchdog\\lib\\' })
    if ($jars.Count -ne 2) { Fail "install.ps1 ($How): the plugin is in $($jars.Count) of the 2 IntelliJ IDEAs" }
    $vsix = Join-Path $code 'bin\installed.vsix'
    if (-not (Test-Path $vsix)) { Fail "install.ps1 ($How): VS Code was given no .vsix" }
    $archive = [IO.Compression.ZipFile]::OpenRead($vsix)
    try {
        $reader = New-Object IO.StreamReader($archive.GetEntry('extension/package.json').Open())
        try { $manifest = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    } finally { $archive.Dispose() }
    if ($manifest.version -ne $version) { Fail "install.ps1 ($How): the .vsix is $($manifest.version), not $version" }
    Write-Host "install.ps1 ($How): Duo Watchdog $version in both IntelliJ IDEAs and in VS Code"
}

Test-Install 'clone'
Test-Install 'alone'
Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
