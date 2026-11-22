# Сообщения, подсказки и туториалы. Шаблоны: {} - подставляемое значение (число или имя), порядок в EN тот же.
PAIRS2 = """
Учебный проект|Tutorial project
Кадр на плейхеде, полное разрешение холста. Копия сохраняется и в галерею (Pictures/VibeToon).|The frame under the playhead at full canvas resolution. A copy is also saved to the gallery (Pictures/VibeToon).
GIF {}×{}, {} кадров|GIF {}×{}, {} frames
Видео {}×{}, {} кадров, без звука|Video {}×{}, {} frames, no audio
Кадр {} из {}|Frame {} of {}
{} КБ. Сохранено в папку проекта|{} KB. Saved to the project folder
{} КБ. Сохранено в папку проекта и в галерею.|{} KB. Saved to the project folder and the gallery.
Нажмите «＋ Добавить», чтобы импортировать PSD, изображение, видео или аудио|Tap “＋ Add” to import a PSD, image, video or audio
Загрузка слоёв: {}/{}|Loading layers: {}/{}
{}: текстур {} ({} МБ). Выделено: {}|{}: {} textures ({} MB). Selected: {}
Ошибка: {}|Error: {}
Не удалось открыть проект|Could not open the project
Не удалось добавить файл|Could not add the file
Добавлено: {}|Added: {}
Камера уже есть: выберите её в списке слоёв|The camera already exists: select it in the layer list
Камера|Camera
Камера: X/Y - смещение кадра, Scale - приближение, Rotation - поворот; ключи на таймлайне|Camera: X/Y - frame offset, Scale - zoom, Rotation - roll; keys on the timeline
Добавлен текст|Text added
Не удалось добавить текст|Could not add text
Не удалось передать изображение в графику|Could not send the image to the renderer
Не удалось передать текст в графику|Could not send the text to the renderer
Пипетка: коснитесь холста, чтобы взять цвет|Eyedropper: tap the canvas to pick a color
Цвет взят|Color picked
Текст не отрисовался: {}|The text was not drawn: {}
Шрифт добавлен: {}|Font added: {}
Не удалось добавить шрифт|Could not add the font
На этом кадре нет ключа у выделенного объекта|The selected object has no key on this frame
Маркеров в этом направлении нет|No markers in this direction
Разрезать пока можно картинки, видео, текст и аудио (слои PSD - позже)|Only images, video, text and audio can be split for now (PSD layers later)
Поставьте плейхед внутри клипа выбранного слоя|Place the playhead inside the selected layer's clip
Клип разрезан на кадре {}|Clip split at frame {}
Дублировать можно картинки, видео, текст и рисунки|Only images, video, text and drawings can be duplicated
Слой продублирован|Layer duplicated
Слой заблокирован: снимите замок в списке слоёв|The layer is locked: unlock it in the layer list
Копируются только обычные слои, не группы|Only regular layers can be copied, not groups
Слой «{}» скопирован|Layer “{}” copied
Сначала скопируйте слой|Copy a layer first
Скопированный слой уже удалён|The copied layer has been deleted
Слой ещё не загружен, повторите|The layer is not loaded yet, try again
Слой вставлен|Layer pasted
Очистить можно слой-картинку или слой PSD|Only an image or PSD layer can be cleared
Слой очищен|Layer cleared
Не удалось нарисовать символ «{}»: откройте и закройте его (кнопка «На главную сцену»)|Could not draw symbol “{}”: open and close it (the “Back to main scene” button)
Создавайте символ на главной сцене: выйдите из редактирования символа|Create symbols on the main scene: leave symbol editing first
Выберите слои для символа|Select layers for the symbol
Определение символа нельзя класть в символ: берите его экземпляр|A symbol definition cannot be put in a symbol: use its instance
Слишком глубокая вложенность символов (максимум {})|Symbols are nested too deeply (maximum {})
Символ {}|Symbol {}
Символ «Символ {}»: слои стали кадрами ({}), маркеры по именам слоёв. Двойной тап открывает его|Symbol “Symbol {}”: layers became frames ({}), markers by layer names. Double-tap opens it
Символ «Символ {}» создан: двойной тап по нему открывает редактирование|Symbol “Symbol {}” created: double-tap it to edit
Это не символ: сначала выберите «Создать символ»|This is not a symbol: choose “Create symbol” first
Редактирование «{}»: правки видны во всех экземплярах|Editing “{}”: changes show in all instances
Маркер «{}»: кадр {}|Marker “{}”: frame {}
«{}» с кадра {}|“{}” from frame {}
Ключ времени удалён|Time key removed
Ключи времени очищены: символ снова идёт по кругу|Time keys cleared: the symbol loops again
У символа нет маркеров: откройте его двойным тапом и поставьте маркеры кнопкой «Маркеры»|The symbol has no markers: double-tap to open it and add markers with the “Markers” button
Ключ времени (Hold) на кадре {}|Time key (Hold) at frame {}
Калька включена: предыдущий рисунок красный, следующий зелёный (нужен покадровый слой)|Onion skin on: previous drawing is red, next is green (needs a frame-by-frame layer)
Калька выключена|Onion skin off
Выберите слой-картинку (кнопка «Новый слой») - на нём ведётся покадровая анимация|Select an image layer (the “New layer” button): frame-by-frame animation lives on it
Нет свободного кадра до конца проекта|No free frame before the end of the project
Пустой кадр добавлен: {}|Empty frame added: {}
Не удалось скопировать рисунок|Could not copy the drawing
Кадр продублирован: {}|Frame duplicated: {}
Последний рисунок удалить нельзя: у слоя должен остаться хотя бы один|The last drawing cannot be deleted: a layer needs at least one
Кадр удалён, держится предыдущий|Frame deleted, the previous one is held
Для слияния нужно минимум два обычных слоя|Merging needs at least two regular layers
Не удалось собрать слой, повторите|Could not build the layer, try again
Слияние ({})|Merge ({})
Слито слоёв: {}|Layers merged: {}
Отметьте слои для группы|Select layers for the group
Слои PSD и картинки/текст нельзя смешивать в одной группе: соберите их по отдельности|PSD layers and images/text cannot be mixed in one group: group them separately
Нельзя: группа внутри выбранного слоя|Not allowed: the group would be inside the selected layer
Группа {}|Group {}
Группа создана: слоёв {}|Group created: {} layers
Под слоем нет обычного слоя, с которым можно слить|There is no regular layer below to merge with
Слой уже растровый|The layer is already raster
Слой растрирован|Layer rasterized
Не удалось снять кадр|Could not capture the frame
Не удалось снять кадр {}|Could not capture frame {}
Не удалось записать GIF|Could not write the GIF
Не удалось записать видео|Could not write the video
FX вкл: правки точек пишутся в ключевые кадры|FX on: point edits go to keyframes
FX выкл (Bind Pose): правки меняют базовую форму|FX off (Bind Pose): edits change the base shape
Warp слоя сброшен|Layer warp reset
Перспектива слоя сброшена|Layer perspective reset
Liquify: выберите слой и проведите пальцем (правка необратимо меняет пиксели слоя)|Liquify: select a layer and drag (the edit permanently changes the layer's pixels)
Не удалось открыть картинку|Could not open the image
Слой готовится, повторите мазок|The layer is getting ready, repeat the stroke
Liquify: выберите один слой (картинка или слой PSD)|Liquify: select one layer (image or PSD layer)
Кисть: рисуйте на слое-картинке (или создайте «Новый слой»)|Brush: draw on an image layer (or create a “New layer”)
Ластик: стирает пиксели слоя-картинки|Eraser: erases pixels of an image layer
На векторном слое ластика нет: «Убрать последнюю» удаляет примитив|There is no eraser on a vector layer: “Remove last” deletes a primitive
Рисовать можно на слое-картинке: выберите его или создайте «Новый слой»|You can draw on an image layer: select one or create a “New layer”
Память Undo для рисунка заполнена: старые штрихи больше не отменяются|Drawing undo memory is full: old strokes can no longer be undone
Этот штрих уже нельзя отменить: память Undo освобождена|This stroke can no longer be undone: undo memory was freed
Рисунок этого штриха удалён: откатывать нечего|The drawing of this stroke was deleted: nothing to undo
Не удалось создать слой в графике|Could not create the layer in the renderer
Рисунок {}|Drawing {}
Новый слой «{}»: рисуйте кистью|New layer “{}”: draw with the brush
Не удалось создать слой|Could not create the layer
Векторный слой «{}»: рисуйте кистью (путь, линия, прямоугольник, эллипс) или добавляйте «Фигуры»|Vector layer “{}”: draw with the brush (path, line, rectangle, ellipse) or add “Shapes”
Анимационный слой «{}»: рисуйте, «Добавить пустой кадр» ставит следующий рисунок|Animation layer “{}”: draw, “Add empty frame” creates the next drawing
Фон «{}» залит цветом фона проекта и заблокирован (замок снимается в списке слоёв)|Background “{}” is filled with the project color and locked (unlock it in the layer list)
Фон «{}» залит цветом фона проекта и заблокирован (замок снимается в списке слоёв). В проекте с PSD он ложится над слоями PSD|Background “{}” is filled with the project color and locked (unlock it in the layer list). In a PSD project it sits above the PSD layers
Новый слой «{}»|New layer “{}”
Пустая группа «Группа {}»: перетащите в неё слои ручкой ≡|Empty group “Group {}”: drag layers into it with the ≡ handle
Фигура «{}» добавлена в векторный слой|Shape “{}” added to the vector layer
Цикл включён: после конца проигрывание идёт с начала|Loop on: playback restarts after the end
Цикл выключен: проигрывание остановится на последнем кадре|Loop off: playback stops on the last frame
Магнит включён: плейхед прилипает к ключам, маркерам и краям клипов|Magnet on: the playhead snaps to keys, markers and clip edges
Магнит выключен|Magnet off
Не удалось создать фигуру в графике|Could not create the shape in the renderer
Фигура: {}|Shape: {}
Фигура «{}» добавлена|Shape “{}” added
Не удалось добавить фигуру|Could not add the shape
Сетка создана по контуру: тапните ещё раз, чтобы поставить пин|Mesh created from the outline: tap again to add a pin
Не больше {} пинов|No more than {} pins
Пин {}: тяните его, рисунок следует за точкой|Pin {}: drag it, the drawing follows the point
Выберите пин (тап по зелёной точке)|Select a pin (tap a green dot)
Выберите пин|Select a pin
Пины возвращены в исходное положение|Pins returned to their original position
Включите FX (кнопка со звёздами): ключи пинов пишутся в режиме анимации|Turn on FX (the sparkle button): pin keys are written in animation mode
Ключ пина на кадре {}|Pin key at frame {}
Выберите один слой, чтобы добавить эффект|Select one layer to add an effect
Выберите один слой|Select one layer
На слое не больше {} эффектов|No more than {} effects per layer
Пипетка: коснитесь холста, чтобы взять цвет для эффекта|Eyedropper: tap the canvas to pick a color for the effect
Сначала создайте сетку («Сгенерировать»)|Create a mesh first (“Generate”)
Привязка к костям снята|Bone binding removed
Нет костей: создайте скелет|No bones: create a skeleton
Сетка привязана к костям ({}): вершины двигаются по весам|Mesh bound to bones ({}): vertices move by weights
Сетка: выберите слой, затем «Сгенерировать» или «+Сетка» (тап добавляет вершину)|Mesh: select a layer, then “Generate” or “+Mesh” (tap adds a vertex)
Сетка: {} вершин|Mesh: {} vertices
Сетка слоя удалена|Layer mesh deleted
Нельзя удалить последний треугольник: используйте «Удалить» сетку|The last triangle cannot be deleted: use “Delete” mesh
Точка удалена, сетка перестроена ({} вершин)|Point deleted, mesh rebuilt ({} vertices)
Слой полностью прозрачен|The layer is fully transparent
Сетка по контуру: {} вершин|Outline mesh: {} vertices
Слой ещё не загружен|The layer is not loaded yet
Режим костей: «＋ Кость» и протяжка пальцем рисуют кость|Bone mode: “＋ Bone” and a finger drag draw a bone
Режим объектов|Object mode
Кости создаются в режиме Bind Pose|Bones are created in Bind Pose mode
Bind Pose: скелет настраивается, картинка не деформируется|Bind Pose: the skeleton is set up, the image is not deformed
Animation: кость двигает привязанные слои|Animation: a bone moves its bound layers
Сначала выберите кость (инструмент «Кости»)|Select a bone first (“Bones” tool)
У кости нет родителя|The bone has no parent
{}: теперь корневая кость|{}: now a root bone
Лассо: слоёв внутри контура нет|Lasso: no layers inside the outline
Обведено слоёв: {}. Выберите кость (инструмент «Выбрать») и нажмите «Привязать»|Layers lassoed: {}. Select a bone (“Select” tool) and tap “Bind”
Сначала выберите кость (инструмент «Выбрать»)|Select a bone first (“Select” tool)
Сначала обведите слои лассо|Lasso some layers first
Привязка работает в режиме Bind Pose|Binding works in Bind Pose mode
Привязано слоёв: {} к «{}» (вручную)|Layers bound: {} to “{}” (manual)
Кость {}|Bone {}
Кость создана, родитель: {}|Bone created, parent: {}
Корневая кость создана|Root bone created
Костей удалено: {}|Bones deleted: {}
Автопривязка работает в режиме Bind Pose|Auto bind works in Bind Pose mode
Сначала создайте кости|Create bones first
Нет свободных слоёв, которые пересекают кости|No free layers intersect the bones
Автопривязка: слоёв привязано {}|Auto bind: layers bound {}
Кость {}|Bone {}
Символ|Symbol
Пиксели|Pixels
Нельзя: родитель сам привязан к этому слою|Not allowed: the parent is itself bound to this layer
Привязка снята|Binding removed
Привязано к: {}|Bound to: {}
Выделите два слоя или больше (кнопка мультивыбора)|Select two or more layers (the multi-select button)
Лесенка: {} клипов встык, конец каждого = началу следующего|Stagger: {} clips back to back, each end = next start
Лесенка: {} клипов встык; {} не поместились в проект и упёрлись в его конец (увеличьте длительность)|Stagger: {} clips back to back; {} did not fit and hit the end of the project (increase the duration)
Нельзя: группа не может войти сама в себя|Not allowed: a group cannot go into itself
Картинки, видео и текст пока нельзя класть в группы PSD|Images, video and text cannot be put into PSD groups yet
Слой вынесен из группы|Layer moved out of the group
Слой перенесён в «{}»|Layer moved into “{}”
Порядок слоёв изменён|Layer order changed
Ключей удалено: {}|Keys deleted: {}
В выделении нет ключей|No keys in the selection
Скопировано ключей: {}|Keys copied: {}
Вставлено ключей: {}|Keys pasted: {}
Удалено: {}|Deleted: {}
Маркеры кадров (кадр {})|Frame markers (frame {})
Символ: {}|Symbol: {}
У символа нет маркеров. Двойной тап по символу открывает его: кнопка «Маркеры» ставит имя на кадр.|The symbol has no markers. Double-tap the symbol to open it: the “Markers” button names a frame.
Ключей: {}. Ключ времени всегда Hold: символ мгновенно переключается на кадр маркера и держит его до следующего ключа.|Keys: {}. A time key is always Hold: the symbol instantly jumps to the marker frame and holds it until the next key.
Эффектов нет. Нажмите «+ Добавить эффект»: Хромакей, Размытие, Цветокоррекция, Пикселизация, Свечение, Тень и другие. Цепочка применяется сверху вниз.|No effects. Tap “+ Add effect”: Chroma key, Blur, Color correction, Pixelate, Glow, Shadow and more. The chain applies top to bottom.
«{}» появится на одном из следующих этапов|“{}” will appear in one of the next stages
Выбрано: {} (правка применяется ко всем)|Selected: {} (edits apply to all)
Группа: {}|Group: {}
Слой: {}|Layer: {}
Родитель: {}|Parent: {}
Привязать к родителю (Parent)|Bind to parent
Слой повторяет сдвиг, поворот и масштаб родителя (глаза двигаются вместе с головой).|The layer follows the parent's move, rotation and scale (eyes move with the head).
Края клипа тянутся пальцем на таймлайне, выбранный клип можно перетащить за середину.|Drag clip edges on the timeline; drag the middle to move the selected clip.
Скорость и реверс уже работают для звука; для видео они заработают вместе с воспроизведением видео.|Speed and reverse already work for audio; for video they will work together with video playback.
Применяется шейдером эффектов (как эффект «Цветокоррекция» перед остальными). Сохраняется в проекте.|Applied by the effects shader (like “Color correction” before the others). Saved in the project.
Текст слоя заменяется числом; «#» в тексте - место числа (например «$#»).|The layer text is replaced by a number; “#” marks where the number goes (for example “$#”).
{} Гц|{} Hz
Допустимо от {} до {} px|Allowed from {} to {} px
Пропорции {}|Aspect ratio {}
{} — в разработке|{} — coming soon
Сначала создайте проект («+» сверху), затем нажмите «Пройти в редакторе»|Create a project first (“+” at the top), then tap “Walk through in the editor”
Новый проект {}|New project {}
«{}» и все его файлы будут удалены без возможности восстановления.|“{}” and all its files will be deleted permanently.
{} · шаг {} из {}|{} · step {} of {}
Пропустить|Skip
Настройки инструмента «{}»|Tool settings “{}”
Инструмент «{}»|Tool “{}”
Символ: {}|Symbol: {}
Liquify: проведите по картинке · кисть {}|Liquify: drag over the image · brush {}
Перспектива · FX вкл: углы пишутся в ключи|Perspective · FX on: corners go to keys
Перспектива · FX выкл: базовая форма|Perspective · FX off: base shape
FX вкл: пин пишется в ключи|FX on: the pin goes to keys
FX выкл: тяните пин (форма привязки)|FX off: drag the pin (binding shape)
FX вкл: вершины пишутся в ключи|FX on: vertices go to keys
FX выкл: форма привязки|FX off: binding shape
Warp · FX вкл: точки пишутся в ключи|Warp · FX on: points go to keys
Warp · FX выкл: базовая форма|Warp · FX off: base shape
Привязать ({})|Bind ({})
Слои как кадры: каждый слой станет отдельным кадром символа, а на кадре появится маркер с именем слоя - удобно для форм рта и анимации по рисункам.\\n\\nСлои стопкой: все слои остаются вместе и видны одновременно (один составной объект).|Layers as frames: each layer becomes a separate frame of the symbol and a marker with the layer's name appears on that frame - handy for mouth shapes and drawing-based animation.\\n\\nLayers stacked: all layers stay together and visible at once (one compound object).
Слоёв: {}|Layers: {}
Удалить выбранные ({})|Delete selected ({})
Привязать к кости ({})|Bind to bone ({})
Кость: {}|Bone: {}
Слоёв пока нет. Нажмите «+» вверху, чтобы добавить PSD, картинку, видео или текст.|No layers yet. Tap “+” at the top to add a PSD, image, video or text.
Длина: {} px|Length: {} px
Привязано слоёв: {}|Layers bound: {}
Клипы|Clips
Память|Memory
Узлов: {}|Nodes: {}
Узел {}|Node {}
Двойной тап по линии — новый узел, долгий тап по узлу — удалить. Тяните узел и красный (In) / синий (Out) усики: так делают S-образные кривые, пружины и затухание. Время → вправо, значение ↑ вверх.|Double-tap the line for a new node, long-press a node to delete it. Drag a node and the red (In) / blue (Out) handles: that is how S-curves, springs and falloff are made. Time → right, value ↑ up.
Прозрачность: {}%|Opacity: {}%
Красный - предыдущий рисунок, зелёный - следующий. Работает на слое-картинке с кадрами (кнопка «Добавить пустой кадр»).|Red is the previous drawing, green is the next. Works on an image layer with frames (the “Add empty frame” button).
Выбрано: {}|Selected: {}
Всего кадров (до {})|Total frames (up to {})
Длительность: {} (мин:сек:кадры)|Duration: {} (min:sec:frames)
Введите числа|Enter numbers
Интерактивный урок|Interactive lesson
Готовый проект с подсказками на кнопках|A ready project with hints on the buttons
Это готовый проект: на холсте три фигуры, каждая на своём слое. Подсказки по очереди покажут главные кнопки. Кнопка «Пропустить» закрывает урок в любой момент.|This is a ready project: three shapes on the canvas, each on its own layer. Hints will point out the main buttons one by one. “Skip” closes the lesson at any time.
Включите «Кисть», чтобы рисовать на слое-картинке. Кнопка «Новый слой» над холстом создаёт чистый слой для рисунка.|Turn on “Brush” to draw on an image layer. The “New layer” button above the canvas creates a clean layer for a drawing.
левая панель инструментов|left tool panel
Справа список слоёв: глаз скрывает слой, ручка ≡ меняет порядок, замок блокирует. Зажмите строку слоя, чтобы открыть меню.|The layer list is on the right: the eye hides a layer, the ≡ handle changes order, the lock locks. Press and hold a layer row to open its menu.
правая панель|right panel
Таймлайн|Timeline
Красная линия - текущий кадр. Тяните её по линейке: у края экрана таймлайн сам прокручивается. «Играть» запускает проигрывание.|The red line is the current frame. Drag it along the ruler: near the screen edge the timeline scrolls by itself. “Play” starts playback.
панель воспроизведения|playback bar
Анимация фигур|Animating shapes
Выберите фигуру, переставьте плейхед, подвиньте фигуру и нажмите «Add Key». Между ключами движение считается само. «Лесенка» выстраивает выбранные клипы встык.|Select a shape, move the playhead, move the shape and tap “Add Key”. Motion between keys is computed automatically. “Stagger” lines up the selected clips back to back.
Готово? Нажмите «Export», выберите MP4, GIF или картинку и поделитесь результатом.|Done? Tap “Export”, choose MP4, GIF or an image and share the result.
верхняя панель|top bar
Урок можно повторить из раздела «Обучение» в любой момент.|You can repeat the lesson from the “Tutorials” section at any time.
Основы работы с холстом|Canvas basics
Первый проект за 5 шагов|Your first project in 5 steps
Создайте проект|Create a project
На экране проектов нажмите «＋» сверху, выберите пропорции и «Создать».|On the projects screen tap “＋” at the top, choose an aspect ratio and “Create”.
правый верхний угол экрана проектов|top right of the projects screen
Добавьте картинку, видео или аудио|Add an image, video or audio
В редакторе откройте меню действий и выберите тип файла. Картинка встанет по центру холста.|In the editor open the actions menu and choose a file type. An image is placed in the center of the canvas.
левый верхний угол редактора|top left of the editor
Двигайте объект|Move an object
Тапните по объекту, чтобы выделить его. Один палец двигает, два пальца вращают и масштабируют.|Tap an object to select it. One finger moves it, two fingers rotate and scale.
Двигайте сам холст|Move the canvas itself
Если ничего не выделено, два пальца двигают и масштабируют вид. Вернуть вид: «Сбросить вид холста» в меню действий.|With nothing selected, two fingers pan and zoom the view. To restore it: “Reset canvas view” in the actions menu.
Тап в пустое место снимает выделение.|Tapping an empty area clears the selection.
Отмена и повтор|Undo and redo
Любое действие можно отменить.|Any action can be undone.
Проект сохраняется сам через пару секунд после правки.|The project saves itself a couple of seconds after an edit.
Как добавить и анимировать текст|How to add and animate text
Надпись, цвет и ключевые кадры|Caption, color and keyframes
Добавьте текст|Add text
Нажмите инструмент «Текст» слева, введите надпись и подтвердите.|Tap the “Text” tool on the left, type the caption and confirm.
Настройте вид|Adjust the look
Выделите текст и откройте вкладку «Свойства»: шрифт, размер, цвет, градиент.|Select the text and open “Properties”: font, size, color, gradient.
вкладка в правой панели|tab in the right panel
Поставьте первый ключ|Set the first key
Перейдите на нужный кадр и нажмите «Add Key» на таймлайне.|Go to the frame you want and tap “Add Key” on the timeline.
первая строка таймлайна|first row of the timeline
Сдвиньте и поставьте второй ключ|Move it and set a second key
Перейдите на другой кадр, подвиньте текст и снова нажмите «Add Key». Текст поедет между ключами.|Go to another frame, move the text and tap “Add Key” again. The text will move between the keys.
Проверьте результат|Check the result
Нажмите Play.|Tap Play.
Кнопка f(x)-кривой рядом с ключом меняет плавность движения.|The f(x) curve button next to a key changes the smoothness of motion.
Текст можно гнуть: добавьте к нему Warp.|Text can be bent: add Warp to it.
Как работают Кости и Bind Pose|How Bones and Bind Pose work
Скелет, привязка и анимация|Skeleton, binding and animation
Переключитесь в режим костей|Switch to bone mode
В верхней панели выберите «Кости». Слева появятся инструменты скелета.|Choose “Bones” in the top bar. The skeleton tools appear on the left.
переключатель режимов сверху|mode switch at the top
Создайте первую кость|Create the first bone
Выберите «+Кость» и проведите пальцем по холсту: задайте длину и направление. Следующая кость, начатая у конца выбранной, станет её дочерней.|Choose “+Bone” and drag on the canvas to set length and direction. The next bone started at the end of the selected one becomes its child.
левая панель в режиме «Кости»|left panel in “Bones” mode
Настройте скелет в Bind Pose|Set up the skeleton in Bind Pose
Включите Bind Pose, чтобы двигать кости, не искажая картинку, и подогнать скелет под персонажа.|Turn on Bind Pose to move bones without distorting the image and fit the skeleton to the character.
тумблер на холсте вверху слева|toggle at the top left of the canvas
Привяжите слои|Bind layers
«Автопривязка» привязывает слои сама. Для ручной привязки: «Лассо», обведите слои руки, выберите кость и нажмите «Привязать».|“Auto bind” binds layers by itself. For manual binding: “Lasso” the arm layers, select a bone and tap “Bind”.
плавающая панель на холсте|floating panel on the canvas
Анимируйте|Animate
Переключите тумблер на Animation, выберите инструмент «Кости» и тяните конец кости.|Switch the toggle to Animation, choose the “Bones” tool and drag the end of a bone.
«Независимый поворот» в свойствах кости сохраняет её угол, пока родитель вращается.|“Independent rotation” in bone properties keeps its angle while the parent rotates.
Рисуйте кости по порядку: плечо → предплечье → кисть.|Draw bones in order: shoulder → forearm → hand.
Что такое Mesh и Warp|What are Mesh and Warp
Два способа согнуть картинку|Two ways to bend an image
Выберите слой|Select a layer
Тапните по слою на холсте или в списке слоёв. Warp работает с картинками, текстом и слоями PSD.|Tap a layer on the canvas or in the layer list. Warp works with images, text and PSD layers.
Включите Warp|Turn on Warp
В левой панели прокрутите вниз и выберите «Warp». На слое появится решётка из 16 точек.|Scroll down the left panel and choose “Warp”. A grid of 16 points appears on the layer.
левая панель, последний инструмент|left panel, last tool
Гните за точки|Bend by the points
Потяните любую точку: картинка плавно изогнётся. Так делают сжатие и растяжение.|Drag any point: the image bends smoothly. This is how squash and stretch are made.
«Сбросить» в плавающей панели убирает деформацию.|“Reset” in the floating panel removes the deformation.
Выберите режим FX|Choose the FX mode
FX выключен: правка меняет базовую форму. FX включён: правка пишется ключом на текущем кадре.|FX off: an edit changes the base shape. FX on: an edit is written as a key on the current frame.
верхняя панель и таймлайн|top bar and timeline
Mesh: сетка из треугольников|Mesh: a triangle grid
Переключитесь в режим «Сетка», выберите слой и нажмите «Сгенерировать» (или «По контуру»). Тяните вершины, режьте инструментом «Разрез», мягко сдвигайте «Смазкой».|Switch to “Mesh” mode, select a layer and tap “Generate” (or “By outline”). Drag vertices, cut with the “Cut” tool, nudge softly with “Smudge”.
Привяжите сетку к костям|Bind the mesh to bones
Создайте кости, затем в панели сетки нажмите «Кости»: вершины будут двигаться по весам костей, а не слой целиком.|Create bones, then tap “Bones” in the mesh panel: vertices will move by bone weights instead of the whole layer.
Повторное нажатие «Кости» снимает привязку.|Tapping “Bones” again removes the binding.
Перспектива, Turbulent, Liquify|Perspective, Turbulent, Liquify
«Перспектива» наклоняет слой за 4 угла. «Эффекты → Turbulent Displace» добавляет волнистость и Line Boil. «Liquify» двигает пиксели картинки пальцем.|“Perspective” tilts a layer by 4 corners. “Effects → Turbulent Displace” adds waviness and Line Boil. “Liquify” pushes the image's pixels with a finger.
Сначала настройте форму при выключенном FX, потом анимируйте.|Set the shape with FX off first, then animate.
Слои и группы|Layers and groups
Перетаскивание, свайпы, мультивыбор|Dragging, swipes, multi-select
Откройте список слоёв|Open the layer list
Правая панель, вкладка «Слои».|Right panel, “Layers” tab.
Перенесите слой в группу|Move a layer into a group
Зажмите строку слоя и потяните на группу — слой станет дочерним. Потяните на слой в корне — слой выйдет из группы.|Press and hold a layer row and drag it onto a group: the layer becomes its child. Drag it onto a root layer: it leaves the group.
Свайпы по слою|Swipes on a layer
Свайп влево открывает меню слоя, свайп вправо включает мультивыбор.|A left swipe opens the layer menu, a right swipe turns on multi-select.
свайп вправо по слою|swipe right on a layer
Картинки и текст в группы PSD класть нельзя.|Images and text cannot be put into PSD groups.
Липсинг: символ, маркеры, время|Lip-sync: symbol, markers, time
Рот говорит под звук|The mouth speaks to the audio
Нарисуйте формы рта|Draw the mouth shapes
Включите «Кисть» и нажмите «Новый слой» в панели рисования: каждая форма рта (A, O, M, Rest...) рисуется на своём слое.|Turn on “Brush” and tap “New layer” in the drawing bar: each mouth shape (A, O, M, Rest...) is drawn on its own layer.
панель кисти на холсте|brush bar on the canvas
Выделите слои и создайте символ|Select the layers and create a symbol
Зайдите в мультивыбор, отметьте слои, зажмите строку слоя и выберите «Создать символ». В окне выберите «Слои как кадры»: каждый слой станет кадром символа, а на кадре появится маркер с именем слоя.|Enter multi-select, mark the layers, press and hold a layer row and choose “Create symbol”. In the dialog choose “Layers as frames”: each layer becomes a frame of the symbol with a marker named after the layer.
значок над списком слоёв|icon above the layer list
«Слои стопкой» оставляет всё видимым одновременно - для составного персонажа, а не для липсинга.|“Layers stacked” keeps everything visible at once - for a compound character, not for lip-sync.
Проверьте маркеры|Check the markers
Двойной тап по символу (или «Редактировать символ» в меню слоя) открывает его внутри. Кнопка «Маркеры» сверху ставит имя (Rest, AI, E, O, U, MBF, L, W или своё) на текущий кадр. «◀ На главную сцену» возвращает.|Double-tap a symbol (or “Edit symbol” in the layer menu) to open it. The “Markers” button at the top names the current frame (Rest, AI, E, O, U, MBF, L, W or your own). “◀ Back to main scene” returns.
плашка «◀ На главную сцену» над холстом|the “◀ Back to main scene” bar above the canvas
Добавьте звук|Add audio
«＋» → Аудио. Проект сам продлится до конца звука. Волна видна на плашке звука; перетаскивание плейхеда по линейке играет короткий фрагмент - так находят начало каждой фонемы на слух.|“＋” → Audio. The project extends to the end of the sound by itself. The waveform shows on the audio bar; dragging the playhead along the ruler plays a short snippet - that is how you find each phoneme by ear.
меню «＋» над списком слоёв|“＋” menu above the layer list
Выберите маркер на кадре|Pick a marker on a frame
Выделите символ на основной сцене: во вкладке «Свойства → Время» нажмите «Выбрать маркер». Откроется плавающая панель с картинками: перетащите её, куда удобно, спрячьте к краю или закройте крестиком. Остановите плейхед на звуке и тапните нужный рот.|Select the symbol on the main scene: in “Properties → Time” tap “Pick marker”. A floating panel with pictures opens: move it where convenient, hide it to the edge or close it with the cross. Stop the playhead on a sound and tap the right mouth.
вкладка «Свойства» у символа|the “Properties” tab of the symbol
Активный маркер подсвечен фиолетовой рамкой.|The active marker has a purple frame.
Hold: мгновенное переключение|Hold: instant switching
Каждый тап по маркеру ставит ключ времени Hold: рот не перематывается, а мгновенно становится выбранным и держится до следующего ключа. Ключи видны на плашке символа фиолетовыми засечками. Иконка «ключ» ставит ключ с текущим значением вручную.|Each tap on a marker sets a Hold time key: the mouth does not scrub, it instantly becomes the chosen one and stays until the next key. Keys show as purple ticks on the symbol bar. The “key” icon sets a key with the current value manually.
Ключи можно удалить (кнопка «Убрать ключ» на текущем кадре) или очистить все: символ снова пойдёт по кругу.|Keys can be deleted (the “Remove key” button on the current frame) or all cleared: the symbol loops again.
Таймлайн: ключи, лесенка, маркеры|Timeline: keys, stagger, markers
Быстрая работа со временем|Working with time quickly
Выделите ключи рамкой|Box-select keys
Включите выделение, обведите ключи, потяните — они сдвинутся вместе.|Turn on selection, box the keys, drag: they move together.
Выделение ключей|Key selection
вторая строка таймлайна|second row of the timeline
Удалить ключи|Delete keys
Выделите несколько слоёв и нажмите «Лесенка»: клипы встанут встык: конец одного совпадёт с началом следующего.|Select several layers and tap “Stagger”: the clips line up back to back: the end of one is the start of the next.
Выделите несколько слоёв и нажмите «Лесенка»: клипы встанут со сдвигом на кадр.|Select several layers and tap “Stagger”: the clips line up back to back.
Размер панели|Panel size
Потяните серый «хват» над таймлайном вверх или вниз. Eject сворачивает таймлайн.|Drag the gray handle above the timeline up or down. Eject collapses the timeline.
Свернуть|Collapse
Щипок двумя пальцами по таймлайну меняет масштаб времени.|A two-finger pinch on the timeline changes the time scale.
Пройти в редакторе: кнопки подсвечиваются|Walk through in the editor: buttons are highlighted
Советы|Tips
Где: {}|Where: {}
Сохранено в галерею|Saved to the gallery
Порог|Threshold
Расшир.|Expand
Зазор|Gap
Овал|Oval
Палочка|Wand
Инверсия|Invert
Снять|Deselect
Перо|Pen
Замкнуть|Close
Убрать точку|Remove point
Прямоугольник|Rectangle
Лассо|Lasso
Смазка|Smudge
Заливка|Fill
Выделение|Selection
Пипетка: коснитесь холста, чтобы взять цвет|Eyedropper: tap the canvas to pick a color
Заливка: коснитесь замкнутой области (порог, расширение и зазор - в панели над холстом)|Fill: tap a closed area (threshold, expand and gap are in the bar above the canvas)
Выделение: обведите область или коснитесь палочкой; внутри выделения тяните, чтобы перенести|Selection: outline an area or tap with the wand; drag inside the selection to move it
Смазка: проведите по рисунку слоя-картинки|Smudge: drag across the drawing of an image layer
Перо: тапами ставьте точки на векторном слое, «Готово» завершает контур, тап по первой точке замыкает|Pen: tap to place points on a vector layer, “Done” finishes the outline, tapping the first point closes it
Инструмент работает на слое-картинке: выберите его или создайте «Новый слой»|The tool works on an image layer: select one or create a “New layer”
Слой готовится, повторите|The layer is getting ready, try again
Здесь нечего заливать: область уже этого цвета или точка попала на линию|Nothing to fill here: the area is already this color or the tap hit a line
Под выделением пусто|The selection is empty
Перо рисует на векторном слое: «＋» → «Векторный», затем снова выберите «Путь»|The pen draws on a vector layer: “＋” → “Vector”, then choose “Path” again
Для контура нужно хотя бы две точки|An outline needs at least two points
Контур замкнут|Outline closed
Контур добавлен|Outline added
Анимация|Animation
Вектор|Vector
Рисунок|Drawing
Поиск|Search
Сортировка|Sort
По дате|By date
По имени|By name
По размеру|By size
Проектов пока нет|No projects yet
Очистить кэш и лишние файлы|Clear cache and unused files
Удалить папку|Delete folder
В папку|Move to folder
Без папки|No folder
Папок пока нет: создайте её кнопкой сверху.|No folders yet: create one with the button at the top.
Резервная копия .tsproj|Backup .tsproj
PNG-последовательность (ZIP)|PNG sequence (ZIP)
Только аудио (WAV)|Audio only (WAV)
Многослойный PSD текущего кадра: слои и группы сохраняются, режимы наложения тоже. Текст, видео, символы и вектор идут как растр; прозрачность слоя запекается в пиксели.|Layered PSD of the current frame: layers and groups are kept, blend modes too. Text, video, symbols and vector go as raster; layer opacity is baked into pixels.
Все дорожки проекта сводятся в один WAV (стерео 44,1 кГц) на всю длительность проекта.|All project tracks are mixed into one WAV (stereo 44.1 kHz) for the whole project duration.
Резервная копия проекта со всеми файлами. Её можно импортировать на экране проектов.|A backup of the project with all its files. It can be imported on the projects screen.
Каждый кадр - PNG с прозрачным фоном, всё в одном ZIP. Это замена видео MOV с альфой: Android не умеет ProRes 4444.|Each frame is a PNG with a transparent background, all in one ZIP. This replaces MOV video with alpha: Android cannot do ProRes 4444.
Обрезать холст (Crop)|Crop canvas
Изменить размер холста (Resize)|Resize canvas
Сохранять пропорции|Keep proportions
Допустимо от 64 до 4096 px. Действие нельзя отменить через Undo.|Allowed from 64 to 4096 px. This cannot be undone with Undo.
Resize масштабирует слои вместе с холстом, Crop оставляет выбранную рамку. Эти действия нельзя отменить через Undo.|Resize scales layers with the canvas, Crop keeps the chosen frame. These actions cannot be undone with Undo.
Применить|Apply
Ширина|Width
Высота|Height
Резервная копия .tsproj сохранена|Backup .tsproj saved
Не удалось сохранить копию|Could not save the backup
Проект импортирован|Project imported
Файл не похож на резервную копию VibeToon (.tsproj)|The file does not look like a VibeToon backup (.tsproj)
Сейчас: {} × {} px|Now: {} × {} px
Ступенька (Hold)|Step (Hold)
Растянуть ×2|Stretch ×2
Сжать ÷2|Compress ÷2
Реверс|Reverse
Transform Layer (null)|Transform Layer (null)
Глубина (параллакс)|Depth (parallax)
В новый слой|To new layer
Удалить узел|Delete node
Удалить примитив|Delete primitive
Выделение вынесено в слой «{}»: теперь его можно привязать к кости|Selection moved to layer “{}”: you can now bind it to a bone
«В новый слой» работает на слое-картинке (не на слое PSD)|“To new layer” works on an image layer (not a PSD layer)
Сначала коснитесь узла примитива|Tap a primitive's node first
Реверс ключей: {}|Keys reversed: {}
Ключи изменены: {}|Keys changed: {}
Слева|Left
По центру|Center
Справа|Right
Межстрочный|Line spacing
Межбуквенный|Letter spacing
Громкость|Volume
Поставить ключ|Set key
Ключ стоит|Key set
Ключей: {}. Громкость меняется линейно между ключами; до первого и после последнего держится. Работает при воспроизведении, прослушивании линейки и в WAV. В MP4 звука пока нет.|Keys: {}. Volume changes linearly between keys; it holds before the first and after the last. Works in playback, ruler scrubbing and WAV. MP4 has no audio yet.
Отменить (с Ctrl)|Undo (with Ctrl)
Повторить (с Ctrl)|Redo (with Ctrl)
Играть / пауза|Play / pause
Снять инструмент|Deselect tool
Кисть меньше|Brush smaller
Кисть больше|Brush bigger
Нажмите новую клавишу на клавиатуре|Press the new key on the keyboard
Нужна физическая клавиатура. Отмена и повтор работают вместе с Ctrl.|A physical keyboard is required. Undo and redo work with Ctrl.
Общие|General
Инструменты|Tools
Импорт|Import
Экран|Display
Жесты|Gestures
Клавиатура|Keyboard
Резервные копии|Backup
О проекте|Project Info
Отразить холст по горизонтали|Flip Canvas Horizontally
Отразить холст по вертикали|Flip Canvas Vertically
Звук при протяжке по линейке|Play Audio While Scrubbing
Быстрое удаление: рисунок (иначе анимация)|Quick Layer Delete: drawing (otherwise animation)
Следовать за камерой|Follow Camera
Вернуть кадр после паузы|Reset Frame After Pause
Вперёд/назад: кадры (иначе рисунки)|Forward/Backward: frames (otherwise drawings)
Векторный режим работы|Vector Workflow
Автодобавление ключей|Auto-Add Keyframes
Правка опорной точки слоя|Layer Pivot Editing
Временные опорные точки|Temporary Pivots
Показывать траекторию движения|Show Motion Path
Быстрое выделение|Quick Selection
Авто-режим правки пикселей|Auto Pixel Edit Mode
Замыкать выделение вручную|Manually Close Selections
Курсор включён|Cursor Enabled
Предпросмотр под курсором|Cursor Hover Preview
Стирать/размазывать только выбранные слои-контуры|Only Erase/Smudge Selected Path Layers
Импортировать картинки как символы|Import Images as Symbols
Вставлять картинки в существующий рисунок|Insert Images into Existing Drawing
Импорт PSD: слои как в файле|PSD Import Mode: layers as in the file
Ограничение разрешения|Resolution Limit
Переносить замки слоёв при импорте|Import Layer Locked States
Импортировать текст как пиксельный слой|Import Text as Pixel Layer
Режим левши|Left Handed
Показывать сетку|Show Grid
Показывать контекст символа|Show Symbol Context
Прозрачность контекста символа|Symbol Context Opacity
Шахматка в миниатюрах|Checker Pattern in Thumbnails
Видимость маски выделения|Selection Mask Visibility
Цвета костей|Bones Colors
Сбросить|Reset
Рисование пальцем|Finger Drawing
Поворот холста двумя пальцами|Rotate Canvas 2 Fingers
Щипок для смены фокуса|Pinch to Refocus
Двойное касание и перетаскивание: мультивыбор|Double Tap & Drag for Multi-select
Выбор слоёв на холсте|Select Layers on Canvas
Определение касаний|Touch Detection
Касание двумя пальцами: отмена|2 Finger Tap Undo
Касание тремя пальцами: повтор|3 Finger Tap Redo
Пипетка (долгое нажатие)|Pipette (Long Press)
Помощь с фигурами|Shape Assist
Импортировать копию|Import Backup
Резервная копия вручную|Manual Backup
Мои работы|My projects
Слой без анимации: правка действует на все кадры. Чтобы анимировать, поставьте ключ кнопкой ◎ на таймлайне|Layer has no animation: edits apply to all frames. To animate, add a key with the ◎ button on the timeline
Нажмите «Назад» ещё раз, чтобы выйти в список проектов|Press Back again to return to the projects list
"""
