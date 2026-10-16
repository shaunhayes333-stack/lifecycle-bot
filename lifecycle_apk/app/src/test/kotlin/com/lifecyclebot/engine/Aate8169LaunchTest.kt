package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.engine.market.ScoutPairVerdict8169
import com.lifecyclebot.engine.market.ScoutPairVerdict8169.Verdict8169
import com.lifecyclebot.engine.truth.AdmissionBirth8144
import com.lifecyclebot.engine.truth.AdmissionBirth8144.Kind8144
import com.lifecyclebot.engine.truth.AdmissionBirth8144.Read8144
import com.lifecyclebot.engine.truth.AdmissionBirth8144.Window8144
import com.lifecyclebot.engine.truth.AdmissionBirth8144.Witness8144
import com.lifecyclebot.engine.truth.AdmissionRunnerTrace8144
import com.lifecyclebot.engine.truth.AdmissionRunnerTrace8144.Trace8144
import com.lifecyclebot.engine.truth.LaunchBirth8169
import com.lifecyclebot.engine.truth.LaunchBirth8169.PairProof8169
import com.lifecyclebot.engine.truth.RunnerCapture8017
import com.lifecyclebot.network.PairInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8169 — launch timing, sniper scope, scouts, runner capture and hand-off naming (device 5.0.8168: 4,749 sniper
 * TOO_OLD native refusals, 59 launch-tape UNKNOWN births, 0 verified scout candidates, NO_RUNNER_TRACES_CAPTURED, 383
 * hand-offs -> 10 at the FDG with the losses unnamed). Pure functions with explicit clocks, plus source contracts.
 */
