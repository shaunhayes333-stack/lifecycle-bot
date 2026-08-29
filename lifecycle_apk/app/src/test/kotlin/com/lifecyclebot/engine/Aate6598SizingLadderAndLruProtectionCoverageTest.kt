package com.lifecyclebot.engine

import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

/**
 * V5.0.6598 — REPAIR 3 (sizing ladder honored past authority cap) +
 * REPAIR 5 (watchlist LRU protects active / open / recent entries).
 *
 * Operator directive Feb 2026 (V5.0.6595 execution-liveness):
 *
 *   REPAIR 3: 'Do not let five successive 0.x multipliers multiply a
 *   valid order into dust ... If final BUY risk budget can afford the
 *   minimum executable notional: clamp the executable order to canonical
 *   minimum. If it cannot: convert the decision to an explicit
 *   NO_BUY/SIZE_BELOW_MIN before creating execution intent.'
 *
 *   REPAIR 5: 'Do not evict: active evaluation candidates, hot
 *   candidates, token-map in-flight candidates, candidates accumulating
 *   required lane history, candidates with pending canonical intent,
 *   open positions ... Retention horizon must be at least long enough to
 *   satisfy the history requirement used by EXPRESS/MOONSHOT/SHITCOIN/
 *   DIP_HUNTER decisions.'
 *
 * Snapshot 6595 evidence:
 *   - RunnerCompounding recommendedSizeSol=0.400 -> OrderSizeResolver
 *     req=0.010 risk=0.010 ladder=0.400 -> final=0 BELOW_MIN_EXECUTABLE
 *     (the authority cap re-imposed `requested`=0.010 on top of the
 *     legitimate 0.400 ladder lift)
 *   - 144 WATCHLIST_LRU_EVICT_6287 in ~5.5min, victims 7-9s old
 *     (thin-data lanes then complain hist=2)
 */
class Aate6598SizingLadderAndLruProtectionCoverageTest {

    @Test
    fun aate6598_sizing_authority_cap_honors_ladder_lift() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/OrderSizeResolver6441.kt"
        ).readText()
        assertTrue(
            "V5.0.6598: ladder rescue must fire only when (a) requested < minExec AND " +
                "(b) ladderTarget >= minExec*3 — narrow enough to preserve existing " +
                "sub-floor rejection contracts while catching the 6595 case (req=0.010 " +
                "ladder=0.400 minExec=~0.05)",
            src.contains("ladderRescueApplies6598") &&
                src.contains("ladderTarget >= (minExec * 3.0)") &&
                src.contains("requestedLamports6491 < minExecLamports6491")
        )
        assertTrue(
            "V5.0.6598: when rescue applies, authority cap uses laneClamped (fully-lifted, " +
                "wallet/lane-capped) as the ceiling — not `requested`",
            src.contains("minOf(laneClampedLamports6491, availableLamports6491)")
        )
        assertTrue(
            "V5.0.6598: when rescue does NOT apply, the conservative pre-6598 authority cap " +
                "is preserved so adaptive/sub-floor callers still see requested-ceiling behaviour",
            src.contains("minOf(requestedLamports6491, riskLamports6491, availableLamports6491, laneCapLamports6491)")
        )
    }

    @Test
    fun aate6598_watchlist_lru_protects_open_hot_recent_entries() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/GlobalTradeRegistry.kt"
        ).readText()
        assertTrue(
            "V5.0.6598: LRU must skip canonical-open mints",
            src.contains("openMints6598") &&
                src.contains("CanonicalPositionAuthority6441.openPositions()") &&
                src.contains("it.mint !in openMints6598")
        )
        assertTrue(
            "V5.0.6598: LRU must protect recently-admitted entries (< MIN_ADMISSION_AGE_MS_6598)",
            src.contains("MIN_ADMISSION_AGE_MS_6598 = 60_000L") &&
                src.contains("now - it.addedAt >= MIN_ADMISSION_AGE_MS_6598")
        )
        assertTrue(
            "V5.0.6598: LRU must protect recently-processed entries (< MIN_PROCESSING_IDLE_MS_6598)",
            src.contains("MIN_PROCESSING_IDLE_MS_6598 = 30_000L") &&
                src.contains("now - it.lastProcessedAt >= MIN_PROCESSING_IDLE_MS_6598")
        )
        assertTrue(
            "V5.0.6598: when every entry is protected, admit anyway (no silent drop) and log " +
                "WATCHLIST_LRU_EVICT_ALL_PROTECTED_6598",
            src.contains("WATCHLIST_LRU_EVICT_ALL_PROTECTED_6598")
        )
    }
}
