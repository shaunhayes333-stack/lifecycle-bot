package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7510HeldHotBatchMarkTest {
    private fun src() =
        File("src/main/kotlin/com/lifecyclebot/engine/truth/HeldHotMarkAuthority7419.kt").readText()

    @Test fun solana_book_uses_one_locked_and_one_fanout_batch() {
        val s = src()
        val fn = s.substringAfter("private fun refreshPass()").substringBefore("fun summary(): Summary")
        // V5.0.7546 — locked venue and fanout resolve the whole held book concurrently.
        assertTrue(fn.contains("val heldBare7546 = solanaByBare7510.keys.toList()"))
        assertTrue(fn.contains("LockedVenueMarks7392.resolve(heldBare7546, dex)"))
        assertTrue(fn.contains("ParallelMarkFanout7088.resolve7088(heldBare7546)"))
        assertTrue(fn.contains("HELD_HOT_BATCH_PASS_7510"))
        assertFalse(fn.contains("LockedVenueMarks7392.resolve(listOf(bare), dex)[bare]"))
    }

    @Test fun batch_fanout_is_bounded() {
        val s = src()
        // V5.0.7810 deadline 1.2s; V5.0.7546 whole-book remaining-time bound.
        assertTrue(s.contains("BATCH_FANOUT_DEADLINE_MS_7510 = 1_200L"))
        assertTrue(s.contains("(BATCH_FANOUT_DEADLINE_MS_7510 - (System.currentTimeMillis() - passStart7546))"))
        assertTrue(s.contains("fanFuture7546.get(remaining7546(), TimeUnit.MILLISECONDS)"))
        assertTrue(s.contains("HELD_HOT_BATCH_FANOUT_TIMEOUT_7546"))
    }

    @Test fun cross_asset_exact_identity_path_is_preserved() {
        val s = src()
        val fn = s.substringAfter("private fun refreshPass()").substringBefore("fun summary(): Summary")
        assertTrue(fn.contains("DynamicAltTokenRegistry.refreshHeldMark7251(p.mint)"))
        assertTrue(fn.contains("dyn.canonicalIdentity.equals(p.mint, true)"))
    }
}
