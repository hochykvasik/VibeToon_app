package com.vibetoon

import com.vibetoon.domain.anim.EffectMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewEffectsTest {
    @Test fun newEffectsAreRegisteredAndDoNotClashWithSpin() {
        val types = EffectMath.DEFS.map { it.type }
        assertEquals(types.size, types.toSet().size)
        for (t in listOf(EffectMath.RGB_SPLIT, EffectMath.PULSE, EffectMath.DISPLACE)) {
            val e = EffectMath.create(t)
            assertNotNull(t.toString(), e)
            val r = EffectMath.records(listOf(e!!), 1.5f)
            assertEquals(1, r.size)
            assertEquals(t.toFloat(), r[0][0], 0f)
            assertEquals(1.5f, r[0][9], 0f)
        }
        assertTrue(EffectMath.SPIN !in listOf(EffectMath.RGB_SPLIT, EffectMath.PULSE, EffectMath.DISPLACE))
    }
}
