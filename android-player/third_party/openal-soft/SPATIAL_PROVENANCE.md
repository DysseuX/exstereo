# Vendored OpenAL Soft

- Upstream: https://github.com/kcat/openal-soft
- Stable release: **1.25.2**, fetched 2026-09-22.
- Source archive: https://codeload.github.com/kcat/openal-soft/tar.gz/refs/tags/1.25.2
- Archive SHA-256: `fb27e5839aa11f0e5b9d33756965291fad5d6909ab928ea1f796f4a1a6877894`
- Archive bytes: 1,407,972.
- Original sources, default HRTF, bundled fmt/GSL and their licenses retained.
  Main library: `COPYING`, `LICENSE*`; bundled dependencies retain notices.

Local compatibility patch: the static-library dependency expression in
`CMakeLists.txt` changes `$<BUILD_LOCAL_INTERFACE:alsoft::fmt>` to
`$<BUILD_INTERFACE:alsoft::fmt>`. `BUILD_LOCAL_INTERFACE` requires CMake 3.26;
the project uses the Android SDK's CMake 3.22.1. Installation/export is disabled
for this embedded build, so the two expressions have the same build behavior.
No upstream DSP code is changed.

Project configuration is in `app/src/main/cpp/cmake/openal.cmake`: static/PIC,
C++20 in upstream targets, embedded HRTF, no hardware output backends,
no examples/utilities/configurator/install, no C++ modules. The final Android
JNI shared library keeps the enclosing project's 16 KiB ELF page alignment.
