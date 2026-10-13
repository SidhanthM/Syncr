# Syncr core — offline Android B1–B3

The offline backend is SQLite-backed, not a bootstrap stub. All work is under `code/core/`. No Android, launcher, task/contract, Mac, networking, sequencer, phone-install or pairing changes are included.

Authority: `tasks/contracts/offline-api.md`, including its binding amendment. UniFFI signatures remain `SyncrLibrary`, fallible `Note.close_note()` / Kotlin `closeNote()`, and error payload `detail/retryable`. Generated `AutoCloseable.close()` releases an FFI reference only; it is **not** a successful checkpoint or a discard decision. Never patch generated Kotlin.

## Implemented behavior

- Persistent installation author and an advisory exclusive library-root writer lock. Note handles retain their library. Concurrent opens of the same live note share one serialized writer and invalidation stream; reopening a successfully closed note creates a new stream.
- Creation commits metadata, the first sheet, both heads, an immutable creation envelope and local sequence **1** in one SQLite transaction. It publishes a standalone `.syncr` file using the mechanism below. No partially initialized note is reported as saved.
- Published metadata is authoritative. The SQLite library index is a rebuildable, optional cache; reads scan published `.syncr` files, including arbitrarily named exports. Missing/stale rows and even a failing index-insert trigger do not hide a note or prevent creation lookup. Duplicate note IDs in multiple files are rejected rather than arbitrarily selected. Listing is sorted by note ID, not wall time.
- `lookup_creation` and `lookup_operation` query durable records. Same-ID canonical retries return the original local sequence and original receipt cursor with `duplicate=true`; different admitted payloads return `IdCollision`. Lookup returns the stored original receipt (`duplicate=false`). Duplicates create no event, sequence or history entry, even after restart.
- Every new command commits its complete envelope, projection, sample BLOBs, exact mark before/after effects and history changes in one WAL/FULL transaction. Local sequences are u64, stored in the log as eight-byte big-endian BLOBs for unsigned order. No sequencer revisions are fabricated: all confirmed heads remain null.
- Canonical dependencies are sorted/deduplicated. Self-dependencies are invalid; every supplied dependency must already be committed in this note. Bases must match the **current** target head, not an ancestor or the global sequence. `Confirmed` bases are rejected for offline notes. Missing explicit pending-head dependencies return `MissingDependency`.
- Marks batches contain 1–1024 unique targets, at most 16 MiB aggregate sample BLOBs and 64 KiB canonical non-sample metadata. A candidate projection is discarded on any invalid child; no durable partial batch, sequence allocation or undo change occurs. Adds require an absent mark ID, an existing sheet and that sheet's creation operation in dependencies. Stroke order is insertion order; delete/restore never move it.
- Add/delete/restore/absolute transform/restyle, add sheet, exact-permutation reorder and rename are implemented. Structural and metadata bases use their separate heads. Empty titles are preserved (the contract defines no title-content restriction). Affines must be finite and nonsingular. Samples are immutable, including signed-zero bits, optional-channel absence and measured timing.
- Persistent, installation-author-scoped ink undo/redo stores exact prior/result stroke states and checked expected heads. Requests must name the current stack target and include current affected heads in dependencies. Inverse head mismatch fails atomically with `StaleBase`. Undo/redo append new immutable operations; they do not rewind/delete history. New Marks clears only the redo branch. Rename/add/reorder neither enter nor clear ink history. Exported notes retain original authors but do not grant a different installation their undo stack or credentials.
- Snapshots and their cursors are captured under the same writer mutex. A per-live-handle 256-event ring supports nonblocking pull invalidation, ordered pagination and limits 1–256. Wrong stream, expired cursor or cursor beyond current returns `ResyncRequired`. A reopen resets the stream and event sequence, not the durable local sequence. Persisted old receipt cursors intentionally force resync against a new stream.
- `export_copy` serializes with writes, uses SQLite backup, checks version/state and `integrity_check`, closes a DELETE-journal standalone destination, syncs and publishes without overwrite. It exports no installation database. `closeNote()` serializes, requires a successful TRUNCATE checkpoint and closes the note connection. A busy/failed checkpoint reports an error and leaves the handle usable; successful close is idempotent and all other note calls then return `Closed`.
- All 13 frozen error identities remain typed. Storage diagnostics do not contain paths, SQL diagnostics or note content. No production path returns a not-implemented error or fictitious durable receipt.

