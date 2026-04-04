#!/bin/sh
# Generate extreme dummy data for collection performance testing.
# Targets: 2000 collections, 11200 ROMs, deep nesting
# Run via: adb shell sh /sdcard/generate_test_data.sh

ROOT="/storage/689D-DA19/CannoliTest"
ROMS="$ROOT/Roms"
COLLECTIONS="$ROOT/Collections"
CONFIG="$ROOT/Config"

PLATFORMS="GB GBA SNES NES N64 GBC PSX MD SMS GG PCE LYNX WSC NGP"

echo "=== Creating directories ==="
for tag in $PLATFORMS; do
    mkdir -p "$ROMS/$tag"
done
mkdir -p "$COLLECTIONS"
mkdir -p "$CONFIG"

echo "=== Creating dummy ROM files (800 per platform = 11200 total) ==="
for tag in $PLATFORMS; do
    i=1
    while [ $i -le 800 ]; do
        touch "$ROMS/$tag/Game $i - $tag Edition.rom"
        i=$((i + 1))
    done
    echo "  $tag: 800 ROMs"
done

echo "=== Creating 2000 collections ==="

# 200 top-level "Series" collections with 30-120 entries each
c=1
while [ $c -le 200 ]; do
    name="Series $c"
    file="$COLLECTIONS/$name.txt"
    > "$file"
    tag_idx=$(( (c % 14) + 1 ))
    tag=$(echo $PLATFORMS | cut -d' ' -f$tag_idx)
    i=1
    count=$(( 30 + (c % 91) ))
    while [ $i -le $count ]; do
        game_num=$(( (c * 3 + i) % 800 + 1 ))
        echo "$ROMS/$tag/Game $game_num - $tag Edition.rom" >> "$file"
        i=$((i + 1))
    done
    c=$((c + 1))
done
echo "  Created 200 Series collections (30-120 entries each)"

# 400 "Genre" collections (children of Series) with 15-60 entries
c=1
while [ $c -le 400 ]; do
    name="Genre $c"
    file="$COLLECTIONS/$name.txt"
    > "$file"
    tag_idx=$(( (c % 14) + 1 ))
    tag=$(echo $PLATFORMS | cut -d' ' -f$tag_idx)
    i=1
    count=$(( 15 + (c % 46) ))
    while [ $i -le $count ]; do
        game_num=$(( (c * 7 + i) % 800 + 1 ))
        echo "$ROMS/$tag/Game $game_num - $tag Edition.rom" >> "$file"
        i=$((i + 1))
    done
    c=$((c + 1))
done
echo "  Created 400 Genre collections (15-60 entries each)"

# 600 "Sub" collections (children of Genre) with 5-30 entries
c=1
while [ $c -le 600 ]; do
    name="Sub $c"
    file="$COLLECTIONS/$name.txt"
    > "$file"
    tag_idx=$(( (c % 14) + 1 ))
    tag=$(echo $PLATFORMS | cut -d' ' -f$tag_idx)
    i=1
    count=$(( 5 + (c % 26) ))
    while [ $i -le $count ]; do
        game_num=$(( (c * 11 + i) % 800 + 1 ))
        echo "$ROMS/$tag/Game $game_num - $tag Edition.rom" >> "$file"
        i=$((i + 1))
    done
    c=$((c + 1))
done
echo "  Created 600 Sub collections (5-30 entries each)"

# 400 "Leaf" collections (deepest level) with 3-15 entries
c=1
while [ $c -le 400 ]; do
    name="Leaf $c"
    file="$COLLECTIONS/$name.txt"
    > "$file"
    tag_idx=$(( (c % 14) + 1 ))
    tag=$(echo $PLATFORMS | cut -d' ' -f$tag_idx)
    i=1
    count=$(( 3 + (c % 13) ))
    while [ $i -le $count ]; do
        game_num=$(( (c * 13 + i) % 800 + 1 ))
        echo "$ROMS/$tag/Game $game_num - $tag Edition.rom" >> "$file"
        i=$((i + 1))
    done
    c=$((c + 1))
done
echo "  Created 400 Leaf collections (3-15 entries each)"

# 396 standalone top-level collections with 10-80 entries (to hit ~2000 total + Favorites)
c=1
while [ $c -le 396 ]; do
    name="Standalone $c"
    file="$COLLECTIONS/$name.txt"
    > "$file"
    tag_idx=$(( (c % 14) + 1 ))
    tag=$(echo $PLATFORMS | cut -d' ' -f$tag_idx)
    i=1
    count=$(( 10 + (c % 71) ))
    while [ $i -le $count ]; do
        game_num=$(( (c * 17 + i) % 800 + 1 ))
        echo "$ROMS/$tag/Game $game_num - $tag Edition.rom" >> "$file"
        i=$((i + 1))
    done
    c=$((c + 1))
done
echo "  Created 396 Standalone collections (10-80 entries each)"

# Favorites with 600 entries
fav="$COLLECTIONS/Favorites.txt"
> "$fav"
i=1
while [ $i -le 600 ]; do
    tag_idx=$(( (i % 14) + 1 ))
    tag=$(echo $PLATFORMS | cut -d' ' -f$tag_idx)
    game_num=$(( i % 800 + 1 ))
    echo "$ROMS/$tag/Game $game_num - $tag Edition.rom" >> "$fav"
    i=$((i + 1))
done
echo "  Created Favorites (600 entries)"

echo "=== Building deep nesting hierarchy ==="

parents_file="$CONFIG/collection_parents.txt"
> "$parents_file"

# Genre 1-400 → parent is Series ceil(c/2)
c=1
while [ $c -le 400 ]; do
    parent_num=$(( (c + 1) / 2 ))
    echo "Genre $c=Series $parent_num" >> "$parents_file"
    c=$((c + 1))
done

# Sub 1-600 → parent is Genre ceil(c/1.5)
c=1
while [ $c -le 600 ]; do
    parent_num=$(( (c * 2 + 2) / 3 ))
    if [ $parent_num -gt 400 ]; then parent_num=400; fi
    echo "Sub $c=Genre $parent_num" >> "$parents_file"
    c=$((c + 1))
done

# Leaf 1-200 → parent is Sub c (depth 4)
c=1
while [ $c -le 200 ]; do
    echo "Leaf $c=Sub $c" >> "$parents_file"
    c=$((c + 1))
done

# Leaf 201-300 → parent is Leaf c-200 (depth 5)
c=201
while [ $c -le 300 ]; do
    echo "Leaf $c=Leaf $((c - 200))" >> "$parents_file"
    c=$((c + 1))
done

# Leaf 301-360 → parent is Leaf c-100 (depth 6)
c=301
while [ $c -le 360 ]; do
    echo "Leaf $c=Leaf $((c - 100))" >> "$parents_file"
    c=$((c + 1))
done

# Leaf 361-400 → parent is Leaf c-60 (depth 7)
c=361
while [ $c -le 400 ]; do
    echo "Leaf $c=Leaf $((c - 60))" >> "$parents_file"
    c=$((c + 1))
done

echo "  Wrote collection_parents.txt ($(wc -l < "$parents_file") lines)"

echo ""
echo "=== Summary ==="
echo "  ROM files:      11200 (14 platforms x 800)"
echo "  Collections:    1997 (200 series + 400 genre + 600 sub + 400 leaf + 396 standalone + 1 favorites)"
echo "  Top-level:      597 (200 series + 396 standalone + 1 favorites)"
echo "  Nested depth:   up to 7 levels"
echo "  Nesting entries: $(wc -l < "$parents_file")"
echo ""
echo "=== Done ==="
