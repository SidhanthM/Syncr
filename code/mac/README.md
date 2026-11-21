# Syncr Mac — Markdown notebook

Native AppKit + Metal SwiftPM executable built with Command Line Tools (no full Xcode or shader compiler). A standalone notebook that needs no phone: create, organise and open notes on the Mac, laid out like Obsidian. Uses generated UniFFI Swift bindings and M2 meshes from `code/core/`. TextKit Markdown editing is layered with the Metal canvas; no AI is included here. A live-ink layer (A2, below) is fed by the phone link (Wi-Fi link v0, below) or by an in-process debug replay.

## The app

- **Window** (restyled per `tasks/contracts/rework-v1.md` §T): a ribbon at the far left (files, search, daily note, phone), the file tree (Cmd-\), tabs above the note, the companion's two rows (name and menu, mode with its amber mark, Web, reader; then "Tell Syncr" in the serif), and the full-width Metal canvas. There is no right sidebar or conversation/reply panel. One status bar at the bottom: save state, word count, the companion's budget, and the phone pill (click for the phone menu). There is no toolbar: pan and zoom are gestures, and Zoom In/Out (Cmd-+/-), Fit (Cmd-0) and Document View (Opt-Cmd-0) are menu and palette commands. Window colours are tokens in `code/shared/mac-theme.json`, generated into `ThemeTokens.swift` by `code/shared/generate-colours.py`; plain ink on the Mac's page uses the Ink token. Colour means state only: amber thinking, sage connected, clay needs you. Margin notes are explanatory aids in serif with a small dot and no card; the passage they answer is underlined. Sources remain visible; Pin/Unpin, Add to note and Dismiss show on hover. There is no Reply action or replacement chat popup. Existing stored companion items and messages are not deleted.
- **Sidebar**: Inbox (notes with no notebook), notebooks as folders with their notes, and Recently deleted (shown when non-empty). Filter field at the top matches note titles. Click a folder to toggle it and make it the target for new notes. Context menus: note (open, open in new tab, rename, move to, delete), notebook (new note here, rename, delete), trash note (restore, delete permanently).
- **Keys**: Cmd-N new note in the selected notebook (ready to type; rename using the inline title), Shift-Cmd-N new notebook, Cmd-O quick switcher (type to filter, Enter opens in the current tab, Cmd-Enter in a new tab), Cmd-P command palette, F2 rename, Cmd-Delete delete to trash, Cmd-W close tab, Shift-Cmd-[ / ] previous/next tab, Cmd-+/-/0 zoom and Fit, Shift-Cmd-I import phone export, Shift-Cmd-O choose library folder.
- **Delete** moves a note to Recently deleted (core trash); restore returns it to its notebook. Deleting a notebook moves its notes to the Inbox first.
- **Session**: open tabs, the active tab, the selected notebook and collapsed folders are saved to `<library>/.mac/session.json` on every change and restored at launch. Notes that no longer exist are dropped; a corrupt file starts empty.

## Settings and custom hotkeys

Open **Syncr > Settings…** (Cmd-, by default), **Syncr > Hotkeys…**, or the gear at the bottom of the left rail. Settings is a searchable hub with **General, Appearance, Editor, Hotkeys, Companion and Library** categories. The sidebar search matches setting names/descriptions/aliases and hotkey commands across categories (for example `dark theme`, `font size`, `daily tokens` or `undo`). Search never indexes note content, credentials or API keys; an empty result gives suggested queries. Command results open the exact Hotkeys row, clearing any previous filter. **Companion** contains the controls directly in this window: provider/sign-in and Keychain actions, reading/answer model pickers, pictures and line style, daily limits/current usage, voice/speech speed, and structured reader name/instruction fields with add/remove/save. There is no separate Companion Settings window or Configure link; the menu/palette command and note-strip settings action select this same page. Search shows matching inline sections without indexing key values. Most controls save immediately; API keys and reader edits have explicit save actions. Reader drafts survive navigation and model refresh, and invalid/failed reader saves preserve existing data. Companion preferences/readers/voice remain library-scoped; API keys remain in macOS Keychain and are never prefilled. Switching provider or library and closing Settings clears unsaved key input.

Appearance offers **System / Light / Dark** plus **Reduce motion** (also respects macOS accessibility settings). Editor preferences offer **85–130% text scale**, **compact/default/relaxed line spacing**, and **spellcheck**. General controls whether to restore the previous session on launch; Library offers folder selection/reveal and import actions. These controls save automatically and atomically in `~/Library/Application Support/Syncr/preferences.json`, separate from hotkeys and note libraries. Changes restyle open editors and reflow companion margins without editing note text or stored ink/operations; they apply to this Mac only. Unknown individual values fall back safely; unsupported future file versions are never overwritten. Failed writes show an error and preserve the last saved value.

The Hotkeys page lists all non-debug app commands, including Show/Hide/Toggle Left Sidebar. The former right-sidebar commands and Opt-Cmd-B default have been removed; old saved overrides for those commands are ignored without invalidating other bindings or reserving their shortcuts. Search by command, category or displayed binding; filter All, Assigned, Customized or Unassigned. Click the keycaps (or **Click to bind** on an empty command), press the desired chord, then click **Save binding**. For example, Ctrl-Shift-M appears as separate **Ctrl**, **Shift**, **M** keycaps. Escape cancels without changing the old binding. Modifier-only presses wait for a key. Conflicts are rejected with the owning command's name; clear or rebind that command first.

