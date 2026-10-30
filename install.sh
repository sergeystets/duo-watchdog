#!/usr/bin/env bash
# Installs or updates Duo Watchdog in IntelliJ IDEA and VS Code on this machine, asking where.
#
#   ./install.sh            pick from the IntelliJ IDEA and VS Code installs found
#   ./install.sh <folder>   offer that IntelliJ IDEA or VS Code too, e.g. one in an unusual place
#
# IntelliJ IDEA gets the plugin built by ./gradlew installPlugin (needs JDK 17+), VS Code the extension
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

# The menu's arrows and the redrawn progress bar need a terminal. Without one (input from a pipe, output to a
# log) the menu asks for a number and each step prints a line.
INTERACTIVE=
if [ -t 0 ] && [ -t 1 ]; then INTERACTIVE=yes; fi
LIVE=
if [ -t 1 ]; then LIVE=yes; fi
ESC=$'\033'
# Blocks, marks and a spinner where the terminal speaks UTF-8, as every macOS terminal does; ASCII elsewhere.
if [ "$(uname -s)" = Darwin ] || [ "$(locale charmap 2>/dev/null || true)" = UTF-8 ]; then
    FULL=█ EMPTY=░ OK_MARK=✓ FAIL_MARK=✗ SKIP_MARK=– POINTER=❯ DOT=· ARROWS=↑/↓
    SPINNER=(⠋ ⠙ ⠹ ⠸ ⠼ ⠴ ⠦ ⠧ ⠇ ⠏)
