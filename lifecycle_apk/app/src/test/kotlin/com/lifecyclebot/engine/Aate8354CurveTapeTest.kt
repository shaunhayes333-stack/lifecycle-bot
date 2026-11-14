package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.engine.truth.CurveReserves7951
import com.lifecyclebot.engine.truth.TradeTape8100
import com.lifecyclebot.network.ChainLaunchFeed8179
import com.lifecyclebot.network.HeliusCurveTape8354
import com.lifecyclebot.network.HeliusCurveTape8354.Source8354
import com.lifecyclebot.network.HeliusCurveTape8354.Transport8354
import com.lifecyclebot.network.PumpFunWS
import io.github.novacrypto.base58.Base58
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8354 — the bonding-curve trade tape comes from Helius (one program-wide subscription, filtered client-side) and
 * PumpPortal's per-frame-metered token trade stream is OFF by default: TradeEvent decode from constructed bytes (buy and
 * sell), the keep / drop filter, the byte / credit fallback decision, the config default, no `subscribeTokenTrade` path
 * while disabled, and the 8346 liquidity hook still firing from the Helius path.
 */
class Aate8354CurveTapeTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private val MINT = "B6DfUsHYvvDjunsCWFiYcJSNMghfW7W3Hr82wSGqpump"
    private val OTHER = "EcZndAERfzNhLirHwfsy44dAyPSZazjDU83pa6RApump"
    private val NON_PUMP = "7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmW2hr"
    private val USER = "4Nd1mBQtrMJVYVfKf2PJy9NZUZdTAsp7D4xWLs4gDB4T"
    private val t0 = 1_760_000_000_000L

    @Before fun fresh() { HeliusCurveTape8354.resetForTest8354(); TradeTape8100.resetForTest8100(); CurveReserves7951.resetForTest7951() }
    @After fun back() { HeliusCurveTape8354.resetForTest8354(); TradeTape8100.resetForTest8100(); CurveReserves7951.resetForTest7951() }

    // ── the TradeEvent layout, built from its IDL (discriminator, mint, sol u64, tokens u64, is_buy u8, user, ts i64, reserves) ──

    private fun u64(v: Long) = ByteArray(8) { i -> ((v ushr (8 * i)) and 0xFF).toByte() }

    private fun tradeEventBytes(mint: String, isBuy: Boolean, solLamports: Long, tokenRaw: Long, vSolLamports: Long, vTokRaw: Long, tsSec: Long = 1_760_000_123L): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(189.toByte(), 219.toByte(), 127, 211.toByte(), 78, 230.toByte(), 97, 238.toByte()))   // sha256("event:TradeEvent")[0..8]
        out.write(Base58.base58Decode(mint))
        out.write(u64(solLamports))
        out.write(u64(tokenRaw))
        out.write(byteArrayOf(if (isBuy) 1.toByte() else 0.toByte()))
        out.write(Base58.base58Decode(USER))
        out.write(u64(tsSec))
        out.write(u64(vSolLamports))
        out.write(u64(vTokRaw))
        out.write(u64((vSolLamports - 30_000_000_000L).coerceAtLeast(0L)))   // real SOL
        out.write(u64(vTokRaw - 280_000_000_000_000L))                        // real tokens
        return out.toByteArray()
    }

    private fun line(b: ByteArray) = "Program data: " + java.util.Base64.getEncoder().encodeToString(b)

    @Test fun aBuyAndASellDecodeFromConstructedBytes() {
        val buy = ChainLaunchFeed8179.decodeTradeEvent8179(tradeEventBytes(MINT, true, 1_500_000_000L, 52_000_000_000_000L, 41_500_000_000L, 775_000_000_000_000L))!!
        assertEquals(MINT, buy.mint); assertTrue(buy.isBuy); assertEquals(USER, buy.user)
        assertEquals(1.5, buy.sol, 1e-12); assertEquals(52_000_000.0, buy.tokens, 1e-6)
        assertEquals(41.5, buy.virtualSol, 1e-12); assertEquals(775_000_000.0, buy.virtualTokens, 1e-6)
        assertEquals(11.5, buy.realSol, 1e-12); assertEquals(1_760_000_123L, buy.timestampSec)
        val sell = ChainLaunchFeed8179.decodeTradeEvent8179(tradeEventBytes(MINT, false, 700_000_000L, 20_000_000_000_000L, 40_800_000_000L, 795_000_000_000_000L))!!
        assertFalse(sell.isBuy); assertEquals(0.7, sell.sol, 1e-12); assertEquals(20_000_000.0, sell.tokens, 1e-6)
        assertEquals(40.8, sell.virtualSol, 1e-12)
        // the line form, as a logsNotification / logMessages entry carries it
        val fromLogs = HeliusCurveTape8354.tradesInLogs8354(listOf("Program log: Instruction: Buy", line(tradeEventBytes(MINT, true, 1L, 1L, 31_000_000_000L, 1_000_000_000_000_000L)), "Program log: junk"))
        assertEquals(1, fromLogs.size); assertEquals(MINT, fromLogs[0].mint)
        assertNull(ChainLaunchFeed8179.decodeTradeEvent8179(ByteArray(100)))
    }

    // ── keep / drop ──

    @Test fun theKeepFilterKeepsOnlyMintsTheBotCaresAbout() {
        assertFalse(HeliusCurveTape8354.keep8354(held = false, watched = false, lifecycle = false, near = false, asked = false))
        assertTrue(HeliusCurveTape8354.keep8354(held = true, watched = false, lifecycle = false, near = false, asked = false))
        assertTrue(HeliusCurveTape8354.keep8354(held = false, watched = true, lifecycle = false, near = false, asked = false))
        assertTrue(HeliusCurveTape8354.keep8354(held = false, watched = false, lifecycle = true, near = false, asked = false))
        assertTrue(HeliusCurveTape8354.keep8354(held = false, watched = false, lifecycle = false, near = true, asked = false))
        assertTrue(HeliusCurveTape8354.keep8354(held = false, watched = false, lifecycle = false, near = false, asked = true))
    }

    @Test fun anUnwantedMintIsDroppedNotStoredAndAnAskedMintIsKeptOnTheTapeWithItsReserves() {
        val stranger = ChainLaunchFeed8179.decodeTradeEvent8179(tradeEventBytes(OTHER, true, 1_000_000_000L, 30_000_000_000_000L, 36_000_000_000L, 900_000_000_000_000L))!!
        assertFalse(HeliusCurveTape8354.onTradeEvent8354(stranger, "sigStranger8354", t0))
        // superseded by V5.0.8475 — owner: lean on the Helius data already arriving. The stranger's print is still dropped (not
        // taped, counted dropped=1), but its curve RESERVES are kept for every decoded trade: they are the price, real SOL and
        // liquidity tick the labeler, the held marks and the launch desks read (was assertFalse(known7951(OTHER))).
        assertTrue(CurveReserves7951.known7951(OTHER))
        assertTrue(TradeTape8100.prints8128(OTHER, t0, 600_000L).isEmpty())
        assertTrue(HeliusCurveTape8354.statusLine8354(t0).contains("dropped=1"))
        // the evaluated coin asked for its tape (watchForSetup8191 routes here while PumpPortal is off)
        assertFalse(HeliusCurveTape8354.ask8354(MINT, t0))   // no transport yet and no per-mint fallback wired: nothing carries it NOW
        val ev = ChainLaunchFeed8179.decodeTradeEvent8179(tradeEventBytes(MINT, true, 1_500_000_000L, 52_000_000_000_000L, 41_500_000_000L, 775_000_000_000_000L))!!
        assertTrue(HeliusCurveTape8354.onTradeEvent8354(ev, "sigKept8354", t0))
        // exactly what a PumpPortal frame wrote: the reserves (price, real SOL -> the 8346 tick) and one executed print
        assertTrue(CurveReserves7951.known7951(MINT))
        assertEquals(11.5, CurveReserves7951.realSolIfActive8046(MINT, System.currentTimeMillis())!!, 1e-9)
        val prints = TradeTape8100.prints8128(MINT, t0, 600_000L)
        assertEquals(1, prints.size)
        assertEquals(41.5 / 775_000_000.0, prints[0].priceSol, 1e-18)
        assertTrue(prints[0].isBuy)
        // the same transaction again is one trade, not two
        assertFalse(HeliusCurveTape8354.onTradeEvent8354(ev, "sigKept8354", t0 + 1L))
        assertEquals(1, TradeTape8100.prints8128(MINT, t0 + 1L, 600_000L).size)
        val line = HeliusCurveTape8354.statusLine8354(t0)
        assertTrue(line, line.contains("kept=1") && line.contains("prints{helius=1,pumpportal=0}") && line.contains("dup=1"))
    }

    @Test fun a_non_pump_decoded_event_does_not_publish_curve_reserves_or_marks() {
        val ev = ChainLaunchFeed8179.decodeTradeEvent8179(tradeEventBytes(NON_PUMP, true,
            1_500_000_000L, 52_000_000_000_000L, 41_500_000_000L, 775_000_000_000_000L))!!
        assertFalse(HeliusCurveTape8354.onTradeEvent8354(ev, "nonPumpGuard8658", t0))
        assertFalse(CurveReserves7951.known7951(NON_PUMP))
        assertTrue(TradeTape8100.prints8128(NON_PUMP, t0, 600_000L).isEmpty())
    }

    @Test fun theHeliusPrintIsAnAuthoritativeCurveMarkForTheRungTriggers() {
        assertTrue(RunnerFunnel8331.rungMarkAuthoritative8331(curveCoin = true, source = HeliusCurveTape8354.SRC_HELIUS_CURVE_8354))
        assertTrue(RunnerFunnel8331.rungMarkAuthoritative8331(curveCoin = true, source = "PUMP_PORTAL_TRADE_WS_7278"))
    }

    @Test fun anOverflowingDerivedPriceDoesNotHydrateReservesOrTradeTape() {
        val ev = ChainLaunchFeed8179.decodeTradeEvent8179(tradeEventBytes(MINT, true, 1_500_000_000L, 52_000_000_000_000L, 41_500_000_000L, 775_000_000_000_000L))!!
        assertFalse(HeliusCurveTape8354.onTradeEvent8354(ev.copy(virtualSol = Double.MAX_VALUE, virtualTokens = Double.MIN_VALUE), "overflow8354", t0))
        assertFalse(CurveReserves7951.known7951(MINT))
        assertTrue(TradeTape8100.prints8128(MINT, t0, 600_000L).isEmpty())
    }

    @Test fun aRejectedProgramLogsSubscriptionCannotCountAsTransportAvailability() {
        assertTrue(HeliusCurveTape8354.sharedLogsEligible8509(true, 2_000L, 2_000L))
        assertFalse(HeliusCurveTape8354.sharedLogsEligible8509(true, 2_001L, 2_000L))
        assertFalse(HeliusCurveTape8354.sharedLogsEligible8509(false, 0L, 2_000L))
    }

    // ── bytes and credits: the fallback decision ──

    @Test fun theByteGovernorStepsDownPastThreeMegabytesAndBackUnderFourHundredKilobytes() {
        assertEquals(1, HeliusCurveTape8354.nextLevel8354(0, 3_200_000L))
        assertEquals(2, HeliusCurveTape8354.nextLevel8354(1, 3_200_000L))
        assertEquals(2, HeliusCurveTape8354.nextLevel8354(2, 9_000_000L))
        assertEquals(1, HeliusCurveTape8354.nextLevel8354(1, 2_000_000L))     // 2 MB holds
        assertEquals(0, HeliusCurveTape8354.nextLevel8354(1, 100_000L))
        assertEquals(0, HeliusCurveTape8354.nextLevel8354(0, 100_000L))
        assertEquals(30L * 60_000L, HeliusCurveTape8354.fallbackHoldMs8354(1))
        assertEquals(60L * 60_000L, HeliusCurveTape8354.fallbackHoldMs8354(2))
        assertEquals(4L * 60L * 60_000L, HeliusCurveTape8354.fallbackHoldMs8354(4))
        assertEquals(4L * 60L * 60_000L, HeliusCurveTape8354.fallbackHoldMs8354(9))
        assertEquals(3_145_728L, HeliusCurveTape8354.HIGH_BYTES_PER_MIN_8354)   // PumpSwapTape8307's halving mark
    }

    @Test fun theProgramStreamNeverExceedsItsShareOfTheDaysCredits() {
        // 2 credits per 100 KB: 1 MB/min for a whole day = 28,800 credits, inside 40% of 300k
        assertTrue(HeliusCurveTape8354.programAffordable8354(0.0, 1_000_000L, 0.0, overBudget = false))
        // 5 MB/min for a whole day = 144,000 credits > 120,000
        assertFalse(HeliusCurveTape8354.programAffordable8354(0.0, 5_000_000L, 0.0, overBudget = false))
        // late in the day the same pace fits
        assertTrue(HeliusCurveTape8354.programAffordable8354(0.0, 5_000_000L, 0.9, overBudget = false))
        // already spent its share: no
        assertFalse(HeliusCurveTape8354.programAffordable8354(120_001.0, 0L, 0.5, overBudget = false))
        // the day is over budget: never
        assertFalse(HeliusCurveTape8354.programAffordable8354(0.0, 0L, 0.5, overBudget = true))
    }

    @Test fun theDecisionPrefersTheEnhancedProgramStreamThenLogsThenPerMint() {
        val d = HeliusCurveTape8354
        // superseded by V5.0.8475 — owner: lean on Helius. PumpPortal delivering no longer switches the program tape off
        // (was PUMPPORTAL / NONE): PumpPortal streams only its subscribed coins; the program tape decodes every curve trade.
        assertEquals(Source8354.HELIUS_PROGRAM to Transport8354.ENHANCED, d.decide8354(pumpPortalOn = true, level = 0, inFallbackHold = false, affordable = true, enhancedOk = true, sharedUp = true))
        assertEquals(Source8354.HELIUS_PROGRAM to Transport8354.ENHANCED, d.decide8354(false, 0, false, true, enhancedOk = true, sharedUp = true))
        assertEquals(Source8354.HELIUS_PROGRAM to Transport8354.LOGS, d.decide8354(false, 0, false, true, enhancedOk = false, sharedUp = true))
        assertEquals(Source8354.HELIUS_PROGRAM to Transport8354.LOGS, d.decide8354(false, 1, false, true, enhancedOk = true, sharedUp = true))   // enhanced was too heavy: logs only
        // superseded by V5.0.8475 — logs wanted but no shared socket: Enhanced (under its byte governor) carries the tape rather
        // than nothing (8471: transport=NONE the whole run); was HELIUS_PER_MINT / NONE.
        assertEquals(Source8354.HELIUS_PROGRAM to Transport8354.ENHANCED, d.decide8354(false, 1, false, true, enhancedOk = true, sharedUp = false))
        assertEquals(Source8354.HELIUS_PER_MINT to Transport8354.NONE, d.decide8354(false, 1, false, true, enhancedOk = false, sharedUp = false))
        assertEquals(Source8354.HELIUS_PER_MINT to Transport8354.NONE, d.decide8354(false, 2, false, true, enhancedOk = true, sharedUp = true))
        assertEquals(Source8354.HELIUS_PER_MINT to Transport8354.NONE, d.decide8354(false, 0, inFallbackHold = true, affordable = true, enhancedOk = true, sharedUp = true))
        assertEquals(Source8354.HELIUS_PER_MINT to Transport8354.NONE, d.decide8354(false, 0, false, affordable = false, enhancedOk = true, sharedUp = true))
        assertEquals("PROGRAM_SHARE_EXHAUSTED", d.fallbackWhy8354(Source8354.HELIUS_PER_MINT, false, false, false, false, 0, Transport8354.NONE))
        assertEquals("HELIUS_OVER_BUDGET", d.fallbackWhy8354(Source8354.HELIUS_PER_MINT, false, false, false, true, 0, Transport8354.NONE))
        assertEquals("BYTES_OVER_3MB_MIN", d.fallbackWhy8354(Source8354.HELIUS_PER_MINT, true, true, true, false, 2, Transport8354.NONE))
        assertEquals("BYTES_LEVEL", d.fallbackWhy8354(Source8354.HELIUS_PER_MINT, true, false, true, false, 2, Transport8354.NONE))
        assertEquals("-", d.fallbackWhy8354(Source8354.HELIUS_PROGRAM, false, false, true, false, 0, Transport8354.ENHANCED))
    }

    @Test fun thePerMintFallbackCarriesTheAskWhenNoProgramStreamIsUp() {
        val asked = ArrayList<String>()
        HeliusCurveTape8354.sync8354(emptySet(), emptyList(), t0, { false }) { m -> asked.add(m); HeliusCurveTape8354.TapeState8386.Queued }
        assertEquals(Source8354.HELIUS_PER_MINT, HeliusCurveTape8354.source8354())   // no socket in a unit test
        assertEquals(HeliusCurveTape8354.TapeState8386.Queued, HeliusCurveTape8354.askState8386(MINT, t0))
        assertEquals(listOf(MINT), asked)
        assertTrue(HeliusCurveTape8354.statusLine8354(t0).contains("source=HELIUS_PER_MINT"))
    }

    @Test fun aSelectedProgramTransportWithoutAckOrPrintsNeedsPerMintFallback() {
        val d = HeliusCurveTape8354
        assertTrue(d.needsPerMint8751(Transport8354.NONE, 0L, false, 0L))
        assertFalse(d.needsPerMint8751(Transport8354.ENHANCED, 10_000L, false, 10_000L))
        assertTrue(d.needsPerMint8751(Transport8354.ENHANCED, 16_000L, false, 16_000L))
        assertTrue(d.needsPerMint8751(Transport8354.LOGS, 50_000L, true, 46_000L))
        assertFalse(d.needsPerMint8751(Transport8354.LOGS, 50_000L, true, 10_000L))
    }

    @Test fun queuedRequestsAreRehomedWhenProgramFallbackBecomesAvailable() {
        assertTrue(HeliusCurveTape8354.askState8386(MINT, t0) is HeliusCurveTape8354.TapeState8386.Unavailable)
        val asked = ArrayList<String>()
        val cb: (String) -> HeliusCurveTape8354.TapeState8386 = { m ->
            asked.add(m); HeliusCurveTape8354.TapeState8386.Queued
        }
        HeliusCurveTape8354.sync8354(setOf(MINT), emptyList(), t0 + 1_000L, { false }, cb)
        assertEquals(listOf(MINT), asked)
        assertTrue(HeliusCurveTape8354.statusLine8354(t0 + 1_000L).contains("rehomes=1"))
        HeliusCurveTape8354.sync8354(setOf(MINT), emptyList(), t0 + 11_000L, { false }, cb)
        assertEquals(1, asked.size)
        HeliusCurveTape8354.sync8354(setOf(MINT), emptyList(), t0 + 22_000L, { false }, cb)
        assertEquals(2, asked.size)
    }

    @Test fun preCareRealPrintsBecomeHistoricalBuyerFlowWithoutFiringLiveMarks() {
        val ev = ChainLaunchFeed8179.decodeTradeEvent8179(tradeEventBytes(OTHER, true,
            1_000_000_000L, 30_000_000_000_000L, 36_000_000_000L, 900_000_000_000_000L,
            tsSec = t0 / 1000L))!!
        var liveMarks = 0
        TradeTape8100.setOnExecutedTrade { _, _ -> liveMarks++ }
        for (i in 0 until 8) assertFalse(HeliusCurveTape8354.onTradeEvent8354(ev, "precared-$i", t0 + i * 1_000L))
        assertTrue(TradeTape8100.prints8128(OTHER, t0 + 8_000L, 60_000L).isEmpty())
        assertTrue(HeliusCurveTape8354.askState8386(OTHER, t0 + 8_000L) is HeliusCurveTape8354.TapeState8386.Delivering)
        assertEquals(8, TradeTape8100.prints8128(OTHER, t0 + 8_000L, 60_000L).size)
        assertTrue(HeliusCurveTape8354.statusLine8354(t0 + 8_000L).contains("replayed=8"))
        assertEquals(0, liveMarks)
        assertTrue(HeliusCurveTape8354.onTradeEvent8354(ev.copy(timestampSec = t0 / 1000L + 9),
            "current-after-promotion", t0 + 9_000L))
        assertEquals(9, TradeTape8100.prints8128(OTHER, t0 + 9_000L, 60_000L).size)
        assertEquals(1, liveMarks)
    }

    @Test fun earlyPrintBufferKeepsOnlyTheNewest32RealTradesPerMint() {
        val ev = ChainLaunchFeed8179.decodeTradeEvent8179(tradeEventBytes(OTHER, true,
            1_000_000_000L, 30_000_000_000_000L, 36_000_000_000L, 900_000_000_000_000L,
            tsSec = t0 / 1000L))!!
        for (i in 0 until 40) HeliusCurveTape8354.onTradeEvent8354(ev, "bound-$i", t0 + i * 100L)
        HeliusCurveTape8354.askState8386(OTHER, t0 + 5_000L)
        assertEquals(32, TradeTape8100.prints8128(OTHER, t0 + 5_000L, 60_000L).size)
        assertTrue(HeliusCurveTape8354.statusLine8354(t0 + 5_000L).contains("evicted=8"))
    }

    @Test fun paidEnabledWithRejectedOrMissingKeyStillAsksTheRealHeliusTape() {
        assertTrue(PumpFunWS.setupNeedsHeliusFallback8646(paidEnabled = false, keyedCapability = false))
        assertTrue(PumpFunWS.setupNeedsHeliusFallback8646(paidEnabled = true, keyedCapability = false))
        assertFalse(PumpFunWS.setupNeedsHeliusFallback8646(paidEnabled = true, keyedCapability = true))
        val source = src("network/PumpFunWS.kt")
        assertTrue(source.contains("setupNeedsHeliusFallback8646(tradeStreamEnabled8354, tradeStreamKeyed7284())"))
        assertTrue(source.contains("return HeliusCurveTape8354.ask8354(mint, nowMs)"))
        assertTrue(source.contains("PUMP_SETUP_HELIUS_FALLBACK_8646"))
        assertTrue(source.contains("if (!meteredSubAllowed8193(mint, setup8317 = true)) {"))
    }

    // ── the PumpPortal stream is opt-in ──

    @Test fun thePumpPortalTradeStreamIsOffByDefault() {
        assertFalse(BotConfig().pumpPortalTradeStreamEnabled)
        assertFalse(PumpFunWS.TRADE_STREAM_DEFAULT_8354)
        assertFalse(PumpFunWS.tradeStreamEnabled8354())
        assertEquals("pump_portal_trade_stream_enabled_8354", PumpFunWS.TRADE_STREAM_SETTING_KEY_8354)
        val cfg = src("data/BotConfig.kt")
        assertTrue(cfg.contains("val pumpPortalTradeStreamEnabled: Boolean = false,"))
        assertTrue(cfg.contains("putBoolean(com.lifecyclebot.network.PumpFunWS.TRADE_STREAM_SETTING_KEY_8354, cfg.pumpPortalTradeStreamEnabled)"))
        assertTrue(cfg.contains("pumpPortalTradeStreamEnabled = p.getBoolean(com.lifecyclebot.network.PumpFunWS.TRADE_STREAM_SETTING_KEY_8354, com.lifecyclebot.network.PumpFunWS.TRADE_STREAM_DEFAULT_8354)"))
        assertTrue(cfg.contains("PumpFunWS.noteTradeStreamConfig8354(it.pumpPortalTradeStreamEnabled)"))
        val sheet = src("ui/SettingsBottomSheet.kt")
        assertTrue(sheet.contains("switchPumpPortalTradeStream8354 = view.findViewById(R.id.switchPumpPortalTradeStream8354)"))
        assertTrue(sheet.contains("switchPumpPortalTradeStream8354.isChecked = cfg.pumpPortalTradeStreamEnabled"))
        assertTrue(sheet.contains("pumpPortalTradeStreamEnabled = switchPumpPortalTradeStream8354.isChecked,"))
        val layout = File("src/main/res/layout/dialog_settings.xml").readText()
        assertTrue(layout.contains("android:id=\"@+id/switchPumpPortalTradeStream8354\""))
        assertTrue(layout.contains("PumpPortal token trade stream (paid per frame)"))
    }

    @Test fun whileOffNoSubscribeTokenTradeIsSentNoDenialIsReprobedAndTheAskGoesToHelius() {
        val ws = src("network/PumpFunWS.kt")
        // every keyed send is behind tradeStreamKeyed7284(), which is false while the switch is off
        assertTrue(ws.contains("private fun tradeStreamKeyed7284(): Boolean = tradeStreamEnabled8354 && apiKey7284.isNotBlank() && !tradeAuthDenied7432.get()"))
        assertTrue(ws.contains("if (held7278.isNotEmpty() && tradeStreamKeyed7284()) {"))
        val sync = ws.substringAfter("fun syncTradeSubscriptions7278(mints: Set<String>) {").substringBefore("fun start(")
        assertTrue(sync.indexOf("if (!tradeStreamEnabled8354) { unsupportedTradeDemand7485.clear(); return }") < sync.indexOf("put(\"method\", \"subscribeTokenTrade\")"))
        val fresh = ws.substringAfter("private fun registerFreshLifecycle7420(").substringBefore("fun syncTradeSubscriptions7278(")
        assertTrue(fresh.indexOf("if (!tradeStreamEnabled8354) { lifecycleSkippedOff8354.incrementAndGet(); return }") < fresh.indexOf("put(\"method\", \"subscribeTokenTrade\")"))
        val setup = ws.substringAfter("fun watchForSetup8191(").substringBefore("fun lifecycleStreamCapable7420()")
        assertTrue(setup.indexOf("if (!tradeStreamEnabled8354) { setupRoutedHelius8354.incrementAndGet(); return HeliusCurveTape8354.ask8354(mint, nowMs) }") < setup.indexOf("put(\"method\", \"subscribeTokenTrade\")"))
        val reprobe = ws.substringAfter("private fun maybeReprobeAuth8094(").substringBefore("refreshKey8181(nowMs)")
        assertTrue(reprobe.contains("if (!tradeStreamEnabled8354) return"))
        assertTrue(ws.contains("\"tradeStream=\${if (!tradeStreamEnabled8354) \"OFF_8354\" else if (tradeAuthDenied7432.get()) \"AUTH_DENIED\""))
        assertTrue(ws.contains("HeliusCurveTape8354.statusLine8354()"))
        // the create and migration streams stay on, unconditionally
        assertTrue(ws.contains("webSocket.send(JSONObject().put(\"method\", \"subscribeNewToken\").toString())"))
        assertTrue(ws.contains("webSocket.send(JSONObject().put(\"method\", \"subscribeMigration\").toString())"))
    }

    // ── the Helius subscription and where prints land ──

    @Test fun oneProgramSubscriptionOnTheEnhancedSocketElseLogsOnTheSharedTransport() {
        assertEquals("6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P", HeliusCurveTape8354.PUMP_PROGRAM_8354)
        val e = src("network/HeliusEnhancedWS.kt")
        val sub = e.substringAfter("private fun sendCurveSubscribe8354(").substringBefore("private fun sendCurveUnsubscribe8354(")
        assertTrue(sub.contains("put(\"accountInclude\", JSONArray().put(HeliusCurveTape8354.PUMP_PROGRAM_8354))"))
        assertTrue(sub.contains("put(\"commitment\", \"processed\")") && sub.contains("put(\"transactionDetails\", \"full\")") && sub.contains("put(\"showRewards\", false)"))
        assertTrue(e.contains("HeliusCurveTape8354.onEnhancedTx8354(result)"))
        assertTrue(e.contains("HeliusCreditEconomy7881.meterWs(\"HELIUS_CURVE_8354\", text.length)"))
        val c = src("network/HeliusCurveTape8354.kt")
        assertTrue(c.contains("put(\"method\", \"logsSubscribe\")") && c.contains("put(\"mentions\", JSONArray().put(PUMP_PROGRAM_8354))"))
        assertTrue(c.contains("HeliusSocketShare8191.send8191("))
        assertTrue(src("network/HeliusSocketShare8191.kt").contains("(router8354?.invoke(text) == true) || (router?.invoke(text) == true)"))
        // PumpSwap stays PumpSwapTape8307's: the AMM program is not subscribed again here
        assertFalse(c.contains("pAMMBay6oceH9fJKBRHGP5D4bD4sWpmSwMn52FMfXEA"))
        assertTrue(src("engine/DataOrchestrator.kt").contains("HeliusCurveTape8354.sync8354(held, near, nowMs, { m -> status.tokens.containsKey(m) }) { m -> heliusWs?.subscribeTokenState8386(m)"))
    }

    @Test fun curveSwapEvidencePrecedesFastLaneEvaluation() {
        val ws = src("network/PumpFunWS.kt")
        val portal = ws.substringAfter("val swapKey8136 =").substringBefore("txType == \"create\"")
        val tape = portal.indexOf("tapeFrame8285(")
        val detail = portal.indexOf("onTradeDetailCb7743?.invoke(")
        assertTrue(tape in 0 until detail && detail < portal.indexOf("onTradeCb?.invoke("))
        val helius = src("network/HeliusCurveTape8354.kt").substringAfter("fun onTradeEvent8354(")
        assertTrue(helius.indexOf("TradeTape8100.note8285(") in 0 until helius.indexOf("PumpFunWS.emitHeliusCurve8354("))
        val emit = ws.substringAfter("fun emitHeliusCurve8354(").substringBefore("private fun tradeStreamKeyed7284")
        assertTrue(emit.indexOf("onTradeDetailCb7743?.invoke(") in 0 until emit.indexOf("onTradeCb?.invoke("))
    }

    @Test fun aKeptTradeWritesWhatAPumpPortalFrameWroteAndThe8346HookStillFires() {
        val c = src("network/HeliusCurveTape8354.kt")
        assertTrue(c.contains("CurveReserves7951.noteFrame7951(mint, ev.virtualSol, ev.virtualTokens)"))
        assertTrue(c.contains("LaunchEvidence8136.dedupKey8136(mint, sig, ev.user, ev.isBuy, ev.sol, ev.virtualSol, ev.virtualTokens)"))
        assertTrue(c.contains("PumpFunWS.emitHeliusCurve8354(mint, priceSol, mcapSol, ev.isBuy, ev.user, ev.sol, ev.virtualSol)"))
        assertTrue(c.contains("mint, priceSol, ev.sol, ev.tokens, ev.isBuy, SRC_HELIUS_CURVE_8354,"))
        assertTrue(c.contains("pool = com.lifecyclebot.engine.truth.TradeTape8100.VENUE_CURVE_8285, signature = sig,"))
        // the frame path's reserve write carries the 8346 liquidity tick (CurveReserves7951 is the one writer both feeds share)
        assertTrue(src("engine/truth/CurveReserves7951.kt").contains("LiquidityTruth8037.onCurveObserved8346(mint, real8346, fromAccount = false, atMs = atMs)"))  // superseded: no atMs — V5.0.8551 the frame's own time
        // the mark names its source on BotService's callback (a ThreadLocal set only around the Helius emission)
        assertTrue(src("engine/BotService.kt").contains("applyPumpTradeMark7278(mint, priceSol, mcapSol, com.lifecyclebot.network.PumpFunWS.currentMarkSource8354())"))
        assertEquals("PUMP_PORTAL_TRADE_WS_7278", PumpFunWS.currentMarkSource8354())
        assertFalse(PumpFunWS.caresAbout8354(MINT))   // not held, not a launch, not setup-watched: the socket's bookkeeping does not care
    }
}
