#!/usr/bin/env bash
# The latency harness (remaining-v1 L.1): every measured workload, N interleaved runs each, on scratch libraries and settings,
# with the Mac app built -c release. One machine-readable summary per invocation, and a printed table.
#
#   code/shared/latency/run.sh                     # all workloads, 5 runs each
#   code/shared/latency/run.sh text pad            # some of them: text pad present pan bulk
#   RUNS=3 NOBUILD=1 KEEP=1 code/shared/latency/run.sh
#
# Workloads (what "visible" or "presented" means is in each metric's name and in the summary's notes):
#   text     typed text both ways (joined-test scenario t): keystroke to visible on the other side. Phone side "visible" is the
#            phone's library holding the text (the joined test's phone is a host JVM); Mac side is the open editor's text.
#   pad      pad ink in Stream mode (joined-test scenario L), idle and while the Mac sends a large note: phone pen event to
#            socket write, and phone frame to Mac drawable presented (clock offset from pings).
#   present  Mac presentation replay, arrival vs arrival-adaptive (present-ab.sh): frame received to drawable presented.
#   pan      Mac canvas pan over a 5,000-stroke fixture: presented-to-presented frame interval, encode and GPU time.
#   bulk     input age behind a note transfer (InputBehindBulkTest, a throttled 20 Mbit/s socket and a slow disk) and the Mac's
#            live frames behind an incoming file (SyncrMacChecks --link, debug build).
#
# Opt-in and bounded: nothing here runs in the app unless the debug flag is set; no providers, no personal library, no posted
# mouse events. Loopback only: these are not Wi-Fi numbers. Needs a logged-in GUI session (the Mac app draws on screen).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
MAC="$ROOT/code/mac"
ANDROID="$ROOT/code/android"
RUNS="${RUNS:-5}"
LOAD_MAX="${LOAD_MAX:-8}"
LOAD_WAIT="${LOAD_WAIT:-90}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
WORKLOADS=("$@")
[[ ${#WORKLOADS[@]} -gt 0 ]] || WORKLOADS=(text pad present pan bulk)
OUT="${OUT:-$ROOT/code/.latency}"
STAMP="$(date +%Y%m%d-%H%M%S)"
S="$(mktemp -d "${TMPDIR:-/tmp}/syncr-latency.XXXXXX")"
RAW="$S/raw.jsonl"
PID=""
cleanup() {
  [[ -n "$PID" ]] && kill "$PID" 2>/dev/null && wait "$PID" 2>/dev/null
  if [[ "${KEEP:-0}" == 1 ]]; then echo "kept: $S"; else rm -rf "$S"; fi
}
trap cleanup EXIT
mkdir -p "$OUT"
: > "$RAW"

load1() { sysctl -n vm.loadavg | awk '{print $2}'; }
# Waits up to LOAD_WAIT seconds for the 1-minute load to drop under LOAD_MAX; returns the load at the start of the run.
settle() {
  local waited=0
  while awk -v l="$(load1)" -v m="$LOAD_MAX" 'BEGIN { exit !(l > m) }' && (( waited < LOAD_WAIT )); do sleep 5; waited=$((waited + 5)); done
  load1
}
record() { printf '%s\n' "$1" >> "$RAW"; }   # one JSON object per run of a workload

free="$(df -g /System/Volumes/Data | awk 'NR == 2 { print $4 }')"
[[ "$free" -ge 4 ]] || { echo "only ${free} GB free; the release build needs at least 4 GB"; exit 1; }
BIN="$MAC/.build/release/SyncrMac"
if [[ "${NOBUILD:-0}" != 1 ]]; then
  echo "== release build (SyncrMac)"
  (cd "$MAC" && swift build -c release --product SyncrMac 2>&1 | grep -E "error:|Build complete" | tail -3) || { echo "FAIL release build"; exit 1; }
fi
[[ -x "$BIN" ]] || { echo "FAIL no release binary at $BIN"; exit 1; }

# ---- the workloads (each runs once per call; $1 is the run number)

run_text_pad() {  # text and pad share one joined-test launch
  local r="$1" load; load="$(settle)"
  local log="$S/join-$r.log"
  SYNCR_JOIN_ONLY=latency SYNCR_MAC_BIN="$BIN" SYNCR_MAC_LINK_LOOPBACK=1 KEEP=0 bash "$ROOT/code/shared/link-v0/join-test.sh" > "$log" 2>&1
  local status=$?
  python3 - "$log" "$r" "$load" "$status" >> "$RAW" <<'PY'
import json, re, sys
log, run, load, status = sys.argv[1], int(sys.argv[2]), float(sys.argv[3]), int(sys.argv[4])
metrics, fails, fallback = {}, [], 0
for line in open(log, errors='replace'):
    m = re.search(r'JOIN fallback \S+ (\d+)', line)
    if m: fallback += int(m.group(1))
    m = re.search(r'JOIN metric (\S+) p50=([\d.-]+|null) p95=([\d.-]+|null) max=([\d.-]+|null) ms n=(\d+|null)', line)
    if m and m.group(2) != 'null':
        metrics[m.group(1)] = dict(p50=float(m.group(2)), p95=float(m.group(3)), max=float(m.group(4)), n=int(m.group(5)))
    if 'JOIN FAIL' in line: fails.append(line.strip()[:300])
for w in ('text', 'pad'):
    print(json.dumps(dict(workload=w, run=run, load=load, exit=status, fails=fails, timestamp_fallbacks=fallback if w == 'pad' else None,
                          metrics={k: v for k, v in metrics.items() if k.startswith(w + '.')})))
PY
}

run_present() {
  local r="$1" load; load="$(settle)"
  local log="$S/present-$r.log"
  RUNS=1 NOBUILD=1 KEEP=1 MODES="${PRESENT_MODES:-arrival arrival-adaptive}" bash "$ROOT/code/shared/latency/present-ab.sh" > "$log" 2>&1
  local dir; dir="$(sed -n 's/^kept: //p' "$log" | tail -1)"
  python3 - "$dir" "$r" "$load" "$log" >> "$RAW" <<'PY'
import json, glob, re, sys
d, run, load, log = sys.argv[1], int(sys.argv[2]), float(sys.argv[3]), sys.argv[4]
metrics, fallback = {}, 0
try: summary = json.load(open(d + '/summary.json'))
except Exception: summary = {'rows': []}
for row in summary['rows']:
    _, mode, kind, p50, p95, mx, frames = row
    metrics[f'present.{mode}.{kind}'] = dict(p50=p50, p95=p95, max=mx, n=frames)
for app in glob.glob(d + '/run-*/app.log'):
    for l in open(app, errors='replace'):
        m = re.search(r'presented \(handler time\) p50.*\(n=(\d+)\)', l)
        if m: fallback += int(m.group(1))
print(json.dumps(dict(workload='present', run=run, load=load, metrics=metrics, timestamp_fallbacks=fallback,
                      pan_sync=[p[2] for p in summary.get('pans', [])])))
PY
  rm -rf "$dir"
}

run_pan() {
  local r="$1" load; load="$(settle)"
  local d="$S/pan-$r"; mkdir -p "$d/library/.mac" "$d/settings"
  echo '{"readingModel":"","askingModel":"","tokenLimit":0,"requestLimit":0}' > "$d/library/.mac/companion.json"
  : > "$d/control"
  SYNCR_MAC_LIBRARY_FOLDER="$d/library" SYNCR_MAC_SETTINGS_FOLDER="$d/settings" SYNCR_MAC_DEBUG_REPLAY=1 \
    SYNCR_MAC_DEBUG_CONTROL="$d/control" SYNCR_MAC_LINK_LOOPBACK=1 "$BIN" > "$d/app.log" 2>&1 &
  PID=$!
  wait_for() { local re="$1" t="${2:-60}"; for ((i = 0; i < t * 5; i++)); do grep -qE "$re" "$d/app.log" && return 0; kill -0 "$PID" 2>/dev/null || return 1; sleep 0.2; done; return 1; }
  local ok=1 id=""
  wait_for "link: control ready" || ok=0
  if ((ok)); then echo "newcanvas Pan fixture" >> "$d/control"; wait_for "ack newcanvas " || ok=0; fi
  id="$(sed -n 's/^link: ack newcanvas //p' "$d/app.log" | tail -1)"
  if ((ok)); then echo "sync-fixture $id ${PAN_STROKES:-5000}" >> "$d/control"; wait_for "ack sync-fixture $id" 180 || ok=0; fi
  if ((ok)); then
    # Reopen the canvas so it loads with every stroke, and wait until the renderer has them all.
    echo "sync-close-tabs" >> "$d/control"; wait_for "ack sync-close-tabs" || ok=0
    echo "open $id" >> "$d/control"; echo "front" >> "$d/control"
    for ((i = 0; i < 60; i++)); do
      echo "report" >> "$d/control"; sleep 1
      grep -qE "^link: report shown=$id .*loading=false strokes=${PAN_STROKES:-5000} " "$d/app.log" && break
    done
    grep -qE "^link: report shown=$id .*strokes=${PAN_STROKES:-5000} " "$d/app.log" || ok=0
    echo "fit" >> "$d/control"; sleep 2
    echo "pan-bench ${PAN_SECONDS:-4}" >> "$d/control"; wait_for "^link: pan-bench " 60 || ok=0
  fi
  echo "quit" >> "$d/control"
  for _ in 1 2 3 4 5 6 7 8 9 10; do kill -0 "$PID" 2>/dev/null || break; sleep 0.5; done
  kill "$PID" 2>/dev/null; wait "$PID" 2>/dev/null; PID=""
  python3 - "$d/app.log" "$r" "$load" "$ok" >> "$RAW" <<'PY'
import json, re, sys
log, run, load, ok = sys.argv[1], int(sys.argv[2]), float(sys.argv[3]), sys.argv[4] == '1'
line = next((l for l in open(log, errors='replace') if l.startswith('link: pan-bench ')), '')
m = re.search(r'strokes=(\d+) frames=(\d+) interval p50 ([\d.-]+) p95 ([\d.-]+) max ([\d.-]+) ms; encode p50 ([\d.-]+) p95 ([\d.-]+) ms; gpu p50 ([\d.-]+) p95 ([\d.-]+) max ([\d.-]+) ms; over-16.7ms=(\d+) presented-fallback=(\d+)', line)
metrics, extra = {}, {}
if m:
    g = [float(x) for x in m.groups()]
    metrics['pan.frame-interval'] = dict(p50=g[2], p95=g[3], max=g[4], n=int(g[1]))
    metrics['pan.encode'] = dict(p50=g[5], p95=g[6], max=None, n=int(g[1]))
    metrics['pan.gpu'] = dict(p50=g[7], p95=g[8], max=g[9], n=int(g[1]))
    extra = dict(strokes=int(g[0]), over_16_7ms=int(g[10]))
print(json.dumps(dict(workload='pan', run=run, load=load, ok=ok and bool(m), metrics=metrics, timestamp_fallbacks=int(m.group(12)) if m else None, **extra)))
PY
}

run_bulk() {
  local r="$1" load; load="$(settle)"
  local log="$S/bulk-$r.log"
  (cd "$ANDROID" && SYNCR_BULK_TEST=1 SYNCR_BULK_REPORT_ONLY=1 ./gradlew -q --console=plain testDebugUnitTest --rerun \
    --tests 'dev.syncr.prototype.link.InputBehindBulkTest' -i) > "$log" 2>&1
  local checks="$MAC/.build/debug/SyncrMacChecks"
  [[ -x "$checks" ]] && "$checks" --link >> "$log" 2>&1
  python3 - "$log" "$r" "$load" >> "$RAW" <<'PY'
import json, re, sys
log, run, load = sys.argv[1], int(sys.argv[2]), float(sys.argv[3])
metrics, delivered = {}, {}
for line in open(log, errors='replace'):
    m = re.search(r'INPUT-AGE workload=(\S+) input=(\S+) p50=([\d.-]+) p95=([\d.-]+) max=([\d.-]+) ms delivered=(\d+)/(\d+)', line)
    if m:
        metrics[f'bulk.phone.{m.group(1)}.{m.group(2)}'] = dict(p50=float(m.group(3)), p95=float(m.group(4)), max=float(m.group(5)), n=int(m.group(6)))
        delivered[f'{m.group(1)}.{m.group(2)}'] = f'{m.group(6)}/{m.group(7)}'
    m = re.search(r'on the connection queue p50 ([\d.-]+) p95 ([\d.-]+) max ([\d.-]+) ms \(n=(\d+)\); on the files queue p50 ([\d.-]+) p95 ([\d.-]+) max ([\d.-]+) ms \(n=(\d+)\)', line)
    if m:
        g = m.groups()
        metrics['bulk.mac.slow-disk.on-connection-queue'] = dict(p50=float(g[0]), p95=float(g[1]), max=float(g[2]), n=int(g[3]))
        metrics['bulk.mac.slow-disk.on-files-queue'] = dict(p50=float(g[4]), p95=float(g[5]), max=float(g[6]), n=int(g[7]))
print(json.dumps(dict(workload='bulk', run=run, load=load, metrics=metrics, delivered=delivered)))
PY
}

# ---- interleaved runs: run 1 of every workload, then run 2 of every workload, ...
for ((r = 1; r <= RUNS; r++)); do
  did_join=0
  for w in "${WORKLOADS[@]}"; do
    echo "== run $r: $w (load $(load1))"
    case "$w" in
      text|pad) ((did_join)) || run_text_pad "$r"; did_join=1 ;;
      present) run_present "$r" ;;
      pan) run_pan "$r" ;;
      bulk) run_bulk "$r" ;;
      *) echo "unknown workload $w"; exit 1 ;;
    esac
  done
