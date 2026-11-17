#!/usr/bin/env bash
# Mac presentation A/B on a RELEASE build: interleaved launches per presentation mode, each on a scratch library and
# settings folder, replaying synthetic pen / highlighter / view-relative ink and printing the software latency
# (frame received to drawable presented) per run and the median of runs.
#
#   code/shared/latency/present-ab.sh                      # arrival vs arrival-transaction, 3 runs each
#   RUNS=5 MODES="arrival arrival-adaptive" code/shared/latency/present-ab.sh
#   NOBUILD=1 ...                                          # reuse the existing release build
#   KEEP=1 ...                                             # keep the temp folder (logs, summary.json) and print it
#
# Software side only: excludes the pen, Wi-Fi and display scan-out. Never touches ~/Documents/Syncr: every launch gets
# its own library and settings folder, and only the PID this script started is ever stopped. Needs a logged-in GUI session.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
MAC="$ROOT/code/mac"
RUNS="${RUNS:-3}"
MODES="${MODES:-arrival arrival-transaction}"
TIMEOUT="${TIMEOUT:-40}"
S="$(mktemp -d "${TMPDIR:-/tmp}/syncr-present-ab.XXXXXX")"
PID=""
cleanup() {
  [[ -n "$PID" ]] && kill "$PID" 2>/dev/null && wait "$PID" 2>/dev/null
  if [[ "${KEEP:-0}" == 1 ]]; then echo "kept: $S"; else rm -rf "$S"; fi
}
trap cleanup EXIT

if [[ "${NOBUILD:-0}" != 1 ]]; then
  echo "== release build"
  df -h /System/Volumes/Data | tail -1
  (cd "$MAC" && swift build -c release --product SyncrMac 2>&1 | grep -E "error:|Compiling|Build complete" | tail -3) || { echo "FAIL build"; exit 1; }
fi
BIN="$MAC/.build/release/SyncrMac"
[[ -x "$BIN" ]] || { echo "FAIL no release binary at $BIN"; exit 1; }
echo "binary: $BIN ($(shasum "$BIN" | cut -c1-12)), host: $(sysctl -n machdep.cpu.brand_string 2>/dev/null), $(sw_vers -productVersion)"

python3 - "$S/fixture.md" <<'PY'
import sys
open(sys.argv[1], 'w').write('# Presentation latency fixture\n\n' + '\n\n'.join(
    f'## Section {i}\n\nA local, synthetic passage for testing canvas presentation. '
    'Text and ink should move together without waiting for network, storage or a language model. '
    '**Bold text**, *emphasis*, and `code` keep the layout representative.\n\n- First point\n- Second point'
    for i in range(1, 13)))
PY

# Interleave: arrival, transaction, arrival, transaction, ... so drift hits every mode alike.
n=0
for ((r = 1; r <= RUNS; r++)); do
  for mode in $MODES; do
    n=$((n + 1))
    run="$S/run-$n-$mode"
    mkdir -p "$run/library/.mac" "$run/settings"
    echo '{"readingModel":"","askingModel":"","tokenLimit":0,"requestLimit":0}' > "$run/library/.mac/companion.json"
    : > "$run/control"
    SYNCR_MAC_LIBRARY_FOLDER="$run/library" SYNCR_MAC_SETTINGS_FOLDER="$run/settings" \
      SYNCR_MAC_DEBUG_REPLAY=1 SYNCR_MAC_DEBUG_AUTOREPLAY=1 SYNCR_MAC_DEBUG_DOCUMENT="$S/fixture.md" \
      SYNCR_MAC_DEBUG_CONTROL="$run/control" SYNCR_MAC_LINK_LOOPBACK=1 SYNCR_MAC_PRESENT_MODE="$mode" \
      "$BIN" >"$run/app.log" 2>&1 &
    PID=$!
    deadline=$((SECONDS + TIMEOUT))
    while ((SECONDS < deadline)) && kill -0 "$PID" 2>/dev/null; do
      grep -q '^pan-sync ' "$run/app.log" && break
      sleep 0.3
    done
    echo "quit" >> "$run/control"
    for _ in 1 2 3 4 5 6 7 8 9 10 11 12; do kill -0 "$PID" 2>/dev/null || break; sleep 0.5; done
    kill "$PID" 2>/dev/null; wait "$PID" 2>/dev/null; PID=""
    echo "run $n $mode: $(grep -c '^live-ink replay ' "$run/app.log") replays, $(grep '^pan-sync ' "$run/app.log" | cut -d: -f2-)"
    sleep 1
  done
done

python3 - "$S" <<'PY'
import sys, re, glob, json, statistics, os
root = sys.argv[1]
pans = []
rows = []  # (run, mode, kind, p50, p95, max, frames)
for log in sorted(glob.glob(root + '/run-*/app.log'), key=lambda p: int(re.search(r'run-(\d+)-', p).group(1))):
    m = re.search(r'run-(\d+)-(.+)/app.log', log)
    seen = 0
    for line in open(log, errors='replace'):
        pan = re.match(r'pan-sync \[(\S+)\]: (.*)', line)
        if pan: pans.append((int(m.group(1)), m.group(2), pan.group(2)))
        h = re.match(r'live-ink replay (\S+) \[(\S+)\]: (\S+); p50 ([\d.]+) / p95 ([\d.]+) / max ([\d.]+) ms over (\d+) frames', line)
        if h and h.group(3) == 'ok':
            seen += 1
            # The fourth replay is the pen again while the view pans programmatically.
            rows.append((int(m.group(1)), m.group(2), h.group(1) + ('-panning' if seen == 4 else ''), float(h.group(4)), float(h.group(5)), float(h.group(6)), int(h.group(7))))
print('\nper run (ms, received to presented):')
print(f'{"run":>3} {"mode":<20} {"replay":<14} {"p50":>6} {"p95":>6} {"max":>6} {"frames":>6}')
for r in rows: print(f'{r[0]:>3} {r[1]:<20} {r[2]:<14} {r[3]:>6.1f} {r[4]:>6.1f} {r[5]:>6.1f} {r[6]:>6}')
print('\nmedian of runs:')
print(f'{"mode":<20} {"replay":<14} {"p50":>6} {"p95":>6} {"runs":>4}')
summary = []
for mode in dict.fromkeys(r[1] for r in rows):
    for kind in dict.fromkeys(r[2] for r in rows):
        sel = [r for r in rows if r[1] == mode and r[2] == kind]
        if not sel: continue
        p50, p95 = statistics.median(r[3] for r in sel), statistics.median(r[4] for r in sel)
        summary.append({'mode': mode, 'replay': kind, 'p50': p50, 'p95': p95, 'runs': len(sel)})
        print(f'{mode:<20} {kind:<14} {p50:>6.1f} {p95:>6.1f} {len(sel):>4}')
print('\npan check (text and ink viewport per presented frame while panning and replaying pen):')
for r in pans: print(f'  run {r[0]} {r[1]}: {r[2]}')
json.dump({'rows': rows, 'summary': summary, 'pans': pans}, open(root + '/summary.json', 'w'), indent=1)
PY
