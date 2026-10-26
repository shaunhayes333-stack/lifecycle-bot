package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ShadowPersist8355
import com.lifecyclebot.engine.truth.ShadowPersist8355.CortexShadowOutcome8372
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The actual persisted outcome selector, not a parallel test-only implementation of shadow accounting. */
class Aate8372ShadowLearningTest {
    private fun ticket(
        mint: String = "MintA", lane: String = "MOONSHOT", atMs: Long = 1_000L,
        kind: String = "CORTEX_YES", outcome: CortexShadowOutcome8372 = CortexShadowOutcome8372(exit = "RUNNER"),
    ) = ShadowPersist8355.TicketRec8355(
        mint = mint, symbol = "COIN", lane = lane, setup = "FIRST_DIP", tactic = "PULLBACK", scoreBand = "S41-60",
        band = "MC_10K_100K", signalPx = 1.0, atMs = atMs, costPct = 3.0, stopPct = 15.0, armPct = 20.0,
        runner = true, nativeEligible = kind == "CORTEX_YES", lastPx = 1.25, lastPxAtMs = atMs + 60_000L,
        legs = listOf(
            ShadowPersist8355.LegRec8355("NOW", "RUNNER", true, false, 1.0, atMs, 1.0, 0.0, 0, 25.0, 0.0),
            ShadowPersist8355.LegRec8355("NOW", "SCALP", true, false, 1.0, atMs, 1.0, 0.0, 0, 25.0, 0.0),
        ), kind8360 = kind, markSrc8360 = "OBSERVED_POOL", cortex8372 = outcome,
    )

    @Test fun bothWinnersAndLosersSurviveForYesNoAndHoldWithoutNativeEligibilityFilter() {
        for (kind in listOf("CORTEX_YES", "CORTEX_NO", "CORTEX_HOLD")) {
            for (net in listOf(22.0, -13.0)) {
                val t = ticket(kind = kind)
                val outcome = t.cortex8372.closed8372("NOW", "RUNNER", net, net + t.costPct, "STOP", 20_000L, true)
                assertTrue("$kind $net must be delivered", outcome.pending8372())
                assertEquals(net, outcome.netPct, 0.0)
                assertEquals(net + 3.0, outcome.grossPct, 0.0)
                assertEquals("no simulated close is a canonical/native fill", kind == "CORTEX_YES", t.nativeEligible)
                val restored = ShadowPersist8355.decodeTicket8355(ShadowPersist8355.encodeTicket8355(t.copy(cortex8372 = outcome)))!!
                assertEquals(kind, restored.kind8360)
                assertEquals(outcome, restored.cortex8372)
            }
        }
    }

    @Test fun exitFrozenAtEntryCannotCherryPickOrDoubleCountTheAlternative() {
        val original = CortexShadowOutcome8372(exit = "RUNNER")
        val alternateWin = original.closed8372("NOW", "SCALP", 40.0, 43.0, "TAKE_PROFIT", 5_000L, true)
        assertEquals(original, alternateWin)
        assertFalse(alternateWin.pending8372())
        val runnerLoss = alternateWin.closed8372("NOW", "RUNNER", -10.0, -7.0, "STOP", 7_000L, true)
        assertTrue(runnerLoss.pending8372())
        assertEquals(-10.0, runnerLoss.netPct, 0.0)
        assertEquals(runnerLoss, runnerLoss.closed8372("NOW", "SCALP", 200.0, 203.0, "TIMEOUT", 9_000L, true))
        assertEquals(runnerLoss, runnerLoss.closed8372("NOW", "RUNNER", 300.0, 303.0, "REPLAY", 10_000L, true))
    }

    @Test fun scalpOwnerDoesNotWaitForRunnerAndDelayedEntryVariantsNeverTrainIt() {
        val original = CortexShadowOutcome8372(exit = "SCALP")
        assertFalse(original.owns8372("PULLBACK", "SCALP"))
        assertFalse(original.owns8372("CONFIRM", "SCALP"))
        assertFalse(original.owns8372("NOW", "RUNNER"))
        val scalp = original.closed8372("NOW", "SCALP", 18.0, 21.0, "SOLD_OUT", 4_000L, true)
        assertTrue(scalp.pending8372())
        assertEquals("SCALP", scalp.exit)
        assertEquals(scalp, scalp.closed8372("PULLBACK", "SCALP", -30.0, -27.0, "STOP", 7_000L, true))
    }

