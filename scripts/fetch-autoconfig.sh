#!/bin/sh
# Fetch the RetroArch joypad autoconfig library from the CannoliHQ fork
# into app/src/main/assets/autoconfig/android/.
#
# Run before building if the assets dir is empty or you want to refresh
# against the latest fork state. The files are not tracked in git; this
# script is the source of truth for populating them.

set -e

FORK_URL="https://github.com/CannoliHQ/retroarch-joypad-autoconfig.git"
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
ASSETS_DIR="$REPO_ROOT/app/src/main/assets/autoconfig/android"
TMP_DIR=$(mktemp -d -t rajc.XXXXXX)

trap 'rm -rf "$TMP_DIR"' EXIT

echo "Cloning $FORK_URL into $TMP_DIR..."
git clone --depth 1 "$FORK_URL" "$TMP_DIR" >/dev/null 2>&1

FORK_SHA=$(git -C "$TMP_DIR" rev-parse --short HEAD)
FORK_COUNT=$(ls "$TMP_DIR/android"/*.cfg 2>/dev/null | wc -l | tr -d ' ')

echo "Fork commit: $FORK_SHA"
echo "Source cfg count: $FORK_COUNT"

mkdir -p "$ASSETS_DIR"
rm -f "$ASSETS_DIR"/*.cfg
cp "$TMP_DIR/android"/*.cfg "$ASSETS_DIR/"

INSTALLED_COUNT=$(ls "$ASSETS_DIR"/*.cfg 2>/dev/null | wc -l | tr -d ' ')
echo "Installed $INSTALLED_COUNT cfgs into $ASSETS_DIR"