else
    FULL='#' EMPTY='.' OK_MARK=+ FAIL_MARK=x SKIP_MARK=- POINTER='>' DOT=- ARROWS=Up/Down
    SPINNER=('|' '/' '-' '\')
fi

columns() { tput cols 2>/dev/null || say 80; }

# The text cut to $2 columns.
fit() {
    local width=$2
    [ "$width" -ge 10 ] || width=10
    if [ "${#1}" -le "$width" ]; then say "$1"; else say "${1:0:$((width - 3))}..."; fi
}

WORK=""
TICKER=""
KEEP_LOGS=""
# However the script ends, the terminal is left as it was: nothing drawing in the background, the cursor shown.
cleanup() {
    if [ -n "$TICKER" ]; then
        touch "$WORK/stop"
        wait "$TICKER" 2>/dev/null || true
        printf '\n\n'
    fi
    if [ -t 1 ]; then printf '\033[?25h'; fi
    if [ -n "$WORK" ] && [ -z "$KEEP_LOGS" ]; then rm -rf "$WORK"; fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM

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

# What installing does to target $1, e.g. "update Duo Watchdog 0.2.1 -> 0.3.0".
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
    [ "${ADDED[$i]}" = yes ] && say "      You added this folder; it is remembered until you forget it in the menu below."
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
    has idea || say "  IntelliJ IDEA: none found in the usual places. If you have one, add it in the menu below."
    has code || say "  VS Code: none found on the PATH or in the usual places. If you have it, add it in the menu below."
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

widest() { awk '{ if (length > w) w = length } END { print w + 0 }'; }

# Puts the lines of $2 to the right of the dog on stdin, starting at its row $1, $3 columns clear of the widest
# dog line they sit beside. With "width" as $4, prints only how wide that would be.
beside() {
    BESIDE="$2" awk -v start="$1" -v gap="$3" -v mode="${4:-}" '
        { dog[NR] = $0 }
        END {
            n = split(ENVIRON["BESIDE"], text, "\n")
            for (i = start; i < start + n; i++) if (length(dog[i]) > col) col = length(dog[i])
            col += gap
            for (k = 1; k <= n; k++) if (col + length(text[k]) > width) width = col + length(text[k])
            if (mode == "width") { print width; exit }
            for (i = 1; i <= NR; i++) {
                k = i - start + 1
                if (k >= 1 && k <= n && text[k] != "") printf "%-" col "s%s\n", dog[i], text[k]
                else print dog[i]
            }
        }'
}

# docs/images/logo.ascii, the dog with the title beside it, with "version x.y.z" in it set to this version.
# A terminal too narrow for the drawing gets the dog with a narrower title beside it, then the dog with the
# title under it, then the title alone, since wrapped lines would tear the dog apart.
banner() {
    local art="" dog row title columns gap
    columns=$(tput cols 2>/dev/null || say 80)
    [ -f "$LOGO" ] && art=$(logo)
    say
    if [ -n "$art" ] && [ "$(say "$art" | widest)" -le "$columns" ]; then
        say "$art" | sed "s/version [0-9][0-9.]*/version $1/"
        say
        return
    fi
    # The text beside the dog starts after a gap of 10 spaces or more; gaps inside the dog are narrower.
    dog=$(say "$art" | sed -E 's/([^ ]) {10,}[^ ].*$/\1/; s/ +$//')
    title=$(printf '%s\n' "D U O   W A T C H D O G" "" "Guards your GitLab Duo credits" \
        "in IntelliJ IDEA and VS Code." "" "Installer, version $1")
    # The title goes beside the dog's head, from its 8th row: below the ears, above the nose.
    row=8
    # As wide a gap between dog and title as the terminal allows, from 4 to 30 columns.
    gap=$((columns - $(say "$dog" | beside "${row:-1}" "$title" 0 width)))
    [ "$gap" -gt 30 ] && gap=30
    if [ -n "$art" ] && [ "$gap" -ge 4 ]; then
        say "$dog" | beside "${row:-1}" "$title" "$gap"
    else
        if [ -n "$art" ] && [ "$(say "$dog" | widest)" -le "$columns" ]; then
            say "$dog"
            say
        fi
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

# --- Choosing --------------------------------------------------------------------------------------------

# The indexes of the listed targets of a kind, idea or code, one per line.
of_kind() { local i; for i in "${!KINDS[@]}"; do [ "${KINDS[$i]}" = "$1" ] && say "$i"; done; return 0; }

# How the menu names the targets of a kind: the name of the only one, or how many there are.
kind_label() {
    local i count=0 name=""
    for i in "${!KINDS[@]}"; do
        if [ "${KINDS[$i]}" = "$1" ]; then
            count=$((count + 1))
            name=${NAMES[$i]}
        fi
    done
    if [ "$count" -eq 1 ]; then
        say "$name"
    elif [ "$1" = idea ]; then
        say "$count IntelliJ IDEAs"
    else
        say "$count VS Codes"
    fi
}

# One line of the menu, cut to the terminal's width; the one the arrows are on ($1 = yes) marked and in colour.
menu_line() {
    local text
    text=$(fit "$2" $(($(columns) - 6)))
    if [ "$1" = yes ]; then
        printf '\r\033[2K  \033[1;36m%s %s\033[0m\n' "$POINTER" "$text"
    else
        printf '\r\033[2K    %s\n' "$text"
    fi
}

# Offers the arguments as a menu and sets PICKED to the index of the one chosen: the arrows (or j and k) move,
# Enter chooses, q and Esc choose the last line, which is Exit. Without a terminal the lines are numbered and a
# number is read instead.
PICKED=0
menu() {
    local count=$# current=0 chosen="" key rest i
    if [ -z "$INTERACTIVE" ]; then
        i=1
        for key in "$@"; do
            say "  $i) $key"
            i=$((i + 1))
        done
        while :; do
            key=$(ask "Number: ") || exit 1
            if [[ "$key" =~ ^[0-9]+$ ]] && [ "$key" -ge 1 ] && [ "$key" -le "$count" ]; then
                PICKED=$((key - 1))
                return
            fi
            say "  Type a number from 1 to $count."
        done
    fi
    printf '\033[?25l'
    while :; do
        i=0
        for key in "$@"; do
            if [ "$i" -eq "$current" ]; then menu_line yes "$key"; else menu_line no "$key"; fi
            i=$((i + 1))
        done
        [ -z "$chosen" ] || break
        if ! IFS= read -rsn1 key; then
            printf '\nNo more input; stopping.\n' >&2
            exit 1
        fi
        case "$key" in
            "") chosen=yes ;;
            "$ESC")
                # An arrow sends Esc [ A (or Esc O A); Esc on its own sends nothing more.
                rest=""
                IFS= read -rsn2 -t 1 rest || true
                case "$rest" in
                    "[A" | OA) current=$(((current + count - 1) % count)) ;;
                    "[B" | OB) current=$(((current + 1) % count)) ;;
                    "") current=$((count - 1)) chosen=yes ;;
                esac
                ;;
            k | K) current=$(((current + count - 1) % count)) ;;
            j | J) current=$(((current + 1) % count)) ;;
            q | Q) current=$((count - 1)) chosen=yes ;;
        esac
        printf '\033[%dA' "$count"
    done
    printf '\033[?25h'
    PICKED=$current
}

