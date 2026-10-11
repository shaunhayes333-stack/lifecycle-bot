package com.lifecyclebot.engine

import com.lifecyclebot.engine.execution.TxParseHelper
import com.lifecyclebot.engine.truth.OnChainCost8024
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.io.File

/** Trade One: only actual wallet-owner, per-token-account blockchain deltas. */
class Aate8038ChainProofAndReplayTest {
    private fun row(owner: String, mint: String, raw: String, index: Int): JSONObject =
        JSONObject().put("owner", owner).put("mint", mint).put("accountIndex", index)
            .put("uiTokenAmount", JSONObject().put("amount", raw).put("decimals", 9))

    @Test fun twoOwnedTokenAccountsOfOneMintAreAddedNotOverwritten() {
        val meta = JSONObject()
            .put("preTokenBalances", JSONArray().put(row("owner", "mint", "100", 1)).put(row("owner", "mint", "200", 2)))
            .put("postTokenBalances", JSONArray().put(row("owner", "mint", "110", 1)).put(row("owner", "mint", "250", 2))
                .put(row("someoneElse", "mint", "9999999", 3)))
        val parsed = TxParseHelper.tokenDeltasFromOwnerBalances8038(meta, "owner")
        assertNotNull(parsed)
        assertEquals(BigInteger.valueOf(60), parsed!!["mint"]!!.rawDelta)
    }

    @Test fun malformedProofIsNotInventedAsZeroBalance() {
        val meta = JSONObject()
            .put("preTokenBalances", JSONArray().put(row("owner", "mint", "not-a-number", 1)))
            .put("postTokenBalances", JSONArray())
        assertNull(TxParseHelper.tokenDeltasFromOwnerBalances8038(meta, "owner"))
        val badDecimals = JSONObject().put("preTokenBalances", JSONArray().put(row("owner", "mint", "123", 1)))
            .put("postTokenBalances", JSONArray().put(row("owner", "mint", "456", 1).put("uiTokenAmount",
                JSONObject().put("amount", "456").put("decimals", 6))))
        assertNull(TxParseHelper.tokenDeltasFromOwnerBalances8038(badDecimals, "owner"))
    }

    @Test fun chainBasisRejectsUnknownTransfersAndTruncatedHistory() {
        fun mv(slot: Long, tokens: Long, lamports: Long) = OnChainCost8024.Move(0L, slot, BigInteger.valueOf(tokens), lamports)
        val r = OnChainCost8024.replay8024(listOf(mv(1, 100, -50_000_000), mv(2, 50, 0)))
        assertEquals(BigInteger.valueOf(150), r.heldRaw)
        assertEquals(BigInteger.valueOf(50), r.unpricedHeldRaw)
        val incomplete = OnChainCost8024.replay8024(listOf(mv(1, -80, 2_000_000), mv(2, 100, -10_000_000)))
        assertEquals(BigInteger.valueOf(80), incomplete.missingPriorRaw)
    }

    @Test fun netPercentUsesSoldLotBasisIncludingActualFees() {
        assertEquals(9.9, TradeHistoryStore.verifiedNetReturnPct8038(0.001, 0.0011, 0.000001)!!, 1e-8)
        assertNull(TradeHistoryStore.verifiedNetReturnPct8038(0.0, 0.0011, 0.0))
        val replay = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperTransaction6486.kt").readText()
        assertTrue(replay.contains("HISTORY_LEDGER_ONLY_JOURNAL_REPAIR_8038"))
        assertTrue(replay.contains("realizedPnlDeltaSol = sell.realizedPnlSol"))
    }
}
