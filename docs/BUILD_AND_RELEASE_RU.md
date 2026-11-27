# VibeToon: сборка и выпуск релиза

Раздел перенесён из `README.md` ветки разработки. Корневой `README.md` теперь из `main`: описание проекта, установка и обратная связь.

## Скачать
- Релизы: вкладка **Releases** репозитория, файл `vibetoon-debug.apk`. Что нового и известные ограничения — в [`RELEASE_NOTES_RU.md`](RELEASE_NOTES_RU.md).
- Свежая сборка ветки: **Actions** → последний зелёный запуск «Android debug build» → артефакт `vibetoon-debug.apk`.

> Перед переустановкой сохраните вне приложения:
> - проекты (`.tsproj`: «Резервная копия .tsproj» в меню карточки проекта);
> - настройки («Настройки» → «Резервные копии» → «Резервная копия вручную»).
>
> Обновляйте поверх, не удаляя старую версию: ключ подписи у всех сборок один.

## Сборка
- Нужны JDK 17, Android SDK 35, NDK 27.0.12077973 и CMake 3.22.1.
- `./gradlew assembleDebug` — APK в `app/build/outputs/apk/debug/`.
- Сборка `debug` подписана публичным тестовым ключом (`app/debug-signing/`) и с Sprint 59 не debuggable: так быстрее. Для пошаговой отладки в Android Studio временно поставьте `isDebuggable = true` в `buildTypes.debug` (`app/build.gradle.kts`).
- Тесты: `./gradlew testDebugUnitTest`.
- Словарь RU/EN: `python3 tools/i18n/check_pairs.py && python3 tools/i18n/gen.py`.

## Документы
- [`CHANGELOG_RU.md`](CHANGELOG_RU.md) — журнал изменений по спринтам.
- [`testing/TESTING_GUIDE_RU.md`](testing/TESTING_GUIDE_RU.md) — сценарии проверки на устройстве.
- [`SPRINT_SETTINGS_RU.md`](SPRINT_SETTINGS_RU.md) — настройки, пороги и ограничения.
- [`RELEASE_NOTES_RU.md`](RELEASE_NOTES_RU.md) — текст GitHub Release.
- [`README_RU.md`](README_RU.md) — оглавление папки `docs/`.

## CI
Workflow `.github/workflows/android-build.yml` запускается:
- на ветке разработки и на `main`;
- на pull request в `main`;
- на тегах `v*`;
- вручную (`workflow_dispatch`).

Шаги:
1. Нативные тесты.
2. Проверки скриптов публикации.
3. Словарь RU/EN.
4. `testDebugUnitTest`, затем `assembleDebug`.
5. Проверка подписи; APK не должен быть debuggable.
6. Загрузка артефакта `vibetoon-debug.apk`.

## Выпуск релиза
Тег `vX.Y.Z` или `vX.Y.Z-суффикс` на коммите из `main` собирает APK, прогоняет тесты и создаёт GitHub Release с `vibetoon-debug.apk`. Скрипт выпуска — `tools/ci/publish-release.sh`, его защиты проверяет `tools/ci/tests/publish-release-test.sh`.
- Описание релиза берётся из `docs/RELEASE_NOTES_RU.md`.
- Тег с суффиксом (`-beta`) публикуется как pre-release.
- Если релиз создан вручную на сайте, CI только прикрепит к нему APK.
- CI ничего не опубликует, если тег неверный или коммит не из `main`. Уже прикреплённый APK не заменяется.

### Пошагово (первый выпуск — `v0.1.0-beta`, `versionName 0.1`)
1. Слейте pull request ветки разработки в `main` и дождитесь зелёного CI на `main`.
2. Поставьте тег:
   - **способ А (с ПК):** `git checkout main && git pull && git tag v0.1.0-beta && git push origin v0.1.0-beta`;
   - **способ Б (на сайте):** Releases → Draft a new release → тег `v0.1.0-beta` (target `main`) → текст из `docs/RELEASE_NOTES_RU.md` → Set as a pre-release → Publish. CI прикрепит APK.
3. Примерно через 5 минут проверьте вкладку **Releases**: `vibetoon-debug.apk` должен скачиваться без входа в аккаунт.

Если CI не прикрепил APK, сделайте это вручную: скачайте артефакт из Actions и выполните `gh release upload v0.1.0-beta vibetoon-debug.apk`.
