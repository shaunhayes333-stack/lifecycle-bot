package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7458SpecialistCapitalContinuityTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun canonical_sizing_records_specialist_proposal_by_asset_and_lane() {
        val s = src("engine/truth/CanonicalSizingBridge6532.kt")
        assertTrue(s.contains("GlobalCapitalArbitration6617.recordSpecialistProposal6617("))
        assertTrue(s.contains("assetClass == AssetClass.SOLANA_TOKEN"))
        assertTrue(s.contains("canonicalAssetId.isNotBlank()"))
    }

    @Test fun proposal_registry_is_lane_plus_mint_not_single_owner_per_mint() {
        val s = src("engine/truth/GlobalCapitalArbitration6617.kt")
        assertTrue(s.contains("specialistKey7458(lane: String, mint: String)"))
        assertTrue(s.contains("\"${lane.trim().uppercase()}|${mint.trim()}\""))
        assertTrue(s.contains("specialistProposals7458"))
    }

    @Test fun canonical_entry_checks_same_specialist_before_intent_seal() {
        val s = src("engine/truth/CanonicalAssetEntryContract6551.kt")
        val verify = s.indexOf("GlobalCapitalArbitration6617.verifySpecialistProposal7458(")
        val seal = s.indexOf("val intent = ExecutableOpenGate.ExecutionIntent(")
        assertTrue(verify >= 0)
        assertTrue(seal > verify)
    }

    @Test fun continuity_mismatch_is_observable_but_not_a_new_gate() {
        val s = src("engine/truth/GlobalCapitalArbitration6617.kt")
        assertTrue(s.contains("SPECIALIST_PROPOSAL_DISPATCH_MISMATCH_7458"))
        val entry = src("engine/truth/CanonicalAssetEntryContract6551.kt")
        val region = entry.substringAfter("GlobalCapitalArbitration6617.verifySpecialistProposal7458(").substringBefore("val verdict")
        assertFalse(region.contains("return blocked("))
    }
}
