# Tablet mode: Pen and Trackpad over Wi-Fi

Both modes use the existing **Mac link** pairing. Bluetooth/HID is not used. The existing TCP link is **not encrypted**; use Trackpad only on trusted Wi-Fi. Pairing is not protection against an on-path attacker who can capture its token. `PointerActivity.kt` retains its old name for existing entry points.

## Open and configure

1. Enable Mac link and pair the phone with Syncr on the Mac (phone Settings → Mac link, four-digit code).
2. Open Tablet mode. The bubble's first chip switches **Pen / Trackpad**; Settings has the same choice.
3. Settings → **Orientation** offers **Landscape**, **Portrait**, or **Auto**. Mode, orientation, trail length and the Pen view choice persist locally.

The pad is black and immersive. Rotation cancels active gestures before remapping; no ongoing stroke or drag is stretched into a new orientation. Pen's Local frame now accepts portrait aspect ratios down to 0.25. Whole view continues to letterbox the Mac's landscape view instead of distorting it.

## Pen

S Pen input writes into the note open in Syncr on the Mac, saved there rather than on the phone. A note is created when Pen opens if no writable destination is available under the existing pad rules.

- **Local:** maps onto a pen frame on the Mac page. Two fingers move the frame; pinch resizes it.
- **Whole view:** covers the Mac's current canvas view with uniform scaling.
- The Mac sends pictures of the covered region. Local stroke feedback is drawn immediately while waiting for the next picture.
- Cancel, second pen, resize, focus loss or pause drops an unfinished stroke. Changes to target/sheet/writability/revision cancel a stroke rather than commit against stale coordinates.

## Trackpad

No note or S Pen is required. Finger input controls the **Mac system pointer**, including other apps:

| Gesture | Action |
|---|---|
| One-finger move | Relative cursor movement |
| One-finger tap | Left click |
| Two-finger drag | Vertical / horizontal scroll |
| Two-finger tap | Right click |
| Double tap, keeping the second contact down | Left-button drag once the finger moves or holds (about 150 ms); lift releases |
| Two quick taps | Double click |

S Pen contact can also move/tap, but there is no pen-only requirement. Adding/lifting contacts reanchors the gesture so the cursor does not jump, and the last finger after a two-finger scroll cannot accidentally start moving the cursor. A third finger brushing past pauses a scroll (no click, no cursor motion) and the scroll resumes from where it is once back to two fingers. A scroll starting right after a tap never presses the button: a drag is only a candidate until the finger moves or holds.

Scrolling is phased like a Mac trackpad: the Mac sends began, changed and ended scroll events in whole pixels. The phone closes each scroll with an optional `se` key on a pointer packet (finger lift, cancel, drag start); if it is lost, the Mac ends the scroll after 150 ms of quiet. Direction follows the Mac's natural-scrolling setting (`com.apple.swipescrolldirection`), natural when unreadable. Not built: momentum after lift.

Every active contact gets an independent coloured fading trail and a contact dot, including both scrolling fingers. Trails are local feedback, bounded in memory, never connected across fingers, and never stored as note ink. Trail length follows the existing setting.

### Pointer and scroll response

Tablet bubble → **Settings → Trackpad response** has four Syncr-drawn sliders (themed rail/fill/thumb, 48dp targets and accessibility adjustment), not native/Material sliders. They are also available in phone **Settings → Tablet mode**.

| Control | Default | Range |
|---|---|---|
| Pointer speed | 3.0× | 0.5–8.0× |
| Pointer acceleration | 75% additional fast-swipe speed | 0–200% |
| Scroll speed | 4.0× | 0.5–12.0× |
| Scroll acceleration | 50% additional fast-swipe speed | 0–200% |

Base speeds are relative to the former 1dp → 1 Mac-point mapping, so existing installs without saved tuning immediately get faster defaults. Slow corrections use base speed; fast swipes smoothly add the selected acceleration (75% means up to 1.75× base speed). Set acceleration to 0% for constant gain. **Reset Trackpad response** restores all four defaults.

