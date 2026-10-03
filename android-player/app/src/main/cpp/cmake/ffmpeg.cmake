# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: GPL-3.0-only

if(ANDROID)
    set(FILTER_PLATFORM "${ANDROID_ABI}")
else()
    set(FILTER_PLATFORM linux-x64)
endif()
get_filename_component(FILTER_PREFIX "${THIRD_PARTY}/../.native-deps/${FILTER_PLATFORM}/install" ABSOLUTE)
foreach(component avcodec avfilter swresample avutil mysofa)
    if(NOT EXISTS "${FILTER_PREFIX}/lib/lib${component}.a")
        message(FATAL_ERROR "Build filter dependencies first: bash scripts/run-limited.sh python3 android-player/tools/build_filter_deps.py ${FILTER_PLATFORM}")
    endif()
    add_library(spatial_${component} STATIC IMPORTED)
    set_target_properties(spatial_${component} PROPERTIES
        IMPORTED_LOCATION "${FILTER_PREFIX}/lib/lib${component}.a"
        INTERFACE_INCLUDE_DIRECTORIES "${FILTER_PREFIX}/include")
endforeach()
add_library(spatial_ffmpeg STATIC "${CMAKE_CURRENT_LIST_DIR}/../ffmpeg_filters.cpp")
find_package(Threads REQUIRED)
target_compile_definitions(spatial_ffmpeg PRIVATE __STDC_CONSTANT_MACROS)
target_include_directories(spatial_ffmpeg PUBLIC "${CMAKE_CURRENT_LIST_DIR}/..")
target_link_libraries(spatial_ffmpeg PUBLIC spatial_avfilter spatial_swresample spatial_avutil spatial_mysofa z m Threads::Threads)
