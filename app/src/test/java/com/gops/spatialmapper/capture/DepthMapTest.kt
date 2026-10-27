package com.gops.spatialmapper.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The saved depth file format and the per-tree median sampling. */
class DepthMapTest {

    private fun mapOf(width: Int, height: Int, value: (Int, Int) -> Int) =
        DepthMap(width, height, ShortArray(width * height) { i -> value(i % width, i / width).toShort() })

    @Test
    fun `encode then decode is lossless, including values above 32767 mm`() {
        // Unsigned 16-bit: 40 m is 40000 mm, which is negative as a signed Short.
        val original = mapOf(160, 120) { x, y -> if (x == 3 && y == 4) 40_000 else 1000 + x * 7 + y * 3 }
        val decoded = decodeDepthMap(encodeDepthMap(original))!!
        assertEquals(160, decoded.width)
        assertEquals(120, decoded.height)
        assertArrayEquals(original.samples(), decoded.samples())
        assertEquals(40_000, decoded.millimetersAt(3, 4))
    }

    @Test
    fun `a smooth depth image compresses well below its raw size`() {
        // A ground-plane-like gradient: the kind of surface a real depth image is mostly made of.
        val gradient = mapOf(160, 120) { _, y -> 3000 + y * 40 }
        val raw = 160 * 120 * 2
        val encoded = encodeDepthMap(gradient).size
        assertTrue("expected well under raw $raw bytes, got $encoded", encoded < raw / 4)
    }

    @Test
    fun `decode refuses anything that is not a depth file`() {
        assertNull(decodeDepthMap(ByteArray(0)))
        assertNull(decodeDepthMap("not a depth file at all".toByteArray()))
        val good = encodeDepthMap(mapOf(8, 6) { _, _ -> 1234 })
        assertNull("truncated", decodeDepthMap(good.copyOf(good.size - 5)))
        assertNull("wrong version", decodeDepthMap(good.copyOf().also { it[4] = 9 }))
    }

    @Test
    fun `median ignores invalid zeros so one foliage gap can't decide the distance`() {
        // 5x5 window: mostly 8 m, with holes (0 = no estimate) and one far background value.
        val map = mapOf(20, 20) { x, y ->
            when {
                x == 10 && y == 10 -> 0          // the gap right at the centre
                x == 9 && y == 10 -> 0
                x == 11 && y == 11 -> 25_000     // background showing through
                else -> 8000
            }
        }
        assertEquals(8.0f, map.medianDepthMeters(10, 10)!!, 1e-6f)
    }

    @Test
    fun `median of an even count averages the middle pair`() {
        // Only two valid values in the window.
        val map = mapOf(9, 9) { x, y -> if (y == 4 && (x == 4 || x == 5)) (if (x == 4) 6000 else 7000) else 0 }
        assertEquals(6.5f, map.medianDepthMeters(4, 4)!!, 1e-6f)
    }

    @Test
    fun `no valid depth in the window is null, not zero`() {
        val map = mapOf(20, 20) { _, _ -> 0 }
        assertNull(map.medianDepthMeters(10, 10))
    }

    @Test
    fun `a window at the image edge uses only the pixels that exist`() {
        val map = mapOf(10, 10) { x, _ -> if (x == 0) 5000 else 9000 }
        // At (0,0) the window covers x = 0..2 only: one column of 5 m, two of 9 m -> median 9 m.
        assertEquals(9.0f, map.medianDepthMeters(0, 0)!!, 1e-6f)
        assertEquals(0, map.millimetersAt(-1, 0))
    }
}
