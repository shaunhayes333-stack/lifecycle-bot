package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7476ToolkitUnchangedReuseTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt").readText()

    @Test fun unchanged_sheet_has_longer_reuse_window() {
        val s = src()
        assertTrue(s.contains("UNCHANGED_CACHE_TTL_MS_7476 = 15_000L"))
        assertTrue(s.contains("existing.fingerprint == fp && age7476 <= UNCHANGED_CACHE_TTL_MS_7476"))
        assertTrue(s.contains("TOOLKIT_UNCHANGED_SHEET_REUSED_7476"))
    }

    @Test fun timestamp_only_updates_do_not_invalidate_but_price_move_does() {
        val s = src()
        val fp = s.substringAfter("private fun fingerprint").substringBefore("fun build(")
        assertFalse(fp.contains("ts.lastPriceUpdate"))
        assertTrue(fp.contains("meaningfulPriceBucket7476(ts.lastPrice)"))
        assertTrue(fp.contains("ts.history.size"))
        assertTrue(fp.contains("ts.lastV3Score"))
        assertTrue(fp.contains("ts.lastLiquidityUsd.toInt()"))
    }
}
