#!/usr/bin/env bash
# Compiles the generated Swift bindings with the example consumer and runs it against the host library.
# Needs only the Swift toolchain from the Command Line Tools; downloads nothing.
set -euo pipefail
cd "$(dirname "$0")/.."
command -v swiftc >/dev/null || { echo "BLOCKED: swiftc not found" >&2; exit 2; }
bindings="${SWIFT_BINDINGS_DIR:-generated/swift}"
host="${HOST_LIBRARY_DIR:-$PWD/generated/host}"
for f in "$bindings/syncr_core.swift" "$bindings/syncr_coreFFI.h" "$bindings/syncr_coreFFI.modulemap" "$host/libsyncr_core.dylib"; do
  [[ -f "$f" ]] || { echo "BLOCKED: missing $f" >&2; exit 2; }
done
mkdir -p examples/swift/build
swiftc -parse-as-library -I "$bindings" -Xcc -fmodule-map-file="$bindings/syncr_coreFFI.modulemap" \
  -L "$host" -lsyncr_core \
  "$bindings/syncr_core.swift" examples/swift/Consumer.swift \
  -o examples/swift/build/consumer
DYLD_LIBRARY_PATH="$host" examples/swift/build/consumer
