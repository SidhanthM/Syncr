# Syncr Android

Offline handwriting notes for the phone (S Pen first), plus **Tablet mode**: **Pen** writes into the Mac's note;
**Trackpad** controls the Mac cursor with fingers. Both use the paired Wi-Fi Mac link, not Bluetooth. Kotlin + Compose, AndroidX Ink 1.0.0 for
rendering, and the Rust core (`../core`) for all storage, history and validation through generated
UniFFI/JNA bindings (`../core/generated/kotlin`, `../core/generated/jniLibs`, arm64-v8a only).

App ID `dev.syncr.prototype`, minSdk 26, target/compile SDK 36, JVM 17.

## Commands

From the repo root (the `syncr` script picks the device; set `ANDROID_SERIAL` when more than one
phone, transport or emulator is connected):

```sh
./syncr devices                              # list ADB devices
./syncr emulator                             # start the Medium_Phone_API_37.0 AVD
./syncr app_update                           # build, install and open
ANDROID_SERIAL=emulator-5554 ./syncr app_update
ANDROID_SERIAL=R5CW12345 ./syncr app_update
./syncr logcat                               # SyncrHid + crashes
./syncr pair PHONE_IP:PAIRING_PORT && ./syncr connect PHONE_IP:DEBUG_PORT   # wireless debugging
```

From `code/android/`:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:testDebugUnitTest -PsyncrNativeHost=true   # adds HostCoreIntegrationTest and LoopbackHostTest (the Mac link, real core) against the real Rust host lib
python3 scripts/verify-apk.py                            # APK packs the current core .so, JNA, Ink; uncompressed arm64
./gradlew :app:lintDebug
./gradlew :app:connectedDebugAndroidTest                 # device/emulator only; installs the app
```

Re-run `verify-apk.py` (after `assembleDebug`) whenever the core's `.so` is rebuilt.

## How to measure writing latency

The pen path has `android.os.Trace` slices (no-ops unless a trace is recording, only for a debuggable
build): `syncr.input.touch` (whole touch handling), `syncr.wet.start`, `syncr.wet.add` (Ink add plus
the prediction call), `syncr.wet.finish`, `syncr.wet.handoff` (wet to dry in one frame) and
`syncr.dry.draw` (all dry strokes in a frame). Use a debug build, install it yourself, then write
on the canvas while recording (S23, USB or wireless adb):

```sh
adb shell perfetto -o /data/misc/perfetto-traces/syncr.pftrace -t 15s \
  -a dev.syncr.prototype sched freq view gfx input binder_driver am wm
adb pull /data/misc/perfetto-traces/syncr.pftrace && open https://ui.perfetto.dev   # drop the file in
```

On the phone instead: Developer options, System Tracing, record a trace with the app selected.

What to look for in the Perfetto UI:

- `syncr.input.touch` on the main thread should stay well under 1 ms per event; `syncr.wet.add`
  is most of it.
- `syncr.dry.draw` should not appear while only the pen moves with ink; it appears at pen-down, pen-up,
  finger pan/zoom, eraser, lasso and selection. If it shows during plain writing, a dry invalidate
  regressed.
- Around pen-up, `syncr.wet.handoff` followed by one frame that draws the stroke: no gap frame and no
  second copy.
- Frame timelines (`gfx`, `view`): long `Choreographer#doFrame` slices while writing mean the dry layer
  is working when it shouldn't.
- Prediction and smoothing feel can only be judged on the device; compare against Samsung Notes
  side by side at Raw and Default smoothing.

## Direct ink

An experimental second wet-ink path (`FrontInkPad`, adapted from xnotes, see `THIRD_PARTY_NOTICES.md`). It
draws the stroke under the pen straight into the buffer the panel is scanning out
(`EGL_KHR_mutable_render_buffer`, `EGL_SINGLE_BUFFER`) and publishes with a `glFlush`, with no buffer
queue. Off by default; the Ink overlay (`WetInkBridge`) stays the fallback.