    @Test fun failedUnfilledLostAndInvalidMarksAreUnavailableNeverInventedReturns() {
        for (why in listOf("UNLANDED_BUY", "UNFILLED", "LOST_MARK", "EXPIRED_ON_RESTORE_8372")) {
            val unavailable = CortexShadowOutcome8372(exit = "RUNNER")
                .closed8372("NOW", "RUNNER", 999.0, 1002.0, why, 5_000L, executable = false)
            assertTrue(unavailable.pending8372())
            assertEquals(why, unavailable.unavailable)
            assertTrue(unavailable.netPct.isNaN())
            assertTrue(unavailable.grossPct.isNaN())
            assertEquals(unavailable, unavailable.closed8372("NOW", "RUNNER", 2.0, 5.0, "LATE_MARK", 9_000L, true))
        }
        val invalid = CortexShadowOutcome8372(exit = "RUNNER")
            .closed8372("NOW", "RUNNER", Double.NaN, 5.0, "INVALID_EXIT_MARK_8372", 5_000L, true)
        assertTrue(invalid.unavailable.isNotBlank())
        assertTrue(invalid.netPct.isNaN())
    }

    @Test fun pendingTerminalReceiptSurvivesRestartAndOnlyAcknowledgementEndsDelivery() {
        val outcome = CortexShadowOutcome8372(exit = "SCALP")
            .closed8372("NOW", "SCALP", -6.0, -3.0, "STOP", 20_000L, true)
        val closedTicket = ticket(outcome = outcome).let { it.copy(legs = it.legs.map { leg -> leg.copy(dead = true) }) }
        val restored = ShadowPersist8355.decodeTicket8355(ShadowPersist8355.encodeTicket8355(closedTicket))!!
        assertTrue(restored.legs.all { it.dead })
        assertTrue("dead ticket still owes its exact outcome", restored.cortex8372.pending8372())
        assertEquals(1_000L, restored.atMs)
        assertEquals(20_000L, restored.cortex8372.atMs)
        val ack = restored.cortex8372.acknowledged8372()
        assertFalse(ack.pending8372())
        assertFalse(ack.awaiting8372())
        assertEquals(ack, ack.closed8372("NOW", "SCALP", 55.0, 58.0, "DUPLICATE", 25_000L, true))
        assertEquals(ack, ShadowPersist8355.decodeTicket8355(ShadowPersist8355.encodeTicket8355(restored.copy(cortex8372 = ack)))!!.cortex8372)
    }

    @Test fun expiredOpenDecisionReleasesItsTraceButKnownTerminalReceiptKeepsItsResult() {
        val unpriced = CortexShadowOutcome8372(exit = "RUNNER").unavailable8372("EXPIRED_ON_RESTORE_8372", 9_000L)
        assertTrue(unpriced.pending8372())
        assertEquals("EXPIRED_ON_RESTORE_8372", unpriced.unavailable)
        assertTrue(unpriced.netPct.isNaN())
        val known = CortexShadowOutcome8372(exit = "RUNNER")
            .closed8372("NOW", "RUNNER", 100.0, 103.0, "SOLD_OUT", 5_000L, true)
        assertEquals(known, known.unavailable8372("EXPIRED_ON_RESTORE_8372", 99_000L))
    }

    @Test fun tokenLaneAndAssessmentEpochRemainExactThroughPersistence() {
        val original = ticket()
        val records = listOf(original, original.copy(mint = "MintB"), original.copy(lane = "EXPRESS"), original.copy(atMs = 901_000L))
        val restored = records.map { ShadowPersist8355.decodeTicket8355(ShadowPersist8355.encodeTicket8355(it))!! }
        val ids = restored.map { "${it.mint}|${it.lane}|${it.atMs}" }.toSet()
        assertEquals(4, ids.size)
        assertEquals(records, restored)
    }

