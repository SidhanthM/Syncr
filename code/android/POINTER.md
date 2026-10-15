# Syncr Tablet mode (Pointer link)

Tablet mode, Pointer link (design §8.2, §8.7): the phone, held on its side, is a Bluetooth HID **absolute mouse**; the Mac owns the canvas and nothing is stored. No pressure or tilt. The Stream link (Wi-Fi into a Syncr note) is shown but disabled until the Mac app exists. Code: `PointerActivity.kt` (class name kept for existing entry points) and `tablet/`.

## Tablet mode

- Black, immersive, landscape (`sensorLandscape`). A dotted outline marks the active area; the pen leaves a ~500 ms fading trail (local feedback only, **not** a mirror of the Mac).
- **Pen only.** Stylus down = button down, move = absolute move (all historical samples), up = final point then release, cancel/pause/focus loss/back/setup panel = release. Hover moves without clicking. Fingers never send pointer data; their only jobs are a two-finger drag (moves the Actual-size window) and a tap on the corner caption (opens setup).
- **Corner caption:** `POINTER · BLUETOOTH · CONNECTED TO <NAME>` or `NOT CONNECTED`, the mapping, `UNCALIBRATED` in Actual size until you tick the ruler check, and (Actual size) a mini-map of where the window sits on the Mac screen. Connection state is read from `HidController`'s status messages.
- **Setup panel** (tap the caption; opens automatically until a Mac has been chosen): link, mapping, Mac screen size in mm (default 290 x 189, M2 Air), ruler check, Bluetooth permission, Bluetooth settings, Select paired Mac. Choices, the window position and the last Mac (auto-reconnected) persist in SharedPreferences `tablet_mode`.

## Mapping (`tablet/TabletMapping.kt`, tested in `TabletMappingTest`)

One scale for both axes, always (the old u = x/w, v = y/h stretched shapes).

- **Actual size (default):** 1 mm of pen = 1 mm of cursor, from `DisplayMetrics.xdpi/ydpi` and the Mac size: `u = (ox + x*25.4/dx) / W`, `v = (oy + y*25.4/dy) / H`, with the window corner (ox, oy) clamped to `0..W - w*25.4/dx`, `0..H - h*25.4/dy`.
- **Whole screen:** the active area is the largest W:H rectangle centred on the pad; `u = (x - ax)/aw`, `v = (y - ay)/ah`.
- **Both:** `X = round(clamp(u,0,1) * 32767)`. That integer is passed to the unchanged `AbsoluteMouse.report(X, Y, 32767, 32767, pressed)`, which encodes exactly X (tested for every value). The HID descriptor and report format are unchanged.

## Build / install / logs

From the project root (no `sudo`):

```sh
./syncr devices                 # USB or Wireless debugging must show `device`
./syncr test                    # Android unit tests
./syncr app_update              # build + install + open on the phone
./syncr logcat                  # stream running-app logs, Ctrl+C to stop
./syncr logcat_errors           # all device errors, including crashes
./syncr help                    # full command list
```

If Wireless debugging isn't connected yet, use `./syncr pair PHONE_IP:PAIRING_PORT` (enter the code shown on the phone), followed by `./syncr connect PHONE_IP:DEBUGGING_PORT` (a different port on the Wireless debugging main screen). If more than one phone/transport appears, specify `ANDROID_SERIAL=<serial> ./syncr app_update` using a serial from `./syncr devices`. Bluetooth HID pairing is separate from ADB pairing. The app writes connection status to Logcat under the `SyncrHid` tag.

Equivalent manual build: `cd code/android && ./gradlew :app:testDebugUnitTest :app:assembleDebug`.

Requires Android 9+ and a device exposing Android's `BluetoothHidDevice` profile; Android 12+ requires granting Nearby Devices / Bluetooth Connect. The S23 Ultra and macOS behavior **has not been tested** here (no connected phone).

1. Enable Bluetooth on both devices. Open **Bluetooth settings / pair** in the app; pair the S23 and Mac through their system Bluetooth settings. Return to Syncr, grant the Bluetooth permission, and tap **Select paired Mac**. If a stale device entry persists after changing the HID descriptor, forget the pairing on both sides and pair again.
2. Open any simple Mac drawing canvas, select its pen/brush, and put the canvas on the display you wish to test. Write on the pad with the S Pen: down holds the primary mouse button, move sends absolute X/Y, and up/cancel releases it. Hover moves without clicking. In Actual size, draw a 5 cm line along a ruler on the phone and measure it on the Mac; if they match, tick the ruler check in setup. Draw a circle in both mappings and check it is not stretched.
3. Test a long continuous stroke, pen lift, cancellation (switch apps mid-stroke), and reconnect. Closing the app unregisters HID and attempts to release the mouse button.

**Acceptance gate:** Check whether macOS actually treats the generic HID mouse descriptor's X/Y fields as absolute coordinates. Standard HID supports absolute axes, but macOS may handle them differently or not expose a useful pointer. The Gradle tests verify report bytes, **not** host interpretation, S23 HID support, pairing, or latency. If coordinates drift or fail to land at fixed screen locations, do not claim absolute tablet control; investigate macOS digitizer HID support or ask before adding a Mac receiver or switching to relative movement. Still unverified on hardware: how accurate the phone's reported DPI is (Samsung's screen-resolution setting may make `xdpi` disagree with the real pixel pitch; the setup panel shows the pad size in mm to check with a ruler), which display macOS maps the pointer to with more than one connected, whether S Pen edge strokes trigger the system back gesture, and the feel of the fading trail.

No Samsung private libraries or Appground code are used. This prototype is independent of the existing Wi-Fi/stroke-sync feasibility plan in `../../tasks/plan.md`.
