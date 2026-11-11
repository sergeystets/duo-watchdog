#!/usr/bin/env bash
# Downloads an IntelliJ IDEA from JetBrains and unpacks it, checked against JetBrains' SHA-256:
#
#   fetch-ide.sh <version> <folder>      e.g. fetch-ide.sh 2025.3.1 "$RUNNER_TEMP/ides/2025.3.1"
#   fetch-ide.sh --wait <folder>...      waits until each folder's download, started in the background, is done
#
# checks.yml starts one for each IDE the plugin job needs, in the background and all at once, so they arrive while
# the job sets up Java and Gradle; Gradle then uses the folders as they are (-PideaPath, -PverifyIdePaths) and has
# nothing to download or unpack itself. <folder>.log keeps the output, <folder>.done or <folder>.failed says how
# it ended.
set -euo pipefail

if [ "${1:-}" = "--wait" ]; then
    shift
    for dir in "$@"; do
        until [ -e "$dir.done" ]; do
            if [ -e "$dir.failed" ]; then
                echo "::error::No IntelliJ IDEA in $dir:"
                cat "$dir.log" || true
                exit 1
            fi
            sleep 1
        done
        cat "$dir.done"
    done
    exit 0
fi

version=$1
dir=$2
trap 'touch "$dir.failed"' ERR

# Up to 2025.2 the Community edition has an archive of its own; from 2025.3 on there is one IntelliJ IDEA.
year=${version%%.*}
minor=${version#*.}
minor=${minor%%.*}
if [ "$year" -lt 2025 ] || { [ "$year" -eq 2025 ] && [ "$minor" -lt 3 ]; }; then
    name=ideaIC-$version
else
    name=idea-$version
fi
url=https://download.jetbrains.com/idea/$name.tar.gz

started=$SECONDS
for attempt in 1 2 3; do
    rm -rf "$dir" "$dir.sha256"
    mkdir -p "$dir"
    # Unpacked as it arrives, so the archive never takes room on the disk; its checksum is taken on the way.
    if expected=$(curl -fsSL --retry 3 "$url.sha256" | cut -d' ' -f1) &&
        curl -fsSL "$url" | tee >(sha256sum | cut -d' ' -f1 >"$dir.sha256") | tar -xz -C "$dir" --strip-components=1; then
        # sha256sum, beside the pipeline, ends a moment after it.
        for _ in $(seq 100); do if [ -s "$dir.sha256" ]; then break; fi; sleep 0.1; done
        actual=$(cat "$dir.sha256")
        if [ -n "$expected" ] && [ "$actual" = "$expected" ] && [ -f "$dir/product-info.json" ]; then
            echo "IntelliJ IDEA $version in $dir, $(du -sh "$dir" | cut -f1), after $((SECONDS - started))s" >"$dir.part"
            mv "$dir.part" "$dir.done"
            exit 0
        fi
        echo "Attempt $attempt: $url has SHA-256 ${actual:-none}, expected ${expected:-none}"
    else
        echo "Attempt $attempt: could not fetch and unpack $url"
    fi
    sleep 5
done
touch "$dir.failed"
exit 1
