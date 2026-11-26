# VibeToon

Редактор 2D-анимации для Android (планшет и телефон): ключевая и покадровая анимация, кости, Mesh, липсинг, экспорт MP4/GIF. Интерфейс RU/EN.

*2D animation editor for Android: keys, frame-by-frame, bones, mesh, lip-sync, MP4/GIF export. RU/EN UI.*

## Скачать
- Релизы: вкладка **Releases** репозитория, файл `vibetoon-debug.apk`. Что нового и ограничения — в [`docs/RELEASE_NOTES_RU.md`](docs/RELEASE_NOTES_RU.md).
- Свежая сборка ветки: **Actions** → последний зелёный запуск «Android debug build» → артефакт `vibetoon-debug.apk`.

> Перед переустановкой сохраните проекты (`.tsproj`, «Экспорт проекта») и настройки («Настройки» → «Резервные копии» → «Резервная копия вручную») вне приложения. Обновляйте поверх, не удаляя старую версию.

## Сборка
- JDK 17, Android SDK 35, NDK 27.0.12077973, CMake 3.22.1.
- `./gradlew assembleDebug` — APK в `app/build/outputs/apk/debug/`.
- Тесты: `./gradlew testDebugUnitTest`. Словарь RU/EN: `python3 tools/i18n/check_pairs.py && python3 tools/i18n/gen.py`.

## Документы
- [`docs/CHANGELOG_RU.md`](docs/CHANGELOG_RU.md) — журнал изменений.
- [`docs/testing/TESTING_GUIDE_RU.md`](docs/testing/TESTING_GUIDE_RU.md) — сценарии проверки.
- [`docs/SPRINT_SETTINGS_RU.md`](docs/SPRINT_SETTINGS_RU.md) — настройки, пороги и ограничения.

## Выпуск релиза
CI запускается на ветке разработки, на `main`, на pull request в `main` и на тегах `v*`. Тег `vX.Y.Z` или `vX.Y.Z-суффикс` на коммите из `main` собирает APK, прогоняет тесты и создаёт GitHub Release с `vibetoon-debug.apk`. Описание берётся из `docs/RELEASE_NOTES_RU.md`, тег с суффиксом публикуется как pre-release. Если релиз создан вручную на сайте, CI только прикрепит к нему APK.
