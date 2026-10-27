package com.gops.spatialmapper.data

import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals

/**
 * Unit tests for [TreeClass] and [ClassSource] Room TypeConverters.
 *
 * - Round-tripping: enum to string and back
 * - Defensive deserialization: unknown values fall back to defaults
 * - Null handling
 */
class ConvertersTreeClassTest {

    private lateinit var converters: Converters

    @Before
    fun setup() {
        converters = Converters()
    }

    // --- TreeClass ---

    @Test
    fun treeClassToStringRoundTrip() {
        TreeClass.values().forEach { treeClass ->
            val stringForm = converters.treeClassToString(treeClass)
            val recovered = converters.stringToTreeClass(stringForm)
            assertEquals("TreeClass $treeClass should round-trip", treeClass, recovered)
        }
    }

    @Test
    fun treeClassNullDefaultsToUnclassified() {
        val result = converters.treeClassToString(null)
        assertEquals(TreeClass.UNCLASSIFIED.name, result)
        val deserialized = converters.stringToTreeClass(result)
        assertEquals(TreeClass.UNCLASSIFIED, deserialized)
    }

    @Test
    fun unknownTreeClassDeserializesFallsBackToUnclassified() {
        val unknown = "FUTURE_UNKNOWN_CLASS"
        val result = converters.stringToTreeClass(unknown)
        assertEquals("Unknown class should fall back to UNCLASSIFIED", TreeClass.UNCLASSIFIED, result)
    }

    @Test
    fun emptyStringTreeClassFallsBackToUnclassified() {
        val result = converters.stringToTreeClass("")
        assertEquals(TreeClass.UNCLASSIFIED, result)
    }

    @Test
    fun nullStringTreeClassFallsBackToUnclassified() {
        val result = converters.stringToTreeClass(null)
        assertEquals(TreeClass.UNCLASSIFIED, result)
    }

    // --- ClassSource ---

    @Test
    fun classSourceToStringRoundTrip() {
        ClassSource.values().forEach { source ->
            val stringForm = converters.classSourceToString(source)
            val recovered = converters.stringToClassSource(stringForm)
            assertEquals("ClassSource $source should round-trip", source, recovered)
        }
    }

    @Test
    fun classSourceNullDefaultsToUnset() {
        val result = converters.classSourceToString(null)
        assertEquals(ClassSource.UNSET.name, result)
        val deserialized = converters.stringToClassSource(result)
        assertEquals(ClassSource.UNSET, deserialized)
    }

    @Test
    fun unknownClassSourceFallsBackToUnset() {
        val unknown = "FUTURE_UNKNOWN_SOURCE"
        val result = converters.stringToClassSource(unknown)
        assertEquals("Unknown source should fall back to UNSET", ClassSource.UNSET, result)
    }

    @Test
    fun emptyStringClassSourceFallsBackToUnset() {
        val result = converters.stringToClassSource("")
        assertEquals(ClassSource.UNSET, result)
    }

    @Test
    fun nullStringClassSourceFallsBackToUnset() {
        val result = converters.stringToClassSource(null)
        assertEquals(ClassSource.UNSET, result)
    }
}