### Dependency read-model amendment — frontend binding handoff

`SheetView.creation_operation_id` (Kotlin `creationOperationId`) exposes the already-persisted exact sheet creator in display order, including after add/reorder/rename and full library restart. AddStroke still requires that creator; the latest `structure_head` and other sheets' creators are **not** substitutes. Structural commands still require their current structural base and dependency.

`NoteSnapshot.undo_dependencies` / `redo_dependencies` (Kotlin `undoDependencies` / `redoDependencies`) contain sorted, deduplicated **current** heads of only the marks affected by the respective top action, including tombstones. They are empty without the matching target, and bounded by the original action's at-most-1024 marks. Snapshot targets, dependencies, state and cursor are captured under the existing writer mutex. No persisted fields, SQLite schema, canonical SYNO v1 encoding, admission limits or reducer preconditions changed.

Frontend must stage the selected sheet's `creationOperationId` for AddStroke, or the matching history target/dependency list for Undo/Redo, once per immutable command. Never refresh a retained command's dependencies or use all notebook heads. Stale targets/heads still fail atomically; a committed identical retry still returns its original receipt. Regenerated Kotlin, host library and Android arm64 artifact are available at the paths below. Android snapshot constructors must adapt to the new required fields; no Android code was edited here.

### Page format, flat notebooks and timestamps amendment (owner decisions 6–7)

