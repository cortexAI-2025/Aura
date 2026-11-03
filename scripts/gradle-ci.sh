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
  echo "--- Failed tests (JUnit XML) ---"
  find . -path '*build/test-results/*' -name '*.xml' 2>/dev/null | xargs -r grep -l '<failure' 2>/dev/null | while read -r f; do
    echo "# $f"
    grep -E '<testcase|<failure' "$f" | grep -B1 '<failure' | head -40
    grep -A6 '<failure' "$f" | head -30
  done
  echo "--- Lint errors (XML reports) ---"
  find . -path '*build/reports/*' -name 'lint-results-*.xml' 2>/dev/null | while read -r f; do
    echo "# $f"
    grep -B3 -A6 -E 'severity="(Error|Fatal)"' "$f" | grep -E 'id="|message="|file="|line="|severity=' | head -60
  done
  echo "#################################################"
fi
exit "$EXIT"
