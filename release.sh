#!/usr/bin/env bash
# Releases this version of Duo Watchdog: tags this commit, which must be on main, v<version> and pushes the tag.
# GitHub Actions then runs every check (.github/workflows/release.yml): the tests, the IntelliJ plugin built and checked
# by JetBrains' Plugin Verifier on the oldest and the newest IntelliJ IDEA, the VS Code extension tested in VS Code,
# and the installers on Linux, macOS and Windows. It publishes the release, with the very files it tested, only when
# every check passes. Both editors then offer it as an update.
#
#   ./release.sh          show what it will tag, and ask
#   ./release.sh --yes    the same without asking
#
# Needs git, and gh signed in to GitHub. Nothing is built here.
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
git fetch --quiet --tags origin main
COMMIT=$(git rev-parse HEAD)
git merge-base --is-ancestor "$COMMIT" origin/main ||
    die "$(git rev-parse --short HEAD) is not on origin/main; merge and push it first"
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null || [ -n "$(git ls-remote --tags origin "refs/tags/$TAG")" ]; then
    die "tag $TAG exists already; raise the version first"
fi
if gh release view "$TAG" --repo "$REPOSITORY" >/dev/null 2>&1; then
    die "release $TAG exists already; raise the version first"
fi

# The changelog's "## <version>" section, up to the next one: the release's notes.
NOTES=$(awk -v v="## $VERSION" '$0 == v { on = 1; next } on && /^## / { exit } on { print }' vscode/CHANGELOG.md)
[ -n "$(printf '%s' "$NOTES" | tr -d '[:space:]')" ] || die "vscode/CHANGELOG.md has no \"## $VERSION\" section"

printf 'Release %s of %s, from %s:\n\n%s\n\n' "$TAG" "$REPOSITORY" "$(git log -1 --format='%h %s' "$COMMIT")" "$NOTES"
if [ -z "$YES" ]; then
    read -r -p "Tag it and let GitHub check and publish it? [y/N] " answer || exit 1
    case "$answer" in [yY]*) ;; *) die "not tagged" ;; esac
fi

git tag -a "$TAG" -m "Duo Watchdog $VERSION" "$COMMIT"
git push --quiet origin "refs/tags/$TAG"
printf '\nPushed %s. GitHub Actions checks it now and publishes it only if every check passes:\n' "$TAG"
printf '  https://github.com/%s/actions/workflows/release.yml\n' "$REPOSITORY"
printf 'Watch it with: gh run watch --repo %s $(gh run list --repo %s --workflow release.yml --limit 1 --json databaseId --jq ".[0].databaseId")\n' \
    "$REPOSITORY" "$REPOSITORY"
printf 'If a check fails, nothing is published: fix it, then drop the tag and release again:\n'
printf '  git push --delete origin %s && git tag -d %s && ./release.sh\n' "$TAG" "$TAG"
