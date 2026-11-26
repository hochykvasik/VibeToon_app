#!/usr/bin/env bash
# Спринт 58: GitHub Release по тегу vX.Y.Z[-суффикс]. Шаг CI запускается только для тегов и только после
# тестов, сборки и проверки подписи APK. Тег должен указывать на коммит, который уже есть в main.
set -euo pipefail
: "${GITHUB_REPOSITORY:?}" "${GITHUB_REF_NAME:?}" "${GITHUB_SHA:?}" "${GH_TOKEN:?}"
apk=${1:?APK path required}
notes=${2:-docs/RELEASE_NOTES_RU.md}
[[ -f "$apk" && "${apk##*/}" == vibetoon-debug.apk ]] || { echo 'Expected vibetoon-debug.apk' >&2; exit 1; }
tag=$GITHUB_REF_NAME
if [[ ! "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]]; then
  echo "Refusing release: tag '$tag' is not vX.Y.Z or vX.Y.Z-suffix." >&2
  exit 1
fi
[[ -f "$notes" ]] || { echo "Release notes '$notes' not found." >&2; exit 1; }
# коммит тега должен входить в main (identical - это и есть верх main, behind - более ранний коммит main)
status=$(gh api "repos/$GITHUB_REPOSITORY/compare/main...$GITHUB_SHA" --jq .status)
if [[ "$status" != identical && "$status" != behind ]]; then
  echo "Refusing release: $GITHUB_SHA is not on main (compare status: $status). Merge into main first." >&2
  exit 1
fi
if gh release view "$tag" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
  echo "Release $tag already exists; refusing to overwrite it." >&2
  exit 1
fi
pre=()
[[ "$tag" == *-* ]] && pre=(--prerelease)
gh release create "$tag" "$apk" --repo "$GITHUB_REPOSITORY" --verify-tag ${pre[@]+"${pre[@]}"} \
  --title "VibeToon $tag" --notes-file "$notes"
