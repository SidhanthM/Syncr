# Install, emulator and pairing

Run these commands from your Syncr checkout root (`cd /path/to/Syncr`). `./syncr help` shows the current command list.

## Use Syncr on the Mac emulator

The Mac already has an Android SDK and an ARM64 virtual device; Android Studio and the third-party Windows-focused emulator manager are not needed. The AVD `Medium_Phone_API_37.0` is configured to match the **S23 Ultra display dimensions: 1440 × 3088 at approximately 500 dpi**. This changes the virtual screen, not the device model, hardware stylus, or Bluetooth behavior. Its original AVD settings are backed up at `~/.android/avd/Medium_Phone.avd/config.ini.before-s23-ultra-display`.

In one terminal, start the existing virtual device (keep the terminal open; Ctrl+C stops the emulator):

```sh
./syncr emulator
```

In another terminal, check its serial, then build, install and open Syncr:

```sh
./syncr devices
ANDROID_SERIAL=emulator-5554 ./syncr app_update
```

Later, to open the installed app without rebuilding:

```sh
ANDROID_SERIAL=emulator-5554 ./syncr launch
```

The serial is usually `emulator-5554`; use the actual serial printed by `./syncr devices` if it differs. When a phone and emulator are both connected, **set `ANDROID_SERIAL`** for commands that target a device, or the script will refuse to choose one. The emulator has its own app data and notes; installing there does not copy notes from the phone.

## Use a phone

If the phone is already connected, find its serial and update the app without clearing its data:

```sh
./syncr devices
ANDROID_SERIAL=PHONE_SERIAL ./syncr app_update
```

If it is the *only* connected device, `./syncr app_update` also works without `ANDROID_SERIAL`. For wireless debugging, pair once with the **pairing** port and code, then connect using the separate **debugging** port:

```sh
./syncr pair PHONE_IP:PAIRING_PORT
./syncr connect PHONE_IP:DEBUGGING_PORT
./syncr devices
```

Do not uninstall the phone app or clear its storage: that would remove its local notes. Phone and emulator storage are separate.

## Other useful commands

```sh
./syncr build                              # Build the debug APK, no install
./syncr test                               # Run Android unit tests
ANDROID_SERIAL=emulator-5554 ./syncr logcat        # SyncrHid and Android crashes; Ctrl+C stops streaming
ANDROID_SERIAL=emulator-5554 ./syncr logcat_errors # All device errors; Ctrl+C stops streaming
ANDROID_SERIAL=emulator-5554 ./syncr logcat_clear  # Clear device logs before reproducing an issue
./syncr help                               # Full command list
```

Replace `emulator-5554` with `PHONE_SERIAL` for phone logs. `logcat_clear` clears the selected device's **logs**, not app data. Bluetooth Pointer mode needs real Bluetooth hardware and is not validated by running Syncr in the emulator.
