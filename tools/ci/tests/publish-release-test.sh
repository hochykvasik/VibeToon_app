#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../../.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
cat > "$tmp/gh" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$CALLS"
if [[ $1 == api ]]; then printf '%s\n' "$COMPARE"; exit 0; fi
if [[ $1 == release && $2 == view && "$*" == *--json* ]]; then printf '%s\n' "${ASSETS:-}"; exit 0; fi
if [[ $1 == release && $2 == view ]]; then exit "${EXISTS:-1}"; fi
if [[ $1 == release && $2 == upload ]]; then exit 0; fi
if [[ $1 == release && $2 == create ]]; then exit 0; fi
exit 2
MOCK
chmod +x "$tmp/gh"
touch "$tmp/vibetoon-debug.apk"
printf 'notes\n' > "$tmp/notes.md"
touch "$tmp/calls"
run() { bash "$root/tools/ci/publish-release.sh" "$tmp/vibetoon-debug.apk" "$tmp/notes.md"; }
export PATH="$tmp:$PATH" CALLS="$tmp/calls" GH_TOKEN=test GITHUB_REPOSITORY=owner/repo GITHUB_SHA=1234567890abcdef COMPARE=identical EXISTS=1
# неверный тег
export GITHUB_REF_NAME=release-1
if run; then echo 'Bad tag was accepted' >&2; exit 1; fi
! grep -q 'release create' "$CALLS"
# коммит не в main
export GITHUB_REF_NAME=v0.1.0-beta COMPARE=ahead
if run; then echo 'Commit outside main was released' >&2; exit 1; fi
! grep -q 'release create' "$CALLS"
# пре-релиз по тегу с суффиксом
export COMPARE=identical
run
grep -q 'release create v0.1.0-beta .*--verify-tag --prerelease' "$CALLS"
grep -q 'compare/main...1234567890abcdef' "$CALLS"
# обычный релиз без суффикса - не пре-релиз
: > "$CALLS"
export GITHUB_REF_NAME=v1.0.0 COMPARE=behind
run
grep -q 'release create v1.0.0 ' "$CALLS"
! grep -q -- '--prerelease' "$CALLS"
# релиз создан вручную без APK - APK прикрепляется, новый релиз не создаётся
: > "$CALLS"
export EXISTS=0 ASSETS=''
run
grep -q 'release upload v1.0.0 ' "$CALLS"
! grep -q 'release create' "$CALLS"
# в релизе уже есть APK - не перезаписывается
: > "$CALLS"
export ASSETS=$'notes.txt\nvibetoon-debug.apk'
if run; then echo 'Existing APK was overwritten' >&2; exit 1; fi
! grep -q 'release create' "$CALLS"
! grep -q 'release upload' "$CALLS"
echo 'Release publishing guard tests passed'
