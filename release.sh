#!/usr/bin/env bash
# Publishes this version of Duo Watchdog as GitHub release v<version>, with the IntelliJ plugin
# (duo-watchdog-<version>.zip) and the VS Code extension (duo-watchdog-<version>.vsix). Both offer the latest release as
# an update from the editor, so a version reaches everyone once it is released.
#
#   ./release.sh          run the tests, build, show what it will publish, and ask
#   ./release.sh --yes    the same without asking
#
# Needs what a build needs (JDK 17+, an installed IntelliJ IDEA, Node.js 20+) and gh signed in to GitHub.
set -euo pipefail
cd "$(dirname "$0")"

die() {
    printf 'release: %s\n' "$*" >&2
    exit 1
}

YES=
case "${1:-}" in
    --yes | -y) YES=yes ;;
    '') ;;
    *) die "unknown option $1; use --yes or nothing" ;;
esac

REPOSITORY=sergeystets/duo-watchdog
PLUGIN_VERSION=$(sed -n 's/^version = "\(.*\)"$/\1/p' intellij/build.gradle.kts)
# sed -n 1p reads to the end, where head would quit early: with pipefail, a writer cut off by a closed pipe
# (SIGPIPE) fails the whole pipeline.
EXTENSION_VERSION=$(sed -n 's/^  "version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' vscode/package.json | sed -n 1p)
[ -n "$PLUGIN_VERSION" ] || die "no version in intellij/build.gradle.kts"
[ "$PLUGIN_VERSION" = "$EXTENSION_VERSION" ] ||
    die "the plugin is $PLUGIN_VERSION but the extension $EXTENSION_VERSION; make them the same"
VERSION=$PLUGIN_VERSION
TAG=v$VERSION

command -v gh >/dev/null || die "gh is not installed: https://cli.github.com"
gh auth status >/dev/null 2>&1 || die "gh is not signed in: run gh auth login"
[ -z "$(git status --porcelain)" ] || die "the working tree has changes; commit them first"
git fetch --quiet origin main
COMMIT=$(git rev-parse HEAD)
git merge-base --is-ancestor "$COMMIT" origin/main ||
    die "$(git rev-parse --short HEAD) is not on origin/main; merge and push it first"
if gh release view "$TAG" --repo "$REPOSITORY" >/dev/null 2>&1; then
    die "release $TAG exists already; raise the version first"
fi

# The changelog's "## <version>" section, up to the next one.
NOTES=$(awk -v v="## $VERSION" '$0 == v { on = 1; next } on && /^## / { exit } on { print }' vscode/CHANGELOG.md)
[ -n "$(printf '%s' "$NOTES" | tr -d '[:space:]')" ] || die "vscode/CHANGELOG.md has no \"## $VERSION\" section"

printf 'Testing and building Duo Watchdog %s...\n' "$VERSION"
./gradlew --quiet test :intellij:buildPlugin :vscode:packageExtension
ZIP=intellij/build/distributions/duo-watchdog-$VERSION.zip
VSIX=vscode/duo-watchdog-$VERSION.vsix
[ -f "$ZIP" ] || die "the build made no $ZIP"
[ -f "$VSIX" ] || die "the build made no $VSIX"
# The listing first, then the search: piped into grep -q, which quits at the first match, unzip could be cut off
# while still writing, and pipefail would take that for a missing jar.
LISTING=$(unzip -l "$ZIP") || die "cannot read $ZIP"
grep -q " duo-watchdog/lib/duo-watchdog-$VERSION.jar$" <<<"$LISTING" ||
    die "$ZIP has no duo-watchdog/lib/duo-watchdog-$VERSION.jar"

printf '\nRelease %s of %s at %s:\n' "$TAG" "$REPOSITORY" "$(git rev-parse --short HEAD)"
printf '  %s (%s bytes)\n' "$ZIP" "$(wc -c <"$ZIP" | tr -d ' ')"
printf '  %s (%s bytes)\n' "$VSIX" "$(wc -c <"$VSIX" | tr -d ' ')"
printf '\n%s\n\n' "$NOTES"
if [ -z "$YES" ]; then
    read -r -p "Publish it? Everyone with Duo Watchdog is offered it as an update. [y/N] " answer || exit 1
    case "$answer" in [yY]*) ;; *) die "not published" ;; esac
fi

printf '%s\n' "$NOTES" | gh release create "$TAG" "$ZIP" "$VSIX" --repo "$REPOSITORY" --target "$COMMIT" \
    --title "Duo Watchdog $VERSION" --notes-file - --latest
printf 'Published https://github.com/%s/releases/tag/%s\n' "$REPOSITORY" "$TAG"
