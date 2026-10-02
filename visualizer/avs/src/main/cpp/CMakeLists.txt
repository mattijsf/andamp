# SPDX-License-Identifier: GPL-3.0-or-later

cmake_minimum_required(VERSION 3.22.1)

project(andamp_avs LANGUAGES C CXX)

set(EVAL_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/projectm-eval")

if(NOT EXISTS "${EVAL_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
            "third_party/projectm-eval is empty. Run: git submodule update --init --recursive")
endif()

# projectm-eval regenerates its flex/bison parser into its own source tree
# whenever flex and bison are installed, which dirties the submodule and
# makes the output depend on the host's tool versions. Refusing to find them
# leaves the generated files upstream ships. Same reason as in :visualizer:projectm.
set(CMAKE_DISABLE_FIND_PACKAGE_BISON ON CACHE BOOL "" FORCE)
set(CMAKE_DISABLE_FIND_PACKAGE_FLEX ON CACHE BOOL "" FORCE)

set(BUILD_NS_EEL_SHIM OFF CACHE BOOL "" FORCE)
set(BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)

add_subdirectory("${EVAL_DIR}" projectm-eval)

add_library(andamp_avs SHARED avs_eel_jni.cpp)

# Static: this is one small evaluator, it is MIT licensed, and nothing outside
# this module calls it. :visualizer:projectm links its own copy, so the two
# modules do not depend on each other.
target_link_libraries(andamp_avs
        PRIVATE
        projectM::Eval
        log
        )
