#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../../.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
cat > "$tmp/gh" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$CALLS"
if [[ $1 == api ]]; then printf '%s\n' "$VISIBILITY"; exit 0; fi
if [[ $1 == release && $2 == view ]]; then exit "${EXISTS:-1}"; fi
if [[ $1 == release && $2 == create ]]; then exit 0; fi
exit 2
MOCK
chmod +x "$tmp/gh"
touch "$tmp/vibetoon-debug.apk"
export PATH="$tmp:$PATH" CALLS="$tmp/calls" GH_TOKEN=test GITHUB_REPOSITORY=owner/repo GITHUB_SHA=1234567890abcdef VISIBILITY=private
if bash "$root/tools/ci/publish-beta.sh" "$tmp/vibetoon-debug.apk"; then echo 'Private repo was accepted' >&2; exit 1; fi
! grep -q 'release create' "$CALLS"
export VISIBILITY=public EXISTS=1
bash "$root/tools/ci/publish-beta.sh" "$tmp/vibetoon-debug.apk"
grep -q 'release create beta-1234567890abcdef' "$CALLS"
: > "$CALLS"
export EXISTS=0
if bash "$root/tools/ci/publish-beta.sh" "$tmp/vibetoon-debug.apk"; then echo 'Existing release was overwritten' >&2; exit 1; fi
! grep -q 'release create' "$CALLS"
echo 'Beta publishing guard tests passed'
