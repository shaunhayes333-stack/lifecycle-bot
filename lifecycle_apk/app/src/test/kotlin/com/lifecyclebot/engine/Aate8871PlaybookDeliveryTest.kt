package com.lifecyclebot.engine

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.cortex.*
import com.lifecyclebot.engine.truth.LabelMark8386
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Actual capture, durable delivery, setup books and Cortex consumers, with SQLite behind Android's database adapter. */
class Aate8871PlaybookDeliveryTest {
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun call(owner: Any, name: String, vararg args: Any?): Any? = owner.javaClass.declaredMethods
        .single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }.invoke(owner, *args)

    @Suppress("UNCHECKED_CAST")
    private inner class Fixture : AutoCloseable {
        val playbook = LanePlaybook7907
        val cortex = Cortex7885
        val pool = field(playbook, "checkpointWorker8372").get(playbook) as ExecutorService
        private val cortexPool = field(cortex, "persistPool8094").get(cortex) as ExecutorService
        private val dbField = field(LearningPersistence, "db")
        private val savedDb = dbField.get(LearningPersistence)
        private val loaded = field(playbook, "loaded")
        private val savedLoaded = loaded.get(playbook)
        private val cortexLoaded = field(cortex, "loaded")
        private val savedCortexLoaded = cortexLoaded.get(cortex)
        private val savedMaps = ArrayList<Pair<MutableMap<Any, Any>, Map<Any, Any>>>()
        private val savedCounters = ArrayList<Pair<AtomicLong, Long>>()
        private val savedScalars = ArrayList<Triple<java.lang.reflect.Field, Any, Any>>()
        val episodes = field(playbook, "episodes8372").get(playbook) as LaneSetupEpisodes8372
        val model = field(cortex, "gradeBook8144").get(cortex) as CortexGradeBook8144
        private val calibration = field(cortex, "calibration").get(cortex) as CortexCalibration7901
        private val originalModel = model.encode()
        private val originalCalibration = calibration.encode().toString()
        val blockWrites = AtomicBoolean(false)
        private val sqlLock = ReentrantLock()
        private val connection = run { Class.forName("org.sqlite.JDBC"); DriverManager.getConnection("jdbc:sqlite::memory:") }

        init {
            flush()
            cortexPool.submit {}.get(10, TimeUnit.SECONDS)
            connection.createStatement().use { it.execute("CREATE TABLE kv (tracker TEXT, bucket TEXT, payload TEXT, updated INTEGER, PRIMARY KEY(tracker,bucket))") }
            val db = mock(SQLiteDatabase::class.java)
            `when`(db.inTransaction()).thenAnswer { blockWrites.get() }
            doAnswer { a -> sqlLock.withLock {
                connection.prepareStatement(a.getArgument<String>(0)).use { statement ->
                    a.getArgument<Array<Any?>>(1).forEachIndexed { i, value -> statement.setObject(i + 1, value) }
                    statement.execute()
                }
            }; null }.`when`(db).execSQL(anyString(), any())
            doAnswer { a ->
                val rows = sqlLock.withLock { connection.prepareStatement(a.getArgument<String>(0)).use { statement ->
                    a.getArgument<Array<String>?>(1)?.forEachIndexed { i, value -> statement.setString(i + 1, value) }
                    statement.executeQuery().use { result -> buildList {
                        while (result.next()) add((1..result.metaData.columnCount).map { result.getObject(it) })
                    } }
                } }
                var row = -1
                mock(Cursor::class.java).also { cursor ->
                    `when`(cursor.moveToFirst()).thenAnswer { row = 0; rows.isNotEmpty() }
                    `when`(cursor.moveToNext()).thenAnswer { row++; row < rows.size }
                    `when`(cursor.getString(anyInt())).thenAnswer { rows[row][it.getArgument<Int>(0)]?.toString() }
                    `when`(cursor.getLong(anyInt())).thenAnswer { (rows[row][it.getArgument<Int>(0)] as Number).toLong() }
                }
            }.`when`(db).rawQuery(anyString(), any())
            var committed = false
            doAnswer { sqlLock.lock(); connection.autoCommit = false; committed = false; null }.`when`(db).beginTransaction()
            doAnswer { committed = true; null }.`when`(db).setTransactionSuccessful()
            doAnswer { try {
                if (committed) connection.commit() else connection.rollback()
                connection.autoCommit = true
            } finally { sqlLock.unlock() }; null }.`when`(db).endTransaction()
            for (name in listOf("books", "pending", "pendingTiming8094", "timingBooks8094", "outcomeForward8084",
                    "graded7997", "independentWaiting8871", "independentReady8871")) saveAndClear(playbook, name)
            for (name in listOf("pending", "inFlight", "latest", "grades", "observations", "episodeClocks8871")) saveAndClear(episodes, name)
            for ((owner, name) in listOf(playbook to "lastPendingPersistMs", episodes to "clockWatermark8871", episodes to "clockPrunedAt8871")) {
                val property = field(owner, name)
                savedScalars.add(Triple(property, owner, property.get(owner))); property.set(owner, 0L)
            }
            val capacity = field(episodes, "capacity")
            savedScalars.add(Triple(capacity, episodes, capacity.get(episodes)))
            for (name in listOf("bookRevision8077", "sincePersist")) {
                val counter = field(playbook, name).get(playbook) as AtomicLong
                savedCounters.add(counter to counter.get()); counter.set(0L)
            }
            loaded.set(playbook, true); cortexLoaded.set(cortex, true); dbField.set(LearningPersistence, db)
        }

        private fun saveAndClear(owner: Any, name: String) {
            val map = field(owner, name).get(owner) as MutableMap<Any, Any>
            savedMaps.add(map to map.toMap()); map.clear()
        }

        fun flush() { pool.submit {}.get(10, TimeUnit.SECONDS) }
        fun capacity(value: Int) { field(episodes, "capacity").setInt(episodes, value) }
        fun raw(lane: String, setup: String): CortexLedger7885.Stat? = call(playbook, "statRaw8187", lane, setup) as CortexLedger7885.Stat?
        fun expected(lane: String, setup: String): Double = call(playbook, "expected", lane, setup) as Double
        fun receipt(d: ForwardOutcomeDelivery8404): String? = LearningPersistence.load("LANE_PLAYBOOK_FORWARD_RECEIPT_8871|${d.deliveryId}")
        fun modelUntouched() {
            assertEquals("setup feedback cannot invent voter grades", originalModel, model.encode())
            assertEquals("an unsampled prediction cannot calibrate the Cortex", originalCalibration, calibration.encode().toString())
        }

        fun capture(mint: String, lane: String = "EXPRESS", setup: String = "BASE_BREAKOUT",
                    at: Long = System.currentTimeMillis() - 30L * 60_000L, admitted: Boolean = false,
                    changeContext: (LanePlaybookDecision8871) -> LanePlaybookDecision8871 = { it }): ForwardOutcomeDelivery8404 {
            val token = TokenState(mint = mint, symbol = "TEST").also { it.lastPrice = 1.0; it.lastPriceUpdate = at }
            val context = call(cortex, "playbookDecision8871", token, lane, admitted, at, "LEARNED_SETUP_NO") as LanePlaybookDecision8871
            val classification = LanePlaybook7907.Classification8084(lane, LanePlaybook7907.Outcome8084.MATCHED,
                setup, listOf(setup), emptyList(), emptyList(), 8871, at, "frozen test setup")
            playbook.capture(token, lane, lane, at, classification, setup, "EARLY", changeContext(context))
            val frozen = requireNotNull(cortex.freezeForwardOutcome8404(mint, lane, at))
            val small = requireNotNull(playbook.freezeIndependent8871(mint, lane, at))
            val horizon = CortexForwardEpoch8521.targetHorizon(context.targetKind)
            // A real post-decision fill can differ from the decision quote; its own dated mark is the return basis.
            val entry = LabelMark8386(mint, "POOL", 1.25, at + 5_000L, at + 5_000L, "POOL_RESERVE", decimals = 6)
            val end = entry.copy(usd = 1.5125, observedAtMs = at + horizon, readAtMs = at + horizon)
            val proof = CortexOutcomeProof8395("$mint|$lane|$at", horizon, end.observedAtMs, 18.0, 21.0, entry, end)
            return ForwardOutcomeDelivery8404.decode(ForwardOutcomeDelivery8404(mint, lane, at, 18.0, 21.0,
                null, false, true, proof, frozen, playbookPrediction8871 = small).encode())
        }

        fun complete(d: ForwardOutcomeDelivery8404) {
            assertFalse(cortex.deliverForwardOutcome8404(d))
            flush()
            assertNotNull("own book must be durable before the Cortex acknowledges the producer", receipt(d))
            assertTrue(cortex.deliverForwardOutcome8404(d))
        }

        fun restartPlaybook() {
            flush()
            savedMaps.forEach { it.first.clear() }
            field(episodes, "clockWatermark8871").setLong(episodes, 0L)
            field(episodes, "clockPrunedAt8871").setLong(episodes, 0L)
            field(playbook, "lastPendingPersistMs").setLong(playbook, 0L)
            loaded.set(playbook, false)
        }

        override fun close() {
            flush(); cortexPool.submit {}.get(10, TimeUnit.SECONDS)
            savedMaps.forEach { (map, before) -> map.clear(); map.putAll(before) }
            savedCounters.forEach { (counter, before) -> counter.set(before) }
            savedScalars.forEach { (property, owner, before) -> property.set(owner, before) }
            loaded.set(playbook, savedLoaded); cortexLoaded.set(cortex, savedCortexLoaded)
            dbField.set(LearningPersistence, savedDb)
            connection.close()
        }
    }

    @Test fun anUnsampledCaptureRequestsTheExistingCheckpointBeforeItsFirstHorizon() = Fixture().use { f ->
        val d = f.capture("pre-horizon-8871", at = System.currentTimeMillis() - 2L * 60_000L)
        f.flush()
        val saved = requireNotNull(LearningPersistence.load("LANE_PLAYBOOK_EPISODES_8521"))
        assertEquals(d.id, JSONObject(saved).getJSONObject("pending").getJSONArray("rows").getJSONObject(0).getString("id"))
        assertNull(f.receipt(d)); assertEquals(0, f.episodes.gradeCount())
        f.restartPlaybook()
        assertEquals("restart restores the actual setup and its eligibility before any outcome exists",
            d.playbookPrediction8871, f.playbook.freezeIndependent8871(d.mint, d.lane, d.atMs))
        assertEquals(0, f.episodes.gradeCount()); f.modelUntouched()
    }

    @Test fun aQualifiedUnsampledOutcomeChangesTheExistingSetupReadWithoutInventingCortexAuthority() = Fixture().use { f ->
        val before = f.expected("EXPRESS", "BASE_BREAKOUT")
        val d = f.capture("base|playbook-8871")
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        f.pool.execute { started.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        try {
            assertFalse("the caller only schedules while the checkpoint worker is occupied", f.cortex.deliverForwardOutcome8404(d))
            assertNull(f.receipt(d))
            assertNull(f.raw("EXPRESS", "BASE_BREAKOUT"))
        } finally { release.countDown() }
        f.flush()
        assertTrue(f.cortex.deliverForwardOutcome8404(d))
        assertEquals(1.0, f.raw("EXPRESS", "BASE_BREAKOUT")!!.n, 0.0)
        assertEquals(18.0, f.raw("EXPRESS", "BASE_BREAKOUT")!!.rawMean8187(), 0.0)
        assertTrue("the next existing shrunk strategy expectation changes", f.expected("EXPRESS", "BASE_BREAKOUT") > before)
        assertEquals("selection caches are invalidated by the actual book mutation", 1L, f.playbook.selectionRevision8198())
        assertNull(f.model.decision(d.id)); f.modelUntouched()
        assertTrue(f.cortex.deliverForwardOutcome8404(d))
        assertEquals(1.0, f.raw("EXPRESS", "BASE_BREAKOUT")!!.n, 0.0)

        f.restartPlaybook()
        assertTrue(f.cortex.deliverForwardOutcome8404(ForwardOutcomeDelivery8404.decode(d.encode())))
        assertEquals(1.0, f.raw("EXPRESS", "BASE_BREAKOUT")!!.n, 0.0)
        assertEquals(d.playbookPrediction8871, f.playbook.freezeIndependent8871(d.mint, d.lane, d.atMs))
        f.modelUntouched()
    }

    @Test fun aFailedCheckpointCannotAcknowledgeAndRestartLearnsTheOriginalLossExactlyOnce() = Fixture().use { f ->
        val original = f.capture("failed-commit-8871")
        val proof = original.proof!!.copy(netPct = -4.0, grossPct = -1.0,
            outcome = original.proof!!.outcome!!.copy(usd = original.proof!!.entry!!.usd * 0.99))
        val d = original.copy(net = -4.0, gross = -1.0, proof = proof)
        f.blockWrites.set(true)
        assertFalse(f.cortex.deliverForwardOutcome8404(d)); f.flush()
        assertEquals(1.0, f.raw(d.lane, "BASE_BREAKOUT")!!.n, 0.0)
        assertNull(f.receipt(d))
        assertNull(LearningPersistence.load("CORTEX_FORWARD_RECEIPT_8404|${d.deliveryId}"))
        f.restartPlaybook() // Lose the uncommitted in-memory contribution; producer still owns the immutable episode.
        f.blockWrites.set(false)
        f.complete(d)
        assertEquals(1.0, f.raw(d.lane, "BASE_BREAKOUT")!!.n, 0.0)
        assertEquals(-4.0, f.raw(d.lane, "BASE_BREAKOUT")!!.rawMean8187(), 0.0)
        assertTrue(f.cortex.deliverForwardOutcome8404(d))
        assertEquals(1.0, f.raw(d.lane, "BASE_BREAKOUT")!!.n, 0.0)
        f.modelUntouched()
    }

    @Test fun anAppliedDelayedAdmissionGetsItsReceiptEvenWhenItsDetailIsImmediatelyEvicted() = Fixture().use { f ->
        f.capacity(1)
        val at = System.currentTimeMillis() - 30L * 60_000L
        f.complete(f.capture("recent-a-8871", at = at))
        f.complete(f.capture("recent-b-8871", at = at + 1_000L))
        val delayed = f.capture("delayed-admit-8871", at = at - 60L * 60_000L, admitted = true)
        f.blockWrites.set(true)
        assertFalse(f.cortex.deliverForwardOutcome8404(delayed)); f.flush()
        assertNull("the detail limit remains in force", f.episodes.grade(delayed.mint, delayed.lane, delayed.atMs))
        assertEquals(3.0, f.raw("EXPRESS", "BASE_BREAKOUT")!!.n, 0.0)
        assertNull(f.receipt(delayed))
        assertFalse(f.cortex.deliverForwardOutcome8404(delayed)); f.flush()
        assertEquals("failed persistence cannot reapply the admitted exception", 3.0, f.raw("EXPRESS", "BASE_BREAKOUT")!!.n, 0.0)
        f.blockWrites.set(false)
        f.complete(delayed)
        assertEquals(3.0, f.raw("EXPRESS", "BASE_BREAKOUT")!!.n, 0.0)
        f.restartPlaybook()
        assertTrue(f.cortex.deliverForwardOutcome8404(delayed))
        assertEquals(3.0, f.raw("EXPRESS", "BASE_BREAKOUT")!!.n, 0.0)
        f.modelUntouched()
    }

    @Test fun olderOwnedOutcomesLearnOnceWithoutDisplacingFullNewerPendingRows() {
        for (admitted in listOf(false, true)) Fixture().use { f ->
            f.capacity(2)
            val at = System.currentTimeMillis() - 75L * 60_000L
            val older = f.capture("owned-full-8871-$admitted", at = at, admitted = admitted)
            f.capture(older.mint, setup = "NEWER_SETUP", at = at + 45L * 60_000L)
            f.capture("other-full-8871-$admitted", setup = "OTHER_SETUP", at = at + 46L * 60_000L)
            f.flush()
            assertNull("the producer still owns the original after ordinary pending eviction",
                f.episodes.pending(older.mint, older.lane, older.atMs))
            val before = f.episodes.pendingSnapshot().toString()
            val evictedBefore = f.episodes.evicted
            f.blockWrites.set(true)
            assertFalse(f.cortex.deliverForwardOutcome8404(older)); f.flush()
            assertEquals(1.0, f.raw("EXPRESS", "BASE_BREAKOUT")!!.n, 0.0)
            assertEquals(18.0, f.raw("EXPRESS", "BASE_BREAKOUT")!!.rawMean8187(), 0.0)
            assertNull(f.receipt(older))
            assertFalse(f.episodes.inFlight(older.mint, older.lane, older.atMs))
            assertEquals(before, f.episodes.pendingSnapshot().toString())
            assertFalse(f.cortex.deliverForwardOutcome8404(older)); f.flush()
            assertEquals("retry cannot reapply either an admitted or refused original", 1.0,
                f.raw("EXPRESS", "BASE_BREAKOUT")!!.n, 0.0)
            f.blockWrites.set(false)
            f.complete(older)
            assertEquals("LEARNED_SETUP_ONLY", JSONObject(f.receipt(older)!!).getString("disposition"))
            assertEquals(before, f.episodes.pendingSnapshot().toString())
            assertEquals(evictedBefore, f.episodes.evicted)
            assertNull(f.raw("EXPRESS", "NEWER_SETUP")); assertNull(f.raw("EXPRESS", "OTHER_SETUP"))
            f.restartPlaybook()
            assertTrue(f.cortex.deliverForwardOutcome8404(older))
            assertEquals(1.0, f.raw("EXPRESS", "BASE_BREAKOUT")!!.n, 0.0)
            assertEquals(before, f.episodes.pendingSnapshot().toString())
            f.modelUntouched()
        }
    }

    @Test fun conflictingOwnedCaptureIsRejectedWithoutConsumingTheHeldOriginal() = Fixture().use { f ->
        val original = f.capture("owned-conflict-8871")
        val before = f.episodes.pendingSnapshot().toString()
        val conflict = original.copy(playbookPrediction8871 = JSONObject(original.playbookPrediction8871!!)
            .put("tag", "WRONG_SETUP#O=MATCHED#V=8871").toString())
        f.complete(conflict)
        assertEquals("ORIGINAL_CAPTURE_CONFLICT", JSONObject(f.receipt(conflict)!!).getString("disposition"))
        assertEquals(before, f.episodes.pendingSnapshot().toString())
        assertNull(f.raw("EXPRESS", "BASE_BREAKOUT")); assertNull(f.raw("EXPRESS", "WRONG_SETUP"))
        f.modelUntouched()
    }

    @Test fun anExistingInFlightLeaseKeepsItsOwnershipUntilItsCallbackFinishes() = Fixture().use { f ->
        val d = f.capture("owned-inflight-8871")
        val held = requireNotNull(f.episodes.take(d.mint, d.lane, d.atMs))
        val before = f.episodes.pendingSnapshot().toString()
        assertFalse(f.cortex.deliverForwardOutcome8404(d)); f.flush()
        assertNull(f.receipt(d)); assertNull(f.raw("EXPRESS", "BASE_BREAKOUT"))
        assertTrue(f.episodes.inFlight(d.mint, d.lane, d.atMs))
        assertEquals(before, f.episodes.pendingSnapshot().toString())
        assertEquals(held, f.episodes.decodeIndependent8871(d.playbookPrediction8871!!,
            d.mint, d.lane, d.atMs, System.currentTimeMillis()))
        f.modelUntouched()
    }

    @Test fun evictedOwnedHygieneExclusionsCannotBeRevivedByALaterDelivery() = Fixture().use { f ->
        f.capacity(1)
        val at = System.currentTimeMillis() - 60L * 60_000L
        val original = f.capture("owned-excluded-8871", at = at)
        f.capture("owned-newer-8871", setup = "NEWER_SETUP", at = at + 30L * 60_000L)
        assertNull(f.episodes.pending(original.mint, original.lane, original.atMs))
        val before = f.episodes.pendingSnapshot().toString()
        f.complete(original.copy(flat = true))
        assertEquals("HYGIENE_EXCLUDED_8372", f.episodes.disposition(original.mint, original.lane, original.atMs))
        f.complete(original.copy(revision = true))
        assertNull(f.raw("EXPRESS", "BASE_BREAKOUT"))
        assertEquals(before, f.episodes.pendingSnapshot().toString())
        f.modelUntouched()
    }

    @Test fun malformedUnfilledLateAndWrongEpisodeEvidenceCannotGradeASetup() = Fixture().use { f ->
        val changes = listOf<(ForwardOutcomeDelivery8404) -> ForwardOutcomeDelivery8404>(
            { it.copy(proof = it.proof!!.copy(entry = null)) },
            { it.copy(proof = it.proof!!.copy(entry = it.proof!!.entry!!.copy(observedAtMs = it.atMs + 60_001L, readAtMs = it.atMs + 60_001L))) },
            { it.copy(proof = it.proof!!.copy(outcome = it.proof!!.outcome!!.copy(mint = "different-asset"))) },
            { it.copy(flat = true) },
            { it.copy(playbookPrediction8871 = "malformed") },
            { it.copy(playbookPrediction8871 = JSONObject(it.playbookPrediction8871!!).put("id", "different|EXPRESS|${it.atMs}").toString()) },
            { it.copy(proof = it.proof!!.copy(netPct = it.net + 1.0)) },
        )
        changes.forEachIndexed { i, change ->
            val d = f.capture("invalid-8871-$i", setup = "INVALID_$i")
            f.complete(change(d))
            assertNull("invalid case $i created no setup outcome", f.raw(d.lane, "INVALID_$i"))
            if (i == 5) assertTrue("a mismatched payload cannot consume the real original setup",
                f.episodes.pending(d.mint, d.lane, d.atMs)!!.tag.startsWith("INVALID_$i"))
        }
        val contexts = listOf<(LanePlaybookDecision8871) -> LanePlaybookDecision8871>(
            { it.copy(executable = false) }, { it.copy(staleMark = true) }, { it.copy(priceUsd = 0.0) },
            { it.copy(priceUsd = Double.NaN) }, { it.copy(priceObservedAtMs = it.priceObservedAtMs + 1L, staleMark = false) },
            { it.copy(priceObservedAtMs = it.priceObservedAtMs - CortexLabelHygiene8144.DECISION_MARK_STALE_MS_8871 - 1L, staleMark = false) },
        )
        contexts.forEachIndexed { i, change ->
            val d = f.capture("bad-context-8871-$i", setup = "CONTEXT_$i", changeContext = change)
            f.complete(d)
            assertNull("context $i created no setup outcome", f.raw(d.lane, "CONTEXT_$i"))
        }
        assertEquals(0, f.episodes.gradeCount()); f.modelUntouched()
    }

    @Test fun runnerTargetsAndTheSixHourRefusalEpisodeRemainFrozenAcrossBothDeliveryRoutes() = Fixture().use { f ->
        val at = System.currentTimeMillis() - 60L * 60_000L
        val target = f.capture("runner-8871", "QUALITY", at = at)
        val fiveAt = at + CortexForwardEpoch8521.SCALP_MS
        val five = target.copy(net = -3.0, gross = 0.0, flat = true, proof = target.proof!!.copy(
            horizonMs = CortexForwardEpoch8521.SCALP_MS, netPct = -3.0, grossPct = 0.0, observedAtMs = fiveAt,
            outcome = target.proof!!.outcome!!.copy(usd = target.proof!!.entry!!.usd, observedAtMs = fiveAt, readAtMs = fiveAt)))
        f.complete(five)
        assertNull(f.raw("QUALITY", "BASE_BREAKOUT"))
        assertNotNull(f.episodes.pending(target.mint, target.lane, at))
        val hour = target.copy(revision = true)
        f.complete(hour)
        assertEquals(1.0, f.raw("QUALITY", "BASE_BREAKOUT")!!.n, 0.0)

        val repeated = f.capture(target.mint, target.lane, at = at + 20L * 60_000L)
        f.complete(repeated)
        assertEquals("a refused same-token episode cannot become a second setup sample", 1.0, f.raw("QUALITY", "BASE_BREAKOUT")!!.n, 0.0)
        val normal = f.capture(target.mint, target.lane, at = at + 25L * 60_000L)
        f.playbook.onLabel(normal.mint, normal.lane, 99.0, 102.0, decisionAtMs8372 = normal.atMs, admitted8871 = false)
        assertEquals("the sampled route shares the same setup repeat protection", 1.0, f.raw("QUALITY", "BASE_BREAKOUT")!!.n, 0.0)
        val admitted = f.capture(target.mint, target.lane, at = at + 30L * 60_000L, admitted = true)
        f.complete(admitted)
        assertEquals("actual admissions retain their existing exception", 2.0, f.raw("QUALITY", "BASE_BREAKOUT")!!.n, 0.0)
        f.modelUntouched()
    }
}
