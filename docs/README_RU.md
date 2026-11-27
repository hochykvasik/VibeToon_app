# VibeToon: документы

Папка `C:\Users\Stacy\Downloads\VibeToon\` (в репозитории `docs/`):

```
VibeToon/
  README_RU.md          <- это оглавление
  CHANGELOG_RU.md       <- что сделано и в каком коммите, читать первым
  BUILD_AND_RELEASE_RU.md <- сборка, CI и выпуск GitHub Release (перенесено из README ветки)
  RELEASE_NOTES_RU.md   <- текст GitHub Release
  architecture/         <- как всё устроено внутри
      DEFORMERS_ARCHITECTURE.md   сетка, пины, Path, ARAP, кисть по вершинам, эффекты, кривые
      BONES_ARCHITECTURE.md       кости и скининг
      PAINTING_ARCHITECTURE.md    рисование: кисть, ластик, Undo, сохранение
  testing/              <- что и как проверять в приложении
      TESTING_GUIDE_RU.md
  commits/              <- по файлу на каждый коммит
      INDEX.md                    список всех коммитов
  apk/                  <- собранные APK (только в папке на ПК)
```

## Правило
Каждый коммит с функцией или заметным исправлением: строка в `CHANGELOG_RU.md`, шаги проверки в `testing/`, устройство в `architecture/`, файл в `commits/` (`python tools/gen_commits.py`). При сборке APK (`gradlew exportDebugApk`) APK и все документы копируются в папку на ПК.
