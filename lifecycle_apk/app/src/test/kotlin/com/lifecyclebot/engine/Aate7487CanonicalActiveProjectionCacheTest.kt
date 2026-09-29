package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7487CanonicalActiveProjectionCacheTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt").readText()

    @Test fun projection_cache_is_keyed_to_canonical_mutation_revision() {
        val s = src()
        val fn = s.substringAfter("fun activeMintProjections6490").substringBefore("V5.0.6461")
        assertTrue(fn.contains("val revision7487 = muts.get()"))
        assertTrue(fn.contains("cached7487.mutationRevision == revision7487"))
        assertTrue(fn.contains("if (muts.get() == revision7487)"))
        assertTrue(fn.contains("ACTIVE_MINT_PROJECTION_CACHE_HIT_7487"))
    }

    @Test fun mode_mint_aggregation_semantics_are_preserved() {
        val s = src()
        val fn = s.substringAfter("fun activeMintProjections6490").substringBefore("V5.0.6461")
        assertTrue(fn.contains("groupBy"))
        assertTrue(fn.contains("it.mode.lowercase()"))
        assertTrue(fn.contains("it.mint"))
        assertTrue(fn.contains("remainingQtyRaw = lots.fold(BigInteger.ZERO)"))
        assertTrue(fn.contains("remainingCostBasisSol = lots.sumOf"))
        assertTrue(fn.contains("primaryMode = representative.mode"))
    }

    @Test fun mode_filter_occurs_after_cached_aggregation() {
        val s = src()
        val fn = s.substringAfter("fun activeMintProjections6490").substringBefore("V5.0.6461")
        assertTrue(fn.contains("allRows7487.filter { it.primaryMode.equals(requestedMode7487, true) }"))
    }
}
