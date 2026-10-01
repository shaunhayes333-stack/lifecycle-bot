package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7611TokenStateEntryMarkContinuityTest {
    private fun src()=File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()

    @Test fun tokenStateEvidenceIsMaterializedBeforeFinalOpenGate() {
        val s=src()
        val fn=s.substringAfter("fun canOpenExecutablePosition(\n        ts: TokenState")
            .substringBefore("private fun canOpenExecutablePositionInternal")
        assertTrue(fn.contains("resolveBestSourceEvidence6734("))
        assertTrue(fn.contains("ts.lastPriceUpdate"))
        assertTrue(fn.contains("ts.lastPriceSource"))
        assertTrue(fn.contains("ts.lastPricePoolAddr"))
        assertTrue(fn.indexOf("resolveBestSourceEvidence6734(") < fn.indexOf("return canOpenExecutablePositionInternal("))
    }

    @Test fun repairDoesNotFabricateFreshnessOrBypassCanonicalResolver() {
        val s=src()
        val fn=s.substringAfter("V5.0.7611 — restore TokenState -> canonical entry-mark continuity")
            .substringBefore("return canOpenExecutablePositionInternal(")
        assertTrue(fn.contains("ts.lastPriceUpdate > 0L"))
        assertTrue(fn.contains("timestampMs = ts.lastPriceUpdate"))
        assertFalse(fn.contains("timestampMs = System.currentTimeMillis()"))
        assertTrue(fn.contains("resolveEntryMarkForMode7465"))
    }
}
