# Android arm64 offline backend artifact

`libsyncr_core.so` is the actual offline B1–B3 backend built by `../../../scripts/build-android.sh arm64-v8a` with installed pinned NDK **28.2.13676358 (r28c)** and Rust target **aarch64-linux-android**, targeting Android API **26**. SQLite is bundled. No prerequisite download, phone install or device access was performed.

Final verified output:

- ELF64, little-endian, shared object (`DYN`), **AArch64**, **3,368,488 bytes**.
- SHA-256: `576fba788bfbbd0db27a600aa31132e396d6bde45b7804c5786f9bde5166f8e7`.
- API 26 / r28c / build 13676358 Android identification note.
- All LOAD segments have **16 KiB** alignment.
- Dynamic dependencies only `libdl.so`, `libm.so`, `libc.so`.
- All **89** generated Kotlin native declarations match defined dynamic symbols.

The script builds and validates a staged artifact before publishing it. Kotlin sources at `../../kotlin/dev/syncr/core/syncr_core.kt` remain generated, package `dev.syncr.core`, native library `syncr_core`, with frozen signatures. The staged and published host Kotlin consumer executed real create/add/snapshot/erase/undo/redo/export/closeNote/reopen and library restart successfully.

The `.so` is ignored local build output, not a committed binary. Reproduce and find evidence logs via `../../../README.md`. Android device loading and APK/JNA AAR packaging are **not** verified by this ELF/host gate. No power-loss, hardware, UI or security-audit guarantee is inferred.
