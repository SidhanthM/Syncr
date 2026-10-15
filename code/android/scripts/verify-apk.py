#!/usr/bin/env python3
"""Offline APK gate: real arm64 ELF core, Android JNA, and Ink. No device/install."""
import hashlib
import pathlib
import struct
import sys
import zipfile

android = pathlib.Path(__file__).resolve().parents[1]
apk = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else android / "app/build/outputs/apk/debug/app-debug.apk"
source = android.parent / "core/generated/jniLibs/arm64-v8a/libsyncr_core.so"
with zipfile.ZipFile(apk) as archive:
    libs = sorted(name for name in archive.namelist() if name.startswith("lib/") and name.endswith(".so"))
    required = [f"lib/arm64-v8a/{name}" for name in ("libsyncr_core.so", "libjnidispatch.so", "libink.so")]
    assert all(name in libs for name in required), f"Missing required libraries: {libs}"
    assert all(name.startswith("lib/arm64-v8a/") for name in libs), libs
    for name in libs:
        data = archive.read(name)
        info = archive.getinfo(name)
        assert data[:5] == b"\x7fELF\x02", f"Not ELF64: {name}"
        assert data[5] == 1 and struct.unpack_from("<H", data, 18)[0] == 183, f"Not AArch64: {name}"
        assert info.compress_type == zipfile.ZIP_STORED, f"Native library must be uncompressed: {name}"
        print(f"{name}: ELF64 AArch64, {len(data)} bytes, sha256={hashlib.sha256(data).hexdigest()}")
    core = archive.read(required[0])
    assert core == source.read_bytes(), "Packaged core differs from current backend artifact; rebuild APK"
    dex = b"".join(archive.read(name) for name in archive.namelist() if name.startswith("classes") and name.endswith(".dex"))
    assert b"Lcom/sun/jna/Native;" in dex, "JNA Java runtime missing from DEX"
    assert b"Ldev/syncr/core/SyncrLibrary;" in dex, "Generated library binding missing from DEX"
    print("PASS: packaged core exactly matches current backend artifact; generated bindings and JNA runtime in DEX; Android JNA and Ink native support present")
    print(f"APK: {apk}")
    print("NOT VERIFIED: Android native loading, rendering, input hardware, SAF or durable filesystem behavior")
