# SPDX-License-Identifier: GPL-3.0-or-later

cmake_minimum_required(VERSION 3.22.1)

project(andamp_projectm LANGUAGES C CXX)

set(PROJECTM_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/projectm")

if(NOT EXISTS "${PROJECTM_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
            "third_party/projectm is empty. Run: git submodule update --init --recursive")
endif()

# projectM reads its configuration from cache variables, so they have to be set
# before add_subdirectory(). FORCE because a stale cache from an earlier
# configure would otherwise win.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)          # LGPL: relink must stay possible
set(ENABLE_PLAYLIST ON CACHE BOOL "" FORCE)            # libprojectM-4-playlist
set(ENABLE_SDL_UI OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_DOCS OFF CACHE BOOL "" FORCE)
set(ENABLE_SYSTEM_GLM OFF CACHE BOOL "" FORCE)         # no system libs on Android; use the vendored copies
set(ENABLE_SYSTEM_PROJECTM_EVAL OFF CACHE BOOL "" FORCE)
# Has projectM write to its error stream which preset it loads and which parts of it fail to
# compile; the JNI glue reads that stream, so the preset selector can say what a preset lacks.
set(ENABLE_DEBUG_MILKDROP_PRESET ON CACHE BOOL "" FORCE)
# ENABLE_GLES is forced ON for Android by projectM's own CMakeLists.txt.

# projectm-eval regenerates its flex/bison parser into its own source tree
# whenever flex and bison are installed, which dirties the submodule and makes
# the build depend on the host's tool versions. Refusing to find them leaves
# the generated files upstream ships in place.
set(CMAKE_DISABLE_FIND_PACKAGE_BISON ON CACHE BOOL "" FORCE)
set(CMAKE_DISABLE_FIND_PACKAGE_FLEX ON CACHE BOOL "" FORCE)

# Without this a debug variant produces libprojectM-4d.so, and the packaged
# library name would differ per build type.
set(ENABLE_DEBUG_POSTFIX OFF CACHE BOOL "" FORCE)

# projectM writes CMAKE_PROJECT_VERSION into its version header, and its own
# project() sets that only while it is undefined. The first configure caches
# it; on every later one the project() above, which has no version, blanks the
# cached value, and the header comes out without numbers. Dropping it here
# lets projectM's project() set it on every configure.
foreach(part "" _MAJOR _MINOR _PATCH _TWEAK)
    unset(CMAKE_PROJECT_VERSION${part})
    unset(CMAKE_PROJECT_VERSION${part} CACHE)
endforeach()

add_subdirectory("${PROJECTM_DIR}" projectm)

add_library(andamp_projectm SHARED projectm_jni.cpp)

target_link_libraries(andamp_projectm
        PRIVATE
        libprojectM::projectM
        libprojectM::playlist
        log
        )