done

SUMMARY="$OUT/latency-$STAMP.json"
python3 - "$RAW" "$SUMMARY" "$ROOT" "$RUNS" "${WORKLOADS[*]}" <<'PY'
import json, statistics, subprocess, sys, platform, datetime
raw, out, root, runs, wanted = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]), sys.argv[5].split()
rows = [json.loads(l) for l in open(raw) if l.strip()]
rows = [r for r in rows if r['workload'] in wanted]
def git(*a): return subprocess.run(['git', '-C', root, *a], capture_output=True, text=True).stdout.strip()
series = {}
for r in rows:
    for name, m in r.get('metrics', {}).items():
        series.setdefault(name, []).append(dict(run=r['run'], load=r['load'], **m))
dist = {}
for name, per in series.items():
    p50s = [p['p50'] for p in per if p.get('p50') is not None]; p95s = [p['p95'] for p in per if p.get('p95') is not None]
    dist[name] = dict(runs=len(per), p50_median=statistics.median(p50s) if p50s else None, p95_median=statistics.median(p95s) if p95s else None,
                      p95_min=min(p95s) if p95s else None, p95_max=max(p95s) if p95s else None, per_run_p95=p95s)
summary = dict(
    harness='code/shared/latency/run.sh', timestamp=datetime.datetime.now().isoformat(timespec='seconds'),
    commit=git('rev-parse', '--short', 'HEAD'), dirty=bool(git('status', '--porcelain', '--', 'code')),
    mac_build='release (swift build -c release --product SyncrMac)', phone='host JVM link client and host Rust core (joined test), not a device',
    host=dict(cpu=subprocess.run(['sysctl', '-n', 'machdep.cpu.brand_string'], capture_output=True, text=True).stdout.strip(), macos=platform.mac_ver()[0]),
    fixtures=dict(text='joined-test default text note (SYNCR_JOIN_TEXT_NOTE=heavy: the ~200-stroke note); 8 bursts of 15 keys 100 ms apart each way',
                  pad='5 Stream strokes of 240 samples at 240 Hz; bulk: the Mac sends a note grown by 200 strokes of 8,000 samples',
                  present='present-ab.sh synthetic 12-section note, pen/highlighter/view-relative replays',
                  pan='a canvas with 5,000 strokes of 60 samples over 8,000 x 6,000 units, panned 4 s at 120 moves a second',
                  bulk='InputBehindBulkTest: 20 Mbit/s socket, 15 ms a chunk disk; Mac check: 15 ms a chunk disk'),
    loopback_only=True, load_note='load is the 1-minute load average when each run started; runs above 8 are flagged',
    runs=rows, distribution=dist)
