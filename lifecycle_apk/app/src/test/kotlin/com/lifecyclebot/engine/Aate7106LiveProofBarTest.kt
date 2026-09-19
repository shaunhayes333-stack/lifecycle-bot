package com.lifecyclebot.engine

import com.lifecyclebot.engine.lab.LabAssetClass
import com.lifecyclebot.engine.lab.LabPromotedFeed
import com.lifecyclebot.engine.lab.LabStrategy
import com.lifecyclebot.engine.lab.LabStrategyStatus
import com.lifecyclebot.engine.lab.LlmLabStore
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5.0.7106 — an LLM-authored strategy may direct real money only after it has
 * cleared a bar strictly above paper promotion, and only up to a rolling cap.
 *
 * The property that matters most is the LAST test: a refusal is an instruction
 * to ignore the nudge, never to skip the trade. Everything else here is the
 * bar; that one is the reason the bar is safe to run unattended.
 */
class Aate7106LiveProofBarTest {

    // V5.0.7107 — the cap is now 25% of spendable cash, so every case needs a
    // known basis. 8.0 SOL cash => a 2.0 SOL per-strategy rolling cap, which is
    // exactly the figure 7106's fixed constant used, so the cases below still
    // read as they did.
    @Before fun setUp() { LabPromotedFeed.setCashForTest7107(8.0) }
    @After fun tearDown() { LabPromotedFeed.setCashForTest7107(null) }

    private fun strategy(
        id: String,
        trades: Int,
        wins: Int,
        pnlSol: Double,
        status: LabStrategyStatus = LabStrategyStatus.PROMOTED,
    ): LabStrategy = LabStrategy(
        id = id,
        name = "T7106-$id",
        rationale = "test",
        asset = LabAssetClass.MEME,
        entryScoreMin = 50, entryRegime = "ANY",
        takeProfitPct = 20.0, stopLossPct = -8.0, maxHoldMins = 60,
        sizingSol = 0.1, generation = 2, status = status,
        paperTrades = trades, paperWins = wins, paperPnlSol = pnlSol,
    )

    @Test
    fun paperPromotionAloneNoLongerBuysRealMoneyAuthority() {
        // Exactly the pre-7106 bar: 30 trades, 33% WR, 0.05 SOL. That used to be
        // both promotion AND live spend authority.
        val s = strategy("s7106a", trades = 30, wins = 10, pnlSol = 0.05)
        LlmLabStore.addStrategy(s)
        val refusal = LabPromotedFeed.liveNudgeRefusal7106(s.id, 0.05)
        assertNotNull("paper-bar proof must no longer authorise live spend", refusal)
        assertTrue("refusal names the axis that failed: $refusal", refusal!!.startsWith("LIVE_BAR_TRADES"))
    }

    @Test
    fun clearingTheLiveBarAuthorisesWithoutAnyHumanTap() {
        val s = strategy("s7106b", trades = 60, wins = 30, pnlSol = 0.20)
        LlmLabStore.addStrategy(s)
        assertNull(
            "60 trades at 50% WR and +0.20 SOL clears the live bar unattended",
            LabPromotedFeed.liveNudgeRefusal7106(s.id, 0.05),
        )
    }

    @Test
    fun anUnpromotedStrategyIsRefusedWhateverItsStats() {
        val s = strategy("s7106c", trades = 200, wins = 150, pnlSol = 5.0, status = LabStrategyStatus.ACTIVE)
        LlmLabStore.addStrategy(s)
        assertTrue(
            "promotion is a precondition, not a formality",
            LabPromotedFeed.liveNudgeRefusal7106(s.id, 0.05) == "NOT_PROMOTED",
        )
    }

    @Test
    fun anOperatorRevokeOutranksEveryAmountOfProof() {
        val s = strategy("s7106d", trades = 500, wins = 400, pnlSol = 20.0)
        LlmLabStore.addStrategy(s)
        LabPromotedFeed.revokeLiveAuthority(s.id)
        assertTrue(
            "the operator's hand must outrank the evidence",
            LabPromotedFeed.liveNudgeRefusal7106(s.id, 0.05) == "OPERATOR_REVOKED",
        )
        LabPromotedFeed.grantLiveAuthority(s.id)
        assertNull("and the grant must lift it again", LabPromotedFeed.liveNudgeRefusal7106(s.id, 0.05))
    }

