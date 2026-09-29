package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7496CanonicalPositionViewCacheTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt").readText()

    @Test fun open_closed_and_classification_share_revision_view() {
        val s = src()
        assertTrue(s.contains("data class PositionViewCache7496"))
        assertTrue(s.contains("val revision7496 = muts.get()"))
        assertTrue(s.contains("fun openPositions(): List<Position> = positionViews7496().open"))
        assertTrue(s.contains("fun closedPositions(): List<Position> = positionViews7496().closed"))
        assertTrue(s.contains("val classification = positionViews7496().classification"))
    }

    @Test fun racing_mutation_prevents_cache_publication() {
        assertTrue(src().contains("if (muts.get() == revision7496) positionViewCache7496.set(built7496)"))
    }

    @Test fun strict_valuation_surface_is_unchanged() {
        assertTrue(src().contains("fun openPositionsForValuation(): List<Position> = positions.values.filter { isEconomicallyValidOpen6631(it) }"))
    }
}
