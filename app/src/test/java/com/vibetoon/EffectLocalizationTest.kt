package com.vibetoon

import com.vibetoon.domain.anim.EffectCategory
import com.vibetoon.domain.anim.EffectMath
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EffectLocalizationTest {
    @Test fun everyEffectTitleAndParameterHasAnEnglishTranslation() {
        val pairs = listOf("../tools/i18n/pairs.py", "../tools/i18n/pairs2.py").flatMap { File(it).readLines() }
            .filter { '|' in it }.associate { it.substringBefore('|') to it.substringAfter('|') }
        val names = EffectMath.DEFS.flatMap { listOf(it.title, it.subtitle) + it.params.map { p -> p.name } } +
            EffectCategory.values().map { it.title }
        val cyrillic = Regex("[А-Яа-яЁё]")
        for (name in names) {
            val translated = if (cyrillic.containsMatchIn(name)) pairs[name] else name
            assertTrue("Missing English for $name", translated != null && !cyrillic.containsMatchIn(translated))
        }
    }
}
