package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.*
import com.lifecyclebot.engine.truth.LabelMark8386
import org.junit.Assert.*
import org.junit.Test

/** Actual grade-book acceptance, persistence and proof readers must agree on the decision's frozen target. */
class Aate8521CortexFrozenHorizonTest {
    private val lanes = listOf("BLUECHIP", "CASHGEN", "QUALITY", "CYCLIC", "SHITCOIN", "MOONSHOT",
        "TREASURY", "CORE", "EXPRESS", "DIP_HUNTER", "PROJECT_SNIPER", "MANIPULATED")
    private val at = 1_000_000L

    private fun decision(lane: String, kind: String = CortexForwardEpoch8521.targetKind(lane)): CortexGradeRecord8144 =
        CortexGradeRecord8144('D', "target8521-$lane|$lane|$at", "target8521-$lane|$lane", at, lane,
            "BASE_BREAKOUT", "TEST", "EARLY", "C3_6", "SMALL", CortexScoreboard7885.Bucket.STRONG.ordinal,
            8.0, false, true, false).also {
            it.scoreVersion8372 = CortexForwardEpoch8521.SCORE_VERSION
            it.outcomeKind8394 = kind
            it.outcomeDueAtMs8394 = at + CortexForwardEpoch8521.targetHorizon(kind)
        }

    private fun proof(r: CortexGradeRecord8144, horizon: Long, observedAt: Long = at + horizon,
                      net: Double = 5.0, gross: Double = 8.0): CortexOutcomeProof8395 {
        val mint = r.id.substringBefore('|')
        val entry = LabelMark8386(mint, "POOL", 1.0, at + 5_000L, at + 5_000L, "POOL_RESERVE", decimals = 6)
        val outcome = LabelMark8386(mint, "POOL", 1.0 + gross / 100.0, observedAt, observedAt,
            "POOL_RESERVE", decimals = 6)
        return CortexOutcomeProof8395(r.id, horizon, observedAt, net, gross, entry, outcome)
    }

    private fun accept(book: CortexGradeBook8144, r: CortexGradeRecord8144, p: CortexOutcomeProof8395,
                       deliveryAt: Long = p.observedAtMs): CortexOutcomeDisposition8395 =
        book.labelOutcome8395(r.id, p.netPct, p.grossPct, p.netPct, true, p.horizonMs,
            p.observedAtMs, deliveryAt, p)

    @Test fun allTwelveLanesGradeOnlyTheirFrozenTargetAndKeepLongerPathsObservational() {
        for (lane in lanes) {
            val book = CortexGradeBook8144()
            val r = decision(lane)
            val horizon = r.outcomeDueAtMs8394 - at
            assertTrue(book.addDecision(r))
            assertEquals(if (LaneGradeHorizon8406.hourGraded8406(lane)) 60L * 60_000L else 5L * 60_000L, horizon)

            if (horizon == CortexForwardEpoch8521.HOUR_MS) {
                assertEquals(CortexOutcomeDisposition8395.OBSERVED_ONLY,
                    accept(book, r, proof(r, CortexForwardEpoch8521.SCALP_MS)))
                assertTrue("$lane five-minute result is provisional", r.executablePct.isNaN())
            }
            // A mature wall clock cannot make a mark observed before its target into an executable return.
            assertEquals(CortexOutcomeDisposition8395.OBSERVED_ONLY,
                accept(book, r, proof(r, horizon, at + horizon - 1L), at + horizon + 30_000L))
            assertTrue("$lane rejects the premature source mark", r.executablePct.isNaN())
            val pending = book.scoredRows8366(lane, false, CortexForwardEpoch8521.SCORE_VERSION).single()
            assertEquals(at + horizon, pending.dueAtMs8392)
            assertTrue(pending.netPct.isNaN())
            val deliveryAt = at + horizon + 60_000L
            assertEquals(CortexOutcomeDisposition8395.ACCEPTED_FIRST,
                accept(book, r, proof(r, horizon), deliveryAt))
            assertEquals(5.0, book.objectivePct8322(r), 0.0)
            assertEquals(deliveryAt, book.objectiveAvailableAt8322(r))

            // A later four-hour path may be displayed, but cannot revise the target that calibrated this prediction.
            assertEquals(CortexOutcomeDisposition8395.OBSERVED_ONLY,
                accept(book, r, proof(r, 4L * CortexForwardEpoch8521.HOUR_MS, net = -23.0, gross = -20.0)))
            assertEquals(-23.0, r.observedNetPct, 0.0)
            assertEquals(5.0, book.objectivePct8322(r), 0.0)
            assertEquals(8.0, book.objectiveGross8324(r), 0.0)
            assertEquals(deliveryAt, book.objectiveAvailableAt8322(r))
            assertEquals(8.0, r.predictedEdgePct, 0.0)
            assertEquals(CortexScoreboard7885.Bucket.STRONG.ordinal, r.bucket)
        }
    }