json.dump(summary, open(out, 'w'), indent=1)
print(f"\n{'metric':<52} {'runs':>4} {'p50 med':>8} {'p95 med':>8} {'p95 min':>8} {'p95 max':>8}")
for name in sorted(dist):
    d = dist[name]
    f = lambda v: f'{v:8.1f}' if isinstance(v, (int, float)) else f'{"-":>8}'
    print(f"{name:<52} {d['runs']:>4} {f(d['p50_median'])} {f(d['p95_median'])} {f(d['p95_min'])} {f(d['p95_max'])}")
# Latency-v1 T.6 (remaining-v1 L.4): typed text p95 within 300 ms while typing and 250 ms after the last key, in at least 4 of 5 runs.
targets = {'text.mac-to-phone.every': 300, 'text.mac-to-phone.last': 250, 'text.phone-to-mac.every': 300, 'text.phone-to-mac.last': 250}
met = {}
for name, limit in targets.items():
    per = series.get(name, [])
    if per:
        met[name] = dict(target_p95=limit, runs=len(per), met=sum(1 for p in per if p['p95'] <= limit))
        print(f"T.6 {name}: p95 <= {limit} ms in {met[name]['met']} of {len(per)} runs")
summary['targets'] = met
json.dump(summary, open(out, 'w'), indent=1)
loads = [r['load'] for r in rows]
if loads: print(f"\nload at run start: min {min(loads):.1f}, max {max(loads):.1f}; {sum(1 for l in loads if l > 8)} of {len(loads)} runs started above 8")
fb = [r.get('timestamp_fallbacks') for r in rows if r.get('timestamp_fallbacks') is not None]
if fb: print(f"frames timed at the presented handler instead of drawable presentedTime (display asleep, locked or window hidden): {sum(fb)}")
print(f"summary: {out}")
PY
