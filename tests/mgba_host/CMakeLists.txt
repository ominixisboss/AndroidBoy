# Builds mGBA's GBA core and the app's GBA backend (gba_core.c) as static libraries for the host
# test (tests/run_host_test.sh), the same way app/src/main/cpp/CMakeLists.txt does for the app.
cmake_minimum_required(VERSION 3.16)
project(mgba_host C)
set(LIBMGBA_ONLY ON)
set(M_CORE_GBA ON)
set(M_CORE_GB OFF)
set(ENABLE_SCRIPTING OFF)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/mgba mgba)

# gba_core.c must see the same definitions as the mGBA library (they change struct layouts).
set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp)
add_library(gba_core STATIC ${CPP_DIR}/gba_core.c)
# Some are on the target, some (USE_PTHREADS, M_CORE_GBA) on mGBA's directory.
get_target_property(MGBA_TARGET_DEFINITIONS mgba COMPILE_DEFINITIONS)
get_directory_property(MGBA_DIRECTORY_DEFINITIONS DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/mgba COMPILE_DEFINITIONS)
set_source_files_properties(${CPP_DIR}/gba_core.c PROPERTIES
    COMPILE_DEFINITIONS "${MGBA_TARGET_DEFINITIONS};${MGBA_DIRECTORY_DEFINITIONS}")
target_link_libraries(gba_core mgba)
