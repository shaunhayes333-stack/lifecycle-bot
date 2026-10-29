package com.lifecyclebot.engine.cortex

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import com.lifecyclebot.engine.truth.FieldManual7715

/**
 * V5.0.8406 §GRADE_THE_STRATEGY_AT_ITS_OWN_HORIZON.
 *
 * 5.0.8404 (547 s): Cortex graded 97 of 5,579 decisions, every lane on the 5-minute read ("THE read" in
 * ForwardReturnLabeler7731) net of a 6.7% round trip; information coefficient CORE -0.22, SHITCOIN -0.37, EXPRESS -0.35;
 * setups that MATCHED did worse than no setup (SHITCOIN -32.5% vs -12.6%). RETIRED_8383 had retired the accumulation
 * family (QUIET_ACCUMULATION, PRE_IGNITION_BASE, LAUNCH_CONTINUATION, BUYERS_DOMINATING, LOW_RUNUP_BASE ...) on the same
 * 5-minute evidence and kept the chase family (IGNITION_5M, NEW_HIGH_PUSH, BREAKOUT_RUNNER). A +50% runner mandate or a
 * +20% trend-pullback mandate does not resolve in 5 minutes; a chase entry sometimes pops in 5 minutes. The yardstick
 * retired the patient strategies and kept the losing ones.
 *
 * The rule, from the lane's own FieldManual7715 mandate: a lane whose expected gross move is at least
 * [HOUR_GRADED_MANDATE_PCT_8406] is graded on the hour slot (the 8337 hour revision owns its Cortex grade, setup books,
 * cells and miner; the 5-minute read stays the provisional/observed value), and a retirement measured on the 5-minute
 * read is not binding evidence against its accumulation/early setups. Range-reversion, yield and derivatives mandates
 * (TREASURY 12%, CRYPTO 12%, PERPS, CARRY) keep the 5-minute read. Hard safety, exits and sizing untouched.
 */
object LaneGradeHorizon8406 {
    private const val HOUR_GRADED_MANDATE_PCT_8406 = 20.0

    /** Setups whose 5-minute retirement is suspended on an hour-graded lane: patience cannot be measured in 5 minutes. */
    private val PATIENT_FAMILY_8406: Set<String> = setOf(
        "QUIET_ACCUMULATION", "BUYERS_DOMINATING", "PRE_IGNITION_BASE", "LAUNCH_CONTINUATION", "LOW_RUNUP_BASE",
        "VERIFIED_LAUNCH", "HIGHER_LOW_PULLBACK", "HIGHER_LOW_CONTINUATION", "FLAG_CONTINUATION", "MICRO_FLAG",
        "LIVESTREAM_LAUNCH", "GRADUATION_APPROACH",
    )

    /** Pure: the mandate's expected gross move decides the grading horizon. */
    fun hourGradedByMandate8406(expectedGrossPct: Double): Boolean =
        expectedGrossPct.isFinite() && expectedGrossPct >= HOUR_GRADED_MANDATE_PCT_8406

    fun hourGraded8406(lane: String?): Boolean {
        val l = lane?.trim()?.uppercase().orEmpty()
        if (l.isBlank()) return false
        val m = try { FieldManual7715.mandateFor(l) } catch (_: Throwable) { return false }
        return hourGradedByMandate8406(m.expectedGrossPct)
    }

    /** Pure (V5.0.8418): the lane's hour book is deep enough for the hour clock to overrule a 5-minute retirement. */
    fun suspensionEarned8418(hourGradedN: Long): Boolean = hourGradedN >= HOUR_BOOK_MIN_N_8408

