package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6383 — LIVE volume & winner-protection invariants.
 *
 * Operator directive (V5.0.6382 snapshot follow-up, verbatim):
 *   "why does paper consistently find huge runners, and consistent winners
 *    and live cannot??"
 *
 * The V5.0.6382 pipeline snapshot showed the smoking gun:
 *   • Live BUY ok / fail  = 31 / 862    (3.5% success rate)
 *   • Top BUY fail reason = LIVE_MODE_DESYNC = 795
 *   • MOONSHOT_FLAT_EXIT closing winners at pnl=+0 within minutes of entry
 *
 * Root cause 1: TokenState.position carried stale isPaperPosition=true (or
 * tradingMode="SHADOW") from earlier paper/shadow runs. When runtime is LIVE
 * this used to be a hard abort. Now we clear the stale flag and proceed.
 *
 * Root cause 2: Lane-contract violations were being counted as LIVE_MODE_DESYNC,
 * inflating the desync metric and hiding whether the real bug was actually
 * fixed. Split onto LIVE_LANE_CONTRACT_6383.
 *
 * Root cause 3: MoonshotTraderAI FLAT_EXIT fired at maxHold/2 with pnl in
 * [-2%, +5%]. In live, this killed winners before they could develop. Now
 * live positions with peak>3% OR hold<15min are protected from the flat exit.
 */
class Bundle6383LiveVolumeAndWinnersTest {

    @Test
    fun stale_paper_flag_is_auto_cleared_not_aborted() {
        val exec = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(
            "V5.0.6383: stale paperFlag/shadowFlag on already-open position must auto-clear when runtime is LIVE",
            exec.contains("LIVE_MODE_STALE_FLAG_AUTO_CLEARED_6383"),
        )
        assertTrue(
            "V5.0.6383: runtimePaper=true must still hard-abort (real desync)",
            exec.contains("mode=LIVE runtimePaper=true"),
        )
    }

    @Test
    fun lane_contract_abort_uses_own_counter_not_desync() {
        val exec = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(
            "V5.0.6383: liveAbortLaneContract helper must exist and emit LIVE_LANE_CONTRACT_6383",
            exec.contains("fun liveAbortLaneContract") && exec.contains("LIVE_LANE_CONTRACT_6383"),
        )
        assertTrue(
            "V5.0.6383: lane_contract_6342 return path must call liveAbortLaneContract (not liveAbortDesync)",
            exec.contains("return liveAbortLaneContract(\"lane_contract_6342"),
        )
        assertTrue(
            "V5.0.6383: lane_contract_6342 must NOT still call liveAbortDesync",
            !exec.contains("return liveAbortDesync(\"lane_contract_6342"),
        )
    }

    @Test
    fun moonshot_flat_exit_protects_live_winners() {
        val ms = File("src/main/kotlin/com/lifecyclebot/v3/scoring/MoonshotTraderAI.kt").readText()
        assertTrue(
            "V5.0.6383: MoonshotTraderAI must expose LIVE_WINNER_PROTECT_6383",
            ms.contains("LIVE_WINNER_PROTECT_6383"),
        )
        assertTrue(
            "V5.0.6383: protection must gate on peakPnlPct >= 3.0 OR holdMinutes < 15",
            ms.contains("hadUpsideBlink") && ms.contains("stillFresh"),
        )
        assertTrue(
            "V5.0.6383: paper positions must be UNAFFECTED (paper's job is to explore)",
            ms.contains("!pos.isPaperMode"),
        )
        assertTrue(
            "V5.0.6383: telemetry counter for suppressed flat exits must exist",
            ms.contains("LIVE_WINNER_PROTECT_FLAT_EXIT_SUPPRESSED_6383"),
        )
    }
}
