package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.7398 regression tape.
 *
 * Protects the operator contract:
 *  - confidence/recovery governor shapes; it does not veto live BUYs;
 *  - paper treasury is part of paper equity and therefore part of the 6501 invariant;
 *  - live-open diagnostics are mode-scoped;
 *  - Crypto Universe producer liveness stamps the bounded candidate handoff and
 *    the canonical submit boundary.
 */
class Aate7398GovernorAccountingAndCryptoCoverageTest {
    private fun src(relative: String): String =
        File("src/main/kotlin/com/lifecyclebot/$relative").readText()

    @Test
    fun governor_is_shaping_only_at_lane_entry_and_readiness() {
        val lane = src("engine/LaneEntryContract6342.kt")
        val assess = lane.substringAfter("fun assessEntry(")
        assertTrue(assess.contains("GOVERNOR_SHAPING_ONLY_7398"))
        assertFalse(assess.contains("return Assessment(Verdict.GOVERNOR_HOLD_VETO"))

        val readiness = src("engine/FirstTradeReadiness6348.kt")
        assertTrue(readiness.contains("shapingOnly=true §7398"))
        assertFalse(readiness.contains("ok = govName != \"HOLD\""))

        val preflight = src("engine/truth/LivePreflight7222.kt")
        assertTrue(preflight.contains("shapingOnly=true (§7398)"))
        assertFalse(preflight.contains("LaneEntryContract6342 blocks every live BUY"))
    }

    @Test
    fun economic_truth_compares_like_for_like_including_owned_treasury() {
        val invariant = src("engine/truth/AcceptanceInvariantAuthority6501.kt")
        assertTrue(invariant.contains("PaperCapitalAuthority6577.treasurySol7294()"))
        assertTrue(invariant.contains("val reported = cash + openMv + treasury7398"))
    }

    @Test
    fun authoritative_live_open_count_is_mode_scoped() {
        val health = src("engine/PipelineHealthCollector.kt")
        assertTrue(health.contains(".openPositions().count { it.mode.equals(\"live\", ignoreCase = true) }"))
    }

    @Test
    fun crypto_universe_stamps_candidate_and_submit_producer_boundaries() {
        val crypto = src("perps/CryptoAltTrader.kt")
        // The CANDIDATE/SUBMIT producer stamps now live at the one canonical submit
        // boundary (CanonicalEntryAuthority6551.submit), which crypto calls with CRYPTO_ALT.
        val admission = crypto.substringAfter("val canonicalCryptoAdmission6565 = com.lifecyclebot.engine.truth.CanonicalEntryAuthority6551.submit(")
            .take(600)
        assertTrue(admission.contains("assetClass = com.lifecyclebot.engine.truth.AssetClass.CRYPTO_ALT,"))
        val contract = src("engine/truth/CanonicalAssetEntryContract6551.kt")
        val submit = contract.substringAfter("fun submit(candidate: CanonicalAssetEntryCandidate6551)")
            .substringBefore("KillSwitch.checkEntry7835")
        assertTrue(submit.contains("CanonicalEntryAuthority6540.markCandidateFor6551(candidate.assetClass"))
        assertTrue(submit.contains("CanonicalEntryAuthority6540.markSubmitFor6551(candidate.assetClass"))
    }
}
