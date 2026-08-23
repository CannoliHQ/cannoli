#!/bin/bash
# Prepare the RetroArch submodule for RicottaArch builds.
# Applies patches, copies native sources, and removes files we replace.
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
RA_DIR="$ROOT_DIR/retroarch"
PATCH_DIR="$ROOT_DIR/patches"
RICOTTA_DIR="$ROOT_DIR/ricotta"

cd "$RA_DIR"

# Apply all patches (idempotent)
echo "Applying patches..."
for patch in "$PATCH_DIR"/*.patch; do
    name="$(basename "$patch")"
    if git apply --check "$patch" 2>/dev/null; then
        git apply "$patch"
        echo "  ✓ $name"
    else
        echo "  - $name (already applied)"
    fi
done

# Remove upstream RetroActivityFuture.java — we replace it with Kotlin
rm -f "$RA_DIR/pkg/android/phoenix/src/com/retroarch/browser/retroactivity/RetroActivityFuture.java"
echo "Removed upstream RetroActivityFuture.java (replaced by Kotlin)"

# Copy bridge sources into RetroArch's JNI directory
# Only copy when changed so the destination mtime is stable and ndk-build's
# incremental compilation isn't invalidated on every build.
for bridge_file in ricotta_bridge.c ricotta_osd.h; do
    BRIDGE_SRC="$RICOTTA_DIR/jni/$bridge_file"
    BRIDGE_DST="$RA_DIR/pkg/android/phoenix-common/jni/$bridge_file"
    [ -f "$BRIDGE_DST" ] && chmod u+w "$BRIDGE_DST"
    if ! cmp -s "$BRIDGE_SRC" "$BRIDGE_DST"; then
        cp "$BRIDGE_SRC" "$BRIDGE_DST"
        echo "Copied $bridge_file"
    else
        echo "$bridge_file up to date"
    fi
done

# Ensure Android.mk includes our bridge file and define
ANDROID_MK="$RA_DIR/pkg/android/phoenix-common/jni/Android.mk"
if ! grep -q "HAVE_RICOTTA_IGM" "$ANDROID_MK"; then
    if [[ "$OSTYPE" == darwin* ]]; then
        sed -i '' 's|griffin/griffin_cpp.cpp|griffin/griffin_cpp.cpp \\\
								ricotta_bridge.c|' "$ANDROID_MK"
        sed -i '' '/^LOCAL_MODULE := retroarch-activity/a\
\
DEFINES += -DHAVE_RICOTTA_IGM' "$ANDROID_MK"
    else
        sed -i 's|griffin/griffin_cpp.cpp|griffin/griffin_cpp.cpp \\\
								ricotta_bridge.c|' "$ANDROID_MK"
        sed -i '/^LOCAL_MODULE := retroarch-activity/a\\nDEFINES += -DHAVE_RICOTTA_IGM' "$ANDROID_MK"
    fi
    echo "Updated Android.mk"
else
    echo "Android.mk already configured."
fi

# The patched RetroArch sources include ricotta_osd.h, which lives beside the bridge
# rather than on any of RetroArch's own include paths. Separate from the block above
# so a tree configured before this existed still picks it up.
if ! grep -q 'LOCAL_C_INCLUDES += \$(LOCAL_PATH)$' "$ANDROID_MK"; then
    if [[ "$OSTYPE" == darwin* ]]; then
        sed -i '' '/^DEFINES += -DHAVE_RICOTTA_IGM/a\
LOCAL_C_INCLUDES += $(LOCAL_PATH)' "$ANDROID_MK"
    else
        sed -i '/^DEFINES += -DHAVE_RICOTTA_IGM/a\LOCAL_C_INCLUDES += $(LOCAL_PATH)' "$ANDROID_MK"
    fi
    echo "Added ricotta include path to Android.mk"
fi

# Force-include ricotta_osd.h into every translation unit rather than patching an include into each
# file that needs it. Twelve of the patch set's forty-two hunks existed only to add that include,
# every one a context-anchored diff into a file upstream churns, rebased by hand at each bump for no
# behaviour. The header is guarded and carries extern "C" so this is safe in the C++ units too.
if ! grep -q 'include ricotta_osd.h' "$ANDROID_MK"; then
    if [[ "$OSTYPE" == darwin* ]]; then
        sed -i '' '/^DEFINES += -DHAVE_RICOTTA_IGM/a\
DEFINES += -include ricotta_osd.h' "$ANDROID_MK"
    else
        sed -i '/^DEFINES += -DHAVE_RICOTTA_IGM/a\DEFINES += -include ricotta_osd.h' "$ANDROID_MK"
    fi
    echo "Added ricotta_osd.h force-include to Android.mk"
fi

# Ensure HAVE_RICOTTA_OSD is defined (Cannoli OSD replaces RetroArch's native notifications)
if ! grep -q "HAVE_RICOTTA_OSD" "$ANDROID_MK"; then
    if [[ "$OSTYPE" == darwin* ]]; then
        sed -i '' '/^LOCAL_MODULE := retroarch-activity/a\
\
DEFINES += -DHAVE_RICOTTA_OSD' "$ANDROID_MK"
    else
        sed -i '/^LOCAL_MODULE := retroarch-activity/a\\nDEFINES += -DHAVE_RICOTTA_OSD' "$ANDROID_MK"
    fi
    echo "Added HAVE_RICOTTA_OSD define"
fi

# Read-only so an edit fails loudly. Both are generated: this script copies the bridge from
# ricotta/jni, and cannoli_ra_settings_strings.patch creates the strings file. Editing either in
# place is silently lost, because the next clean run overwrites it and a dirty tree makes
# git apply --check fail, which this script reports as "already applied".
#
# Deliberately not applied to the upstream files the patches modify: git apply --check cannot read
# a file it has no write access to, and every patch would then look already applied.
for generated in \
    "$BRIDGE_DST" \
    "$RA_DIR/pkg/android/phoenix/res/values/strings_cannoli.xml"
do
    [ -f "$generated" ] && chmod a-w "$generated"
done

echo "Ready to build."
