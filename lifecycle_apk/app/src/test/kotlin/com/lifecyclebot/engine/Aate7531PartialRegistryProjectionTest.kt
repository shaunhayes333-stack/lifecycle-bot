package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7531PartialRegistryProjectionTest {
    @Test fun partial_commit_immediately_projects_reduced_canonical_mint_state() {
        val partial = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperPartialOperation6510.kt").readText()
        val registry = File("src/main/kotlin/com/lifecyclebot/engine/EmergentGuardrails.kt").readText()
        assertTrue(partial.contains("syncMintFromCanonical7531(mint, active7531)"))
        assertTrue(partial.indexOf("syncMintFromCanonical7531(mint, active7531)") < partial.indexOf("PAPER_PARTIAL_CLOSE_DONE"))
        assertTrue(registry.contains("fun syncMintFromCanonical7531("))
        assertTrue(registry.contains("PARTIAL_REGISTRY_PROJECTED_FROM_CANONICAL_7531"))
        assertTrue(registry.contains("PARTIALLY_CLOSED"))
        assertTrue(registry.contains("remainingQtyRaw"))
        assertTrue(registry.contains("entryCostSol - it.soldCostBasisSol"))
    }
}
