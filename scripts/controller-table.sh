#!/bin/sh

set -e

SERIAL="${1:-${DEVICE:-}}"

if [ -z "$SERIAL" ]; then
    devices=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
    count=$(printf '%s\n' "$devices" | grep -c . || true)

    if [ "$count" -eq 0 ]; then
        echo "No adb devices attached." >&2
        exit 1
    elif [ "$count" -eq 1 ]; then
        SERIAL="$devices"
    else
        echo "Attached devices:" >&2
        i=1
        for d in $devices; do
            model=$(adb -s "$d" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
            printf '  %d) %-24s %s\n' "$i" "$d" "$model" >&2
            i=$((i + 1))
        done
        printf 'Select [1-%d]: ' "$((i - 1))" >&2
        read -r choice
        SERIAL=$(printf '%s\n' "$devices" | sed -n "${choice}p")
        [ -n "$SERIAL" ] || { echo "Invalid selection." >&2; exit 1; }
    fi
fi

MODEL=$(adb -s "$SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
echo "Device: $SERIAL  ($MODEL)"
echo

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

adb -s "$SERIAL" shell 'ls /dev/input/' 2>/dev/null | tr -d '\r' > "$TMP/nodes"
adb -s "$SERIAL" shell 'dumpsys input' 2>/dev/null | tr -d '\r' > "$TMP/dumpsys"
adb -s "$SERIAL" shell 'cat /proc/bus/input/devices' 2>/dev/null | tr -d '\r' > "$TMP/proc" || true

grep -oE 'Path: /dev/input/event[0-9]+' "$TMP/dumpsys" 2>/dev/null \
    | sed 's|.*/||' | sort -u > "$TMP/android_nodes" || true

if [ ! -s "$TMP/proc" ]; then
    echo "WARNING: /proc/bus/input/devices unreadable (SELinux). Falling back to dumpsys only," >&2
    echo "so suppressed nodes cannot be detected on this device." >&2
    echo
    awk '
        /^    [0-9]+: / { name = $0; sub(/^    [0-9]+: /, "", name); node = ""; next }
        /^      Path: / { node = $0; sub(/.*\//, "", node); next }
        /^      Identifier: / {
            v = $0; sub(/.*vendor=0x/, "", v); sub(/,.*/, "", v)
            p = $0; sub(/.*product=0x/, "", p); sub(/,.*/, "", p)
            if (v != "0000" && node != "")
                printf "  %-10s %s:%s  %s\n", node, v, p, name
        }
    ' "$TMP/dumpsys"
    exit 0
fi

awk -v nodes="$TMP/nodes" -v android="$TMP/android_nodes" '
    BEGIN {
        while ((getline line < nodes) > 0) have[line] = 1
        while ((getline line < android) > 0) exposed[line] = 1
        RS = ""
    }
    {
        vend = prod = name = node = bus = ""
        n = split($0, lines, "\n")
        for (i = 1; i <= n; i++) {
            if (lines[i] ~ /^I: /) {
                bus = lines[i]; sub(/.*Bus=/, "", bus); sub(/ .*/, "", bus)
                vend = lines[i]; sub(/.*Vendor=/, "", vend); sub(/ .*/, "", vend)
                prod = lines[i]; sub(/.*Product=/, "", prod); sub(/ .*/, "", prod)
            }
            if (lines[i] ~ /^N: Name=/) {
                name = lines[i]; sub(/^N: Name="/, "", name); sub(/"$/, "", name)
            }
            if (lines[i] ~ /^H: Handlers=/) {
                node = lines[i]
                if (match(node, /event[0-9]+/)) node = substr(node, RSTART, RLENGTH)
                else node = ""
            }
        }
        if (vend == "0000" || node == "") next

        vidpid = vend ":" prod
        if (bus == "0003") virtual[vidpid]++
        rows[++r] = node "\t" vidpid "\t" name "\t" bus
    }
    END {
        printf "  %-9s %-11s %-7s %-8s %s\n", "NODE", "VID:PID", "/dev", "ANDROID", "NAME"
        for (i = 1; i <= r; i++) {
            split(rows[i], f, "\t")
            node = f[1]; vidpid = f[2]; name = f[3]; bus = f[4]

            dev = (node in have) ? "yes" : "NO"
            app = (node in exposed) ? "yes" : "no"

            note = ""
            if (!(node in have)) note = "  <- SUPPRESSED, real node hidden"
            else if (bus == "0003" && virtual[vidpid] > 1) note = "  <- uinput clone"

            printf "  %-9s %-11s %-7s %-8s %s%s\n", node, vidpid, dev, app, name, note
        }

        shared = ""
        for (k in virtual) if (virtual[k] > 1) shared = k
        if (shared != "") {
            printf "\n  %s is claimed by %d uinput devices, so it is the host virtual id rather than any real pad.\n",
                shared, virtual[shared]
        }
    }
' "$TMP/proc"