**×** clears a shortcut; **↶** restores that command's default. **Restore defaults** resets everything after confirmation. Existing defaults are retained until changed; changes update dispatch, the main menu, palette hints and sidebar/companion shortcut hints immediately. Standard macOS edit/window shortcuts and known system combinations are protected; bare typing/navigation keys cannot be assigned (bare function keys can). Syncr cannot override shortcuts intercepted by macOS, other apps, or keyboard hardware. Recording uses a temporary app-local event monitor, not global keyboard monitoring or an Accessibility permission requirement.

Overrides are app-wide, stored atomically in `~/Library/Application Support/Syncr/hotkeys.json`, not in a note library. A cleared shortcut remains cleared after restart; resetting removes its override. Failed writes leave live bindings unchanged. Invalid/corrupt saved bindings fall back to defaults with a warning in Settings. For isolated GUI tests set `SYNCR_MAC_SETTINGS_FOLDER=/path/to/disposable/settings` alongside `SYNCR_MAC_LIBRARY_FOLDER`.

Focused checks: `swift run --package-path code/mac SyncrMacChecks --hotkeys` and `swift run --package-path code/mac SyncrMacChecks --settings`. Settings checks cover restart persistence, corrupt/unknown/future values, failed atomic writes, search aliases and live editor updates without note writes. The checks executable isolates its presentation settings in a fresh temporary directory. The full viewer checks include the same binding tests. Actual recording/conflict/cancel/clear/reset, menu updates, and rebound sidebar/Bold/Undo shortcuts were also exercised through macOS Accessibility scripting against a disposable-library window. This is not a qualification of every international keyboard layout, IME, hardware function-key mode or OS-reserved chord.

## Mac-created versus imported notes

Core exposes no creating author for a note (an empty note has no strokes), so the Mac keeps its own record: `<library>/.mac/owned.json`, the IDs of notes created on this Mac. The ID is recorded before the note is created. **In the record = Mac note, writable** (rename, move, delete, restore). **Not in the record = imported phone copy, read-only until sync**: shown with a lock, no rename or move, notebook rename/delete refused while the notebook holds one (core would rewrite those copies and raise their sequence), but delete to trash is allowed. If the record is lost, notes become read-only copies, never the reverse. Import refuses to replace a Mac-created note with the same ID. Purging a note removes it from the record.

## Build, check, launch

```sh
python3 code/shared/generate-colours.py --check
cd code/mac
sh prepare.sh   # when core bindings change; builds and copies the optimised (release) core
swift build
swift run SyncrMacChecks
swift run SyncrMac
# Or import and open a standalone local export using the same copy-only path:
swift run SyncrMac --import /path/to/note.syncr
```

`prepare.sh` copies generated Swift bindings/header/host dylib and rewrites the dylib install name only on the copy. Run again after core generation. `.build/`, generated copies and native library are ignored. Launch via SwiftPM or `.build/debug/SyncrMac`; no signed `.app` or installer is produced yet.

## Storage and safety

The chosen library folder defaults to `~/Documents/Syncr/`. Note copies (`<note-id>.syncr`) and the core index live **directly there**, not in an Imported Copies silo. File > Choose Library Folder… chooses the app-owned library for this session; restart returns to default. `SYNCR_MAC_LIBRARY_FOLDER=/path/to/disposable/folder` isolates a launch. Do not choose a folder of irreplaceable originals for testing: this is an app-owned library, and core may upgrade its managed files.

File > Import Phone Export… (Shift-Cmd-I) accepts a **standalone safely exported** `.syncr`, copies it into hidden staging, validates/migrates only the copy through core, preserves migration backups under `.backups/`, and publishes via atomic same-filesystem rename. An active SQLite `.syncr` with a nonempty WAL is not a standalone export. Source export bytes are never passed to core or modified. Same ID with a strictly greater local sequence replaces the managed read-only copy; older/equal sequences are refused. Previous copies remain under `.replaced/` for recovery. An existing unmanaged filename is not silently overwritten. These local comparisons do not establish branch ancestry or sequencer authority; the future authenticated snapshot-adoption path must prove lineage before replacement.

On first load after the R7 update, files in a legacy `Imported Copies/` child are **copied** into the main library through the same validator. The original child is never deleted or moved. Already current/newer main-library copies are retained. No tests or launch validation use personal notes.

## Rendering

Fit uses the 4800 × 3000 reference window, preserving aspect ratio. Vertical/horizontal trackpad scroll, arrow controls (400 units), zoom to 8× and Fit are available. Legacy sheet origins apply after stored affine transforms. System light/dark uses the same named ink/paper palette as Android, generated from `code/shared/colours.json` (including highlighter alpha 0x66).

Screen and offscreen use the same **single 4x MSAA pass**. Immutable vertex/index buffers are uploaded once per loaded stroke; multisample colour/stencil attachments are reused until resize. Highlighter stencil references prevent self-darkening; after 255 references a colour-masked fullscreen triangle resets stencil inside the same pass. Onscreen presentation is asynchronous; only pixel checks wait for completion. Serial background tessellation progressively delivers strokes to the main thread; cancelling/switching notes or folders discards stale results.

## Verification and remaining acceptance

