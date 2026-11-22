package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AgreementPocket8874
import com.lifecyclebot.engine.truth.ProofBar8947
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8947 — the proof bar is tiered: 20+ coins that hold (mean above zero by two SE, positive without the top three,
 * both halves positive) open the careful path; 50+ that hold with the newest 20 positive admit at full size.
 */
class Aate8947TieredProofTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private fun steady(n: Int, win: Double = 40.0, loss: Double = -10.0) = List(n) { if (it % 2 == 0) win else loss }

    @Test fun twentyCoinsThatHoldOpenCautiouslyAndFiftyAdmitInFull() {
        assertEquals(ProofBar8947.NONE_8947, ProofBar8947.tier8947(steady(19)))
        assertEquals("20 coins can already prove a strong edge", ProofBar8947.CAUTIOUS_8947, ProofBar8947.tier8947(steady(20)))
        assertEquals(ProofBar8947.CAUTIOUS_8947, ProofBar8947.tier8947(steady(49)))
        assertEquals(ProofBar8947.FULL_8947, ProofBar8947.tier8947(steady(50)))
        // the newest 20 turn negative: full drops to cautious while the record as a whole still holds
        val turned = List(60) { if (it % 3 != 0) 60.0 else -10.0 } + List(20) { if (it < 8) 20.0 else -20.0 }
        assertTrue(ProofBar8947.holds8947(turned))
        assertEquals(ProofBar8947.CAUTIOUS_8947, ProofBar8947.tier8947(turned))
        assertEquals(ProofBar8947.NONE_8947, ProofBar8947.tier8947(steady(30) + listOf(Double.NaN)))
    }

    @Test fun aRunnerEdgeHoldsAndALuckyStreakDoesNot() {
        // the runner edge: 60 coins, one in ten a +400 % runner, the rest -5 %: the median is negative and it still holds
        val runners = List(60) { if (it % 10 == 0) 400.0 else -5.0 }
        assertTrue(ProofBar8947.holds8947(runners))
        assertEquals(ProofBar8947.FULL_8947, ProofBar8947.tier8947(runners))
        // three runners carry 80 coins that otherwise lose: not proven
        val lucky = List(77) { if (it % 3 == 0) 5.0 else -12.0 } + listOf(400.0, 400.0, 400.0)
        assertFalse(ProofBar8947.holds8947(lucky))
        assertEquals(ProofBar8947.NONE_8947, ProofBar8947.tier8947(lucky))
        // all of the gain in three coins: not proven
        assertFalse(ProofBar8947.holds8947(List(17) { -1.0 } + listOf(60.0, 60.0, 60.0)))
        // positive overall but all of it in the older half
        val faded = List(30) { if (it % 2 == 0) 80.0 else 0.0 } + List(30) { if (it % 2 == 0) 5.0 else -8.0 }
        assertFalse("the newer half must pay on its own", ProofBar8947.holds8947(faded))
    }

    @Test fun theCarefulPathOpensOnTheCautiousTierAndFullSizeNeedsFull() {
        assertTrue(AgreementPocket8874.cautious8947("MOONSHOT", null, emptyList(), steady(20)))
        assertFalse("not a meme lane", AgreementPocket8874.cautious8947("BLUECHIP_X", null, steady(20)))
        assertFalse("no record holds", AgreementPocket8874.cautious8947("MOONSHOT", null, steady(19), emptyList()))
        val losing = doubleArrayOf(40.0, -800.0, 40.0 * 400.0, 0.0)
        assertFalse("the lane's own pocket labels proven losing", AgreementPocket8874.cautious8947("MOONSHOT", losing, steady(40)))
        val pocket = src("engine/truth/AgreementPocket8874.kt")
        assertTrue(pocket.contains("coinEvidenceOf8895(l, laneRec, research) != null || cautious8947(l, laneRec, *books8947)"))
        assertTrue(pocket.contains("if (!ready || ProofBar8947.tier8947(coinValues8947()) != ProofBar8947.FULL_8947) null"))
        assertTrue("the full-size call is unchanged", pocket.contains("else (evidenceOf8874(key, recordOf(key)) ?: coinEvidence8895(raw.lane, recordOf(key)))"))
        assertTrue(src("engine/truth/BasePocket8904.kt").contains(
            "else if (ProofBar8947.tier8947(values8947(if (origin == \"PUMP\") pumpCoins else coins)) != ProofBar8947.FULL_8947) null"))
        assertTrue(ProofBar8947.line8947(steady(50)).startsWith("FULL n50 med"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 135. The proof bar is tiered (8947)"))
    }
}
