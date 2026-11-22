#!/usr/bin/env bash
# Link soak (part K.8): pad ink, typing and one large note sync under randomised faults (cuts, resets, blackholes, stalls, a throttled
# and delayed path, a stopped Mac), for SECONDS seconds (default 600). Passes when both libraries converge, every finished stroke is
# in the Mac's note exactly once, the Mac is still alive and nothing panicked; prints the distribution of how long the phone took
# to notice a fault and how long to be welcomed again once the path was back. Loopback, a fault-injecting proxy, a temp library:
# never ~/Documents/Syncr, a phone, or a Mac app it did not start. The 90 s version runs inside join-test.sh.
#
#   code/shared/link-v0/soak.sh              ten minutes
#   code/shared/link-v0/soak.sh 90           a minute and a half
#   KEEP=1 code/shared/link-v0/soak.sh 60    keep the temp folder (the Mac's log)
set -uo pipefail
SECONDS_TO_RUN="${1:-600}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
echo "== soak for ${SECONDS_TO_RUN}s (load $(sysctl -n vm.loadavg))"
exec bash "$HERE/fault-table.sh" --only soak --soak "$SECONDS_TO_RUN"
