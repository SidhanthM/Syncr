# syncr-core

The Rust core behind the Syncr apps. It stores handwritten notes offline in SQLite, one file per note, and exposes them through UniFFI as Kotlin (`dev.syncr.core`, Android) and Swift (Mac). It also turns stored strokes into triangle meshes for hosts without an ink engine.

Every write is one SQLite transaction. Every operation carries a canonical binary identity, so retries are idempotent. Nothing here invents sequencer-confirmed state: confirmed heads are always `null`.

Contracts: `tasks/contracts/offline-api.md`, `canvas-amendment.md`, `mac-api.md`. Format history: [`IMPLEMENTATION.md`](IMPLEMENTATION.md).

## Module map

| Module | Role |
|---|---|
| `lib.rs` | Crate root: `new_id`, `canvas_size`, re-exports |
| `types.rs` | FFI records and enums (commands, snapshots, receipts) |
| `error.rs` | `CoreError` (16 variants, each with `detail` and `retryable`) and its constructors; SQLite and I/O mapping |
| `library/mod.rs` | `SyncrLibrary`, `Note` and `open_library`: the whole exported surface. Each method delegates to one of the files below |
| `library/catalog.rs` | Scanning published notes, plus create, open, list and creation lookup |
| `library/note.rs` | An open note: the serialized writer, `submit`, the event ring, export and close |
| `library/trash.rs` | Recently deleted: delete, restore and purge, plus purge records |
| `library/notebooks.rs` | The notebook registry and `rename_notebook` |
| `reducer.rs` | In-memory projection (`State`): applies commands and holds the per-author undo/redo stacks |
| `text.rs` | Text validation, mutations, compact splice inverses and the future anchor-adjustment hook |
| `validation.rs` | Stateless admission checks: IDs, limits, bases, notebook names, affines |
| `canonical.rs` | SYNO v1 canonical operation bytes |
| `samples.rs` | SYNS v1 sample blob codec |
| `store.rs` | The note SQLite file: schema, versions and upgrade, log, projection, backup/export |
| `files.rs` | fsync, temporaries, no-overwrite rename publication, sidecars |
| `fault.rs` | Test-only crash and failure hooks (no-ops in release) |
| `mesh.rs` | Deterministic stroke tessellation |
| `preview.rs` | Live-preview frame codec, `PreviewReceiver` state machine, `PreviewSendQueue` (no I/O; `mac-api.md` §3a) |
| `commit.rs` | Durable stroke-commit chunking, CRC-32, `StrokeReassembler` |
| `*_tests.rs`, `library/tests.rs` | Unit tests that need crate internals |

UniFFI emits metadata in symbol order per codegen unit. Moving an `#[uniffi::export]` item, or a `uniffi::Object` derive, to another module therefore reorders the generated bindings. Keep exports where they are.

## Public API

Every fallible call returns `CoreResult<T>`, which is `CoreException` in Kotlin. IDs are canonical uppercase ULID strings.

**Free functions**: `new_id()`, `canvas_size()`, `open_library(root_path)`, `encode_samples(channels)`, `decode_samples(blob)`, `default_stroke_style(brush)`, `stroke_style_min_size(brush, min_size)`, `tessellate_stroke(samples, style, transform)`, `mesh_renderer_version()`.

**`SyncrLibrary`** (one writer per root; calls are serialized)

| Area | Methods |
|---|---|
| Notes | `author_id`, `list_notes`, `create_note`, `open_note`, `lookup_creation` |
| Recently deleted | `delete_note`, `list_deleted_notes`, `restore_note`, `purge_note`, `purge_deleted_older_than` |
| Notebooks | `list_notebooks`, `create_notebook`, `delete_notebook`, `rename_notebook` |

**`Note`** (concurrent `open_note` calls share one live handle): `snapshot`, `get_samples`, `submit`, `lookup_operation`, `changes_since`, `export_copy`, `close_note`.

**Commands** (`CommandBody`):
- `Marks`: 1–1024 unique stroke or text mutations, applied atomically. Strokes: `AddStroke` / `DeleteStroke` / `RestoreStroke` / `TransformStroke` / `RestyleStroke`. Text: `AddText` / `SpliceText` / `SetTextWidth` / `DeleteText` / `RestoreText` / `TransformText` / `RestyleText`. Exact text API and canonical tags: `tasks/contracts/text-v1.md` §3.
- `RenameNote`, `SetNotebook`, `SetArchived`: on the metadata head, outside ink undo.
- `Undo`, `Redo`: on the author's own top action.
- `AddSheet` and `ReorderSheets` are always `InvalidInput`: a note is one canvas. They exist only so old logs replay and old retries still return duplicates.

