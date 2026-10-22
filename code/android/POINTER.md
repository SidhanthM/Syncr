# Syncr Tablet mode (Wi-Fi Stream, Bluetooth Pointer fallback)

Tablet mode defaults to **Stream · Wi-Fi** if Mac link is on and a pairing exists, otherwise **Pointer · Bluetooth**. An explicit selection
wins and persists. Tap the corner caption (`⇄`) once to switch links, or use Link in the setup panel. Stream writes with pressure/optional
tilt into the note open in Syncr on the Mac, saved there and never on the phone. Pointer is a Bluetooth HID **absolute mouse** for any Mac app;
it has no pressure/tilt and Syncr stores nothing. Code: `PointerActivity.kt` (name retained for existing entry points), `tablet/` and `link/`.

## Stream · Wi-Fi

- Turn Mac link on and pair in phone Settings; open Syncr on the Mac. With no explicit pad-link choice, opening Tablet mode now selects Stream.
  Check the caption says **Stream · Wi-Fi**, not Pointer. The pad is a separately foreground-counted activity and brings the link up itself.
- The top message says `<Mac> · Ink is saved in the Mac's note` only while connected with a writable target. Other states:
  - `Mac link is off` → **Turn on Mac link**.
  - `Pair your Mac in Mac link settings` → **Open Mac link settings**, directly on the pad (discovery, manual address and four-digit code).
  - `Waiting for <Mac>. Is the Syncr Mac app running?` → **Use Bluetooth pointer**. This fallback is only for this pad session, including rotation;
    it does not replace the saved preference. A caption/panel selection, in contrast, persists.
  - `Waiting for a writable note on the Mac` → wait: pad-on creates a Mac note when none is active. Read-only targets cannot receive ink.
- One scale maps the letterboxed active area to 4800 × 3000, independent of Pointer mapping. Fingers/palms cannot ink or move this letterbox.
  Hover sends position only. Real history is traversed oldest first; pen-up includes the final point and pressure clamps to 0..1.
  Cancel, second pen, lost contact, resize, focus loss or pause abandons the stroke. A target/sheet/writability/revision change mid-stroke also
  cancels whole, rather than saving against a stale view; lift and try again. Leaving or switching away sends `pad off`.
- Stream input and UI are not hardware-verified. The owner previously verified Wi-Fi pairing and live note mirror, **not Stream**.

### Exact logcat events

Owner-run filter: `adb logcat -v time SyncrLink:I '*:S'`. For a normal Stream stroke, the message text is (values vary; IDs are eight-character prefixes):

```text
state connecting mac=M2mac
state connected mac=M2mac
wifi lock acquired held=true requested=WIFI_MODE_FULL_LOW_LATENCY low-latency-effect=unobservable(public API reports isHeld only)
pad on sent
target present=true writable=true rev=7 note=12345678 sheet=abcdefgh
stream stroke begin id=87654321 note=12345678 sheet=abcdefgh rev=7
stream stroke end id=87654321 samples=240
stream stroke commit queued id=87654321
latency stroke N: pen-event-to-socket-write n=… p50=… p95=… max=… | round-trip n=… p50=… p95=… max=… | wifi-lock-held=true requested=WIFI_MODE_FULL_LOW_LATENCY low-latency-effect=unobservable wifi-speed=…Mbps wifi-frequency=…MHz band=5GHz rssi=…dBm battery-saver=false location-fields=not-read(no-location-permission) | excludes: digitiser, display scan-out, Mac decode and present (the Mac logs phone-send to Mac-presented)
pad off sent
```

`state needs code mac=M2mac` means pairing is needed. Disconnect: `state disconnected reason=<reason>` and
`wifi lock released held=false requested=WIFI_MODE_FULL_LOW_LATENCY`. Cancel: `stream stroke cancel id=<prefix> samples=<n> reason=<reason>`.
Rejected down: `stream stroke not sent reason=Mac link is off` (or Mac needs pairing code / Mac is not paired / Mac is disconnected / no target /
target not writable / no stroke ID available / link disconnected or pen queue full). A revision change also reports
`stream stroke not sent reason=target changed mid-stroke (including rev)`. Logging runs on workers, never directly on pen input.
Commit queued is **not** a Mac-save acknowledgement; the Mac's `link: stroke-saved` line and visible note are the durable confirmation.

Radio metrics use existing normal permissions and say `unavailable` when redacted/unreadable. No SSID, BSSID or scan/location data is read.
Lock acquisition proves only API-reported ownership, not that the driver enabled low-latency operation. Metrics are captured at report time;
no fix for the owner's earlier slow round trips is claimed.

## Tablet mode

