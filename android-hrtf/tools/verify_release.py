#!/usr/bin/env python3
# Copyright (c) 2026 DysseuX (DysseuX Group)
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

"""Run through the portable HRTF launcher after the bounded Android build."""
from pathlib import Path
import ctypes
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

import numpy as np
from netCDF4 import Dataset

PROJECT = Path(__file__).resolve().parents[1]
ROOT = PROJECT.parent
ARTIFACTS = ROOT / "artifacts"
ARTIFACTS.mkdir(parents=True, exist_ok=True)
APK = PROJECT / "app/build/outputs/apk/debug/app-debug.apk"


def run(*args):
    return subprocess.check_output([str(arg) for arg in args], text=True)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    group = next(line.split(":", 2)[2] for line in Path("/proc/self/cgroup").read_text().splitlines() if line.startswith("0::"))
    cgroup = Path("/sys/fs/cgroup") / group.lstrip("/")
    resource_limits = {name: (cgroup / name).read_text().strip() for name in ("cpu.max", "memory.max", "memory.swap.max", "pids.max")}
    assert resource_limits == {"cpu.max": "210000 100000", "memory.max": "3221225472", "memory.swap.max": "0", "pids.max": "128"}, resource_limits
    affinity = sorted(os.sched_getaffinity(0))
    assert len(affinity) == 3, affinity
    sdk_path = next(line.partition("=")[2] for line in (PROJECT / "local.properties").read_text().splitlines() if line.startswith("sdk.dir="))
    sdk = Path(sdk_path) / "build-tools/36.0.0"
    signature = run(sdk / "apksigner", "verify", "--verbose", "--print-certs", APK)
    assert "Verified using v2 scheme (APK Signature Scheme v2): true" in signature
    run(sdk / "zipalign", "-c", "-P", "16", "4", APK)
    badging = run(sdk / "aapt", "dump", "badging", APK)
    assert "name='org.dysseux.hrtf.debug'" in badging
    assert "versionCode='2'" in badging and "versionName='0.2.0'" in badging
    assert "sdkVersion:'26'" in badging and "targetSdkVersion:'36'" in badging
    permissions = run(sdk / "aapt", "dump", "permissions", APK)
    assert "uses-permission:" not in permissions, permissions
    manifest = run(sdk / "aapt", "dump", "xmltree", APK, "AndroidManifest.xml")
    assert "TelemetryInitializer" not in manifest
    assert "allowBackup" in manifest and "CaptureProvider" in manifest
    tests = PROJECT / "app/build/test-results/testDebugUnitTest"
    suites = {}
    for report in tests.glob("TEST-*.xml"):
        suite = ET.parse(report).getroot()
        assert all(suite.attrib.get(key, "0") == "0" for key in ("failures", "errors", "skipped")), report
        suites[suite.attrib["name"]] = int(suite.attrib["tests"])
    for expected in ("PhotoProcessorTest", "AcousticProcessorTest", "PipelineParityTest", "SofaWriterTest", "NeuralPipelineTest", "AppUiTest", "SystemLanguageTest"):
        assert "org.dysseux.hrtf." + expected in suites, expected
    models = json.loads((PROJECT / "app/src/main/assets/models/manifest.json").read_text())
    with zipfile.ZipFile(APK) as apk, tempfile.TemporaryDirectory() as temporary:
        names = apk.namelist()
        assert not any(name.endswith((".ckpt", ".pt", ".pth", ".py", ".pyc", ".f32")) for name in names)
        assert not any("test-data" in name or "parity/" in name for name in names)
        assert {name.split("/")[1] for name in names if name.startswith("lib/")} == {"arm64-v8a"}
        for key in ("encoder", "decoder"):
            model = models[key]
            data = apk.read("assets/models/" + model["filename"])
            assert len(data) == model["bytes"] and hashlib.sha256(data).hexdigest() == model["sha256"]
        reference = json.loads(apk.read("assets/reference_manifest.json"))
        assert reference == json.loads((PROJECT / "app/src/main/assets/reference_manifest.json").read_text())
        for filename, digest_key in (("reference.bin", "reference_sha256"), ("personal_template.sofa", "template_sha256")):
            assert hashlib.sha256(apk.read("assets/" + filename)).hexdigest() == reference[digest_key]
        native_files = [name for name in names if name.startswith("lib/") and name.endswith(".so")]
        assert len(native_files) == 2
        for name in native_files:
            path = Path(temporary) / name.replace("/", "-")
            path.write_bytes(apk.read(name))
            header = run("readelf", "-lW", path)
            loads = [line.split() for line in header.splitlines() if line.strip().startswith("LOAD ")]
            assert loads and all(int(fields[-1], 16) >= 16384 for fields in loads), name
        assert len([name for name in names if name.startswith("assets/licenses/")]) >= 5
    mysofa = ctypes.CDLL("libmysofa.so.1")
    mysofa.mysofa_load.argtypes = [ctypes.c_char_p, ctypes.POINTER(ctypes.c_int)]
    mysofa.mysofa_load.restype = ctypes.c_void_p
    mysofa.mysofa_check.argtypes = [ctypes.c_void_p]
    mysofa.mysofa_check.restype = ctypes.c_int
    mysofa.mysofa_free.argtypes = [ctypes.c_void_p]
    checked_sofas = []
    for relative in ("sofa-writer-test.sofa", "android-hrtf-pipeline/personal.sofa"):
        path = PROJECT / "app/build/reports" / relative
        with Dataset(path) as data:
            ir = np.asarray(data["Data.IR"][:])
            assert ir.shape == (729, 2, 2200) and np.isfinite(ir).all()
            assert np.max(np.abs(ir)) > 0
            assert np.all(data["Data.Delay"][:] == 0)
            assert data["Data.SamplingRate"][0] == 48000
            assert data.SOFAConventions == "SimpleFreeFieldHRIR"
            assert "Attribution 4.0" in data.License
        error = ctypes.c_int(-1)
        pointer = mysofa.mysofa_load(str(path).encode(), ctypes.byref(error))
        assert pointer and error.value == 0, (path, error.value)
        try:
            check = mysofa.mysofa_check(pointer)
            assert check == 0, (path, "libmysofa_check", check)
        finally:
            mysofa.mysofa_free(pointer)
        checked_sofas.append({"file": relative, "sha256": sha(path), "netcdf4": True, "libmysofa": True})
    target = ARTIFACTS / "hrtf-photo-v2.apk"
    shutil.copy2(APK, target)
    saved = ARTIFACTS / "android-hrtf-test-results"
    shutil.copytree(tests, saved, dirs_exist_ok=True)
    for folder in ("hrtf-ui", "android-hrtf-pipeline"):
        shutil.copytree(PROJECT / "app/build/reports" / folder, saved / folder, dirs_exist_ok=True)
    result = {
        "package": "org.dysseux.hrtf.debug", "versionCode": 2, "versionName": "0.2.0",
        "apk": target.name, "bytes": target.stat().st_size, "sha256": sha(target),
        "modelBytes": models["exported_bytes"], "sourceCheckpointBytes": models["source_checkpoint_bytes"],
        "androidMinSdk": 26, "targetSdk": 36, "abis": ["arm64-v8a"],
        "jvmTests": suites, "jvmTestCount": sum(suites.values()),
        "sofaChecks": checked_sofas, "v2Signature": True, "elfZip16KiB": True,
        "androidPermissions": [], "physicalAndroidTested": False,
        "limits": {"cpuPercent": 210, "cpuAffinityCount": 3, "memoryMaxGiB": 3, "tasksMax": 128, "heavyJobs": 1},
        "observedCgroup": resource_limits, "observedCpuAffinity": affinity,
    }
    (ARTIFACTS / "hrtf-photo-v2-manifest.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
