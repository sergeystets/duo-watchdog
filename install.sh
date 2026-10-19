#!/usr/bin/env bash
# Installs or updates Duo Watchdog in IntelliJ IDEA and VS Code on this machine, asking where.
#
#   ./install.sh            pick from the IntelliJ IDEA and VS Code installs found
#   ./install.sh <folder>   offer that IntelliJ IDEA or VS Code too, e.g. one in an unusual place
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
ADDED=()   # yes when someone typed its folder, which is then remembered

# Folders added with p or as the argument, listed again on the next run until forgotten with f.
REMEMBERED="${XDG_CONFIG_HOME:-$HOME/.config}/duo-watchdog/folders"

remember() {
    mkdir -p "$(dirname "$REMEMBERED")"
    grep -qxF "$1" "$REMEMBERED" 2>/dev/null || say "$1" >>"$REMEMBERED"
}

forget() {
    [ -f "$REMEMBERED" ] || return 0
    grep -vxF "$1" "$REMEMBERED" >"$REMEMBERED.new" || true
    mv "$REMEMBERED.new" "$REMEMBERED"
}

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
        return 1
    fi
    KINDS+=(idea) PLACES+=("$dir") NAMES+=("$name") FOLDERS+=("$dir") CURRENT+=("$(plugin_version "$dir")") ADDED+=("")
}

add_code() {
    local cli=$1
    KINDS+=(code) PLACES+=("$cli") FOLDERS+=("$(code_folder "$cli")") CURRENT+=("$(extension_version "$cli")") ADDED+=("")
    NAMES+=("$(code_name "$cli") $("$cli" --version 2>/dev/null | head -n 1 || true)")
}

# The CLI of the VS Code (or Insiders, VSCodium, Cursor) in a folder, or that CLI itself, or nothing.
code_cli_in() {
    local candidate
    for candidate in "$1" "$1"/bin/code "$1"/bin/code-insiders "$1"/bin/codium "$1"/bin/cursor \
        "$1"/Contents/Resources/app/bin/code "$1"/Contents/Resources/app/bin/codium "$1"/Contents/Resources/app/bin/cursor; do
        if [ -f "$candidate" ] && [ -x "$candidate" ]; then
            say "$candidate"
            return
        fi
    done
}

real_path() { readlink -f "$1" 2>/dev/null || say "$1"; }

# Whether the editor is listed already: the same IDEA folder, or a CLI that is the same file.
listed() {
    local i
    for i in "${!KINDS[@]}"; do
        [ "$(real_path "${PLACES[$i]}")" = "$(real_path "$1")" ] && return 0
    done
    return 1
}

