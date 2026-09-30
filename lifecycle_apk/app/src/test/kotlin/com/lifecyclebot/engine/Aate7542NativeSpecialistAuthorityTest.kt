package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7542NativeSpecialistAuthorityTest {
    private fun src(path:String)=File("src/main/kotlin/com/lifecyclebot/"+path).readText()

    @Test fun all_twelve_native_brains_are_called_by_one_bridge(){
        val b=src("engine/SpecialistBrainBridge7542.kt")
        listOf("QualityTraderAI.evaluate","BlueChipTraderAI.evaluate","ShitCoinTraderAI.evaluate",
            "ShitCoinExpress.evaluate","MoonshotTraderAI.scoreToken","ProjectSniperAI.assessTarget",
            "DipHunterAI.evaluate","ManipulatedTraderAI.evaluate","TreasuryBrain.evaluate",
            "CashGenerationAI.evaluate","CyclicTradeEngine.evaluateCandidate7542").forEach{
            assertTrue("missing native call $it",b.contains(it))
        }
        assertTrue(b.contains("out[\"CORE\"]=op"))
    }

    @Test fun toolkit_cannot_resurrect_native_rejected_lane(){
        val t=src("engine/ToolkitSignalSheet.kt")
        assertTrue(t.contains("SpecialistBrainBridge7542.evaluate(ts)"))
        assertTrue(t.contains("deskHypotheses.remove(lane)"))
        assertTrue(t.contains("NATIVE_BRAIN_VETO_APPLIED_7542_"))
    }

    @Test fun learning_uses_same_native_opinions_not_specialist_surrogates(){
        val v=src("learning/LayerVoteSampler.kt")
        assertTrue(v.contains("SpecialistBrainBridge7542.evaluate(ts)"))
        assertTrue(v.contains("\"TreasuryBrain\""))
        assertTrue(v.contains("\"CyclicTradeEngine\""))
        assertFalse(v.substringAfter("fun captureAllMemeVotes").substringBefore("// ── Per-layer vote predicates").contains("::voteProjectSniper"))
        assertFalse(v.substringAfter("fun captureAllMemeVotes").substringBefore("// ── Per-layer vote predicates").contains("::voteCashGen"))
    }

    @Test fun cyclic_native_brain_is_pure_decision_surface(){
        val c=src("engine/CyclicTradeEngine.kt")
        val body=c.substringAfter("fun evaluateCandidate7542").substringBefore("// ──")
        assertFalse(body.contains("treasuryBuy("))
        assertFalse(body.contains("TradeAuthorizer.authorize("))
        assertFalse(body.contains("paperSell("))
    }
}