- `PageFormat { Phone, Sheet16x10 }` is fixed at creation (`CreateNoteRequest.page_format`) and shared by all sheets; AddSheet appends a page of the same format. Note units: **Phone 1600 × 3200**, **Sheet16x10 1600 × 1000** (same width, so brush sizes and transforms are consistent). `NoteSnapshot.page_format/page_width/page_height` (f32, the sample coordinate space) and `page_size(format)` mean the app never hardcodes dimensions. No admission check bounds or clamps coordinates to any page size (none existed before either). Pre-format notes report Sheet16x10.
- Notebook membership is the note's own metadata (`notebook: Option<String>`, None = Inbox) and travels with the file. `CommandBody::SetNotebook { notebook, base }` uses the metadata head/base like RenameNote, reports `MetadataChanged`, and neither enters nor clears ink undo/redo. Name rules (`InvalidInput` otherwise): non-empty, no leading/trailing whitespace (stored exactly; the frontend trims), ≤ 100 UTF-8 bytes, no `/`, `\` or control characters, not `.`/`..` (future Mac folder names), not "Inbox" in any case. Names compare exactly.
- `library.sqlite3` v2 adds a `notebooks(name)` registry for ordering/empty notebooks. `list_notebooks()` is the union of registered names and names used by published notes, with note counts, sorted case-insensitively (exact name breaks ties). `create_notebook` is idempotent; `delete_notebook` removes only the registry entry (absent is a no-op) and returns `InvalidInput` while any note uses the name. `rename_notebook(old, new)` moves the registry entry in one library transaction, then for each member note opens it through `open_note` (sharing the app's live serialized writer when the note is already open) and submits a SetNotebook with a fresh operation ID against its current metadata head. **Not atomic across notes**; on failure, retrying the same call moves the remainder, a completed rename retried is a no-op, and renaming onto an existing name merges. A concurrent metadata edit can make one step fail with `StaleBase`; retry. Published note metadata stays authoritative; the registry only adds names.
- `NoteSummary.created_at_ms/modified_at_ms`: wall-clock ms recorded in `operations.committed_at_ms` inside each commit transaction. Created = creation operation's time; modified = time of the highest-sequence operation (clamped to ≥ created). Not in canonical bytes, so duplicates neither match differently nor change times. Operations from schema v1 have NULL and fall back to ULID time (note ID for created, the latest operation ID for modified).
- Creation identity: new notes use SYNO creation **tag 2** (adds format + initial notebook). Stored pre-format **tag 0** envelopes still verify: a retry of such a creation matches only if it carries Sheet16x10 and no notebook; otherwise `IdCollision`.

**Frontend binding handoff (Kotlin `dev.syncr.core`).** These generated signatures changed; Android will not compile until adapted:

| Generated type | Change |
|---|---|
| `CreateNoteRequest(operationId, noteId, firstSheetId, title, pageFormat: PageFormat, notebook: String?)` | two new required trailing params |
| `NoteSummary(id, title, localSequence, pageFormat, notebook: String?, createdAtMs: Long, modifiedAtMs: Long)` | four new fields |
| `NoteSnapshot(noteId, title, notebook: String?, pageFormat, pageWidth: Float, pageHeight: Float, metadataHead, …)` | four new fields inserted **after `title`** (positional constructors shift) |
| `CommandBody.SetNotebook(notebook: String?, base: Base)` | new variant; exhaustive `when` needs a branch |
| `enum class PageFormat { PHONE, SHEET16X10 }`, `PageSize(width: Float, height: Float)`, `NotebookSummary(name, noteCount: ULong)` | new types |
| `pageSize(format)`, `SyncrLibrary.listNotebooks()`, `createNotebook(name)`, `deleteNotebook(name)`, `renameNotebook(oldName, newName)` | new functions (all but `pageSize` throw `CoreException`) |

### Archive and Recently deleted amendment (owner request, 2026-11-11)

Notes exist only on the owner's phone, so deletion is recoverable and never loses data by accident.

- **Archive is note metadata** and travels with the file like the notebook. `CommandBody::SetArchived { archived, base }` uses the metadata head/base rules exactly like SetNotebook (stale base → `StaleBase`, missing head dependency → `MissingDependency`, identical retry → `duplicate=true`, different payload → `IdCollision`), reports `MetadataChanged` and neither enters nor clears ink undo/redo. SYNO body tag **7**. `NoteSummary.archived` and `NoteSnapshot.archived` are new trailing fields. Projection JSON without the field (every existing v1/v2 note and the legacy fixture) reads as not archived; no note schema change or migration. `list_notebooks` counts still include archived notes (they remain members, so `delete_notebook` still refuses).
- **Delete = move to Recently deleted.** `delete_note(id)` moves the note's file from the root to `<root>/.trash/<NOTE_ID>.<deleted_at_ms>.syncr`: the deletion time lives in the file name, so it is recorded by the same atomic rename. Before moving, any non-empty WAL or hot rollback journal is folded into the main file (writable open + TRUNCATE checkpoint + close; refused with retryable `StorageIo` if it can't be folded), so the moved file alone holds every commit. The move is the same no-overwrite `renameat2(RENAME_NOREPLACE)` / `renamex_np(RENAME_EXCL)` used by publication (**no hard links**; the trash is inside the root, so it is one same-filesystem rename) and syncs both directories before success. A crash leaves the file at exactly one of the two names. Empty `-wal`/`-shm` files left by read-only scans are tidied after moves and trash reads.
  - `NoteLocked` (retryable, "Close the note before deleting it") while a live handle has the note open; a closed or released handle does not block. Deleting an already-deleted note returns Ok; an unknown ID is `NotFound`.
  - `list_deleted_notes()` → `DeletedNoteSummary { id, title, notebook, page_format, deleted_at_ms, created_at_ms }`, newest deletion first. An unreadable trash file makes the listing fail with a typed error rather than vanish.
  - `restore_note(id)` moves it back to `<root>/<NOTE_ID>.syncr` without overwrite: an occupied name, or a library note with the same ID under any file name, is `IdCollision` and the trash entry stays. Restoring a note that is already back in the library returns Ok (lost-reply retry). The restored note is byte-identical: same operations, receipts, history and snapshot.
  - `purge_note(id)` permanently removes the trash file and its `-wal`/`-shm`/`-journal` sidecars. Only Recently deleted notes can be purged (`InvalidInput` for a library note); purging an already-purged note returns Ok. `purge_deleted_older_than(cutoff_ms)` purges entries deleted strictly before the cutoff, from file names alone (so an unreadable file still expires), and returns the count.
  - The library scan, `list_notes`, `list_notebooks` counts, `open_note`, `rename_notebook` and startup cleanup only look at regular `.syncr` files directly in the root; `.trash/` is never scanned or cleaned. A deleted note can't be opened (`NotFound`).