- Black, immersive, landscape (`sensorLandscape`). A dotted outline marks the active area; the pen leaves a ~500 ms fading trail (local feedback only, **not** a mirror of the Mac).
- **Pen only.** Stylus down = button down, move = absolute move (all historical samples), up = final point then release, cancel/pause/focus loss/back/setup panel = release. Hover moves without clicking. Fingers never send pointer data; their only jobs are a two-finger drag (moves the Actual-size window) and a tap on the corner caption (switches the link). A resting palm within 300 ms of pen-up cannot activate the caption.
- **Corner caption:** `POINTER · BLUETOOTH · CONNECTED TO <NAME>` or `NOT CONNECTED`, the mapping, `UNCALIBRATED` in Actual size until you tick the ruler check, and (Actual size) a mini-map of where the window sits on the Mac screen. Connection state is read from `HidController`'s status messages.
- **Corner caption:** ends in `⇄`; a finger tap switches Stream · Wi-Fi ↔ Pointer · Bluetooth and saves that explicit selection.
- **Setup panel** (bubble > Settings; opens automatically in Pointer until a Mac has been chosen): link, mapping, Mac screen size in mm (default 290 x 189, M2 Air), ruler check, Bluetooth permission, Bluetooth settings, Select paired Mac. Choices, the window position and the last Mac (auto-reconnected) persist in SharedPreferences `tablet_mode`.

## Mapping (`tablet/TabletMapping.kt`, tested in `TabletMappingTest`)

One scale for both axes, always (the old u = x/w, v = y/h stretched shapes).

- **Actual size (default):** 1 mm of pen = 1 mm of cursor, from `DisplayMetrics.xdpi/ydpi` and the Mac size: `u = (ox + x*25.4/dx) / W`, `v = (oy + y*25.4/dy) / H`, with the window corner (ox, oy) clamped to `0..W - w*25.4/dx`, `0..H - h*25.4/dy`.
- **Whole screen:** the active area is the largest W:H rectangle centred on the pad; `u = (x - ax)/aw`, `v = (y - ay)/ah`.
- **Both:** `X = round(clamp(u,0,1) * 32767)`. That integer is passed to the unchanged `AbsoluteMouse.report(X, Y, 32767, 32767, pressed)`, which encodes exactly X (tested for every value). The HID descriptor and report format are unchanged.

## Hardware check

Build and install as in [README.md](README.md) (`./syncr app_update`). The app logs connection status under the `SyncrHid` tag (`./syncr logcat`). Bluetooth HID pairing is separate from ADB pairing.

Requires Android 9+ and a device exposing Android's `BluetoothHidDevice` profile; Android 12+ requires granting Nearby Devices / Bluetooth Connect. The S23 Ultra and macOS behavior **has not been tested** here (no connected phone).

1. Enable Bluetooth on both devices. Open **Bluetooth settings / pair** in the app; pair the S23 and Mac through their system Bluetooth settings. Return to Syncr, grant the Bluetooth permission, and tap **Select paired Mac**. If a stale device entry persists after changing the HID descriptor, forget the pairing on both sides and pair again.
2. Open any simple Mac drawing canvas, select its pen/brush, and put the canvas on the display you wish to test. Write on the pad with the S Pen: down holds the primary mouse button, move sends absolute X/Y, and up/cancel releases it. Hover moves without clicking. In Actual size, draw a 5 cm line along a ruler on the phone and measure it on the Mac; if they match, tick the ruler check in setup. Draw a circle in both mappings and check it is not stretched.
3. Test a long continuous stroke, pen lift, cancellation (switch apps mid-stroke), and reconnect. Closing the app unregisters HID and attempts to release the mouse button.

**Acceptance gate:** Check whether macOS actually treats the generic HID mouse descriptor's X/Y fields as absolute coordinates. Standard HID supports absolute axes, but macOS may handle them differently or not expose a useful pointer. The Gradle tests verify report bytes, **not** host interpretation, S23 HID support, pairing, or latency. If coordinates drift or fail to land at fixed screen locations, do not claim absolute tablet control; investigate macOS digitizer HID support or ask before adding a Mac receiver or switching to relative movement. Still unverified on hardware: how accurate the phone's reported DPI is (Samsung's screen-resolution setting may make `xdpi` disagree with the real pixel pitch; the setup panel shows the pad size in mm to check with a ruler), which display macOS maps the pointer to with more than one connected, whether S Pen edge strokes trigger the system back gesture, and the feel of the fading trail.

No Samsung private libraries or Appground code are used. Stream uses the Wi-Fi wire contract in `../../tasks/contracts/wifi-link-v0.md`; Pointer's Bluetooth protocol is unchanged.
