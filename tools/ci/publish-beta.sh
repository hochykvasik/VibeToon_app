#!/usr/bin/env bash
# Run only after tests, APK signing verification, and explicit repository opt-in.
set -euo pipefail
: "${GITHUB_REPOSITORY:?}" "${GITHUB_SHA:?}" "${GH_TOKEN:?}"
apk=${1:?APK path required}
[[ -f "$apk" && "${apk##*/}" == vibetoon-debug.apk ]] || { echo 'Expected vibetoon-debug.apk' >&2; exit 1; }
visibility=$(gh api "repos/$GITHUB_REPOSITORY" --jq .visibility)
if [[ "$visibility" != public ]]; then
  echo 'Refusing beta publication: the repository is not public. A private release is not anonymously downloadable.' >&2
  exit 1
fi
tag="beta-$GITHUB_SHA"
# A unique immutable tag for each commit; never replace an existing release asset.
if gh release view "$tag" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
  echo "Release $tag already exists; refusing to overwrite it." >&2
  exit 1
fi
gh release create "$tag" "$apk" --repo "$GITHUB_REPOSITORY" --target "$GITHUB_SHA" --prerelease \
  --title "Vibetoon beta ${GITHUB_SHA:0:7}" \
  --notes 'Debug beta build, not a production release. Back up projects outside the app before changing signing keys.'
