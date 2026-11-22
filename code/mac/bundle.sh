#!/bin/sh
# CLT-only local app wrapper. Build first; never changes a library or launches the app.
# SYNCR_BUILD_CONFIG=release wraps a `swift build -c release` executable instead of the debug one.
# SYNCR_SIGN_IDENTITY defaults to an ad-hoc signature that only this Mac trusts.
set -eu
cd "$(dirname "$0")"
out=${1:-/tmp/Syncr-local.app}
config=${SYNCR_BUILD_CONFIG:-debug}
identity=${SYNCR_SIGN_IDENTITY:--}
case "$out" in *.app) ;; *) echo 'Output must end in .app' >&2; exit 1 ;; esac
case "$config" in debug|release) ;; *) echo 'SYNCR_BUILD_CONFIG must be debug or release' >&2; exit 1 ;; esac
[ ! -e "$out" ] || { echo 'Refusing to replace an existing path' >&2; exit 1; }
version=$(sh ../../scripts/version.sh version)
build=$(sh ../../scripts/version.sh build)
mkdir -p "$out/Contents/MacOS" "$out/Contents/Frameworks" "$out/Contents/Resources"
cp Info.plist "$out/Contents/Info.plist"
# Stamp the one version before signing: editing the plist afterwards would break the seal.
/usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $version" -c "Set :CFBundleVersion $build" "$out/Contents/Info.plist"
cp Resources/Syncr.icns "$out/Contents/Resources/"
cp ".build/$config/SyncrMac" "$out/Contents/MacOS/"
cp Native/libsyncr_core.dylib "$out/Contents/Frameworks/"
install_name_tool -add_rpath @executable_path/../Frameworks "$out/Contents/MacOS/SyncrMac"
codesign --force --sign "$identity" "$out/Contents/Frameworks/libsyncr_core.dylib"
codesign --force --sign "$identity" "$out"
printf '%s\n' "$out"
