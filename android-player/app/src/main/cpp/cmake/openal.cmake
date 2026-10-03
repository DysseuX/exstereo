# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: GPL-3.0-only

# Include after THIRD_PARTY is defined. No playback/capture hardware backend is
# built: Media3 owns AudioTrack, and we only use ALC_SOFT_loopback for float PCM.
set(LIBTYPE STATIC CACHE STRING "OpenAL library type" FORCE)
foreach(option_name
    ALSOFT_UTILS ALSOFT_EXAMPLES ALSOFT_TESTS ALSOFT_INSTALL
    ALSOFT_INSTALL_CONFIG ALSOFT_INSTALL_HRTF_DATA ALSOFT_INSTALL_AMBDEC_PRESETS
    ALSOFT_INSTALL_EXAMPLES ALSOFT_INSTALL_UTILS ALSOFT_UPDATE_BUILD_VERSION
    ALSOFT_ENABLE_MODULES ALSOFT_EAX ALSOFT_DLOPEN ALSOFT_DLOPEN_NOTES ALSOFT_RTKIT
    ALSOFT_BACKEND_PIPEWIRE ALSOFT_BACKEND_PULSEAUDIO ALSOFT_BACKEND_ALSA
    ALSOFT_BACKEND_OSS ALSOFT_BACKEND_SOLARIS ALSOFT_BACKEND_SNDIO
    ALSOFT_BACKEND_WASAPI ALSOFT_BACKEND_DSOUND ALSOFT_BACKEND_WINMM
    ALSOFT_BACKEND_JACK ALSOFT_BACKEND_COREAUDIO ALSOFT_BACKEND_OBOE
    ALSOFT_BACKEND_OPENSL ALSOFT_BACKEND_PORTAUDIO ALSOFT_BACKEND_SDL3
    ALSOFT_BACKEND_SDL2 ALSOFT_BACKEND_WAVE)
    set(${option_name} OFF CACHE BOOL "Spatial loopback configuration" FORCE)
endforeach()
set(ALSOFT_NO_CONFIG_UTIL ON CACHE BOOL "No desktop configuration utility" FORCE)
set(ALSOFT_EMBED_HRTF_DATA ON CACHE BOOL "Keep builtin HRTF offline in APK" FORCE)
add_subdirectory("${THIRD_PARTY}/openal-soft" "${CMAKE_CURRENT_BINARY_DIR}/openal-soft" EXCLUDE_FROM_ALL)
set_target_properties(OpenAL PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(spatial_openal STATIC "${CMAKE_CURRENT_LIST_DIR}/../openal_renderer.cpp")
target_include_directories(spatial_openal PUBLIC "${CMAKE_CURRENT_LIST_DIR}/..")
target_link_libraries(spatial_openal PUBLIC OpenAL::OpenAL)
target_compile_options(spatial_openal PRIVATE -Wall -Wextra -Wpedantic -fvisibility=hidden)

if(NOT ANDROID)
    add_executable(openal_smoke "${CMAKE_CURRENT_LIST_DIR}/../tests/openal_smoke.cpp")
    target_link_libraries(openal_smoke PRIVATE spatial_openal)
endif()