- **Creation semantics for deleted notes.** `lookup_creation` returns `Committed` with the original receipt for a note in Recently deleted (the creation happened and its record still exists); it never restores the note. A canonical `create_note` retry returns that receipt with `duplicate=true` and the note **stays deleted**; any other request reusing its note ID is `IdCollision`. After a purge, `lookup_creation` returns `NotRecorded`, and `library.sqlite3` keeps a `purged(note_id, creation_operation_id, purged_at_ms)` record so a stale retry with that note ID or creation ID gets `IdCollision` ("This ID belongs to a permanently deleted note") instead of silently recreating the note. The purge is recorded before the file is removed; a crash in between leaves the file listed in Recently deleted and purging again finishes it. Restore clears a stale purge record. If `library.sqlite3` itself is lost, the purge record goes with it (the trash and notes are unaffected). An unreadable trash file is skipped when matching creation IDs, but its file-name ID stays reserved.

**Frontend binding handoff (Kotlin `dev.syncr.core`).** Existing positional constructors still compile: the new record fields are trailing with a generated default of `false`.

| Generated type | Change |
|---|---|
| `NoteSummary(…, modifiedAtMs, archived: Boolean = false)`, `NoteSnapshot(…, redoDependencies, archived: Boolean = false)` | new trailing field |
| `CommandBody.SetArchived(archived: Boolean, base: Base)` | new variant; exhaustive `when` needs a branch |
| `DeletedNoteSummary(id, title, notebook: String?, pageFormat, deletedAtMs: Long, createdAtMs: Long)` | new record |
| `SyncrLibrary.deleteNote(noteId)`, `listDeletedNotes()`, `restoreNote(noteId)`, `purgeNote(noteId)`, `purgeDeletedOlderThan(cutoffMs: Long): UInt` | new functions (all throw `CoreException`) |

### Stroke mesh (round M2, `tasks/contracts/mac-api.md` §1)

Pure, additive functions for hosts without an ink engine (the Mac draws the triangles with Metal). No note handle, storage or I/O; safe on any thread. Implemented in `src/mesh.rs`.

- `default_stroke_style(brush) -> StrokeStyle`: the phone's `InkStyles.style(brush, InkPreferences())` (Fountain look, pressure 0.5, smoothing 0.5, taper on) with `brushWidth()` sizes, transcribed in f32 so the values match the phone bit for bit. Highlighter: flat chisel (tip 0.4 x 1, rounding 0.3), no pressure/taper, `discard_self_overlap = true`.
- `tessellate_stroke(samples, style, transform) -> StrokeMesh` (fallible): samples decoded/validated exactly as `decode_samples`; a non-finite or non-positive style value or a non-finite transform is `InvalidInput`; over 4,000,000 vertices is `ResourceLimit`. Output: little-endian f32 (x, y) vertices with the affine applied (`x' = a·x + c·y + tx`, `y' = b·x + d·y + ty`), little-endian u32 indices (three per triangle), tight f32 bounds, `discard_self_overlap` copied from the style.
- `mesh_renderer_version() -> u32` (currently **1**); bumped whenever output changes. A mesh is a cache, not note data.
- Algorithm: drop repeated (x, y, time) samples like the phone renderer; upsample to `upsampling_hz` (plus spatial subdivision); centred sliding-window smoothing of position and pressure over `smoothing_window_ms`, shrunk at the ends so the first/last points stay put; size multiplier = pressure (time-damped, the phone's cubic-Bézier curve, low..high) x speed thinning (30 ms damping) x start/end tapers (gated by distance to the other end, as on the phone), clamped to [0, 2]; points removable within `epsilon` are dropped; the stroke is emitted as the convex hull of each consecutive pair of rounded-rectangle tip polygons (arcs within `epsilon`), as triangle fans. A tap is one tip polygon (a dot). Neighbouring hulls overlap, so translucent ink must be drawn with overlap discarded (stencil) as the flag says; winding may flip under the transform, so do not cull.
- Deterministic: f64 arithmetic with only +, −, ×, ÷, sqrt, floor/ceil, comparisons and total-order sorting; exp/sin/cos use fixed series in the crate rather than libm. A unit test pins the output hash of a fixed stroke (debug and release agree on this host).
- Known differences from AndroidX Ink (pixel parity not claimed): Ink's exact sliding-window and upsampling model, its speed estimate and its outline extraction are approximated; Ink outputs a single non-overlapping outline mesh, this outputs overlapping hulls; `epsilon` is applied in stroke units before the transform. Tilt and orientation are ignored (the phone's style does not use them either).
- Kotlin: `defaultStrokeStyle`, `tessellateStroke` (throws `CoreException`), `meshRendererVersion`, records `StrokeStyle`/`StrokeMesh` (bytes as `ByteArray`). Swift: same names, bytes as `Data`. Both host smokes exercise them.