`swift run SyncrMacChecks` covers notebooks and note create/rename/move/delete/restore with the sidebar model after each step, quick-switcher filtering and ordering, command palette availability, Mac-writable versus imported read-only notes, and session save/restore (a library has one writer, so the checks scope each model before reopening), plus v2 fixture copy migration/origins, immutable original files, older/equal/newer replacement and recovery copies, invalid input, legacy-silo copying, background load/cancellation, viewport geometry, light/dark pixels, MSAA fractional edge coverage, highlighter self/distinct-stroke overlap, 8-bit stencil wrap, buffer reuse and resize-only attachments. Checks are a standalone executable because the installed CLT lacks XCTest/Testing macros; symlinks compile the actual model/renderer/palette, not test duplicates. `python3 code/shared/generate-colours.py --check` catches stale Android/Mac palette outputs.

On-screen design fidelity, real-export parity and large-note performance remain human acceptance, not established by pixel tests or GUI startup. No ink editing, drag-and-drop in the sidebar or physical-phone update is included. The phone link below is plain TCP by owner decision; the pinned-TLS pairing design under `tasks/` is not part of it.

## Live ink (A2)

Transport-agnostic: `LiveInk.receive(bytes, at:)` takes one encoded frame and a `monotonicNs()` receive time, from any queue. Nothing here opens a socket. The only source is the debug replay.

**Pipeline.** `LiveInk` (`LiveInk.swift`) routes by `frameKind` to a `PreviewReceiver` (preview) or a `StrokeReassembler` (commit), calls `tick` for expiry (a timer runs only while something is live) and `reset` on a simulated disconnect. Everything untrusted is dropped and counted (`LiveStats`), never fatal. Decode, receiver, tessellation and GPU buffer creation run on one serial `userInteractive` queue; the main thread and the display callback never wait on it. The display side calls `take()` for the newest `WetFrame`; frames that arrive faster than refresh are merged (the queue drains everything pending and tessellates once per stroke).

**Target note.** Live ink is accepted only for the note open in the active tab, and only if it is Mac-writable. Other notes, read-only copies and "no note open" are dropped and counted, and a small status line at the bottom of the note view says why (it fades after 8 s). The future session layer will tell the phone the active note ID and sheet ID; until then frames are matched here. Write failures show in the same line ("could not save a stroke"); they are not a modal.

**Wet layer.** `MeshRenderer.render(_:wet:...)` draws wet strokes after the committed ones in the same single MSAA pass, continuing the same stencil references, so a live highlighter is one coverage and shares the screen/offscreen path. Hover is a 5 px dot.

**Incremental tessellation.** The core tessellates a whole stroke from a blob and has no append API, so `WetBuilder` cuts the stroke into windows: a tail window (at most 64 samples) is re-tessellated when samples arrive; when it fills it is frozen as a piece and a new tail starts up to 32 samples (about the taper length in distance) back, so each piece's tapered ends sit under its neighbour's body. Work per update is bounded by the window, not the stroke (checked up to 9000 samples). The live joins are a preview; the committed stroke is tessellated whole, as on reopen. A core append/incremental API would make this exact and cheaper and is the recommended follow-up.

**Coordinates.** `viewRelative` samples are in units of the 4800 x 3000 reference window at the viewport's zoom: canvas = (left, top) + v / zoom, using the viewport frozen at Begin (a commit with no preview uses the viewport at receipt). `canvas` samples are used as they are. Hover is treated as view-relative at the current viewport (the contract gives it no space field).

**Commit.** A reassembled commit is written on `LibraryModel`'s serial tessellation queue (never the main thread; serial with loads so the note is not opened twice), read back and tessellated as a reopen would. Then, in one main-thread block, the stroke is added to the drawing and the preview is removed from the published frame (`LiveInk.finishCommit`), then `receiver.commit` is called. No draw can run between, so there is no flicker and no duplicate. A reload of the note since the write already contains the stroke (`LibraryModel.generation`). A commit with no preview just saves. A repeated stroke ID is counted as a duplicate.

**Presentation path.** Default: `arrival-adaptive` (since 2026-11-18): presents like `arrival` while the view's transform is stable and through the Core Animation transaction only while a pan, zoom, resize or slide is in flight, plus one frame after (`PresentPolicy` in `LiveInk.swift`). Release-build A/B on an M2 (`code/shared/latency/present-ab.sh`, median of 7 runs): pen p50/p95 15.6/28.0 ms against 14.8/24.1 for `arrival`, and about 33/52 for `arrival-transaction`. To go back to transaction presentation for every frame: `SYNCR_MAC_PRESENT_MODE=arrival-transaction`. The description that follows applies to the moving frames and to that fixed mode. When the ingest queue publishes a wet frame, a main-thread hop takes `nextDrawable`, encodes, waits for scheduling, and presents in the same Core Animation transaction as the text/annotation viewport update. Text stays at the last submitted viewport while a frame is in flight, rather than moving ahead of the ink. Uses display sync, two drawables, and at most one frame in flight (a newer frame arriving meanwhile is merged and drawn when the GPU finishes). The default changed to address rapid-pan layer mismatch; the historical measurements below are not a fresh validation of this change. No link, no timer: an idle view does not redraw. Modes, all kept behind the variable: `link` (CAMetalDisplayLink on the main run loop, frame latency 1), `arrival`, `arrival-sync` (`displaySyncEnabled = true`), `arrival-transaction` (`presentsWithTransaction`, present after scheduling). Also `SYNCR_MAC_DRAWABLES` (2 or 3), `SYNCR_MAC_FRAME_LATENCY` (link) and `SYNCR_MAC_OPAQUE=1`. Notes from running them: a layer with a CAMetalDisplayLink must never have `nextDrawable` called by anyone else (MTKView traps), so MTKView's own drawing is bypassed in every mode and all draws go through our code; drawing stays on the main thread because viewport and drawing are main-confined, which also keeps the preview-to-commit swap atomic.

