#!/usr/bin/env bash
# Installs or updates Duo Watchdog in IntelliJ IDEA and VS Code on this machine, asking where.
#
#   ./install.sh               pick from the IntelliJ IDEA installs and VS Code CLIs found
#   ./install.sh <IDEA folder> offer that IntelliJ IDEA too, e.g. one in an unusual place
#
# IntelliJ IDEA gets the plugin built by intellij/gradlew installPlugin (needs JDK 17+), VS Code the extension
# packaged from vscode/ (needs Node.js 20+). Runs on the bash 3.2 that macOS ships.
set -euo pipefail
cd "$(dirname "$0")"

bold() { if [ -t 1 ]; then printf '\033[1m%s\033[0m\n' "$*"; else printf '%s\n' "$*"; fi; }
say() { printf '%s\n' "$*"; }
# Fails at the end of the input, so a script fed by a pipe stops instead of taking every default forever.
ask() {
    local answer
    if ! read -r -p "$1" answer; then
        printf '\nNo more input; stopping.\n' >&2
        return 1
    fi
    printf '%s' "$answer"
}
yes_default() {
    local answer
    answer=$(ask "$1 [Y/n] ") || exit 1
    case "$answer" in [nN]*) return 1 ;; *) return 0 ;; esac
}

PLUGIN_VERSION=$(sed -n 's/^version = "\(.*\)"$/\1/p' intellij/build.gradle.kts)
EXTENSION_VERSION=$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' vscode/package.json 2>/dev/null | head -n 1 || true)

# --- IntelliJ IDEA ---------------------------------------------------------------------------------------

product_info() {
    if [ -f "$1/product-info.json" ]; then
        printf '%s\n' "$1/product-info.json"
    elif [ -f "$1/Contents/Resources/product-info.json" ]; then
        printf '%s\n' "$1/Contents/Resources/product-info.json"
    fi
}

# A top-level string field of the IDE's product-info.json, e.g. version or dataDirectoryName.
info() {
    local file
    file=$(product_info "$1")
    [ -n "$file" ] || return 0
    sed -n "s/.*\"$2\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$file" | head -n 1
}

is_intellij() { case "$(info "$1" productCode)" in IU | IC) return 0 ;; *) return 1 ;; esac; }

# 243 for 2024.3: the plugin needs 243 or later.
branch() { info "$1" buildNumber | sed 's/^[A-Z]*-//; s/\..*//'; }

plugins_dir() {
    local data
    data=$(info "$1" dataDirectoryName)
    case "$(uname -s)" in
        Darwin) printf '%s\n' "$HOME/Library/Application Support/JetBrains/$data/plugins" ;;
        *) printf '%s\n' "${XDG_DATA_HOME:-$HOME/.local/share}/JetBrains/$data" ;;
    esac
}

plugin_version() {
    ls "$(plugins_dir "$1")/duo-watchdog/lib" 2>/dev/null | sed -n 's/^duo-watchdog-\(.*\)\.jar$/\1/p' | head -n 1 || true
}

# A process started from the IDE's folder, other than its bundled JBR, which may be running Gradle.
idea_running() {
    ps -A -o command= | awk -v p="$1/" 'index($0, p) == 1 && index($0, "/jbr/") == 0 { found = 1 } END { exit !found }'
}

find_ideas() {
    local dir
    for dir in /Applications/*.app "$HOME"/Applications/*.app \
        "$HOME/Library/Application Support/JetBrains/Toolbox/apps"/*/ch-*/*/*.app \
        /opt/* /opt/*/* "$HOME"/.local/share/JetBrains/Toolbox/apps/* \
        "$HOME"/.local/share/JetBrains/Toolbox/apps/*/ch-*/* /snap/intellij-idea-*/current; do
        [ -d "$dir" ] && is_intellij "$dir" && (cd "$dir" && pwd -P)
    done | sort -u
}