# Adds and remembers the IntelliJ IDEA or VS Code in a folder someone typed; fails, saying why, when there is
# none. With quiet, an editor listed already is no news (a remembered folder the search found too).
add_folder() {
    local dir=${1%/} cli place=""
    [ "$(basename "$dir")" = bin ] && dir=$(dirname "$dir")
    if is_intellij "$dir"; then
        place=$(cd "$dir" && pwd -P)
    else
        cli=$(code_cli_in "$dir")
        [ -n "$cli" ] || cli=$(code_cli_in "$1")
        place=$cli
    fi
    if [ -z "$place" ]; then
        say "  $1 is neither an IntelliJ IDEA folder (with product-info.json) nor a VS Code folder (with bin/code)."
        return 1
    fi
    if listed "$place"; then
        [ "${2:-}" = quiet ] || say "  That one is listed already."
        return 1
    fi
    if [ "$(kind_of "$place")" = idea ]; then add_idea "$place" || return 1; else add_code "$place"; fi
    ADDED[${#ADDED[@]} - 1]=yes
    remember "$place"
}

kind_of() { if is_intellij "$1"; then say idea; else say code; fi; }

show() {
    local i=$1
    say
    bold "  [$((i + 1))] ${NAMES[$i]}"
    say "      ${FOLDERS[$i]}"
    say "      $(status "$i")"
    if [ "${KINDS[$i]}" = idea ] && idea_running "${PLACES[$i]}"; then
        say "      It is running now; you'll be asked to close it before installing."
    fi
    [ "${ADDED[$i]}" = yes ] && say "      You added this folder; it is remembered (f forgets it)."
    return 0
}

has() { local i; for i in "${!KINDS[@]}"; do [ "${KINDS[$i]}" = "$1" ] && return 0; done; return 1; }

show_all() {
    local i
    say
    if [ ${#KINDS[@]} -eq 0 ]; then
        say "Found on this computer: nothing yet."
    else
        say "Found on this computer:"
        for i in "${!KINDS[@]}"; do show "$i"; done
    fi
    say
    has idea || say "  IntelliJ IDEA: none found in the usual places. If you have one, type p and its folder."
    has code || say "  VS Code: none found on the PATH or in the usual places. If you have it, type p and its folder."
    has idea && has code || say
}

# Takes target $1 off the list.
drop() {
    local i k=() p=() names=() f=() c=() a=()
    for i in "${!KINDS[@]}"; do
        [ "$i" = "$1" ] && continue
        k+=("${KINDS[$i]}") p+=("${PLACES[$i]}") names+=("${NAMES[$i]}") f+=("${FOLDERS[$i]}") c+=("${CURRENT[$i]}") a+=("${ADDED[$i]}")
    done
    KINDS=("${k[@]+"${k[@]}"}") PLACES=("${p[@]+"${p[@]}"}") NAMES=("${names[@]+"${names[@]}"}")
    FOLDERS=("${f[@]+"${f[@]}"}") CURRENT=("${c[@]+"${c[@]}"}") ADDED=("${a[@]+"${a[@]}"}")
}

any_added() { local i; for i in "${!ADDED[@]}"; do [ "${ADDED[$i]}" = yes ] && return 0; done; return 1; }

LOGO=docs/images/logo.ascii

# The logo without the margin all its lines share and without blank lines above and below it.
logo() {
    awk '{ sub(/\r$/, "") }
        NR == FNR { if ($0 ~ /[^ ]/) { match($0, /^ */); if (min == "" || RLENGTH < min) min = RLENGTH; if (!first) first = FNR; last = FNR }; next }
        FNR >= first && FNR <= last { line = substr($0, min + 1); sub(/ +$/, "", line); print line }' "$LOGO" "$LOGO"
}

# docs/images/logo.ascii, the dog with the title beside it, with "version x.y.z" in it set to this version. A
# terminal narrower than the drawing gets the title alone, since wrapped lines would tear the dog apart.
banner() {
    local art="" columns
    columns=$(tput cols 2>/dev/null || say 80)
    [ -f "$LOGO" ] && art=$(logo)
    say
    if [ -n "$art" ] && [ "$(say "$art" | awk '{ if (length > w) w = length } END { print w + 0 }')" -le "$columns" ]; then
        say "$art" | sed "s/version [0-9][0-9.]*/version $1/"
    else
        say "D U O   W A T C H D O G"
        say "Guards your GitLab Duo credits in IntelliJ IDEA and VS Code."
        say "Installer, version $1"
    fi
    say
}

if [ "$PLUGIN_VERSION" = "$EXTENSION_VERSION" ]; then
    banner "$PLUGIN_VERSION"
else
    banner "$PLUGIN_VERSION (VS Code $EXTENSION_VERSION)"
fi
say "Looking for IntelliJ IDEA and VS Code..."

if [ -f "$REMEMBERED" ]; then
    while IFS= read -r place; do
        [ -n "$place" ] || continue
        if [ -e "$place" ]; then
            add_folder "$place" quiet || true
        else
            say "  $place, which you added earlier, is gone; forgetting it."
            forget "$place"
        fi
    done <"$REMEMBERED"
fi
[ $# -ge 1 ] && { add_folder "$1" || true; }
while IFS= read -r dir; do
    [ -n "$dir" ] && ! listed "$dir" && { add_idea "$dir" || true; }
done < <(find_ideas)
while IFS= read -r cli; do
    [ -n "$cli" ] && ! listed "$cli" && add_code "$cli"
done < <(find_codes)

show_all

CHOSEN=()
while [ ${#CHOSEN[@]} -eq 0 ]; do
    say "Which of these should get Duo Watchdog?"
    if [ ${#KINDS[@]} -gt 0 ]; then
        say "  Enter   all of them"
        say "  1 2     only the ones with these numbers"
    fi
    say "  p       an IntelliJ IDEA or VS Code that isn't listed (you'll type its folder; it's remembered)"
    any_added && say "  f       forget a folder you added"
    say "  q       quit without installing"
    answer=$(ask "Your choice: ") || exit 1
    case "$answer" in
        "")
            if [ ${#KINDS[@]} -gt 0 ]; then CHOSEN=("${!KINDS[@]}"); else say "  Nothing is listed yet: type p to add a folder, or q."; fi
            ;;
        q | Q) exit 0 ;;
        p | P)
            dir=$(ask "Folder of the IntelliJ IDEA or VS Code (on macOS, the .app): ") || exit 1
            count=${#KINDS[@]}
            if [ -n "$dir" ] && add_folder "$dir" && [ ${#KINDS[@]} -gt "$count" ]; then
                show "$count"
            fi
            say
            ;;
        f | F)
            if ! any_added; then
                say "  You haven't added any folder."
                continue
            fi
            added=()
            for i in "${!ADDED[@]}"; do [ "${ADDED[$i]}" = yes ] && added+=("$i"); done
            if [ ${#added[@]} -eq 1 ]; then
                number=$((added[0] + 1))
            else
                number=$(ask "Number of the one to forget: ") || exit 1
            fi
            if [[ "$number" =~ ^[0-9]+$ ]] && [ "$number" -ge 1 ] && [ "$number" -le ${#KINDS[@]} ] &&
                [ "${ADDED[$((number - 1))]}" = yes ]; then
                forget "${PLACES[$((number - 1))]}"
                say "  Forgot ${FOLDERS[$((number - 1))]}."
                drop $((number - 1))
                show_all
            else
                say "  [$number] isn't a folder you added."
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
    # A checkout made with core.autocrlf=true has CRLF in gradlew, which then fails with "/bin/sh^M: bad interpreter".
    if grep -q "$(printf '\r')" intellij/gradlew; then
        say "intellij/gradlew has Windows line endings (CRLF), so it can't run here. Your git wrote them:"
        say "it has core.autocrlf=true. Fix the setting and check the files out again (this discards local edits):"
        say "  git config --global core.autocrlf input"
        say "  git rm -r -q --cached . && git reset --hard"
        RESULTS+=("FAILED   ${NAMES[$i]}: intellij/gradlew has CRLF line endings, see above.")
        return
    fi
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
