#!/usr/bin/env bash
# Build inside an isolated Docker container (no Android Studio / SDK on the host).
#
#   ./build.sh                 -> assembleDebug (APKs in */build/outputs/apk/)
#   ./build.sh test            -> any gradle task(s)
#   ./build.sh --release       -> tests + signed release APKs, copied to dist/
#   ./build.sh --purge         -> delete the builder image and gradle cache volume
#
# karoo-ext is on GitHub Packages, which needs a token even for public packages.
# TOKEN/USERNAME are taken from the environment or from `gh auth` and are only
# passed to the container as env vars, never written to disk.
#
# Release signing: a directory (default ~/.local/share/karoo-maps-bridge, or
# $SIGNING_DIR) with release.jks and signing.properties:
#   RELEASE_KEYSTORE=release.jks
#   RELEASE_KEY_ALIAS=...
#   RELEASE_KEYSTORE_PASSWORD=...
#   RELEASE_KEY_PASSWORD=...
# It is mounted read-only into the container; nothing of it touches the project.
set -euo pipefail
cd "$(dirname "$0")"

IMAGE=karoo-maps-bridge-builder
VOLUME=karoo-maps-bridge-gradle

if [[ "${1:-}" == "--purge" ]]; then
    docker rmi -f "$IMAGE" 2>/dev/null || true
    docker volume rm -f "$VOLUME" 2>/dev/null || true
    rm -rf build */build .gradle
    echo "Builder image, gradle cache and build outputs removed."
    exit 0
fi

docker image inspect "$IMAGE" > /dev/null 2>&1 || docker build -t "$IMAGE" docker/

export TOKEN="${TOKEN:-$(gh auth token)}"
export USERNAME="${USERNAME:-$(gh api user --jq .login)}"

release=false
signing_args=()
tasks=("$@")
if [[ "${1:-}" == "--release" ]]; then
    release=true
    SIGNING_DIR="${SIGNING_DIR:-$HOME/.local/share/karoo-maps-bridge}"
    props="$SIGNING_DIR/signing.properties"
    [[ -r "$props" ]] || { echo "Missing $props (see the header of this script)" >&2; exit 1; }
    prop() { grep "^$1=" "$props" | cut -d= -f2-; }
    export RELEASE_KEYSTORE="/signing/$(prop RELEASE_KEYSTORE)"
    export RELEASE_KEY_ALIAS="$(prop RELEASE_KEY_ALIAS)"
    export RELEASE_KEYSTORE_PASSWORD="$(prop RELEASE_KEYSTORE_PASSWORD)"
    export RELEASE_KEY_PASSWORD="$(prop RELEASE_KEY_PASSWORD)"
    signing_args=(-v "$SIGNING_DIR":/signing:ro
        -e RELEASE_KEYSTORE -e RELEASE_KEY_ALIAS -e RELEASE_KEYSTORE_PASSWORD -e RELEASE_KEY_PASSWORD)
    tasks=(test assembleRelease)
fi
[[ ${#tasks[@]} -eq 0 ]] && tasks=(assembleDebug)

# ANDROID_USER_HOME in the volume keeps the debug signing key stable between builds
# (a new key each build makes Android refuse to update the installed app).
docker run --rm \
    -u "$(id -u):$(id -g)" \
    -e HOME=/tmp -e GRADLE_USER_HOME=/gradle -e ANDROID_USER_HOME=/gradle/android \
    -e TOKEN -e USERNAME \
    "${signing_args[@]}" \
    -v "$PWD":/project \
    -v "$VOLUME":/gradle \
    "$IMAGE" ./gradlew --no-daemon "${tasks[@]}"

if $release; then
    mkdir -p dist
    version() { grep -m1 'versionName' "$1/build.gradle.kts" | cut -d'"' -f2; }
    cp mobile-app/build/outputs/apk/release/mobile-app-release.apk \
        "dist/karoo-maps-bridge-phone-$(version mobile-app).apk"
    cp karoo-extension/build/outputs/apk/release/karoo-extension-release.apk \
        "dist/karoo-maps-bridge-karoo-$(version karoo-extension).apk"
    (cd dist && sha256sum karoo-maps-bridge-*.apk > SHA256SUMS)
    echo "Signed release APKs:" && ls -la dist
fi
