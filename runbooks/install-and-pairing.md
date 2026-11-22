# Install, emulator and pairing

Run these commands from your Syncr checkout root (`cd /path/to/Syncr`). `./syncr help` shows the Android command list; the native Mac app uses the Swift commands below.

## Build and run the native Mac app (Swift)

### Prerequisites

- macOS 14 or newer with Metal support.
- Apple Command Line Tools with Swift 6.0 or newer. Full Xcode is not required.
- Rust 1.94 or newer and Cargo, used to build the native core.

If Command Line Tools are not installed, run this once and finish the installer:

```sh
xcode-select --install
```

If you use Homebrew and do not already have Rust, install it with `brew install rust`. Alternatively, install Rust through [rustup](https://rustup.rs/). Verify the active tools before building:

```sh
xcrun swift --version
rustc --version
cargo --version
```

Android Studio, the Android SDK/NDK, and Java are not required to build the Mac app. The generated Swift bindings are already versioned; a normal checkout does not need to regenerate them.

### First build

From the checkout root:

```sh
# Git does not preserve this ignored generated-source directory on a fresh clone.
mkdir -p code/mac/Sources/SyncrCore

# Build the release Rust core and copy its dylib plus the Swift bindings/header.
sh code/mac/prepare.sh

# Build the Swift app and its companion dependency.
swift build --package-path code/mac

# Launch the Mac app.
swift run --package-path code/mac SyncrMac
```

The app is a native AppKit/Metal executable. The Rust core and companion are libraries used by the app, not separate servers to start. A phone is not required to use the Mac notebook.

### Everyday launches and updates

To build any changed Swift code and launch again:

```sh
swift run --package-path code/mac SyncrMac
```

To launch the existing debug build without rebuilding:

```sh
code/mac/.build/debug/SyncrMac
```

Quit the running app before rebuilding its native library. After pulling changes to the Rust core or generated bindings, rerun `sh code/mac/prepare.sh` before launching. Swift-only changes do not require that step. If the app reports a missing `libsyncr_core.dylib` or a UniFFI checksum mismatch, prepare the native core again, then rebuild Swift.

### Install a local .app for Finder/Dock launches

After the debug build above, create an app wrapper in your user Applications folder:

```sh
mkdir -p "$HOME/Applications"
sh code/mac/bundle.sh "$HOME/Applications/Syncr.app"
open "$HOME/Applications/Syncr.app"
```

`bundle.sh` copies the debug executable and native dylib and applies an ad-hoc signature. It refuses to overwrite an existing path. For a later build, quit Syncr and either use a new `.app` name or move the old app wrapper to the Trash before bundling again. Removing the wrapper does not remove the separate note library.

This is a **local development wrapper**, not a notarized installer or portable distribution. Keep the checkout and its build/resource outputs in place; rebundle after rebuilding to update the executable and native library. Bundling supplies the microphone/speech usage descriptions needed for dictation; macOS will request permission when dictation is used.

### Library and safe test launches

The default library is `~/Documents/Syncr/`. To try the app without using your normal notes, launch it with a fresh temporary library (quit other Syncr instances first):

```sh
SYNCR_MAC_LIBRARY_FOLDER="$(mktemp -d /tmp/syncr-mac-library.XXXXXX)" \
  swift run --package-path code/mac SyncrMac
```

Use **File → Choose Library Folder…** to select another library for the current session. Do not point test builds at irreplaceable originals: the app owns its library and may migrate managed note files.

### Checks

After preparing the native core, run these from the checkout root:

```sh
# Full Mac checks, including the renderer, notebook, settings, and hotkeys.
swift run --package-path code/mac SyncrMacChecks

# Companion checks use recorded fixtures, not paid AI requests.
swift run --package-path code/companion SyncrCompanionChecks

# Rust core tests.
cargo test --locked --manifest-path code/core/Cargo.toml
```

For phone-to-Mac use, keep both apps open on the same Wi-Fi, enable **Phone Link** in the Mac phone menu and **Mac link** in Android Settings, select the Mac, and enter the pairing code shown by the Mac. Allow Local Network access if prompted. App pairing is separate from the ADB wireless-debugging pairing described below. The current app link is plain TCP, so use a trusted network.

## Use Syncr on the Android emulator

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

If it is the *only* connected device, `./syncr app_update` also works without `ANDROID_SERIAL`. For wireless debugging, pair once by QR code (needs `brew install qrencode`; the phone scans it from Wireless debugging > Pair device with QR code):

```sh
./syncr pair
./syncr devices
```

Fallback: pair with the **pairing** port and code, then connect using the separate **debugging** port:

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
