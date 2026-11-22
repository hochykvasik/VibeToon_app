# VibeToon: передача контекста в новый чат

Вставь в новый чат: «Прочитай `docs/HANDOFF_RU.md` и продолжай». Остальное (архитектура, тесты, история) лежит в `docs/`.

## Проект
Android-редактор 2D-анимации: Kotlin + Jetpack Compose (UI), C++ NDK, OpenGL ES 3.0 (рендер). Папка `C:\dev\VibeToon`. Тестирует девушка на реальном планшете с пером.

## Правила работы (от пользователя)
- Отвечать по-русски, коротко. Шаги маленькие и проверяемые.
- Математику покрывать юнит-тестами, проверять на эмуляторе.
- Пуш и сборку APK делать только по явной просьбе.
- Документы держать в `docs/` (README_RU.md, CHANGELOG_RU.md сверху; architecture/, testing/, commits/). `gradlew exportDebugApk` кладёт APK в `C:\Users\Stacy\Downloads\VibeToon\apk`, `exportDocs` зеркалит документы в `Downloads\VibeToon`. Коммиты в `docs/commits` генерирует `python tools/gen_commits.py`.
- Коммиты оканчиваются строкой `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- На Windows: `python` (не `python3`); сложные скрипты писать файлом в scratchpad, не heredoc с `C:\...`. Пустой `adb shell ""` зависает.

## Состояние git
- Ветка `main`. Чисто.
- Запушено: этап 1 (`8a84f70`, `785387e`). APK: `VibeToon-20261117-0112-debug.apk`.
- Локально, НЕ запушено, APK НЕ собран: этап 2 (`8989e83`, docs `6d8403d`).
- Ждём от пользователя: «пушить и собирать APK этапа 2?»

## Большая задача: растровый движок рисования (уровень Procreate/ToonSquid)
- **Этап 1 (готов):** кисть и ластик, Stroke FBO, Undo с dirty rect, сохранение в PNG, ввод пера (давление, защита от ладони).
- **Этап 2 (готов, проверен на эмуляторе):** 6 кистей (Перо, Маркер, Аэрограф, Карандаш, Скринтон, Квадратный маркер), 3 ластика, зерно в координатах холста, квадратная кисть через `step()`, превью в offscreen FBO 256x64, выезжающее меню кистей (Popup).
- **Этап 3 (следующий, после проверки давления пера на планшете):** панель слоёв: Blend Mode (Multiply, Overlay, Soft Light), Opacity, Alpha Lock, обтравка (clipping mask применяется к Src до композитинга).
- **Этап 4:** заливка (flood fill, сначала CPU с допуском).
- Отложено: `AXIS_TILT` (Compose его не отдаёт, нужен `pointerInteropFilter`).

## Как устроен движок (кратко, подробности в `docs/architecture/PAINTING_ARCHITECTURE.md`)
- Штрих: Stroke FBO размером со слой + `backup` (копия слоя на начало штриха). Пакеты штампов рисуются instanced (`glDrawArraysInstanced`, атрибут vec4 x,y,radius,opacity). Превью = backup + stroke в прямоугольнике пакета, потом mipmaps. `paintEnd` читает «до» из backup и «после» из слоя.
- Режимы: CAP_OPACITY (GL_MAX по 4 каналам), ACCUMULATE (premultiplied ADD, Flow), ERASE (маска GL_MAX, запекание Destination-Out). Opacity только при запекании, Flow на каждый штамп.
- Undo: `Snapshot.paintOp` в общем стеке, Deflater.BEST_SPEED в фоне, лимит 160 МБ (`PaintBudget`), восстановление `glTexSubImage2D`.
- Штампы (Kotlin, `DabStroker`): centripetal Catmull-Rom, накопитель расстояния, умный шаг `clamp(base*(1+R/300), base, max(base,0.4))`, размер логарифмический `1*1500^t`.
- Зерно: процедурные 256x256 R8 REPEAT; CANVAS: `uv = worldPos/(256*scale)`.
- Ввод: Compose `pointerInput`, `PointerType.Stylus/Eraser`, `pressure`, `historical`; палец игнорируется, пока перо касается и 1.2 с после; второй палец отменяет штрих и включает жест камеры.

## Ключевые файлы
- C++: `app/src/main/cpp/render/Paint.cpp`, `Renderer.h/.cpp`, `jni/RenderBridge.cpp`, `CMakeLists.txt`.
- Kotlin: `domain/paint/` (`BrushMath.kt`, `BrushPresets.kt`, `GrainMath.kt`, `PaintOp.kt`), `nativebridge/RenderNative.kt`, `render/VtGLSurfaceView.kt`, `ui/MainViewModel.kt` (раздел «Рисование»), `ui/MainScreen.kt` (`EditorCanvas`, `PaintCursorLayer`), `ui/shell/EditorShell.kt` (`PaintToolRow`, `BrushMenu`).
- Тесты: `BrushMathTest`, `PaintOpTest`, `GrainPresetTest`, `DeformTest`, `KeyGroupTest`. Все проходят.
- Документы: `docs/architecture/PAINTING_ARCHITECTURE.md`, `docs/testing/TESTING_GUIDE_RU.md` (пункты 8o, 8p), `docs/CHANGELOG_RU.md`.

## Не проверено (только на планшете)
Давление пера, защита от ладони, наклон. Ластики в меню на эмуляторе не рисовались. Квадрат 1:1 не оценён (слой на эмуляторе уменьшен).

## Известные грабли
- Свайп от правого края вызывал системный «Назад»: решено `systemGestureExclusion()` на полосе 36dp.
- Тесты тапов в Compose: свайп, начатый на `ScrubBox`, меняет значение; тапать по недвижимым элементам.
- После `adb emu kill` данные эмулятора теряются, это не баг приложения.
- Меню кистей в Popup, иначе обрезается в низком ландшафте.

## Прочее
- Ответ про iPad (теория): KMP + Compose Multiplatform для общего Kotlin/UI, рендер переписать на Metal (или ANGLE для прототипа), сервисы (текст, видео, аудио, декод картинок) переписать, нужен Mac/Xcode.
- Полная история прошлого чата: `C:\Users\Stacy\.claude\projects\C--dev-VibeToon\6e7a7674-6ed0-4568-a2e0-24736835c062.jsonl`.

## Что делать дальше
1. Спросить, пушить ли этап 2 и собирать ли APK.
2. После проверки пера на планшете начать этап 3 (спецификацию пользователь присылает из Telegram; сначала сверить формулы, потом код, тесты, эмулятор).

## Очередность планируемых настроек и функций

Поведенческие/UI-настройки → жесты, камера, ввод → оверлеи, pivots, инструменты и границы Warp & Perspective → Asset Library, импорт и символы в спринте 6.9. Для библиотеки, импорта и символов планируется единый пайплайн импорта, вставки и превью. Всё не реализованное остаётся Coming soon; подробности — в `SPRINT_SETTINGS_RU.md`.

Актуальные статусы спринтов 6.6–6.9 и ограничения текущих функций: `SPRINT_SETTINGS_RU.md`. Auto-Add Keyframes поддерживает трансформации, анимационную позу костей и параметры FX с оговорками для ретайминга; прочие типы остаются вне охвата.
