package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExploreShadow8347
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8368: all Cortex verdicts get their own shadow episodes in the specialist's lane.
 * A gate refusal is NOT a Cortex NO and an executor-only shadow fill is NOT a Cortex label.
 * Shadow results never imply an actual chain receipt or grant unvalidated live authority.
 */
class Aate8368CortexShadowVerdictTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun cortexYesNoAndHoldAreThreeDistinctShadowTradePopulations() {
        val x = ExploreShadow8347
        assertEquals(x.KIND_CORTEX_YES_8368, x.cortexKind8368("STRONG"))
        assertEquals(x.KIND_CORTEX_NO_8368, x.cortexKind8368("REFUSE"))
        assertEquals(x.KIND_CORTEX_HOLD_8368, x.cortexKind8368("NEUTRAL"))
        val base = x.ticketKey8360("Mint1", "SHITCOIN", "FIRST_DIP")
        val yes = x.cortexTicketKey8368(base, x.KIND_CORTEX_YES_8368)
        val no = x.cortexTicketKey8368(base, x.KIND_CORTEX_NO_8368)
        val hold = x.cortexTicketKey8368(base, x.KIND_CORTEX_HOLD_8368)
        assertEquals(base, x.cortexTicketKey8368(base, x.KIND_NATIVE_8360))
        assertEquals(4, setOf(base, yes, no, hold).size)
        for (kind in listOf(x.KIND_CORTEX_YES_8368, x.KIND_CORTEX_NO_8368, x.KIND_CORTEX_HOLD_8368)) {
            assertTrue(x.opens8360(kind))
            assertEquals("each verdict models both exit doctrines without six overlapping tickets", 2, x.legsFor8360(kind).size)
        }
        assertFalse(yes.contains("REJECT"))
        assertFalse(no.contains("NATIVE"))
    }

    @Test fun cortexVoterAssessmentFeedsShadowWithoutRewritingLiveDecision() {
        val c = src("engine/cortex/Cortex7885.kt")
        val hook = c.indexOf("ExploreShadow8347.onCortexAssessment8368(")
        assertTrue("Cortex must deliver ITS OWN bucket to shadow", hook > 0)
        assertTrue("use the actual assessed bucket not an FDG allow/reject proxy", c.contains("ts, a.lane, a.bucket.name, a.fused.pWin, nowMs"))
        assertTrue("cache the exact decision first", c.lastIndexOf("assessCache[key] = a", hook) > 0)
        assertTrue("the shadow hook must not cancel the authoritative assessment", hook < c.indexOf("return a", hook))
        assertTrue("voters still grade both admitted and rejected forward decisions", c.contains("ledger.grade(p.a.lane, p.a.ids") &&
            c.contains("board.record(p.a.lane, p.a.bucket, p.legacyAdmitted"))
    }

    @Test fun cortexTradeOutcomesArePricedAndPersistedButCannotMasqueradeAsNativeOrOnChain() {
        val e = src("engine/truth/ExploreShadow8347.kt")
        assertTrue("the YES/NO/HOLD records must be delivered whether FDG buys or not", e.contains("fun onCortexAssessment8368("))
        assertTrue("observed decision mark required", e.contains("val mark = ShadowMark8360.observed8360(ts, ts.mint, nowMs)"))
        assertTrue("missing marks are not imaginary fills", e.contains("skip8360(\"CORTEX_NO_OBSERVED_MARK_8368\")"))
        assertTrue("hard-safety candidates never become fake executable trades", e.contains("if (ts.safety.isBlocked)"))
        assertTrue("wrong venue cannot become a synthetic Solana PnL", e.contains("HeliusSolanaScope7819.isSolanaMint7819(ts.mint)"))
        assertTrue("Cortex tickets have separate identities", e.contains("cortexTicketKey8368(key, kind)"))
        assertTrue("Cortex verdicts are a separate learned result population", e.contains("bookKey8351(\"C|\$v|CX:\$verdict\", net, nowMs)"))
        assertTrue("Cortex results train setup cohorts", e.contains("CXS:\$verdict:"))
        assertTrue("Cortex results train tactic cohorts", e.contains("CXT:\$verdict:"))
        assertTrue("Cortex results don't enter native variant/evidence books", e.contains("KIND_CORTEX_YES_8368, KIND_CORTEX_NO_8368, KIND_CORTEX_HOLD_8368 -> {"))
        assertTrue("results tracked after entry and exit, not at vote-time", e.contains("cortexClosesByKind8368.computeIfAbsent(t.kind8360)"))
        assertTrue("live simulator cannot invent a real wallet/journal row", !e.substringAfter("fun onCortexAssessment8368(").substringBefore("private fun open8347(")
            .contains("CanonicalFinalizedTradeBus6464.publish"))
    }

    @Test fun nativeHardSellabilityStopsShadowButStrategyRefusalsStillLearn() {
        assertTrue(ExploreShadow8347.cortexNativeHardStop8368("NATIVE_SELLABILITY_REJECT"))
        assertTrue(ExploreShadow8347.cortexNativeHardStop8368("HARD_BLOCK_HONEYPOT"))
        assertTrue(ExploreShadow8347.cortexNativeHardStop8368("TOKEN2022_FREEZE"))
        assertFalse(ExploreShadow8347.cortexNativeHardStop8368("NATIVE_THRESHOLD_FAIL_SCORE"))
        assertFalse(ExploreShadow8347.cortexNativeHardStop8368("NATIVE_NO_CONTINUATION"))
        assertFalse(ExploreShadow8347.cortexNativeHardStop8368(null))
        val e = src("engine/truth/ExploreShadow8347.kt")
        assertTrue(e.contains("CORTEX_NATIVE_HARD_UNROUTABLE_8368"))
        assertTrue(e.contains("cachedSnapshot7650(ts.mint)"))
    }

    @Test fun auditsRevealCortexCoverageIncludingUnpricedAndResourceLimitedEpisodes() {
        val e = src("engine/truth/ExploreShadow8347.kt")
        assertTrue(e.contains("cortexSeen8368.incrementAndGet()"))
        assertTrue(e.contains("CORTEX_NON_SOLANA_SHADOW_UNROUTABLE_8368"))
        assertTrue(e.contains("CORTEX_HARD_SAFETY_8368"))
        assertTrue(e.contains("SKIP_FULL_8360") && e.contains("SKIP_LANE_FULL_8360"))
        assertTrue(e.contains("cortex8368[seen="))
        assertTrue(e.contains("opened=") && e.contains("legsClosed="))
        assertTrue("Cortex YES must NEVER count as native proof by accident", e.contains("KIND_NATIVE_8360 && leg.entry == Entry8347.NOW"))
    }
}
