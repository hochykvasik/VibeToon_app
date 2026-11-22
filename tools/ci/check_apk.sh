#!/usr/bin/env bash
# Проверка debug-APK: подпись валидна, applicationId = com.vibetoon, отпечаток сертификата.
# Ключ из секрета -> отпечаток обязан совпасть (иначе ошибка). Без секрета -> только предупреждение.
set -euo pipefail
APK="$1"
BT="${ANDROID_HOME:-$ANDROID_SDK_ROOT}/build-tools/${BUILD_TOOLS:-35.0.0}"
[ -f "$APK" ] || { echo "::error::Нет $APK"; exit 1; }
echo "APK: $APK ($(du -h "$APK" | cut -f1))"

PKG="$("$BT/aapt2" dump packagename "$APK" 2>/dev/null || "$BT/aapt" dump badging "$APK" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
echo "applicationId: $PKG"
[ "$PKG" = "com.vibetoon" ] || { echo "::error::applicationId $PKG, ожидался com.vibetoon"; exit 1; }

CERTS="$("$BT/apksigner" verify --print-certs "$APK")"
echo "$CERTS"
SHA256="$(echo "$CERTS" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | tr -d ':' | tr 'A-F' 'a-f')"
SHA1="$(echo "$CERTS" | sed -n 's/^Signer #1 certificate SHA-1 digest: //p' | tr -d ':' | tr 'A-F' 'a-f')"
echo "SHA-256: $SHA256"
echo "SHA-1:   $SHA1"
echo "### Подпись vibetoon-debug.apk" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
echo "- SHA-256: \`$SHA256\`" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"

match=0
if [ -n "${EXPECTED_CERT_SHA256:-}" ]; then
  exp="$(echo "$EXPECTED_CERT_SHA256" | tr -d ': ' | tr 'A-F' 'a-f')"
  [ "$SHA256" = "$exp" ] && match=1
else
  for d in "$SHA256" "$SHA1"; do
    case "$d" in "${EXPECTED_CERT_PREFIX}"*"${EXPECTED_CERT_SUFFIX}") match=1 ;; esac
  done
fi

if [ "$match" = 1 ]; then
  echo "Отпечаток совпал (${EXPECTED_CERT_PREFIX}…${EXPECTED_CERT_SUFFIX})"
  echo "- Отпечаток совпал с ключом проекта ✅" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
elif [ "${VT_KEY_FROM_SECRET:-0}" = 1 ]; then
  echo "::error::Отпечаток $SHA256 не совпал с ${EXPECTED_CERT_PREFIX}…${EXPECTED_CERT_SUFFIX}: в секрете не тот keystore"
  exit 1
else
  echo "::warning title=Fingerprint::Отпечаток $SHA256 ≠ ${EXPECTED_CERT_PREFIX}…${EXPECTED_CERT_SUFFIX}: добавьте секрет DEBUG_KEYSTORE_BASE64 (base64 вашего debug.keystore)"
  echo "- ⚠️ Временный ключ раннера: добавьте секрет DEBUG_KEYSTORE_BASE64" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
fi
