#!/usr/bin/env bash
# CI, on Linux and macOS: install.sh installs the latest release into stand-in editors (two IntelliJ IDEA folders and
# a VS Code CLI that only keeps what it is asked to install), from the clone and then on its own, as the one-line
# install runs it. Everything happens in a temporary HOME; the machine's own editors are left alone.
set -euo pipefail
cd "$(dirname "$0")/../.."

REPOSITORY=sergeystets/duo-watchdog
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

fail() {
    echo "::error::$*"
    exit 1
}

# The latest release, as the release page's redirect names it (GitHub's API limits how often a runner may ask).
tag=$(curl -fsSLI -o /dev/null -w '%{url_effective}' "https://github.com/$REPOSITORY/releases/latest")
version=${tag##*/v}
echo "Latest release: $version"

idea() {
    mkdir -p "$work/$1"
    cat >"$work/$1/product-info.json" <<EOF
{
  "name": "IntelliJ IDEA",
  "version": "$2",
  "buildNumber": "$3.100",
  "productCode": "IU",
  "dataDirectoryName": "DuoWatchdogSmoke-$1"
}
EOF
}
idea idea-oldest 2024.3.7 243
idea idea-newest 2026.3 263

mkdir -p "$work/code/bin"
cat >"$work/code/bin/code" <<'EOF'
#!/usr/bin/env bash
here=$(dirname "$0")
case "$1" in
    --version) echo "1.99.0-smoke" ;;
    --list-extensions) cat "$here/installed.txt" 2>/dev/null || true ;;
    --install-extension) cp "$2" "$here/installed.vsix" && echo "local.duo-watchdog@smoke" >"$here/installed.txt" ;;
esac
EOF
chmod +x "$work/code/bin/code"

# $1: clone, or alone (the script on its own, as bash <(curl ...) runs it).
install() {
    local home="$work/home-$1" output status=0
    mkdir -p "$home/.config/duo-watchdog"
    rm -f "$work/code/bin/installed.vsix" "$work/code/bin/installed.txt"
    # Remembered folders come first in the list: [1] the VS Code, [2] and [3] the IntelliJ IDEAs.
    printf '%s\n' "$work/code" "$work/idea-oldest" "$work/idea-newest" >"$home/.config/duo-watchdog/folders"
    # 4 = Some of them, by their numbers; then the plan; then twice "Start ... now?".
    local answers=$'4\n1 2 3\ny\nn\nn\n'
    if [ "$1" = clone ]; then
        output=$(printf '%s' "$answers" | env HOME="$home" XDG_CONFIG_HOME="$home/.config" \
            XDG_DATA_HOME="$home/.local/share" ./install.sh 2>&1) || status=$?
    else
        cp install.sh "$work/alone.sh"
        output=$(printf '%s' "$answers" | (cd "$work" && env HOME="$home" XDG_CONFIG_HOME="$home/.config" \
            XDG_DATA_HOME="$home/.local/share" bash <(cat alone.sh)) 2>&1) || status=$?
    fi
    echo "$output"
    [ "$status" -eq 0 ] || fail "install.sh ($1) exited with $status"
    grep -q "Installing the ready-made files of release v$version" <<<"$output" || fail "install.sh ($1) did not take release v$version"

    local jars
    jars=$(find "$home" -path "*/duo-watchdog/lib/duo-watchdog-$version.jar" | wc -l | tr -d ' ')
    [ "$jars" = 2 ] || fail "install.sh ($1): the plugin is in $jars of the 2 IntelliJ IDEAs"
    [ -f "$work/code/bin/installed.vsix" ] || fail "install.sh ($1): VS Code was given no .vsix"
    local manifest
    manifest=$(unzip -p "$work/code/bin/installed.vsix" extension/package.json)
    grep -q "\"version\": \"$version\"" <<<"$manifest" || fail "install.sh ($1): the .vsix is not $version"
    echo "install.sh ($1): Duo Watchdog $version in both IntelliJ IDEAs and in VS Code"
}

install clone
install alone

# Read from a pipe, the script stops and says how to run it instead.
if piped=$(bash <install.sh 2>&1); then fail "curl ... | bash ran instead of stopping"; fi
grep -q 'bash <(curl' <<<"$piped" || fail "curl ... | bash stopped without saying how to run it: $piped"
echo "curl ... | bash: refused, with how to run it"
