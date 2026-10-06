#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
RA_DIR="$ROOT_DIR/retroarch"
PATCH_DIR="$ROOT_DIR/patches"
RICOTTA_DIR="$ROOT_DIR/ricotta"

cd "$RA_DIR"

echo "Applying patches..."
for patch in "$PATCH_DIR"/*.patch; do
    name="$(basename "$patch")"
    if git apply --check "$patch" 2>/dev/null; then
        git apply "$patch"
        echo "  ✓ $name"
    elif git apply --reverse --check "$patch" 2>/dev/null; then
        echo "  - $name (already applied)"
    else
        echo "  ✗ $name" >&2
        echo "" >&2
        echo "This patch does not apply and is not already applied. The submodule has moved" >&2
        echo "under it, or it is applied in part. Resolve it by hand in retroarch/, then run" >&2
        echo "scripts/regenerate-patches.sh. Do not build from this tree." >&2
        exit 1
    fi
done

rm -f "$RA_DIR/pkg/android/phoenix/src/com/retroarch/browser/retroactivity/RetroActivityFuture.java"
echo "Removed upstream RetroActivityFuture.java (replaced by Kotlin)"

for bridge_file in ricotta_bridge.c ricotta_osd.h ricotta_menu_screens.h ricotta_key_aliases.h; do
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

if ! grep -q 'LOCAL_C_INCLUDES += \$(LOCAL_PATH)$' "$ANDROID_MK"; then
    if [[ "$OSTYPE" == darwin* ]]; then
        sed -i '' '/^DEFINES += -DHAVE_RICOTTA_IGM/a\
LOCAL_C_INCLUDES += $(LOCAL_PATH)' "$ANDROID_MK"
    else
        sed -i '/^DEFINES += -DHAVE_RICOTTA_IGM/a\LOCAL_C_INCLUDES += $(LOCAL_PATH)' "$ANDROID_MK"
    fi
    echo "Added ricotta include path to Android.mk"
fi

if ! grep -q 'include ricotta_osd.h' "$ANDROID_MK"; then
    if [[ "$OSTYPE" == darwin* ]]; then
        sed -i '' '/^DEFINES += -DHAVE_RICOTTA_IGM/a\
DEFINES += -include ricotta_osd.h' "$ANDROID_MK"
    else
        sed -i '/^DEFINES += -DHAVE_RICOTTA_IGM/a\DEFINES += -include ricotta_osd.h' "$ANDROID_MK"
    fi
    echo "Added ricotta_osd.h force-include to Android.mk"
fi

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

if ! grep -q "HAVE_RICOTTA_CHEEVOS" "$ANDROID_MK"; then
    if [[ "$OSTYPE" == darwin* ]]; then
        sed -i '' '/^LOCAL_MODULE := retroarch-activity/a\
\
DEFINES += -DHAVE_RICOTTA_CHEEVOS' "$ANDROID_MK"
    else
        sed -i '/^LOCAL_MODULE := retroarch-activity/a\\nDEFINES += -DHAVE_RICOTTA_CHEEVOS' "$ANDROID_MK"
    fi
    echo "Added HAVE_RICOTTA_CHEEVOS define"
fi

for generated in \
    "$BRIDGE_DST" \
    "$RA_DIR/pkg/android/phoenix/res/values/strings_cannoli.xml"
do
    [ -f "$generated" ] && chmod a-w "$generated"
done

echo "Ready to build."
