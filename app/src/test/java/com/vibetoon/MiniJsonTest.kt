package com.vibetoon

import com.vibetoon.domain.project.JsonException
import com.vibetoon.domain.project.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MiniJsonTest {
    @Test fun parsesAllValueTypes() {
        val v = MiniJson.parse("""{"a":1,"b":-2.5e1,"c":"x","d":true,"e":null,"f":[1,2,{"g":false}]}""") as Map<*, *>
        assertEquals(1.0, v["a"])
        assertEquals(-25.0, v["b"])
        assertEquals("x", v["c"])
        assertEquals(true, v["d"])
        assertNull(v["e"])
        assertEquals(3, (v["f"] as List<*>).size)
    }

    @Test fun stringEscapesRoundTrip() {
        val original = mapOf("t" to "строка \"в кавычках\"\nс переносом\tи \\ слэшем")
        val back = MiniJson.parse(MiniJson.stringify(original)) as Map<*, *>
        assertEquals(original["t"], back["t"])
    }

    @Test fun unicodeEscapeIsRead() = assertEquals("Я", MiniJson.parse("\"\\u042F\""))

    @Test fun emptyContainers() {
        assertTrue((MiniJson.parse("{}") as Map<*, *>).isEmpty())
        assertTrue((MiniJson.parse(" [ ] ") as List<*>).isEmpty())
    }

    @Test fun compactAndPrettyBothParse() {
        val data = mapOf("k" to listOf(1, 2, mapOf("z" to "w")))
        assertEquals(MiniJson.parse(MiniJson.stringify(data, pretty = true)), MiniJson.parse(MiniJson.stringify(data, pretty = false)))
    }

    @Test fun badInputThrows() {
        for (bad in listOf("{ not json", "[1,2", "{\"a\":}", "\"open", "{} trailing", "")) {
            try {
                MiniJson.parse(bad)
                fail("ожидалась ошибка для: $bad")
            } catch (_: JsonException) {
            }
        }
    }

    @Test fun nanIsWrittenAsZero() {
        assertEquals("0", MiniJson.stringify(Double.NaN))
    }
}
