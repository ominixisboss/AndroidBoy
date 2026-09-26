cmake_minimum_required(VERSION 3.22.1)
project(androidboy C)

# The vendored SameBoy source tree lives at the repository root.
set(SAMEBOY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../sameboy)

file(STRINGS ${SAMEBOY_DIR}/version.mk SAMEBOY_VERSION_LINE REGEX "^VERSION")
string(REGEX REPLACE "^VERSION[ :]*=[ ]*" "" SAMEBOY_VERSION "${SAMEBOY_VERSION_LINE}")

# Same core source set as SameBoy's libretro port.
set(SAMEBOY_SOURCES
    ${SAMEBOY_DIR}/Core/apu.c
    ${SAMEBOY_DIR}/Core/camera.c
    ${SAMEBOY_DIR}/Core/display.c
    ${SAMEBOY_DIR}/Core/gb.c
    ${SAMEBOY_DIR}/Core/joypad.c
    ${SAMEBOY_DIR}/Core/mbc.c
    ${SAMEBOY_DIR}/Core/memory.c
    ${SAMEBOY_DIR}/Core/random.c
    ${SAMEBOY_DIR}/Core/rewind.c
    ${SAMEBOY_DIR}/Core/cheats.c
    ${SAMEBOY_DIR}/Core/printer.c
    ${SAMEBOY_DIR}/Core/rumble.c
    ${SAMEBOY_DIR}/Core/save_state.c
    ${SAMEBOY_DIR}/Core/sgb.c
    ${SAMEBOY_DIR}/Core/sm83_cpu.c
    ${SAMEBOY_DIR}/Core/timing.c
)

add_library(sameboy STATIC ${SAMEBOY_SOURCES})
target_include_directories(sameboy PUBLIC ${SAMEBOY_DIR})
target_compile_definitions(sameboy PUBLIC
    GB_VERSION="${SAMEBOY_VERSION}"
    GB_DISABLE_TIMEKEEPING
    GB_DISABLE_DEBUGGER
    GB_DISABLE_CHEAT_SEARCH
    _GNU_SOURCE
)
# The core sees its own internals; frontends (emulator.c, jni_bridge.c) must not.
target_compile_definitions(sameboy PRIVATE GB_INTERNAL)
target_compile_options(sameboy PRIVATE
    -std=gnu11
    -O3
    -ffast-math
    -Wno-multichar
    -Wno-unused-result
    -Wno-deprecated-declarations
)

# RetroAchievements client (rcheevos, MIT), vendored unmodified. Only what a cartridge-based
# system needs: the client, web API, achievement runtime and ROM hashing.
set(RCHEEVOS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/rcheevos)
file(GLOB RCHEEVOS_SOURCES
    ${RCHEEVOS_DIR}/src/rapi/*.c
    ${RCHEEVOS_DIR}/src/rcheevos/*.c
)
list(APPEND RCHEEVOS_SOURCES
    ${RCHEEVOS_DIR}/src/rc_client.c
    ${RCHEEVOS_DIR}/src/rc_compat.c
    ${RCHEEVOS_DIR}/src/rc_util.c
    ${RCHEEVOS_DIR}/src/rc_version.c
    ${RCHEEVOS_DIR}/src/rhash/hash.c
    ${RCHEEVOS_DIR}/src/rhash/hash_rom.c
    ${RCHEEVOS_DIR}/src/rhash/md5.c
)
add_library(rcheevos STATIC ${RCHEEVOS_SOURCES})
target_include_directories(rcheevos PUBLIC ${RCHEEVOS_DIR}/include)
target_compile_definitions(rcheevos PUBLIC
    RC_CLIENT_SUPPORTS_HASH
    RC_HASH_NO_DISC
    RC_HASH_NO_ENCRYPTED
    RC_HASH_NO_ZIP
)
target_compile_options(rcheevos PRIVATE -std=gnu11 -O2 -Wno-unused-function)

# Game Boy Advance: mGBA (MPL-2.0), vendored with only what its GBA core needs (see
# third_party/mgba/ANDROIDBOY.md). LIBMGBA_ONLY builds just the static library: no frontends
# and no dependencies (no zlib, PNG or SQLite), and M_CORE_GB off leaves Game Boy games to SameBoy.
set(MGBA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/mgba)
set(LIBMGBA_ONLY ON)
set(M_CORE_GBA ON)
set(M_CORE_GB OFF)
set(ENABLE_SCRIPTING OFF)
add_subdirectory(${MGBA_DIR} mgba EXCLUDE_FROM_ALL)
# Always optimized, like SameBoy: a debug build should still run games at full speed.
target_compile_options(mgba PRIVATE -O3 -Wno-unused-parameter -Wno-sign-compare)

# mGBA builds with private definitions (USE_PTHREADS and the like) that change the layout of
# structs in its headers, so the code using those headers must see exactly the same ones.
# Some are on the target, some (USE_PTHREADS, M_CORE_GBA) on mGBA's directory.
get_target_property(MGBA_TARGET_DEFINITIONS mgba COMPILE_DEFINITIONS)
get_directory_property(MGBA_DIRECTORY_DEFINITIONS DIRECTORY ${MGBA_DIR} COMPILE_DEFINITIONS)
set_source_files_properties(gba_core.c PROPERTIES
    COMPILE_DEFINITIONS "${MGBA_TARGET_DEFINITIONS};${MGBA_DIRECTORY_DEFINITIONS}")

add_library(androidboy SHARED emulator.c jni_bridge.c achievements.c gba_core.c)
target_compile_options(androidboy PRIVATE -std=gnu11 -O2 -Wall -Wno-multichar)
target_link_libraries(androidboy sameboy rcheevos mgba android log m)