## Storage and publication

`library.sqlite3` (`user_version=3`) contains the installation identity, optional `notes` cache, the `notebooks` name registry and (v3) the `purged` record of permanently deleted note/creation IDs. A v1 or v2 library is upgraded on open (`CREATE TABLE IF NOT EXISTS notebooks` / `purged`, version 3) in the same transaction as installation setup; a v3 library is rejected as `UnsupportedVersion` by older cores, so an older APK cannot open it. Recently deleted notes are files under `<root>/.trash/` (see the amendment above), not database rows. A note has `user_version=2`, application ID `0x53594e43` (`SYNC`), and:

| Table | Purpose |
|---|---|
| `document` | One serialized typed materialized projection, sheet creators and author-scoped inverse stacks/head expectations |
| `operations` | Immutable operation ID, ordered unsigned local sequence, note/author IDs, payload version 1, complete typed JSON payload, canonical binary envelope, original receipt and (v2) display-only `committed_at_ms` |
| `samples` | Immutable mark ID → exact original SYNS bytes |
| `mark_effects` | Immutable operation ID → exact before/after stroke states for Marks/Undo/Redo (absent-before adds are explicit null) |

Sample bytes are present both in AddStroke's immutable payload/canonical envelope and the immutable sample table; they are not inferred from a rendering cache. Effects remain after a redo branch is cleared. JSON here is an internal typed storage representation, not the FFI ABI or the definition of idempotent equality. `serde_json/float_roundtrip` preserves finite f64 projection values. Log encoding and schema are versioned; newer library/note versions fail before journal-mode or note-content changes.

**Note migration v1 → v2:** v1 and v2 notes are both readable read-only (library listing never writes to a note). Opening a v1 note for writing (`open_note`, including via `rename_notebook`) runs one transaction: `ALTER TABLE operations ADD COLUMN committed_at_ms INTEGER` and `user_version=2`. Existing rows keep NULL times; the projection JSON is not rewritten, because absent `page_format`/`notebook` fields deserialize as Sheet16x10/Inbox, and the next commit stores them explicitly. Exports are taken from migrated (v2) files. A v2 note or library is rejected as `UnsupportedVersion` by the previous core, so installing an older build after this one cannot open migrated files.

Creation publication on the tested host filesystem:

1. Reserve a unique `.syncr-create-<ULID>` file in the library root.
2. Create and commit a FULL-synchronous SQLite **DELETE-journal** database, close it and `sync_all` its file.
3. Atomically rename the temp file onto `<note-id>.syncr` with a **no-overwrite** rename in the same directory: `renameat2(AT_FDCWD, temp, AT_FDCWD, dest, RENAME_NOREPLACE)` (raw syscall) on Linux/Android, `renamex_np(temp, dest, RENAME_EXCL)` on macOS. An existing destination fails with `IdCollision` and is left byte-for-byte unchanged. Other targets keep exclusive `hard_link` publication followed by unlinking the temporary alias.
4. `sync_all` the directory before reporting success. The rename consumes the temporary name, so no alias remains. Rebuild the optional index from published files.

Publication previously used `hard_link`; it was replaced because Android's SELinux policy denies app processes creating hard links in app data (confirmed on an S23 Ultra: `ln` fails with permission denied while `mv` succeeds), which made note creation and export fail with `PermissionDenied`.

With the root writer lock held, library reopen removes orphan files using the reserved `.syncr-create-` prefix, including SQLite temporary sidecars. Committed but unpublished temporary notes are deliberately discarded, not reported as saved. A published note never carries the creation prefix, so cleanup cannot touch it. Export uses the same no-overwrite publication on a separately backed-up file in the destination directory. Failed export cleanup is best-effort; a process killed during export can leave an unreported `.syncr-export-` temporary file in that explicitly chosen directory.

