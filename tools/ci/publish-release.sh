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
  # релиз создан вручную на сайте (вместе с тегом): APK прикрепляется, если его ещё нет; готовый файл не заменяется
  assets=$(gh release view "$tag" --repo "$GITHUB_REPOSITORY" --json assets --jq '.assets[].name')
  if grep -qx 'vibetoon-debug.apk' <<<"$assets"; then
    echo "Release $tag already has vibetoon-debug.apk; refusing to overwrite it." >&2
    exit 1
  fi
  gh release upload "$tag" "$apk" --repo "$GITHUB_REPOSITORY"
  echo "Attached vibetoon-debug.apk to the existing release $tag."
  exit 0
fi
pre=()
[[ "$tag" == *-* ]] && pre=(--prerelease)
gh release create "$tag" "$apk" --repo "$GITHUB_REPOSITORY" --verify-tag ${pre[@]+"${pre[@]}"} \
  --title "VibeToon $tag" --notes-file "$notes"