**Rules every caller relies on**
- **Idempotency.** Retrying an operation ID with identical canonical bytes returns the original receipt with `duplicate = true`. Different bytes give `IdCollision`. This holds after restarts and for deleted notes.
- **Bases and dependencies.** A base must equal the entity's current head, and that head must be listed in `dependencies`; every dependency must already be committed. `AddStroke` depends on its sheet's `creation_operation_id`. Undo and redo use the snapshot's `undo_dependencies` / `redo_dependencies`. Stale input gives `StaleBase` or `MissingDependency` and changes nothing.
- **`close_note()`** checkpoints and closes. It is fallible: on error the handle stays usable. Kotlin's `AutoCloseable.close()` only releases the FFI reference.
- **Events.** Each open handle keeps the last 256 events. `changes_since` pages them, with a limit of 1–256. A foreign or expired cursor gives `ResyncRequired`, and so does any cursor after a reopen.
- **Errors.** `detail` is a fixed safe string: no paths, SQL text or note content. Only `StorageFull`, `StorageIo` and `NoteLocked` are retryable.

## Storage layout

```
<root>/
  .syncr-writer.lock                    advisory exclusive lock (fs2)
  library.sqlite3                       user_version 3: installation, notes (cache), notebooks, purged
  <NOTE_ID>.syncr                       one note; any *.syncr in the root is scanned
  .trash/<NOTE_ID>.<deleted_ms>.syncr   Recently deleted
  .backups/<NOTE_ID>.v<old>.<ms>.syncr  copy taken before a v1/v2 -> v3 upgrade
  .syncr-create-*, .backups/.syncr-backup-*   crash leftovers, removed on open
```

A note file is SQLite with `application_id = 0x53594e43` ("SYNC") and `user_version = 3` until its first committed text operation, then `4`. It holds four tables (five after text):
- `document`: the projection as JSON, including the undo/redo stacks.
- `operations`: the immutable log. It stores the canonical bytes, the JSON payload, the original receipt, the local sequence as a big-endian BLOB, and `committed_at_ms`, which is display only.
- `samples`: exact SYNS bytes per mark.
- `mark_effects`: exact before and after stroke states per operation.
- `text_effects` (v4 only): added text views or compact before/after property/splice edits per operation. Text blocks are also in the document projection and have persistent inverse-edit history.

Published note files are authoritative. The `notes` table is a cache rebuilt on every scan.

**Durability rules**
- Notes run in WAL with `synchronous = FULL`. A receipt exists only after `COMMIT`.
- Creation writes a DELETE-journal database at `.syncr-create-<ULID>`, fsyncs it, and publishes it with a **no-overwrite rename**: `renameat2(RENAME_NOREPLACE)` as a raw syscall on Linux/Android, `renamex_np(RENAME_EXCL)` on macOS. It then fsyncs the directory. It never uses hard links, which Android's SELinux denies in app data, and never falls back to check-then-rename. An unsupported filesystem gives non-retryable `StorageIo`.
- Export and the pre-upgrade backup use SQLite online backup and `integrity_check`, then the same publication.
- Delete folds the WAL into the main file, then does one no-overwrite rename into `.trash/`, syncing both directories. Restore is the reverse rename. Purge records the IDs in `purged` before removing the file.
- Older note and library versions are readable. v1/v2 notes upgrade to v3 on `open_note`, after a backup. v3/v4 opens never migrate or back up. Only a committed text mutation raises v3 to v4, transactionally; undo never downgrades it. Likewise the first stroke with a pen look other than Fountain or a stored feel raises the note to v8 (with the v5-v7 markers and `brush_format`); plain strokes never do. A version above 8 gives `UnsupportedVersion` before anything is written.

## SYNO v1 canonical bytes

Integers are little-endian. Strings are a `u32` byte length plus UTF-8. Lists are a `u32` count plus their entries. Tags are `u8`. Every operation starts with `SYNO` and `u16 1`.

