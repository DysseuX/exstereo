#!/usr/bin/env python3
# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: GPL-3.0-only

"""Build pinned minimal FFmpeg/libmysofa archives. Run inside run-limited.sh.

No downloads, package installs or global changes; each ABI has its own cache.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
THIRD = ROOT / "third_party"


def run(args, cwd=None, env=None):
    print("+ " + " ".join(map(str, args)), flush=True)
    subprocess.run(list(map(str, args)), cwd=cwd, env=env, check=True)


def build(platform, sdk):
    cmake = sdk / "cmake/3.22.1/bin/cmake"
    if not cmake.is_file():
        cmake = Path(shutil.which("cmake") or "/missing/cmake")
    base = ROOT / ".native-deps" / platform
    prefix = base / "install"
    signature = {"revision": 5, "ffmpeg": "8.1.3", "libmysofa": "1.3.5",
                 "builder": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                 "sofalizer": hashlib.sha256((THIRD / "ffmpeg-8.1.3/libavfilter/af_sofalizer.c").read_bytes()).hexdigest(),
                 "platform": platform, "root": str(ROOT), "sdk": str(sdk)}
    stamp = base / "built.json"
    libraries = [prefix / "lib" / f"lib{n}.a" for n in ("avcodec", "avfilter", "avutil", "swresample", "mysofa")]
    if stamp.is_file() and json.loads(stamp.read_text()) == signature and all(p.is_file() for p in libraries):
        print(f"{platform}: filter dependencies up to date", flush=True)
        return
    base.mkdir(parents=True, exist_ok=True)
    # CMake caches absolute toolchain/source paths. After moving the SSD, keep
    # the previous generated tree but configure a fresh tree for this host/path.
    host_key = hashlib.sha256((str(ROOT) + str(sdk)).encode()).hexdigest()[:12]
    mybuild = base / ("mysofa-" + host_key)
    ffbuild = base / ("ffmpeg-" + host_key)
    config = [cmake, "-S", THIRD / "libmysofa-1.3.5", "-B", mybuild,
              "-DCMAKE_BUILD_TYPE=Release", "-DBUILD_TESTS=OFF", "-DBUILD_SHARED_LIBS=OFF",
              "-DBUILD_STATIC_LIBS=ON", "-DCMAKE_C_FLAGS=-fPIC", "-DCMAKE_INSTALL_LIBDIR=lib",
              f"-DCMAKE_INSTALL_PREFIX={prefix}"]
    cross = []
    if platform != "linux-x64":
        ndk = sdk / "ndk/28.2.13676358"
        toolchain = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
        arch, triple = {"arm64-v8a": ("aarch64", "aarch64-linux-android"),
                        "x86_64": ("x86_64", "x86_64-linux-android")}[platform]
        config += [f"-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake",
                   f"-DANDROID_ABI={platform}", "-DANDROID_PLATFORM=android-26"]
        cross = ["--enable-cross-compile", "--target-os=android", f"--arch={arch}",
                 f"--cc={toolchain}/{triple}26-clang", f"--cxx={toolchain}/{triple}26-clang++",
                 f"--ar={toolchain}/llvm-ar", f"--ranlib={toolchain}/llvm-ranlib",
                 f"--nm={toolchain}/llvm-nm", f"--strip={toolchain}/llvm-strip"]
    run(config)
    run([cmake, "--build", mybuild, "--parallel", "1"])
    run([cmake, "--install", mybuild])
    ffbuild.mkdir(exist_ok=True)
    env = os.environ.copy()
    env["PKG_CONFIG_LIBDIR"] = str(prefix / "lib/pkgconfig")
    env["PKG_CONFIG_PATH"] = ""
    run([THIRD / "ffmpeg-8.1.3/configure", f"--prefix={prefix}", "--disable-everything",
         "--disable-autodetect", "--disable-programs", "--disable-doc", "--disable-debug",
         "--enable-avcodec", "--disable-avformat", "--disable-avdevice", "--disable-swscale",
         "--enable-decoder=aac,alac,flac,opus,vorbis,mp3,ac3,eac3,dca,ape",
         "--enable-parser=dca",
         "--enable-avfilter", "--enable-swresample", "--enable-static", "--disable-shared",
         "--enable-pic", "--disable-x86asm", "--enable-small", "--enable-libmysofa",
         "--enable-filter=abuffer,abuffersink,aformat,aresample,surround,sofalizer,extrastereo,stereowiden,haas",
         "--pkg-config-flags=--static", f"--extra-cflags=-I{prefix}/include -fPIC",
         f"--extra-ldflags=-L{prefix}/lib", "--extra-libs=-lz -lm", *cross], cwd=ffbuild, env=env)
    run(["make", "-j1"], cwd=ffbuild, env=env)
    run(["make", "install"], cwd=ffbuild, env=env)
    stamp.write_text(json.dumps(signature, indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("platform", nargs="+", choices=["linux-x64", "arm64-v8a", "x86_64"])
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Android/Sdk"))))
    args = parser.parse_args()
    for platform in args.platform:
        build(platform, args.sdk.resolve())