    @Test fun restartRetainsTriggeredSellCauseClockAndPartiallyBankedState() {
        val open = ticket().let { t -> t.copy(legs = t.legs.map { leg -> leg.copy(
            dead = false, held = 0.7, banked = 4.5, tiers = 1,
            exitAt8366 = 17_000L, exitWhy8366 = "RAPID_FLUID_STOP",
        ) }) }
        val restored = ShadowPersist8355.decodeTicket8355(ShadowPersist8355.encodeTicket8355(open))!!
        assertEquals(open, restored)
        assertEquals(17_000L, restored.legs.first().exitAt8366)
        assertEquals("RAPID_FLUID_STOP", restored.legs.first().exitWhy8366)
        // A historically persisted leg lacked these fields; it remains readable
        // but no original trigger or price event is invented at restoration.
        val fields = ShadowPersist8355.encodeTicket8355(open).split('|').toMutableList()
        fields[18] = fields[18].split(';').joinToString(";") { it.split(',').take(14).joinToString(",") }
        val old = ShadowPersist8355.decodeTicket8355(fields.joinToString("|"))!!
        assertEquals(0L, old.legs.first().exitAt8366)
        assertEquals("", old.legs.first().exitWhy8366)
    }

    @Test fun delayedExitRequiresPostLatencySourceObservationNotCachedMarkReread() {
        val trigger = 10_000L
        val latency = 5_000L
        val now = 20_000L
        assertFalse(ShadowPersist8355.exitFillObserved8372(9_000L, trigger, latency, now))
        assertFalse(ShadowPersist8355.exitFillObserved8372(trigger, trigger, latency, now))
        assertFalse(ShadowPersist8355.exitFillObserved8372(14_999L, trigger, latency, now))
        assertFalse(ShadowPersist8355.exitFillObserved8372(0L, trigger, latency, now))
        assertFalse(ShadowPersist8355.exitFillObserved8372(now + 1L, trigger, latency, now))
        assertFalse(ShadowPersist8355.exitFillObserved8372(now, 0L, latency, now))
        assertTrue(ShadowPersist8355.exitFillObserved8372(15_000L, trigger, latency, now))
        assertTrue("same price is valid when it is genuinely a new observation",
            ShadowPersist8355.exitFillObserved8372(now, trigger, latency, now))
    }

    @Test fun historicalTicketFormatsRemainReadableWithoutInventingOldTrainingSnapshots() {
        val encoded = ShadowPersist8355.encodeTicket8355(ticket())
        for (oldLength in listOf(19, 21)) {
            val old = ShadowPersist8355.decodeTicket8355(encoded.split('|').take(oldLength).joinToString("|"))
            assertNotNull(old)
            assertEquals("", old!!.cortex8372.exit)
            assertFalse(old.cortex8372.awaiting8372())
            val noRewrite = old.cortex8372.closed8372("NOW", "RUNNER", 50.0, 53.0, "TIMEOUT", 99_000L, true)
            assertEquals(old.cortex8372, noRewrite)
        }
    }

    @Test fun runtimeDeliversExactSelectedOutcomeOffTheAssessmentPathAndKeepsUnackedTicket() {
        val source = File("src/main/kotlin/com/lifecyclebot/engine/truth/ExploreShadow8347.kt").readText()
        val assessment = source.substringAfter("fun onCortexAssessment8368(").substringBefore("private fun open8347(")
        assertTrue(assessment.contains("): Long?"))
        assertTrue(assessment.contains("return open8347("))
        assertFalse(assessment.contains("onShadowOutcome8372("))
        assertFalse(assessment.contains("onShadowUnavailable8372("))
        assertTrue(source.contains("tickets.putIfAbsent(ticketKey8368, t)"))
        assertTrue(source.contains("t.mint, t.lane, t.atMs, outcome.netPct, outcome.grossPct, t.setup, outcome.atMs"))
        assertTrue(source.contains("if (!acknowledged) { cortexRetry8372.incrementAndGet(); return }"))
        assertTrue(source.contains("t.legs.all { it.dead } && !t.cortex8372.awaiting8372()"))
        assertTrue(source.contains("tickActive8372.compareAndSet(false, true)"))
        // V5.0.8377 — receipts are staged per slice and committed once, before delivery.
        assertTrue(source.contains("if (!t.receiptDurable8372) {"))
        assertTrue(source.contains("val written = flushOpen8386(nowMs, durable = receipts8377.isNotEmpty())"))
        assertFalse(assessment.contains("receiptDurable8372 = true"))
        assertTrue(source.contains("current !== completed"))
        assertTrue(source.contains("synchronized(ticketReservation8372)"))
        assertTrue(source.contains("t.lastPxAtMs = observation.observedAtMs"))
        assertTrue(source.contains("ShadowPersist8355.exitFillObserved8372(observedAtMs8372, leg.exitAt8366"))
        val tick = source.substringAfter("fun tick8347(").substringBefore("private fun tickSingle8372(")
        assertTrue(tick.contains("finally"))
        assertEquals("one full checkpoint per batch, never per terminal delivery", 1,
            Regex("Cortex7885\\.flushShadowOutcomes8372\\(").findAll(source).count())
        assertTrue(tick.contains("Cortex7885.flushShadowOutcomes8372()"))
        assertFalse("shadow must never bypass live money authority", source.contains("withMode(\"LIVE\", canonical = true)"))
    }