**Filesystem assumptions:** trusted app-owned local paths, a filesystem supporting atomic no-overwrite rename (`RENAME_NOREPLACE` on Linux/Android kernel 3.15+ and common filesystems such as ext4/f2fs; `RENAME_EXCL` on APFS) and successful file/directory `fsync`. If the no-replace rename is unsupported (`EINVAL`/`ENOSYS`/`ENOTSUP`) publication returns a non-retryable `StorageIo`; other failures return typed errors. There is no plain overwriting rename or check-then-rename fallback that weakens exclusivity. The tests exercise this mechanism on the host filesystem, not Android storage hardware. They exit child processes immediately before/after transaction commit and after publication; they do **not** establish arbitrary mid-fsync, power-cut, kernel/filesystem-corruption or Android power-loss guarantees. An I/O error after publication can leave a committed published file despite a lost/failed response; query/retry the original ID. Receipt means SQLite commit plus the required publication steps completed, not hub confirmation.

The advisory lock protects cooperative core writers, not hostile apps or aliases created outside this API. Roots/export paths must be private/app-controlled; no SAF content URI, encryption, network authentication or authorization layer is added. Incoming FFI values are allocated by UniFFI before Rust admission checks; these are in-process limits, not a network parser guarantee.

## Canonical operation grammar (SYNO v1)

All integers are unsigned little-endian unless noted; strings are `u32 byte_length + exact UTF-8`; lists are `u32 count + entries`. Tags are u8. The prefix is ASCII `SYNO` + u16 version **1**. No JSON ordering, wall clock, float normalization or dependency arrival order participates in equality.

- Creation (current): tag **2**, strings `note_id, author_id, operation_id, first_sheet_id, title`, then page format tag (Phone **0** / Sheet16x10 **1**), then optional notebook.
- Creation (legacy, notes created before page formats): tag **0**, the same five strings only. Never produced for new notes; still accepted when verifying retries (implies Sheet16x10, Inbox).
- Optional string: **0** = None, **1** + string = Some.
- Command: tag **1**, strings `note_id, author_id, operation_id`, sorted/deduplicated dependency string list, then body.
- Body tags: Marks **0** + ordered mutation list; AddSheet **1** + sheet ID + base; ReorderSheets **2** + ordered sheet-ID list + base; RenameNote **3** + title + base; Undo **4** / Redo **5** + original target ID; SetNotebook **6** + optional notebook + base; SetArchived **7** + u8 archived (0/1) + base.
- Mutation tags: Add **0** + mark ID + sheet ID + `u32 length + exact SYNS bytes` + brush; Delete **1** / Restore **2** + mark ID + base; Transform **3** + mark ID + base + six f64 IEEE bit patterns `[a,b,c,d,tx,ty]` in little-endian order; Restyle **4** + mark ID + base + brush.
- Base: Absent **0**; Operation **1** + operation ID string; Confirmed **2** + u64 revision (encoded for collision identity but not admitted offline).
- Brush: family Pen **0** / Highlighter **1**, size Small **0** / Medium **1** / Large **2**, colour Ink **0** / Red **1** / Green **2** / Blue **3** / Orange **4**; three consecutive tags.
- The 64 KiB metadata budget includes envelope, tags, counts and sample-length fields but excludes sample bytes. Exact sample bytes count toward the separate aggregate 16 MiB budget. Positive/negative zero are distinct canonical float encodings.

Complete canonical bytes **and** complete typed payloads/envelope columns are retained for later adoption. Adoption, confirmed revisions, conflict records, network transport and import/merge remain out of scope.

## Pins and prerequisites

| Component | Exact version / configuration |
|---|---|
| Rust exercised | rustc 1.94.1 (`e408947bf 2026-03-25`), edition 2024, declared MSRV 1.94 |
| UniFFI runtime + generator | **=0.32.2**, same crate dependency, generator enabled via `bindgen` |
| SQLite | `rusqlite =0.42.0`, `bundled,backup`; `libsqlite3-sys 0.40.0` locked |
| Other runtime pins | `ulid =3.0.0`, `thiserror =2.0.18`, `fs2 =0.4.3`, `serde =1.0.228`, `serde_json =1.0.149` |
| Test-only pins | `base64 =0.22.1`, `tempfile =3.27.0` |
| Host Kotlin/JNA | Kotlin 2.2.20 from installed Gradle 9.2.1 distribution; JNA 5.17.0; JVM target 17; Homebrew JDK 21.0.12.1 |
| Android NDK | **28.2.13676358 (r28c)**, installed at `$HOME/Library/Android/sdk/ndk/28.2.13676358` |
| Android target | Installed `aarch64-linux-android`, `arm64-v8a`, API **26**; NDK `darwin-x86_64` prebuilt on this Apple Silicon host |