    @Test
    fun anOperatorGrantSubstitutesForTheProofBarButNotForTheCap() {
        // Revoke is honoured; a grant must be honoured too, or the operator's
        // hand works in one direction only.
        val s = strategy("s7106g", trades = 1, wins = 0, pnlSol = -1.0, status = LabStrategyStatus.ACTIVE)
        LlmLabStore.addStrategy(s)
        assertNotNull("unproven and ungranted is refused", LabPromotedFeed.liveNudgeRefusal7106(s.id, 0.05))
        LabPromotedFeed.grantLiveAuthority(s.id)
        assertNull("an explicit grant substitutes for the proof bar", LabPromotedFeed.liveNudgeRefusal7106(s.id, 0.05))

        // ...but the cap still binds. Trusting a strategy is not the same as
        // letting it spend without limit.
        repeat(20) { LabPromotedFeed.recordLiveSpend7106(s.id, 0.1) }
        val capped = LabPromotedFeed.liveNudgeRefusal7106(s.id, 0.1)
        assertNotNull("the exposure cap outranks an operator grant", capped)
        assertTrue("$capped", capped!!.startsWith("EXPOSURE_CAP"))
    }

    @Test
    fun theRollingExposureCapBindsAndNamesItself() {
        val s = strategy("s7106e", trades = 60, wins = 30, pnlSol = 0.20)
        LlmLabStore.addStrategy(s)
        assertNull(LabPromotedFeed.liveNudgeRefusal7106(s.id, 0.1))
        // Spend up to the cap.
        repeat(20) { LabPromotedFeed.recordLiveSpend7106(s.id, 0.1) }  // 2.0 SOL
        val refusal = LabPromotedFeed.liveNudgeRefusal7106(s.id, 0.1)
        assertNotNull("one strategy must not direct unbounded real money", refusal)
        assertTrue("the refusal states the arithmetic: $refusal", refusal!!.startsWith("EXPOSURE_CAP"))
    }

    @Test
    fun theCapMovesWithTheCashBalance() {
        val s = strategy("s7106h", trades = 60, wins = 30, pnlSol = 0.20)
        LlmLabStore.addStrategy(s)
        repeat(20) { LabPromotedFeed.recordLiveSpend7106(s.id, 0.1) }   // 2.0 SOL spent

        // At 8 SOL cash the cap is 2.0 and 2.0 + 0.1 is over it.
        assertNotNull("bound at the smaller balance", LabPromotedFeed.liveNudgeRefusal7106(s.id, 0.1))

        // Grow the account and the SAME spend is comfortably inside the cap.
        // This is the property the operator asked for: one rule at every size.
        LabPromotedFeed.setCashForTest7107(40.0)                        // cap = 10.0
        assertNull("the cap must grow with the cash", LabPromotedFeed.liveNudgeRefusal7106(s.id, 0.1))

        // And shrink with it.
        LabPromotedFeed.setCashForTest7107(4.0)                         // cap = 1.0
        assertNotNull("and tighten when the account draws down", LabPromotedFeed.liveNudgeRefusal7106(s.id, 0.1))
    }

    @Test
    fun anUnreadableCashBalanceIsNotAVerdictAboutTheStrategy() {
        val s = strategy("s7106i", trades = 60, wins = 30, pnlSol = 0.20)
        LlmLabStore.addStrategy(s)
        LabPromotedFeed.setCashForTest7107(0.0)
        val refusal = LabPromotedFeed.liveNudgeRefusal7106(s.id, 0.05)
        assertNotNull(refusal)
        assertTrue(
            "a missing cash reading must name itself, not masquerade as a failed proof bar: $refusal",
            refusal == "EXPOSURE_CAP_CASH_UNKNOWN_OR_ZERO",
        )
    }

    @Test
    fun paperSpendNeverConsumesTheRealMoneyCap() {
        // recordLiveSpend7106 is only ever called on a live buy. Guard the
        // contract that zero/negative/non-finite input cannot move the ledger,
        // since a paper path wiring itself in by mistake is exactly how a cap
        // that bounds real money ends up bounded by fake money.
        val s = strategy("s7106f", trades = 60, wins = 30, pnlSol = 0.20)
        LlmLabStore.addStrategy(s)
        LabPromotedFeed.recordLiveSpend7106(s.id, 0.0)
        LabPromotedFeed.recordLiveSpend7106(s.id, -5.0)
        LabPromotedFeed.recordLiveSpend7106(s.id, Double.NaN)
        LabPromotedFeed.recordLiveSpend7106("", 1.0)
        assertNull(
            "no real spend happened, so nothing may be charged against the cap",
            LabPromotedFeed.liveNudgeRefusal7106(s.id, 0.1),
        )
    }
}
