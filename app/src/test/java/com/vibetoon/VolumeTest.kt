package com.vibetoon

import com.vibetoon.domain.project.VolKey
import com.vibetoon.domain.project.VolumeMath
import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeTest {
    private val keys = listOf(VolKey(10, 1f), VolKey(30, 0f), VolKey(50, 0.5f))

    @Test fun linearBetweenKeysHoldOutside() {
        assertEquals(1f, VolumeMath.at(emptyList(), 5.0), 0f)      // без ключей полная громкость
        assertEquals(1f, VolumeMath.at(keys, 0.0), 1e-4f)          // до первого держится
        assertEquals(0.5f, VolumeMath.at(keys, 20.0), 1e-4f)       // середина спада
        assertEquals(0f, VolumeMath.at(keys, 30.0), 1e-4f)
        assertEquals(0.25f, VolumeMath.at(keys, 40.0), 1e-4f)
        assertEquals(0.5f, VolumeMath.at(keys, 99.0), 1e-4f)       // после последнего держится
    }

    @Test fun keysAreReplacedPerFrameAndClamped() {
        val a = VolumeMath.withKey(keys, VolKey(30, 5f))
        assertEquals(3, a.size); assertEquals(1f, a[1].value, 0f)
        assertEquals(2, VolumeMath.withoutKey(keys, 30).size)
    }
}