`Cargo.lock` is retained; commands use `--locked`. No toolchain installs or downloads, cargo-ndk, emulator or phone access are performed by the scripts. Use a JNA Android AAR in the platform application, not the host JNA jar; Android packaging/loading remains platform verification.

## Reproduce from repository root

```sh
cargo test --manifest-path code/core/Cargo.toml --locked
cargo clippy --manifest-path code/core/Cargo.toml --locked --all-targets -- -D warnings
cargo fmt --manifest-path code/core/Cargo.toml -- --check
bash -n code/core/scripts/*.sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  code/core/scripts/generate-bindings.sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  code/core/scripts/smoke-kotlin-host.sh
ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/28.2.13676358" \
  code/core/scripts/build-android.sh arm64-v8a
```

Generation uses temporary output, builds the real host cdylib, invokes the pinned generator, then compiles **and executes** the staged Kotlin/library pair before publishing either generated file. Override `KOTLIN_COMPILER_LIB`, `JNA_JAR`, `JAVA_HOME` when needed; the Android Gradle project is not invoked. The native script requires the installed pinned NDK/target, builds with NDK Clang (including bundled SQLite), validates staged AArch64 ELF and all generated native exports, then publishes using a temporary-file rename. Failed build/generation/host-smoke steps preserve previously published artifacts. Kotlin and native artifacts are published as individual files, not a multi-file atomic deployment.

Paths:

- `generated/kotlin/dev/syncr/core/syncr_core.kt`: generated package `dev.syncr.core`, amended snapshot records, no hand patches.
- `generated/host/libsyncr_core.dylib`: exercised host Mach-O library, ignored local build output.
- `generated/jniLibs/arm64-v8a/libsyncr_core.so`: real Android release ELF, ignored local build output.
- `examples/kotlin/Consumer.kt`: executed consumer, not a mock or compile-only future flow.
- `fixtures/offline-v1/`: reviewed static golden sample/canonical/workflow/atomicity fixtures; u64 fixture values are decimal strings.

## Executed verification

| Check | Result |
|---|---|
| `cargo test --locked` | **PASS**, 65 tests: 32 unit (incl. 11 stroke-mesh tests, delete/restore/purge crash probes and WAL folding) + 8 boundary + 2 dependency integration + 1 error identity + 6 formats/notebooks integration + 9 offline integration + 7 trash/archive integration (+1 lock-probe child, filtered in the main run); child process probes also executed |
| `cargo clippy --locked --all-targets -- -D warnings` | **PASS** |
| `cargo fmt -- --check`, script `bash -n` | **PASS** |
| Staged binding generation + host Kotlin native smoke | **PASS**; independently reran published host smoke |
| Pinned Android arm64 release rebuild | **PASS**; staged ELF and all **115** generated external symbols validated |
| Archive / Recently deleted (`tests/trash_archive.rs`, `failure_tests`, `library::tests`) | **PASS**: SetArchived base/dependency/duplicate/collision rules, outside ink undo, restart; legacy v1 note reads not archived, archives, deletes and restores; delete → hidden/not openable → listed (title, notebook, format, times) → restart → restore → identical snapshot and duplicate retry; live handle → `NoteLocked`; restore collisions (same ID under another name, occupied file name) leave the trash entry; lookup/retry of deleted and purged creations never resurrect; purge removes file and sidecars, idempotent; `purge_deleted_older_than` cutoff; unreadable trash file; WAL folded before the move; child-process exits before/after the delete rename, after the restore rename and after the purge record |
| Old-format note fixture | **PASS** (`tests/formats_notebooks.rs`): `fixtures/legacy-v1/` holds the sequence-6 v1 note and v1 library written by the pre-format core (from the earlier legacy probe). Listing reads it as Sheet16x10/Inbox with ULID fallback times without modifying it; the original tag-0 creation retry returns `duplicate=true` (Phone/notebook variants collide); open migrates to v2 keeping all six operations; a new stroke, its duplicate retry and a SetNotebook commit; full restart reproduces the snapshot. |
| Legacy-file compatibility probe | **PASS**; the pre-amendment published host library wrote a sequence-5 note before regeneration. The new Kotlin/library pair reopened its provenance, samples and persistent history, committed an undo and retried it identically. SQLite remained schema version 1 with all six immutable operations. |
| Publication failure gates (prior B1–B3 run, not rerun for this amendment) | **PASS** previously; missing JNA/NDK failures preserved published artifacts. Staged scripts themselves are unchanged. |