- **Turn it on:** Settings, Ink, "Direct ink (experimental)". Off, or a phone that cannot do it, behaves
  exactly as before. Pen-down picks the path per stroke; highlighters always use the overlay.
- **Prediction** is off on the pad (v0), whatever "Predict pen motion" says.
- **Did it start?** `adb logcat -s SyncrFrontInk` prints one probe line (`probe ok: ...` or
  `pad unavailable: <why>`; the full EGL extension list is at debug level). While writing it prints every
  200 samples `p50=... p95=... max=... n=200 ms (input-to-flush)`: pen event to the `glFlush` that put it in
  the front buffer. That is **not** input-to-photon; the panel's scanout is not in it.
- **Slices:** `syncr.front.present`, `syncr.front.flush`, `syncr.front.handoff` (pad), next to the existing
  `syncr.wet.*` (overlay). One Perfetto capture shows both paths:

```sh
adb shell perfetto -o /data/misc/perfetto-traces/front.pftrace -t 15s \
  -a dev.syncr.prototype sched gfx view input am wm
adb pull /data/misc/perfetto-traces/front.pftrace   # ui.perfetto.dev; search "syncr.front" and "syncr.wet"
```

- **Camera test (owner):** film the pen tip and the ink with a second device's slow-motion camera (240 fps)
  and count the frames between the tip and the ink's leading edge. Do it for the old path (Direct ink off)
  with prediction on and off, then Direct ink on. Also watch pen-up: a one-frame blink or a doubled edge
  where the stroke moves from the pad to dry ink.

## Map

| Path | What |
| --- | --- |
| `MainActivity.kt` | Compose host, theme/appearance, system bar colours. |
| `ui/` | Compose screens: library, note screen and chrome, settings, dialogs, `NotesViewModel`. |
| `notes/NotesRepository.kt` | The only door to the core. Public API used by `ui/`. |
| `notes/CoreSession.kt` | Published state, core handles, the one serialized IO worker. |
| `notes/OpenNoteEditor.kt` | Stages strokes, Marks commands, metadata and history; snapshot refresh. |
| `notes/DraftQueue.kt` | Submit/lookup/retry of retained commands; recovery export. |
| `notes/LibraryOperations.kt` | Library tasks on closed notes, 30-day purge, card thumbnails. |
| `notes/NotesState.kt` | `NotesState`, `PendingEdit`, `MarkEdit`, `LibraryTask`, `RecoveryData`. |
| `notes/CoreAdapter.kt` | Blocking adapter interfaces over the generated bindings; error diagnostics. |
| `notes/NoteCanvasView.kt` | The canvas View: routes input and draws. Public API used by `ui/`. |
| `notes/CanvasScene.kt` | What to draw: snapshot + staged edits + local handoffs; hit-testing. |
| `notes/CanvasViewport.kt` | Viewport state, pan/zoom/fit/fling; `PanZoomTracker`. |
| `notes/PenCapture.kt` | Pen gesture capture, tool choice, palm rule. |
| `notes/CanvasSelection.kt` | Lasso/long-press selection and move/resize. |
| `notes/DryInkLayer.kt` / `WetInkBridge.kt` | Dry-ink cache and renderer / front-buffer wet ink. |
| `notes/StylusHoverIcon.kt` | Brush-sized system hover pointer for the stylus. |
| `notes/PageLayout.kt`, `SheetGeometry.kt`, `Selection.kt` | Pure canvas geometry, measured samples, affine math. |
| `notes/InkBrushes.kt`, `InkPreferences.kt` | Brush styles and Ink families; local rendering prefs. |
| `notes/Thumbnails.kt`, `RecoveryExport.kt`, `Notebooks.kt`, `FingerTaps.kt` | Cards, recovery JSON, notebook names/filters, tap classifier. |
| `PointerActivity.kt`, `tablet/` | Portrait/landscape Pen and finger Trackpad modes; see [POINTER.md](POINTER.md). |
| `link/` | The Mac link, plain JVM (no Android imports): wire framing and control messages, session state machine, sync sender, send scheduler, pen ring and frame pump, reconnect. |
| `link/android/` | The Android shell of the link: `LinkController` (lifecycle, Wi-Fi lock, notes bridge), `NsdFinder`, `PrefsPairingStore`. UI: `ui/MacLink.kt`. |

