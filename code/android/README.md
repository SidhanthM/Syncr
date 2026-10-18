# Syncr Android — offline Write + separate Bluetooth Pointer

## Current canvas amendment

The approved canvas amendment is implemented against core `ce10fc7`: continuous 4800-wide canvas, first-sheet canvas-coordinate ink, fixed legacy-sheet origins, no page-format/add-page/auto-add/page-indicator UI, canvas thumbnails and recovery v2. See **[CANVAS-HANDOFF.md](CANVAS-HANDOFF.md)** for current behavior, exact changed paths, executed test/build results, artifact hashes and remaining device/Stream acceptance.

The sections below and the F1–F2 handoff/plan describe earlier milestones. Their page-layout descriptions, API shapes, test counts and artifact hashes are historical and superseded by the canvas handoff. Build commands remain applicable; the checked-in daemon criteria now select Java 25 even when the wrapper is launched with the documented JDK 21 path.

Application ID/namespace remain **`dev.syncr.prototype`**; minSdk 26, compile/target SDK 36, JVM target 17. No device was installed, pairing changed, HID descriptor rewritten, Mac app added, or sync connection simulated.

## Implemented paths

- `app/build.gradle.kts`, root `build.gradle.kts`: existing AGP 8.13.0 / Kotlin 2.2.20, matching Compose compiler plugin, Compose BOM 2025.10.01, AndroidX Ink **1.0.0**, Android JNA **5.17.0 AAR**. Direct sourceSets point to backend-owned `../core/generated/kotlin` and `../core/generated/jniLibs`; arm64-v8a only. No copied/alternate/hand-edited bindings.
- `app/src/main/java/dev/syncr/prototype/notes/CoreAdapter.kt`: shared blocking consumer interfaces and real generated adapter (`SyncrLibrary`, typed errors with safe `detail` diagnostics). `closeDurably()` calls fallible **`closeNote()` before generated `close()`**. Failure never releases the note handle as a close substitute.
- `notes/NotesRepository.kt`: IO-dispatched, mutex-serialized core access, retained creation request and immutable commands/IDs, core SYNS encode/decode, core snapshot plus `changesSince` invalidation, sample caches, receipt-to-snapshot overlay handoff, sheets/title, and core-owned persistent ink undo/redo. No SQL, second document reducer, local revision invention, or production fake.
- `notes/SheetGeometry.kt`, `notes/InkSheetView.kt`: uniform letterboxed 1600×1000 mapping; stylus/eraser only; stable pointer ID and oldest-first historical real samples; no prediction. Finger-only pan/pinch, palm navigation suppression during contact and until remaining palm lifts. Frozen per-stroke mapping. Cancel, lost pointer, cancelled-up flag, resize/background terminate unfinished ink without submission. Onscreen eraser fallback; one drag is one atomic delete command, never split.
- Ink dry meshes are disposable worker-built caches from decoded core channels. Measured paths bridge wet/dry handoff, including pending completion and eraser handoffs by stable ID. Repeated x/y/time triplets are removed only from Ink's renderer input (Ink rejects them), never from stored measured samples. Three sizes/five named colours, pressure pen/highlighter, theme-aware Ink colour. No latency or pixel-parity claim.
- `MainActivity.kt`: Compose library/create/open/title, ordered sheet controls/add, pen/highlighter/whole-stroke eraser, sizes/colours, explicit Ink undo/redo/Fit, light/dark theme, text save state, More/location/offline confirmation explanation. ViewModel retains drafts across configuration changes. Resume refreshes; pause/stop cancels active gesture but does not discard completed drafts or cancel durable writes.
- `notes/RecoveryExport.kt`: explicit version-1 `.syncr-recovery.json` to a **user-chosen SAF document**, with note/author IDs, original commands/dependencies, base64 core sample bytes, creation request when pending, and safe core backup when available. Private staging stays outside the library root and is removed after use. No SQLite access. Destination write/flush/close failures do not authorize closure. Export is recovery, not a saved note or automatic import. Newer drafts revoke export authorization.
- `PointerActivity.kt`: original Bluetooth activity renamed/moved only (class/context names); `HidController.kt` and `AbsoluteMouse.kt` unchanged. Open **Pointer mode** separately; entering from notes honors durable close and the unsaved guard. Original prototype instructions/limitations are retained in [POINTER.md](POINTER.md). No changes to pairing, descriptor or Pointer mapping.

## Save, limits, and closure

A rendered stroke is not a saved stroke. Pending work says **Saving**, typed failures retain visible proposals and show **Save failed**. Only a durable core receipt represented by a snapshot permits removal of its overlay. Unknown completion uses the same immutable operation ID/payload, with persisted lookup; blocked later drafts stay queued. Stroke staging captures the selected sheet's `creationOperationId`, never the current structure head. Ink undo/redo captures the matching target and `undoDependencies`/`redoDependencies` from one snapshot, without adding the target or unrelated notebook heads. These lists are core-derived, sorted/deduplicated and bounded to 1,024 affected heads. Refresh/retry/export never rebase retained commands. The first successful codec preparation (including export before first submit) retains the exact typed command/sample bytes for all later attempts; failed destination writes retain that preparation too. No frontend history stack or sheet-operation index is maintained. All native calls except ID creation run off the UI thread.

Back, Library and Pointer transitions cancel active unfinished input and guard completed unsaved drafts: Retry; private recovery export followed by explicit Leave; or a second, explicit Discard confirmation. Export failure never permits leaving. Even confirmed discard does not release a handle if `closeNote()` fails. Already-durable operations cannot be rolled back by cancelling a UI coroutine. OS process termination may lose frontend-only unsaved drafts; this app does not promise otherwise.