start_idea() {
    case "$(uname -s)" in
        Darwin) open "$1" ;;
        *)
            local launcher="$1/bin/idea"
            [ -x "$launcher" ] || launcher="$1/bin/idea.sh"
            nohup "$launcher" >/dev/null 2>&1 &
            ;;
    esac
}

# --- VS Code ---------------------------------------------------------------------------------------------

# The CLIs of VS Code and its forks that install .vsix files, one per real file.
find_codes() {
    local cli
    {
        for cli in code code-insiders codium cursor; do command -v "$cli" || true; done
        for cli in "/Applications/Visual Studio Code.app" "$HOME/Applications/Visual Studio Code.app"; do
            [ -x "$cli/Contents/Resources/app/bin/code" ] && printf '%s\n' "$cli/Contents/Resources/app/bin/code"
        done
    } | while read -r cli; do
        printf '%s\t%s\n' "$(readlink -f "$cli" 2>/dev/null || printf '%s' "$cli")" "$cli"
    done | sort -u -t "$(printf '\t')" -k1,1 | cut -f2
}

code_name() {
    case "$(basename "$1")" in
        code-insiders) say "VS Code Insiders" ;;
        codium) say "VSCodium" ;;
        cursor) say "Cursor" ;;
        *) say "VS Code" ;;
    esac
}

extension_version() {
    "$1" --list-extensions --show-versions 2>/dev/null | sed -n 's/^local\.duo-watchdog@//p' | head -n 1 || true
}

node_major() { node --version 2>/dev/null | sed 's/^v//; s/\..*//'; }

# The folder VS Code is installed in, from its CLI: the .app on macOS, the folder above bin elsewhere.
code_folder() {
    local real
    real=$(readlink -f "$1" 2>/dev/null || printf '%s' "$1")
    case "$real" in
        */Contents/Resources/app/bin/*) say "${real%%/Contents/Resources/app/bin/*}" ;;
        */bin/code | */bin/code-insiders | */bin/codium | */bin/cursor) dirname "$(dirname "$real")" ;;
        *) say "$1" ;;
    esac
}

# --- What is on this machine -----------------------------------------------------------------------------

KINDS=()   # idea | code
PLACES=()  # the IDEA folder or the VS Code CLI
NAMES=()   # e.g. IntelliJ IDEA 2025.3.1
FOLDERS=() # where the editor is installed
CURRENT=() # the Duo Watchdog version it has, or empty

new_version() { if [ "${KINDS[$1]}" = idea ]; then say "$PLUGIN_VERSION"; else say "$EXTENSION_VERSION"; fi; }

# What installing does to target $1, e.g. "update Duo Watchdog 0.2.0 -> 0.2.1".
plan() {
    local current=${CURRENT[$1]} new
    new=$(new_version "$1")
    if [ -z "$current" ]; then
        say "install Duo Watchdog $new"
    elif [ "$current" = "$new" ]; then
        say "reinstall Duo Watchdog $new"
    else
        say "update Duo Watchdog $current -> $new"
    fi
}

status() {
    local current=${CURRENT[$1]} new
    new=$(new_version "$1")
    if [ -z "$current" ]; then
        say "Duo Watchdog is not installed yet."
    elif [ "$current" = "$new" ]; then
        say "Duo Watchdog $current is installed (up to date)."
    else
        say "Duo Watchdog $current is installed; $new is available."
    fi
}

add_idea() {
    local dir=$1 name
    name="IntelliJ IDEA $(info "$dir" version)"
    if [ "$(branch "$dir")" -lt 243 ] 2>/dev/null; then
        say "  Skipping $name in $dir: Duo Watchdog needs IntelliJ IDEA 2024.3 or later."
        return
    fi
    KINDS+=(idea) PLACES+=("$dir") NAMES+=("$name") FOLDERS+=("$dir") CURRENT+=("$(plugin_version "$dir")")
}

