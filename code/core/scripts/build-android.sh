#!/usr/bin/env bash
# No sdkmanager/rustup installation or device access is performed by this script.
set -euo pipefail
cd "$(dirname "$0")/.."
export CARGO_TARGET_DIR="$PWD/target"
abi="${1:-arm64-v8a}"
if [[ "$abi" != arm64-v8a ]]; then
  echo "Only the approved S23 ABI arm64-v8a is configured" >&2; exit 2
fi
ndk_version=28.2.13676358
ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$ndk" || ! -f "$ndk/source.properties" ]]; then
  echo "BLOCKED: set ANDROID_NDK_HOME to installed NDK $ndk_version. No download attempted." >&2
  exit 2
fi
installed_ndk_version="$(awk '$1 == "Pkg.Revision" {print $3}' "$ndk/source.properties")"
if [[ "$installed_ndk_version" != "$ndk_version" ]]; then
  echo "BLOCKED: expected pinned NDK $ndk_version, found $installed_ndk_version." >&2
  exit 2
fi
if ! rustup target list --installed | grep -qx aarch64-linux-android; then
  echo "BLOCKED: Rust target aarch64-linux-android is not installed. Install explicitly before retrying." >&2
  exit 2
fi
case "$(uname -s)" in
  Darwin) host=darwin-x86_64 ;;
  Linux) host=linux-x86_64 ;;
  *) echo "Unsupported NDK host" >&2; exit 2 ;;
esac
bin="$ndk/toolchains/llvm/prebuilt/$host/bin"
linker="$bin/aarch64-linux-android26-clang"
[[ -x "$linker" ]] || { echo "BLOCKED: missing NDK compiler $linker" >&2; exit 2; }
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$linker"
export CC_aarch64_linux_android="$linker"
export AR_aarch64_linux_android="$bin/llvm-ar"
# The default language set (python, c, cpp, java) is built; see Cargo.toml features.
cargo build --locked --release --lib --target aarch64-linux-android
mkdir -p generated
stage="$(mktemp -d "$PWD/generated/.android-XXXXXX")"
trap 'rm -rf "$stage"' EXIT
cp target/aarch64-linux-android/release/libsyncr_core.so "$stage/libsyncr_core.so"
# Debug symbols are dead weight on a phone (about 2 MB); the dynamic symbol table the check below reads stays.
"$bin/llvm-strip" "$stage/libsyncr_core.so"
"$bin/llvm-readelf" -h -l -d -n "$stage/libsyncr_core.so" > "$stage/elf.log"
"$bin/llvm-nm" -D --defined-only "$stage/libsyncr_core.so" > "$stage/symbols.log"
# Validate the architecture and every generated native symbol before publication.
python3 - "$stage" <<'PY'
import pathlib, re, sys
stage = pathlib.Path(sys.argv[1])
elf = (stage / 'elf.log').read_text()
assert 'AArch64' in elf and 'DYN' in elf
symbols = {line.split()[-1] for line in (stage / 'symbols.log').read_text().splitlines() if line.split()}
kotlin = pathlib.Path('generated/kotlin/dev/syncr/core/syncr_core.kt').read_text()
required = set(re.findall(r'external fun (\w+)\(', kotlin))
assert required and not (required - symbols), sorted(required - symbols)
print(f'PASS: Android AArch64 ELF and all {len(required)} generated native symbols')
PY
mkdir -p "generated/jniLibs/$abi"
mv "$stage/libsyncr_core.so" "generated/jniLibs/$abi/libsyncr_core.so"
mv "$stage/elf.log" generated/android-elf.log
mv "$stage/symbols.log" generated/android-symbols.log
file "generated/jniLibs/$abi/libsyncr_core.so"
echo "Built using NDK metadata:"
grep 'Pkg.Revision' "$ndk/source.properties"
