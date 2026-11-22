package com.vibetoon.ui.projects

import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PlayArrow
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.vibetoon.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Схема для статьи: рисуется Canvas-ом, без картинок-файлов. */
enum class DiagramKind { BONES, BIND_POSE, WARP, LAYERS, TIMELINE, MESH }

/**
 * Визуальная плашка кнопки интерфейса: значок + название + где её искать. Рисуется как настоящая кнопка
 * приложения, чтобы пользователь сразу узнал её на экране редактора. [chip] - подпись в виде чипа без значка.
 */
class UiButton(val icon: ImageVector?, val label: String, val where: String, val chip: Boolean = false, val coach: String? = null)

/** Шаг туториала: заголовок действия, пояснение, плашки кнопок и необязательный совет. */
class TutorialStep(val title: String, val text: String, val buttons: List<UiButton> = emptyList(), val tip: String? = null)

class Tutorial(
    val title: String,
    val subtitle: String,
    val color: Color,
    val diagram: DiagramKind,
    val steps: List<TutorialStep>,
    val tips: List<String> = emptyList(),
    /** true - «Пройти в редакторе» создаёт готовый учебный проект с фигурами и запускает подсказки на нём. */
    val lesson: Boolean = false
)

/** Статьи раздела «Обучение»: пошаговые гайды с плашками кнопок. Захардкожены в приложении. */
val TUTORIALS: List<Tutorial> = listOf(
    Tutorial(
        "Интерактивный урок", "Готовый проект с подсказками на кнопках", Color(0xFF26A69A), DiagramKind.TIMELINE,
        listOf(
            TutorialStep("Учебный проект", "Это готовый проект: на холсте три фигуры, каждая на своём слое. Подсказки по очереди покажут главные кнопки. Кнопка «Пропустить» закрывает урок в любой момент."),
            TutorialStep("Кисть", "Включите «Кисть», чтобы рисовать на слое-картинке. Кнопка «Новый слой» над холстом создаёт чистый слой для рисунка.",
                listOf(UiButton(Icons.Default.Brush, "Кисть", "левая панель инструментов"))),
            TutorialStep("Слои", "Справа список слоёв: глаз скрывает слой, ручка ≡ меняет порядок, замок блокирует. Зажмите строку слоя, чтобы открыть меню.",
                listOf(UiButton(null, "Слои", "правая панель", chip = true, coach = "Слои"))),
            TutorialStep("Таймлайн", "Красная линия - текущий кадр. Тяните её по линейке: у края экрана таймлайн сам прокручивается. «Играть» запускает проигрывание.",
                listOf(UiButton(Icons.Default.PlayArrow, "Играть", "панель воспроизведения"))),
            TutorialStep("Анимация фигур", "Выберите фигуру, переставьте плейхед, подвиньте фигуру и нажмите «Add Key». Между ключами движение считается само. «Лесенка» выстраивает выбранные клипы встык."),
            TutorialStep("Экспорт", "Готово? Нажмите «Export», выберите MP4, GIF или картинку и поделитесь результатом.",
                listOf(UiButton(null, "Export", "верхняя панель", chip = true, coach = "Export")))
        ),
        listOf("Урок можно повторить из раздела «Обучение» в любой момент."),
        lesson = true
    ),
    Tutorial(
        "Основы работы с холстом", "Первый проект за 5 шагов", Color(0xFF42A5F5), DiagramKind.LAYERS,
        listOf(
            TutorialStep("Создайте проект", "На экране проектов нажмите «＋» сверху, выберите пропорции и «Создать».",
                listOf(UiButton(Icons.Default.Add, "Создать проект", "правый верхний угол экрана проектов"))),
            TutorialStep("Добавьте картинку, видео или аудио", "В редакторе откройте меню действий и выберите тип файла. Картинка встанет по центру холста.",
                listOf(UiButton(Icons.Default.Menu, "Действия", "левый верхний угол редактора"))),
            TutorialStep("Двигайте объект", "Тапните по объекту, чтобы выделить его. Один палец двигает, два пальца вращают и масштабируют.",
                listOf(UiButton(Icons.Default.OpenWith, "Трансформ", "левая панель инструментов"))),
            TutorialStep("Двигайте сам холст", "Если ничего не выделено, два пальца двигают и масштабируют вид. Вернуть вид: «Сбросить вид холста» в меню действий.",
                tip = "Тап в пустое место снимает выделение."),
            TutorialStep("Отмена и повтор", "Любое действие можно отменить.",
                listOf(UiButton(Icons.Default.Undo, "Отменить", "верхняя панель"), UiButton(Icons.Default.Redo, "Повторить", "верхняя панель")))
        ),
        listOf("Проект сохраняется сам через пару секунд после правки.")
    ),
    Tutorial(
        "Как добавить и анимировать текст", "Надпись, цвет и ключевые кадры", Color(0xFFEC407A), DiagramKind.TIMELINE,
        listOf(
            TutorialStep("Добавьте текст", "Нажмите инструмент «Текст» слева, введите надпись и подтвердите.",
                listOf(UiButton(Icons.Default.TextFields, "Текст", "левая панель инструментов"))),
            TutorialStep("Настройте вид", "Выделите текст и откройте вкладку «Свойства»: шрифт, размер, цвет, градиент.",
                listOf(UiButton(null, "Свойства", "вкладка в правой панели", chip = true))),
            TutorialStep("Поставьте первый ключ", "Перейдите на нужный кадр и нажмите «Add Key» на таймлайне.",
                listOf(UiButton(null, "◆ Add Key", "первая строка таймлайна", chip = true))),
            TutorialStep("Сдвиньте и поставьте второй ключ", "Перейдите на другой кадр, подвиньте текст и снова нажмите «Add Key». Текст поедет между ключами.",
                listOf(UiButton(Icons.Default.ChevronRight, "Следующий кадр", "панель воспроизведения"))),
            TutorialStep("Проверьте результат", "Нажмите Play.",
                listOf(UiButton(Icons.Default.PlayArrow, "Играть", "панель воспроизведения")),
                tip = "Кнопка f(x)-кривой рядом с ключом меняет плавность движения.")
        ),
        listOf("Текст можно гнуть: добавьте к нему Warp.")
    ),
    Tutorial(
        "Как работают Кости и Bind Pose", "Скелет, привязка и анимация", Color(0xFF7E57C2), DiagramKind.BONES,
        listOf(
            TutorialStep("Переключитесь в режим костей", "В верхней панели выберите «Кости». Слева появятся инструменты скелета.",
                listOf(UiButton(null, "Кости", "переключатель режимов сверху", chip = true))),
            TutorialStep("Создайте первую кость", "Выберите «+Кость» и проведите пальцем по холсту: задайте длину и направление. Следующая кость, начатая у конца выбранной, станет её дочерней.",
                listOf(UiButton(Icons.Default.AddCircleOutline, "+Кость", "левая панель в режиме «Кости»"))),
            TutorialStep("Настройте скелет в Bind Pose", "Включите Bind Pose, чтобы двигать кости, не искажая картинку, и подогнать скелет под персонажа.",
                listOf(UiButton(null, "Bind Pose", "тумблер на холсте вверху слева", chip = true))),
            TutorialStep("Привяжите слои", "«Автопривязка» привязывает слои сама. Для ручной привязки: «Лассо», обведите слои руки, выберите кость и нажмите «Привязать».",
                listOf(UiButton(Icons.Default.Polyline, "Лассо", "левая панель в режиме «Кости»"), UiButton(null, "Автопривязка", "плавающая панель на холсте", chip = true))),
            TutorialStep("Анимируйте", "Переключите тумблер на Animation, выберите инструмент «Кости» и тяните конец кости.",
                listOf(UiButton(Icons.Default.OpenWith, "Кости", "левая панель в режиме «Кости»")),
                tip = "«Независимый поворот» в свойствах кости сохраняет её угол, пока родитель вращается.")
        ),
        listOf("Рисуйте кости по порядку: плечо → предплечье → кисть.")
    ),
    Tutorial(
        "Что такое Mesh и Warp", "Два способа согнуть картинку", Color(0xFFFF7043), DiagramKind.WARP,
        listOf(
            TutorialStep("Выберите слой", "Тапните по слою на холсте или в списке слоёв. Warp работает с картинками, текстом и слоями PSD."),
            TutorialStep("Включите Warp", "В левой панели прокрутите вниз и выберите «Warp». На слое появится решётка из 16 точек.",
                listOf(UiButton(Icons.Default.Transform, "Warp", "левая панель, последний инструмент"))),
            TutorialStep("Гните за точки", "Потяните любую точку: картинка плавно изогнётся. Так делают сжатие и растяжение.",
                tip = "«Сбросить» в плавающей панели убирает деформацию."),
            TutorialStep("Выберите режим FX", "FX выключен: правка меняет базовую форму. FX включён: правка пишется ключом на текущем кадре.",
                listOf(UiButton(Icons.Default.AutoAwesome, "FX", "верхняя панель и таймлайн"))),
            TutorialStep("Mesh: сетка из треугольников", "Переключитесь в режим «Сетка», выберите слой и нажмите «Сгенерировать» (или «По контуру»). Тяните вершины, режьте инструментом «Разрез», мягко сдвигайте «Смазкой».",
                listOf(UiButton(null, "Сетка", "переключатель режимов сверху", chip = true))),
            TutorialStep("Привяжите сетку к костям", "Создайте кости, затем в панели сетки нажмите «Кости»: вершины будут двигаться по весам костей, а не слой целиком.",
                tip = "Повторное нажатие «Кости» снимает привязку."),
            TutorialStep("Перспектива, Turbulent, Liquify", "«Перспектива» наклоняет слой за 4 угла. «Эффекты → Turbulent Displace» добавляет волнистость и Line Boil. «Liquify» двигает пиксели картинки пальцем.")
        ),
        listOf("Сначала настройте форму при выключенном FX, потом анимируйте.")
    ),
    Tutorial(
        "Слои и группы", "Перетаскивание, свайпы, мультивыбор", Color(0xFF26A69A), DiagramKind.LAYERS,
        listOf(
            TutorialStep("Откройте список слоёв", "Правая панель, вкладка «Слои».",
                listOf(UiButton(Icons.Default.Layers, "Слои", "правая панель"))),
            TutorialStep("Перенесите слой в группу", "Зажмите строку слоя и потяните на группу — слой станет дочерним. Потяните на слой в корне — слой выйдет из группы."),
            TutorialStep("Свайпы по слою", "Свайп влево открывает меню слоя, свайп вправо включает мультивыбор.",
                listOf(UiButton(Icons.Default.Checklist, "Мультивыбор", "свайп вправо по слою")))
        ),
        listOf("Картинки и текст в группы PSD класть нельзя.")
    ),
    Tutorial(
        "Липсинг: символ, маркеры, время", "Рот говорит под звук", Color(0xFFAB47BC), DiagramKind.TIMELINE,
        listOf(
            TutorialStep("Нарисуйте формы рта", "Включите «Кисть» и нажмите «Новый слой» в панели рисования: каждая форма рта (A, O, M, Rest...) рисуется на своём слое.",
                listOf(UiButton(Icons.Default.Brush, "Кисть", "левая панель инструментов"), UiButton(null, "Новый слой", "панель кисти на холсте", chip = true))),
            TutorialStep("Выделите слои и создайте символ", "Зайдите в мультивыбор, отметьте слои, зажмите строку слоя и выберите «Создать символ». В окне выберите «Слои как кадры»: каждый слой станет кадром символа, а на кадре появится маркер с именем слоя.",
                listOf(UiButton(Icons.Default.Checklist, "Мультивыбор", "значок над списком слоёв")),
                tip = "«Слои стопкой» оставляет всё видимым одновременно - для составного персонажа, а не для липсинга."),
            TutorialStep("Проверьте маркеры", "Двойной тап по символу (или «Редактировать символ» в меню слоя) открывает его внутри. Кнопка «Маркеры» сверху ставит имя (Rest, AI, E, O, U, MBF, L, W или своё) на текущий кадр. «◀ На главную сцену» возвращает.",
                listOf(UiButton(null, "Маркеры", "плашка «◀ На главную сцену» над холстом", chip = true))),
            TutorialStep("Добавьте звук", "«＋» → Аудио. Проект сам продлится до конца звука. Волна видна на плашке звука; перетаскивание плейхеда по линейке играет короткий фрагмент - так находят начало каждой фонемы на слух.",
                listOf(UiButton(Icons.Default.MusicNote, "Аудио", "меню «＋» над списком слоёв"))),
            TutorialStep("Выберите маркер на кадре", "Выделите символ на основной сцене: во вкладке «Свойства → Время» нажмите «Выбрать маркер». Откроется плавающая панель с картинками: перетащите её, куда удобно, спрячьте к краю или закройте крестиком. Остановите плейхед на звуке и тапните нужный рот.",
                listOf(UiButton(null, "Время", "вкладка «Свойства» у символа", chip = true)),
                tip = "Активный маркер подсвечен фиолетовой рамкой."),
            TutorialStep("Hold: мгновенное переключение", "Каждый тап по маркеру ставит ключ времени Hold: рот не перематывается, а мгновенно становится выбранным и держится до следующего ключа. Ключи видны на плашке символа фиолетовыми засечками. Иконка «ключ» ставит ключ с текущим значением вручную.",
                listOf(UiButton(Icons.Default.PlayArrow, "Играть", "панель воспроизведения")))
        ),
        listOf("Ключи можно удалить (кнопка «Убрать ключ» на текущем кадре) или очистить все: символ снова пойдёт по кругу.")
    ),
    Tutorial(
        "Таймлайн: ключи, лесенка, маркеры", "Быстрая работа со временем", Color(0xFF8D6E63), DiagramKind.TIMELINE,
        listOf(
            TutorialStep("Выделите ключи рамкой", "Включите выделение, обведите ключи, потяните — они сдвинутся вместе.",
                listOf(UiButton(Icons.Default.HighlightAlt, "Выделение ключей", "вторая строка таймлайна"), UiButton(Icons.Default.DeleteSweep, "Удалить ключи", "вторая строка таймлайна"))),
            TutorialStep("Лесенка", "Выделите несколько слоёв и нажмите «Лесенка»: клипы встанут встык: конец одного совпадёт с началом следующего.",
                listOf(UiButton(Icons.Default.Stairs, "Лесенка", "вторая строка таймлайна"))),
            TutorialStep("Размер панели", "Потяните серый «хват» над таймлайном вверх или вниз. Eject сворачивает таймлайн.",
                listOf(UiButton(Icons.Default.Eject, "Свернуть", "первая строка таймлайна")))
        ),
        listOf("Щипок двумя пальцами по таймлайну меняет масштаб времени.")
    )
)

