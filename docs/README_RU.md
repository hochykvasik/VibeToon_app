# VibeToon: документы

Папка `C:\Users\Stacy\Downloads\VibeToon\` (в репозитории `docs/`):

```
VibeToon/
  README_RU.md          <- это оглавление
  CHANGELOG_RU.md       <- что сделано и в каком коммите, читать первым
  SPRINT_SETTINGS_RU.md <- 6.8/6.9: что работает, что «В разработке»
  architecture/         <- как всё устроено внутри
      DEFORMERS_ARCHITECTURE.md   сетка, пины, Path, ARAP, кисть по вершинам, эффекты, кривые
      BONES_ARCHITECTURE.md       кости и скининг
      PAINTING_ARCHITECTURE.md    рисование: кисть, ластик, Undo, сохранение
      VIEW_PIVOT_ARCHITECTURE.md  поворот/отражение вида, опорные точки, ластик по контурам
  testing/              <- что и как проверять в приложении
      TESTING_GUIDE_RU.md
  commits/              <- по файлу на каждый коммит
      INDEX.md                    список всех коммитов
  apk/                  <- собранные APK (только в папке на ПК)
```

## Правило
Каждый коммит с функцией или заметным исправлением: строка в `CHANGELOG_RU.md`, шаги проверки в `testing/`, устройство в `architecture/`, файл в `commits/` (`python tools/gen_commits.py`). При сборке APK (`gradlew exportDebugApk`) APK и все документы копируются в папку на ПК.

## CI (GitHub Actions)
`.github/workflows/android.yml`: на каждый push в `main` и `arena/**`, на PR и вручную - юнит-тесты (`testDebugUnitTest`), нативные тесты (`tools/ci/native_tests.sh`, g++), сборка `vibetoon-debug.apk` (артефакт run), проверка `applicationId=com.vibetoon` и отпечатка сертификата (`tools/ci/check_apk.sh`).
Чтобы CI подписывал APK вашим ключом (отпечаток `02e8a1d4…f3b88003`, установка поверх старой сборки), добавьте секреты репозитория:
`DEBUG_KEYSTORE_BASE64` (`base64 -w0 debug.keystore`), при нестандартных паролях - `DEBUG_KEYSTORE_PASSWORD`, `DEBUG_KEY_ALIAS`, `DEBUG_KEY_PASSWORD`; полный SHA-256 можно задать переменной `VT_DEBUG_CERT_SHA256`. С секретом несовпадение отпечатка роняет сборку, без секрета - только предупреждение.
