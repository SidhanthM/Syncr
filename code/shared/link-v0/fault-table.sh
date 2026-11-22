#!/usr/bin/env bash
# Link faults (part K): the phone's real link client against the real Mac app through a fault-injecting TCP proxy on
# loopback. Prints the fault table of K.0 (time to notice, time to be welcomed again, what was lost) and the K.1 to K.5
# checks. Starts, kills and restarts its own Mac app on a temp library and a free port; never touches ~/Documents/Syncr,
# a phone, or an app it did not start.
#
#   code/shared/link-v0/fault-table.sh                       the table
#   code/shared/link-v0/fault-table.sh --only outage,cut     only the scenarios whose names start so
#   code/shared/link-v0/fault-table.sh --measure-only         print the rows without holding them to the contract (the "before" table)
#   code/shared/link-v0/fault-table.sh --only stroke-stages  K.4 checks (see FaultTest.kt for the names)
#   MAC_BIN=/path/to/SyncrMac NO_MAC_BUILD=1 ...            run another build of the Mac app (for a "before" table)
#   KEEP=1 ...                                               keep the temp folder (the Mac's log) and print its path
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
MAC="$ROOT/code/mac"
ANDROID="$ROOT/code/android"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
ONLY=""; SOAK=""; CUTS=""; CHECK=1
while [[ $# -gt 0 ]]; do
  case "$1" in
    --only) ONLY="$2"; shift 2 ;;
    --soak) SOAK="$2"; shift 2 ;;
    --cuts) CUTS="$2"; shift 2 ;;
    --measure-only) CHECK=0; shift ;;
    *) echo "unknown option $1"; exit 2 ;;
  esac
done

S="$(mktemp -d "${TMPDIR:-/tmp}/syncr-fault.XXXXXX")"
cleanup() { if [[ "${KEEP:-0}" == 1 ]]; then echo "kept: $S"; else rm -rf "$S"; fi; }
trap cleanup EXIT

echo "== build (load $(sysctl -n vm.loadavg))"
if [[ "${NO_MAC_BUILD:-0}" != 1 ]]; then
  (cd "$MAC" && swift build 2>&1 | grep -E "error:|Build complete") || { echo "FAIL mac build"; exit 1; }
fi
(cd "$ANDROID" && ./gradlew -q --console=plain :app:compileDebugUnitTestKotlin >"$S/gradle-build.log" 2>&1) || { cat "$S/gradle-build.log"; echo "FAIL android build"; exit 1; }

PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')"
mkdir -p "$S/maclib" "$S/work"
args=("127.0.0.1:$PORT" --faults --mac-bin "${MAC_BIN:-$MAC/.build/debug/SyncrMac}" --mac-library "$S/maclib" --work "$S/work")
[[ -n "$ONLY" ]] && args+=(--only "$ONLY")
[[ -n "$SOAK" ]] && args+=(--soak "$SOAK")
[[ -n "$CUTS" ]] && args+=(--cuts "$CUTS")
[[ "$CHECK" == 0 ]] && args+=(--no-check)
echo "== scenarios (Mac port $PORT)"
(cd "$ANDROID" && ./gradlew -q --console=plain :app:linkProbe -x :app:testDebugUnitTest --args="${args[*]}") >"$S/probe.log" 2>&1
status=$?
grep -E "^FAULT " "$S/probe.log"
grep -vE "^(FAULT |PROBE|$)" "$S/probe.log" | head -20
nfail="$(grep -c '^FAULT FAIL' "$S/probe.log")"
if [[ "$status" == 0 && "$nfail" == 0 ]]; then echo "FAULT-TABLE GREEN"; else echo "FAULT-TABLE RED (exit $status, $nfail failing line(s)); KEEP=1 keeps the logs"; exit 1; fi
