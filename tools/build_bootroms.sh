#!/bin/sh
# Rebuilds SameBoy's open-source boot ROMs into app/src/main/assets/BootROMs, with the AndroidBoy
# logo (tools/AndroidBoyLogo.png) in the start-up animation instead of SameBoy's. The logo must be
# a 128x24 indexed PNG with SameBoy's palette (white, grey, black), like sameboy/BootROMs/SameBoyLogo.png;
# Every 8x8 tile of it must be different: tools/bootrom-logo.patch (applied to a copy of the sources,
# so sameboy/ stays unmodified) takes out the Color boot ROM's special case for SameBoy's logo, whose
# E and B share tiles.
# The built binaries are committed, so this is only needed after updating sameboy/ or the logo.
# Requires RGBDS 0.7 or newer (https://rgbds.gbdev.io); set RGBDS=/path/to/rgbds/ if not on PATH.
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/sameboy/BootROMs"
DEST="$ROOT/app/src/main/assets/BootROMs"
WORK="$(mktemp -d)"
LOGO="${LOGO:-$ROOT/tools/AndroidBoyLogo.png}"
cp -r "$SRC" "$WORK/src"
patch -s -d "$WORK/src" -p1 < "$ROOT/tools/bootrom-logo.patch"
SRC="$WORK/src"
trap 'rm -rf "$WORK"' EXIT

# The boot ROMs include it as SameBoyLogo.pb12, whichever logo it is.
"${RGBDS}rgbgfx" -Z -u -c embedded -o "$WORK/SameBoyLogo.2bpp" "$LOGO"
# 48 unique tiles of 16 bytes: any repeated tile would shift the rest.
if [ "$(wc -c < "$WORK/SameBoyLogo.2bpp")" -ne 768 ]; then
    echo "The logo must be 128x24 with no two 8x8 tiles the same" >&2
    exit 1
fi
${CC:-cc} -std=c99 -Wall -Werror "$SRC/pb12.c" -o "$WORK/pb12"
"$WORK/pb12" < "$WORK/SameBoyLogo.2bpp" > "$WORK/SameBoyLogo.pb12"

mkdir -p "$DEST"
for rom in dmg mgb sgb sgb2 cgb cgb0 agb; do
    "${RGBDS}rgbasm" --include "$WORK/" --include "$SRC/" -o "$WORK/$rom.o" "$SRC/${rom}_boot.asm"
    "${RGBDS}rgblink" -x -o "$DEST/${rom}_boot.bin" "$WORK/$rom.o"
    echo "Built ${rom}_boot.bin"
done