## Mac link

Phone to Mac over the local network, specified in `../../tasks/contracts/wifi-link-v0.md` (frozen; the wire and the shared vectors in
`../shared/link-v0/vectors.json` are the authority). **Plain TCP, no encryption** (owner decision, recorded in the contract): the
pairing token and the notes are readable by anyone who can capture the Wi-Fi traffic. Off by default. Settings, "Mac link", switches it on.

- **Lifecycle.** Connected only while the link is on and an activity is started (the notes and the Tablet pad count together, so going
  between them keeps the session). `NsdManager` finds `_syncr._tcp`; a typed `host:port` is the fallback. Pairing is the four digits the
  Mac shows (five tries); the token and the Mac's ID are kept in the app-private `syncr-link` preferences. Forget deletes them. While
  connected the app holds a `WIFI_MODE_FULL_LOW_LATENCY` Wi-Fi lock. After a drop it retries after 1, 2, 4, 8, then every 10 seconds.
- **Notes.** After `welcome` the whole manifest goes out; a changed note is announced 1.5 s after its last save, or at once when it closes.
  The Mac asks (`want`); the phone makes the copy with the core's `export_copy` on the notes worker (never the pen thread), hashes it
  (SHA-256), sends it in 65,000-byte chunks, one transfer at a time, and deletes the temp file (`cache/link-out`). One retry per
  connection on `file_error`.
- **Live mirror.** While you write in a note, one preview frame per input batch goes out in Canvas space with the stroke's real ID (the ID
  the stroke is then saved under), so the Mac's copy neither blinks nor doubles. Real samples only, never predicted points. Preview only:
  the next synced copy is the durable result.
- **Tablet mode.** Pen writes live frames and durable stroke commits into the Mac's writable note. Local maps to a movable pen frame;
  Whole view is letterboxed 4800:3000. Trackpad instead sends relative cursor, click, drag and finger-scroll controls through an independently
  versioned pointer extension. It requires Mac approval and Accessibility permission, works without a note/S Pen, and never creates a note.
  Both modes offer persisted Landscape / Portrait / Auto orientation and local fading trails. Trackpad has four themed sliders for pointer/scroll
  speed and acceleration, defaulting to 3× pointer speed and 4× scrolling before the adjustable fast-swipe boost. Older Macs without the extension
  retain pen/sync support and show an update message for Trackpad. See [POINTER.md](POINTER.md) for controls, safety limits and pending verification.
- **Where the pen touches the link** (and why it costs the local ink nothing). `NoteCanvasView` calls `InkMirror` (a `link.PenFeed` ring)
  only after the wet ink has been given the sample: `begin` after `wet.start`, `sample` after `pen.addInkSample`, `flush` after
  `wet.add`, `end` after `wet.finish`. The ring is a fixed array of preallocated slots written by the UI thread and published with one
  volatile store plus an `unpark`; no lock, no allocation, no core, disk or socket call (stroke IDs come from a pool filled on the link
  thread; `newId` is a core call). A full ring drops preview batches (the receiver sees an index gap and drops that preview cleanly) and
  keeps room to end a stroke. Encoding, the core's `PreviewSendQueue`, the socket and files all run on the link thread. A dead or slow
  Mac fills the ring and nothing else. A stroke only exists on the link when the link is up at pen-down.
