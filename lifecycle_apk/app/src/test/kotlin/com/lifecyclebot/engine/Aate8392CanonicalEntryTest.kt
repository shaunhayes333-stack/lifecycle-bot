package com.lifecyclebot.engine
import org.junit.Test
import org.junit.Assert.*
import com.lifecyclebot.engine.truth.*
import com.lifecyclebot.engine.cortex.CortexInversion8310
import java.io.File
class Aate8392CanonicalEntryTest {
    private fun src(s:String)=File("src/main/kotlin/com/lifecyclebot/$s.kt").readText()
    @Test fun removedModeCannotRunOrBeRestoredBySavedSettings() {
        for(n in listOf("Entry8114Mode8326","Entry8114Gate8326","Entry8114Shadow8326"))
            assertFalse(File("src/main/kotlin/com/lifecyclebot/engine/truth/$n.kt").exists())
        val config=src("data/BotConfig")
        assertFalse(config.contains("val entry8114ModeEnabled"))
        assertTrue(config.contains("remove(\"entry_8114_mode_enabled_8326\")"))
        assertFalse(src("ui/SettingsBottomSheet").contains("switchEntry8114Mode"))
        assertFalse(File("src/main/res/layout/dialog_settings.xml").readText().contains("switchEntry8114Mode"))
    }
    @Test fun onePathKeepsAdmissionAndNeverCallsAlternateVerdict() {
        val g=src("engine/truth/LiveEdgeGate7877")
        assertTrue(g.contains("canonicalRefusal8392(ts, l, paper, nowMs, candidateVersion, candidateAdmission)"))
        assertFalse(g.contains("entryRefusal8114"));assertFalse(g.contains("inLegacy8326"))
        assertTrue(src("engine/FinalDecisionGate").contains("val canonicalAdmission8392 = candidateAdmission"))
    }
    @Test fun hardSafetyClassificationIsPreserved() {
        listOf("RUG","HARD_BLOCK","WALLET_BLIND","IDENTITY","ROUTE_MIN_NOT_FUNDABLE","CANONICAL_ENTRY_EVAL_FAILED_8392").forEach {
            assertTrue(it,FdgGate8365.hardSafety8392(it))
        }
        assertFalse(FdgGate8365.hardSafety8392("SETUP_RECORD_LOSING_THIN_ADVISORY_8257"))
    }
    @Test fun specialistAliasesDoNotChangeOwners() {
        assertEquals("EXPRESS",CanonicalLaneIdentity6506.desk8392("SHITCOIN_EXPRESS"))
        assertEquals("MOONSHOT",CanonicalLaneIdentity6506.desk8392("MOONSHOT"))
        assertFalse(EvidenceHold8336.heldLane8342("CRYPTO_ALT"))
        assertTrue(EvidenceHold8336.heldLane8342("SHITCOIN_EXPRESS"))
    }
    @Test fun liveSizingKeepsRecentLossAndIndependentProof() {
        val r=src("engine/truth/LiveRiskPolicy7807")
        assertTrue(r.contains("LiveConfidence8296.recencyAdjusted8296("))
        assertTrue(r.contains("LiveSizingTruth8136.drawdownWaiver8136("))
        assertTrue(r.contains("SizingNetEdge8153.laneEnvelopeRoomSol8153("))
        assertTrue(r.contains("recentKellyGate8296(ts, l, validated8296)"))
        assertFalse(r.contains("entry8114Admitted"))
    }
    @Test fun lossLockoutsCannotBeBypassedByLegacyScope() {
        val r=src("engine/RebuyLockout8019")
        assertTrue(r.contains("if (fallingKnife8130(change1h8130, ticket))"))
        assertTrue(r.contains("if (bigLossLocked8130(mint, nowMs, ticket))"))
        assertFalse(r.contains("legacyScope8326"))
    }
    @Test fun invertedRefusalsStillStandDownWithoutAdmittingTheCoin() {
        assertEquals("LANE",CortexInversion8310.shadowWhy8310(false,41.0,38.7,12.0,-34.0,0.0,Double.NaN,0.0,Double.NaN))
        assertNull(CortexInversion8310.shadowWhy8310(false,41.0,-13.0,12.0,5.0,0.0,Double.NaN,0.0,Double.NaN))
        assertTrue(src("engine/cortex/Cortex7885").contains("shadowInvertedRefusal8310(ts, a)"))
        assertFalse(src("engine/cortex/Cortex7885").contains("fun entryRefusal8114"))
    }
    @Test fun finalVerdictOwnsTelemetryAndKeepsPaperSeparate() {
        val s=src("engine/truth/LiveEdgeGate7877")
        assertTrue(s.contains("entryContext8392.get()?.let { it.assessment = a; return }"))
        assertTrue(s.contains("if (entryContext8392.get() != null) return"))
        assertTrue(s.contains("if (paper && final == null) Rehearsal8339.notePaperAdmit8342"))
        assertTrue(s.contains("val paper8342 = paper"))
        assertTrue(s.contains("if (!paper && core == null && hold != null && final == hold)"))
    }
    @Test fun existingHoldAndShadowRecoveryAreNotDeletedWithTheMode() {
        val s=src("engine/truth/LiveEdgeGate7877")
        assertTrue(s.contains("EvidenceHold8336.refusal8336(ts, l, false, nowMs)"))
        assertTrue(s.contains("EvidenceShadow8344.open8344(ts, l, nowMs)"))
        assertTrue(s.contains("ExploreShadow8347.liveEntryWait8347("))
        assertTrue(s.contains("ExploreShadow8347.nicheRefusal8351("))
    }
}
