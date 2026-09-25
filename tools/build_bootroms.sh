#!/bin/sh
# Rebuilds SameBoy's open-source boot ROMs into app/src/main/assets/BootROMs.
# The built binaries are committed, so this is only needed after updating sameboy/.
# Requires RGBDS 0.7 or newer (https://rgbds.gbdev.io); set RGBDS=/path/to/rgbds/ if not on PATH.
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/sameboy/BootROMs"
DEST="$ROOT/app/src/main/assets/BootROMs"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

"${RGBDS}rgbgfx" -Z -u -c embedded -o "$WORK/SameBoyLogo.2bpp" "$SRC/SameBoyLogo.png"
${CC:-cc} -std=c99 -Wall -Werror "$SRC/pb12.c" -o "$WORK/pb12"
"$WORK/pb12" < "$WORK/SameBoyLogo.2bpp" > "$WORK/SameBoyLogo.pb12"

mkdir -p "$DEST"
for rom in dmg mgb sgb sgb2 cgb cgb0 agb; do
    "${RGBDS}rgbasm" --include "$WORK/" --include "$SRC/" -o "$WORK/$rom.o" "$SRC/${rom}_boot.asm"
    "${RGBDS}rgblink" -x -o "$DEST/${rom}_boot.bin" "$WORK/$rom.o"
    echo "Built ${rom}_boot.bin"
done