    @Test fun shadowLearningTicksAreSingleFlightOffLoopAndCancellationReleasesAfterCompletion() {
        val source = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val worker = source.substringAfter("private fun dispatchShadowEvidenceTicks8372(")
            .substringBefore("private fun requestSellOffLoop7288(")
        assertTrue(worker.contains("shadowEvidenceTickInFlight8372.compareAndSet(false, true)"))
        assertTrue(worker.contains("start = CoroutineStart.LAZY"))
        assertTrue(worker.contains("job.invokeOnCompletion"))
        assertTrue(worker.indexOf("job.invokeOnCompletion") < worker.indexOf("job.start()"))
        val release = worker.substringAfter("job.invokeOnCompletion").substringBefore("job.start()")
        assertTrue(release.contains("shadowEvidenceTickJob8372.compareAndSet(job, null)"))
        assertTrue(release.contains("shadowEvidenceTickInFlight8372.set(false)"))
        assertTrue(worker.contains("shadowEvidenceTickEpoch8372.get() != epoch"))
        assertTrue(worker.indexOf("EvidenceShadow8344.tick8344") < worker.indexOf("ExploreShadow8347.tick8347"))
        val cancel = worker.substringAfter("private fun cancelShadowEvidenceTicks8372(")
        assertTrue(cancel.contains("shadowEvidenceTickEpoch8372.incrementAndGet()"))
        assertTrue(cancel.contains("shadowEvidenceTickJob8372.get()?.cancel"))
        assertFalse("cancel must not release the lease while synchronous work still drains", cancel.contains("shadowEvidenceTickInFlight8372.set(false)"))
        val loop = source.substringAfter("executor.checkShadowPositions(tokenStatesCopy)").substringBefore("// HOT MODE SWITCH")
        assertTrue(loop.contains("dispatchShadowEvidenceTicks8372(tokenStatesCopy)"))
        assertFalse(loop.contains("EvidenceShadow8344.tick8344"))
        assertFalse(loop.contains("ExploreShadow8347.tick8347"))
        assertTrue(source.contains("cancelShadowEvidenceTicks8372(\"onDestroy\")"))
        assertTrue(source.contains("cancelShadowEvidenceTicks8372(\"stopBot:\$source\")"))
        val initializer = source.substringAfter("private fun initializeShadowEvidence8372()")
            .substringBefore("private fun dispatchShadowEvidenceTicks8372(")
        assertTrue(initializer.contains("synchronized(shadowEvidenceInitLock8372)"))
        assertTrue(initializer.contains("if (!shadowEvidenceInitialized8372)"))
        assertTrue(initializer.contains("EvidenceShadow8344.initReady8372() &&"))
        assertTrue(initializer.contains("ExploreShadow8347.initReady8372()"))
        assertTrue(worker.contains("if (!shadowEvidenceInitialized8372) initializeShadowEvidence8372()"))
        assertTrue(worker.indexOf("initializeShadowEvidence8372()") < worker.indexOf("EvidenceShadow8344.tick8344"))
        assertTrue(source.substringAfter("private fun createChannels()").contains("initializeShadowEvidence8372()"))
    }