class Aate8169LaunchTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private val t0 = 1_800_000_000_000L
    private val pumpMint = "9BB6NFEcjBCtnNLFko2FqVQBq8HHM13kCyYcdQbgpump"
    private val plainMint = "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263"

    // ── (1) birth: what each witness proves, named ──────────────────────────

    @Test fun aPumpMintsOwnCurvePairIsItsBirthAnyOtherPoolOnlyAFloor() {
        val b = LaunchBirth8169
        assertTrue(b.pumpMint8169(pumpMint))
        assertFalse(b.pumpMint8169(plainMint))
        assertFalse(b.pumpMint8169("shortpump"))
        assertEquals(PairProof8169.BIRTH, b.pairProof8169(pumpMint, "pumpfun", t0 - 60_000L, t0))
        assertEquals(PairProof8169.BIRTH, b.pairProof8169(pumpMint, " PumpFun ", t0 - 60_000L, t0))
        // A graduated pump coin's PumpSwap pool, or any pool of a non-pump mint, was created after the coin: a floor.
        assertEquals(PairProof8169.FLOOR, b.pairProof8169(pumpMint, "pumpswap", t0 - 60_000L, t0))
        assertEquals(PairProof8169.FLOOR, b.pairProof8169(plainMint, "pumpfun", t0 - 60_000L, t0))
        assertEquals(PairProof8169.FLOOR, b.pairProof8169(plainMint, "raydium", t0 - 60_000L, t0))
        // No time, a pre-2020 time or a future time proves nothing.
        assertEquals(PairProof8169.NONE, b.pairProof8169(pumpMint, "pumpfun", 0L, t0))
        assertEquals(PairProof8169.NONE, b.pairProof8169(pumpMint, "pumpfun", 1_000_000L, t0))
        assertEquals(PairProof8169.NONE, b.pairProof8169(pumpMint, "pumpfun", t0 + 5L * 60_000L, t0))
        assertEquals(PairProof8169.NONE, b.pairProof8169("not-a-mint", "pumpfun", t0 - 60_000L, t0))
    }

    @Test fun aPoolFloorProvesOldButNeverMakesATokenYoung() {
        val b = AdmissionBirth8144
        val floor = b.read8144(Witness8144(poolFloorMs = t0 - 10L * 60_000L), t0)
        assertEquals(Kind8144.PROVEN_OLDER_THAN, floor.kind)
        assertEquals("POOL_FLOOR", floor.source)
        assertEquals(10L * 60_000L, floor.minAgeMs)
        assertEquals(Window8144.PAST_WINDOW, b.window8144(floor, 180_000L))
        // A pool created 40 s ago does not prove a 40-second launch: unknown, the launch lane defers.
        val young = b.read8144(Witness8144(poolFloorMs = t0 - 40_000L), t0)
        assertEquals(Kind8144.PROVEN_OLDER_THAN, young.kind)
        assertEquals(Window8144.UNKNOWN_DEFER, b.window8144(young, 180_000L))
        // A direct witness (the curve pair, PAIR_CREATION) beats any floor; the earliest floor is the bound.
        assertEquals(Kind8144.KNOWN, b.read8144(Witness8144(pairCreatedMs = t0 - 50_000L, poolFloorMs = t0 - 30_000L), t0).kind)
        assertEquals("POOL_FLOOR", b.read8144(Witness8144(poolFloorMs = t0 - 900_000L, firstSeenMs = t0 - 30_000L), t0).source)
        assertEquals("HISTORY_FLOOR", b.read8144(Witness8144(historyFloorMs = t0 - 900_000L, poolFloorMs = t0 - 600_000L), t0).source)
    }

    @Test fun launchTapeRecoveryTakesTheEarliestDirectWitnessByName() {
        val b = LaunchBirth8169
        val got = b.earliestDirect8169(listOf(
            "CREATE_FRAME" to null, "PUMP_COIN_RECORD" to t0 - 30_000L, "CURVE_PAIR" to t0 - 45_000L,
            "OLDEST_SIGNATURE" to t0 - 44_000L), t0)
        assertEquals(t0 - 45_000L, got?.birthMs)
        assertEquals("CURVE_PAIR", got?.source)
        // On a tie the first listed is credited; implausible witnesses are skipped; nothing -> null (not a launch).
        assertEquals("CREATE_FRAME", b.earliestDirect8169(listOf("CREATE_FRAME" to t0 - 1_000L, "CURVE_PAIR" to t0 - 1_000L), t0)?.source)
        assertEquals("CURVE_PAIR", b.earliestDirect8169(listOf("CREATE_FRAME" to 5L, "CURVE_PAIR" to t0 - 1_000L), t0)?.source)
        assertNull(b.earliestDirect8169(listOf("CREATE_FRAME" to null, "CURVE_PAIR" to t0 + 600_000L), t0))
        // Wiring: the tape asks every direct witness; DexScreener pair creation reaches the birth store from both parsers.
        assertTrue(src("engine/market/LaunchTape7921.kt").contains("LaunchBirth8169.launchBirth8169(mint, nowMs)"))
        assertTrue(src("engine/market/LaunchTape7921.kt").contains("RECOVERY_SOURCE_8169_"))
        assertTrue(src("engine/truth/LaunchBirth8169.kt").contains("\"OLDEST_SIGNATURE\" to (try { AdmissionBirth8144.oldestSignatureMs8169(m)"))
        assertTrue(src("network/DexscreenerApi.kt").contains("AdmissionBirth8144.notePairCreated8169(baseAddr7385, p.optString(\"dexId\", \"\"), p.optLong(\"pairCreatedAt\", 0L))"))
        assertTrue(src("engine/market/MarketSweep7297.kt").contains("AdmissionBirth8144.notePairCreated8169(mint, p.optString(\"dexId\", \"\"), created)"))
        assertTrue(src("engine/truth/AdmissionBirth8144.kt").contains("pairCreatedMs = curvePairs8169[m] ?: 0L"))
    }

    // ── (2) the sniper's window, read before its native assessment ──────────

    @Test fun sniperWindowMirrorsAssessTargetInWholeSeconds() {
        val b = LaunchBirth8169
        val none = Read8144(Kind8144.UNKNOWN, 0L, 0L, "UNKNOWN")
        assertEquals(Window8144.PAST_WINDOW, b.sniperWindow8169(1_321_306_000L, none, 180))
        assertEquals(Window8144.PAST_WINDOW, b.sniperWindow8169(181_000L, none, 180))
        assertEquals(Window8144.IN_WINDOW, b.sniperWindow8169(180_999L, none, 180))  // 180 s is not > 180 s
        assertEquals(Window8144.IN_WINDOW, b.sniperWindow8169(5_000L, none, 180))
        // No canonical age: the AdmissionBirth8144 read decides, exactly as assessTarget's fallback.
        assertEquals(Window8144.UNKNOWN_DEFER, b.sniperWindow8169(null, none, 180))
        assertEquals(Window8144.PAST_WINDOW, b.sniperWindow8169(null, Read8144(Kind8144.PROVEN_OLDER_THAN, 0L, 600_000L, "HISTORY_FLOOR"), 180))
        assertEquals(Window8144.UNKNOWN_DEFER, b.sniperWindow8169(null, Read8144(Kind8144.PROVEN_OLDER_THAN, 0L, 30_000L, "FIRST_SEEN"), 180))
        assertEquals(Window8144.IN_WINDOW, b.sniperWindow8169(null, Read8144(Kind8144.KNOWN, t0 - 40_000L, 40_000L, "CREATE_FRAME"), 180))
    }

    @Test fun onlyAProvenPastWindowTokenLeavesTheSnipersScope() {
        val b = LaunchBirth8169
        val scope = setOf("SHITCOIN", "MOONSHOT", "EXPRESS", "PROJECT_SNIPER", "MANIPULATED", "CORE")
        assertEquals(scope - "PROJECT_SNIPER", b.scopeFor8169(scope, Window8144.PAST_WINDOW))
        assertEquals(scope, b.scopeFor8169(scope, Window8144.IN_WINDOW))
        assertEquals(scope, b.scopeFor8169(scope, Window8144.UNKNOWN_DEFER))  // unknown: assessTarget still defers by name
        val noSniper = setOf("QUALITY", "BLUECHIP")
        assertEquals(noSniper, b.scopeFor8169(noSniper, Window8144.PAST_WINDOW))
        // Wiring: the bridge filters its scope through the pre-filter; the hand-off is the sniper's own TOO_OLD one.
        val bridge = src("engine/SpecialistBrainBridge7542.kt")
        assertTrue(bridge.contains("LaunchBirth8169.sniperScoped8169(ts, nativeScope8168(ts.lastMcap, residentScope7828))"))
        val sniper = src("v3/scoring/ProjectSniperAI.kt")
        assertTrue(sniper.contains("const val SNIPER_WINDOW_SECS_8169: Int = MAX_TOKEN_AGE_SECONDS"))
        assertTrue(sniper.contains("handOff8144(ts, \"TOO_OLD_8144: \${tokenAgeSecs}s > \${MAX_TOKEN_AGE_SECONDS}s\", tokenAgeSecs)"))
        assertTrue(sniper.contains("return handOff8144(ts, \"TOO_OLD_8144:"))  // the in-assessment path is unchanged
        assertTrue(src("engine/truth/AdmissionBirth8144.kt").contains("LaunchBirth8169.sniperScopeLine8169()"))
    }

    @Test fun birthlessMintsAreCountedByFeedAndSuffix() {
        val b = LaunchBirth8169
        assertEquals("PUMP_FUN_NEW/pump", b.unknownKey8169(pumpMint, "pump_fun_new"))
        assertEquals("HELIUS_TAPE/other", b.unknownKey8169(plainMint, " HELIUS_TAPE, DEX "))
        assertEquals("NO_SOURCE/pump", b.unknownKey8169(pumpMint, ""))
        assertEquals("A=2,B=1", b.histogram8169(listOf("B", "A", "A"), 5))
        assertEquals("-", b.histogram8169(emptyList(), 5))
    }

    // ── (3) scouts: a named verdict, the curve pair verifies ────────────────

    private fun pair(base: String, price: Double, liq: Double, dex: String, chain: String = "solana", sym: String = "TOKE") = PairInfo(
        pairAddress = "pair", baseSymbol = sym, baseName = sym, url = "",
        candle = Candle(ts = t0, priceUsd = price, marketCap = 0.0, volumeH1 = 0.0, volume24h = 0.0),
        liquidity = liq, baseTokenAddress = base, chainId = chain, dexId = dex,
    )

    @Test fun scoutVerdictNamesEveryFailureAndAcceptsALiveCurvePair() {
        val v = ScoutPairVerdict8169
        assertEquals(Verdict8169.VERIFIED, v.verdict8169(pumpMint, pair(pumpMint, 1e-5, 12_000.0, "pumpswap")))
        // DexScreener serves a pump.fun curve pair with a price and no liquidity figure: verified, liquidity left unknown.
        val curve = v.verdict8169(pumpMint, pair(pumpMint, 1e-5, 0.0, "pumpfun"))
        assertEquals(Verdict8169.VERIFIED_CURVE, curve)
        assertTrue(curve.verified)
        assertEquals(Verdict8169.NO_LIQUIDITY, v.verdict8169(plainMint, pair(plainMint, 1e-5, 0.0, "raydium")))
        assertEquals(Verdict8169.NO_PAIR, v.verdict8169(pumpMint, null))
        assertEquals(Verdict8169.WRONG_BASE, v.verdict8169(pumpMint, pair(plainMint, 1e-5, 12_000.0, "raydium")))
        assertEquals(Verdict8169.NOT_SOLANA, v.verdict8169(pumpMint, pair(pumpMint, 1e-5, 12_000.0, "uniswap", chain = "base")))
        assertEquals(Verdict8169.QUOTE_TOKEN, v.verdict8169(pumpMint, pair(pumpMint, 1.0, 1e6, "raydium", sym = "usdc"), setOf("USDC")))
        assertEquals(Verdict8169.NO_PRICE, v.verdict8169(pumpMint, pair(pumpMint, 0.0, 0.0, "pumpfun")))
        assertEquals(Verdict8169.NO_PRICE, v.verdict8169(pumpMint, pair(pumpMint, Double.NaN, 5_000.0, "raydium")))
        assertFalse(Verdict8169.NO_LIQUIDITY.verified)
        assertTrue(v.curvePair8169(" PUMPFUN "))
        assertFalse(v.curvePair8169("pumpswap"))
        // A NO_PAIR is asked once more, only while the call is still fresh after the retry delay.
        val fresh = 45L * 60_000L
        assertTrue(v.retryNoPair8169(Verdict8169.NO_PAIR, 0, 30_000L, fresh))
        assertFalse(v.retryNoPair8169(Verdict8169.NO_PAIR, 1, 30_000L, fresh))
        assertFalse(v.retryNoPair8169(Verdict8169.NO_PAIR, 0, fresh - 10_000L, fresh))
        assertFalse(v.retryNoPair8169(Verdict8169.NO_LIQUIDITY, 0, 30_000L, fresh))
        val tally = ScoutPairVerdict8169.Tally8169()
        assertEquals("-", tally.line())
        tally.note("NO_PAIR"); tally.note("NO_PAIR"); tally.note("WRONG_BASE")
        assertEquals("NO_PAIR=2,WRONG_BASE=1", tally.line())
        // Wiring: both scouts use the one verdict and print it.
        val tg = src("engine/market/TelegramCallSweeper7830.kt")
        assertTrue(tg.contains("ScoutPairVerdict8169.verdict8169(cand.mint, direct)"))
        assertTrue(tg.contains("verdicts8169{\${verdicts8169.line()}} retriedNoPair8169="))
        assertFalse(tg.contains("p.liquidity > 0.0"))
        val groq = src("engine/market/GroqTokenScout7830.kt")
        assertTrue(groq.contains("ScoutPairVerdict8169.verdict8169(mint, pair, QUOTE_SYMBOLS_7830)"))
        assertTrue(groq.contains("verdicts8169{\${verdicts8169.line()}}"))
    }

    // ── (4) runner capture survives a restart ───────────────────────────────

    private val trace = Trace8144(
        mint = pumpMint, symbol = "RUNR", lane = "MOONSHOT", peakPct = 520.0, detectedAtMs = t0 - 3_600_000L,
        firstSeenMs = t0 - 4_000_000L, source = "PUMP_FUN_NEW", birthKind = "KNOWN", birthSource = "CREATE_FRAME", birthAgeMs = 45_000L,
        liquidityUsd = 6_000.0, nativeAtMs = t0 - 3_900_000L, natives = "MOONSHOT:N:40:NATIVE_MCAP_TOO_LOW", qualified = "",
        setup = "", admission = "", fdgLane = "", fdgVerdict = "", handoff = "", refusedBy = "NATIVE_MCAP_TOO_LOW",
        downstream = "", markPx = 1.0e-5, windowAtMs = 0L, windowCostPct = Double.NaN, windowCostMeasured = false, route = "",
        held = false,
    )

    @Test fun restoredTracesRebuildTheRunnerListAndKeepTheDecisionTimeRecord() {
        val r = AdmissionRunnerTrace8144
        val older = trace.copy(detectedAtMs = t0 - 7_200_000L, refusedBy = "OLDER")
        val list = r.restorable8169(listOf(older, trace, trace.copy(mint = ""), trace.copy(mint = plainMint, detectedAtMs = 0L),
            trace.copy(mint = plainMint, detectedAtMs = t0 + 3_600_000L)), t0)
        assertEquals(1, list.size)
        assertEquals("NATIVE_MCAP_TOO_LOW", list[0].refusedBy)  // newest per mint
        assertEquals(1, r.restorable8169(listOf(trace, trace.copy(mint = plainMint)), t0, max = 1).size)
        // Runner list: only real 4x+ runners of the last 24 h, missed or held as recorded.
        val c = RunnerCapture8017
        assertEquals(1, c.restorableRuns8169(listOf(trace), t0).size)
        assertEquals(0, c.restorableRuns8169(listOf(trace.copy(peakPct = 300.0)), t0).size)
        assertEquals(0, c.restorableRuns8169(listOf(trace.copy(peakPct = Double.NaN)), t0).size)
        assertEquals(0, c.restorableRuns8169(listOf(trace.copy(detectedAtMs = t0 - 25L * 3_600_000L)), t0).size)
        // Wiring: restored before the first capture rewrites the file, read by the report and the runner status line.
        val tr = src("engine/truth/AdmissionRunnerTrace8144.kt")
        val cap = tr.substringAfter("fun capture8144(")
        assertTrue(cap.indexOf("restore8169(nowMs)") in 0 until cap.indexOf("export8144()"))
        assertTrue(tr.contains("file=\$FILE_8144 restored8169=\$restored8169"))
        assertTrue(tr.contains("RunnerCapture8017.restore8169(list, nowMs)"))
        val rc = src("engine/truth/RunnerCapture8017.kt")
        assertTrue(rc.substringAfter("fun statusLine(): String {").substringBefore("val recent").contains("AdmissionRunnerTrace8144.restore8169(now)"))
        assertTrue(rc.contains("restoredRuns8169=\$restoredRuns8169"))
    }

    // ── (5) every lost hand-off is named ────────────────────────────────────

    @Test fun handoffVerdictsAreTalliedByStableName() {
        val t = HandoffReceiverTasks8153
        assertEquals("RECEIVER_REFUSED:NATIVE_NO_BUY_WAIT", t.verdictKey8169("RECEIVER_REFUSED:NATIVE_NO_BUY_WAIT"))
        assertEquals("DEFERRED_NATIVE:DATA_INCOMPLETE", t.verdictKey8169("DEFERRED_NATIVE:DATA_INCOMPLETE:tape missing 3"))
        assertEquals("SCHEDULED_NO_FDG_VERDICT_x#", t.verdictKey8169("SCHEDULED_NO_FDG_VERDICT_x3"))
        assertEquals("TASK_REFUSED:BINDING", t.verdictKey8169("TASK_REFUSED:BINDING:VERSION_SEALED_BY_SHITCOIN"))
        assertEquals("NATIVE_REFUSED:TOO_OLD_#", t.verdictKey8169("NATIVE_REFUSED:TOO_OLD_8144 600s > 180s"))
        assertEquals("UNNAMED", t.verdictKey8169(""))
        val tallies = mapOf("REFUSED|A" to 3L, "REFUSED|B" to 5L, "DEFERRED_DATA|C" to 2L)
        assertEquals("B=5,A=3", t.topTallies8169(tallies, "REFUSED|", 8))
        assertEquals("B=5", t.topTallies8169(tallies, "REFUSED|", 1))
        assertEquals("-", t.topTallies8169(tallies, "EVALUATED|", 4))
        // Wiring: both ledgers tally on settle and print the names on their status lines.
        val tasks = src("engine/HandoffReceiverTasks8153.kt")
        assertTrue(tasks.contains("tally8169(after)  // V5.0.8169"))
        assertTrue(tasks.contains("verdictLine8169() + \"]\""))
        val ledger = src("engine/truth/AdmissionHandoff8144.kt")
        assertTrue(ledger.contains("if (!after.open) try { tally8169(after) }"))
        assertTrue(ledger.contains("arrivedBy8169{"))
    }
}
