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
# Diffs against HEAD rather than the index: git apply -3 stages what it merges, so an
# index-versus-worktree diff comes back empty after a conflicted bump.
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
RA_DIR="$ROOT_DIR/retroarch"
PATCH_DIR="$ROOT_DIR/patches"

cd "$RA_DIR"

# name:paths. One file per patch, so a conflict at a bump is scoped to one file.
#
# retroactivity_common was retired at the 9770234364 bump: it guarded registerReceiver behind an
# SDK check because upstream did not, which crashed on Android 14. Upstream now does it itself.
# configuration and ra_settings_strings were retired for being dead: the first added an override
# path helper nothing called, the second eleven strings nothing referenced.
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

# Every file that differs from upstream has to be claimed by an entry above, or its edits are
# dropped silently the next time this runs and nobody is told. Derived from the tree rather than
# trusted, because the roster is written by hand and has been wrong twice: cheevos_client.c once,
# then runloop.c and gfx/video_driver.c, the first of which carries the command pump the whole
# in-game menu writes through.
claimed="$(mktemp)"
trap 'rm -f "$claimed"' EXIT
{
    printf '%s\n' "$ROSTER" | sed -n 's/^[^:]*://p' | tr ' ' '\n'
    # Managed by apply-patches.sh rather than by a patch: edited in place, deleted, and created.
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
# Iterate lines, not words: an entry can name more than one path.
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
