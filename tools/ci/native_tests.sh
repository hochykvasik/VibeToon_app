#!/usr/bin/env bash
# Нативные тесты на хосте (без Android и GL): математика вида холста (поворот, отражение, экспорт без поворота).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="${RUNNER_TEMP:-/tmp}/vt_native_tests"
mkdir -p "$OUT"
for src in "$ROOT"/app/src/test/cpp/*Test.cpp; do
  name="$(basename "$src" .cpp)"
  g++ -std=c++17 -O1 -Wall -Wextra -Werror -I "$ROOT/app/src/main/cpp" "$src" -o "$OUT/$name"
  "$OUT/$name"
done
