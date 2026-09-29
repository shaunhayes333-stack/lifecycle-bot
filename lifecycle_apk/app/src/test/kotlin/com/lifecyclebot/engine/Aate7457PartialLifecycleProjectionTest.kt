package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7457PartialLifecycleProjectionTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun canonical_partial_sell_projects_state_at_the_mutation_site() {
        val s = src("engine/truth/CanonicalPositionAuthority6441.kt")
        val fn = s.substringAfter("fun partialSell(").substringBefore("/** V5.0.6485")
        assertTrue(fn.contains("if (newLifecycle == Lifecycle.PARTIALLY_CLOSED)"))
        assertTrue(fn.contains("PositionStateLedger6454.onPartial(positionId)"))
        assertTrue(fn.contains("POSITION_STATE_PARTIAL_PROJECTED_7457"))
    }

    @Test fun partial_projection_requires_canonical_partial_with_remaining_quantity() {
        val s = src("engine/truth/PositionStateLedger6454.kt")
        val fn = s.substringAfter("fun onPartial(positionId: String)").substringBefore("/**\n     * CAS OPEN/PARTIAL")
        assertTrue(fn.contains("CanonicalPositionAuthority6441.getPosition(positionId)"))
        assertTrue(fn.contains("CanonicalPositionAuthority6441.Lifecycle.PARTIALLY_CLOSED"))
        assertTrue(fn.contains("canonical.remainingQtyRaw > java.math.BigInteger.ZERO"))
        assertTrue(fn.contains("POSITION_STATE_PARTIAL_REFUSED_NO_CANONICAL_PROOF_7457"))
    }

    @Test fun unknown_live_projection_can_seed_only_from_canonical_truth() {
        val s = src("engine/truth/PositionStateLedger6454.kt")
        val fn = s.substringAfter("fun onPartial(positionId: String)").substringBefore("/**\n     * CAS OPEN/PARTIAL")
        assertTrue(fn.contains("Lifecycle.OPEN, Lifecycle.PARTIAL, null"))
        assertTrue(fn.contains("states[positionId] = Lifecycle.PARTIAL"))
    }

    @Test fun partial_callback_cannot_overwrite_terminal_ownership() {
        val s = src("engine/truth/PositionStateLedger6454.kt")
        val fn = s.substringAfter("fun onPartial(positionId: String)").substringBefore("/**\n     * CAS OPEN/PARTIAL")
        assertTrue(fn.contains("Lifecycle.CLOSING, Lifecycle.CLOSED, Lifecycle.UNKNOWN"))
        assertTrue(fn.contains("POSITION_STATE_PARTIAL_REFUSED_STATE_7457"))
    }
}
