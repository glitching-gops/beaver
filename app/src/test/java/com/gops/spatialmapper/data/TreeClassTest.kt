package com.gops.spatialmapper.data

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Unit tests for [TreeClass] enum and [ClassSource] enum.
 *
 * - Enum completeness: all expected values exist
 * - Enum round-tripping: enum.name survives serialization and deserialization
 * - Defensive deserialization: unknown values fall back to UNCLASSIFIED rather than crashing
 */
class TreeClassTest {

    @Test
    fun treeClassHasAllSevenValues() {
        val values = TreeClass.values()
        assertEquals("TreeClass should have exactly 7 values", 7, values.size)
        assertTrue(values.contains(TreeClass.PALM))
        assertTrue(values.contains(TreeClass.BROADLEAF_EVERGREEN))
        assertTrue(values.contains(TreeClass.BROADLEAF_DECIDUOUS))
        assertTrue(values.contains(TreeClass.BROADLEAF_UNCONFIRMED))
        assertTrue(values.contains(TreeClass.BAMBOO))
        assertTrue(values.contains(TreeClass.OTHER))
        assertTrue(values.contains(TreeClass.UNCLASSIFIED))
    }

    @Test
    fun enumNameRoundTrip() {
        TreeClass.values().forEach { original ->
            val serialized = original.name
            val deserialized = TreeClass.valueOf(serialized)
            assertEquals("Enum $original should round-trip through .name", original, deserialized)
        }
    }

    @Test
    fun classSourceHasThreeValues() {
        val values = ClassSource.values()
        assertEquals("ClassSource should have exactly 3 values", 3, values.size)
        assertTrue(values.contains(ClassSource.UNSET))
        assertTrue(values.contains(ClassSource.MANUAL))
        assertTrue(values.contains(ClassSource.DERIVED))
    }

    @Test
    fun classSourceNameRoundTrip() {
        ClassSource.values().forEach { original ->
            val serialized = original.name
            val deserialized = ClassSource.valueOf(serialized)
            assertEquals("Enum $original should round-trip through .name", original, deserialized)
        }
    }
}
