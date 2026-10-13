#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export CARGO_TARGET_DIR="$PWD/target"
case "$(uname -s)" in
  Darwin) library="libsyncr_core.dylib" ;;
  Linux) library="libsyncr_core.so" ;;
  *) echo "Unsupported host for this bootstrap script" >&2; exit 2 ;;
esac
cargo build --locked --lib
mkdir -p generated
stage="$(mktemp -d "$PWD/generated/.bindings-XXXXXX")"
trap 'rm -rf "$stage"' EXIT
mkdir -p "$stage/kotlin" "$stage/swift" "$stage/host"
cargo run --locked --features bindgen --bin uniffi-bindgen -- \
  generate --library "target/debug/$library" --language kotlin \
  --config uniffi.toml --out-dir "$stage/kotlin" --no-format
cp "target/debug/$library" "$stage/host/$library"
# Swift bindings for the Mac app; generated and smoke-tested on macOS hosts only.
swift_bindings=0
if [[ "$(uname -s)" == Darwin ]] && command -v swiftc >/dev/null; then
  cargo run --locked --features bindgen --bin uniffi-bindgen -- \
    generate --library "target/debug/$library" --language swift \
    --config uniffi.toml --out-dir "$stage/swift" --no-format
  swift_bindings=1
fi
# Compile and exercise the staged pair before making either available to Android.
BINDINGS_DIR="$stage/kotlin" HOST_LIBRARY_DIR="$stage/host" scripts/smoke-kotlin-host.sh
if [[ "$swift_bindings" == 1 ]]; then
  SWIFT_BINDINGS_DIR="$stage/swift" HOST_LIBRARY_DIR="$stage/host" scripts/smoke-swift-host.sh
fi
mkdir -p generated/kotlin/dev/syncr/core generated/host
mv "$stage/kotlin/dev/syncr/core/syncr_core.kt" generated/kotlin/dev/syncr/core/syncr_core.kt
mv "$stage/host/$library" "generated/host/$library"
if [[ "$swift_bindings" == 1 ]]; then
  mkdir -p generated/swift
  for f in syncr_core.swift syncr_coreFFI.h syncr_coreFFI.modulemap; do mv "$stage/swift/$f" "generated/swift/$f"; done
  echo "Generated Swift: $PWD/generated/swift/syncr_core.swift"
fi
echo "Generated Kotlin: $PWD/generated/kotlin/dev/syncr/core/syncr_core.kt"
echo "Host library: $PWD/generated/host/$library"