    /** A 5-minute retirement of a patient setup on an hour-graded lane is suspended (counted per lane). */
    fun retirementSuspended8406(lane: String, setup: String): Boolean {
        if (setup !in PATIENT_FAMILY_8406 || !hourGraded8406(lane)) return false
        // V5.0.8418 — superseded: the suspension lifted the 8383 retirement on every hour-graded lane with NO hour evidence
        // (review 8418). It now waits for the lane's hour book (HOUR_BOOK_MIN_N_8408 hour-graded labels); until then the
        // 5-minute retirement stands and the would-be suspension is counted.
        // V5.0.8420 — restored 8406: a 5-minute book has no standing to retire a patient (accumulation) setup on an hour-graded
        // lane; keeping that retirement until the hour book fills retired exactly the early-accumulation entries (the goal of
        // this repair) and kept the chase family. Retirement on the lane's own hour evidence is unaffected.
        if (!suspensionEarned8418(hourGradedN8408(canon(lane))))
            try { PipelineHealthCollector.labelInc("RETIREMENT_SUSPENDED_NO_HOUR_BOOK_8420_${lane.trim().uppercase()}") } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("RETIREMENT_SUSPENDED_WRONG_HORIZON_8406_${lane.trim().uppercase()}") } catch (_: Throwable) {}
        return true
    }

    // ── V5.0.8408 §THE_WRONG_CLOCK_CANNOT_REFUSE ─────────────────────────────────────────────────────────────────────────────
    // 8406 moved the GRADE of hour-mandate lanes to the hour read, but the books those lanes carry were filled on the 5-minute
    // read (QUALITY LANE_PROVEN_LOSING_7938 -13.7% x274; PLAYBOOK_SETUP_EXPECTED_NEGATIVE_7948 QUALITY 67, TREASURY 6). Until a
    // lane has banked [HOUR_BOOK_MIN_N_8408] hour-graded labels since the clock changed, its 5-minute evidence is observational:
    // it may still ALLOW (a positive 5-minute record is good news on any clock) but it may not refuse. Nothing is reset: the
    // books keep accumulating and the hour revisions keep landing in them; the refusal authority simply waits for the right
    // clock. 5-minute lanes (TREASURY, CASHGEN, CRYPTO) are untouched. The count persists (LearningPersistence) across restarts.
    private const val HOUR_BOOK_MIN_N_8408 = 30
    private fun canon(lane: String?): String = lane?.trim()?.uppercase().orEmpty()
    private const val HOUR_BOOK_KEY_8408 = "lane_grade_horizon_hour_book_8408"
    private val hourBook8408 = ConcurrentHashMap<String, AtomicLong>()
    private val wrongClockSpared8408 = ConcurrentHashMap<String, AtomicLong>()
    @Volatile private var hourBookLoaded8408 = false
    @Volatile private var lastPersist8408 = 0L

    private fun ensureHourBook8408() {
        if (hourBookLoaded8408) return
        synchronized(hourBook8408) {
            if (hourBookLoaded8408) return
            try {
                val raw = com.lifecyclebot.engine.LearningPersistence.load(HOUR_BOOK_KEY_8408)
                if (!raw.isNullOrBlank()) {
                    val o = org.json.JSONObject(raw)
                    for (k in o.keys()) hourBook8408[k] = AtomicLong(o.optLong(k, 0L))
                }
            } catch (_: Throwable) {}
            hourBookLoaded8408 = true
        }
    }

    /** An hour-graded label landed for [lane] (the 8337 hour revision or a direct 240 book). */
    fun noteHourGrade8408(lane: String) {
        val l = canon(lane); if (l.isBlank()) return
        ensureHourBook8408()
        hourBook8408.computeIfAbsent(l) { AtomicLong() }.incrementAndGet()
        val now = System.currentTimeMillis()
        if (now - lastPersist8408 > 30_000L) {
            lastPersist8408 = now
            try {
                val o = org.json.JSONObject(); for ((k, v) in hourBook8408) o.put(k, v.get())
                com.lifecyclebot.engine.LearningPersistence.save(HOUR_BOOK_KEY_8408, o.toString())
            } catch (_: Throwable) {}
        }
    }

    private fun hourGradedN8408(lane: String): Long { ensureHourBook8408(); return hourBook8408[canon(lane)]?.get() ?: 0L }

    /** Pure: may 5-minute evidence refuse this lane? Never for an hour-graded lane short of its hour book. */
    fun fiveMinuteMayRefuse8408(hourGraded: Boolean, hourGradedN: Long): Boolean = !hourGraded || hourGradedN >= HOUR_BOOK_MIN_N_8408

    /** The lane's 5-minute refusal is spared (counted by [gate]); true when the refusal must NOT bind. */
    fun wrongClockSpares8408(lane: String, gate: String): Boolean {
        val l = canon(lane)
        if (fiveMinuteMayRefuse8408(hourGraded8406(l), hourGradedN8408(l))) return false
        wrongClockSpared8408.computeIfAbsent("$gate|$l") { AtomicLong() }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("WRONG_CLOCK_REFUSAL_SPARED_8408_${gate}_$l") } catch (_: Throwable) {}
        return true
    }

    /**
     * V5.0.8418 — a refusal site's verdict on the clock: the 5-minute refusal KEEPS binding (always true). superseded by
     * V5.0.8418 — 8408 spared PLAYBOOK_7907 / LANE_7938 refusals on every hour-graded lane until its hour book had 30 labels
     * (the book starts at 0), while a positive 5-minute record still allowed: one-sided, and QUALITY read -13.7% over 274
     * labels. Where 8408 would have spared, it is counted (WRONG_CLOCK_REFUSAL_KEPT_8418_<gate>_<lane>); once the hour book
     * exists nothing changes. The raise-only move read (wrongClockSpares8408) is unaffected.
     */
    // V5.0.8420 — restored to 8408: on an hour-graded lane without its hour book, a 5-minute-graded refusal does not bind
    // (the operator's "the wrong clock cannot refuse"). Hard safety, cost economics and Cortex hour grades still refuse.
    fun refusalKept8418(lane: String, gate: String): Boolean {
        val l = canon(lane)
        val may = fiveMinuteMayRefuse8408(hourGraded8406(l), hourGradedN8408(l))
        if (!may) try { PipelineHealthCollector.labelInc("WRONG_CLOCK_REFUSAL_SPARED_8420_${gate}_$l") } catch (_: Throwable) {}
        return may
    }

    fun hourBookLine8408(): String {
        ensureHourBook8408()
        val book = hourBook8408.entries.sortedByDescending { it.value.get() }.take(12).joinToString(",") { "${it.key}=${it.value.get()}" }
        val spared = wrongClockSpared8408.entries.sortedByDescending { it.value.get() }.take(8).joinToString(",") { "${it.key}=${it.value.get()}" }
        return "hourBook8408[min=$HOUR_BOOK_MIN_N_8408 n{$book} spared{$spared}]"
    }
}