    @Test fun restoreAndChangedRunnerFlagsCannotMoveTheOriginalTargetOrItsAvailability() {
        for (lane in lanes) {
            val book = CortexGradeBook8144()
            val r = decision(lane)
            book.addDecision(r)
            val horizon = r.outcomeDueAtMs8394 - at
            accept(book, r, proof(r, horizon), at + horizon + 45_000L)
            // An actual correction at this same target may update its value, preserving the original first outcome.
            assertEquals(CortexOutcomeDisposition8395.ACCEPTED_REVISION,
                accept(book, r, proof(r, horizon, at + horizon + 60_000L, 7.0, 10.0), at + horizon + 90_000L))
            val restored = CortexGradeBook8144().also { it.decode(book.encode()); it.runnerLane8322 = { false } }
            val copy = restored.decision(r.id)!!
            assertEquals(r.outcomeKind8394, copy.outcomeKind8394)
            assertEquals(at + horizon, copy.outcomeDueAtMs8394)
            assertEquals(5.0, copy.executablePct, 0.0)
            assertEquals(7.0, restored.objectivePct8322(copy), 0.0)
            assertEquals(at + horizon + 90_000L, restored.objectiveAvailableAt8322(copy))
            restored.runnerLane8322 = { true }
            assertEquals(7.0, restored.objectivePct8322(copy), 0.0)
            assertEquals(at + horizon, restored.scoredRows8366(lane, true, CortexForwardEpoch8521.SCORE_VERSION).single().dueAtMs8392)
        }
    }

    @Test fun currentGradesRequireSourceIdentityAndCannotUseLocalTimeAsAReplacement() {
        for (lane in lanes) {
            val book = CortexGradeBook8144()
            val r = decision(lane)
            val horizon = r.outcomeDueAtMs8394 - at
            book.addDecision(r)
            assertEquals(CortexOutcomeDisposition8395.OBSERVED_ONLY,
                book.labelOutcome8395(r.id, 5.0, 8.0, 5.0, true, horizon, at + horizon, at + horizon))
            val missingMarks = CortexOutcomeProof8395(r.id, horizon, at + horizon, 5.0, 8.0)
            assertEquals(CortexOutcomeDisposition8395.OBSERVED_ONLY, accept(book, r, missingMarks))
            val stamped = proof(r, horizon)
            assertEquals(CortexOutcomeDisposition8395.OBSERVED_ONLY,
                accept(book, r, stamped.copy(outcome = stamped.outcome!!.copy(mint = "different-mint"))))
            assertEquals(CortexOutcomeDisposition8395.OBSERVED_ONLY,
                accept(book, r, stamped.copy(outcome = stamped.outcome!!.copy(observedAtMs = at + horizon - 1L))))
            assertEquals("a decision-time quote is not a rebased fill", CortexOutcomeDisposition8395.OBSERVED_ONLY,
                accept(book, r, stamped.copy(entry = stamped.entry!!.copy(observedAtMs = at, readAtMs = at))))
            assertEquals("a recovered first price after the fill window stays observed-only", CortexOutcomeDisposition8395.OBSERVED_ONLY,
                accept(book, r, stamped.copy(entry = stamped.entry!!.copy(observedAtMs = at + 60_001L, readAtMs = at + 60_001L))))
            assertTrue(r.executablePct.isNaN())
            assertEquals(CortexOutcomeDisposition8395.ACCEPTED_FIRST, accept(book, r, stamped))
        }
    }

