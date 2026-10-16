package com.lifecyclebot.engine

import com.lifecyclebot.engine.RetentionPolicy8136.Pressure
import com.lifecyclebot.engine.chart.StructureTracker7962
import com.lifecyclebot.engine.truth.SpecialistMiner7972
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8144 — P0 OutOfMemory: pressure-scaled hard caps with value-ranked eviction that never picks a protected entry,
 * a per-store census whose numbers add up, one trim dispatch that reaches every registered store, coalesced
 * whole-blob saves, and the stores the 7977 trims never reached (Cortex, hive book, structure tapes, policy heads).
 */
class Aate8144MemoryTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @After fun tearDown() { SpecialistMiner7972.resetForTest7972() }

    // ── caps ──

    @Test fun capsScaleWithPressureAndRespectTheFloor() {
        assertEquals(30_000, MemoryBounds8144.capFor8144(30_000, Pressure.NORMAL))
        assertEquals(22_500, MemoryBounds8144.capFor8144(30_000, Pressure.SOFT))
        assertEquals(15_000, MemoryBounds8144.capFor8144(30_000, Pressure.HARD))
        assertEquals(7_500, MemoryBounds8144.capFor8144(30_000, Pressure.CRITICAL))
        assertEquals(7, MemoryBounds8144.capFor8144(10, Pressure.SOFT))
        assertEquals(50, MemoryBounds8144.capFor8144(100, Pressure.CRITICAL, floor = 50))
        assertEquals(0, MemoryBounds8144.capFor8144(-5, Pressure.NORMAL))
        assertEquals(900, MemoryBounds8144.trimTarget8144(1_000))
        assertEquals(1_000, MemoryBounds8144.trimTarget8144(1_000, slackPct = 0))
        assertEquals(500, MemoryBounds8144.trimTarget8144(1_000, slackPct = 80))
        assertEquals(60_000L, MemoryBounds8144.saveIntervalMs8144(60_000L, Pressure.NORMAL))
        assertEquals(120_000L, MemoryBounds8144.saveIntervalMs8144(60_000L, Pressure.SOFT))
        assertEquals(300_000L, MemoryBounds8144.saveIntervalMs8144(60_000L, Pressure.HARD))
        assertEquals(600_000L, MemoryBounds8144.saveIntervalMs8144(60_000L, Pressure.CRITICAL))
    }

    @Test fun evictionUnderAnInsertStormKeepsTheCapAndNeverPicksProtected() {
        val entries = (0 until 10_000).map { i ->
            MemoryBounds8144.Candidate8144("k$i", (i % 50).toDouble(), i.toLong(), isProtected = i % 100 == 0)
        }
        val victims = MemoryBounds8144.victims8144(entries, 1_000)
        assertEquals(9_000, victims.size)
        val gone = victims.toHashSet()
        assertTrue(entries.filter { it.isProtected }.none { it.key in gone })
        val kept = entries.filter { it.key !in gone }
        assertEquals(1_000, kept.size)
        // Value-ranked: every unprotected entry kept is worth at least as much as any evicted one.
        val minKept = kept.filter { !it.isProtected }.minOf { it.value }
        val maxGone = entries.filter { it.key in gone }.maxOf { it.value }
        assertTrue(minKept >= maxGone)
        // A store full of protected entries stays over its cap rather than evict one.
        val allProtected = (0 until 20).map { MemoryBounds8144.Candidate8144("p$it", 0.0, 0L, isProtected = true) }
        assertTrue(MemoryBounds8144.victims8144(allProtected, 5).isEmpty())
        // Equal value: the least recently touched goes first.
        val tie = listOf(MemoryBounds8144.Candidate8144("new", 1.0, 200L, false), MemoryBounds8144.Candidate8144("old", 1.0, 100L, false))
        assertEquals(listOf("old"), MemoryBounds8144.victims8144(tie, 1))
        assertTrue(MemoryBounds8144.victims8144(tie, 2).isEmpty())
    }

    @Test fun oldestStampsGoFirstButOnlyPastTheMinimumAgeAndNeverProtected() {
        val stamps = (0 until 10).associate { "m$it" to it * 1_000L }
        assertEquals(listOf("m0", "m1", "m2", "m3", "m4"), MemoryBounds8144.oldestBeyondCap8144(stamps, 5, 0L, 100_000L))
        // Only stamps at least 97 s old may go (m0..m3); m1 is protected.
        assertEquals(listOf("m0", "m2", "m3"), MemoryBounds8144.oldestBeyondCap8144(stamps, 5, 97_000L, 100_000L) { it == "m1" })
        assertTrue(MemoryBounds8144.oldestBeyondCap8144(stamps, 10, 0L, 100_000L).isEmpty())
    }

    // ── coalesced saves ──

    @Test fun oneSaveInFlightAndNoChangeLeftUnsaved() {
        val c = MemorySaveCoalescer8144(10_000L)
        assertTrue(c.claim8144())
        repeat(99) { assertFalse(c.claim8144()) }
        assertEquals(100L, c.requests.get())
        assertEquals(99L, c.coalesced.get())
        assertTrue(c.isScheduled8144())
        assertEquals(0L, c.delayMs8144(1_000_000L))         // never ran: no wait
        c.begin8144(1_000_000L)
        assertFalse(c.isScheduled8144())                    // released before the save reads the state
        assertEquals(1L, c.runs.get())
        assertEquals(1_000_000L, c.lastRunMs)
        assertEquals(7_000L, c.delayMs8144(1_003_000L))     // the next save waits out the 10 s gap
        assertEquals(0L, c.delayMs8144(1_050_000L))
        assertTrue(c.claim8144())                           // a change during the save claims the next one
        c.abort8144()                                       // a refused launch releases the claim
        assertTrue(c.claim8144())
        assertTrue(c.statusLine8144().contains("saves=1/102"))
    }

    // ── registry: census and trim reach ──

    @Test fun censusNumbersAddUpAndTrimReachesEveryRegisteredStore() {
        val r = MemoryRegistry8144()
        val a = ArrayList<Int>().apply { repeat(1_000) { add(it) } }
        val b = ArrayList<Int>().apply { repeat(300) { add(it) } }
        val calls = ArrayList<String>()
        r.register8144("a", 100L, { a.size }, { p -> calls += "a:$p"; while (a.size > 200) a.removeAt(a.size - 1) })
        r.register8144("b", 10L, { b.size }, { p -> calls += "b:$p"; b.clear() }) { 12_345L }
        r.register8144("throws", 8L, { 7 }, { _ -> calls += "throws"; throw IllegalStateException("boom") })
        r.register8144("counted", 50L, { 40 }, null)
        r.register8144("unknown", 64L, { -1 }, { _ -> calls += "unknown" })
        assertEquals(listOf("a", "b", "throws", "counted", "unknown"), r.names8144())
        assertEquals(listOf("counted"), r.untrimmed8144())

        val before = r.census8144()
        assertEquals(5, before.size)
        assertEquals(100_000L, before.first { it.name == "a" }.estBytes)       // 1,000 x 100 B
        assertEquals(12_345L, before.first { it.name == "b" }.estBytes)        // the store's own estimate wins
        assertEquals(56L, before.first { it.name == "throws" }.estBytes)
        assertEquals(2_000L, before.first { it.name == "counted" }.estBytes)
        assertEquals(0L, before.first { it.name == "unknown" }.estBytes)       // unknown count: no guess
        assertEquals(100_000L + 12_345L + 56L + 2_000L, MemoryRegistry8144.totalBytes8144(before))

        val results = r.trimAll8144(Pressure.HARD)
        assertEquals(r.names8144(), results.map { it.name })
        // Every store with a trim was called — including after one threw.
        assertEquals(listOf("a:HARD", "b:HARD", "throws", "unknown"), calls)
        assertTrue(results.first { it.name == "a" }.reached)
        assertEquals(800, results.first { it.name == "a" }.freed)
        assertEquals(300, results.first { it.name == "b" }.freed)
        assertFalse(results.first { it.name == "throws" }.reached)
        assertFalse(results.first { it.name == "counted" }.reached)
        assertEquals(1L, r.trimsRun8144())
        // Freed entries are booked as evictions and shown by the census.
        assertEquals(800L, r.evictions8144("a"))
        val after = r.census8144()
        assertEquals(20_000L, after.first { it.name == "a" }.estBytes)
        assertEquals(800L, after.first { it.name == "a" }.evictions)
        val unfreed = MemoryRegistry8144.unfreed8144(results, after)
        assertEquals(listOf("counted(unreached)", "throws(unreached)", "unknown"), unfreed)
        val line = MemoryRegistry8144.line8144(after)
        assertTrue(line, line.contains("stores=5") && line.contains("a:200/") && line.contains("ev=800"))
        assertEquals("-", MemoryRegistry8144.line8144(emptyList()))
        // Re-registering a name replaces it (no duplicate rows).
        r.register8144("a", 1L, { a.size }, null)
        assertEquals(5, r.names8144().size)
    }

    // ── the specialist miner (~10,000 keys resident in 8142) ──

    @Test fun minerCapHoldsUnderAStormAndKeepsPromotedAndProvenKeys() {
        SpecialistMiner7972.resetForTest7972()
        val cap = SpecialistMiner7972.residentCapAt8144(Pressure.NORMAL)
        assertEquals(SpecialistMiner7972.RESIDENT_KEYS_8144, cap)
        SpecialistMiner7972.promoteForTest8144("L|k5")
        SpecialistMiner7972.stormForTest8144(100, evidence = 60, prefix = "H")
        SpecialistMiner7972.stormForTest8144(45_000, evidence = 1, prefix = "L")
        assertTrue(SpecialistMiner7972.size7977() <= cap)
        assertTrue(SpecialistMiner7972.hasKeyForTest8144("L|k5"))               // promoted: never evicted
        assertTrue((0 until 100).all { SpecialistMiner7972.hasKeyForTest8144("H|k$it") })   // most evidence kept
        assertFalse(SpecialistMiner7972.hasKeyForTest8144("L|k6"))              // thin and oldest: gone
        assertTrue(SpecialistMiner7972.evicted8144() > 0L)
        // A hard trim tightens to the HARD cap (down to 90% of it), still keeping promoted and proven keys.
        SpecialistMiner7972.trim7977(true)
        assertTrue(SpecialistMiner7972.size7977() <= MemoryBounds8144.trimTarget8144(SpecialistMiner7972.residentCapAt8144(Pressure.HARD)))
        assertTrue(SpecialistMiner7972.hasKeyForTest8144("L|k5"))
        assertTrue(SpecialistMiner7972.hasKeyForTest8144("H|k99"))
        assertTrue(SpecialistMiner7972.MAX_DEVS_8144 < 20_000)
        // ~160 B a key: the NORMAL cap holds under 5 MB of a 512 MB heap.
        assertTrue(SpecialistMiner7972.RESIDENT_KEYS_8144 * SpecialistMiner7972.KEY_BYTES_8144 < 5L shl 20)
    }

    // ── structure tapes: never reached by a trim before ──

    @Test fun structureTapesAreCappedUnderPressureAndProtectedMintsKeepTheirTape() {
        val t0 = 1_900_000_000_000L
        for (i in 0 until 300) StructureTracker7962.onPrice7962("mem8144_s$i", 1.0 + i, t0 + i * 1_000L)
        assertTrue(StructureTracker7962.tapeCount8144() >= 300)
        assertTrue(StructureTracker7962.tapeBytes8144() > 0L)
        StructureTracker7962.trimUnderPressure8144(100) { it == "mem8144_s0" }
        assertEquals(100, StructureTracker7962.tapeCount8144())
        assertNotNull(StructureTracker7962.markAt7962("mem8144_s0", t0 + 30_000L))   // protected: tape kept
        assertNull(StructureTracker7962.markAt7962("mem8144_s1", t0 + 1_000L + 30_000L))  // oldest unprotected: freed
        // A quiet-tape trim frees nothing that is protected.
        StructureTracker7962.trimQuiet8144(0L, Long.MAX_VALUE / 4) { true }
        assertEquals(100, StructureTracker7962.tapeCount8144())
        StructureTracker7962.trimQuiet8144(0L, Long.MAX_VALUE / 4)
        assertEquals(0, StructureTracker7962.tapeCount8144())
    }

    // ── Cortex / hive / dispatcher sizing ──

    @Test fun deadPendingDecisionsAreBeyondTheLabelersHorizon() {
        // The labeler holds an observation 70 min at most; a Cortex pending decision past 90 min can never be graded.
        assertTrue(com.lifecyclebot.engine.cortex.Cortex7885.DEAD_PENDING_MS_8144 > 70L * 60_000L)
        val now = 10_000_000_000L
        assertTrue(com.lifecyclebot.engine.cortex.Cortex7885.persistablePending8144(now - 89L * 60_000L, now))
        assertFalse(com.lifecyclebot.engine.cortex.Cortex7885.persistablePending8144(now - 91L * 60_000L, now))
        assertTrue(com.lifecyclebot.engine.truth.HiveEdge8000.AGGREGATE_CAP_8144 * com.lifecyclebot.engine.truth.HiveEdge8000.AGGREGATE_KEY_BYTES_8144 < 16L shl 20)
        assertEquals(4_096, com.lifecyclebot.util.AppDispatchers.SIDE_EFFECT_QUEUE_CAP_8144)
        assertEquals(20_000, PipelineHealthCollector.LABEL_KEY_CAP_8144)
        // The chart library is preallocated: ~5.5 MB whatever its motif count.
        assertTrue(com.lifecyclebot.engine.chart.ChartLibrary7950.residentBytes8144() in (5L shl 20)..(6L shl 20))
    }

    @Test fun guardTrimLevelsAndRegisteredStores() {
        assertEquals(Pressure.SOFT, MemoryGuard7977.trimLevel8144(false, Pressure.CRITICAL))
        assertEquals(Pressure.HARD, MemoryGuard7977.trimLevel8144(true, Pressure.NORMAL))
        assertEquals(Pressure.HARD, MemoryGuard7977.trimLevel8144(true, Pressure.HARD))
        assertEquals(Pressure.CRITICAL, MemoryGuard7977.trimLevel8144(true, Pressure.CRITICAL))
        val stores = MemoryGuard7977.registeredStores8144()
        for (s in listOf("tokenRows", "miner", "chartLibrary", "chartTapes", "structureTapes", "cortex", "hiveBook",
                "labeler", "policyHeadStamps", "metaPolicyStamps", "sideEffectQueue", "cortexQueue")) assertTrue(s, s in stores)
        assertEquals(stores.size, stores.toSet().size)
        assertTrue(MemoryGuard7977.statusLine7977().contains("census8144="))
        assertEquals(Pressure.NORMAL, MemoryGuard7977.pressure8144())
    }

    // ── wiring (supplement) ──

    @Test fun wiring() {
        val g = src("engine/MemoryGuard7977.kt")
        assertTrue(g.contains("registry8144.trimAll8144(trimLevel8144(hard, pressure8136))"))
        assertTrue(g.contains("noteHardTrimOutcome8144(prior8144, frac, max)"))
        assertTrue(src("engine/chart/ChartReader7950.kt").contains("StructureTracker7962.trimUnderPressure8144(maxTapes, isProtected)"))
        for (f in listOf("engine/UnifiedPolicyHead.kt", "engine/AutonomousMetaPolicy.kt")) {
            val s = src(f)
            assertTrue(f, s.contains("if (!saveCoalescer8144.claim8144()) return"))
            assertFalse(f, s.contains("appContext?.let { ctx -> GlobalScope.launch(AppDispatchers.sideEffect) { save(ctx) } }"))
        }
        assertTrue(src("util/AppDispatchers.kt").contains("LinkedBlockingQueue<Runnable>(SIDE_EFFECT_QUEUE_CAP_8144)"))
        assertTrue(src("engine/cortex/Cortex7885.kt").contains("pruneDeadPending8144(nowMs)"))
        assertTrue(src("engine/truth/HiveEdge8000.kt").contains("capAggregate8144(aggregateCapNow8144())"))
    }
}
