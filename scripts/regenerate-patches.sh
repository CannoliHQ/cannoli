#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
RA_DIR="$ROOT_DIR/retroarch"
PATCH_DIR="$ROOT_DIR/patches"

cd "$RA_DIR"

ROSTER="
android_input:input/drivers/android_input.c
cheat_manager:cheat_manager.c
cheevos:cheevos/cheevos.c
cheevos_client:cheevos/cheevos_client.c
command:command.c
disk_control_interface:disk_control_interface.c
gfx_widgets:gfx/gfx_widgets.c
gl2:gfx/drivers/gl2.c
netplay_frontend:network/netplay/netplay_frontend.c
platform_unix:frontend/drivers/platform_unix.c
retroarch:retroarch.c
runloop:runloop.c
task_autodetect:tasks/task_autodetect.c
task_save:tasks/task_save.c
task_screenshot:tasks/task_screenshot.c
video_driver:gfx/video_driver.c
"

claimed="$(mktemp)"
trap 'rm -f "$claimed"' EXIT
{
    printf '%s\n' "$ROSTER" | sed -n 's/^[^:]*://p' | tr ' ' '\n'
    echo "pkg/android/phoenix-common/jni/Android.mk"
    echo "pkg/android/phoenix/src/com/retroarch/browser/retroactivity/RetroActivityFuture.java"
} | sed '/^$/d' | sort -u > "$claimed"

unclaimed="$(git diff --name-only HEAD | grep -vxFf "$claimed" || true)"
if [ -n "$unclaimed" ]; then
    echo "No ROSTER entry claims these changed files:" >&2
    echo "$unclaimed" | sed 's/^/  /' >&2
    echo "" >&2
    echo "Their changes would be dropped. Add them to ROSTER and run this again." >&2
    exit 1
fi

written=0
while IFS= read -r entry; do
    [ -z "$entry" ] && continue
    name="${entry%%:*}"
    paths="${entry#*:}"
    out="$PATCH_DIR/$name.patch"
    # shellcheck disable=SC2086
    if git diff --quiet HEAD -- $paths; then
        echo "  skip $name (no changes)"
        continue
    fi
    # shellcheck disable=SC2086
    git diff HEAD -- $paths > "$out"
    echo "  wrote $name.patch"
    written=$((written + 1))
done <<< "$ROSTER"


echo "Regenerated $written patches."
