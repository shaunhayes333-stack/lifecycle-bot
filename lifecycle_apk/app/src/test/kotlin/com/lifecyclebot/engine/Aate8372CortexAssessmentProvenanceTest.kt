package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexAssessmentProvenance8372
import com.lifecyclebot.engine.cortex.CortexCalibration7901
import com.lifecyclebot.engine.cortex.CortexRunOdds8325
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class Aate8372CortexAssessmentProvenanceTest {
    private fun original() = CortexAssessmentProvenance8372(
        scoreVersion = 1,
        rawEdge = 0.12345678901234567,
        laneMean = -11.987654321098765,
        laneMeanAll = 1.2345678901234567,
        calibrationInput = 13.345679000234568,
        calibratedEdge = 5.678901234567891,
        assessmentAtMs = 1_792_846_531_483L,
    )

    private fun restored(value: CortexAssessmentProvenance8372) =
        CortexAssessmentProvenance8372.decode(JSONObject(value.encode().toString()))!!

    @Test fun checkpointPreservesEveryOriginalDoubleAndTimestampWithoutRounding() {
        val before = original()
        val after = restored(before)
        assertEquals(before, after)
        assertEquals(java.lang.Double.doubleToRawLongBits(before.rawEdge), java.lang.Double.doubleToRawLongBits(after.rawEdge))
        assertEquals(java.lang.Double.doubleToRawLongBits(before.laneMean), java.lang.Double.doubleToRawLongBits(after.laneMean))
        assertEquals(java.lang.Double.doubleToRawLongBits(before.laneMeanAll), java.lang.Double.doubleToRawLongBits(after.laneMeanAll))
        assertEquals(java.lang.Double.doubleToRawLongBits(before.calibrationInput), java.lang.Double.doubleToRawLongBits(after.calibrationInput))
        assertEquals(java.lang.Double.doubleToRawLongBits(before.calibratedEdge), java.lang.Double.doubleToRawLongBits(after.calibratedEdge))
    }

    @Test fun unknownBaselineRemainsUnknownRatherThanBeingInventedAsZero() {
        val before = original().copy(scoreVersion = 0, laneMeanAll = Double.NaN, calibrationInput = Double.NaN)
        val after = restored(before)
        assertEquals(0, after.scoreVersion)
        assertTrue(after.laneMeanAll.isNaN())
        assertTrue(after.calibrationInput.isNaN())
        assertEquals(before.rawEdge, after.rawEdge, 0.0)
        assertTrue(before.encode().isNull("laneMeanAll"))
        assertTrue(before.encode().isNull("calibrationInput"))
    }

    @Test fun oldMissingOrCorruptProvenanceDoesNotPretendToBeNewScoreEvidence() {
        assertNull(CortexAssessmentProvenance8372.decode(null))
        assertNull(CortexAssessmentProvenance8372.decode(JSONObject()))
        for (field in listOf("scoreVersion", "rawEdge", "laneMean", "laneMeanAll", "calibrationInput", "calibratedEdge", "assessmentAtMs")) {
            val missing = original().encode().also { it.remove(field) }
            assertNull("Missing $field", CortexAssessmentProvenance8372.decode(missing))
        }
        for ((field, value) in listOf("scoreVersion" to -1, "scoreVersion" to 1.5, "assessmentAtMs" to 0L,
            "assessmentAtMs" to -2L, "rawEdge" to "not-a-number", "calibrationInput" to JSONObject(),
            "scoreVersion" to BigDecimal("1.00000000000000000001"),
            "assessmentAtMs" to BigDecimal("1792846531483.000000000001"))) {
            assertNull("Invalid $field=$value", CortexAssessmentProvenance8372.decode(original().encode().put(field, value)))
        }
        assertEquals(0, restored(original().copy(scoreVersion = 0)).scoreVersion)
    }

    @Test fun restoredScalpLearnsTheSameCalibrationInputAndOutcomeAsTheOriginalToken() {
        val raw = 5.123456789012345
        val mean = 3.456789012345678
        val all = -4.567890123456789
        val input = CortexRunOdds8325.regimeNeutral8325(raw, mean, all, 1.0)
        val before = original().copy(rawEdge = raw, laneMean = mean, laneMeanAll = all,
            calibrationInput = input, calibratedEdge = 7.123456789012345)
        val after = restored(before)
        assertNotEquals("Losing the all-regime baseline would change this score", raw, input, 1e-9)
        val direct = CortexCalibration7901()
        val restarted = CortexCalibration7901()
        direct.learn("QUALITY", before.rawEdge, before.laneMean, 21.23456789012345, before.calibrationInput)
        restarted.learn("QUALITY", after.rawEdge, after.laneMean, 21.23456789012345, after.calibrationInput)
        assertEquals(direct.encode().getString("QUALITY"), restarted.encode().getString("QUALITY"))
        assertEquals(direct.encode().getString("ISO8366|QUALITY"), restarted.encode().getString("ISO8366|QUALITY"))
        assertEquals(21.23456789012345, restarted.netFit8372("QUALITY")!!.expectedNetPct8372(input), 0.0)
    }

    @Test fun laterRegimeChangesCannotRewriteTheFrozenScoreOrForecast() {
        val before = original()
        val after = restored(before)
        val laterInput = CortexRunOdds8325.regimeNeutral8325(70.0, -30.0, 20.0, 1.0)
        assertNotEquals(laterInput, after.calibrationInput, 1e-9)
        assertEquals(before.calibrationInput, after.calibrationInput, 0.0)
        assertEquals(before.calibratedEdge, after.calibratedEdge, 0.0)
        assertEquals(before.assessmentAtMs, after.assessmentAtMs)
        assertEquals(before.scoreVersion, after.scoreVersion)
    }
}
