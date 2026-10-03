# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: GPL-3.0-only

cmake_minimum_required(VERSION 3.22.1)
project(spatial_audio LANGUAGES C CXX)

# Keep build-machine paths out of assertions and debug information.
get_filename_component(EXSTEREO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
add_compile_options("-ffile-prefix-map=${EXSTEREO_ROOT}=/usr/src/exstereo")
if(ANDROID_NDK)
    add_compile_options("-ffile-prefix-map=${ANDROID_NDK}=/opt/android-ndk")
endif()
if(DEFINED ENV{HOME})
    add_compile_options("-ffile-prefix-map=$ENV{HOME}=/home/builder")
endif()

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# AGP invokes Ninja directly, so CMAKE_BUILD_PARALLEL_LEVEL alone is insufficient.
set_property(GLOBAL PROPERTY JOB_POOLS spatial_native=1)
set(CMAKE_JOB_POOL_COMPILE spatial_native)
set(CMAKE_JOB_POOL_LINK spatial_native)
get_filename_component(THIRD_PARTY "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party" ABSOLUTE)

if(ANDROID)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(STEAM_PLATFORM android/arm64-v8a)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(STEAM_PLATFORM android/x86_64)
    else()
        message(FATAL_ERROR "Spatial audio currently packages arm64-v8a and x86_64 only")
    endif()
else()
    set(STEAM_PLATFORM linux-x64)
endif()

add_library(phonon SHARED IMPORTED)
set_target_properties(phonon PROPERTIES
    IMPORTED_LOCATION "${THIRD_PARTY}/steam-audio/lib/${STEAM_PLATFORM}/libphonon.so"
    INTERFACE_INCLUDE_DIRECTORIES "${THIRD_PARTY}/steam-audio/include")
add_library(freesurround STATIC
    "${THIRD_PARTY}/freesurround/source/ChannelMaps.cpp"
    "${THIRD_PARTY}/freesurround/source/KissFFT.cpp"
    "${THIRD_PARTY}/freesurround/source/KissFFTR.cpp"
    "${THIRD_PARTY}/freesurround/source/FreeSurroundDecoder.cpp")
target_include_directories(freesurround PUBLIC "${THIRD_PARTY}/freesurround/include")
target_compile_options(freesurround PRIVATE -fvisibility=hidden)

include(cmake/openal.cmake)
include(cmake/ffmpeg.cmake)

# The same pinned, minimal FFmpeg supplies software decoding and DSP filters.
if(ANDROID)
    add_library(ffmpegJNI SHARED "${THIRD_PARTY}/media3-ffmpeg-1.9.2/jni/ffmpeg_jni.cc")
    target_link_libraries(ffmpegJNI PRIVATE spatial_avcodec spatial_swresample spatial_avutil log m)
    target_link_options(ffmpegJNI PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--exclude-libs,ALL")
else()
    # JNI headers only: a headless JDK has no AWT and no JVM library is linked.
    find_package(Java REQUIRED COMPONENTS Development)
    get_filename_component(SPATIAL_JAVAC "${Java_JAVAC_EXECUTABLE}" REALPATH)
    get_filename_component(SPATIAL_JAVA_BIN "${SPATIAL_JAVAC}" DIRECTORY)
    get_filename_component(SPATIAL_JAVA_HOME "${SPATIAL_JAVA_BIN}" DIRECTORY)
    find_path(SPATIAL_JNI_INCLUDE jni.h PATHS "${SPATIAL_JAVA_HOME}/include" REQUIRED)
    find_path(SPATIAL_JNI_PLATFORM jni_md.h PATHS "${SPATIAL_JNI_INCLUDE}/linux" REQUIRED)
    add_library(ffmpegJNI SHARED "${THIRD_PARTY}/media3-ffmpeg-1.9.2/jni/ffmpeg_jni.cc")
    target_include_directories(ffmpegJNI PRIVATE "${SPATIAL_JNI_INCLUDE}" "${SPATIAL_JNI_PLATFORM}")
    target_link_libraries(ffmpegJNI PRIVATE spatial_avcodec spatial_swresample spatial_avutil m Threads::Threads)
endif()

target_sources(ffmpegJNI PRIVATE dts_parser_jni.cpp)

add_library(spatial_core STATIC spatial_engine.cpp equalizer30.cpp pae_upmixer.cpp stereo_widener.cpp advanced_wideners.cpp virtual_room.cpp)
target_include_directories(spatial_core PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(spatial_core PUBLIC freesurround phonon spatial_openal spatial_ffmpeg)
target_compile_options(spatial_core PRIVATE -Wall -Wextra -Wpedantic -fvisibility=hidden)

# Debug APKs are the shipped test builds: keep symbols, but optimize realtime DSP.
# Otherwise hundreds of allpass sections run unoptimized on the audio thread.
if(ANDROID)
    foreach(target spatial_core freesurround spatial_ffmpeg spatial_openal OpenAL)
        target_compile_options(${target} PRIVATE -O2)
    endforeach()
endif()

if(ANDROID)
    add_library(spatial_audio SHARED spatial_jni.cpp)
    target_link_libraries(spatial_audio PRIVATE spatial_core)
    # FFmpeg's static ARM assembly references private tables directly. Hide
    # archive symbols, as FFmpeg's shared-library version script normally does;
    # only our JNI entry points form the public interface of this .so.
    target_link_options(spatial_audio PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--exclude-libs,ALL")
else()
    enable_testing()
    # Exercise the APK's actual JNI/AudioProcessor path in host integration tests.
    add_library(spatial_audio SHARED spatial_jni.cpp)
    target_include_directories(spatial_audio PRIVATE "${SPATIAL_JNI_INCLUDE}" "${SPATIAL_JNI_PLATFORM}")
    target_link_libraries(spatial_audio PRIVATE spatial_core)
    find_package(Threads REQUIRED)
    add_executable(equalizer30_smoke tests/equalizer30_smoke.cpp equalizer30.cpp)
    target_include_directories(equalizer30_smoke PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
    add_test(NAME equalizer30_smoke COMMAND equalizer30_smoke)
    add_executable(spatial_smoke tests/spatial_smoke.cpp)
    target_link_libraries(spatial_smoke PRIVATE spatial_core)
    add_test(NAME spatial_smoke COMMAND spatial_smoke)
    add_executable(pae_smoke tests/pae_smoke.cpp)
    target_link_libraries(pae_smoke PRIVATE spatial_core)
    add_test(NAME pae_smoke COMMAND pae_smoke)
    add_test(NAME openal_smoke COMMAND openal_smoke)
    add_executable(backends_smoke tests/backends_smoke.cpp)
    target_link_libraries(backends_smoke PRIVATE spatial_core)
    add_executable(ffmpeg_invalid_sofa tests/ffmpeg_invalid_sofa.cpp)
    target_link_libraries(ffmpeg_invalid_sofa PRIVATE spatial_core)
    add_executable(sofalizer_live tests/sofalizer_live.cpp)
    target_link_libraries(sofalizer_live PRIVATE spatial_core)
    add_executable(stereo_scene_smoke tests/stereo_scene_smoke.cpp)
    target_link_libraries(stereo_scene_smoke PRIVATE spatial_core)
    add_executable(ffmpeg_widening_smoke tests/ffmpeg_widening_smoke.cpp)
    target_link_libraries(ffmpeg_widening_smoke PRIVATE spatial_core)
    add_executable(stereo_widener_smoke tests/stereo_widener_smoke.cpp)
    target_link_libraries(stereo_widener_smoke PRIVATE spatial_core)
    add_executable(widening_engine_smoke tests/widening_engine_smoke.cpp)
    target_link_libraries(widening_engine_smoke PRIVATE spatial_core)
    add_executable(advanced_wideners_smoke tests/advanced_wideners_smoke.cpp)
    target_link_libraries(advanced_wideners_smoke PRIVATE spatial_core)
    add_executable(virtual_room_smoke tests/virtual_room_smoke.cpp)
    target_link_libraries(virtual_room_smoke PRIVATE spatial_core)
    add_executable(spatial_lifecycle tests/spatial_lifecycle.cpp)
    target_link_libraries(spatial_lifecycle PRIVATE spatial_core Threads::Threads)
endif()
