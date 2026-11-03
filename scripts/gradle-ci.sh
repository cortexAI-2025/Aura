#!/usr/bin/env bash
# Run Gradle in CI and print a compact failure summary (Kotlin errors + failed tasks)
# so the root cause is visible without digging through stack traces.
set -o pipefail
OUT="${GRADLE_CI_OUT:-build-output.txt}"
./gradlew "$@" --no-daemon --console=plain --stacktrace --continue 2>&1 | tee "$OUT"
EXIT=${PIPESTATUS[0]}
if [ "$EXIT" -ne 0 ]; then
  echo
  echo "################ FAILURE SUMMARY ################"
  echo "--- Failed tasks ---"
  grep -E "^> Task .* FAILED|Execution failed for task" "$OUT" | sort -u
  echo "--- Compiler / KSP / AAPT errors ---"
  grep -E "^e: |^w: .*deprecat|error: |\[ksp\]|AAPT|Unresolved reference|cannot find symbol" "$OUT" | sort -u | head -60
  echo "--- What went wrong ---"
  grep -A6 "^\* What went wrong:" "$OUT" | head -80
  echo "#################################################"
fi
exit "$EXIT"