- **Creation**: tag `3`, then the strings `note_id, author_id, operation_id, first_sheet_id, title`, then an optional notebook. Retries of older notes also match tag `2` (the same, plus a page format byte before the notebook: Phone `0`, Sheet16x10 `1`) and tag `0` (the five strings only, which implies Inbox).
- **Command**: tag `1`, then the strings `note_id, author_id, operation_id`, then the sorted, deduplicated dependency list, then the body.
- **Body**: Marks `0` + mutation list; AddSheet `1` + sheet ID + base; ReorderSheets `2` + ID list + base; RenameNote `3` + title + base; Undo `4` / Redo `5` + target ID; SetNotebook `6` + optional + base; SetArchived `7` + `u8` + base.
- **Mutation**: Add `0` + mark ID + sheet ID + `u32` length + SYNS bytes + brush; Delete `1` / Restore `2` + mark ID + base; Transform `3` + mark ID + base + six `f64` bit patterns `[a,b,c,d,tx,ty]`; Restyle `4` + mark ID + base + brush.
- **Optional**: `0` for none, or `1` + string. **Base**: Absent `0`, Operation `1` + ID, Confirmed `2` + `u64`. **Brush** (`brush.rs`, also the preview Begin and stroke-commit wire): family byte (Pen/Fountain `0`, Highlighter `1`, Pen/Ballpoint `2`, Pen/Fineliner `3`; `|0x10` = a feel block follows), size (Small `0`, Medium `1`, Large `2`), colour (Ink `0`, Red `1`, Green `2`, Blue `3`, Orange `4`), then the optional feel block (pressure u8, smoothing u8, taper u8). A plain brush is the original three bytes.
- **Text mutation tags**: AddText `5`, SpliceText `6`, SetTextWidth `7`, DeleteText `8`, RestoreText `9`, TransformText `10`, RestyleText `11`. Full byte layouts and errors: `tasks/contracts/text-v1.md` §3. Existing tags are unchanged.
- **Budgets**: 64 KiB for everything except sample/text payload bytes, 16 MiB of sample bytes and independently 16 MiB of text bytes per gesture. Each text block/result/insertion is at most 1 MiB; at most 10,000 blocks including tombstones. `-0.0` and `0.0` are different operations.

The SYNS sample layout is documented at the top of `src/samples.rs`.

## Build, test, bindings

From `code/core/`:

```sh
cargo test --locked
cargo clippy --locked --all-targets -- -D warnings
cargo fmt --check
bash -n scripts/*.sh

# Kotlin + Swift bindings and host dylib. Each pair is smoke-tested (examples/) before it is published to generated/.
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home scripts/generate-bindings.sh

# Android arm64 release .so -> generated/jniLibs/arm64-v8a/ (checks the ELF and every generated JNI symbol)
ANDROID_NDK_HOME=$HOME/Library/Android/sdk/ndk/28.2.13676358 scripts/build-android.sh arm64-v8a
```

Outputs:
- `generated/kotlin/dev/syncr/core/syncr_core.kt`
- `generated/swift/`
- `generated/host/libsyncr_core.dylib`
- `generated/jniLibs/arm64-v8a/libsyncr_core.so`

Never edit generated code. The scripts download and install nothing. If the Gradle 9.2.1 Kotlin compiler is elsewhere, set `KOTLIN_COMPILER_LIB`; set `JNA_JAR` likewise for JNA.

The crash tests re-run the test binary as a child process (`failure_tests::crash_probe`) with `SYNCR_CRASH_STAGE` set. The hooks compile only under `cfg(test)`.

## Version pins

| Component | Version |
|---|---|
| Rust | rustc 1.94.1, edition 2024, `rust-version = 1.94` |
| UniFFI (runtime and bindgen) | `=0.32.2`; the generator is behind the `bindgen` feature |
| SQLite | `rusqlite =0.42.0` (`bundled`, `backup`), `libsqlite3-sys 0.40.0` |
| Runtime crates | `thiserror =2.0.18`, `ulid =3.0.0`, `fs2 =0.4.3`, `serde =1.0.228`, `serde_json =1.0.149` (`float_roundtrip`), `libc =0.2.191` |
| Test crates | `base64 =0.22.1`, `tempfile =3.27.0` |
| Host smoke | Kotlin 2.2.20 (from Gradle 9.2.1), JNA 5.17.0, JVM target 17, JDK 21; Swift from Command Line Tools |
| Android | NDK 28.2.13676358 (r28c), `aarch64-linux-android`, API 26, `arm64-v8a` only |

`Cargo.lock` is committed. Always build with `--locked`.

## Known limits

- **Discovery scans every note file on each library call.** There is no performance benchmark, history pruning or log compaction. The log grows forever.
- **One bad file in the root breaks the library.** An unreadable or too-new `*.syncr` there makes scans fail with a typed error instead of hiding the note. Two files with the same note ID, for example an export saved into the root, give `IdCollision`.
- **One bad file in `.trash/` breaks the listing.** It makes `list_deleted_notes` fail. Its ID stays reserved and it still expires by name.
- **The writer lock is advisory.** Roots and export paths must be app-private filesystem paths. SAF `content:` URIs are rejected. There is no encryption, and purge is not secure erasure.
- **Crash coverage is limited.** Crash tests kill a child process at named points on the host filesystem. They do not cover power loss, mid-fsync failure or Android storage hardware. An I/O error after publication can lose a reply for a committed note; query or retry the original ID.
- **No sync features yet.** Offline only: no adoption, confirmed revisions, conflict handling, import or merge.
- **The mesh approximates AndroidX Ink.** Pixel parity is not claimed. Hulls overlap, so translucent ink needs `discard_self_overlap`. Tilt and orientation are ignored. Bump `MESH_RENDERER_VERSION` whenever output changes; the pinned hash test enforces it.
- **Android loading is untested here.** It is verified by symbol checks only, not on a device.
