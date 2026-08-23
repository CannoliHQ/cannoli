#!/bin/bash
# Regenerate patches/ from the current state of the retroarch/ submodule.
#
# Run this after resolving a bump in the RA tree. One patch per upstream file, so a patch can always
# be regenerated with a single git diff and a conflict is scoped to one file. The roster used to be
# split by concern, which meant retroarch.c and cheevos/cheevos.c were each edited by two patches:
# `git diff -- retroarch.c` then produced the union of both and neither could be regenerated without
# splitting it by hand, every bump, on the files upstream churns most.
#
# Not covered here, deliberately:
#   - Android.mk is edited in place by apply-patches.sh, not patched.
#   - RetroActivityFuture.java is deleted by apply-patches.sh, not patched.
#   - ricotta_bridge.c and ricotta_osd.h are copied from ricotta/jni by apply-patches.sh.
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
RA_DIR="$ROOT_DIR/retroarch"
PATCH_DIR="$ROOT_DIR/patches"

cd "$RA_DIR"

# name:paths. configuration.c and .h are one patch because the struct and its accessor move
# together; nothing else shares a file with anything else.
ROSTER="
android_input:input/drivers/android_input.c
cheat_manager:cheat_manager.c
cheevos:cheevos/cheevos.c
command:command.c
configuration:configuration.c configuration.h
disk_control_interface:disk_control_interface.c
gfx_widgets:gfx/gfx_widgets.c
netplay_frontend:network/netplay/netplay_frontend.c
retroactivity_common:pkg/android/phoenix-common/src/com/retroarch/browser/retroactivity/RetroActivityCommon.java
retroarch:retroarch.c
task_autodetect:tasks/task_autodetect.c
task_save:tasks/task_save.c
task_screenshot:tasks/task_screenshot.c
"

# The strings file is created by its patch rather than modified, so it needs an intent-to-add for
# the `new file mode` header to survive.
NEW_FILE="pkg/android/phoenix/res/values/strings_cannoli.xml"

written=0
# Iterate lines, not words: an entry can name more than one path.
while IFS= read -r entry; do
    [ -z "$entry" ] && continue
    name="${entry%%:*}"
    paths="${entry#*:}"
    out="$PATCH_DIR/$name.patch"
    # shellcheck disable=SC2086
    if git diff --quiet -- $paths; then
        echo "  skip $name (no changes)"
        continue
    fi
    # shellcheck disable=SC2086
    git diff -- $paths > "$out"
    echo "  wrote $name.patch"
    written=$((written + 1))
done <<< "$ROSTER"

if [ -f "$NEW_FILE" ]; then
    chmod u+w "$NEW_FILE"
    git add -N "$NEW_FILE"
    git diff -- "$NEW_FILE" > "$PATCH_DIR/ra_settings_strings.patch"
    git reset -q -- "$NEW_FILE"
    echo "  wrote ra_settings_strings.patch"
    written=$((written + 1))
fi

echo "Regenerated $written patches."
