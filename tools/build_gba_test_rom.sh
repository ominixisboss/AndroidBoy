#!/bin/sh
# Assembles tests/gbarom.s into tests/gbarom.gba with clang and lld (both with ARM support).
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
clang --target=armv4t-none-eabi -c "$ROOT/tests/gbarom.s" -o "$TMP/gbarom.o"
ld.lld -Ttext=0x08000000 -e _start "$TMP/gbarom.o" -o "$TMP/gbarom.elf"
llvm-objcopy -O binary -j .text "$TMP/gbarom.elf" "$ROOT/tests/gbarom.gba"
# Header checksum over 0xA0-0xBC, and pad to a whole kilobyte like a real cartridge image.
python3 - "$ROOT/tests/gbarom.gba" <<'PY'
import sys
path = sys.argv[1]
rom = bytearray(open(path, 'rb').read())
rom[0xBD] = (-(sum(rom[0xA0:0xBD]) + 0x19)) & 0xFF
rom += b'\xff' * (-len(rom) % 1024)
open(path, 'wb').write(rom)
PY