/** Ряд карточек «Обучение» на экране проектов. */
@Composable
fun TutorialRow(onOpen: (Tutorial) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Обучение", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(TUTORIALS) { t ->
                Card(
                    modifier = Modifier.width(220.dp).clickable { onOpen(t) },
                    colors = CardDefaults.cardColors(containerColor = t.color.copy(alpha = 0.14f))
                ) {
                    Column {
                        Box(Modifier.fillMaxWidth().height(70.dp).background(t.color.copy(alpha = 0.25f))) {
                            Diagram(t.diagram, t.color, Modifier.fillMaxSize())
                        }
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(t.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Text(t.subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Экран туториала: слева схема, справа вертикальный список карточек-шагов. В карточке номер, заголовок,
 * пояснение, плашки кнопок интерфейса и совет. Подходит для любого списка [Tutorial.steps].
 */
/** Кнопки, которых нет среди подсвечиваемых целей редактора: для них показывается только текст шага. */
private val NO_TARGET = setOf("Создать проект", "Bind Pose", "Автопривязка", "Свойства", "◆ Add Key", "Мультивыбор", "Выделение ключей", "Удалить ключи", "Лесенка", "Свернуть")

/** Шаги для подсказок в редакторе: цель - первая кнопка шага. */
fun Tutorial.coachSession() = com.vibetoon.ui.shell.Coach.Session(
    title, color,
    steps.map { st ->
        val b = st.buttons.firstOrNull()
        val target = b?.let { it.coach ?: when (it.label) {
            "Кости" -> if (it.chip) "chip:Кости" else "Кости"
            "Сетка" -> "chip:Сетка"
            "FX" -> "Эффекты (FX)"
            else -> it.label
        } }?.takeIf { b.label !in NO_TARGET }
        com.vibetoon.ui.shell.CoachStep(st.title, st.text, target)
    }
)

@Composable
fun TutorialDetailScreen(t: Tutorial, onClose: () -> Unit, onShowInEditor: (() -> Unit)? = null) {
    BackHandler(onBack = onClose)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(52.dp).background(t.color.copy(alpha = 0.18f)).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, "Назад") }
                Text(t.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.weight(0.7f).fillMaxSize().clip(RoundedCornerShape(16.dp)).background(t.color.copy(alpha = 0.15f))) {
                    Diagram(t.diagram, t.color, Modifier.fillMaxSize().padding(12.dp))
                }
                Column(Modifier.weight(1.3f).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(t.subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    if (onShowInEditor != null) androidx.compose.material3.Button(
                        onClick = onShowInEditor, modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = t.color)
                    ) { com.vibetoon.ui.IconText(androidx.compose.material.icons.Icons.Default.PlayArrow, "Пройти в редакторе: кнопки подсвечиваются", 14.sp) }
                    t.steps.forEachIndexed { i, step -> StepCard(i + 1, step, t.color) }
                    if (t.tips.isNotEmpty()) Card(colors = CardDefaults.cardColors(containerColor = t.color.copy(alpha = 0.12f))) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Lightbulb, null, tint = t.color, modifier = Modifier.size(18.dp))
                                Text("  Советы", fontWeight = FontWeight.SemiBold)
                            }
                            for (tip in t.tips) Text("• $tip", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepCard(n: Int, step: TutorialStep, color: Color) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                Text("$n", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(step.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(step.text, fontSize = 14.sp, lineHeight = 20.sp)
                for (b in step.buttons) ButtonPlate(b, color)
                step.tip?.let {
                    com.vibetoon.ui.IconText(androidx.compose.material.icons.Icons.Default.Lightbulb, it, 12.sp, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
                }
            }
        }
    }
}

/** Плашка «кнопка интерфейса»: значок (или чип) в рамке, как в редакторе, и подпись, где её искать. */
@Composable
private fun ButtonPlate(b: UiButton, color: Color) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.10f)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier.clip(RoundedCornerShape(if (b.chip) 8.dp else 20.dp)).background(MaterialTheme.colorScheme.surface)
                .border(1.dp, color, RoundedCornerShape(if (b.chip) 8.dp else 20.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            if (b.icon != null) Icon(b.icon, b.label, tint = color, modifier = Modifier.size(24.dp))
            else Text(b.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = color)
        }
        Column {
            Text(b.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("Где: ${b.where}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        }
    }
}

/** Простые схемы: цепочка костей, две позы, решётка Warp, дерево слоёв, дорожка с ключами, треугольная сетка. */
@Composable
private fun Diagram(kind: DiagramKind, color: Color, modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = (h * 0.03f).coerceIn(2f, 6f)
        fun bone(a: Offset, b: Offset, c: Color = color) {
            drawLine(c, a, b, strokeWidth = sw * 1.5f)
            val r = sw * 2.2f
            val d = Path().apply { moveTo(a.x, a.y - r); lineTo(a.x + r, a.y); lineTo(a.x, a.y + r); lineTo(a.x - r, a.y); close() }
            drawPath(d, c)
            drawCircle(c, r * 0.9f, b)
        }
        when (kind) {
            DiagramKind.BONES -> {
                val p0 = Offset(w * 0.2f, h * 0.7f); val p1 = Offset(w * 0.45f, h * 0.4f); val p2 = Offset(w * 0.7f, h * 0.5f); val p3 = Offset(w * 0.85f, h * 0.25f)
                drawRoundRect(ink.copy(alpha = 0.12f), Offset(w * 0.15f, h * 0.3f), androidx.compose.ui.geometry.Size(w * 0.55f, h * 0.2f), androidx.compose.ui.geometry.CornerRadius(h * 0.1f))
                bone(p0, p1); bone(p1, p2); bone(p2, p3)
            }
            DiagramKind.BIND_POSE -> {
                val gap = w * 0.5f
                bone(Offset(w * 0.12f, h * 0.75f), Offset(w * 0.12f + gap * 0.3f, h * 0.35f), ink.copy(alpha = 0.5f))
                bone(Offset(w * 0.12f + gap * 0.3f, h * 0.35f), Offset(w * 0.12f + gap * 0.6f, h * 0.75f), ink.copy(alpha = 0.5f))
                bone(Offset(w * 0.6f, h * 0.75f), Offset(w * 0.6f + gap * 0.3f, h * 0.35f))
                bone(Offset(w * 0.6f + gap * 0.3f, h * 0.35f), Offset(w * 0.6f + gap * 0.75f, h * 0.45f))
                drawLine(ink.copy(alpha = 0.4f), Offset(w * 0.48f, h * 0.5f), Offset(w * 0.55f, h * 0.5f), strokeWidth = sw)
            }
            DiagramKind.WARP -> {
                val pts = Array(4) { j -> Array(4) { i ->
                    val x = w * (0.2f + 0.6f * i / 3f); val y = h * (0.15f + 0.7f * j / 3f)
                    Offset(x + (if (i == 2 && j == 1) w * 0.08f else 0f), y + (if (i == 2 && j == 1) -h * 0.08f else 0f))
                } }
                for (j in 0 until 4) for (i in 0 until 4) {
                    if (i < 3) drawLine(color.copy(alpha = 0.6f), pts[j][i], pts[j][i + 1], strokeWidth = sw)
                    if (j < 3) drawLine(color.copy(alpha = 0.6f), pts[j][i], pts[j + 1][i], strokeWidth = sw)
                }
                for (j in 0 until 4) for (i in 0 until 4) drawCircle(color, sw * 2f, pts[j][i])
            }
            DiagramKind.LAYERS -> {
                val rowH = h / 5f
                for (i in 0 until 4) {
                    val indent = if (i in 1..2) w * 0.12f else 0f
                    drawRoundRect(color.copy(alpha = if (i == 0) 0.6f else 0.3f), Offset(w * 0.12f + indent, rowH * (i + 0.5f)), androidx.compose.ui.geometry.Size(w * 0.6f - indent, rowH * 0.7f), androidx.compose.ui.geometry.CornerRadius(8f))
                }
                drawLine(ink.copy(alpha = 0.6f), Offset(w * 0.8f, rowH * 3.3f), Offset(w * 0.8f, rowH * 1.4f), strokeWidth = sw)
            }
            DiagramKind.TIMELINE -> {
                for (r in 0 until 3) {
                    val y = h * (0.25f + 0.25f * r)
                    drawRoundRect(color.copy(alpha = 0.3f), Offset(w * 0.1f + r * w * 0.05f, y - h * 0.07f), androidx.compose.ui.geometry.Size(w * 0.7f, h * 0.14f), androidx.compose.ui.geometry.CornerRadius(8f))
                    for (k in 0 until 3) {
                        val x = w * (0.2f + 0.2f * k) + r * w * 0.05f
                        val d = Path().apply { moveTo(x, y - sw * 2); lineTo(x + sw * 2, y); lineTo(x, y + sw * 2); lineTo(x - sw * 2, y); close() }
                        drawPath(d, color)
                    }
                }
                drawLine(Color(0xFFE53935), Offset(w * 0.5f, h * 0.05f), Offset(w * 0.5f, h * 0.95f), strokeWidth = sw)
            }
            DiagramKind.MESH -> {
                val a = Offset(w * 0.2f, h * 0.8f); val b = Offset(w * 0.5f, h * 0.15f); val c = Offset(w * 0.8f, h * 0.8f)
                val m = Offset(w * 0.5f, h * 0.6f)
                val tri = listOf(a to b, b to c, c to a, a to m, b to m, c to m)
                for ((p, q) in tri) drawLine(color.copy(alpha = 0.7f), p, q, strokeWidth = sw, )
                for (p in listOf(a, b, c, m)) drawCircle(color, sw * 2f, p)
                drawRect(ink.copy(alpha = 0.0f), Offset.Zero, size, style = Stroke(0f))
            }
        }
    }
}
