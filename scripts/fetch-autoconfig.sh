#!/bin/sh
# Fetch Cannoli's curated controller input database (CannoliHQ/input-db) into
# app/src/main/assets/autoconfig/cannoli/. Vendor subdirs are flattened to
# <vendor>_<device>.cfg. The community RetroArch autoconfig library is no
# longer bundled; controllers without a curated entry fall to the in-app setup
# wizard and the generic Android default.
#
# Run before building if the assets dir is empty or you want to refresh against
# the latest DB. The files are not tracked in git; this script is the source of
# truth for populating them.

set -e

DB_URL="https://github.com/CannoliHQ/input-db.git"
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
ASSETS_DIR="$REPO_ROOT/app/src/main/assets/autoconfig/cannoli"
TMP_DIR=$(mktemp -d -t inputdb.XXXXXX)

trap 'rm -rf "$TMP_DIR"' EXIT

echo "Cloning $DB_URL into $TMP_DIR..."
git clone --depth 1 "$DB_URL" "$TMP_DIR" >/dev/null 2>&1

DB_SHA=$(git -C "$TMP_DIR" rev-parse --short HEAD)
echo "DB commit: $DB_SHA"

mkdir -p "$ASSETS_DIR"
rm -f "$ASSETS_DIR"/*.cfg

# Flatten <vendor>/<device>.cfg into <vendor>_<device>.cfg. Filenames are
# cosmetic (the resolver matches on cfg contents), but the prefix keeps two
# vendors' same-named devices from colliding.
find "$TMP_DIR" -name '*.cfg' -not -path '*/.git/*' | while IFS= read -r cfg; do
    vendor=$(basename "$(dirname "$cfg")")
    device=$(basename "$cfg")
    cp "$cfg" "$ASSETS_DIR/${vendor}_${device}"
done

INSTALLED_COUNT=$(ls "$ASSETS_DIR"/*.cfg 2>/dev/null | wc -l | tr -d ' ')
echo "Installed $INSTALLED_COUNT curated cfg(s) into $ASSETS_DIR"