    @Test fun theHorizonTagCannotDisguiseAStalePriceAndFirstHourNeedsNoInventedFiveMinuteGrade() {
        val book = CortexGradeBook8144()
        val r = decision("PROJECT_SNIPER", CortexForwardEpoch8521.HOUR_KIND)
        book.addDecision(r)
        val due = at + CortexForwardEpoch8521.HOUR_MS
        val lastValid = CortexForwardEpoch8521.targetDeadlineMs(r.outcomeKind8394, at)
        assertFalse(CortexForwardEpoch8521.matchesTarget(r.outcomeKind8394, at, CortexForwardEpoch8521.HOUR_MS, due - 1L))
        assertTrue(CortexForwardEpoch8521.matchesTarget(r.outcomeKind8394, at, CortexForwardEpoch8521.HOUR_MS, lastValid))
        assertFalse(CortexForwardEpoch8521.matchesTarget(r.outcomeKind8394, at, CortexForwardEpoch8521.HOUR_MS, lastValid + 1L))
        assertEquals(CortexOutcomeDisposition8395.OBSERVED_ONLY,
            accept(book, r, proof(r, CortexForwardEpoch8521.HOUR_MS, at + 24L * CortexForwardEpoch8521.HOUR_MS)))
        val deliveredAt = at + 24L * CortexForwardEpoch8521.HOUR_MS + 60_000L
        assertEquals(CortexOutcomeDisposition8395.ACCEPTED_FIRST,
            accept(book, r, proof(r, CortexForwardEpoch8521.HOUR_MS, due + 5_000L), deliveredAt))
        assertEquals(0, r.revisions8272)
        assertEquals(5.0, r.executablePct, 0.0)
        assertEquals(deliveredAt, r.firstAvailableAtMs8372)
        assertEquals(CortexForwardEpoch8521.HOUR_MS, CortexOutcomeProof8395.decode8395(r.firstOutcomeProof8395)!!.horizonMs)
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun supersededPredictionsStayInExactCheckpointsAndMissingCoverageAcrossEveryLane() {
        val cortex = Cortex7885
        fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
        fun call(name: String, vararg args: Any?): Any? = cortex.javaClass.declaredMethods
            .single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }.invoke(cortex, *args)
        val pending = field(cortex, "pending").get(cortex) as MutableMap<String, Any>
        val superseded = field(cortex, "superseded8443").get(cortex) as MutableMap<String, Any>
        val book = field(cortex, "gradeBook8144").get(cortex) as CortexGradeBook8144
        val savedBook = book.encode()
        val pendingCtor = Class.forName("${cortex.javaClass.name}\$Pending").declaredConstructors
            .single { it.parameterCount == 7 }.apply { isAccessible = true }
        val contextCtor = Class.forName("${cortex.javaClass.name}\$DecisionCtx8144").declaredConstructors
            .single { it.parameterCount == 6 }.apply { isAccessible = true }
        val inserted = ArrayList<Pair<String, String>>()
        val baseline = System.currentTimeMillis() - 80L * 60_000L
        try {
            assertTrue((call("diagnosticLanes8521") as List<String>).containsAll(lanes))
            lanes.forEachIndexed { index, lane ->
                val before = baseline + index * 1_000L
                val after = before + 1L
                val key = "superseded8521-$index|$lane"
                val firstId = "$key|$before"
                fun frozen(time: Long, score: Double): Any {
                    val a = Cortex7885.Assessment(lane, false, doubleArrayOf(score), listOf("TARGET_8521"),
                        listOf(doubleArrayOf(0.0)), "TEST",
                        CortexLedger7885.Fused(score, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyList()), score,
                        if (score > 0) CortexScoreboard7885.Bucket.STRONG else CortexScoreboard7885.Bucket.REFUSE,
                        0, false, time)
                    return pendingCtor.newInstance(a, false, time, null, "", "BASE", emptyList<String>()).also {
                        field(it, "decisionReason8196").set(it, "CORTEX_SHADOW_STRONG")
                        field(it, "context8372").set(it, contextCtor.newInstance("EARLY", "C3_6", "SMALL", true, true, null))
                    }
                }
                val first = frozen(before, 8.0)
                pending[key] = frozen(after, -5.0)
                superseded[firstId] = first
                inserted.add(key to firstId)
                val encode = synchronized(cortex) { call("pendingSnapshot8076", setOf(firstId, "$key|$after")) as () -> String }
                val snapshot = org.json.JSONObject(encode())
                assertEquals("$lane checkpoint includes both original predictions", 2, snapshot.getJSONArray("p").length())
                assertEquals(0, snapshot.getJSONArray("unpaired8401").length())
                fun rows(): List<CortexAuthority8366.Row8366> = synchronized(cortex) {
                    (call("authorityRows8392", lane, null, false) as List<CortexAuthority8366.Row8366>)
                        .filter { it.atMs == before || it.atMs == after }
                }
                assertEquals("$lane pending and superseded outcomes both enter coverage", 2, rows().size)
                synchronized(cortex) { call("retainMissingOutcome8521", firstId, first); superseded.remove(firstId) }
                assertTrue(book.decision(firstId)!!.lost)
                assertEquals("$lane dropping feature memory preserves missing evidence", 2, rows().size)
                assertTrue(rows().all { it.netPct.isNaN() })
                assertEquals(before + CortexForwardEpoch8521.targetHorizon(CortexForwardEpoch8521.targetKind(lane)),
                    book.decision(firstId)!!.outcomeDueAtMs8394)
            }
        } finally {
            inserted.forEach { (key, id) -> pending.remove(key); superseded.remove(id) }
            book.decode(savedBook)
        }
    }
}