CHOSEN=()
while [ ${#CHOSEN[@]} -eq 0 ]; do
    labels=() actions=()
    if has idea && has code; then
        labels+=("Both: $(kind_label idea) and $(kind_label code)") actions+=(all)
        labels+=("IntelliJ IDEA only: $(kind_label idea)") actions+=(idea)
        labels+=("VS Code only: $(kind_label code)") actions+=(code)
        if [ ${#KINDS[@]} -gt 2 ]; then labels+=("Some of them, by their numbers in the list above...") actions+=(numbers); fi
    elif [ ${#KINDS[@]} -eq 1 ]; then
        labels+=("${NAMES[0]}") actions+=(all)
    elif [ ${#KINDS[@]} -gt 1 ]; then
        labels+=("All of them: $(kind_label "${KINDS[0]}")") actions+=(all)
        labels+=("Some of them, by their numbers in the list above...") actions+=(numbers)
    fi
    labels+=("An IntelliJ IDEA or VS Code that isn't listed (you type its folder)...") actions+=(add)
    if any_added; then labels+=("Forget a folder you added...") actions+=(forget); fi
    labels+=("Exit without installing") actions+=(exit)

    bold "Where should Duo Watchdog go?"
    if [ -n "$INTERACTIVE" ]; then say "  $ARROWS to move, Enter to choose"; fi
    menu "${labels[@]}"
    say
    case "${actions[$PICKED]}" in
        all) CHOSEN=("${!KINDS[@]}") ;;
        idea | code) CHOSEN=($(of_kind "${actions[$PICKED]}")) ;;
        numbers)
            answer=$(ask "Their numbers in the list above, e.g. 1 3: ") || exit 1
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
        add)
            dir=$(ask "Folder of the IntelliJ IDEA or VS Code (on macOS, the .app): ") || exit 1
            count=${#KINDS[@]}
            if [ -n "$dir" ] && add_folder "$dir" && [ ${#KINDS[@]} -gt "$count" ]; then
                show "$count"
            fi
            say
            ;;
        forget)
            added=()
            for i in "${!ADDED[@]}"; do [ "${ADDED[$i]}" = yes ] && added+=("$i"); done
            if [ ${#added[@]} -eq 1 ]; then
                number=$((added[0] + 1))
            else
                number=$(ask "Number of the one to forget, in the list above: ") || exit 1
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
        exit) exit 0 ;;
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

# --- Install, with a progress bar ------------------------------------------------------------------------

RESULTS=()
STARTS=()   # the IntelliJ IDEAs to offer to start at the end
IDEAS=()
CODES=()
# IntelliJ IDEA replaces plugins only while it is closed. That is settled here, so no question interrupts the bar.
for i in "${CHOSEN[@]}"; do
    if [ "${KINDS[$i]}" = code ]; then
        CODES+=("$i")
        continue
    fi
    skipped=
    while idea_running "${PLACES[$i]}"; do
        say "${NAMES[$i]} is running. Plugins can be replaced only while it is closed."
        answer=$(ask "Quit it (IntelliJ IDEA > Quit, or File > Exit), then press Enter. s = skip it: ") || exit 1
        case "$answer" in s | S) skipped=yes && break ;; esac
    done
    if [ -n "$skipped" ]; then RESULTS+=("SKIPPED  ${NAMES[$i]}: it was running."); else IDEAS+=("$i"); fi
done

# A checkout made with core.autocrlf=true has CRLF in gradlew, which then fails with "/bin/sh^M: bad interpreter".
if [ ${#IDEAS[@]} -gt 0 ] && grep -q "$(printf '\r')" gradlew; then
    say "gradlew has Windows line endings (CRLF), so it can't run here. Your git wrote them:"
    say "it has core.autocrlf=true. Fix the setting and check the files out again (this discards local edits):"
    say "  git config --global core.autocrlf input"
    say "  git rm -r -q --cached . && git reset --hard"
    for i in "${IDEAS[@]}"; do RESULTS+=("FAILED   ${NAMES[$i]}: gradlew has CRLF line endings, see above."); done
    IDEAS=()
fi

# Each step's share of the bar; building the plugin is most of the wait.
W_PREPARE=10 W_BUILD=45 W_NPM=20 W_PACKAGE=10 W_CODE=5
STEP_COUNT=0 TOTAL_WEIGHT=0
if [ ${#IDEAS[@]} -gt 0 ]; then
    STEP_COUNT=$((1 + ${#IDEAS[@]}))
    TOTAL_WEIGHT=$((W_PREPARE + W_BUILD * ${#IDEAS[@]}))
fi
if [ ${#CODES[@]} -gt 0 ]; then
    STEP_COUNT=$((STEP_COUNT + 2 + ${#CODES[@]}))
    TOTAL_WEIGHT=$((TOTAL_WEIGHT + W_NPM + W_PACKAGE + W_CODE * ${#CODES[@]}))
fi
STEP_NUMBER=0 DONE_WEIGHT=0 GRADLE_TASKS=0
STEP_LABEL="" STEP_KIND="" STEP_WEIGHT=0 STEP_TAU=1 STEP_LOG="" STEP_START=0

# m:ss
clock() { printf '%d:%02d' $(($1 / 60)) $(($1 % 60)); }

# The bar for $1 percent, as wide as $2 columns leave room for, at most 40.
bar() {
    local width=$(($2 - 30)) filled full empty
    [ "$width" -le 40 ] || width=40
    [ "$width" -ge 10 ] || width=10
    filled=$(($1 * width / 100))
    full=$(printf '%*s' "$filled" '')
    empty=$(printf '%*s' $((width - filled)) '')
    printf '\033[36m%s\033[0m\033[2m%s\033[0m' "${full// /$FULL}" "${empty// /$EMPTY}"
}

# How far the running step is, in percent, after $1 seconds: the build by Gradle's tasks against the count the
# dry run planned, the other steps by time against how long they usually take (half of it at $STEP_TAU seconds).
# It waits at 95 for the step to end.
step_percent() {
    local pct seen
    if [ "$STEP_KIND" = gradle ] && [ "$GRADLE_TASKS" -gt 0 ]; then
        seen=$(grep -c '^> Task ' "$STEP_LOG" 2>/dev/null || true)
        pct=$((${seen:-0} * 100 / (GRADLE_TASKS + 1)))
    else
        pct=$(($1 * 100 / ($1 + STEP_TAU)))
    fi
    [ "$pct" -le 95 ] || pct=95
    say "$pct"
}

# The running step with a spinner, the Gradle task it is on and its time; under it the bar of the whole install.
# The cursor ends where it began, at the start of the step's line, for the next frame to draw over.
draw_live() {
    local frame=$1 cols=$2 elapsed=$((SECONDS - STEP_START)) pct overall task=""
    pct=$(step_percent "$elapsed")
    overall=$(((DONE_WEIGHT * 100 + pct * STEP_WEIGHT) / TOTAL_WEIGHT))
    if [ "$STEP_KIND" = gradle ]; then
        task=$(sed -n 's/^> Task :\([^ ]*\).*/\1/p' "$STEP_LOG" 2>/dev/null | tail -n 1)
        [ -z "$task" ] || task=" $DOT ${task##*:}"
    fi
    printf '\r\033[2K  \033[36m%s\033[0m %s\033[2m%s  %s\033[0m\n' "${SPINNER[$((frame % ${#SPINNER[@]}))]}" \
        "$(fit "$STEP_LABEL" $((cols - 12 - ${#task})))" "$task" "$(clock "$elapsed")"
    printf '\r\033[2K    %s \033[1m%3d%%\033[0m  \033[2mstep %d of %d\033[0m\033[1A\r' \
        "$(bar "$overall" "$cols")" "$overall" "$STEP_NUMBER" "$STEP_COUNT"
}

# Redraws the running step five times a second until the step's stop file appears.
ticker() {
    set +e
    local frame=0 cols
    cols=$(columns)
    while [ ! -f "$WORK/stop" ]; do
        draw_live "$frame" "$cols"
        frame=$((frame + 1))
        sleep 0.2
    done
}

stop_ticker() {
    [ -n "$TICKER" ] || return 0
    touch "$WORK/stop"
    wait "$TICKER" 2>/dev/null || true
    TICKER=
}

# A step that ended: its mark ($2) in colour ($1), its name and $3, how long it took or why it didn't run.
# The bar's line under it is cleared for the next step.
step_line() {
    if [ -n "$LIVE" ]; then
        printf '\r\033[2K  \033[%sm%s\033[0m %s  \033[2m%s\033[0m\n\033[2K' "$1" "$2" "$STEP_LABEL" "$3"
    else
        say "  $2 [$STEP_NUMBER/$STEP_COUNT] $STEP_LABEL ($3)"
    fi
}

# Runs the command of a step (label, kind, weight, usual seconds, then the command) with its output in a log
# while the bar moves; then marks the step done, or failed with the end of its log. Returns the command's status.
run_step() {
    local status=0
    STEP_LABEL=$1 STEP_KIND=$2 STEP_WEIGHT=$3 STEP_TAU=$4
    shift 4
    STEP_NUMBER=$((STEP_NUMBER + 1))
    STEP_LOG="$WORK/step-$STEP_NUMBER.log"
    STEP_START=$SECONDS
    : >"$STEP_LOG"
    if [ -n "$LIVE" ]; then
        rm -f "$WORK/stop"
        ticker &
        TICKER=$!
    fi
    "$@" >"$STEP_LOG" 2>&1 </dev/null || status=$?
    stop_ticker
    DONE_WEIGHT=$((DONE_WEIGHT + STEP_WEIGHT))
    if [ "$status" -eq 0 ]; then
        step_line 32 "$OK_MARK" "$(clock $((SECONDS - STEP_START)))"
    else
        KEEP_LOGS=yes
        step_line 31 "$FAIL_MARK" "failed after $(clock $((SECONDS - STEP_START)))"
        tail -n 20 "$STEP_LOG" | sed 's/^/      /'
        say "      The whole output is in $STEP_LOG"
    fi
    return "$status"
}

# A step that can't run because one before it failed: label, weight, why.
skip_step() {
    STEP_LABEL=$1
    STEP_NUMBER=$((STEP_NUMBER + 1))
    DONE_WEIGHT=$((DONE_WEIGHT + $2))
    step_line 2 "$SKIP_MARK" "skipped: $3"
}

in_vscode() { (cd vscode && "$@"); }

if [ "$STEP_COUNT" -gt 0 ]; then
    WORK=$(mktemp -d "${TMPDIR:-/tmp}/duo-watchdog.XXXXXX")
    say
    bold "Installing ($STEP_COUNT steps)"
    if [ -n "$LIVE" ]; then printf '\033[?25l'; fi
fi

if [ ${#IDEAS[@]} -gt 0 ]; then
    # The dry run lists the tasks the build will run, so the bar can follow it task by task.
    if run_step "Preparing Gradle (the first run downloads Gradle and a JDK)" prepare $W_PREPARE 20 \
        ./gradlew :intellij:installPlugin -PideaPath="${PLACES[${IDEAS[0]}]}" --dry-run --console=plain; then
        GRADLE_TASKS=$(grep -c ' SKIPPED$' "$STEP_LOG" || true)
        for i in "${IDEAS[@]}"; do
            if run_step "Building and installing the plugin into ${NAMES[$i]}" gradle $W_BUILD 60 \
                ./gradlew :intellij:installPlugin -PideaPath="${PLACES[$i]}" --console=plain; then
                RESULTS+=("OK       ${NAMES[$i]}: Duo Watchdog $PLUGIN_VERSION installed.")
                STARTS+=("$i")
            else
                RESULTS+=("FAILED   ${NAMES[$i]}: the build failed, see its output above.")
            fi
        done
    else
        for i in "${IDEAS[@]}"; do
            skip_step "Building and installing the plugin into ${NAMES[$i]}" $W_BUILD "Gradle didn't start"
            RESULTS+=("FAILED   ${NAMES[$i]}: Gradle didn't start, see its output above.")
        done
    fi
fi

if [ ${#CODES[@]} -gt 0 ]; then
    VSIX="vscode/duo-watchdog-$EXTENSION_VERSION.vsix"
    why=""
    if ! run_step "Downloading the extension's npm packages" npm $W_NPM 25 \
        in_vscode npm ci --no-audit --no-fund --loglevel=error; then
        why="its npm packages didn't download"
        skip_step "Packaging the VS Code extension" $W_PACKAGE "$why"
    elif ! run_step "Packaging the VS Code extension" package $W_PACKAGE 15 in_vscode npm run package ||
        [ ! -f "$VSIX" ]; then
        why="the extension didn't package"
    fi
    for i in "${CODES[@]}"; do
        if [ -n "$why" ]; then
            skip_step "Installing the extension into ${NAMES[$i]}" $W_CODE "$why"
            RESULTS+=("FAILED   ${NAMES[$i]}: $why, see the output above.")
        elif run_step "Installing the extension into ${NAMES[$i]}" code $W_CODE 8 \
            "${PLACES[$i]}" --install-extension "$VSIX" --force; then
            RESULTS+=("OK       ${NAMES[$i]}: Duo Watchdog $EXTENSION_VERSION installed. In open windows run 'Developer: Reload Window'.")
        else
            RESULTS+=("FAILED   ${NAMES[$i]}: '${PLACES[$i]} --install-extension' failed.")
        fi
    done
fi

if [ "$STEP_COUNT" -gt 0 ] && [ -n "$LIVE" ]; then
    printf '\r\033[2K    %s \033[1m100%%\033[0m\n\033[?25h' "$(bar 100 "$(columns)")"
fi

say
bold "Summary"
for result in "${RESULTS[@]}"; do say "  $result"; done
say
for i in ${STARTS[@]+"${STARTS[@]}"}; do
    yes_default "Start ${NAMES[$i]} now?" && start_idea "${PLACES[$i]}"
done
for result in "${RESULTS[@]}"; do case "$result" in FAILED*) exit 1 ;; esac; done
say "Next: set your GitLab token. IntelliJ IDEA: Settings > Tools > Duo Watchdog."
say "VS Code: Ctrl/Cmd+Shift+P > 'Duo Watchdog: Set GitLab Token'."
