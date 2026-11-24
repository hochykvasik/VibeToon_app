package com.vibetoon.ui.i18n

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import java.util.Locale

/**
 * Локализация «на лету». Текст интерфейса в коде остаётся русским (он же ключ), а [L] и [Text] берут перевод
 * из res/values/strings.xml (RU) или res/values-en/strings.xml (EN) в зависимости от языка, выбранного в приложении.
 * Строки, которых нет в таблице (сообщения ViewModel, туториалы, строки с числами), показываются как есть.
 */
@Composable
fun L(ru: String): String {
    val ctx = LocalContext.current
    // язык читается из состояния настроек прямо здесь: переключение обновляет и окна-диалоги (у них свой слой композиции)
    val lang = com.vibetoon.ui.theme.LocalAppSettings.current?.effectiveLang
    return translate(ctx, lang, ru, 0)
}

/**
 * Перевод без Compose (тот же словарь, что у [L]). Значения, подставляемые в шаблон, тоже переводятся, если они
 * сами есть в словаре: «Переключение сцен — в разработке» в английском режиме даёт «Scene switching — coming soon»,
 * а не смесь «Переключение сцен — coming soon» (пакет 10).
 */
fun translate(ctx: Context, lang: String?, ru: String, depth: Int = 0): String {
    val exact = I18N_IDS[ru]
    val tm = if (exact == null) findTemplate(ru) else null
    val id = exact ?: tm?.first ?: return ru
    val text = if (lang == null) ctx.getString(id) else stringsFor(ctx, lang).getString(id)
    if (tm == null) return text
    // шаблон: подставляем значения из исходной строки по порядку на места {}
    val sb = StringBuilder()
    var gi = 0
    var i = 0
    while (i < text.length) {
        if (text.startsWith("{}", i) && gi < tm.second.size) {
            val v = tm.second[gi++]
            sb.append(if (depth < 2) translate(ctx, lang, v, depth + 1) else v)
            i += 2
        } else { sb.append(text[i]); i++ }
    }
    return sb.toString()
}

/** Translate known nested project diagnostics separately; leave OS and user-supplied text intact. */
@Composable
fun localizeDiagnostic(message: String): String {
    val prefix = "Повреждённый файл проекта: "
    if (!message.startsWith(prefix)) return L(message)
    val detail = message.removePrefix(prefix)
    val position = Regex("^(.*) \\(позиция ([0-9]+)\\)$").matchEntire(detail)
    val translated = if (position != null) L("{} (позиция {})").replaceFirst("{}", L(position.groupValues[1])).replaceFirst("{}", position.groupValues[2]) else L(detail)
    return L("Повреждённый файл проекта: {}").replace("{}", translated)
}

private val compiled: List<Pair<Regex, Int>> by lazy { I18N_TEMPLATES.map { Regex(it.first) to it.second } }
private val templateMemo = HashMap<String, Pair<Int, List<String>>?>()

/** Шаблон для русской строки с подстановками («Слой {} скопирован»): ресурс и значения групп; null - не подходит. Результат запоминается. */
private fun findTemplate(s: String): Pair<Int, List<String>>? {
    if (s.none { it in 'А'..'я' || it == 'ё' || it == 'Ё' }) return null
    synchronized(templateMemo) {
        if (templateMemo.containsKey(s)) return templateMemo[s]
        var res: Pair<Int, List<String>>? = null
        for ((rx, id) in compiled) {
            val m = rx.matchEntire(s) ?: continue
            res = id to m.groupValues.drop(1)
            break
        }
        if (templateMemo.size > 4000) templateMemo.clear()
        templateMemo[s] = res
        return res
    }
}

private val resCache = HashMap<String, Resources>()

// спринт 30: translate вызывается и из фоновых потоков (стандартные имена при импорте), поэтому кэш под замком
private fun stringsFor(ctx: Context, lang: String): Resources = synchronized(resCache) {
    resCache.getOrPut(lang) {
        val cfg = Configuration(ctx.applicationContext.resources.configuration)
        cfg.setLocale(Locale(lang))
        ctx.applicationContext.createConfigurationContext(cfg).resources
    }
}

/** Контекст с локалью [lang] ("ru" или "en"): ресурсы строк берутся из соответствующей папки values. */
fun localizedContext(base: Context, lang: String): Context {
    val cfg = Configuration(base.resources.configuration)
    cfg.setLocale(Locale(lang))
    val res: Resources = base.createConfigurationContext(cfg).resources
    return object : ContextWrapper(base) {
        override fun getResources(): Resources = res
    }
}

/** Material-текст с переводом: везде, где файл импортирует этот Text вместо material3.Text, русские строки из таблицы подменяются. */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current
) = androidx.compose.material3.Text(
    L(text), modifier, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration, textAlign,
    lineHeight, overflow, softWrap, maxLines, minLines, onTextLayout, style
)

@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current
) = androidx.compose.material3.Text(
    text, modifier, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration, textAlign,
    lineHeight, overflow, softWrap, maxLines, minLines, emptyMap(), onTextLayout ?: {}, style
)
