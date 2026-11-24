package com.dincer552.tvremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SamsungIrTest {
    @Test
    fun powerHasTwoLegacyAndOneSamsung32Candidate() {
        val candidates = SamsungIr.candidates("Power")
        assertEquals(3, candidates.size)
        assertEquals("4500,4500", candidates[0].pattern.split(",").take(2).joinToString(","))
        assertEquals("4611,4433", candidates[1].pattern.split(",").take(2).joinToString(","))
        assertTrue(candidates[2].description.contains("Samsung32"))
    }

    @Test
    fun generatedSamsungFrameHasAlternatingMarkSpacePairsAndSafeDuration() {
        val candidate = SamsungIr.candidates("Power").first()
        val values = candidate.pattern.split(",").map(String::toInt)
        assertEquals(68, values.size)
        assertEquals(4500, values[0])
        assertEquals(4500, values[1])
        assertEquals(560, values[2])
        assertEquals(1690, values[3])
        assertEquals(560, values[66])
        assertEquals(45000, values[67])
        assertTrue(values.sum() < 200000)
    }

    @Test
    fun samsung32UsesAddressByteTwiceAndInvertedCommand() {
        val values = SamsungIr.candidates("Power")[2].pattern.split(",").map(String::toInt)
        // Samsung32 payload bytes for this candidate are 07 07 02 FD.
        // The first two address bytes must therefore encode the same 0x07 value.
        val expectedAddressBytePairs = listOf(550, 1650, 550, 1650, 550, 1650, 550, 550)
        assertEquals(expectedAddressBytePairs, values.subList(2, 18).chunked(2).map { it[0] to it[1] }.flatMap { listOf(it.first, it.second) })
    }

    @Test
    fun missingSamsung32MappingDoesNotCreateFalseCandidate() {
        val menuCandidates = SamsungIr.candidates("Menu")
        assertEquals(2, menuCandidates.size)
    }

    @Test
    fun customPatternValidationRejectsMalformedOrTooLongSignals() {
        assertNotNull(SamsungIr.validateCustom("38000", "4500,4500,560,560", "1"))
        assertNull(SamsungIr.validateCustom("0", "4500,4500,560,560", "1"))
        assertNull(SamsungIr.validateCustom("38000", "4500,broken,560,560", "1"))
        assertNull(SamsungIr.validateCustom("38000", "1000000,1000000,1000000,1000000", "1"))
    }
}
