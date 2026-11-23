# Сообщения, подсказки и туториалы. Шаблоны: {} - подставляемое значение (число или имя), порядок в EN тот же.
PAIRS2 = """
Линейка|Ruler
Обычная линейка|Straight ruler
Циркуль|Compass
Линейка и циркуль работают только на растровом слое|Ruler and compass work only on raster layers
Градиент|Gradient
По альфе рисунка|Existing alpha
Направляющие и градиент работают только на растровом слое|Guides and gradient work only on raster layers
Градиент недоступен для деформированного слоя или текстуры больше 4 Мп|Gradient is unavailable on deformed layers or textures over 4 MP
Не удалось применить градиент|Could not apply gradient
Вставка на другой слой доступна только для трансформации; деформации и эффекты не изменены|Cross-layer paste supports transforms only; deformers and effects were not changed
Ключи не вставлены: несовместимая дорожка|Keys not pasted: incompatible track
Ключи не вставлены: занятый кадр или выход за границы|Keys not pasted: occupied frame or outside project
Ключи не вставлены: слой удалён|Keys not pasted: layer was deleted
Быстрый выбор слоя касанием|Quick layer selection by tap
Отразить X|Flip X
Отразить Y|Flip Y
Частично: прямые, овалы, прямоугольники и треугольники после удержания; без предпросмотра|Partial: lines, ovals, rectangles and triangles after holding; no preview
Касание выбирает слой под пальцем сразу, минуя группы; выделение пикселей не меняется|Tap selects the layer directly, bypassing groups; pixel selection is unchanged
Повернуть −90°|Rotate −90°
Повернуть +90°|Rotate +90°
Операция не выполнена: граница слоя, предел 2048 или недостаточно памяти; пиксели не изменены|Operation cancelled: layer boundary, 2048 limit or insufficient memory; pixels unchanged
Размер ½|Scale ½
Размер ×2|Scale ×2
Жест выделения отменён: недопустимый масштаб|Selection gesture cancelled: unsupported scale
Два пальца: поворот и масштаб выделения|Two fingers: rotate and scale the selection
{} КБ. Сохранено в папку проекта; копию в галерею создать не удалось.|{} KB. Saved in the project folder; the gallery copy could not be created.
{}×{}, {} кадров|{}×{}, {} frames
Копия .tsproj: {}|Backup .tsproj: {}
  Маркеры  кадр {}|  Markers  frame {}
 Ключ| Key
Пин {}/{}|Pin {}/{}
Не удалось подготовить холст|Could not prepare the canvas
Слой изменился: восстановление отменено|Layer changed: restore cancelled
Не удалось восстановить текстуру; история не изменена|Could not restore texture; history is unchanged
Холст {}×{}: слои масштабированы. Отмена через Undo недоступна|Canvas {}×{}: layers scaled. Undo is unavailable
Обрезка недоступна в проекте с PSD: используйте Resize|Crop is unavailable in PSD projects: use Resize
Рамка выходит за холст или меньше {} px|Bounds are outside the canvas or smaller than {} px
Холст обрезан до {}×{}. Отмена через Undo недоступна|Canvas cropped to {}×{}. Undo is unavailable
Transform Layer «Transform {}»: привяжите к нему слои через «Родитель», двигайте и вращайте его в Свойствах|Transform Layer “Transform {}”: link layers via Parent, then move and rotate it in Properties
Ожидался объект|Expected an object
Проект создан в более новой версии приложения (v{})|Project was created by a newer app version (v{})
Нет id проекта|Project ID is missing
Проект не найден|Project not found
Области деформации требуют формат версии 2|Deformation bounds require format version 2
Некорректные области групп|Invalid group bounds
Некорректный id группы|Invalid group ID
Некорректная область группы|Invalid group bounds
Некорректная координата области|Invalid bounds coordinate
Размер сетки Warp требует формат версии 3|Warp grid dimensions require format version 3
Некорректные размеры сетки Warp|Invalid Warp grid dimensions
Некорректный id Warp|Invalid Warp ID
Некорректный размер Warp|Invalid Warp grid size
Плотный Warp требует формат версии 4|Dense Warp requires format version 4
Некорректная сетка Warp|Invalid Warp grid
Нет точек Warp|Warp points are missing
Некорректное число точек Warp|Invalid number of Warp points
Некорректная точка Warp|Invalid Warp point
Повреждённый файл проекта: {}|Damaged project file: {}
Лишние данные в позиции {}|Extra data at position {}
{} (позиция {})|{} (position {})
Неожиданный конец|Unexpected end
Неожиданный символ '{}'|Unexpected character '{}'
Ожидалось {}|Expected {}
Неверное число|Invalid number
Строка не закрыта|Unterminated string
Обрыв экранирования|Incomplete escape
Массив не закрыт|Unterminated array
Ожидалась запятая или ]|Expected a comma or ]
Ожидался ключ|Expected a key
Ожидалось ':'|Expected ':'
Объект не закрыт|Unterminated object
Ожидалась запятая или }|Expected a comma or }
Нет активного GL-контекста|No active GL context
Отменено|Cancelled
Кодек не выдал ни одного кадра|The codec produced no frames
Нет EGL-конфига для кодека|No EGL configuration for the codec
Кодек завис при завершении|The codec stalled while finishing
Рендерер не готов|Renderer is not ready
Не удалось создать изображение|Could not create image
Не удалось записать GIF|Could not write GIF
Не удалось записать ZIP|Could not write ZIP
Не удалось записать PSD|Could not write PSD
Не удалось записать аудио|Could not write audio
Не удалось записать видео|Could not write video
В проекте нет аудио или оно ещё декодируется: подождите пару секунд и повторите|The project has no audio or it is still decoding: wait a few seconds and try again
Края клипа тянутся пальцем на таймлайне, выбранный клип можно перетащить за середину. Скорость и реверс уже работают для звука; для видео они заработают вместе с воспроизведением видео. Скорость и Цикл меняют и время ключей анимации слоя.|Drag clip edges on the timeline, or drag the middle to move the selected clip. Speed and reverse work for audio; video support is in development. Speed and Loop also change the layer animation key timing.
{} КБ. Сохранено в папку проекта.|{} KB. Saved in the project folder.
{} КБ|{} KB
{} МБ|{} MB
{} ГБ|{} GB
Цвет и толщина кисти|Brush color and width
Сначала коснитесь узла выбранного незаблокированного слоя|First tap a node on the selected unlocked layer
Цвет и толщина контура обновлены|Path color and width updated
Подготовить область|Prepare bounds
Включить для группы|Enable for group
Деформация пропущена: неподдерживаемое содержимое или область|Deformation skipped: unsupported content or bounds
Экспорт остановлен: неподдерживаемая деформация или область|Export stopped: unsupported deformation or bounds
Раздел X|X divisions
Плотный Warp: статический, FX выкл|Dense Warp: static, FX off
Плотный Warp: только обычный слой и FX выкл|Dense Warp requires a regular layer and FX off
Плотный Warp недоступен при ключах точек|Dense Warp is unavailable when control points have keys
Плотный Warp несовместим с содержимым слоя|Dense Warp is incompatible with this layer
Раздел Y|Y divisions
Более 3 делений: в разработке|More than 3 divisions: in development
Изменение сетки недоступно: есть временные правки точек|Grid change is unavailable: points have temporary edits
Сетка не изменена: форма или ключи несовместимы|Grid unchanged: shape or keys are incompatible
Экспорт остановлен: неподдерживаемая групповая деформация или область|Export stopped: unsupported group deformation or bounds
Не удалось сохранить видео|Could not save video
Сначала подготовьте область группы|Prepare group bounds first
Сначала сбросьте деформации группы|Reset the group deformers first
Групповая деформация недоступна для этого содержимого или области|Group deformation is unavailable for this content or bounds
Групповая деформация пропущена: неподдерживаемое содержимое или область|Group deformation skipped: unsupported content or bounds
Удалить область|Remove bounds
Область группы удалена|Group bounds removed
Область группы уже сохранена; деформация в разработке|Group bounds are already saved; deformation is in development
Нет видимых непустых слоёв для области группы|No visible nonempty layers to define group bounds
Область группы сохранена; деформация в разработке|Group bounds saved; deformation is in development
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
Пипетка: коснитесь холста, чтобы взять цвет|Color Picker: tap the canvas to pick a color
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
Пипетка: коснитесь холста, чтобы взять цвет для эффекта|Color Picker: tap the canvas to pick a color for the effect
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
Пипетка: коснитесь холста, чтобы взять цвет|Color Picker: tap the canvas to pick a color
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
Пипетка (долгое нажатие)|Color Picker (Long Press)
Помощь с фигурами|Shape Assist
Импортировать копию|Import Backup
Резервная копия вручную|Manual Backup
Мои работы|My projects
Слой без анимации: правка действует на все кадры. Чтобы анимировать, поставьте ключ кнопкой ◎ на таймлайне|Layer has no animation: edits apply to all frames. To animate, add a key with the ◎ button on the timeline
Нажмите «Назад» ещё раз, чтобы выйти в список проектов|Press Back again to return to the projects list
Сначала обведите видимые слои лассо|First lasso visible layers
Для привязки к кости выберите видимые слои|Select visible layers to bind to a bone
Маркер|Marker
Аэрограф|Airbrush
Карандаш|Pencil
Скринтон мелкий|Fine Screentone
Квадратный маркер|Square Marker
Ручка (Ink)|Ink Pen
Плотный маркер|Dense Marker
Скринтон крупный|Coarse Screentone
Пиксельная|Pixel Brush
Градиентная|Soft Brush
Штриховка ═|Horizontal Hatching
Штриховка ║|Vertical Hatching
Жёсткий ластик|Hard Eraser
Мягкий ластик|Soft Eraser
Квадратный ластик|Square Eraser
Сердце|Heart
Звезда|Star
Квадрат|Square
Треугольник|Triangle
Стрелка|Arrow
Диалог|Speech Bubble
Фигура: Круг|Shape: Circle
Фигура: Сердце|Shape: Heart
Фигура: Звезда|Shape: Star
Фигура: Квадрат|Shape: Square
Фигура: Треугольник|Shape: Triangle
Фигура: Стрелка|Shape: Arrow
Фигура: Диалог|Shape: Speech Bubble
В разработке|Coming soon
Недоступные пункты помечены как находящиеся в разработке.|Unavailable settings are marked as coming soon.
Рекомендуем начать с учебного проекта|Start with the tutorial project
Готовые фигуры и подсказки помогут освоить редактор.|Ready-made shapes and hints help you learn the editor.
Начать урок|Start tutorial
Не удалось создать учебный проект|Could not create the tutorial project
Язык интерфейса меняется сразу.|The interface language changes immediately.
Фигура «Круг» добавлена|Shape “Circle” added
Фигура «Круг» добавлена в векторный слой|Shape “Circle” added to the vector layer
Фигура «Квадрат» добавлена|Shape “Square” added
Фигура «Квадрат» добавлена в векторный слой|Shape “Square” added to the vector layer
Фигура «Треугольник» добавлена|Shape “Triangle” added
Фигура «Треугольник» добавлена в векторный слой|Shape “Triangle” added to the vector layer
Фигура «Звезда» добавлена|Shape “Star” added
Фигура «Звезда» добавлена в векторный слой|Shape “Star” added to the vector layer
Фигура «Стрелка» добавлена|Shape “Arrow” added
Фигура «Стрелка» добавлена в векторный слой|Shape “Arrow” added to the vector layer
Фигура «Сердце» добавлена|Shape “Heart” added
Фигура «Сердце» добавлена в векторный слой|Shape “Heart” added to the vector layer
Фигура «Диалог» добавлена|Shape “Speech Bubble” added
Фигура «Диалог» добавлена в векторный слой|Shape “Speech Bubble” added to the vector layer
Ячеек по ширине холста|Cells across the canvas width
Хромакей|Chroma Key
Допуск|Tolerance
Мягкость|Softness
Экспозиция (EV)|Exposure (EV)
Тон, °|Hue, °
Степень|Amount
Размытие по Гауссу|Gaussian Blur
Радиус|Radius
Радиальное размытие|Zoom Blur
Размытие в движении|Motion Blur
Длина|Length
Угол, °|Angle, °
Пикселизация|Pixelate
Размер пикселя|Pixel Size
Плитки|Tiles
Повторов|Repeats
Зеркало|Mirror
Режим (0 гор, 1 верт, 2 оба)|Mode (0 horizontal, 1 vertical, 2 both)
Волна|Wave
Амплитуда|Amplitude
Длина волны|Wavelength
Направление (0 / 1)|Direction (0 / 1)
Свечение|Glow
Тень|Drop Shadow
Смещение X|Offset X
Смещение Y|Offset Y
Размытие|Blur
Непрозрачность|Opacity
Виньетка|Vignette
Шум|Noise
Размер зерна|Grain Size
Частота, 1/с|Frequency, 1/s
RGB-разрыв / Глитч|RGB Split / Glitch
Сдвиг X|Shift X
Сдвиг Y|Shift Y
Глитч, px|Glitch, px
Частота глитча, 1/с|Glitch Frequency, 1/s
Пульсация|Pulse
Масштаб ±|Scale ±
Частота, Гц|Frequency, Hz
Непрозр. ±|Opacity ±
Центр X|Center X
Центр Y|Center Y
Карта смещения|Displacement Map
Сила, px|Strength, px
Масштаб карты, px|Map Scale, px
Вращение|Rotation
Скорость, °/с|Speed, °/s
Шум и Движение|Noise and Motion
Стилизация|Stylize
Искажение|Distortion
Добавлено: {}. Проект продлён до конца звука|Added: {}. Project extended to the end of the audio
Добавлено: {}. Проект продлён до максимума, конец звука не поместился|Added: {}. Project extended to its maximum duration; the end of the audio does not fit
Разрезано клипов: {}|Clips split: {}
Поставьте плейхед внутри клипа каждого выбранного слоя|Place the playhead inside every selected layer’s clip
Первый проект за 6 шагов|Your first project in 6 steps
Попробуйте тестовый PSD|Try the test PSD
Меню действий|Actions menu
меню действий|actions menu
Меню открыто, кнопка тестового PSD подсвечена. Нажмите её сами для эксперимента или закройте меню и продолжите другие уроки. Загрузка заменит текущий PSD и его анимацию.|The menu is open and Test PSD is highlighted. Tap it yourself to experiment, or close the menu and continue with other tutorials. Loading it replaces the current PSD and its animation.
Камера: Pan и Zoom|Camera: Pan and Zoom
Движение и приближение кадра|Move and zoom the frame
Добавьте камеру|Add a camera
В меню действий выберите «Камера (Pan & Zoom)». Камера — отдельный слой, её положение и масштаб можно анимировать ключами.|Choose “Camera (Pan & Zoom)” in the Actions menu. The camera is a separate layer; its position and scale can be animated with keys.
Настройте движение|Set up motion
Выберите камеру, измените X, Y или Scale в свойствах и поставьте ключи на таймлайне. Движение вида пальцами без выделения не меняет экспорт.|Select the camera, change X, Y or Scale in Properties, and add keys on the timeline. Moving the view with fingers without a selection does not change the export.
Магнит таймлайна|Timeline magnet
Привязка плейхеда|Playhead snapping
Включите магнит|Enable snapping
Нажмите «Магнит» на панели таймлайна. При перетаскивании плейхед притягивается к ключам, маркерам и краям клипов.|Tap “Magnet” on the timeline. The playhead snaps to keys, markers and clip edges when dragged.
панель таймлайна|timeline bar
Выключите магнит|Disable snapping
Нажмите кнопку ещё раз, чтобы свободно перемещать плейхед.|Tap the button again to move the playhead freely.
Функция в разработке|Feature coming soon
Текущий статус|Current status
Кнопка «Иерархия трансформаций» на таймлайне пока не реализована. Для группировки используйте доступные группы и связи слоёв.|The “Transform hierarchy” timeline button is not implemented yet. Use available groups and layer links for grouping.
Ключи и траектория|Keys and motion path
Ключи работают; траектория в разработке|Keys are available; motion path is coming soon
Выберите слой и добавьте ключи на разных кадрах кнопкой Add Key. Перемещайте ключи выделением на таймлайне.|Select a layer and add keys on different frames using Add Key. Select keys on the timeline to move them.
Кнопка «Режим ключей» и отображение траектории движения пока не реализованы.|The “Key mode” button and motion path display are not implemented yet.
Кнопка в разработке|Button coming soon
Кнопка «Расширить» пока не реализована. Границы клипа можно менять перетаскиванием его краёв на таймлайне.|The “Extend” button is not implemented yet. You can change clip boundaries by dragging their edges on the timeline.
Подготовка аудио…|Preparing audio…
Время подготовки аудио истекло|Audio preparation timed out
Не удалось подготовить аудио|Could not prepare audio
Не удалось подготовить волну аудио|Could not prepare the audio waveform
Волна не появилась на таймлайне: откройте дорожку; импортированный файл сохранён|The waveform did not appear on the timeline: open the track; the imported file was saved
Автоключ трансформации недоступен при ретайминге клипа|Automatic transform keys are unavailable when the clip is retimed
Автоключ этого параметра эффекта недоступен|Automatic keys are unavailable for this effect parameter
Слои, кости в режиме анимации и эффекты; ретайминг и деформации не поддерживаются|Layers, bones in animation mode, and effects; retiming and deformers are not supported
Кисть и ластик: при выключении рисует только перо; остальные растровые инструменты не меняются|Brush and eraser: turning this off limits drawing to a stylus; other raster tools are unchanged
Круг кисти и инструментов деформации во время касания|Brush and deformer cursor during touch
Выбор слоёв касанием холста; кости и пипетка не затрагиваются|Select layers by tapping the canvas; bones and the Color Picker are unaffected
Превью пера над выбранным растровым слоем; зависит от поддержки наведения устройством|Pen preview over the selected raster layer; requires device hover support
Удерживайте неподвижно в режиме Transform; при рисовании и деформации жест отключён|Hold still in Transform mode; disabled while drawing or deforming
Дважды нажмите, чтобы сбросить вид холста|Double-tap to reset the canvas view
Двойной тап по кнопке фокуса на верхней панели; жест щипка не меняется|Double-tap the focus button in the top bar; pinch gestures are unchanged
При воспроизведении центрирует выбранный слой или кость; не меняет экспорт|During playback, centers the selected layer or bone; export is unchanged
Только корневой слой или кость: без родителя, камеры проекта и ретайминга; не экспортируется|Root layer or bone only: no parent, project camera or retiming; not exported
Для растрового лассо: верните конец к отмеченному началу, прежде чем отпустить; остальные способы выделения не меняются|For raster lasso: return the end to the marked start before releasing; other selection methods are unchanged
Лассо не замкнуто: верните конец к началу и отпустите|Lasso is not closed: return the end to the start and release
Для растрового лассо: замкните у начала или продолжайте от конца; остальные способы выделения не меняются|For raster lasso: close at the start or continue from the end; other selection methods are unchanged
Продолжите от конца контура или коснитесь начала; отмена — в панели инструмента|Continue from the end or tap the start; cancel from the tool panel
Контур слишком длинный; начните заново|Contour is too long; start again
Отменить контур|Cancel contour
Transform: двойной тап и протяжка выбирают видимые незаблокированные слои по центру рамки; без костей|Transform: double-tap and drag selects visible unlocked layers by their center; bones excluded
В рамке нет доступных слоёв|No available layers inside the marquee
Выбрано слоёв: {}|Selected layers: {}
Защита от ладони: касания пальцем игнорируются во время касания пера и 1,2 секунды после него; наведение не блокирует палец|Palm rejection: finger touches are ignored during pen contact and for 1.2 seconds afterward; hovering does not block touch
Частично: только прямая растровой кистью после удержания и отпускания; овалы и прямоугольники — в разработке|Partial: straight lines with the raster brush after holding and releasing; ovals and rectangles are in development
Вырезать в новый слой|Cut to new layer
Копировать в новый слой|Copy to new layer
Подготовка выделения|Preparing selection
Создаём слой, пожалуйста, подождите|Creating layer, please wait
Пока доступны только корневые PNG без анимации, трансформаций, эффектов и групп|Only root PNG layers without animation, transforms, effects or groups are supported for now
Не удалось прочитать пиксели слоя|Could not read layer pixels
Размер слоя изменился: выделите область заново|The layer size changed; make the selection again
Под выделением пусто или слой слишком велик|The selection is empty or the layer is too large
Не удалось сохранить выделение|Could not save the selection
Слой изменился: повторите операцию|The layer changed; try again
Выделение вырезано в новый слой|Selection cut to a new layer
Выделение скопировано в новый слой|Selection copied to a new layer
Общая кривая|Shared curve
Фрагмент перенесён на отдельный слой без обрезки|Fragment moved to a separate layer without clipping
На целевом кадре уже есть ключ: перенос отменён|A key already exists at the destination; move cancelled
часть|part
Перенос за границу слоя пока не поддерживается; пиксели не изменены|Moving beyond the layer bounds is not supported yet; pixels were not changed
Частично: прямые, овалы и прямоугольники растровой кистью после удержания и отпускания; без предпросмотра|Partial: raster brush lines, ovals and rectangles after holding and releasing; no preview
GL не ответил: восстановление отменено без изменений|GL did not respond; restore was cancelled without changes
Перенос превышает безопасный предел размера или памяти; пиксели не изменены|Move exceeds the safe size or memory limit; pixels were not changed
Не удалось безопасно восстановить текстуру; сохранение приостановлено|Could not safely restore the texture; saving is paused
Не удалось сохранить рисунок; проект не сохранён|Could not save the drawing; the project was not saved
Не удалось сохранить проект; повторите сохранение|Could not save the project; try saving again
Выберите растровый слой|Select a raster layer
Этот тип слоя пока не поддерживается|This layer type is not supported yet
Слой должен быть видимым и находиться вне группы|The layer must be visible and outside a group
Свойства слоя пока не поддерживаются для этой операции|Layer properties are not supported for this operation yet
Связанные слои пока не поддерживаются|Linked layers are not supported yet
Слой с изменённым клипом пока не поддерживается|A layer with a modified clip is not supported yet
Слой с ключами анимации пока не поддерживается|A layer with animation keys is not supported yet
Трансформация слоя пока не поддерживается|Layer transforms are not supported yet
Деформация слоя пока не поддерживается|Layer deformation is not supported yet
Эффекты слоя пока не поддерживаются|Layer effects are not supported yet
Обтравочные слои в проекте пока не поддерживаются|Clipping layers in the project are not supported yet
Выберите один слой PNG|Select one PNG layer
Недостаточно запаса памяти для переноса; пиксели не изменены|Not enough memory for the move; pixels were not changed
Размер после переноса превышает 2048 пикселей или выделение некорректно; пиксели не изменены|The result exceeds 2048 pixels or the selection is invalid; pixels were not changed
Для переноса за границы нужен PNG; GIF и другие форматы пока не поддерживаются|Moving beyond the layer requires PNG; GIF and other formats are not supported yet
Не удалось перенести: предел 2048 пикселей или недостаточно памяти; пиксели не изменены|Cannot move: 2048 pixel limit or insufficient memory; pixels were not changed
Для отделения нужен PNG; GIF и другие форматы пока не поддерживаются|Separating requires PNG; GIF and other formats are not supported yet
Подождите завершения операции|Please wait for the current operation to finish
Остановите воспроизведение перед отделением|Stop playback before separating the selection
Сначала замкните или отмените контур лассо|Close or cancel the lasso outline first
Сначала выделите область|Select an area first
Недостаточно запаса памяти для отделения; пиксели не изменены|Not enough memory to separate the selection; pixels were not changed
Не удалось загрузить новый слой; пиксели не изменены|Could not load the new layer; pixels were not changed
Не удалось отделить выделение; пиксели не изменены|Could not separate the selection; pixels were not changed
Невозможно безопасно сохранить масштаб слоя; пиксели не изменены|Cannot safely preserve layer scale; pixels were not changed
Ключи совпадают после изменения: операция отменена|Keyframes would collide; operation cancelled
Слой и его группы должны быть видимы и разблокированы; группы PSD пока не поддерживаются|The layer and its groups must be visible and unlocked; PSD groups are not supported yet
Перспектива для групп: в разработке|Perspective for groups: coming soon
Warp для групп: в разработке|Warp for groups: coming soon
Сетка 4×4 · плотность: в разработке|Grid 4×4 · density: coming soon
Остановите воспроизведение перед редактированием эффектов группы|Stop playback before editing group effects
Групповые эффекты с автоключом пока не поддерживаются|Group effects with auto-key are not supported yet
Групповые эффекты недоступны: проверьте одинаковость цепочек, ключи, обтравку и блокировки|Group effects unavailable: check matching effect chains, keys, clipping and locks
Группа: цепочки различаются или есть неподдерживаемые слои, ключи, обтравка или блокировка|Group: chains differ or layers, keyframes, clipping or locks are unsupported
Группа: статические эффекты применяются ко всем вложенным слоям; автоключ пока не поддерживается|Group: static effects apply to all nested layers; auto-key is not supported yet
Группа: ключи эффектов создаются на вложенных слоях; ромба группы пока нет|Group: effect keys are created on child layers; no group diamond yet
Ключи эффектов на вложенных слоях|Key effects on child layers
Нельзя поставить групповые ключи: проверьте эффекты и совпадение дорожек|Cannot key group effects: check effects and matching tracks
Ключи эффектов поставлены на вложенных слоях|Effect keys added to child layers
Ключи группы нельзя сдвинуть: нет ключа, предел или совпадение кадров|Cannot move group keys: no key, boundary or frame collision
Нет совпадающих ключей эффектов группы на этом кадре|No matching group effect keys on this frame
Ключ −1|Key −1
Ключ +1|Key +1
Группы|Groups
Границы клипа меняются перетаскиванием его краёв на таймлайне. Кнопка «Разделить» разрезает клип на текущем кадре.|Drag clip edges on the timeline to change its bounds. The Split button cuts the clip at the current frame.
Границы клипа на таймлайне|Clip bounds on the timeline
Края клипа|Clip edges
Жёлтый маркер на холсте перетаскивается; «Пивот в центр» ставит точку в центр рамки. Пивот сохраняется в проекте и учитывается в экспорте.|Drag the yellow marker on the canvas; Pivot to Center puts the point in the middle of the frame. The pivot is saved in the project and used in export.
Выделите слои и соберите их в группу. Группа вращается и масштабируется вокруг своей опорной точки.|Select layers and group them. The group rotates and scales around its pivot point.
Траектория|Motion path
Включите «Показывать траекторию» в настройках: путь опорной точки слоя рисуется на холсте. Траектория не экспортируется.|Turn on Show Motion Path in settings: the layer pivot path is drawn on the canvas. The path is not exported.
Кнопка «Родитель» в инспекторе: слой повторяет сдвиг, поворот и масштаб родителя.|Parent button in the inspector: the layer follows the parent's move, rotation and scale.
Ключи и траектория движения|Keys and motion path
Опорная точка|Pivot point
Связи|Links
Группы, связи и опорные точки|Groups, links and pivots
Совместимый экспорт видео|Compatible video export
Маркеры форм|Form markers
Hold (резко)|Hold (instant)
Hold - резкая смена формы. Плавно/Линейно - кадры символа между двумя ключами проигрываются по кривой.|Hold is an instant form change. Smooth/Linear play the symbol frames between two keys along a curve.
В проекте|In the project
Время сборки кадра (UI) и отрисовки (GL), доля медленных кадров, слои вне экрана и проходы эффектов|Frame build time (UI) and draw time (GL), share of slow frames, off-screen layers and effect passes
Вставлено ключей времени: {}|Time keys pasted: {}
Вставлено маркеров: {}|Markers pasted: {}
Дубликат не помещается в длительность|The duplicate does not fit in the duration
Ключей: {}. Новый ключ из маркера - Hold: символ мгновенно переключается на форму и держит её до следующего ключа. Без ключей символ стоит на первом кадре.|Keys: {}. A new key from a marker is Hold: the symbol instantly switches to that form and holds it until the next key. Without keys the symbol stays on its first frame.
Ключи времени не вставлены: выход за границы или символ удалён|Time keys not pasted: out of range or the symbol was deleted
Маркеров удалено: {}|Markers deleted: {}
Маркеров: {}|Markers: {}
Маркеры не выбраны|No markers selected
Маркеры не изменены: занятый кадр или выход за границы символа|Markers unchanged: the frame is taken or outside the symbol
Маркеры отзеркалены|Markers mirrored
Маркеры продублированы: {}|Markers duplicated: {}
Метрики: прокрутите или запустите Play|Metrics: scrub or press Play
На этом кадре нет ключа времени: сначала выберите маркер|No time key on this frame: pick a marker first
Панель метрик производительности|Performance HUD
Переход к следующему ключу: {}|Transition to the next key: {}
Переход: {} (ключей: {})|Transition: {} (keys: {})
Переход:|Transition:
Плавно|Smooth
Пока пусто: импортированные картинки и рисунки слоёв появятся здесь|Empty for now: imported pictures and layer drawings will appear here
Символ без ключей держит первый кадр (Hold)|A symbol without keys holds its first frame (Hold)
Символ без ключей крутит свою анимацию по кругу|A symbol without keys loops its own animation
Скопировано маркеров: {}|Markers copied: {}
Скрыто служебных файлов (кэш миниатюр, Liquify, кадры анимации): {}|Hidden service files (thumbnail cache, Liquify, animation frames): {}
Скрыть файлы удалённых слоёв ({})|Hide files of deleted layers ({})
Файлы удалённых слоёв ({})|Files of deleted layers ({})
Цикл без ключей (внутренняя анимация по кругу)|Loop without keys (inner animation repeats)
кадр 1 (Hold)|frame 1 (Hold)
по кругу (цикл)|looping
Кадр (UI): ср {} / p95 {} мс · GL: {} мс · медленных {}% · слоёв {}, вне экрана {} · FX в рамке {}, проходов {}, из кэша {}|Frame (UI): avg {} / p95 {} ms · GL: {} ms · slow {}% · layers {}, off-screen {} · FX scissored {}, passes {}, cached {}
«{}» с кадра {} (Hold)|“{}” from frame {} (Hold)
Маркеры не сдвинуты: на целевом кадре слоя «{}» уже есть другой рисунок|Markers not moved: layer “{}” already has another drawing on the target frame
Маркеры отзеркалены: формы поменялись местами вместе с ними|Markers mirrored: their forms swapped places with them
Не удалось нарисовать символ «{}»: прежняя картинка сохранена, повтор при следующем кадре|Could not draw symbol “{}”: the previous picture is kept, retrying on the next frame
В выделении нет ключей: выделите ключи рамкой (кнопка выделения на таймлайне)|No keys in the selection: select keys with a marquee (selection button on the timeline)
Выделенные формы ещё не выбраны ни одним ключом времени: выберите маркер в панели символа на сцене|No time key picks the selected forms yet: pick a marker in the symbol panel on the stage
Кривая - для ключей слоёв; переход ключей времени символа задаётся кнопками «Переход»|The curve applies to layer keys; symbol time key transitions are set with the “Transition” buttons
На том же месте ещё экземпляров этого символа: {}. Каждый показывает свою форму - лишний удалите или скройте, иначе видны две формы сразу.|More instances of this symbol at the same spot: {}. Each shows its own form - delete or hide the extra one, otherwise two forms are visible at once.
Не хватает памяти GPU для всех форм символа: часть форм показывается с задержкой|Not enough GPU memory for all symbol forms: some forms appear with a delay
Общая кривая: ключи времени символа|Shared curve: symbol time keys
Общий переход: {} (ключей: {})|Shared transition: {} (keys: {})
Переход от каждого выделенного ключа к следующему. Между разными формами символ всегда меняет форму резко (Hold) - промежуточные формы не показываются.|Transition from each selected key to the next. Between different forms the symbol always switches sharply (Hold) - intermediate forms are never shown.
Ось растяжения|Stretch Axis
Качание|Swing
Масштаб|Scale
Угол 1, °|Angle 1, °
Угол 2, °|Angle 2, °
Начальная фаза|Starting Phase
Синусоида|Sine
Меандр|Square
Пила|Sawtooth
Вращение, Качание и Ось растяжения для группы - В разработке: сделайте из группы символ, его экземпляр качается и растягивается целиком|Spin, Swing and Stretch Axis for a group are in development: turn the group into a symbol - its instance swings and stretches as a whole
Остановите воспроизведение: ключ времени ставится на кадр паузы|Stop playback: a time key is placed on the paused frame
Импортировано как символ «Символ {}»: двойной тап открывает редактирование|Imported as symbol “Symbol {}”: double-tap opens editing
Вставлено из библиотеки: {}|Inserted from the library: {}
Область группы уже сохранена|Group bounds are already saved
Область группы сохранена: теперь нажмите «Включить для группы»|Group bounds saved: now tap “Enable for group”
Смаз действует только на выбранных слоях-контурах: выберите векторный слой|Smudge works only on selected path layers: select a vector layer
Пивот в центр|Pivot to center
Пивот по умолчанию|Default pivot
Изображение как символ|Image as symbol
Повернуть холст на 90°|Rotate canvas by 90°
Сбросить поворот холста (0°)|Reset canvas rotation (0°)
Цвет/толщина|Color/width
Кисть на векторном слое рисует примитивы (путь, линия, прямоугольник, эллипс); на растровом - пиксели|On a vector layer the brush draws primitives (path, line, rectangle, ellipse); on a raster layer - pixels
При выборе кисти/ластика слой выбирается или создаётся автоматически|Picking the brush/eraser selects or creates a layer automatically
Ластик и смаз действуют только на выбранных слоях-контурах|Eraser and smudge work only on selected path layers
Пивот действует только на время перетаскивания и не сохраняется|The pivot applies only while dragging and is not saved
Поворот после 8° двумя пальцами, снап к 0/90/180/270°; только вид, экспорт не меняется|Two-finger rotation after 8°, snaps to 0/90/180/270°; view only, export is unchanged
Опорная точка слоя перетаскивается на холсте; «Пивот в центр» в инспекторе|Drag the layer pivot on the canvas; “Pivot to center” is in the inspector
Ключи кости: {}|Bone keys: {}
Выбран ключ: {}|Selected key: {}
Ключ не выбран: тапните ◆ на таймлайне|No key selected: tap a ◆ on the timeline
◀ Предыдущий ключ|◀ Previous key
◆ Добавить ключ|◆ Add key
Следующий ключ ▶|Next key ▶
◇ Удалить ключ|◇ Delete key
Переход к следующему ключу|Transition to the next key
График…|Graph…
На этом кадре ключ кости уже есть|This frame already has a bone key
Выберите ключ кости на таймлайне|Select a bone key on the timeline
Ключей кости в этом направлении нет|No bone keys in this direction
Группа: Вращение, Качание и Ось растяжения работают у самой группы; остальные эффекты недоступны - цепочки различаются или есть неподдерживаемые слои, ключи, обтравка или блокировка|Group: Spin, Swing and Stretch Axis work on the group itself; other effects are unavailable - the chains differ or there are unsupported layers, keys, clipping or locks
Группа: Вращение, Качание и Ось растяжения - у самой группы (вокруг её пивота, ключи на строке группы); остальные эффекты - на вложенных слоях|Group: Spin, Swing and Stretch Axis apply to the group itself (around its pivot, keys on the group row); other effects go to the nested layers
✕ Снять выделение|✕ Deselect
Имя кости|Bone name
Например: Предплечье|For example: Forearm
Переименовать кость|Rename bone
Сцены|Scenes
Сцена|Scene
Вне сцен|Outside scenes
Сцены делят общий таймлайн на части: переход, Play и экспорт по сцене. Удаление сцены не удаляет кадры и ключи.|Scenes split the shared timeline into parts: jump, Play and export per scene. Deleting a scene keeps its frames and keys.
Сцен пока нет: «+ Новая сцена» сделает текущий таймлайн первой сценой.|No scenes yet: “+ New scene” turns the current timeline into the first scene.
Имя сцены|Scene name
{} – {} · {} кадров|{} – {} · {} frames
Переименовать сцену|Rename scene
Удалить сцену|Delete scene
+ Новая сцена|+ New scene
Разрезать на плейхеде|Split at playhead
◀ Предыдущая|◀ Previous
Следующая ▶|Next ▶
Play только в текущей сцене|Play only the current scene
Экспорт только текущей сцены|Export only the current scene
Только сцена|Only scene
Слишком много сцен|Too many scenes
Проект достиг максимальной длительности|The project has reached its maximum length
Сцена добавлена в конец таймлайна|Scene added at the end of the timeline
Поставьте плейхед внутрь сцены, не на её первый кадр|Put the playhead inside a scene, not on its first frame
Сцена разрезана на плейхеде|Scene split at the playhead
Сцена удалена, кадры остались на таймлайне|Scene deleted, its frames stay on the timeline
Сцен в этом направлении нет|No scenes in this direction
Нельзя: экземпляр символа не может лежать внутри своего определения|Not allowed: a symbol instance cannot be inside its own definition
Слой перенесён в группу и получил её эффекты|Layer moved into the group and received its effects
Расширить клип|Extend clip
Весь таймлайн|Whole timeline
Управление сценами…|Manage scenes…
Открыта сцена: Play, переходы и экспорт - в её границах|Scene opened: Play, navigation and export stay inside it
Показан весь таймлайн|Showing the whole timeline
Режим ключей: показаны только слои с ключами|Key mode: only layers with keys are shown
Режим ключей выключен|Key mode off
Ключей в этом направлении нет|No keys in this direction
На этом кадре ключей нет|No keys on this frame
Ключей нет|No keys
Все привязки сняты|All links removed
Клип уже занимает всю сцену|The clip already fills the scene
Клип расширен до конца сцены|Clip extended to the end of the scene
Клип расширен до конца проекта|Clip extended to the end of the project
Слоёв с ключами: {}|Layers with keys: {}
◀ Ключ|◀ Key
Ключ ▶|Key ▶
Ключи кадра|Frame keys
Все ключи|All keys
Все слои|All layers
Привязанный слой повторяет сдвиг, поворот и масштаб родителя поверх своей трансформации; группы тоже передают трансформацию вложенным слоям.|A linked layer repeats its parent's move, rotation and scale on top of its own transform; groups also pass their transform to nested layers.
Выбран слой|Selected layer
нет|none
Выбрать…|Choose…
Цепочка привязок|Link chain
Ведёт слоёв|Drives layers
Выберите слой, чтобы назначить ему родителя.|Select a layer to assign its parent.
Все привязки|All links
Привязок пока нет.|No links yet.
Отвязать всё|Unlink all
Копия настроек сохранена|Settings backup saved
Не удалось сохранить копию настроек|Could not save the settings backup
Настройки восстановлены из копии|Settings restored from the backup
Это не файл настроек VibeToon|This is not a VibeToon settings file
Настройки отображения сброшены|Display settings reset
«+ Изображение» сразу создаёт символ: картинка - определение, на холсте - экземпляр|“+ Image” creates a symbol right away: the picture is the definition, the canvas gets an instance
Секвенция обрезана до {} кадров (больше за раз не импортируется)|Sequence trimmed to {} frames (more cannot be imported at once)
Секвенция ({} кадров) импортирована как символ «Символ {}»|Sequence ({} frames) imported as symbol “Symbol {}”
Секвенция импортирована: {} кадров. Проект продлён до конца секвенции|Sequence imported: {} frames. Project extended to the end of the sequence
Секвенция импортирована: {} кадров|Sequence imported: {} frames
Не удалось импортировать секвенцию|Could not import the sequence
Секвенция картинок|Image sequence
Секвенция картинок (покадровый слой)|Image sequence (frame-by-frame layer)
Секвенция картинок как символ|Image sequence as symbol
PSD проекта уже разложен на слои; вставка другого PSD из библиотеки - в разработке|The project PSD is already split into layers; inserting another PSD from the library is coming soon
Не удалось вставить ассет|Could not insert the asset
Файл ассета не найден|Asset file not found
Пока пусто: импортированные картинки, видео, аудио и рисунки слоёв появятся здесь|Empty for now: imported images, videos, audio and layer drawings will appear here
Картинки|Images
Без звука|Mute
В этом видео нет звуковой дорожки|This video has no audio track
Звук слоя выключен|Layer sound off
Звук слоя включён|Layer sound on
Ключей: {}. Громкость меняется линейно между ключами; до первого и после последнего держится. Работает при воспроизведении, прослушивании линейки и в WAV (у видео - его звуковая дорожка). В MP4 звука пока нет.|Keys: {}. Volume changes linearly between keys; it holds before the first and after the last. Works in playback, ruler scrubbing and WAV (for video - its own audio track). MP4 has no audio yet.
Список ключей|Key list
Всем ключам…|All keys…
Дорожек на кадре|Tracks on this frame
Своя кривая|Custom curve
Только деформации|Deformers only
У этого слоя пока нет ключей|This layer has no keys yet
Удалить ключ|Delete key
Интерполяция всем ключам: {}|Interpolation applied to all keys: {}
Интерполяция ключа действует до следующего ключа; ключ слоя задаёт её всем его дорожкам на этом кадре (трансформация и деформации). Тап по строке - перейти к ключу.|A key's interpolation lasts until the next key; a layer key sets it for all of the layer's tracks on that frame (transform and deformers). Tap a row to go to the key.
Отражение только вида холста; экспорт не меняется|Flips the canvas view only; export is unchanged
Ключей: {}|Keys: {}
Видео {}×{}, {} кадров, со звуком (AAC, стерео 44,1 кГц)|Video {}×{}, {} frames, with sound (AAC, stereo 44.1 kHz)
Все дорожки проекта (и звук видео-слоёв) сводятся в один WAV (стерео 44,1 кГц) на всю длительность проекта или сцены. Дорожки «Без звука» пропускаются.|All project tracks (and the sound of video layers) are mixed into one WAV (stereo 44.1 kHz) for the whole project or scene. Muted tracks are skipped.
Звук|Sound
Звук в видео|Sound in video
Звук…|Sound…
Звук добавить не удалось - видео сохранено без звука|Could not add sound - the video was saved without sound
Со звуком: AAC, стерео 44,1 кГц|With sound: AAC, stereo 44.1 kHz
Кодирование и сведение звука: {}%|Mixing and encoding sound: {}%
Картинки при импорте уменьшаются до длинной стороны холста (512-2048 px): меньше памяти и файлов проекта; видео не меняется|Imported images are downscaled to the canvas's long side (512-2048 px): less memory and smaller project files; videos are unchanged
Панель инструмента показана|Tool panel shown
Панель инструмента скрыта: тап по нему ещё раз вернёт её|Tool panel hidden: tap the tool again to bring it back
Ключей: {}. Громкость меняется линейно между ключами; до первого и после последнего держится. Работает при воспроизведении, прослушивании линейки, в WAV и в звуке MP4 (у видео - его звуковая дорожка).|Keys: {}. Volume changes linearly between keys; it holds before the first and after the last. Works in playback, ruler scrubbing, WAV and MP4 sound (for video - its own audio track).
Файл не похож на проект VibeToon: нужен .tsproj, .zip с project.json, project.json или PSD|The file does not look like a VibeToon project: use .tsproj, a .zip with project.json, project.json or a PSD
Проект создан в более новой версии VibeToon: обновите приложение и повторите импорт|The project was created in a newer version of VibeToon: update the app and import again
Файл проекта повреждён или обрезан: project.json не читается|The project file is damaged or truncated: project.json cannot be read
Архив слишком большой или содержит слишком много файлов|The archive is too large or contains too many files
Недостаточно места на устройстве для импорта|Not enough storage space on the device to import
Не удалось прочитать выбранный файл|Could not read the selected file
Импорт проекта…|Importing project…
Файл распаковывается и проверяется. Существующие проекты не меняются.|The file is being unpacked and checked. Existing projects are not changed.
Проект с таким именем уже есть - импортированный назван по-другому, ваш проект не изменён.|A project with this name already exists - the imported one got a different name, your project is unchanged.
Все файлы проекта на месте.|All project files are in place.
Не найдено файлов: {}. Эти слои будут пустыми; данные (ключи, кости, эффекты) сохранены.|Files not found: {}. These layers will be empty; their data (keys, bones, effects) is kept.
Добавлено шрифтов: {}|Fonts added: {}
Нет шрифтов: {} - текст покажется шрифтом по умолчанию|Missing fonts: {} - the text will use the default font
Отменить импорт - удалить этот проект; остальные проекты не затрагиваются.|Undo import - delete this project; other projects are not affected.
Открыть|Open
Отменить импорт|Undo import
Закрыть|Close
Импорт не удался|Import failed
Существующие проекты не изменены.|Existing projects were not changed.
Выбрать другой файл|Choose another file
PSD откроется в новом проекте: слои загружаются|The PSD opens in a new project: loading layers
Импорт отменён: проект удалён|Import undone: the project was deleted
Звук выключен - видео без звука|Sound is off - the video will be silent
Звуковых дорожек в видео: {}|Sound tracks in the video: {}
Все звуковые дорожки «Без звука» - видео без звука|All sound tracks are muted - the video will be silent
Звук не попадает в экспортируемый отрезок - видео без звука|The sound is outside the exported range - the video will be silent
Громкость звука на нуле - видео без звука|Sound volume is at zero - the video will be silent
Файл звука не найден в проекте - видео без звука|The sound file is missing from the project - the video will be silent
Звук не удалось подготовить (файл не декодируется) - видео сохранено без звука|Could not prepare the sound (the file cannot be decoded) - the video was saved without sound
Сборка {}|Build {}
Края клипа тянутся пальцем на таймлайне, выбранный клип можно перетащить за середину. Скорость, реверс и Цикл работают для звука и видео; Цикл повторяет клип до конца проекта или сцены. Скорость и Цикл меняют и время ключей анимации слоя.|Drag clip edges on the timeline; drag the middle to move the selected clip. Speed, reverse and Loop work for sound and video; Loop repeats the clip until the end of the project or scene. Speed and Loop also change the timing of the layer's animation keys.
GIF обрезан до {} кадров (больше за раз не импортируется)|GIF trimmed to {} frames (more cannot be imported at once)
GIF ({} кадров) импортирован как символ «Символ {}»: крутится по кругу|GIF ({} frames) imported as symbol “Symbol {}”: it plays in a loop
GIF без анимации импортирован как картинка|A GIF without animation was imported as an image
GIF импортирован: {} кадров. Проект продлён до конца GIF|GIF imported: {} frames. Project extended to the end of the GIF
GIF импортирован: {} кадров|GIF imported: {} frames
GIF не прочитан: файл повреждён или слишком большой|Could not read the GIF: the file is damaged or too large
Не удалось импортировать GIF|Could not import the GIF
В GIF нет кадров|The GIF has no frames
Новый PSD: замок слоя (пиксели, положение, весь слой) - замок, замок прозрачности - «Замок прозрачности»; уже открытые проекты не меняются|New PSD: a layer lock (pixels, position, whole layer) becomes a lock, a transparency lock becomes “Alpha lock”; projects already open are not changed
Выкл.: текстовые слои нового PSD становятся редактируемым текстом (текст, размер, цвет, выравнивание; шрифт по умолчанию, поверх слоёв PSD)|Off: text layers of a new PSD become editable text (text, size, color, alignment; default font, above the PSD layers)
Текстовые слои PSD стали редактируемым текстом: {}; остались картинкой: {}|PSD text layers became editable text: {}; kept as images: {}
Текстовые слои PSD стали редактируемым текстом: {}; перенесено замков: {}|PSD text layers became editable text: {}; locks imported: {}
Текстовые слои PSD стали редактируемым текстом: {}|PSD text layers became editable text: {}
Перенесено замков слоёв PSD: {}|PSD layer locks imported: {}
Выбран слой-картинка: «+ Изображение» вписывает картинку в его рисунок на плейхеде (пропорции, по центру) вместо нового слоя; Undo возвращает рисунок|With an image layer selected, “+ Image” fits the picture into its drawing at the playhead (keeping proportions, centered) instead of adding a new layer; Undo restores the drawing
Картинка вставлена в рисунок «{}» на кадре {}|Picture inserted into the drawing “{}” at frame {}
Слой изменился во время вставки: картинка не вставлена|The layer changed during insertion: the picture was not inserted
Не удалось прочитать рисунок слоя|Could not read the layer drawing
Не удалось вставить картинку в рисунок|Could not insert the picture into the drawing
PNG не записан|PNG was not written
Объединить|Union
Вычесть|Subtract
Пересечь|Intersect
Исключить|Exclude
Разобрать|Release
Фигуры объединены|Shapes united
Выбранная фигура вычтена из фигуры под ней|The selected shape was subtracted from the shape below it
Осталось пересечение фигур|Only the overlap of the shapes remains
Осталось то, что не перекрывается|Only the non-overlapping parts remain
Под выбранной фигурой нет другой фигуры: операция берёт выбранную и предыдущую нарисованную|There is no shape below the selected one: the operation uses the selected shape and the one drawn before it
Линии и ломаные не участвуют в булевых операциях: нужны замкнутые фигуры|Lines and open polylines can’t be used in boolean operations: closed shapes are needed
Разобрать можно только фигуру булевой операции|Only a boolean operation shape can be released
Фигура разобрана на две|The shape was released into two shapes
Узлы фигуры булевой операции можно двигать; чтобы удалить узел, сначала нажмите «Разобрать»|Nodes of a boolean operation shape can be moved; to delete a node, tap “Release” first
Сгладить узел|Smooth node
Острый угол|Corner
Сгладить контур|Smooth path
У фигуры булевой операции ручки не правятся: сначала нажмите «Разобрать»|Handles of a boolean operation shape can’t be edited: tap “Release” first
Ручки кривых есть только у контуров пера; у линии, прямоугольника, эллипса и фигур их нет|Only pen paths have curve handles; lines, rectangles, ellipses and preset shapes don’t
У узла уже нет ручек|This node has no handles
Узел нельзя сгладить: нужен соседний узел в другом месте|The node can’t be smoothed: it needs a neighboring node at a different position
Узел сглажен: потяните ручку, чтобы изменить изгиб|Node smoothed: drag a handle to change the curve
Узел стал углом|The node is now a corner
Контур сглажен|Path smoothed
Перо: тапами ставьте точки на векторном слое, протяжка от новой точки вытягивает ручки кривой, «Готово» завершает контур, тап по первой точке замыкает|Pen: tap to place points on a vector layer, drag from a new point to pull out curve handles, “Done” finishes the outline, tapping the first point closes it
Независимые ручки|Independent handles
У узла нет ручек: сначала «Сгладить узел» или вытяните ручки пером|The node has no handles: tap “Smooth node” first or pull handles out with the pen
Ручки узла независимы: каждая двигается отдельно|The node’s handles are independent: each moves on its own
Ручки узла связаны: вторая снова напротив первой|The node’s handles are linked: the second one is opposite the first again
При редактировании символа слои главной сцены видны полупрозрачно вокруг него, выровненные по экземпляру; не выбираются и не экспортируются|While editing a symbol, main scene layers are shown semi-transparent around it, aligned to the instance; they can’t be selected and aren’t exported
Насколько видна главная сцена вокруг открытого символа (5-100%)|How visible the main scene is around the open symbol (5–100%)
Видео как символ|Video as Symbol
Видео обрезано до {} кадров (больше за раз не импортируется)|Video trimmed to {} frames (more cannot be imported at once)
Видео ({} кадров) импортировано как символ «Символ {}»: крутится по кругу|Video ({} frames) imported as symbol “Symbol {}”: it plays in a loop
{}. Звук - отдельная дорожка|{}. The sound is on a separate track
{}. Проект продлён до конца видео|{}. Project extended to the end of the video
В файле нет видео|The file contains no video
Не удалось получить кадры видео|Could not read the video frames
Не удалось импортировать видео|Could not import the video
PSD проекта уже разложен на слои: выберите другой PSD или «+ → PSD в текущий проект»|The project PSD is already split into layers: pick another PSD or “+ → PSD into current project”
Это не PSD-файл|This is not a PSD file
Не удалось вставить PSD|Could not insert the PSD
Файл PSD не найден|PSD file not found
Не удалось прочитать PSD|Could not read the PSD
В PSD нет слоёв с пикселями|The PSD has no layers with pixels
В PSD нет видимых слоёв с пикселями|The PSD has no visible layers with pixels
PSD «{}» вставлен: слоёв {}|PSD “{}” inserted: {} layers
PSD «{}» вставлен одним слоем|PSD “{}” inserted as a single layer
PSD «{}» вставлен как покадровый слой: кадров {} (больше за раз не импортируется)|PSD “{}” inserted as a frame-by-frame layer: {} frames (more cannot be imported at once)
PSD «{}» вставлен как покадровый слой: кадров {}|PSD “{}” inserted as a frame-by-frame layer: {} frames
Режим импорта PSD|PSD Import Mode
Для PSD, вставленного в текущий проект (+ → «PSD в текущий проект» или библиотека). PSD нового проекта всегда раскладывается слоями как в файле|For a PSD inserted into the current project (+ → “PSD into current project” or the library). A new project’s PSD is always split into layers as in the file
Слои как в файле|Layers as in file
Одним слоем|Single layer
Слои как кадры|Layers as frames
PSD в текущий проект|PSD into current project
Не удалось сохранить папки библиотеки|Could not save library folders
Удалить папку (файлы останутся)|Delete folder (files stay)
+ Папка|+ Folder
Долгое нажатие на папку - переименовать или удалить; на файл - переложить|Long-press a folder to rename or delete it; long-press a file to move it
Долгое нажатие на файл - положить в папку|Long-press a file to put it into a folder
Папка пуста: долгое нажатие на файл в другой папке кладёт его сюда|The folder is empty: long-press a file in another folder to put it here
Переименовать папку|Rename folder
Пустое название или такая папка уже есть|Empty name, or such a folder already exists
Сначала создайте папку: «+ Папка»|Create a folder first: “+ Folder”
Убрать из папки|Remove from folder
"""
