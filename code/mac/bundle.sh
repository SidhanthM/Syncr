#!/bin/sh
# CLT-only local app wrapper. Build first; never changes a library or launches the app.
set -eu
cd "$(dirname "$0")"
out=${1:-/tmp/Syncr-local.app}
case "$out" in *.app) ;; *) echo 'Output must end in .app' >&2; exit 1 ;; esac
[ ! -e "$out" ] || { echo 'Refusing to replace an existing path' >&2; exit 1; }
mkdir -p "$out/Contents/MacOS" "$out/Contents/Frameworks"
cp Info.plist "$out/Contents/Info.plist"
cp .build/debug/SyncrMac "$out/Contents/MacOS/"
cp Native/libsyncr_core.dylib "$out/Contents/Frameworks/"
install_name_tool -add_rpath @executable_path/../Frameworks "$out/Contents/MacOS/SyncrMac"
codesign --force --sign - "$out/Contents/Frameworks/libsyncr_core.dylib"
codesign --force --sign - "$out"
printf '%s\n' "$out"