    @Test fun partiallyFailedRestoreRetriesOnlyMissingStoresWithoutReplacingLiveEpisodes() {
        val truth = "src/main/kotlin/com/lifecyclebot/engine/truth/"
        for (name in listOf("ExploreShadow8347.kt", "EvidenceShadow8344.kt")) {
            val source = File(truth + name).readText()
            assertTrue(source.contains("private val initLock8372 = Any()"))
            assertTrue(source.contains("fun initReady8372(): Boolean = openRestored8372 && booksRestored8372"))
            assertTrue(source.contains("if (!openRestored8372) openRestored8372 = restoreOpen8355(ctx, nowMs)"))
            assertTrue(source.contains("if (!booksRestored8372) booksRestored8372 = try"))
            assertTrue(source.contains("catch (_: Throwable) { false }"))
        }
        val explore = File(truth + "ExploreShadow8347.kt").readText()
        assertTrue(explore.contains("books.attach(ShadowColdStore8397.open(ctx, PREFS_8347))"))
        assertTrue(explore.contains("waits.putIfAbsent(w.key,"))
        assertTrue(explore.contains("if (tickets.containsKey(key)) continue   // partial-init retry"))
        val evidence = File(truth + "EvidenceShadow8344.kt").readText()
        assertTrue(evidence.contains("closes.attach(ShadowColdStore8397.open(ctx, PREFS_8344))"))
        assertTrue(evidence.contains("open.putIfAbsent(r.mint, pos)"))
        assertTrue(evidence.contains("if (open.containsKey(r.mint)) continue   // partial-init retry"))
    }

    @Test fun cortexFilledAndUnfilledPopulationKeysNeverTouchNativeVariantDenominators() {
        for (kind in listOf("CORTEX_YES", "CORTEX_NO", "CORTEX_HOLD")) {
            val keys = ShadowPersist8355.cortexPopulationKeys8372("EXPRESS", "PULLBACK", "RUNNER", kind, "IGNITION", "MOMENTUM")
            val verdict = kind.removePrefix("CORTEX_")
            assertEquals(listOf("C|EXPRESS|PULLBACK|RUNNER|CX:$verdict",
                "C|EXPRESS|PULLBACK|RUNNER|CXS:$verdict:IGNITION",
                "C|EXPRESS|PULLBACK|RUNNER|CXT:$verdict:MOMENTUM"), keys)
            assertTrue(keys.none { it == "EXPRESS|PULLBACK|RUNNER" || it.contains("|S:") || it.contains("|M:") })
        }
        assertTrue(ShadowPersist8355.cortexPopulationKeys8372("EXPRESS", "NOW", "RUNNER", "NATIVE", "IGNITION", "MOMENTUM").isEmpty())
        val source = File("src/main/kotlin/com/lifecyclebot/engine/truth/ExploreShadow8347.kt").readText()
        val closes = source.substringAfter("private fun close8347(").substringBefore("private fun bookKeys8397(")
        val unfilled = source.substringAfter("private fun unfilledKeys8364(").substringBefore("private fun unfilled8364(")
        assertTrue(closes.contains("ShadowPersist8355.cortexPopulationKeys8372("))
        assertTrue(unfilled.contains("ShadowPersist8355.cortexPopulationKeys8372("))
    }

    @Test fun crossAssetLaneCannotUseSolanaFillModelEvenWithARoutedSplMint() {
        for (lane in listOf("MKT_STOCK", "CRYPTO_ALT", "CRYPTO_EXPRESS", "EVM_BSC", "FOREX_EURUSD", "PERPS", "PERPS_SHORT",
            "SHORT_ETH", "EXPRESS_SHORT", "FOREX", " CRYPTO_QUALITY@paper "))
            assertTrue("unsupported venue/side $lane", ShadowPersist8355.crossAssetShadowLane8372(lane))
        for (lane in listOf("CORE", "EXPRESS", "SHITCOIN", "MOONSHOT", "QUALITY", "BLUECHIP", "DIP_HUNTER", "TREASURY"))
            assertFalse(ShadowPersist8355.crossAssetShadowLane8372(lane))
        val source = File("src/main/kotlin/com/lifecyclebot/engine/truth/ExploreShadow8347.kt").readText()
        val assessment = source.substringAfter("fun onCortexAssessment8368(").substringBefore("private fun open8347(")
        assertTrue(assessment.indexOf("crossAssetShadowLane8372(laneRaw)") < assessment.indexOf("isSolanaMint7819(ts.mint)"))
        assertTrue(assessment.contains("CORTEX_UNSUPPORTED_VENUE_SHADOW_8372"))
        assertFalse(assessment.contains("lastPriceUpdate ="))
    }
}
