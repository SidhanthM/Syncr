#!/bin/sh
# Syncr's one version: VERSION at the repo root (MAJOR.MINOR.PATCH). Every platform's compared
# build number derives from it, so the Mac, Android and update feeds can never disagree.
# code/android/app/build.gradle.kts keeps the same formula; change both together.
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)

fail() { printf 'version.sh: %s\n' "$*" >&2; exit 1; }

version=$(tr -d '[:space:]' < "$root/VERSION")
case "$version" in
  # Leading zeros are invalid semver, and shell arithmetic would read them as octal.
  *[!0-9.]* | .* | *. | *..* | 0[0-9]* | *.0[0-9]*) fail "VERSION must be MAJOR.MINOR.PATCH, found '$version'" ;;
esac
IFS=. read -r major minor patch rest <<EOF
$version
EOF
[ -n "$patch" ] && [ -z "$rest" ] || fail "VERSION must be MAJOR.MINOR.PATCH, found '$version'"
# MINOR or PATCH of 1000+ would collide with the next MAJOR or MINOR (1.1000.0 == 2.0.0); a MAJOR
# below 1000 keeps the build under Android's 2100000000 cap. Gradle enforces the same limits.
[ "$major" -lt 1000 ] && [ "$minor" -lt 1000 ] && [ "$patch" -lt 1000 ] || fail "each part must be below 1000, found '$version'"
build=$((major * 1000000 + minor * 1000 + patch))
[ "$build" -ge 1 ] || fail "VERSION 0.0.0 cannot ship"

case "${1:-}" in
  version) printf '%s\n' "$version" ;;
  build) printf '%s\n' "$build" ;;
  tag) printf 'v%s\n' "$version" ;;
  check-tag)
    [ $# -eq 2 ] || fail 'usage: version.sh check-tag TAG'
    [ "$2" = "v$version" ] || fail "tag '$2' does not match VERSION (expected v$version)"
    printf 'Tag %s matches VERSION\n' "$2" ;;
  *) printf '%s\n' 'Usage: scripts/version.sh version|build|tag|check-tag TAG' >&2; exit 2 ;;
esac
