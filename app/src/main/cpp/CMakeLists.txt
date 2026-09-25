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
    GB_DISABLE_REWIND
    GB_DISABLE_DEBUGGER
    GB_DISABLE_CHEATS
    GB_DISABLE_CHEAT_SEARCH
    _GNU_SOURCE
)
target_compile_options(sameboy PRIVATE
    -std=gnu11
    -O3
    -ffast-math
    -Wno-multichar
    -Wno-unused-result
    -Wno-deprecated-declarations
)

add_library(androidboy SHARED emulator.c jni_bridge.c)
target_compile_options(androidboy PRIVATE -std=gnu11 -O2 -Wall -Wno-multichar)
target_link_libraries(androidboy sameboy android jnigraphics log m)
