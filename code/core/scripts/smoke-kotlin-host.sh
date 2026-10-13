#!/usr/bin/env bash
# Reuses an installed Gradle distribution's Kotlin compiler; downloads nothing.
set -euo pipefail
cd "$(dirname "$0")/.."
java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
if [[ -z "${KOTLIN_COMPILER_LIB:-}" ]]; then
  compiler=$(find "$HOME/.gradle/wrapper/dists/gradle-9.2.1-bin" -name kotlin-compiler-embeddable-2.2.20.jar -print -quit 2>/dev/null || true)
  [[ -n "$compiler" ]] || { echo "BLOCKED: set KOTLIN_COMPILER_LIB to a Kotlin 2.2.20 compiler lib directory" >&2; exit 2; }
  KOTLIN_COMPILER_LIB="$(dirname "$compiler")"
fi
lib="$KOTLIN_COMPILER_LIB"
stdlib="$lib/kotlin-stdlib-2.2.20.jar"
jna="${JNA_JAR:-$lib/plugins/jna-5.17.0.jar}"
annotations="$lib/annotations-24.0.1.jar"
for jar in "$lib/kotlin-compiler-embeddable-2.2.20.jar" "$stdlib" "$jna" "$annotations"; do
  [[ -f "$jar" ]] || { echo "BLOCKED: missing local dependency $jar" >&2; exit 2; }
done
mkdir -p examples/kotlin/build
"$java" -cp "$lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -jvm-target 17 -no-stdlib -no-reflect \
  -classpath "$stdlib:$jna:$annotations" \
  "${BINDINGS_DIR:-generated/kotlin}/dev/syncr/core/syncr_core.kt" examples/kotlin/Consumer.kt \
  -d examples/kotlin/build/consumer.jar
"$java" -Djna.library.path="${HOST_LIBRARY_DIR:-$PWD/generated/host}" \
  -cp "examples/kotlin/build/consumer.jar:$stdlib:$jna" dev.syncr.example.ConsumerKt