- **Latency.** Pen event time to socket write and ping round trip, as p50/p95/max under `SyncrLink` at stroke/session end, labelled with what
  they exclude (digitiser, display scan-out, Mac decode/present). The same line includes `wifi-lock-held`, requested lock mode, Wi-Fi link speed,
  frequency/band, RSSI and battery-saver state (a snapshot at report time, not per latency sample). Unreadable/redacted fields say `unavailable`.
  `low-latency-effect=unobservable` is deliberate: the public API reports lock ownership, not whether firmware actually entered low-latency mode.
  Existing ACCESS_WIFI_STATE suffices for these non-location radio metrics where Android exposes them; no new permissions are requested. SSID,
  BSSID and scan/location fields are not read: those can require location permission. This gathers evidence for the next slow-network report,
  not a claimed latency fix. The Mac reports phone-send to Mac-presented. Pen-to-write is approximate under preview merging/drops.
- **Diagnostics.** Info-level `SyncrLink` lines name state changes, actual pad controls written, every target (IDs shortened to eight characters),
  and each Stream stroke's begin, end/sample count or cancel, and commit queued. Rejected pen-downs include the reason even when the link is off.
  Rejection reasons pass through a bounded ring to a worker; successful-stroke logging is on the link worker. No logcat call is on the pen path.
  See [POINTER.md](POINTER.md) for exact working-stroke lines. Pairing tokens are never logged.

**Permissions added:** `INTERNET` (TCP and NSD), `ACCESS_WIFI_STATE` (precaution for NSD; not known to be required), `WAKE_LOCK` (the
Wi-Fi lock). All normal permissions: no runtime prompt. Not added: `CHANGE_WIFI_MULTICAST_STATE` (`NsdManager` does not need a
`MulticastLock`). Newer Android releases are adding a runtime "local network" permission; this app targets 36 and does not request it.

### Host probe

