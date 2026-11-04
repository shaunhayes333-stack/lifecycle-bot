package com.lifecyclebot.engine

import android.content.SharedPreferences
import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.CanonicalPartialReceipt8527 as Receipt
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441 as Cpa
import com.lifecyclebot.v3.scoring.ShitCoinExpress as Express
import com.lifecyclebot.v3.scoring.ProjectSniperAI as Sniper
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class Aate8527NativeSolLedgerPrecisionTest {
    /** Exercise the real preference readers/writers without changing their Android persistence contract. */
    private fun prefs(values: MutableMap<String, Any>): SharedPreferences {
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when {
                method.name.startsWith("put") -> {
                    val key = args!![0] as String
                    val value = args[1]
                    if (value == null) values.remove(key) else values[key] = value
                    editor
                }
                method.name == "remove" -> { values.remove(args!![0] as String); editor }
                method.name == "clear" -> { values.clear(); editor }
                method.name == "commit" -> true
                method.name == "apply" -> null
                method.name == "toString" -> "TestPreferenceEditor8527"
                else -> throw AssertionError("Unexpected preference editor call: " + method.name)
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when {
                method.name == "edit" -> editor
                method.name == "contains" -> values.containsKey(args!![0] as String)
                method.name == "getAll" -> values.toMap()
                method.name.startsWith("get") -> values[args!![0] as String] ?: args[1]
                method.name == "registerOnSharedPreferenceChangeListener" ||
                    method.name == "unregisterOnSharedPreferenceChangeListener" -> null
                method.name == "toString" -> "TestPreferences8527"
                else -> throw AssertionError("Unexpected preference call: " + method.name)
            }
        } as SharedPreferences
    }

    private fun withLedger(owner: Any, values: MutableMap<String, Any>, body: () -> Unit) {
        val cls = owner.javaClass
        val counters = cls.declaredFields.mapNotNull { field ->
            field.isAccessible = true
            when (val value = field.get(owner)) {
                is AtomicLong -> Triple(value, value.get(), true)
                is AtomicInteger -> Triple(value, value.get().toLong(), false)
                else -> null
            }
        }
        val prefsField = cls.getDeclaredField("prefs").apply { isAccessible = true }
        val oldPrefs = prefsField.get(owner)
        val saveField = cls.getDeclaredField("lastSaveTime").apply { isAccessible = true }
        val oldSaveTime = saveField.getLong(owner)
        Cpa.resetForTest()
        Express.clearAllRides(); Sniper.clearAllMissions()
        try {
            prefsField.set(owner, prefs(values))
            cls.getDeclaredMethod("restore").apply { isAccessible = true }.invoke(owner)
            body()
        } finally {
            counters.forEach { (counter, value, long) ->
                if (long) (counter as AtomicLong).set(value) else (counter as AtomicInteger).set(value.toInt())
            }
            prefsField.set(owner, oldPrefs)
            saveField.setLong(owner, oldSaveTime)
            Express.clearAllRides(); Sniper.clearAllMissions()
            Cpa.resetForTest()
        }
    }

    private fun restored(owner: Any) {
        owner.javaClass.getDeclaredMethod("restore").apply { isAccessible = true }.invoke(owner)
    }

    private fun preferencesAtToday(legacyDailyBps: Long): MutableMap<String, Any> = mutableMapOf(
        "savedDay" to System.currentTimeMillis() / 86_400_000L,
        "dailyPnlSolBps" to legacyDailyBps,
    )

    private fun nativeLoss(sniper: Boolean, index: Int) {
        val mint = "micro-loss8527-$sniper-$index-${System.nanoTime()}"
        val pid = "LIVE:$mint"
        assertEquals(Cpa.MutateResult.APPLIED, Cpa.openPosition("open:$pid", pid, mint, mint,
            if (sniper) "PROJECT_SNIPER" else "EXPRESS", "8527", 0.05,
            BigInteger.valueOf(100L), 0, 0.0, false, entryPriceUsd = 1.0, entryPriceSource = "LIVE_FINALIZED"))
        val row = Cpa.getPosition(pid)!!
        if (sniper) Sniper.restoreMission(Sniper.SniperMission(mint, mint, 1.0, 0.05, row.openedAtMs,
            30, 50_000.0, 50_000.0, 75.0, 1.0))
        else Express.restoreRide(Express.ExpressRide(mint, mint, 1.0, 0.05, row.openedAtMs, 5.0, 75.0, false))
        val capture = Receipt.Capture8527()
        assertEquals(Cpa.MutateResult.APPLIED, Cpa.partialSell("sell:$pid", pid,
            BigInteger.valueOf(100L), 0.045, 0.05, 0.0, false, receiptCapture8527 = capture))
        assertEquals(-0.005, capture.receipt!!.realizedPnlSol, 1e-12)
        assertTrue(com.lifecyclebot.engine.truth.NativeTerminalClose8527.close8527(capture.receipt, 0.90))
        if (sniper) assertFalse(Sniper.hasMission(mint)) else assertFalse(Express.hasRide(mint))
    }

    @Test fun expressRepeatedMicroLossesReachTheUnchangedRecoverySizingThresholdAndSurviveRestore() {
        val values = preferencesAtToday(-199L).apply { put("expressBalanceBps", 50L) }
        withLedger(Express, values) {
            assertEquals(-1.99, Express.getDailyPnlSol(), 1e-12)
            assertEquals(1.0, Express.dailyLossSizeMultiplier8527(), 0.0)
            nativeLoss(false, 1)
            assertEquals(-1.995, Express.getDailyPnlSol(), 1e-12)
            assertEquals(1.0, Express.dailyLossSizeMultiplier8527(), 0.0)
            nativeLoss(false, 2)
            assertEquals(-2.0, Express.getDailyPnlSol(), 1e-12)
            assertEquals(0.35, Express.dailyLossSizeMultiplier8527(), 0.0)
            Express.save(force = true)
            assertEquals(-2_000_000_000L, values["dailyPnlLamports8527"])
            assertEquals(-200L, values["dailyPnlSolBps"])
            assertEquals(500_000_000L, values["expressBalanceLamports8527"])
            Express.resetDaily()
            assertEquals(0.0, Express.getDailyPnlSol(), 0.0)
            values["dailyPnlSolBps"] = 0L // A stale compatibility mirror cannot override current exact state.
            restored(Express)
            assertEquals(-2.0, Express.getDailyPnlSol(), 1e-12)
            assertEquals(0.35, Express.dailyLossSizeMultiplier8527(), 0.0)
        }
    }

    @Test fun sniperRepeatedMicroLossesReachItsUnchangedRecoverySizingThresholdAndSurviveRestore() {
        val values = preferencesAtToday(-149L)
        withLedger(Sniper, values) {
            assertEquals(-1.49, Sniper.getDailyStats().pnlSol, 1e-12)
            assertEquals(1.0, Sniper.dailyLossSizeMultiplier8527(), 0.0)
            nativeLoss(true, 1)
            assertEquals(-1.495, Sniper.getDailyStats().pnlSol, 1e-12)
            assertEquals(1.0, Sniper.dailyLossSizeMultiplier8527(), 0.0)
            nativeLoss(true, 2)
            assertEquals(-1.5, Sniper.getDailyStats().pnlSol, 1e-12)
            assertEquals(0.35, Sniper.dailyLossSizeMultiplier8527(), 0.0)
            Sniper.save(force = true)
            assertEquals(-1_500_000_000L, values["dailyPnlLamports8527"])
            assertEquals(-150L, values["dailyPnlSolBps"])
            values["dailyPnlSolBps"] = 0L
            restored(Sniper)
            assertEquals(-1.5, Sniper.getDailyStats().pnlSol, 1e-12)
            assertEquals(0.35, Sniper.dailyLossSizeMultiplier8527(), 0.0)
        }
    }

    @Test fun expressCompoundsHalfOfASubCentProfitInExactLamports() {
        val values = preferencesAtToday(0L).apply { put("expressBalanceBps", 50L) }
        withLedger(Express, values) {
            // Existing terminal owner, not a fake partial callback; 0.05 SOL at +10% earns 0.005.
            val mint = "express-micro-profit8527-${System.nanoTime()}"
            Express.restoreRide(Express.ExpressRide(mint, mint, 1.0, 0.05,
                System.currentTimeMillis(), 5.0, 75.0, false))
            Express.exitRide(mint, 1.10, Express.ExitSignal.TAKE_PROFIT_30)
            assertEquals(0.005, Express.getDailyPnlSol(), 1e-12)
            Express.save(force = true)
            assertEquals(5_000_000L, values["dailyPnlLamports8527"])
            assertEquals(502_500_000L, values["expressBalanceLamports8527"])
            restored(Express)
            assertEquals(0.005, Express.getDailyPnlSol(), 1e-12)
            Express.save(force = true)
            assertEquals(502_500_000L, values["expressBalanceLamports8527"])
        }
    }


    private fun withTreasury8527(body: () -> Unit) {
        val cls = TreasuryManager::class.java
        val saved = cls.declaredFields.filter { !java.lang.reflect.Modifier.isFinal(it.modifiers) }.map { field ->
            field.isAccessible = true
            field to field.get(TreasuryManager)
        }
        val eventsField = cls.getDeclaredField("_events").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val events = eventsField.get(TreasuryManager) as MutableCollection<Any>
        val savedEvents = events.toList()
        val ledger = com.lifecyclebot.engine.truth.PaperAccountLedger6430
        ledger.resetForTest()
        ledger.initialize(1.0)
        cls.getDeclaredField("cachedCtx").apply { isAccessible = true }.set(TreasuryManager, null)
        try { body() } finally {
            saved.forEach { (field, value) -> field.set(TreasuryManager, value) }
            events.clear(); events.addAll(savedEvents)
            ledger.resetForTest()
        }
    }

    @Test fun sniperFinalTwentyPercentUsesOnlyItsExactSliceAndNeverSplitsTreasuryAgain() {
        val values = preferencesAtToday(0L)
        withLedger(Sniper, values) {
            withTreasury8527 {
                val mint = "sniper-final-slice8527-${System.nanoTime()}"
                val pid = "PAPER:$mint"
                assertEquals(Cpa.MutateResult.APPLIED, Cpa.openPosition("open:$pid", pid, mint, mint,
                    "PROJECT_SNIPER", "8527", 0.05, BigInteger.valueOf(100L), 0, 0.0, true,
                    entryPriceUsd = 1.0, entryPriceSource = "PAPER"))
                val row = Cpa.getPosition(pid)!!
                Sniper.restoreMission(Sniper.SniperMission(mint, mint, 1.0, 0.05, row.openedAtMs,
                    30, 50_000.0, 50_000.0, 75.0, 1.0))
                val first = Receipt.Capture8527()
                assertEquals(Cpa.MutateResult.APPLIED, Cpa.partialSell("first:$pid", pid,
                    BigInteger.valueOf(80L), 0.048, 0.04, 0.0, true, receiptCapture8527 = first))
                Sniper.syncExtractedFromCanonicalPartial7669(mint, BigInteger.valueOf(100L), BigInteger.valueOf(20L))
                assertEquals(80, Sniper.getMission(mint)!!.extractedPct)
                assertEquals(0.05, Sniper.getMission(mint)!!.entrySol, 0.0)
                assertTrue(TreasuryManager.contributeFromMemeSell(first.receipt!!.realizedPnlSol, 100.0, true) > 0.0)

                val last = Receipt.Capture8527()
                assertEquals(Cpa.MutateResult.APPLIED, Cpa.partialSell("last:$pid", pid,
                    BigInteger.valueOf(20L), 0.013, 0.01, 0.001, true, receiptCapture8527 = last))
                val receipt = requireNotNull(last.receipt)
                assertEquals(0.002, receipt.realizedPnlSol, 1e-12)
                // Executor's confirmed accounting invokes this once before native completion.
                assertTrue(TreasuryManager.contributeFromMemeSell(receipt.realizedPnlSol, 100.0, true) > 0.0)
                val treasuryAfterAccounting = TreasuryManager.treasurySol
                val paperTreasuryAfterAccounting = com.lifecyclebot.engine.truth.PaperAccountLedger6430.treasurySol7294()
                assertTrue(com.lifecyclebot.engine.truth.NativeTerminalClose8527.close8527(receipt, 2.0))
                assertEquals(0.002, Sniper.getDailyStats().pnlSol, 1e-12)
                assertEquals(1, Sniper.getDailyStats().kills)
                assertEquals(treasuryAfterAccounting, TreasuryManager.treasurySol, 0.0)
                assertEquals(paperTreasuryAfterAccounting,
                    com.lifecyclebot.engine.truth.PaperAccountLedger6430.treasurySol7294(), 0.0)
                assertTrue(com.lifecyclebot.engine.truth.NativeTerminalClose8527.close8527(receipt, 2.0))
                assertEquals(0.002, Sniper.getDailyStats().pnlSol, 1e-12)
                assertEquals(treasuryAfterAccounting, TreasuryManager.treasurySol, 0.0)
            }
        }
    }

    @Test fun expressFinalNetLossDoesNotCompoundAGrossMarkOrRelabelAWinningWholePosition() {
        val values = preferencesAtToday(0L).apply { put("expressBalanceBps", 50L) }
        withLedger(Express, values) {
            withTreasury8527 {
                val mint = "express-final-fees8527-${System.nanoTime()}"
                val pid = "PAPER:$mint"
                assertEquals(Cpa.MutateResult.APPLIED, Cpa.openPosition("open:$pid", pid, mint, mint,
                    "EXPRESS", "8527", 0.05, BigInteger.valueOf(100L), 0, 0.0, true,
                    entryPriceUsd = 1.0, entryPriceSource = "PAPER"))
                val row = Cpa.getPosition(pid)!!
                val ts = TokenState(mint = mint, symbol = mint, position = Position(positionId = pid,
                    qtyToken = 100.0, costSol = 0.05, entryPrice = 1.0, entryTime = row.openedAtMs,
                    isPaperPosition = true, tradingMode = "EXPRESS"))
                Express.restoreRide(Express.ExpressRide(mint, mint, 1.0, 0.05, row.openedAtMs, 5.0, 75.0, true))
                val project = Express.partialProjection8527(ts)
                val first = Receipt.Capture8527()
                assertEquals(Cpa.MutateResult.APPLIED, Cpa.partialSell("first:$pid", pid,
                    BigInteger.valueOf(80L), 0.048, 0.04, 0.0, true, receiptCapture8527 = first))
                assertTrue(project(first.receipt))
                assertEquals(0.01, Express.getActiveRides().single().entrySol, 1e-12)
                assertTrue(TreasuryManager.contributeFromMemeSell(first.receipt!!.realizedPnlSol, 100.0, true) > 0.0)

                val last = Receipt.Capture8527()
                assertEquals(Cpa.MutateResult.APPLIED, Cpa.partialSell("last:$pid", pid,
                    BigInteger.valueOf(20L), 0.012, 0.01, 0.003, true, receiptCapture8527 = last))
                val receipt = requireNotNull(last.receipt)
                assertEquals(-0.001, receipt.realizedPnlSol, 1e-12)
                assertEquals(0.0, TreasuryManager.contributeFromMemeSell(receipt.realizedPnlSol, 100.0, true), 0.0)
                val treasuryAfterAccounting = TreasuryManager.treasurySol
                assertTrue(com.lifecyclebot.engine.truth.NativeTerminalClose8527.close8527(receipt, 2.0))
                assertEquals(-0.001, Express.getDailyPnlSol(), 1e-12)
                assertEquals(1, Express.getStats().dailyWins) // Whole lot +0.007; the final slice lost -0.001.
                assertEquals(0, Express.getStats().dailyLosses)
                Express.save(force = true)
                assertEquals(500_000_000L, values["expressBalanceLamports8527"])
                assertEquals(treasuryAfterAccounting, TreasuryManager.treasurySol, 0.0)
                assertTrue(com.lifecyclebot.engine.truth.NativeTerminalClose8527.close8527(receipt, 2.0))
                assertEquals(-0.001, Express.getDailyPnlSol(), 1e-12)
                assertEquals(treasuryAfterAccounting, TreasuryManager.treasurySol, 0.0)
            }
        }
    }

    @Test fun onlyExecutorRetainsTheConfirmedProfitSplitWriterForTheseNativeOwners() {
        val root = "src/main/kotlin/com/lifecyclebot/"
        for (name in listOf("ShitCoinExpress", "ProjectSniperAI")) {
            val source = java.io.File(root + "v3/scoring/$name.kt").readText()
            assertFalse(name, source.contains("TreasuryManager.contributeFromMemeSell("))
            assertTrue(name, source.contains("canonicalSlice8527?.realizedPnlSol"))
            assertTrue(name, source.contains("canonicalSlice8527?.realizedPnlPct"))
        }
        val executor = java.io.File(root + "engine/Executor.kt").readText()
        val partial = executor.substringAfter("private fun requestPartialSellWithDelivery8527(")
        val booking = partial.indexOf("TreasuryManager.contributeFromMemeSell(")
        val completion = partial.indexOf("NativeTerminalClose8527.close8527(")
        assertTrue(booking >= 0 && completion > booking)
    }

    @Test fun entrySizingConsumesTheSameExactLedgerMultiplierWithoutChangingTheCapOrProbe() {
        val root = "src/main/kotlin/com/lifecyclebot/v3/scoring/"
        val express = java.io.File(root + "ShitCoinExpress.kt").readText()
        assertTrue(express.contains("private const val DAILY_MAX_LOSS_SOL = 2.0"))
        assertTrue(express.contains("val dailyLossSizeMult8527 = dailyLossSizeMultiplier8527(dailyPnl)"))
        assertTrue(express.contains("positionSol *= dailyLossSizeMult8527"))
        assertFalse(express.contains("(pnlSol * 100).toLong()"))
        val sniper = java.io.File(root + "ProjectSniperAI.kt").readText()
        assertTrue(sniper.contains("private const val DAILY_MAX_LOSS_SOL = 1.5"))
        assertTrue(sniper.contains("val dailyLossProbeMult = dailyLossSizeMult8527"))
        assertTrue(sniper.contains("if (dailyPnlSol <= -DAILY_MAX_LOSS_SOL) 0.35 else 1.0"))
        assertFalse(sniper.contains("Math.round(pnlSol * 100)"))
    }
}