Values save when the slider is released or adjusted through accessibility, survive restarts/rotation, and apply when returning to the pad. Acceleration uses dp/second, not distance per sample. Only the velocity estimate is smoothed; no extra frame queue or delayed movement is introduced. Contact changes, cancellation and tuning changes clear its history. This does not scale tap thresholds, clicks, drag/release state, trails or Pen input. Boosted output remains within the existing ±2048-point wire bounds, with the same response convention on both axes.

### Mac approval and permissions

Choosing Trackpad requests local approval on the Mac. The Mac must allow that paired phone and have **System Settings → Privacy & Security → Accessibility → Syncr** enabled. The OS permission prompt is requested only after clicking Allow on the Mac, not on connection or startup. After granting permission use **Retry Trackpad** on the phone. One connection may control the pointer at a time. Closing Trackpad or disconnecting ends its approval; reconnecting requires approval again.

Status distinguishes pairing/offline, an older Mac without pointer support, Mac approval, Accessibility permission, and ready. Trackpad does not enable Pen's `pad` state, request note pictures, or create a note. There is no keyboard injection.

## Versioned pointer extension

The existing Wi-Fi link remains version 1 (compatible note sync and pen streams). Pointer support has its **own version 1**, advertised as a `pointerstate` after an authenticated welcome. An older Mac never advertises it, so the phone shows an update message and does not send pointer controls.

- `pointerstate`: `v`, `ready`, `message` (Mac → phone).
- `pointermode`: `v`, `on` (phone → Mac); explicit approval required for `on`.
- `pointer`: `v=1`, monotonic `seq`, bounded finite `dx/dy/sx/sy`, `buttons` (0 or left-held=1), `click` (0=none, 1=left, 2=right, 3=double-left/second-contact drag). Relative values are logical screen points, not backing pixels.

Pointer controls use paired TCP with control-message priority, not the preview UDP path. Queued pointer packets are capped and expire after 200 ms; overflow/stall disables control rather than replay old clicks. Buttons are current state, never toggles. Cancel, mode changes, panels, background, resize and disconnect release an active drag. The Mac also ends a held drag after a 1.2-second input timeout; stationary drags send a 150-ms heartbeat. Permission loss refuses further input.

## Verification handoff

**Compilation verified:** Mac `swift build` passes after the source-target/import fixes. Android app, JVM regression sources and instrumentation sources compile with `:app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin`.

**Test execution and hardware verification remain deferred to Claude.** No physical-phone deployment, actual cursor injection, or real Accessibility approval was performed by the assistant. No runtime speed/feel or light/dark rendering claims are made from compilation alone.

Added regression sources: `TrackpadGestureTest`, `PointerProtocolTest`, `TrackpadResponseTest` (gain, acceleration, timing independence, reset, finite bounds and sanitization), `TrackpadControlsTest` (light/dark slider actions, reset, isolated preference persistence), and pointer round-trip/hostile-input cases plus portrait aspect expectations in `SyncrMacChecks/LinkChecks.swift`. The Compose gallery includes light/dark Trackpad controls previews.

Suggested next steps for Claude:

```sh
cd code/android
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
cd ../mac
swift build
swift run SyncrMacChecks
```

Manual checks must cover portrait/landscape/auto in both modes, pen regressions, contact trails, cursor and multi-display edges, two-finger scroll/right-click, double-tap-drag, approval/denial/permission retry, old Mac compatibility, disconnect/background/rotation during a drag, stalled transport, and switching back to Pen without spurious pointer or note input. Check all four speed/acceleration sliders and reset, save/reopen/rotation, slow precise versus fast swipes, both scroll axes, and different screen densities/touch rates. Inspect custom slider rendering in light/dark themes and narrow portrait sheets.
