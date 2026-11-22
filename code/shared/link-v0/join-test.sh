#!/usr/bin/env bash
# Wi-Fi link v0, joined test: the phone's real link client (a plain JVM process) against the real Mac app, over loopback.
# Starts the Mac app on a temp library and a free port, runs the scripted phone scenarios (JoinTest.kt), checks both sides'
# logs, cleans up, and exits non-zero on any failure. Never touches ~/Documents/Syncr, a phone, or the repo fixtures in place.
#
#   code/shared/link-v0/join-test.sh            # builds both sides if needed, then runs once
#   KEEP=1 code/shared/link-v0/join-test.sh     # keep the temp folder (logs) and print its path
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
MAC="$ROOT/code/mac"
ANDROID="$ROOT/code/android"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"

S="$(mktemp -d "${TMPDIR:-/tmp}/syncr-join.XXXXXX")"
MAC_PID=""
cleanup() {
  [[ -n "$MAC_PID" ]] && kill "$MAC_PID" 2>/dev/null && wait "$MAC_PID" 2>/dev/null
  if [[ "${KEEP:-0}" == 1 ]]; then echo "kept: $S"; else rm -rf "$S"; fi
}
trap cleanup EXIT

fails=0
pass() { echo "PASS $1"; }
fail() { echo "FAIL $1"; fails=$((fails + 1)); }

echo "== build"
(cd "$MAC" && swift build 2>&1 | grep -E "error:|Build complete" ) || { echo "FAIL mac build"; exit 1; }
(cd "$ANDROID" && ./gradlew -q --console=plain :app:compileDebugUnitTestKotlin >"$S/gradle-build.log" 2>&1) || { cat "$S/gradle-build.log"; echo "FAIL android build"; exit 1; }

PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')"
mkdir -p "$S/maclib"
: > "$S/control"

echo "== start the Mac app (port $PORT, library $S/maclib)"
SYNCR_MAC_LIBRARY_FOLDER="$S/maclib" SYNCR_MAC_DEBUG_REPLAY=1 SYNCR_MAC_DEBUG_CONTROL="$S/control" SYNCR_MAC_LINK_PORT="$PORT" \
  "$MAC/.build/debug/SyncrMac" >"$S/mac.log" 2>&1 &
MAC_PID=$!
for _ in $(seq 1 100); do
  grep -q "link: listening port=$PORT" "$S/mac.log" 2>/dev/null && grep -q "link: control ready" "$S/mac.log" 2>/dev/null && break
  kill -0 "$MAC_PID" 2>/dev/null || { cat "$S/mac.log"; echo "FAIL the Mac app exited at launch"; exit 1; }
  sleep 0.2
done
grep -q "link: listening port=$PORT" "$S/mac.log" || { cat "$S/mac.log"; echo "FAIL the Mac app never listened"; exit 1; }
pass "mac-start: listening on 127.0.0.1-reachable port $PORT, control file ready"

echo "== phone scenarios"
(cd "$ANDROID" && ./gradlew -q --console=plain :app:linkProbe -x :app:testDebugUnitTest \
  --args="127.0.0.1:$PORT --join --mac-log $S/mac.log --mac-control $S/control --mac-library $S/maclib") >"$S/probe.log" 2>&1
probe_status=$?
grep -E "^JOIN " "$S/probe.log"
grep -vE "^JOIN " "$S/probe.log" | grep -vE "^(PROBE|$)" | head -20
nfail="$(grep -c '^JOIN FAIL' "$S/probe.log")"
[[ "$probe_status" == 0 && "$nfail" == 0 ]] && grep -q "^JOIN done ok" "$S/probe.log" || fail "phone scenarios (exit $probe_status, $nfail failing line(s))"

echo "== Mac side"
kill -0 "$MAC_PID" 2>/dev/null && pass "mac still running after every scenario" || fail "the Mac app died during the run"
if grep -qE "protocol error|before welcome|bad offer|unknown-command|failed" "$S/mac.log"; then
  fail "Mac log has protocol or failure lines:"; grep -nE "protocol error|before welcome|bad offer|unknown-command|failed" "$S/mac.log" | head
else pass "mac log has no protocol errors"; fi
[[ -z "$(ls -A "$S/maclib/.incoming-link" 2>/dev/null)" ]] && pass "staging folder empty" || fail "staging folder not empty: $(ls -A "$S/maclib/.incoming-link")"
n=$(grep -c "^link: connected " "$S/mac.log"); echo "info: the Mac accepted $n connections"
grep -E "^link: latency" "$S/mac.log" | tail -2

# Link faults (part K): the real Mac and the phone's client through a fault-injecting proxy, on a Mac of their own (this one is done).
# A pad stroke cut at each stage, then randomised cuts, then a 90 s soak of ink, typing and a large note under random faults.
[[ -n "$MAC_PID" ]] && kill "$MAC_PID" 2>/dev/null && wait "$MAC_PID" 2>/dev/null; MAC_PID=""
if [[ "${SYNCR_JOIN_FAULTS:-1}" == 0 ]]; then echo "== link faults skipped (SYNCR_JOIN_FAULTS=0)"; else
echo "== link faults (stroke stages, live ink after a cut, ${SYNCR_JOIN_CUTS:-200} randomised cuts, ${SYNCR_JOIN_SOAK:-90} s soak)"
faults="$(NO_MAC_BUILD=1 bash "$ROOT/code/shared/link-v0/fault-table.sh" --only stroke-stages,random-cuts,soak,calm-status,wet-ink --cuts "${SYNCR_JOIN_CUTS:-200}" --soak "${SYNCR_JOIN_SOAK:-90}" 2>&1)"
fault_status=$?
grep -E "^(FAULT|FAULT-TABLE)" <<<"$faults"
if [[ "$fault_status" == 0 ]]; then pass "link faults: strokes through every cut stage, randomised cuts, soak"; else fail "link faults (exit $fault_status)"; grep -vE "^(FAULT|PROBE|WARNING|$)" <<<"$faults" | head -10; fi
fi

echo
if (( fails == 0 )); then echo "JOIN-TEST GREEN"; else echo "JOIN-TEST RED ($fails failure(s)); KEEP=1 keeps the logs"; exit 1; fi