The phone side of the link as a host JVM process, for testing against the real Mac app with no phone (`src/test/.../link/Probe.kt`).
It copies the fixture library to a temp folder, pairs, sends the manifest, serves `want`ed notes with the real `export_copy`, plays one
mirrored stroke and one Stream stroke at 240 Hz in real time, then waits for pings. Needs the host core (`code/core/generated/host`).

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew -q --console=plain :app:linkProbe --args="127.0.0.1:PORT --code 1234"      # code omitted: asks on stdin
#   other options: --library DIR  --store FILE (keep the pairing; a second run needs no code)  --timeout SECONDS  --no-mirror  --no-stream
```

It prints one line per result, all starting `PROBE`: `library`, `status phase=…`, `welcome mac=… epoch=…`, `sync want ids=…`,
`sync have id=… seq=…`, `sync wanted=N delivered=N`, `mirror stroke=… note=… sheet=… samples=240 frames=N space=canvas`,
`target note=… sheet=… writable=… rev=…`, `stream note=… sheet=… rev=… samples=240 frames=N commit=queued space=view-relative`,
`latency pen-to-socket-write … | round-trip … | excludes …`, `log …`, and last `PROBE done ok` (exit 0) or `PROBE FAIL <step>: <why>`
(exit 1). The same script runs against a Kotlin fake Mac in `LoopbackHostTest`.

`--join` runs the joined test instead (`JoinTest.kt`, driven by `code/shared/link-v0/join-test.sh`, which starts the Mac app, passes `--mac-log`, `--mac-control` and `--mac-library`, and checks both logs): it prints `JOIN PASS/FAIL <step>` lines and exits non-zero on any failure.

**Link off by default.** `LinkPolicy.ENABLED_BY_DEFAULT` is false; `LinkController` creates the client (sockets), the discovery object and the Wi-Fi lock only when the link is switched on in Settings and an activity is started (`LinkPolicy.shouldRun`), and holds the lock only while connected (`LinkPolicy.wantsWifiLock`). Tested on the host in `MacLinkTextTest`; the Android-side wiring itself is read, not run.

**Lint.** `lintDebug` reports 26 errors, none in link or UI code: 23 are in `HidController.kt` (Bluetooth HID, last changed 12 Oct: 12 `NewApi` for API 28 calls against minSdk 26, 11 `MissingPermission`) and 3 are `NewApi` (API 33 `Cleaner`) in the generated core bindings, which are not edited by hand. Left as they are.

## Rules

**Threading.** Every core call (FFI, storage, codec, close) runs inside `CoreSession.work`/`background`:
one IO dispatcher behind one mutex. Nothing touches the core on the UI thread. Staging edits only
appends to state; delivery happens on the worker.

**Text compatibility.** New core text blocks stay opaque on Android: not rendered or edited. Stroke commands and core `export_copy` preserve
text blocks and their history; no note is rebuilt from the phone's stroke-only view. Undo/redo is disabled if its exact dependency set touches a
text head (including a mixed text/ink batch), so the phone cannot accidentally edit hidden text. Phone recovery never stages text commands and
fails explicitly if such a command is supplied, rather than omitting it. Existing core backups remain opaque complete note copies.

**Durability.**
- A drawn stroke is not saved. Status says *Saving* until a core receipt is represented in a
  snapshot; only then does its overlay go.
- Commands and IDs are allocated once and retried or exported unchanged; a lost reply is resolved by
  `lookup`, never by a second submit. History captures target and dependencies from one snapshot.
- One eraser, selection or recolour gesture is one atomic Marks command (max 1,024 strokes).
- Leaving a note with unsaved work needs Retry, an exported recovery file, or a confirmed Discard.
  `closeNote` must succeed before the handle is released; on failure nothing is dropped.
- Library tasks (move/rename/archive/delete/restore/purge) run only with no note open, and are
  retained until they complete. Recently deleted is purged after 30 days on library load.
- Limits: 100,000 samples per stroke, 64 drafts / 500,000 queued samples. Hitting one cancels the
  whole gesture; it is never truncated.

**Input.**
- Fingers never ink: they pan, pinch, tap (2 = undo, 3 = redo, double = fit, if enabled) and
  long-press to select. The pen draws, erases or lassos.
- Each stroke freezes the canvas mapping at pen-down. New ink always goes to the first sheet in
  canvas units; legacy sheets keep their fixed origins.
- Palm rule: a finger landing within `palmMs` (setting, default 300 ms) of pen-up is ignored until
  it lifts. Pen contact abandons any finger gesture.
- The eraser end, or the side button when "side button erases" is on, erases whole strokes.
- Pause, cancel, lost contact or a second pen cancels the gesture without saving.
- Wet ink is Ink's `InProgressStrokesView` (pen only, front buffer). A finished stroke stays on
  the wet layer until Ink hands it over, then becomes dry ink in the same frame.

## Unverified on hardware

The instrumented tests (`app/src/androidTest`) compile but have not run on a device. Not yet
checked on a real phone: S Pen pressure/tilt/orientation and the eraser end, palm rejection feel,
wet/dry visual handoff and latency, the stylus hover icon (Android 14+; One UI may ignore it),
rotation and Activity recreation, process death after a receipt, SAF recovery export with real
providers, storage-full UI, JNA loading on device, and large-note performance. Pointer mode
hardware checks are listed in [POINTER.md](POINTER.md).

Before this change the owner paired the real phone over Wi-Fi and mirrored 16 strokes to the Mac. The owner's prior log showed round trips
p50 25 ms, p95 about 100 ms, max 383 ms; those are historical observations, not measurements of this build. Stream on the real pad has **not**
been run. Still needs owner checks: new caption/action layout and touch targets on screen, real S Pen historical/hover/cancel input, the Wi-Fi
lock's API report, radio metric availability on One UI, foreground reconnect/rotation, and whether the extra queue work is invisible in Perfetto.
Host tests and joined loopback do not establish hardware latency or Wi-Fi behaviour.