**Latency (software side only).** Per frame, received (`monotonicNs()` at `receive`) to each stage, rolling 1024 frames, p50/p95/max, logged to stderr at replay end with the per-stage table (debug flag). "presented" is the drawable's `presentedTime`; when the system reports none it is counted as "presented (handler time)" (the time the handler ran, an upper bound of when it reached the glass). It excludes pen sampling, Wi-Fi and the display's own scan-out after the compositor. Method: `SYNCR_MAC_DEBUG_REPLAY=1 SYNCR_MAC_DEBUG_AUTOREPLAY=1` in the real window (debug Swift build; Swift optimisation was shown not to matter), three replays of about 116 to 128 frames each on a 60 Hz display, on a machine that was also busy with other work (load average 5 to 10), so treat p95 and max as pessimistic. Pen replay medians, ms from receive:

| stage | before: debug core, link | after: release core, arrival |
|---|---|---|
| ingest start | 0.5 | 0.4 |
| published (decode, tessellate, buffers) | 11.0 | 2.5 |
| draw start (taken by the display side) | 18.6 | 2 to 3 |
| gpu done | 19.8 | 5 to 8 |
| presented | 68.5 (p95 86) | 18 to 20 (p95 28 to 41) |

Where the time went before: ingest 11 ms was the debug Rust core (about 4 times slower tessellation); the link then added up to a refresh of waiting; and the 48 ms between GPU-done and presented was the link's presentation scheduling plus compositor frames, which `arrival` removes.

Mode comparison (release core, pen replay, presented p50 / p95 / max):

| mode | p50 | p95 | max | notes |
|---|---|---|---|---|
| `link`, latency 1, 2 drawables | 58 | 68 | 69 | 60 Hz callbacks; frame latency 2 the same (61) |
| `arrival-sync` | 41 | 54 | 56 | vsync wait, about 2.5 refreshes |
| `arrival-transaction` | 40 | 54 | 87 | blocks the main thread until scheduled |
| `arrival` (previous default) | 16 to 20 | 25 to 41 | 40 to 65 | |
| `arrival`, 3 drawables | 31 | 48 | 57 | frames superseded (only 85 of 116 presented) |
| `arrival`, opaque layer | 16 to 20 | 22 to 35 | 36 to 47 | no clear difference |

The remaining 12 ms or so from GPU-done to presented in `arrival` is the WindowServer composing the window at the next display refresh; no layer setting I tried removes it for windowed content. Drawable prefetch on another queue was tried and was no better (the frame then waits for the drawable instead), so it is not in. Tearing: windowed content is composited by the WindowServer, and the window screenshots of replayed strokes show complete frames; `screencapture` reads the composed image, so it cannot show scan-out tearing. Eyes on the real display are still needed, and `SYNCR_MAC_PRESENT_MODE=arrival-sync` is the fallback if tearing shows.

First frame of a stroke (the hover dot that wakes an idle view): 21 to 46 ms presented, against 17 to 20 for the following frames, so waking costs about 10 to 25 ms once per stroke in these runs (one sample per replay, noisy). In `arrival` mode there is no link to start; the extra is the first drawable and compositor wake-up. Not reduced.

