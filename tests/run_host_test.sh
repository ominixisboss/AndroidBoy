#!/bin/sh
# Builds the SameBoy core plus the app's emulator wrapper for the host and runs the smoke test.
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/androidboy-host-test"
mkdir -p "$OUT"
CC="${CC:-cc}"
VERSION="$(sed -n 's/^VERSION *:= *//p' "$ROOT/sameboy/version.mk")"
FLAGS="-std=gnu11 -O2 -Wall -Wno-multichar -Wno-unused-result -D_GNU_SOURCE \
    -DGB_VERSION=\"$VERSION\" -DGB_DISABLE_TIMEKEEPING \
    -DGB_DISABLE_DEBUGGER -DGB_DISABLE_CHEAT_SEARCH \
    -I$ROOT/sameboy -I$ROOT/app/src/main/cpp"
cd "$OUT"
# The core is compiled with GB_INTERNAL, frontends without it, as in SameBoy's own Makefile.
$CC $FLAGS -DGB_INTERNAL -c \
    "$ROOT"/sameboy/Core/apu.c "$ROOT"/sameboy/Core/camera.c "$ROOT"/sameboy/Core/display.c \
    "$ROOT"/sameboy/Core/gb.c "$ROOT"/sameboy/Core/joypad.c "$ROOT"/sameboy/Core/mbc.c \
    "$ROOT"/sameboy/Core/memory.c "$ROOT"/sameboy/Core/random.c "$ROOT"/sameboy/Core/rewind.c "$ROOT"/sameboy/Core/rumble.c "$ROOT"/sameboy/Core/cheats.c \
    "$ROOT"/sameboy/Core/save_state.c "$ROOT"/sameboy/Core/sgb.c "$ROOT"/sameboy/Core/sm83_cpu.c \
    "$ROOT"/sameboy/Core/timing.c
$CC $FLAGS "$ROOT/app/src/main/cpp/emulator.c" "$ROOT/tests/host_test.c" "$OUT"/*.o -lm -o "$OUT/host_test"
"$OUT/host_test" "$ROOT/tests/testrom.gb" "$ROOT/app/src/main/assets/BootROMs"

# RetroAchievements: rcheevos' client against the emulator's memory, with a stand-in server.
RC="$ROOT/third_party/rcheevos"
RC_FLAGS="-std=gnu11 -O2 -DRC_CLIENT_SUPPORTS_HASH -DRC_HASH_NO_DISC -DRC_HASH_NO_ENCRYPTED -DRC_HASH_NO_ZIP -I$RC/include"
mkdir -p "$OUT/rcheevos"
(cd "$OUT/rcheevos" && $CC $RC_FLAGS -w -c "$RC"/src/rapi/*.c "$RC"/src/rcheevos/*.c "$RC/src/rc_client.c" \
    "$RC/src/rc_compat.c" "$RC/src/rc_util.c" "$RC/src/rc_version.c" \
    "$RC/src/rhash/hash.c" "$RC/src/rhash/hash_rom.c" "$RC/src/rhash/md5.c")
$CC $FLAGS $RC_FLAGS "$ROOT/app/src/main/cpp/emulator.c" "$ROOT/tests/achievements_test.c" "$OUT"/*.o \
    "$OUT"/rcheevos/*.o -lm -lpthread -o "$OUT/achievements_test"
MD5="$(md5sum "$ROOT/tests/testrom.gb" | cut -d' ' -f1)"
"$OUT/achievements_test" "$ROOT/tests/testrom.gb" "$ROOT/app/src/main/assets/BootROMs" "$MD5"
