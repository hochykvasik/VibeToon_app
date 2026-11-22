#!/usr/bin/env bash
# Запуск Gradle с выжимкой ошибок в аннотацию (ошибки компиляции Kotlin/C++ и упавшие тесты видны прямо в сводке run).
set -uo pipefail
LOG="${RUNNER_TEMP:-/tmp}/gradle-$(echo "$*" | tr -c 'a-zA-Z0-9' '_' | cut -c1-40).log"
./gradlew --no-daemon --stacktrace "$@" 2>&1 | tee "$LOG"
status=${PIPESTATUS[0]}
if [ "$status" != 0 ]; then
  msg="$(grep -E '^e: |error:|FAILED|What went wrong|Exception|expected|> Task .* FAILED|^  [A-Za-z].*Test >' "$LOG" | grep -v '^\s*at ' | head -60 | sed 's/%/%25/g' | awk '{printf "%s%%0A", $0}')"
  echo "::error title=Gradle $*::${msg}"
fi
exit "$status"
