package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.engine.truth.CurveMark8332
import com.lifecyclebot.engine.truth.CurveReserves7951
import com.lifecyclebot.engine.truth.EntrySnapPersist8332
import com.lifecyclebot.engine.truth.GradeBind8332
import com.lifecyclebot.engine.truth.IdentitySkew8332
import com.lifecyclebot.engine.truth.SpecialistDelivery8332
import com.lifecyclebot.network.OhlcvRoute8332
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8332 — learning delivery and data coverage (5.0.8326 runtime audit, "Cortex / data"): entry snapshot idempotence and
 * boot-tolerant persistence, one canonical grade-bind key, dead OHLCV source detection and routing with freshness stamps,
 * the curve as an un-graduated pump coin's mark, and the price/cap identity compared only inside one write.
 */
class Aate8332LearningDataTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    // ── 1. entry snapshot persistence ──

    @Test fun durableWriteIsIdempotent() {
        assertTrue(EntrySnapPersist8332.needsWrite8332(null, "{\"a\":1}"))
        assertTrue(EntrySnapPersist8332.needsWrite8332("{\"a\":0}", "{\"a\":1}"))
        assertFalse("an identical durable copy is never rewritten", EntrySnapPersist8332.needsWrite8332("{\"a\":1}", "{\"a\":1}"))
        assertTrue(EntrySnapPersist8332.durable8332(prefsOk = true, kvReady = false))
        assertTrue(EntrySnapPersist8332.durable8332(prefsOk = false, kvReady = true))
        assertFalse(EntrySnapPersist8332.durable8332(prefsOk = false, kvReady = false))
    }

    @Test fun queuedSealFlushesExactlyOnceWhenTheStoreCanTakeIt() {
        EntrySnapPersist8332.resetForTest8332()
        EntrySnapPersist8332.queue8332("pos-A", "{\"lane\":\"MOONSHOT\"}")
        EntrySnapPersist8332.queue8332("pos-A", "{\"lane\":\"MOONSHOT\"}")   // same seal twice: one queue entry
        assertEquals(1, EntrySnapPersist8332.pendingCount8332())
        var storeReady = false
        val writes = mutableListOf<String>()
        val writer: (String, String) -> Boolean = { pid, _ -> writes.add(pid); storeReady }
        assertEquals(0, EntrySnapPersist8332.flush8332(writer))          // store still closed: stays queued
        assertEquals(1, EntrySnapPersist8332.pendingCount8332())
        storeReady = true
        assertEquals(1, EntrySnapPersist8332.flush8332(writer))          // durable now: leaves the queue
        assertEquals(0, EntrySnapPersist8332.pendingCount8332())
        assertEquals(0, EntrySnapPersist8332.flush8332(writer))          // nothing left: no second write
        assertEquals(listOf("pos-A", "pos-A"), writes)
        val line = EntrySnapPersist8332.statusLine8332()
        assertTrue(line, line.startsWith("entrySnap8332[written=1, readOk=0, missing=0, pending=0, queued=1, flushed=1]"))
        EntrySnapPersist8332.resetForTest8332()
    }

    // ── 2. one canonical grade-bind key ──

    @Test fun canonicalLaneIsTheSealedEntryLaneThenSealedIdentityThenClose() {
        assertEquals("MOONSHOT", GradeBind8332.canonicalLane8332("moonshot ", "M1", "CASHGEN", "M1", "SHITCOIN"))
        assertEquals("CASHGEN", GradeBind8332.canonicalLane8332("MOONSHOT", "OTHER", "cashgen", "M1", "SHITCOIN"))
        assertEquals("SHITCOIN", GradeBind8332.canonicalLane8332(null, null, "", "M1", " shitcoin"))
    }

    @Test fun openedAtPrefersTheSealedEntryTime() {
        assertEquals(1_000L, GradeBind8332.openedAt8332(1_000L, 5_000L, 3_000L))
        assertEquals(2_000L, GradeBind8332.openedAt8332(null, 5_000L, 3_000L))
        assertEquals(2_000L, GradeBind8332.openedAt8332(0L, 5_000L, 3_000L))
        assertEquals(2_000L, GradeBind8332.openedAt8332(9_000L, 5_000L, 3_000L))   // a seal after the close is not an open time
        assertEquals(5_000L, GradeBind8332.openedAt8332(null, 5_000L, -1L))
    }

    @Test fun unboundCloseNamesWhyAndTheLineReportsIt() {
        assertEquals("REPLAY_FILED", GradeBind8332.unboundWhy8332(replay = true, alreadyFiled = true, hasSnapshot = true, snapLaneDiffers = false, hasSealed = true))
        assertEquals("REPLAY_UNMATCHED", GradeBind8332.unboundWhy8332(true, false, false, false, false))
        assertEquals("NO_ENTRY_IDENTITY", GradeBind8332.unboundWhy8332(false, false, false, false, false))
        assertEquals("LANE_KEY_MISMATCH", GradeBind8332.unboundWhy8332(false, false, true, true, false))
        assertEquals("NO_DECISION_RECORD", GradeBind8332.unboundWhy8332(false, false, true, false, true))
        GradeBind8332.resetForTest8332()
        GradeBind8332.noteBound8332(); GradeBind8332.noteBound8332()
        GradeBind8332.noteUnbound8332("LANE_KEY_MISMATCH")
        assertEquals("gradeBind8332[bound=2, unbound=1 why{LANE_KEY_MISMATCH=1}]", GradeBind8332.line8332())
        GradeBind8332.resetForTest8332()
    }

    // ── 3. specialist delivery ──

    @Test fun ownerLaneIsTheLedgerStampElseTheSameMintSnapshotLane() {
        assertEquals("CASHGEN", SpecialistDelivery8332.ownerLane8332("CASHGEN", "MOONSHOT", "M1", "M1"))
        assertEquals("MOONSHOT", SpecialistDelivery8332.ownerLane8332("", "MOONSHOT", "M1", "M1"))
        assertNull("another mint's snapshot never delivers", SpecialistDelivery8332.ownerLane8332(null, "MOONSHOT", "M2", "M1"))
        assertNull(SpecialistDelivery8332.ownerLane8332(null, "", "M1", "M1"))
    }

    // ── 4. dead OHLCV source detection and routing ──

    private fun bar(ts: Long) = Candle(ts = ts, priceUsd = 1.0, marketCap = 0.0, volumeH1 = 0.0, volume24h = 0.0, highUsd = 1.0, lowUsd = 1.0, openUsd = 1.0)

    @Test fun twelveZeroBarAnswersDeclareTheSourceDeadAndOneServedAnswerRevivesIt() {
        OhlcvRoute8332.resetForTest8332()
        val t0 = 1_700_000_000_000L
        val src = OhlcvRoute8332.GECKO_8332
        for (i in 0 until OhlcvRoute8332.DEAD_AFTER_ZERO_ANSWERS_8332 - 1) OhlcvRoute8332.noteAnswer8332(src, 0, t0 + i)
        assertFalse("eleven answered-empty reads are not death", OhlcvRoute8332.dead8332(src))
        assertTrue(OhlcvRoute8332.probeDue8332(src, t0))
        OhlcvRoute8332.noteAnswer8332(src, 0, t0 + 100L)
        assertTrue(OhlcvRoute8332.dead8332(src))
        assertFalse("just declared dead: no probe yet", OhlcvRoute8332.probeDue8332(src, t0 + 101L))
        assertEquals(OhlcvRoute8332.PROBE_BASE_MS_8332 - 1L, OhlcvRoute8332.probeWaitMs8332(src, t0 + 101L))
        val probeAt = t0 + 100L + OhlcvRoute8332.PROBE_BASE_MS_8332
        assertTrue("the first probe is due after five minutes", OhlcvRoute8332.probeDue8332(src, probeAt))
        assertFalse("one probe per interval", OhlcvRoute8332.probeDue8332(src, probeAt + 1L))
        assertEquals(OhlcvRoute8332.probeIntervalMs8332(1), OhlcvRoute8332.probeWaitMs8332(src, probeAt))
        OhlcvRoute8332.noteAnswer8332(src, 0, probeAt + 10L)      // the probe answered empty: still dead
        assertTrue(OhlcvRoute8332.dead8332(src))
        OhlcvRoute8332.noteAnswer8332(src, 7, probeAt + 20L)      // a served answer revives at once
        assertFalse(OhlcvRoute8332.dead8332(src))
        assertEquals(0L, OhlcvRoute8332.probeWaitMs8332(src, probeAt + 21L))
        OhlcvRoute8332.resetForTest8332()
    }

    @Test fun probeBackoffDoublesToOneHour() {
        assertFalse(OhlcvRoute8332.deadAfter8332(OhlcvRoute8332.DEAD_AFTER_ZERO_ANSWERS_8332 - 1))
        assertTrue(OhlcvRoute8332.deadAfter8332(OhlcvRoute8332.DEAD_AFTER_ZERO_ANSWERS_8332))
        assertEquals(60L * 60_000L, OhlcvRoute8332.PROBE_MAX_MS_8332)
        assertEquals(5L * 60_000L, OhlcvRoute8332.probeIntervalMs8332(0))
        assertEquals(10L * 60_000L, OhlcvRoute8332.probeIntervalMs8332(1))
        assertEquals(40L * 60_000L, OhlcvRoute8332.probeIntervalMs8332(3))
        assertEquals(60L * 60_000L, OhlcvRoute8332.probeIntervalMs8332(4))
        assertEquals(60L * 60_000L, OhlcvRoute8332.probeIntervalMs8332(9))
    }

    @Test fun servedBarsAreStampedAndAStaleBarNeverReadsAsCurrent() {
        OhlcvRoute8332.resetForTest8332()
        val now = 1_700_000_000_000L
        val bars = listOf(bar(now - 180_000L), bar(now - 120_000L), bar(now - 60_000L))
        OhlcvRoute8332.served8332(OhlcvRoute8332.LOCAL_8332, "MintA", bars, now)
        val st = OhlcvRoute8332.stamp8332("MintA")
        assertNotNull(st)
        assertEquals(OhlcvRoute8332.LOCAL_8332, st!!.source)
        assertEquals(now - 60_000L, st.newestBarMs)
        assertTrue(OhlcvRoute8332.current8332("MintA", 1, now))
        assertFalse("a 1 m bar three minutes old is not the present price", OhlcvRoute8332.current8332("MintA", 1, now + 120_001L))
        assertTrue("the same bar is current for a 5 m frame", OhlcvRoute8332.current8332("MintA", 5, now + 120_001L))
        assertFalse(OhlcvRoute8332.current8332("Unknown", 1, now))
        assertFalse("nothing from the future", OhlcvRoute8332.fresh8332(now + 1L, now, 1))
        assertFalse(OhlcvRoute8332.fresh8332(0L, now, 1))
        val line = OhlcvRoute8332.statusLine8332(now)
        assertTrue(line, line.startsWith("ohlcvRoute8332[source=local, bars{local=3}, deadSince{none}"))
        OhlcvRoute8332.resetForTest8332()
    }

    // ── 5. the curve as the mark; identity inside one write ──

    @Test fun curveIsTheVenueOnlyWhileUngraduatedKnownAndFresh() {
        val now = 1_700_000_000_000L
        assertTrue(CurveMark8332.curveIsVenue8332(graduated = false, curveKnown = true, readingAtMs = now - 30_000L, nowMs = now))
        assertFalse("graduated: the pool is the venue", CurveMark8332.curveIsVenue8332(true, true, now - 30_000L, now))
        assertFalse(CurveMark8332.curveIsVenue8332(false, false, now - 30_000L, now))
        assertFalse(CurveMark8332.curveIsVenue8332(false, true, null, now))
        assertFalse("a stale reading is not a mark", CurveMark8332.curveIsVenue8332(false, true, now - CurveMark8332.CURVE_MARK_MAX_AGE_MS_8332 - 1L, now))
        assertEquals(0.5, CurveMark8332.markUsd8332(0.0025, 200.0)!!, 1e-12)
        assertNull(CurveMark8332.markUsd8332(0.0, 200.0))
        assertNull(CurveMark8332.markUsd8332(0.0025, Double.NaN))
    }

    @Test fun curveMarkReadsTheCoinsOwnReservesAtTheirObservedTime() {
        CurveReserves7951.resetForTest7951()
        val now = System.currentTimeMillis()
        val mint = "Curve8332ActiveAAAAAAAAAAAAAAAAAAAAAAApump"
        CurveReserves7951.noteAccount7951(mint, 31.2, 1_031_000_000.0, 1.2, now - 5_000L)
        val m = CurveMark8332.read8332("solana|$mint", 200.0, now)
        assertNotNull("an active curve read 5 s ago is the mark", m)
        assertEquals(CurveReserves7951.priceSol7951(31.2, 1_031_000_000.0) * 200.0, m!!.priceUsd, 1e-15)
        assertEquals(now - 5_000L, m.observedAtMs)
        assertNull("no SOL price: no mark", CurveMark8332.read8332(mint, 0.0, now))
        assertNull("a reading older than the window: no mark", CurveMark8332.read8332(mint, 200.0, now + CurveMark8332.CURVE_MARK_MAX_AGE_MS_8332 + 10_000L))
        assertNull(CurveMark8332.read8332("Unknown8332MintBBBBBBBBBBBBBBBBBBBBBBpump", 200.0, now))
        CurveReserves7951.resetForTest7951()
    }

    @Test fun identityIsComparedOnlyInsideOneWrite() {
        val eps = 0.005
        assertEquals(1_500L, IdentitySkew8332.SAME_WRITE_MS_8332)
        assertEquals(0.5, IdentitySkew8332.SKEW_RATIO_MIN_8332, 0.0)
        assertEquals(2.0, IdentitySkew8332.SKEW_RATIO_MAX_8332, 0.0)
        val h = IdentitySkew8332.Verdict8332.HELD; val s = IdentitySkew8332.Verdict8332.SKEW; val b = IdentitySkew8332.Verdict8332.BROKEN
        assertEquals(h, IdentitySkew8332.classify8332(1.004, eps, 1_000L, 9_000L))
        assertEquals("same write, 1.3x: broken", b, IdentitySkew8332.classify8332(1.3, eps, 1_000L, 1_000L))
        assertEquals("same write (inside 1.5 s), 0.8x: broken", b, IdentitySkew8332.classify8332(0.8, eps, 1_000L, 2_400L))
        assertEquals("different writes, 1.3x: skew", s, IdentitySkew8332.classify8332(1.3, eps, 1_000L, 9_000L))
        assertEquals("different writes, 0.6x: skew", s, IdentitySkew8332.classify8332(0.6, eps, 9_000L, 1_000L))
        assertEquals("different writes, 1211x: a unit break stays broken", b, IdentitySkew8332.classify8332(1211.0, eps, 1_000L, 9_000L))
        assertEquals(b, IdentitySkew8332.classify8332(0.01, eps, 1_000L, 9_000L))
        assertEquals(b, IdentitySkew8332.classify8332(Double.NaN, eps, 1_000L, 9_000L))
        assertEquals(b, IdentitySkew8332.classify8332(0.0, eps, 1_000L, 9_000L))
    }

    // ── 6. source-shape pins for the hook points ──

    @Test fun entrySnapshotSealIsDurableQueuedAndIdempotent() {
        val s = src("engine/truth/EntryStrategySnapshot6450.kt")
        assertTrue(s.contains("EntrySnapPersist8332.queue8332(snap.positionId, evidence8093)"))
        assertTrue(s.contains("if (!EntrySnapPersist8332.needsWrite8332(stored8332, evidence8093)) { EntrySnapPersist8332.noteWritten(); return }"))
        assertTrue(s.contains("if (prior == snap) { persist6567(snap); return false }"))
        assertTrue("a miss retries the queued seals before it is a miss", s.contains("flushPending8332()\n        val retried = lookup8332(positionId)"))
        assertTrue(s.contains("EntrySnapPersist8332.statusLine8332()"))
    }

    @Test fun cortexCloseBindsOnTheSealedSnapshotKeyAndNamesEveryUnboundClose() {
        val c = src("engine/cortex/Cortex7885.kt")
        assertTrue(c.contains("recoverRead8187(env, closeKey7931, mode, sealed8144, snap8332)"))
        assertTrue(c.contains("val snapLane8332 = snap8332?.takeIf { it.mint == env.mint && it.mode8090.equals(mode, true) }?.entryLane"))
        assertTrue(c.contains("gradeBook8144.admittedDecision8187(m, l, mode, openedAtSnap8332, ENTRY_READ_MATCH_MS)"))
        assertTrue(c.contains("GradeBind8332.noteUnbound8332("))
        assertTrue(c.contains("GradeBind8332.noteBound8332()"))
        assertTrue(c.contains("GradeBind8332.line8332()"))
        // The snapshot is read before the lock (8325: no IO under the Cortex monitor); the lock structure is untouched.
        assertTrue(c.indexOf("val snap8332 = if (!eligible) null else") < c.indexOf("synchronized(this) {\n            val tradeId = env.tradeId"))
    }

    @Test fun specialistCloseIsDeliveredFromTheSealedEntryLane() {
        val e = src("engine/truth/AateDecisionEnvelope6512.kt")
        assertTrue(e.contains(".takeUnless { it.isNullOrBlank() } ?: SpecialistDelivery8332.sealedEntryLane8332(env.positionId, env.mint),"))
        assertTrue(e.contains("val ledgerLane6747 = LaneAttributionLedger6427.getEntryLane(env.positionId).takeUnless { it.isNullOrBlank() }\n                    ?: SpecialistDelivery8332.sealedEntryLane8332(env.positionId, env.mint)"))
        assertTrue(src("engine/ToolkitSignalSheet.kt").contains("SpecialistDelivery8332.statusLine8332()"))
    }

    @Test fun ohlcvFeedRoutesAroundTheDeadSourceAndStampsEverySeries() {
        val f = src("network/SolanaOhlcvFeed6916.kt")
        assertTrue(f.contains("if (OhlcvRoute8332.dead8332(OhlcvRoute8332.GECKO_8332) && !OhlcvRoute8332.probeDue8332(OhlcvRoute8332.GECKO_8332, now)) {"))
        assertTrue(f.contains("return localTapeCandles8285(mint, timeframeLabel, n, now) ?: emptyList()\n        }\n        FeatureSourceCounters8285.note8285(\"shape\", \"gecko\", FeatureSourceCounters8285.ATTEMPT)"))
        assertTrue(f.contains("OhlcvRoute8332.noteAnswer8332(OhlcvRoute8332.GECKO_8332, chronological.size, now)"))
        assertTrue(f.contains("OhlcvRoute8332.served8332(OhlcvRoute8332.GECKO_8332, mint, chronological, now)"))
        assertTrue(f.contains("OhlcvRoute8332.served8332(OhlcvRoute8332.PAPRIKA_8332, mint, paprika7293, now)"))
        assertTrue(f.contains("OhlcvRoute8332.served8332(OhlcvRoute8332.LOCAL_8332, mint, served8332, nowMs)"))
        assertTrue("a dead source is a cooldown to the seed: no waiting on its probe", f.contains("if (probe8332 > 0L) return SlotWait7809(probe8332, cooldown = true)"))
        assertTrue(f.contains("OhlcvRoute8332.statusLine8332()"))
    }

    @Test fun markRepairReadsTheCurveAndIdentityIsCheckedAtMatchingTimestamps() {
        val r = src("engine/truth/MarkIdentityRepairAuthority7236.kt")
        assertTrue(r.contains("} else if (curveRepair8332(mint, solUsd)) {"))
        assertTrue(r.contains("cache[mint] = Repaired(m.priceUsd, CurveMark8332.SOURCE_8332, m.observedAtMs)"))
        assertTrue(r.contains("MARK_REPAIR_SUCCEEDED_7236_PUMP_CURVE_8332"))
        val t = src("engine/truth/TokenMetricsAuthority7069.kt")
        assertTrue("the epsilon is not widened", t.contains("private const val IDENTITY_EPSILON = 0.005"))
        assertTrue(t.contains("if (IdentitySkew8332.classify8332(ratio, IDENTITY_EPSILON, priceAt8332, capAt8332) == IdentitySkew8332.Verdict8332.SKEW) {"))
        assertTrue(t.contains("TOKEN_METRICS_UNVERIFIABLE_WRITE_SKEW_8332"))
        // The skew branch returns the provider's price untouched and never reaches the gate feed.
        val skewIdx = t.indexOf("TOKEN_METRICS_UNVERIFIABLE_WRITE_SKEW_8332")
        val retIdx = t.indexOf("return Metrics7069(price, mcap, storedSupply, repaired = false, verifiable = false)", skewIdx)
        assertTrue(retIdx > skewIdx && retIdx < t.indexOf("if (capStale7268 || corroborated7268) {"))
    }
}
