#!/bin/sh
set -eu
cd "$(dirname "$0")"
core=../core/generated
# The app links an optimised core by default (the generated host library is a debug build and makes
# tessellation several times slower). Same source, same API: the bindings' UniFFI checksums still match.
# SYNCR_CORE_PROFILE=debug copies the generated host library instead. Only writes to the core's target/.
profile=${SYNCR_CORE_PROFILE:-release}
if [ "$profile" = release ]; then
    (cd ../core && cargo build --release --locked)
    lib=../core/target/release/libsyncr_core.dylib
else
    lib="$core/host/libsyncr_core.dylib"
fi
for file in "$core/swift/syncr_core.swift" "$core/swift/syncr_coreFFI.h" "$lib"; do
    test -f "$file" || { echo "Missing $file; generate core Swift host bindings first" >&2; exit 1; }
done
cp "$core/swift/syncr_core.swift" Sources/SyncrCore/
cp "$core/swift/syncr_coreFFI.h" Native/include/
# Remove first: overwriting a signed library in place keeps its inode, and macOS then kills
# install_name_tool (and anything loading the copy) on the stale signature.
rm -f Native/libsyncr_core.dylib
cp "$lib" Native/
# The core's host artifact carries its Rust build-directory install name. Rewrite only
# this private copy so the executable resolves it from the package-local rpath.
install_name_tool -id @rpath/libsyncr_core.dylib Native/libsyncr_core.dylib