Actual host Kotlin flows additionally cover page sizes, Phone/notebook creation with duplicate and collision, reserved "Inbox" diagnostic, summary format/notebook/timestamps, `listNotebooks`, SetNotebook outside undo, `renameNotebook` reaching an open handle and `deleteNotebook` refusal. Earlier flows: create → add → snapshot → erase → undo → redo → live backup/export/reopen → closeNote → note reopen → full library release/reopen; plus four sheets → reorder → rename → full restart → draw on every sheet using exposed creators → undo → full restart → redo. The latter rejects structure-head substitutes and checks exact history lists and immutable old-command retries. It asserts persistent stroke state/history, exact sequence 5, creation/command lookup, same-ID duplicates, event invalidation, new-installation export history isolation, installation identity and writer lock. It executes real `InvalidInput`, `UnsupportedVersion`, `NoteLocked`, `IdCollision` and `Closed` errors and checks exact generated `detail`, `retryable` and exception messages; it also round-trips `ULong.MAX_VALUE` through generated converters and optional sample layouts/signed zeros.

Native artifact from the final source:

- **3,583,904 bytes**, ELF64 little-endian `DYN`, **AArch64**.
- SHA-256: `b791778fd47471a08a16db947ef6f0c117ba34b874aea8ecc5fa6ba956a9c043`.
- Kotlin SHA-256: `969586d29bbb1a3cbd4934d88cbfedfd424826527b182be31cb82a5ebf185663`.
- Host dylib SHA-256: `2d26097ab544b7410a1b50567981f3df98c2be25701c9c1e0a20145929754d68`.
- Android identification API **26**, NDK **r28c**, build **13676358**; all LOAD segments have **0x4000** (16 KiB) alignment.
- Dynamic dependencies only `libdl.so`, `libm.so`, `libc.so`; SQLite is bundled, no shared C++ runtime.

Evidence logs (local ignored output): `generated/rust-tests.log`, `generated/bindings-generation.log`, `generated/kotlin-smoke.log`, `generated/android-build.log`, `generated/android-elf.log`, `generated/android-symbols.log`, `generated/legacy-probe/write.log`, `generated/legacy-probe/reopen.log`. Legacy probe Kotlin sources and its old-writer database are retained under `generated/legacy-probe/` as local execution evidence, not a new production API. Earlier publication-failure logs remain from B1–B3. Fixture README maps detailed failure coverage.

### Concrete remaining limitations

- Backend dependency reconciliation is complete. Frontend adaptation and coordinator-run integrated default/opt-in real-Rust regression suites remain separate handoffs; no integrated Android pass is claimed here.
- No Android device/emulator native execution, APK/JNA packaging, UI draft/closure behavior, phone install, power-loss/hardware testing, encryption, security audit or performance/latency claim.
- Projection and persistent inverse stacks are serialized per command; library discovery scans note metadata. This implementation has no large-notebook performance benchmark, history pruning or cache compaction promise. Complete immutable operations are retained indefinitely.
- Recently deleted is not encrypted or hidden from the filesystem; it is app-private storage like the library. Purge is file removal, not secure erasure. The trash crash tests exit child processes at fault points on the host filesystem; they make no Android power-loss claim.
- An unsupported/newer or unreadable `.syncr` file makes a scan fail with a typed error rather than being silently hidden. Only the v1 → v2 note/library migrations exist; corrupt-file repair, note import/merge, sync adoption and sequencer conflict handling are not implemented.