Frontend safety caps: 100,000 measured samples per gesture (cancel the entire stroke; require lift), 1,024 erased targets per gesture (cancel the entire gesture), 64 retained drafts / 500,000 queued preview samples. Core also enforces its own authoritative byte/metadata limits; errors never silently split an atomic action. Ink objects/sample arrays are transient caches only. A codec failure retains the raw draft; an export that cannot encode it fails honestly and does not unlock closure.

## Build and executed verification

From `code/android/` (the executed Gradle commands used `JAVA_HOME=/opt/homebrew/opt/openjdk@21`; use a compatible local JDK):

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/verify-apk.py
```

The ordinary test suite uses fakes **only in test sources** and excludes the opt-in host test. It covers storage-full, delayed save/serialized close, lost creation and submit replies, identical retry IDs/payloads, receipt/snapshot handoff and lag, retained blocked drafts, atomic multi-mark erase and limits, stale history proposals, export authorization/revocation/close failure, failed durable close, event resync/cache reuse, codec failure, injected worker execution, affine conventions, uniform mapping, frozen measured channels, cancel/limit/time-invalid input, eraser sweep, and the existing HID report tests.

Actual results and artifact hashes are recorded in `build/verification/dependency-*` (local build outputs; see [OFFLINE-HANDOFF.md](OFFLINE-HANDOFF.md) for exact paths). `testDebugUnitTest` passed **25 tests, 0 failures/errors/skips**: 17 repository, 5 geometry and 3 HID report tests. Dependency regressions cover matching history lists, absent targets, immutable stale retries/exports and selected-sheet provenance captured before refresh. Export-before-submit and failed export retain one encoded command across later attempts. `assembleDebug` and compilation of the Android instrumentation APK also passed. Instrumentation APK compilation is **not execution**. `scripts/verify-apk.py` checks uncompressed real AArch64 ELF libraries `libsyncr_core.so`, `libjnidispatch.so` (required Android JNA runtime), and `libink.so`, and exact equality of the packaged core with the current backend artifact. Re-run this after backend native replacement.

### Real Rust host integration (additional, not Android execution)

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:testDebugUnitTest -PsyncrNativeHost=true
# Optional focused command (the full suite above was executed):
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:testDebugUnitTest -PsyncrNativeHost=true \
  --tests '*HostCoreIntegrationTest.productionRepository*'
```

This opt-in test uses the **production Android adapter/repository**, generated bindings, JNA host jar and backend `generated/host` library, not a fake. The main flow has passed: create → two measured strokes through Rust codec → one atomic erase → undo → add sheet/rename without clearing redo → durable close/library release → reopen/identity/history → redo → explicit recovery payload with actual safe core backup → reopen proving the unsaved exported mark was never applied.

**Dependency reconciliation verified:** the full opt-in suite passed **28 tests, 0 failures/errors/skips**, including all **3 real-host tests**. The previously failing `olderSheetCanBeWrittenAfterStructureChangesUsingExposedHead` retains its original regression name but now uses exact exposed sheet creation provenance. It creates the original plus two new sheets, performs two reorders through the public core adapter while the repository is closed, renames before/after reordering, reopens repeatedly, writes on every sheet through the production repository, and reopens again to verify persistence and unchanged provenance. No frontend reorder feature was added.

`exactHistoryDependenciesRemainImmutableAcrossRetryExportAndLostReply` reopens a note containing unrelated marks and an atomic two-mark erase with a shared head. Undo → redo → undo uses only the exact affected current heads, deduplicated, with no unrelated heads or extra original-action dependency. Test-only delivery failures exercise same-command retry, recovery export with real core backup, snapshot refresh and committed-but-lost-reply lookup without a second submit. Every successful operation/lookup/backup uses Rust, not a fake reducer. Ordinary tests separately retain stale target/head proposals across changed snapshots without rebasing. Core validation is unchanged; this frontend consumes the coordinator-selected binding amendment rather than relaxing dependencies. Large-notebook performance remains unmeasured, but the all-notebook history dependency superset is removed.

## Compiled but blocked device tests

`app/src/androidTest/.../notes/` contains:

- `NativeNoteSmokeTest`: real JNA/Android Rust create/add/erase/undo/redo/closeNote/reopen and persistent undo target.
- `StylusInputTest`: finger-only no ink; stylus ID distinct from pointer index; competing palm suppression; historical ordering; cancel, simulated lifecycle cancellation, and lost contact.
- `RecoveryExportTest`: actual JSON/base64 recovery bytes/IDs/dependencies/backup and failing destination close propagation.

`adb devices -l` was empty. No emulator was launched and no on-device install was authorized. **Not run**: those instrumented tests; S23 pressure/tilt/orientation and hardware eraser; real palm rejection; actual Canvas/Ink appearance and wet/dry visual handoff; portrait/landscape UI/accessibility; real SAF provider failure/cancel; app background/rotation/process kill after durable receipt; airplane-mode fresh install; real storage-full UI injection; Android JNI/JNA loading; physical filesystem durability; Pointer opening/button release/reconnect or Mac interpretation. Host execution and APK inspection do not establish these results.

With owner approval and an arm64 device/emulator, `./gradlew :app:connectedDebugAndroidTest` builds/installs/runs instrumentation; **do not run it as a read-only check**. Manual checks should exercise write/erase/undo/reopen; failed save with guarded Back/Pointer; cancel/background mid-stroke; process death after receipt; chosen recovery archive; and existing Pointer release on up/cancel. No Mac/sync/pairing/lasso work or measured hardware-latency claim is included.
