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
        assertTrue(fn.contains("LockedVenueMarks7392.resolve(solanaByBare7510.keys.toList(), dex)"))
        assertTrue(fn.contains("ParallelMarkFanout7088.resolve7088(unresolved7510)"))
        assertTrue(fn.contains("HELD_HOT_BATCH_PASS_7510"))
        assertFalse(fn.contains("LockedVenueMarks7392.resolve(listOf(bare), dex)[bare]"))
    }

    @Test fun batch_fanout_is_bounded() {
        val s = src()
        assertTrue(s.contains("BATCH_FANOUT_DEADLINE_MS_7510 = 2_500L"))
        assertTrue(s.contains("boundedBatch7510"))
        assertTrue(s.contains("HELD_HOT_BATCH_FANOUT_TIMEOUT_7510"))
    }

    @Test fun cross_asset_exact_identity_path_is_preserved() {
        val s = src()
        val fn = s.substringAfter("private fun refreshPass()").substringBefore("fun summary(): Summary")
        assertTrue(fn.contains("DynamicAltTokenRegistry.refreshHeldMark7251(p.mint)"))
        assertTrue(fn.contains("dyn.canonicalIdentity.equals(p.mint, true)"))
    }
}