Optimised core: `sh prepare.sh` now builds `code/core` with `cargo build --release --locked` (writes only the core's `target/`) and copies that dylib, keeping the generated bindings and header as they are (same API; the checks load it and pass). `SYNCR_CORE_PROFILE=debug sh prepare.sh` copies the generated debug host library instead.

Tail window: with the release core, tail 64 / overlap 32 gives published p50 2.4 / p95 6.5 ms offscreen; 32 / 16 gives 1.7 / 4.1 but about twice as many pixels differ from the committed stroke (145 against 69 of 3138), and 16 / 8 four times (270). Kept 64 / 32.

Offscreen harness (checks, release core): received to GPU-complete p50 3.7 / p95 7.3 ms, published p50 2.8 ms.

**Debug replay.** Launch with `--debug-replay` or `SYNCR_MAC_DEBUG_REPLAY=1` to list palette commands "Replay Test Stroke", "Replay Test Highlighter Stroke", "Replay Cancelled Stroke", "Replay View-Relative Stroke" and "Simulate Live Ink Disconnect". The replay generates 240 Hz strokes with varying pressure in batches of 4, encodes them with the core, pushes them through a real `PreviewSendQueue`, pops and feeds the engine in real time, then sends the commit. `SYNCR_MAC_DEBUG_AUTOREPLAY=1` (with the flag) makes a note and runs three replays unattended, for launching without a GUI driver. Use a disposable `SYNCR_MAC_LIBRARY_FOLDER`.

**Checks added** (`LiveInkChecks.swift`): partial stroke visible mid-replay (offscreen pixels); cancel leaves nothing; expiry (live 5 s, ended 10 s) and reset clear the wet layer, old epochs ignored; commit leaves exactly one stroke, pixel-equal after reopen, and a fresh library reads it; no frame has both or neither of preview and committed stroke; commit with no preview saves; duplicate commit; wrong-note, read-only and no-note refusals with the note unchanged and the status text; failed write (unknown sheet) leaves no ghost ink and surfaces an error; switching notes drops wet ink; malformed and fuzzed frames dropped; view-relative mapping at a scrolled and zoomed viewport, frozen across a mid-stroke scroll; highlighter without self-darkening across pieces and out-and-back (the check fails if the stencil is bypassed); bounded incremental work and merging of bursts; hover show, hide on stroke start, idle, reset and wrong note; real-time replay with latency recorded.

**Not verified.** Tearing on the real display, an idle-machine run, a 120 Hz display, wet ink mid-stroke in the real window (the screenshots after the replays show the committed strokes; a mid-stroke wet frame was not caught), drawing with the real pen, 120 Hz, any transport. The GUI could not be driven by keystrokes (no assistive access), hence the auto-replay flag.

## Phone link (Wi-Fi link v0)

Implements the Mac side of `tasks/archive/contracts/wifi-link-v0.md` (the wire, messages and shared vectors in `code/shared/link-v0/vectors.json` are the authority). **It is plain TCP with no TLS, by the owner's decision of 2026-11-14 (security is not a concern for this link yet).** Anyone on the same Wi-Fi who captures traffic can read the notes and the pairing token; the token only stops other devices writing by accident. The pinned-TLS design is not deleted and slots under this link later.

**Turning it on.** It is on by default and the listener exists only while the app is open and "Phone link" is on. Phone menu (or Cmd-P): "Phone Link On/Off", "Follow Phone On/Off", "Forget Phone…". The choices are kept in `<library>/.mac/link.json` (with this Mac's link ID, generated once); paired phones in `<library>/.mac/paired-phones.json` (mode 600, holds tokens). The title bar shows "Phone link: off / waiting for phone / pairing with X / X connected". The Mac listens on any free TCP port (`TCP_NODELAY`, keepalive) on every interface and advertises Bonjour `_syncr._tcp` with TXT `v=0`, `name`, `id`.

**Pairing.** An unpaired phone makes the Mac show four digits and the phone's name in a card at the top of the note area, valid 120 s, five tries. A paired phone is welcomed by its stored token. One connection per phone: a new one replaces the old (closed silently, no `bye`).

**Notes.** The phone's `notes` list is compared with the library; missing or lower-sequence notes are wanted (never a Mac-created note, never one in Recently deleted). A transfer is staged under `<library>/.incoming-link/`, size and SHA-256 are checked, then it goes through `LibraryModel.importCopy` like a manual import. Failed or partial transfers leave nothing; the folder is emptied at launch. If the note is open it is reloaded in place (new copy built off screen and swapped in one main-thread block, viewport kept).

**Live mirror.** While a phone is connected, canvas-space previews for the read-only phone copy in the active tab are drawn and nothing is written. When the newer copy installs, the swap drops exactly the previews whose stroke IDs are in the new copy in the same block (no blink, no doubling); other previews stay until they expire (10 s after End). "Follow phone" opens the note the phone is viewing in a tab once its copy exists.

**Tablet Stream.** The Mac sends `target` after welcome and when the active note, its writability, its sheet or the viewport changes (first change at once, bursts coalesced to one message per 50 ms, final state always sent). `rev` increases per change and the viewport for each rev is remembered (last 128), so a Begin that names a rev is placed with that viewport, else with the one at Begin. `pad on` creates and opens a Mac note if the active one is not a writable Mac note.

**Debug and automation** (only with the replay's debug flag, `SYNCR_MAC_DEBUG_REPLAY=1` or `--debug-replay`; use a disposable `SYNCR_MAC_LIBRARY_FOLDER`). `SYNCR_MAC_LINK_PORT=<n>` fixes the listening port (honoured with or without the flag). With the flag, single greppable lines go to stderr, never tokens:

```
link: listening port=47831
link: pairing-code 4882 phone="Galaxy S23 Ultra"
link: paired phone="Galaxy S23 Ultra" id=<phone_id>
link: connected phone="…" id=<phone_id> epoch=<n>
link: viewing id=<note id|none>
link: pad on=<true|false>
link: target note=<id|none> sheet=<id|none> writable=<bool> rev=<n>
link: note-installed id=<id> seq=<n>        link: note-current id=<id> seq=<n>
link: note-refused id=<id> reason=<text>    link: file-error id=<id> reason=<text>
link: stroke-saved note=<id> stroke=<id>
link: latency [stroke end|session end|check] phone-send-to-mac-presented p50=… p95=… max=… ms n=… rtt-last=… ms rtt-best=… ms (<what it excludes>)
link: disconnected phone="…"
link: listen-failed reason=<text>
link: transfer-aborted id=<id> received=<n> of=<n> partial-file-removed=<bool>   (the connection died mid-file; the partial file is deleted)
link: control ready                                  (debug control file is being read, see below)
```

`link: target` also carries `view=<left>,<top>,<zoom>`, the viewport the Mac told the phone about.

**Debug control** (debug flag plus `SYNCR_MAC_DEBUG_CONTROL=<file>`, used by `code/shared/link-v0/join-test.sh`): the file is polled for appended lines; each answer is one `link:` line. `report` (shown note, its sequence, strokes the renderer holds, wet-layer strokes, offscreen ink pixels, viewport, last stroke's canvas bounds, library notes with sequences, connected phones), `open <id>`, `view <left> <top> <zoom>` (exact, unclamped), `newnote`, `import <path to .syncr>` (adds a read-only phone-style copy), `forget-phones`, `link on|off`. Source: `DebugControl.swift`.

**Latency log.** Each preview frame's `sent_ms` (phone monotonic clock at socket write) is converted with the clock offset (`offset = pong.now - (at + rtt/2)` from the lowest-RTT of the last 32 ping/pong samples, the Mac pings every 2 s) and compared with the drawable's presented time, per frame, through the live-ink stage instrumentation. Rolling 1024 frames, logged 0.4 s after each stroke ends and at session end. It covers phone socket write to Mac presented (Wi-Fi, Mac queues, tessellation, GPU, compositor). It excludes pen sampling, the phone's queueing before the write (the phone logs that itself), and display scan-out. The existing receive-to-presented figure stays in the replay log. In the offscreen checks the number is only sanity-bounded; real values need the real window and phone.

**Checks** (`LinkChecks.swift`): framing limits, 3000 fuzzed streams, every vector parsed and round-tripped, clock maths, pairing (right, wrong, five tries, expiry, stored token, forget, replacement), the sync state machine with real libraries (want, transfer, hash/size/order/bad-offer failures, dropped connection, replace-when-newer, Mac-note ID refused, reload in place), mirror (drawn, nothing written, swap without doubled ink, view-relative refused), target coalescing and rev lookup, and a loopback socket through the real Network.framework layer with a Swift fake phone (pair, sync, follow, mirror, newer copy swap, Stream into a new Mac note, replacement, hostile peer, forget, link off). Everything binds to 127.0.0.1.

**Local Network permission.** Advertising and listening may make macOS ask for Local Network access. In the unattended runs here no dialog was seen and Bonjour (`dns-sd -B`/`-L`) showed the service and TXT record; whether a prompt appears for a normally launched app was not observed. If it is denied, the link will not be discoverable and the phone must use host:port.

**Install off the main thread.** A synced note is installed with `LibraryModel.importCopyAsync`: the copy and validation (open in an isolated library, decode every stroke) run on their own utility queue, never the tessellation queue that saves live strokes; the decision (Mac-note and sequence checks) and the final `refresh` run on main in short blocks; the file swap runs on the tessellation queue behind any running load; the in-place reload and its preview swap were already one atomic main-thread block. The synchronous `importCopy` (menu import, legacy folder) is unchanged. Check: `asyncImportKeepsMainThreadFree` (validation off main, completion on main, main run loop keeps turning during a deliberately slowed import, refusals leave the library alone).

**Known limits.** A `notes` message is one 64 KiB frame, so a phone with more than roughly 600 notes must split its list (several messages are fine). File chunks are written to disk on the connection's queue (one 65 KB write at a time) next to preview handling.

## Typing and the notebook (N1–N3)

A writable note opens with an inline title and a Markdown document in the first canvas column. Click/type normally: TextKit handles selection, clipboard, spelling and input methods. Headings 1–6, emphasis, lists/tasks, quotes, fenced code, three rule styles, links, wikilinks and tags have live preview; source syntax is revealed on the caret's line. YAML front matter stays plain. Built-in code colouring covers Swift, Rust, Python, JS/TS, JSON, shell and Kotlin; unknown languages stay plain. Task boxes toggle on click.

- Enter continues lists/tasks and ends an empty item; Tab/Shift-Tab indent/outdent and numbered lists renumber. Brackets, quotes and Markdown delimiters pair around a selection. Cmd-B/I/K format bold/italic/link; Cmd-Enter toggles a task. Cmd-Z/Shift-Cmd-Z use core history shared with ink (title field edits retain their system undo).
- A pause schedules a save after 300 ms. The core adapter computes one UTF-8 changed-middle splice, not a replacement document. The first edit first adds an empty Document, then splices into it; opening an empty/v3 note alone does not write it. Saves queue before tab changes/reloads, and close/quit wait asynchronously for acknowledgements. A failed write retains the buffer and refuses close/quit until it can save. The toolbar says Saved, Unsaved, Saving… or Not saved. The fixture checks measured input-to-acknowledgement around 312–326 ms; slow disks or a busy writer are not a hard real-time guarantee.
- Document View fits the first two reference columns (zoom 1.5); Fit still shows the whole 4800-unit canvas. Drag a page's upper corner grip to change its width (700–2400 canvas units); double-click resets 1600. Resizing previews locally and saves on release. Ink never reflows with paragraphs.
- Double-click empty canvas outside the document for a free block; click its body to edit, drag its ⋮⋮ handle to move, and leave it empty to delete it. The palette's New Free Text Block puts one in the third column and selects Fit, leaving the reserved middle column empty by default. Imported copies have no editable caret and refuse mutations.
- Type `/` at line start for the filtered slash list; arrows choose and Enter applies. Every default from the contract is supported. `<library>/.mac/slash.json` is an array of `{ "name": "h1", "insertion": "# " }` records, written on first use; `{date}` and `{time}` expand locally. Choosing replaces the current source line/block and preserves a following line break. The palette also offers Insert Slash Command.
- `[[` offers note titles (arrows/Enter). Click to open; Cmd-click uses a tab. Missing targets are dimmer and created on click. Case-insensitive title resolution uses the lowest note ID to break duplicate-title ties. Renaming updates wikilinks, retaining aliases, in other writable documents as ordinary undoable splices. Code and embeds are not rewritten.
- Go to Heading remains available through the command palette; there is no right Outline/Backlinks sidebar. The left Tags section shows per-note counts; clicking a tag lists its notes. Cmd-Shift-F searches titles and document lines; phone copies are title-only. Cmd-F uses the same line picker scoped to the current editor; Enter opens the matching line. Cmd-D opens today's `yyyy-MM-dd` note in Daily. All these actions, formatting and history are palette commands.
- The notebook index is disposable in-memory derived data, updated after writes/imports/renames. After initial load, only notes whose ID/sequence/title/writability changed are read again; unchanged entries are reused and deleted notes are removed without rereads. Async passes recheck live metadata before publication, and library switches invalidate old callbacks. Explicit recovery can still rebuild from core snapshots. It contains documents, not canvas free blocks. There is no separate authoritative index file to back up. The notebook checks count reader calls (64 on initial load, zero unchanged, one for a one-note update) and cover stale/in-flight changes, rename/delete, writability, library switches and recovery.

**Markdown import/export.** File/palette Import Markdown File or Vault accepts one `.md` or a folder. Titles are basenames; a vault root is a notebook, with nested paths shown as `Vault › Projects › Work` because core notebook names forbid slashes. Fresh notes are created, never replacements. Non-Markdown files (including hidden vault metadata), symlinks, invalid UTF-8/NUL and oversized files are skipped/listed; sources are never modified. CRLF/CR input normalizes to core-required LF; other UTF-8, including an initial U+FEFF, is preserved. Export writes the document alone as `.md`, no title header, ink or free blocks. LF-compatible export/import round-trips byte-for-byte. A failed multi-file import may leave already completed notes; it never overwrites existing notes.

**Separation from ink.** Text editing/styling is main-thread TextKit work, not a call from the receive/draw callbacks. The text layer is a clipped sibling of the CAMetalLayer, not a bitmap capture/rasterization of Metal or an ancestor layer. Both use `Viewport.visibleSize` for exactly the same translation and scale. Text mutations use the existing serial note worker, never the live-ingest queue; the link still calls `LiveInk.receive` directly on its connection queue. `arrival`, one MSAA pass and all original renderer/link checks stay unchanged. Large-document editing and very large libraries have not been performance-qualified.

Paired debug replay, matched temporary fixture libraries/zoom 1.5, arrival; software received-to-presented ms, p50 / p95 / max:

| Replay | with document | without document |
|---|---|---|
| pen (116 frames) | 16.0 / 29.3 / 46.5 | 15.2 / 26.7 / 43.7 |
| highlighter (128) | 13.7 / 23.8 / 27.1 | 17.9 / 59.9 / 67.5 |
| view-relative (116) | 14.7 / 24.1 / 28.1 | 15.4 / 39.2 / 51.2 |

These are individual runs on a shared, busy machine, not proof of no latency cost. Window captures showed text and the committed pen together. A further run inserted 25 characters at 50-ms intervals during pen replay: pen p50/p95/max 28.1/60.4/82.6 ms (116 frames); the capture showed the caret, appended characters and a partial wet segment together, and all 437 document bytes were saved. This is not physical pen latency or display scan-out, and typing does add main-thread/compositor work.

## Document layout and page images — companion handoff

`Sources/SyncrMac/DocumentLayout.swift` exports main-actor, offscreen TextKit functions with no window or core handle requirement:

```swift
DocumentLayout.lines(note: NoteSnapshot, width: CGFloat) -> [DocumentLayoutLine]
DocumentLayout.image(note: NoteSnapshot, width: CGFloat, scale: CGFloat = 0.5) -> NSImage?
DocumentLayout.page(note: NoteSnapshot, width: CGFloat) -> DocumentPage?
```

Here `width` is the outer document-column width (normally the live Document block's `width`). Each line has a UTF-16 `range` into the original Markdown, its raw `text`, and a top-left **canvas-unit** `rect`, including sheet/block translation and the document's 100-unit side/260-unit top inset. An absent Document returns no lines/image. No anchor persistence or AI margin content is created here. The app only creates identity/translation text transforms; arbitrary affine rotation/shear is not qualified by these helpers.

Overloads taking `DocumentPage(title:text:x:y:width:)` accept text without a snapshot; for these, `width` is the **inner wrap width**, normally outer width minus 200. Fonts are 48-unit body, system headings/monospace. Screen and image use the same styling and rule/task/code-label decorations; offscreen preview hides all syntax (there is no caret). `image` includes the inline title and document, not ink/free blocks/margin, and follows the current AppKit appearance. Read/write PNG with `NSBitmapImageRep` after `tiffRepresentation`, or use debug `page-image` below. `TextDocument`/`MemoryTextDocument` are the editor seam; `TextSession` retains failed buffers; `NotebookController` is the asynchronous core adapter.

## Notebook debug hooks

All hooks require the existing debug flag plus `SYNCR_MAC_DEBUG_CONTROL`; they never enable on a normal launch. Use a disposable `SYNCR_MAC_LIBRARY_FOLDER`. `SYNCR_MAC_LINK_LOOPBACK=1` with the flag disables Bonjour and binds test sockets to loopback.

Additional control lines:

- `front`, `window`, `windows`: activate/unhide and report this app's window IDs for `screencapture -l ID`.
- `typefile /tmp/file.md`: replace the writable document buffer; `insert "JSON-escaped text"`: real TextKit insertion; `caret <UTF-16 offset>`; `click-link <UTF-16 offset> [newtab]` sends a synthetic click through the real link handler; `key enter|tab|backtab|bold|italic|link|task|undo|redo`; `save`; `text-report` (buffer/save state and separately read core bytes/width/block count).
- `width <units>` or `drag-width <units> [left]` (the latter sends synthetic down/drag/up through the real grip handlers); `free <x> <y> [text-file]`; `free-edit <index> <text-file>`; `free-move <index> <x> <y>` or `drag-free <index> <dx> <dy>` (real handle handlers).
- `rename New title`, `wikilink Target title`, `daily`, `outline <zero-based heading index>`, `search query`, `tag tag/name` inspect or exercise the corresponding notebook paths.
- `command <AppCommand rawValue>` opens the real command UI (for example `searchNotes`, `findInNote`, `documentView`, `fit`); `palette query`, `accept` drive its actual picker; `tag-open tag/name` opens the actual tag picker.
- `mdimport /tmp/vault`, `mdexport /tmp/note.md`, `native-export /tmp/note.syncr`, `page-image /tmp/page.png`, `replay`, `quit`.

For matched replay runs add `SYNCR_MAC_DEBUG_AUTOREPLAY=1`, with either `SYNCR_MAC_DEBUG_DOCUMENT=/tmp/text.md` or `SYNCR_MAC_DEBUG_NO_DOCUMENT=1`. Both set document zoom 1.5. The latter hides the virtual empty editor without creating a text block. If window capture fails while the display is asleep, `caffeinate -u -t 120` can wake it for capture; this does not test scan-out.

`NotebookChecks.swift` and `NotebookCoreChecks.swift` add pure logic, real TextKit/memory seam, retained write-failure buffers, UTF-8/BOM boundaries, 300-ms debounce, real core pause/restart/history/width/free/read-only, derived-index rebuild, rename/undo, daily and fixture-vault/core/export round-trip checks. Their source symlinks compile the real app implementation. The existing link check's pixel counter now compares BGRA bytes directly instead of allocating an array for every pixel before taking its latency sample; its original expectations and 1000-ms sanity bound are unchanged. Unattended window screenshots verify appearance and scripted paths, not physical keyboard/IME, clipboard, mouse/trackpad feel, tearing or real-phone latency.

## Companion v2: engine, pictures, images, voice (mac-companion-2)

- **Engine.** `CompanionController` keeps one `CompanionEngine` per open note tab (created on first use, shut down when the tab closes; each has its own lazily started pi child, retired after five idle minutes without discarding the engine). A pass streams: provisional margin or reply text appears as it arrives (replies in the thread window), picture steps draw as each closes, complete items are staged and written only on `.finished` through `CompanionStore.apply` (one validated `submit_agent` write). `.cancelled` discards; typing cancels at once (the engine cancels on any typing, not only inside the answered passage). Nothing on the main thread waits on a provider. Ink-only notes (no text to quote) still use the one-shot `CompanionSession` path, because the engine has no region notes. Settings: Codex, Claude key or OpenAI key; "Gate (reading along)" and "Answer" model pickers; the decision log is `<library>/.mac/companion-decisions.jsonl`; the strip's amber mark pulses while a pass is in flight.
- **Pictures** (on by default since 2026-11-16; "Pictures" in Companion Settings, stored as `pictures` in companion.json). Accepted pictures are stored as `CompanionBody::Picture` (filtered, exact filtered source) with a `![[syncr-drawing:ID]]` embed line under the passage, drawn by `PictureCard` (caption, stepper, replay, "See it larger", Pin -> `PatternBook.addPinned`). A picture whose embed line was deleted waits in the margin. Old primitive drawings still render. `PictureRenderer` is shared by app, checks and `SyncrPictureTrial`. **Hand-drawn lines** (Settings, default on): seeded wobble, end overshoot and a faint second pass on straight lines and box edges, smoothed curves, crisp labels; off draws clean strokes.
- **Images.** Paste or drop: `NoteImages.prepare` (ImageIO, off main) then `NoteImageStore.place` (text anchor under the caret's paragraph, then `ImageMarks Add`). Stored images draw under their paragraph (`DocumentLayout` imageGaps) and on reload. Images near the passage go to the engine scaled to 1280 px; after a finished pass `MarkSeen` goes through `submit_agent` and a small "seen" mark appears. Bytes are read with `LibraryModel.editNote` on the note worker.
- **Voice.** See `CompanionVoice.swift`; `bundle.sh` makes a bundled app with usage strings for dictation.
- **Debug control** (debug flag only): `ai-script <file> [chunk] [delay-ms]` / `ai-all` / `ai-draw` replay recorded provider text (passes split by a `-----` line) as a stream; `ai-out` lines trace each engine output with its time; `ai-hour <script>` runs a simulated hour; `typing-latency <n>`; `ai-hand on|off`; `ai-cancel`; `ai-report`.
- **Checks.** `swift run SyncrMacChecks --companion-v2` runs the v2 checks alone (adapter fields, marker workaround, deterministic pen, baselines, picture and image storage, seen, relaunch); they also run in the full suite.
- **Trial.** `swift run SyncrPictureTrial --live` (16 prompts from `tasks/sketch-trial-2/prompts.json`, newest Sol on pi, at most 40 live calls ledgered in `tasks/sketch-trial-2/live-calls.jsonl`; `--only N`, `--output DIR`); without `--live` it replays one recorded fixture. `--patterns DIR [--clean]` renders the twelve bundled patterns through the core with no model.