show() {
    local i=$1
    say
    bold "  [$((i + 1))] ${NAMES[$i]}"
    say "      ${FOLDERS[$i]}"
    say "      $(status "$i")"
    if [ "${KINDS[$i]}" = idea ] && idea_running "${PLACES[$i]}"; then
        say "      It is running now; you'll be asked to close it before installing."
    fi
}

if [ "$PLUGIN_VERSION" = "$EXTENSION_VERSION" ]; then
    bold "Duo Watchdog $PLUGIN_VERSION installer"
else
    bold "Duo Watchdog installer (IntelliJ plugin $PLUGIN_VERSION, VS Code extension $EXTENSION_VERSION)"
fi
say "Looking for IntelliJ IDEA and VS Code..."

ARGUMENT_IDEA=""
if [ $# -ge 1 ]; then
    dir=${1%/}
    [ "$(basename "$dir")" = bin ] && dir=$(dirname "$dir")
    if is_intellij "$dir"; then
        ARGUMENT_IDEA=$(cd "$dir" && pwd -P)
        add_idea "$ARGUMENT_IDEA"
    else
        say "  $1 is not an IntelliJ IDEA folder (no product-info.json)."
    fi
fi
while IFS= read -r dir; do
    [ -n "$dir" ] && [ "$dir" != "$ARGUMENT_IDEA" ] && add_idea "$dir"
done < <(find_ideas)
while IFS= read -r cli; do
    [ -n "$cli" ] || continue
    KINDS+=(code) PLACES+=("$cli") FOLDERS+=("$(code_folder "$cli")") CURRENT+=("$(extension_version "$cli")")
    NAMES+=("$(code_name "$cli") $("$cli" --version 2>/dev/null | head -n 1 || true)")
done < <(find_codes)

if [ ${#KINDS[@]} -eq 0 ]; then
    say
    say "No IntelliJ IDEA (2024.3+) or VS Code found."
    say "Run ./install.sh \"/path/to/IntelliJ IDEA\" for an IDEA in an unusual place,"
    say "or put VS Code's 'code' command on the PATH (VS Code: Shell Command: Install 'code' command in PATH)."
    exit 1
fi

say
say "Found on this computer:"
for i in "${!KINDS[@]}"; do show "$i"; done
say

CHOSEN=()
while [ ${#CHOSEN[@]} -eq 0 ]; do
    say "Install into: press Enter for all, or type numbers (e.g. 1 2). p = add an IntelliJ IDEA folder, q = quit."
    answer=$(ask "Your choice: ") || exit 1
    case "$answer" in
        "") CHOSEN=("${!KINDS[@]}") ;;
        q | Q) exit 0 ;;
        p | P)
            dir=$(ask "IntelliJ IDEA folder (the one with product-info.json; on macOS the .app): ") || exit 1
            dir=${dir%/}
            [ "$(basename "$dir")" = bin ] && dir=$(dirname "$dir")
            if is_intellij "$dir"; then
                count=${#KINDS[@]}
                add_idea "$(cd "$dir" && pwd -P)"
                if [ ${#KINDS[@]} -gt "$count" ]; then
                    show "$count"
                    say
                fi
            else
                say "  $dir is not an IntelliJ IDEA folder."
            fi
            ;;
        *)
            for n in ${answer//,/ }; do
                if [[ "$n" =~ ^[0-9]+$ ]] && [ "$n" -ge 1 ] && [ "$n" -le ${#KINDS[@]} ]; then
                    CHOSEN+=($((n - 1)))
                else
                    say "  There is no [$n]."
                    CHOSEN=()
                    break
                fi
            done
            ;;
    esac
done

# --- Check the tools before building ---------------------------------------------------------------------

needs() { local i; for i in "${CHOSEN[@]}"; do [ "${KINDS[$i]}" = "$1" ] && return 0; done; return 1; }

if needs idea && [ -z "${JAVA_HOME:-}" ] && ! command -v java >/dev/null; then
    say "IntelliJ IDEA needs a JDK 17 or later to build the plugin, and none was found."
    say "Install one (e.g. 'brew install --cask temurin@21' or your package manager's openjdk-21) and run again."
    exit 1
fi
if needs code; then
    major=$(node_major)
    if [ -z "$major" ] || [ "$major" -lt 20 ] || ! command -v npm >/dev/null; then
        say "VS Code needs Node.js 20 or later (with npm) to package the extension; found: $(node --version 2>/dev/null || echo none)."
        say "Install it from https://nodejs.org (or 'brew install node') and run again."
        exit 1
    fi
fi

say
say "Plan:"
for i in "${CHOSEN[@]}"; do
    note=""
    [ "${KINDS[$i]}" = idea ] && idea_running "${PLACES[$i]}" && note=" (you'll be asked to close it first)"
    say "  - ${NAMES[$i]}: $(plan "$i")$note"
done
yes_default "Continue?" || exit 0

# --- Install ---------------------------------------------------------------------------------------------

RESULTS=()
VSIX=""

install_idea() {
    local i=$1 dir=${PLACES[$1]}
    while idea_running "$dir"; do
        say "${NAMES[$i]} is running. Plugins can be replaced only while it is closed."
        answer=$(ask "Quit it (IntelliJ IDEA > Quit, or File > Exit), then press Enter. s = skip it: ") || exit 1
        case "$answer" in
            s | S) RESULTS+=("SKIPPED  ${NAMES[$i]}: it was running.") && return ;;
        esac
    done
    say "Building the plugin for ${NAMES[$i]} (the first build downloads Gradle and a JDK and can take a few minutes)..."
    if intellij/gradlew -p intellij installPlugin -PideaPath="$dir" --quiet --console=plain; then
        RESULTS+=("OK       ${NAMES[$i]}: Duo Watchdog $PLUGIN_VERSION installed.")
        yes_default "Start ${NAMES[$i]} now?" && start_idea "$dir"
    else
        RESULTS+=("FAILED   ${NAMES[$i]}: the build failed, see the Gradle output above.")
    fi
}

package_extension() {
    say "Packaging the VS Code extension..."
    if (cd vscode && npm ci --no-audit --no-fund --loglevel=error && npm run --silent package >/dev/null); then
        VSIX="vscode/duo-watchdog-$EXTENSION_VERSION.vsix"
    else
        VSIX="failed"
    fi
}

install_code() {
    local i=$1 cli=${PLACES[$1]}
    [ -n "$VSIX" ] || package_extension
    if [ "$VSIX" = failed ] || [ ! -f "$VSIX" ]; then
        VSIX=failed
        RESULTS+=("FAILED   ${NAMES[$i]}: packaging the extension failed, see the npm output above.")
        return
    fi
    say "Installing into ${NAMES[$i]}..."
    if "$cli" --install-extension "$VSIX" --force >/dev/null; then
        RESULTS+=("OK       ${NAMES[$i]}: Duo Watchdog $EXTENSION_VERSION installed. In open windows run 'Developer: Reload Window'.")
    else
        RESULTS+=("FAILED   ${NAMES[$i]}: '$cli --install-extension' failed.")
    fi
}

for i in "${CHOSEN[@]}"; do
    say
    case "${KINDS[$i]}" in
        idea) install_idea "$i" ;;
        code) install_code "$i" ;;
    esac
done

say
bold "Summary"
for result in "${RESULTS[@]}"; do say "  $result"; done
for result in "${RESULTS[@]}"; do case "$result" in FAILED*) exit 1 ;; esac; done
say
say "Next: set your GitLab token. IntelliJ IDEA: Settings > Tools > Duo Watchdog."
say "VS Code: Ctrl/Cmd+Shift+P > 'Duo Watchdog: Set GitLab Token'."
