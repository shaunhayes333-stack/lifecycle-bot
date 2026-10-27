package com.lifecyclebot.engine.cortex

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.LearningPersistence
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.SpecialistBrainBridge7542
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7885 — AATE Cortex runtime (plan v1 §2, v2 §E builds 4-10).
 *
 *   snapshot   one frozen read per (mint, lane) decision: every voter's raw
 *              opinion + feature provenance (OBSERVED / UNKNOWN / STALE)
 *   ledger     every voter graded prequentially on forward labels (net of
 *              cost, admitted AND refused candidates) — authority is earned
 *              per lane and decays when skill does
 *   fusion     authority x independence pooling around the lane prior
 *   rules      a Constitution that can only refuse, every refusal named
 *   authority  the Cortex refuses in PAPER once its REFUSE record is proven
 *              (bar V1, n>=20) and in LIVE at n>=40; it overrules a live
 *              edge-gate refusal only when its STRONG record is proven.
 *              Until then it shadows: what it would have done is counted.
 *              V5.0.7955: STRONG-side authority is graduated (0..1 from 15
 *              grades); overrule from 0.5, size-up scaled by the fraction.
 *
 * Horizon: every lane is graded on THE read — label slot 60, the 5-minute
 * spike-credited label (V5.0.7946). V5.0.7948: a decision is filed under the
 * bucket assigned by the ledger of the same label epoch (see restorePending).
 *
 * Nothing here sizes a trade. Every entry point fails open (null / false).
 */
object Cortex7885 {
    // V5.0.8318 — 20 s made most decision-time captures cold (5.0.8314: capture meanLagMs 111,764; each queued capture re-ran
    // a full assessment, damaging labels, not entry timing). A 60 s assessment still carries its own stale mark
    // (priceAge > MARK_STALE_MS) and the cache stays bounded by MAX_ASSESS_CACHE / ASSESS_PRUNE_AT_8144.
    private const val ASSESS_TTL_MS = 60_000L
    private const val PENDING_TTL_MS = 5L * 60L * 60_000L
    private const val MAX_PENDING = 8_000
    private const val MAX_ASSESS_CACHE = 4_000
    /**
     * V5.0.7947 — the forward-label ledgers moved to a new key. They were graded on
     * 60/240-minute, spike-blind labels; since 7945/7946 a label is the 5-minute read
     * with the spike tiers credited, so the old grades measured a different thing and
     * kept refusing on it (5.0.7944: C3 on CASHGEN 1,006). The realised-trade ledgers
     * ("real") are fills and exits, still true, and carry over. The old key is left in
     * place, untouched.
     */
    // V5.0.8064 — the voters were graded on spike-credited labels; they restart on the honest label (the realised-trade
    // ledgers carry over from the 7947 key, as they did from the original).
    private const val PERSIST_KEY = "CORTEX_7885_V8076"
    private const val LEGACY_PERSIST_KEY_7947 = "CORTEX_7885_V8064"
    private const val PERSIST_EVERY = 40
    private const val MARK_STALE_MS = 180_000L

    class Assessment(
        val lane: String,
        val runnerLane: Boolean,
        val raws: DoubleArray,
        val ids: List<String>,
        val edges: List<DoubleArray>,
        val regime: String,
        val fused: CortexLedger7885.Fused,
        val calibratedEdge: Double,
        val bucket: CortexScoreboard7885.Bucket,
        val unknownFeatures: Int,
        val staleMark: Boolean,
        val atMs: Long,
        val prediction8076: CortexLedger7885.PredictionSnapshot8076? = null,
        /** Runner-tail diagnostic. Since 8372 it cannot substitute for this token's displayed forecast/verdict. */
        val tailEv8324: Double = Double.NaN,
        /** Frozen score semantics; legacy snapshots retain version 0 rather than being relabelled after the outcome. */
        val scoreVersion8372: Int = 1,
        /** The slope-free predictor actually paired with this outcome; never recomputed from a changed lane prior. */
        val calibrationInput8372: Double = Double.NaN,
    )

    private fun predictionForGrade8076(a: Assessment): CortexLedger7885.PredictionSnapshot8076 =
        a.prediction8076 ?: CortexLedger7885.PredictionSnapshot8076(
            DoubleArray(a.ids.size) { Double.NaN }, DoubleArray(a.ids.size) { Double.NaN }, Double.NaN, Double.NaN,
        )

    private class Pending(val a: Assessment, val legacyAdmitted: Boolean, val atMs: Long, val vetoRule: String? = null, val source: String = "", val setup7961: String = "",
                          /** V5.0.8094 — every OTHER setup the coin fired in this lane (session-only): each is graded in its own cell. */
                          val fired8094: List<String> = emptyList()) {
        /** V5.0.8153 — the strategy the coin trades as at the decision (selectedSetup8094, taken with the snapshot). */
        @Volatile var selected8153: String = ""
        /** V5.0.8169 — the voters were read after this decision's forward label started (CortexAudit8169.voterReadLeaks8169). */
        @Volatile var lateRead8169: Boolean = false
        /** 8196: capture-time account mode and complete refusal are immutable provenance. */
        @Volatile var decisionMode8196: String = "UNKNOWN"
        @Volatile var decisionReason8196: String? = null
        /** Owned by this exact observation, never borrowed from a newer mint|lane capture. */
        @Volatile var context8372: DecisionCtx8144? = null
        var outcomeKind8394: String = "FORWARD"
        var outcomeDueAtMs8394: Long = atMs + 5 * 60_000L
    }

    private val ledger = CortexLedger7885().also { it.runnerLane8324 = { l -> isRunner(l) } }  // V5.0.8324 — anti-runner seats demoted
    private val board = CortexScoreboard7885()
    private val calibration = CortexCalibration7901()
    private val realLedgers = HashMap<String, CortexLedger7885>()   // mode -> outcome-truth ledger
    /** V5.0.8309 — census: the voter seat ledger plus the per-mode outcome-truth ledgers (learning: counted, never trimmed). */
    fun ledgerBytes8309(): Long = ledger.estBytes8309().coerceAtLeast(0L) +
        (try { realLedgers.values.toList() } catch (_: Throwable) { emptyList() }).sumOf { it.estBytes8309().coerceAtLeast(0L) }
    private val assessCache = ConcurrentHashMap<String, Assessment>()
    private val pending = ConcurrentHashMap<String, Pending>()
    private val shadowPending8372 = ConcurrentHashMap<String, Pending>()
    private val shadowCtx8372 = ConcurrentHashMap<String, DecisionCtx8144>()
    private val outcomeBook8372 = CortexOutcomeBook8372()
    private val gradingForward8372 = ConcurrentHashMap.newKeySet<String>()
    private val shadowReady8372 = ConcurrentHashMap<String, CortexOutcomeBook8372.Terminal8372>()
    private val shadowFlushRunning8372 = java.util.concurrent.atomic.AtomicBoolean(false)
    private val durableTraining8372 = ConcurrentHashMap<String, String>()
    private const val TRAINING_RECEIPT_8372 = "CORTEX_TRAINING_8372|"
    private val voterFailures = ConcurrentHashMap<String, AtomicLong>()
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val assessed = AtomicLong(0)
    private val graded = AtomicLong(0)
    private val sincePersist = AtomicLong(0)
    private val assessNanos = AtomicLong(0)
    @Volatile private var loaded = false

    private fun inc(k: String) { counters.computeIfAbsent(k) { AtomicLong(0) }.incrementAndGet() }

    private fun canon(lane: String): String {
        val c = try { com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.canonical(lane).uppercase() } catch (_: Throwable) { "" }
        return if (c.isBlank()) lane.trim().uppercase() else c
    }

    private fun accountMode8077(): String = if (com.lifecyclebot.engine.RuntimeModeAuthority.isPaper()) "PAPER" else "LIVE"

    private fun credits7888(): Double = try { com.lifecyclebot.engine.truth.HeliusCreditEconomy7881.creditsToday7888() } catch (_: Throwable) { 0.0 }

    // V5.0.8187 — the robust read, with the raw stored mean beside it where the cap rules the raw mean out.
    private fun fmtStat(s: CortexLedger7885.Stat): String = CortexLedger7885.fmt8187(s)
    private fun fmtRow8153(n: Int, mean: Double): String = if (n < 1 || !mean.isFinite()) "-" else "n$n/${"%+.1f".format(mean)}%"

    private fun isRunner(lane: String): Boolean =
        try { com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(lane) } catch (_: Throwable) { false }

    /** Snapshot + votes + fusion for (ts, lane); cached briefly so the gate and the labeler see one read. */
    fun assess(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): Assessment? {
        if (ts.mint.isBlank() || laneRaw.isBlank()) return null
        val lane = canon(laneRaw)
        val key = "${ts.mint}|$lane"
        assessCache[key]?.let { if (nowMs - it.atMs in 0L..ASSESS_TTL_MS) return it }
        ensureLoaded()
        val t0 = System.nanoTime()
        val staticRaws = CortexVoters7885.readAll(ts, lane, nowMs, voterFailures, coreVoters8291(lane, nowMs))
        val runner = isRunner(lane)
        val voters: List<CortexVoters7885.Voter> = CortexVoters7885.ALL
        // V5.0.7895 — dynamic voters: every V3 UnifiedScorer module recorded for this mint (read outside the Cortex lock).
        val dynamic8325 = try { CortexVoters7885.dynamicVotes(ts) } catch (_: Throwable) { emptyList() }
        val baseVotes8325 = voters.mapIndexed { i, v -> CortexLedger7885.Vote(v.id, v.edges, staticRaws[i], v.evidence) } + dynamic8325
        // V5.0.7898 — Cortex v4: the decision is read in the current market regime.
        val regime = try { com.lifecyclebot.engine.RegimeDetector.currentRegime().name } catch (_: Throwable) { "" }
        // V5.0.8325 §LOCK_ONLY_FOR_SMALL_STATE — ONE short critical section per assessment: the cross votes, the fusion over the
        // ledger's seats and the calibration slope. Every voter read, pond, ranking, sheet and persistence read is outside it.
        val wait8324 = System.nanoTime()
        val locked8325 = synchronized(this) {
            noteAssessWait8324(System.nanoTime() - wait8324)
            val cross = try { crossVotes(lane, staticRaws) } catch (_: Throwable) { emptyList() }
            val fp = ledger.fuseWithSnapshot8324(lane, baseVotes8325 + cross, regime)  // Freeze the raw prequential prediction before the optional cost-basis projection.
            val offset8404 = executableOffset8187(lane)
            val fit8404 = calibration.netFit8372(lane, offset8404)
            Locked8325(cross, fp.first, fp.second, calibration.slope(lane), fit8404,
                if (fit8404?.calibrated == true || offset8404 <= 0.0 || !ledger.hasShadowBasis8404()) fp.first else ledger.fuseCostBasis8404(lane, baseVotes8325 + cross, regime, offset8404))
        }
        val dyn = dynamic8325 + locked8325.cross
        val ids = CortexVoters7885.IDS + dyn.map { it.voterId }
        val edges = CortexVoters7885.EDGES + dyn.map { it.edges }
        val raws = DoubleArray(ids.size) { i -> if (i < staticRaws.size) staticRaws[i] else dyn[i - staticRaws.size].raw }
        val fused = locked8325.fused
        val prediction8076 = locked8325.prediction
        // V5.0.7901 — Cortex v7: buckets read the calibrated edge, not the raw one.
        // V5.0.8187 — and executable: the lane's label-to-bank gap comes off the edge the bucket is read on.
        // V5.0.8325 §REGIME_NEUTRAL_SCALP_ORDER — a scalp lane reads the voters' deviation around the lane's ALL-regime prior: the
        // regime-conditioned prior made every coin of a regime share one edge, so the lane's ordering was a bet on the regime's
        // past mean (TREASURY ic -0.33 INVERTED, QUALITY / BLUECHIP / CYCLIC FLAT on lanes with few seated voters).
        // One frozen per-token NET-return forecast drives the panel, verdict and validation record.
        // Log-growth, lane rank and tail ponds remain diagnostics; they must not silently rewrite YES/NO.
        val calibrated = calibratedRead8372(runner, fused, locked8325.slope, locked8325.net8372, executableOffset8187(lane), locked8325.costFused8404)
        val ev8324 = if (runner) tailEvNow8324(lane, fused) else Double.NaN
        val bucket = CortexScoreboard7885.bucketOf(calibrated, fused.runnerRate, runner)
        val registryAt = try { com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522.get(ts.mint)?.timestampMs ?: 0L } catch (_: Throwable) { 0L }
        val freshestAt = maxOf(ts.lastPriceUpdate, registryAt)
        val priceAge = if (freshestAt > 0L) nowMs - freshestAt else Long.MAX_VALUE
        val a = Assessment(lane, runner, raws, ids, edges, regime, fused, calibrated, bucket, raws.count { !it.isFinite() }, priceAge < 0L || priceAge > MARK_STALE_MS, nowMs, prediction8076, ev8324,
            calibrationInput8372 = calibratedRead8325(runner, fused, 1.0))
        try { CortexInvariants7911.recordProvenance(ts, nowMs) } catch (_: Throwable) {}
        assessNanos.addAndGet(System.nanoTime() - t0)
        noteAssessMs8187((System.nanoTime() - t0) / 1_000_000.0)
        assessed.incrementAndGet()
        if (assessCache.size >= MAX_ASSESS_CACHE) assessCache.entries.removeIf { nowMs - it.value.atMs > ASSESS_TTL_MS }
        if (assessCache.size >= ASSESS_PRUNE_AT_8144) pruneAssess8144(nowMs)
        // V5.0.8368: every NEW Cortex lane assessment attempts an independent shadow decision episode.
        // The native-opinion shadow hook is not a Cortex verdict; this keeps YES/NO/HOLD outcomes
        // distinct even when FDG blocks the candidate. Never changes LIVE/PAPER authority or fills.
        val shadowId8372 = "$key|${a.atMs}"
        val shadowContext8372 = try { decisionCtx8144(ts, lane, false, "CORTEX_SHADOW_${a.bucket.name}") } catch (_: Throwable) { null }
        val reserved8372 = synchronized(this) {
            // Publish only after ownership exists. A concurrent capture must not see a prediction whose
            // forward alias cannot yet bind. Coalesced assessments also keep the first immutable prediction.
            assessCache[key]?.let { if (nowMs - it.atMs in 0L..ASSESS_TTL_MS) return it }
            outcomeBook8372.prune(nowMs - GRADED_KEEP_MS_8013)
            outcomeBook8372.reserve(shadowId8372, a.atMs).also { reserved ->
                if (reserved) {
                    shadowPending8372[shadowId8372] = Pending(a, false, a.atMs, source = ts.source).also {
                        it.decisionMode8196 = accountMode8077()
                        it.decisionReason8196 = "CORTEX_SHADOW_${a.bucket.name}"
                        it.context8372 = shadowContext8372
                    }
                    if (shadowContext8372 != null) shadowCtx8372[shadowId8372] = shadowContext8372
                }
                // A slower read from an older snapshot must not replace a newer cached prediction.
                if ((assessCache[key]?.atMs ?: Long.MIN_VALUE) <= a.atMs) assessCache[key] = a
            }
        }
        try {
            if (reserved8372) LanePlaybook7907.onShadowObserved8372(ts.mint, lane, a.atMs)
            // Price evidence has its own bounded observation lifecycle. A simulated non-fill must not
            // decide whether this already-frozen prediction gets a future price outcome.
            if (reserved8372) com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.observe(
                ts, lane, false, "CORTEX_SHADOW_${a.bucket.name}", a.atMs)
            val opened8372 = if (reserved8372) com.lifecyclebot.engine.truth.ExploreShadow8347.onCortexAssessment8368(
                ts, a.lane, a.bucket.name, a.fused.pWin, nowMs,
            ) else null
            if (opened8372 == null && reserved8372) synchronized(this) {
                if (outcomeBook8372.cancelUnopened(shadowId8372)) { shadowPending8372.remove(shadowId8372); shadowCtx8372.remove(shadowId8372) }
            }
        } catch (_: Exception) {
            synchronized(this) { if (outcomeBook8372.cancelUnopened(shadowId8372)) { shadowPending8372.remove(shadowId8372); shadowCtx8372.remove(shadowId8372) } }
            inc("CORTEX_SHADOW_DELIVERY_ERROR_8368")
        }
        return a
    }

    /** V5.0.8325 — what the assessment's one critical section returns. */
    private class Locked8325(val cross: List<CortexLedger7885.Vote>, val fused: CortexLedger7885.Fused,
                             val prediction: CortexLedger7885.PredictionSnapshot8076, val slope: Double,
                             /** Arithmetic net-return calibration, read in the same critical section. */
                             val net8372: CortexCalibration7901.NetFit8372? = null,
                             val costFused8404: CortexLedger7885.Fused = fused)

    /** A flat/unmeasured map has no token discrimination: retain the original fused token read, never a lane-wide HOLD. */
    internal fun calibratedRead8372(runner: Boolean, fused: CortexLedger7885.Fused, slope: Double,
                                    fit: CortexCalibration7901.NetFit8372?, executionOffset: Double = 0.0,
                                    costFused8404: CortexLedger7885.Fused = fused): Double {
        if (fit?.calibrated == true) {
            val edge = fit.expectedNetPct8372(calibratedRead8325(runner, fused, 1.0))
            if (edge.isFinite()) return edge
        }
        return calibratedRead8325(runner, costFused8404, slope) - executionOffset.takeIf { it.isFinite() && it > 0.0 }.orZero8372()
    }

    private fun Double?.orZero8372(): Double = this ?: 0.0

    private fun calibrationScore8372(a: Assessment): Double = a.calibrationInput8372.takeIf { it.isFinite() }
        ?: calibratedRead8325(a.runnerLane, a.fused, 1.0)

    private fun provenance8372(a: Assessment) = CortexAssessmentProvenance8372(
        a.scoreVersion8372, a.fused.edgePct, a.fused.laneMean, a.fused.laneMeanAll8325,
        calibrationScore8372(a), a.calibratedEdge, a.atMs,
    )

    /**
     * Pure (V5.0.8366 §RANK_CALIBRATION): the calibrated (pre-offset) edge. A lane whose rank fit is calibrated reads the
     * isotonic expected return at its slope-free score ([calibratedRead8325] at slope 1, the same score the fit learned on);
     * otherwise the stored slope read stands (thin fit) — an UNCALIBRATED lane keeps that finite value but buckets NEUTRAL.
     */
    internal fun calibratedRead8366(runner: Boolean, fused: CortexLedger7885.Fused, slope: Double, iso: CortexCalibration7901.IsoFit8366?): Double {
        if (iso != null && iso.calibrated) {
            val e = iso.expectedPct8366(calibratedRead8325(runner, fused, 1.0))
            if (e.isFinite()) return e
        }
        return calibratedRead8325(runner, fused, slope)
    }

    /** V5.0.8366 — a lane whose measured rank fit is flat or does not rank its outcomes holds no verdict: NEUTRAL. */
    private fun uncalibratedNeutral8366(lane: String, iso: CortexCalibration7901.IsoFit8366?, b: CortexScoreboard7885.Bucket): CortexScoreboard7885.Bucket {
        if (iso == null || !iso.measured || iso.calibrated || b == CortexScoreboard7885.Bucket.NEUTRAL) return b
        inc("UNCALIBRATED_NEUTRAL_8366_${lane}_${b.name}")
        return CortexScoreboard7885.Bucket.NEUTRAL
    }

    /**
     * Pure (V5.0.8325): the calibrated (pre-offset) edge an assessment is bucketed and ranked on. Runner lanes: the 7901 read
     * (their ordering and STRONG read the tail EV, [tailEvNow8324]). Scalp lanes: the regime-neutral read
     * ([CortexRunOdds8325.regimeNeutral8325]) when the lane's all-regime prior is known.
     */
    internal fun calibratedRead8325(runner: Boolean, fused: CortexLedger7885.Fused, slope: Double): Double {
        val s = if (slope.isFinite()) slope else 1.0
        if (runner || !fused.laneMeanAll8325.isFinite()) return fused.laneMean + s * (fused.edgePct - fused.laneMean)
        return CortexRunOdds8325.regimeNeutral8325(fused.edgePct, fused.laneMean, fused.laneMeanAll8325, s)
    }

    // ── V5.0.8324 §TAIL_EV — runner lanes are bucketed on p x E[run] + (1 - p) x E[non-run] (CortexTailEv8324) ──
    /** Lane -> its pond (graded hold-window objective, raw while plausible), refreshed with the executable offsets. Lock-free reads. */
    private val tailPonds8324 = ConcurrentHashMap<String, CortexTailEv8324.Pond8324>()
    private const val EV_WINDOW_8324 = 256
    /** Lane -> ring of recent decision-time tail EVs (the lane's own distribution the STRONG bar follows). */
    private val evWindows8324 = ConcurrentHashMap<String, DoubleArray>()
    private val evWindowAt8324 = ConcurrentHashMap<String, AtomicLong>()

    /** V5.0.8324 — [lane]'s tail pond (null until graded records exist): admission and the cheat sheets read the same pond. */
    fun tailPond8324(laneRaw: String): CortexTailEv8324.Pond8324? = tailPonds8324[canon(laneRaw)]

    private fun noteEv8324(lane: String, ev: Double) {
        if (!ev.isFinite()) return
        val w = evWindows8324.computeIfAbsent(lane) { DoubleArray(EV_WINDOW_8324) { Double.NaN } }
        val i = evWindowAt8324.computeIfAbsent(lane) { AtomicLong(0) }.getAndIncrement()
        w[(i % EV_WINDOW_8324).toInt()] = ev
    }

    private fun recentEvs8324(lane: String): List<Double> = evWindows8324[lane]?.filter { it.isFinite() }.orEmpty()

    /**
     * The coin's tail EV on its lane's pond (NaN without one). V5.0.8325 §RUN_ODDS — p(run) from the voters' bins on the
     * run / no-run outcome (CortexLedger7885.runOdds8325) when run-skilled seats read the coin; else the 8324 deviation read.
     */
    private fun tailEvNow8324(lane: String, fused: CortexLedger7885.Fused): Double = try {
        val pond = tailPonds8324[lane]
        CortexRunOdds8325.ev8325(pond, fused.pRun8325).takeIf { it.isFinite() }?.also { inc("TAIL_EV_RUN_ODDS_8325_$lane") }
            ?: CortexTailEv8324.ev8324(pond, fused.runnerRate, fused.laneRunnerRate8324)
    } catch (_: Throwable) { Double.NaN }

    /**
     * V5.0.8325 §BUCKET_IS_RANK — a scalp lane's STRONG must sit in the top fifth of its own recent calibrated reads (runner
     * lanes: the tail-EV STRONG bar already is the top fifth, CortexTailEv8324.strongBar8324); and a lane whose own recent
     * ordering is measured INVERTED (cached ranking, never computed here) buckets NEUTRAL. Lock-free.
     */
    private fun rankConsistentBucket8325(lane: String, runner: Boolean, b: CortexScoreboard7885.Bucket, calibrated: Double): CortexScoreboard7885.Bucket = try {
        var out = b
        if (!runner && calibrated.isFinite()) {
            val k = "$lane|CAL8325"
            if (out == CortexScoreboard7885.Bucket.STRONG && !CortexRunOdds8325.strongInTopFifth8325(calibrated, recentEvs8324(k))) out = CortexScoreboard7885.Bucket.NEUTRAL
            noteEv8324(k, calibrated)
        }
        val g = rankCache8325.peek()?.get(lane)
        out = CortexRunOdds8325.guardedBucket8325(out, g?.verdict, g?.rows ?: 0)
        if (out != b) inc("BUCKET_RANK_8325_${lane}_${b.name}_TO_${out.name}")
        out
    } catch (_: Throwable) { b }

    /** A runner lane's bucket: tail EV against the lane's own STRONG bar; REFUSE only on a clearly negative EV with evidence. */
    private fun runnerBucketNow8324(lane: String, calibrated: Double, ev: Double): CortexScoreboard7885.Bucket = try {
        val pond = tailPonds8324[lane]
        val bar = CortexTailEv8324.strongBar8324(recentEvs8324(lane), pond?.mean ?: Double.NaN)
        noteEv8324(lane, ev)
        val b = CortexTailEv8324.runnerBucket8324(calibrated, ev, pond, bar)
        if (b != CortexScoreboard7885.bucketOf(calibrated, 0.0, true)) inc("TAIL_EV_REBUCKET_8324_${lane}_${b.name}")
        b
    } catch (_: Throwable) { CortexScoreboard7885.Bucket.NEUTRAL }

    /** V5.0.8325 — every lane's label pond (runner and scalp): the live admission's lane-label check reads it. Lock-free. */
    private val lanePonds8325 = ConcurrentHashMap<String, CortexTailEv8324.Pond8324>()
    fun lanePond8325(laneRaw: String): CortexTailEv8324.Pond8324? = lanePonds8325[canon(laneRaw)]
    private val pondCache8325 = CortexRank8325.TtlCache8325<Map<String, CortexTailEv8324.Pond8324>>(CortexRank8325.TTL_MS_8325)
    private val rankCache8325 = CortexRank8325.TtlCache8325<Map<String, CortexRank8325.LaneRank8325>>(CortexRank8325.TTL_MS_8325)

    /**
     * Rebuild every lane's pond from its graded records. V5.0.8325 §COPY_ON_READ — never call while holding the lock: the rows
     * are copied under it in one pass, the ponds computed outside it, at most every [CortexRank8325.TTL_MS_8325].
     */
    private fun refreshPonds8324(lanes: Collection<String>) {
        val want = lanes.toSet()
        pondCache8325.get(System.currentTimeMillis()) {
            val rows = synchronized(this) { gradeBook8144.pondRowsByLane8325() }
            val out = HashMap<String, CortexTailEv8324.Pond8324>()
            for ((lane, r) in rows) {
                if (lane !in want) continue
                CortexTailEv8324.pond8324(r.values, r.caps, witnessed = r.witnessed)?.let { out[lane] = it }
            }
            for (lane in want) {
                val pond = out[lane]
                if (pond == null) lanePonds8325.remove(lane) else lanePonds8325[lane] = pond
                if (isRunner(lane)) { if (pond == null) tailPonds8324.remove(lane) else tailPonds8324[lane] = pond }
            }
            out
        }
    }

    /** V5.0.8325 — every lane's ranking check, cached ([CortexRank8325.TTL_MS_8325]); rows copied under the lock, ranked outside. */
    private fun rankings8325(nowMs: Long = System.currentTimeMillis()): Map<String, CortexRank8325.LaneRank8325> =
        rankCache8325.get(nowMs) {
            val rows = synchronized(this) { gradeBook8144.rankRowsByLane8325(scoreVersion = 1) }
            rows.mapNotNull { (lane, r) -> CortexRank8325.rankLane8325(lane, r, isRunner(lane), tailPonds8324[lane]?.compact) }.associateBy { it.lane }
        } ?: emptyMap()

    /**
     * V5.0.8324 — does the Cortex's ranking order forward returns? Per lane with 10+ graded records: Spearman rank IC of the
     * decision-time fused edge (and of the tail EV where recorded) against the lane objective (runner lanes: hold-window
     * revision, raw, executable-net), the top / bottom quintile means, the Cortex buckets and AATE's own take / refuse counts.
     */
    private fun inversionLine8324(): String {
        // V5.0.8325 — cached; computed outside the lock (CortexRank8325). Never call while holding the lock.
        val parts = rankings8325().values.sortedBy { it.lane }.map { it.line }
        return "inversion8324[currentFrozenV1 score=executed_forecast ${parts.joinToString(" · ").ifBlank { "no lane with 10+ graded records" }}]"
    }

    // ── V5.0.8324 — how long an assessment waits for the Cortex lock (the capture pool queues behind it) ──
    private val assessWaitNanos8324 = AtomicLong(0)
    private val assessWaitN8324 = AtomicLong(0)
    private val assessWaitMaxNanos8324 = AtomicLong(0)

    private fun noteAssessWait8324(nanos: Long) {
        if (nanos < 0L) return
        assessWaitNanos8324.addAndGet(nanos); assessWaitN8324.incrementAndGet()
        LaneBrainVotes8366.noteWaitMs8366(nanos / 1_000_000L)  // V5.0.8366 — the lane-brain vote TTL covers the lock wait
        while (true) { val m = assessWaitMaxNanos8324.get(); if (nanos <= m || assessWaitMaxNanos8324.compareAndSet(m, nanos)) break }
    }

    private fun assessWaitLine8324(): String {
        val n = assessWaitN8324.get()
        return "snapshot8386[g=$snapshotGeneration8386 wait=${snapshotWaitMs8386}ms hold=${snapshotHoldMs8386}ms encode=${snapshotEncodeMs8386}ms db=${snapshotDbMs8386}ms] assessLockWait8324[mean=${if (n > 0) "%.1f".format(assessWaitNanos8324.get() / n / 1e6) else "-"}ms max=${"%.0f".format(assessWaitMaxNanos8324.get() / 1e6)}ms n=$n]"
    }

    // ── V5.0.8291 §CORE_VOTERS — the voters seated in a lane are never abstained by the read budget ──
    private const val CORE_REFRESH_MS_8291 = 30_000L
    private val coreCache8291 = ConcurrentHashMap<String, Pair<Long, BooleanArray>>()

    /** Index-aligned with CortexVoters7885.ALL: true where the voter holds authority in [lane] (fusion weighs it). */
    private fun coreVoters8291(lane: String, nowMs: Long): BooleanArray? = try {
        coreCache8291[lane]?.takeIf { nowMs - it.first in 0L until CORE_REFRESH_MS_8291 }?.second ?: run {
            val ids = CortexVoters7885.IDS
            val core = synchronized(this) { BooleanArray(ids.size) { i -> (ledger.seats["${ids[i]}|$lane"]?.authority() ?: 0.0) > 0.0 } }
            coreCache8291[lane] = nowMs to core
            core
        }
    } catch (_: Throwable) { null }

    // ── V5.0.8144 — memory: assessments and pending decisions are the heaviest resident Cortex rows ──
    // An Assessment carries ~400 voter slots (raws, ids, edges and the prediction snapshot): ~15-20 KB. The cache kept
    // up to 4,000 of them although each is read for 20-60 s (stale ones went only once the cache was full: ~70 MB at
    // steady state), and a pending decision whose 5-minute label can no longer arrive (ForwardReturnLabeler7731 keeps
    // an observation 70 min at most) stayed for 5 hours (up to 8,000 x ~18 KB). Neither was reached by any trim.
    private const val ASSESS_PRUNE_AT_8144 = 512
    /** The longest any reader trusts a cached assessment (cortexStake7893 and the legacy-shape read: 60 s). */
    private const val ASSESS_KEEP_MS_8144 = 60_000L
    /** Past this a pending decision cannot be graded: the labeler never holds an observation past 70 min. */
    const val DEAD_PENDING_MS_8144 = 90L * 60_000L
    const val ASSESSMENT_BASE_BYTES_8144 = 512L
    @Volatile private var lastAssessPruneMs8144 = 0L
    @Volatile private var lastDeadPrune8144 = 0L
    private val assessEvicted8144 = AtomicLong(0)
    private val deadPendingEvicted8144 = AtomicLong(0)

    /** Drop cached assessments no reader can use any more (at most every 5 s). */
    private fun pruneAssess8144(nowMs: Long) {
        if (nowMs - lastAssessPruneMs8144 < 5_000L) return
        lastAssessPruneMs8144 = nowMs
        val before = assessCache.size
        assessCache.entries.removeIf { nowMs - it.value.atMs > ASSESS_KEEP_MS_8144 }
        assessEvicted8144.addAndGet((before - assessCache.size).coerceAtLeast(0).toLong())
    }

    /** Drop pending decisions older than [DEAD_PENDING_MS_8144] (their label can no longer arrive; at most every 60 s). */
    private fun pruneDeadPending8144(nowMs: Long, force: Boolean = false) {
        if (!force && nowMs - lastDeadPrune8144 < 60_000L) return
        lastDeadPrune8144 = nowMs
        pairedPending8401.entries.removeIf { nowMs - it.value.atMs > DEAD_PENDING_MS_8144 }
        val before = pending.size
        for ((key, p) in pending.entries) if (nowMs - p.atMs > DEAD_PENDING_MS_8144) {
            synchronized(this) {
                if (pending.remove(key, p)) {
                    captureOwners8397.terminal(key, p.atMs)
                    deferredLabels8397.remove("$key|${p.atMs}")
                    p.context8372?.let { pendingCtx8144.remove(key, it) }
                }
            }
        }
        deadPendingEvicted8144.addAndGet((before - pending.size).coerceAtLeast(0).toLong())
    }

    /**
     * MemoryGuard7977 (registry trim): stale assessments and dead pending decisions go at any pressure; under HARD /
     * CRITICAL pressure every cached assessment older than the 20 s read window goes (each is rebuilt on demand).
     * Entry reads of open positions (entryReads) and live pending decisions are never touched.
     */
    fun trim8144(p: com.lifecyclebot.engine.RetentionPolicy8136.Pressure) {
        val now = System.currentTimeMillis()
        lastAssessPruneMs8144 = 0L
        pruneAssess8144(now)
        pruneDeadPending8144(now, force = true)
        if (p == com.lifecyclebot.engine.RetentionPolicy8136.Pressure.HARD || p == com.lifecyclebot.engine.RetentionPolicy8136.Pressure.CRITICAL) {
            val before = assessCache.size
            assessCache.entries.removeIf { now - it.value.atMs > ASSESS_TTL_MS }
            assessEvicted8144.addAndGet((before - assessCache.size).coerceAtLeast(0).toLong())
            scheduled.entries.removeIf { now - it.value > 30_000L }
        }
    }

    fun assessCacheCount8144(): Int = assessCache.size
    fun entryReadCount8144(): Int = entryReads.size
    fun gradedCount8144(): Int = synchronized(this) { graded8006.size }
    fun evicted8144(): Long = assessEvicted8144.get() + deadPendingEvicted8144.get()

    /** Census: bytes of one assessment of the current voter width (raws, ids, edges, prediction: ~40 B a slot). */
    fun assessmentBytes8144(): Long = ASSESSMENT_BASE_BYTES_8144 + 40L * (try { CortexVoters7885.IDS.size + 120 } catch (_: Throwable) { 400 })

    /** Pure: the persisted pending snapshot carries only decisions a label can still reach. */
    fun persistablePending8144(atMs: Long, nowMs: Long): Boolean = nowMs - atMs <= DEAD_PENDING_MS_8144

    // ── V5.0.8169 — audit accessors and the voter-read leakage check ──

    /**
     * Were this decision's voters read after its forward label started? The labeler starts the label at the first mark
     * ForwardReturnLabeler7731.rebaseDelayMs8094(lane fill latency) after the decision; a later read graded features that
     * already contain part of the graded move (a lagged voter "predicts" the pump it watched). Recorded per capture.
     */
    private fun lateVoterRead8169(labelLane: String, decisionAtMs: Long, readAtMs: Long): Boolean {
        val delay = try {
            com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.rebaseDelayMs8094(
                try { com.lifecyclebot.engine.truth.EntryChase7961.laneLatencyMs8094(labelLane) } catch (_: Throwable) { 0L })
        } catch (_: Throwable) { 5_000L }
        val late = CortexAudit8169.voterReadLeaks8169(readAtMs, decisionAtMs, delay)
        CortexAudit8169.noteCapture(readAtMs - decisionAtMs, late)
        return late
    }

    // ── V5.0.8187 — assessment latency distribution (the 8186 mean of 1.8 s hid whether every read was slow) ──
    private val assessMsRing8187 = DoubleArray(256)
    private val assessMsN8187 = AtomicLong(0)

    private fun noteAssessMs8187(ms: Double) {
        if (!ms.isFinite()) return
        LaneBrainVotes8366.noteComputeMs8366(ms.toLong())  // V5.0.8366
        val i = (assessMsN8187.getAndIncrement() % assessMsRing8187.size).toInt()
        synchronized(assessMsRing8187) { assessMsRing8187[i] = ms }
    }

    /** Pure (V5.0.8187): the [q]-quantile of the first [n] values of [ring] (NaN with none). */
    fun quantile8187(ring: DoubleArray, n: Int, q: Double): Double {
        val m = n.coerceIn(0, ring.size)
        if (m == 0) return Double.NaN
        val v = ring.copyOf(m).sorted()
        return v[((m - 1) * q.coerceIn(0.0, 1.0)).toInt()]
    }

    /** V5.0.8187 — "assessMs8187[p50= p90= n=]" over the last 256 assessments. */
    fun assessLatencyLine8187(): String {
        val n = assessMsN8187.get().coerceAtMost(assessMsRing8187.size.toLong()).toInt()
        val ring = synchronized(assessMsRing8187) { assessMsRing8187.copyOf() }
        return "assessMs8187[p50=${"%.1f".format(quantile8187(ring, n, 0.5))} p90=${"%.1f".format(quantile8187(ring, n, 0.9))} n=${assessMsN8187.get()}]"
    }

    fun assessedCount8169(): Long = assessed.get()
    fun gradedCount8169(): Long = graded.get()
    fun avgAssessMs8169(): Double = assessed.get().let { n -> if (n > 0) assessNanos.get() / n / 1_000_000.0 else 0.0 }
    fun gradeBookSize8169(): Long = try { synchronized(this) { (gradeBook8144.size() + gradeBook8144.tradeCount()).toLong() } } catch (_: Throwable) { 0L }
    fun calibrationLine8169(): String = synchronized(this) { "slope=${calibration.line()}" }
    private fun counterSum8169(prefix: String): Long = counters.entries.filter { it.key.startsWith(prefix) }.sumOf { it.value.get() }
    fun hygieneLine8169(): String =
        "lateVoterRead=${counterSum8169("LABEL_HYGIENE_LATE_VOTER_READ_8169")} lateFirstPrice=${counterSum8169("LABEL_HYGIENE_LATE_FIRST_PRICE_8153")} " +
            "repeat=${counterSum8169("LABEL_HYGIENE_REPEAT")} unexec=${counterSum8169("LABEL_HYGIENE_UNEXECUTABLE")} seen[R/N/S]=" +
            "${counterSum8169("SEEN_REFUSE")}/${counterSum8169("SEEN_NEUTRAL")}/${counterSum8169("SEEN_STRONG")} " +
            "lost8187=${counterSum8169("LABEL_LOST_")} restoredPastLabel8187=${counterSum8169("PENDING_RESTORE_PAST_LABEL_8187")} " +
            "clamped8187=${counterSum8169("LABEL_CLAMPED_AT_INGESTION_8187")} gradedPerHour8187=${"%.1f".format(gradedPerHour8187())} " +
            "unexecWhy8291=[${counters.entries.filter { it.key.startsWith("LABEL_UNEXEC_CLASS_8291_") }.sortedByDescending { it.value.get() }.take(6)
                .joinToString(",") { "${it.key.removePrefix("LABEL_UNEXEC_CLASS_8291_")}=${it.value.get()}" }.ifBlank { "-" }}]"
    fun gradeBookLine8169(): String =
        "decisions+trades=${gradeBookSize8169()} unboundFiled=${counterSum8169("REALIZED_UNBOUND_FILED_8153")} " +
            "replayedCloses=${counterSum8169("REALIZED_REPLAYED_8169")} olderThanBook=${counterSum8169("REALIZED_UNBOUND_OLDER_THAN_BOOK_8169")} " +
            "evictionPasses8187=${try { synchronized(this) { gradeBook8144.evicted8187 } } catch (_: Throwable) { 0L }}"
    private val loadedAtMs8169 = System.currentTimeMillis()

    // ── v1 §2.9 compute model (V5.0.7909): voters run off the decision path ──
    //
    // 5.0.7891: 1,073 assessments at 25.7 ms each, inline on the FDG thread. The
    // gate now only READS a fresh cached assessment; a miss schedules one on a
    // small bounded worker pool and the gate proceeds without a Cortex opinion
    // this cycle (the candidate is re-evaluated next cycle). Capture runs on the
    // pool too; its label still starts at the observation time.
    // V5.0.8318 — assessment workers 2 → 4 (core 2): with the queue bounded at 256 the two extra workers only start when
    // the queue is full; the voter time budget (CortexVoters7885.ASSESS_BUDGET_MS_8187) still bounds each assessment.
    private val pool: java.util.concurrent.ThreadPoolExecutor = java.util.concurrent.ThreadPoolExecutor(
        2, 4, 30L, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue(256),
        { r -> Thread(r, "cortex-7909").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } },
        // V5.0.7930 — a dropped job is counted (ASYNC_DROPPED), never silent.
        java.util.concurrent.RejectedExecutionHandler { r, ex ->
            if (!ex.isShutdown) {
                ex.queue.poll()
                inc("ASYNC_DROPPED")
                try { ex.execute(r) } catch (_: Throwable) {}
            }
        },
    )
    // V5.0.8171: decision-time Cortex captures no longer wait behind a queue
    // of slow native/voter assessments. 8169 measured mean capture lag 3.65 s
    // with 190 queued and 257 late reads. Two single-worker pools retain the
    // original maximum of two workers but reserve one exclusively for captures.
    // Bounded queue: if overwhelmed the oldest observation is discarded and
    // accounted for; we never relabel a late read as decision-time proof.
    // V5.0.8187 — the queue is admitted to by submitCaptureKeyed8187's own accounting (coalesced per mint|lane, bounded,
    // every refusal counted by reason), so the executor never evicts a queued decision: 8186 lost 86 captures to the
    // oldest-job poll. Its own last resort runs the job on the caller (the decision stays, immutable) and is counted.
    // V5.0.8318 — capture workers 1 → 2: 5.0.8314 measured capture meanLagMs 111,764 with the single worker behind a queue
    // of up to 416 cold captures. The queue hard cap (CAPTURE_QUEUE_HARD_8187, declared first: the executor's queue is sized
    // from it) and submitCaptureKeyed8187's per-key accounting still bound memory; a capture's label still starts at the
    // observation time, never at the run time.
    private const val CAPTURE_QUEUE_HARD_8187 = 416
    private val capturePool8171: java.util.concurrent.ThreadPoolExecutor = java.util.concurrent.ThreadPoolExecutor(
        3, 3, 30L, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue(CAPTURE_QUEUE_HARD_8187 + 32),   // V5.0.8399 — 2 → 3 workers
        { r -> Thread(r, "cortex-capture-8171").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } },
        java.util.concurrent.RejectedExecutionHandler { r, ex ->
            if (!ex.isShutdown) {
                inc("CAPTURE_QUEUE_DROP_8171_CALLER_RUNS_8187")
                try { r.run() } catch (_: Throwable) { inc("CAPTURE_REJECTED_8171") }
            }
        },
    )

    // ── V5.0.8187 §ACCOUNTABLE_CAPTURE_QUEUE ──
    /** Non-admitted decisions queue up to this; an admitted decision (a trade's entry verdict) may use the headroom above. */
    private const val CAPTURE_QUEUE_SOFT_8187 = 320
    /** "mint|LANE" -> the newest decision's capture job not yet run (one queued runnable per key). */
    private val captureLatest8187 = ConcurrentHashMap<String, () -> Unit>()
    private val captureQueued8187 = java.util.concurrent.atomic.AtomicInteger(0)
    private val captureOwners8397 = CortexCaptureOwnership8397(this)

    /** A label can beat a queued immutable capture; retain it until that exact capture publishes. */
    private data class DeferredLabel8397(val mint: String, val lane: String, val atMs: Long,
        val net: Double, val gross: Double, val family: DoubleArray?, val flat: Boolean, val witness: Boolean,
        val proof: CortexOutcomeProof8395?)
    private val deferredLabels8397 = HashMap<String, DeferredLabel8397>() // guarded by the model lock, <= queued/running captures

    /** Pure (V5.0.8187): may a new key enter a capture queue already holding [queued] keys? */
    fun captureAdmits8187(queued: Int, admitted: Boolean): Boolean =
        queued < (if (admitted) CAPTURE_QUEUE_HARD_8187 else CAPTURE_QUEUE_SOFT_8187)

    /**
     * V5.0.8187 — one queued capture per (mint, lane). A newer decision for a key still queued REPLACES its job (the labeler
     * keeps one observation per key and an admit supersedes a refusal, so the label belongs to the newest decision; each job
     * carries its own frozen decision-time evidence). A new key on a full queue is the one refused (the newest, never a queued
     * decision), admitted decisions keep headroom, and every refusal is counted by reason.
     */
    private fun submitCaptureKeyed8187(ticket: CortexCaptureOwnership8397.Ticket, admitted: Boolean, job: () -> Unit) {
        val key = ticket.key
        val ownedJob8397 = {
            try { job() } finally {
                synchronized(this) {
                    if (captureOwners8397.abandon(ticket)) {
                        deferredLabels8397.remove("$key|${ticket.atMs}")
                        inc("CAPTURE_ENDED_WITHOUT_PUBLICATION_8397")
                    }
                }
            }
        }
        synchronized(this) {
            // Freeze/setup work can itself finish out of order before enqueue. An old enqueue must not replace the new job.
            if (!captureOwners8397.current(ticket)) { inc("CAPTURE_SUPERSEDED_BEFORE_QUEUE_8397"); return }
            if (captureLatest8187.put(key, ownedJob8397) != null) { inc("CAPTURE_COALESCED_SAME_KEY_8187"); return }
            if (!captureAdmits8187(captureQueued8187.get(), admitted)) {
                captureLatest8187.remove(key, ownedJob8397)
                // V5.0.8399 §NO_DECISION_LOSES_ITS_OUTCOME — a frozen decision whose capture cannot queue now waits in a
                // bounded overflow (one per key, admitted first) and drains as captures complete. 5.0.8398 dropped 135
                // admitted + 594 newest captures as terminal: each became a matured decision with no outcome, CORE/MOONSHOT
                // STRONG coverage fell to ~3%, and CortexAuthority8366 answered INCOMPLETE_OUTCOMES (authority 0) for every
                // lane — the learning was retained while the permission to use it was lost.
                overflowPut8399(key, ticket, admitted, ownedJob8397)
                return
            }
            captureQueued8187.incrementAndGet()
        }
        submitCapture8171 { try { captureLatest8187.remove(key)?.invoke() } finally { captureQueued8187.decrementAndGet(); drainOverflow8399() } }
    }

    // ── V5.0.8399 §NO_DECISION_LOSES_ITS_OUTCOME ──
    private class Overflow8399(val ticket: CortexCaptureOwnership8397.Ticket, val admitted: Boolean, val job: () -> Unit)
    private const val CAPTURE_OVERFLOW_MAX_8399 = 2_000
    /** "mint|LANE" -> the newest decision's capture waiting for queue room; insertion-ordered so the oldest drains first. */
    private val captureOverflow8399 = LinkedHashMap<String, Overflow8399>()   // guarded by the model lock
    private val overflowPeak8399 = java.util.concurrent.atomic.AtomicInteger(0)

    /** Model lock held. A newer decision for a key already waiting replaces it (the older ticket ends exactly as a drop did). */
    private fun overflowPut8399(key: String, ticket: CortexCaptureOwnership8397.Ticket, admitted: Boolean, job: () -> Unit) {
        captureOverflow8399.remove(key)?.let { old ->
            captureOwners8397.terminal(key, old.ticket.atMs)
            deferredLabels8397.remove("$key|${old.ticket.atMs}")
            inc("CAPTURE_OVERFLOW_SUPERSEDED_SAME_KEY_8399")
        }
        if (captureOverflow8399.size >= CAPTURE_OVERFLOW_MAX_8399) {
            // Evict the oldest NON-admitted waiter (an admitted decision is a trade's own verdict: it waits); if every
            // waiter is admitted, the oldest admitted one goes. Bounded memory, and the loss is counted by kind.
            val victimKey = captureOverflow8399.entries.firstOrNull { !it.value.admitted }?.key ?: captureOverflow8399.keys.first()
            captureOverflow8399.remove(victimKey)?.let { v ->
                captureOwners8397.terminal(victimKey, v.ticket.atMs)
                deferredLabels8397.remove("$victimKey|${v.ticket.atMs}")
                inc(if (v.admitted) "CAPTURE_OVERFLOW_EVICT_ADMITTED_8399" else "CAPTURE_OVERFLOW_EVICT_NEWEST_8399")
            }
        }
        captureOverflow8399[key] = Overflow8399(ticket, admitted, job)
        overflowPeak8399.updateAndGet { maxOf(it, captureOverflow8399.size) }
        inc(if (admitted) "CAPTURE_OVERFLOW_WAIT_ADMITTED_8399" else "CAPTURE_OVERFLOW_WAIT_NEWEST_8399")
    }

    /** After a capture completes: move waiting decisions into the queue while it admits them (admitted keys first). */
    private fun drainOverflow8399() {
        while (true) {
            var key: String? = null
            var o: Overflow8399? = null
            synchronized(this) {
                if (captureOverflow8399.isEmpty()) return
                val e = captureOverflow8399.entries.firstOrNull { it.value.admitted } ?: captureOverflow8399.entries.first()
                if (!captureAdmits8187(captureQueued8187.get(), e.value.admitted)) return
                key = e.key; o = e.value
                captureOverflow8399.remove(e.key)
                if (!captureOwners8397.current(o!!.ticket)) { inc("CAPTURE_OVERFLOW_SUPERSEDED_BEFORE_QUEUE_8399"); key = null; return@synchronized }
                if (captureLatest8187.put(e.key, o!!.job) != null) { inc("CAPTURE_COALESCED_SAME_KEY_8187"); key = null; return@synchronized }
                captureQueued8187.incrementAndGet()
            }
            val k = key ?: continue
            inc("CAPTURE_OVERFLOW_DRAINED_8399")
            submitCapture8171 { try { captureLatest8187.remove(k)?.invoke() } finally { captureQueued8187.decrementAndGet(); drainOverflow8399() } }
            return   // one drain per completed capture keeps the queue's own accounting the authority
        }
    }

    /** V5.0.8399 — report: decisions waiting for capture room and the high-water mark. */
    private fun captureOverflowStatsLocked8399(): String = "overflow8399=${captureOverflow8399.size} peak=${overflowPeak8399.get()}"

    private fun submitCapture8171(job: () -> Unit) {
        try {
            capturePool8171.execute { try { job() } catch (_: Throwable) { inc("CAPTURE_FAILED_8171") } }
            inc("ASYNC_CAPTURE")
        } catch (_: Throwable) { inc("CAPTURE_REJECTED_8171") }
    }

    private val scheduled = ConcurrentHashMap<String, Long>()

    private fun submit(tag: String, job: () -> Unit) {
        try {
            pool.execute { try { job() } catch (_: Throwable) {} }
            inc("ASYNC_$tag")
        } catch (_: Throwable) { inc("ASYNC_REJECTED") }
    }

    /** V5.0.7948 — CortexTiming7900: an inverted lane's STRONG read is not exempt from the dip wait. */
    fun laneInverted7948(laneRaw: String): Boolean =
        try { ensureLoaded(); synchronized(this) { invertedCurrent8372(canon(laneRaw)) } } catch (_: Throwable) { false }

    /** For CortexInvariants7911. */
    fun queuedTasks(): Int = try { pool.queue.size + capturePool8171.queue.size } catch (_: Throwable) { 0 }
    fun pendingCount(): Int = pending.size + pairedPending8401.size

    /** The fresh cached assessment, or null after scheduling one (never computes inline). */
    private fun cachedOrSchedule(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): Assessment? {
        if (ts.mint.isBlank() || laneRaw.isBlank()) return null
        val key = "${ts.mint}|${canon(laneRaw)}"
        assessCache[key]?.let { if (nowMs - it.atMs <= ASSESS_TTL_MS) return it }
        // A job the full queue dropped never reaches its finally: a key may be
        // rescheduled after 30 s, so a dropped assessment cannot block a mint forever.
        val prev = scheduled[key]
        if (prev == null || nowMs - prev > 30_000L) {
            scheduled[key] = nowMs
            CortexVoters7885.stampLaneBrains8366(ts, nowMs)  // V5.0.8366 — the queued assessment keeps the brains' votes
            submit("ASSESS") { try { assess(ts, laneRaw) } finally { scheduled.remove(key) } }
        } else inc("ASYNC_ALREADY_SCHEDULED")
        if (scheduled.size > 4_000) scheduled.entries.removeIf { nowMs - it.value > 30_000L }
        return null
    }

    /**
     * 8196: the native brain identified a qualified lane BEFORE its FDG read.
     * Queue a Cortex voter assessment now, so the immutable decision-time
     * capture can reuse a warm prediction rather than opening an already-late
     * cold voter read. Strictly bounded by cachedOrSchedule's existing pool.
     */
    fun prewarmNative8196(ts: TokenState, lane: String) {
        try {
            if (ts.mint.isNotBlank() && lane.isNotBlank()) {
                val cached = cachedOrSchedule(ts, lane)
                inc(if (cached == null) "PREWARM_QUEUED_8196" else "PREWARM_CACHE_HIT_8196")
            }
        } catch (_: Throwable) { inc("PREWARM_FAILED_8196") }
    }

    /** Setup/freezing happens before queue handoff and can fail too; no unfinished owner may be stranded there. */
    private fun prepareCapture8397(ticket: CortexCaptureOwnership8397.Ticket, prepare: () -> Unit) {
        try {
            prepare()
        } catch (failure: Throwable) {
            synchronized(this) {
                if (captureOwners8397.abandon(ticket)) {
                    deferredLabels8397.remove("${ticket.key}|${ticket.atMs}")
                    inc("CAPTURE_PREPARATION_FAILED_8397")
                }
            }
            throw failure
        }
    }

    /** ForwardReturnLabeler7731.observe: one graded decision opens here (1:1 with its observation). */
    fun capture(ts: TokenState, labelLane: String, admitted: Boolean, nowMs: Long = System.currentTimeMillis(), reason: String? = null) {
        if (labelLane.startsWith("PLANWAIT_") || labelLane.startsWith("PLANADMIT_")) return
        val captureKey8397 = "${ts.mint}|${labelLane.trim().uppercase()}"
        val ticket8397 = synchronized(this) {
            captureOwners8397.claim(captureKey8397, nowMs, pending[captureKey8397]?.atMs ?: 0L)?.also {
                deferredLabels8397.keys.removeIf { id -> id.startsWith("$captureKey8397|") && id != "$captureKey8397|$nowMs" }
            }
        } ?: run { inc("CAPTURE_DUPLICATE_STALE_OR_FULL_8397"); return }
        prepareCapture8397(ticket8397) {
            CortexVoters7885.stampLaneBrains8366(ts, nowMs)  // V5.0.8366 — decision-time lane-brain votes for the capture read
            // V5.0.8084 — the setup classification is frozen NOW (decision time); the async job used to classify the
            // TokenState as it was whenever the queue reached it, crediting setups the decision never saw.
            val snap8084 = try { LanePlaybook7907.decisionSnapshot8084(ts, labelLane, nowMs) } catch (_: Throwable) { null }
            // V5.0.8153 — and the strategy the coin trades as (proven / valid alternative / classified), at the same instant.
            val sel8153 = try { LanePlaybook7907.selectedSetup8094(ts, labelLane, nowMs) } catch (_: Throwable) { null }
            // Playbook labels belong to the decision even when the 291-voter Cortex is not ready.
            // Freeze the tag and timing here; never classify the mutable TokenState minutes later.
            val timing8174 = try { LanePlaybook7907.timingBandOf8094(ts) } catch (_: Throwable) { LanePlaybook7907.TIMING_UNKNOWN_8094 }
            try { LanePlaybook7907.capture(ts, labelLane, labelLane, nowMs, snap8084, sel8153, timing8174) } catch (_: Throwable) {}
            val a8174 = assessCache["${ts.mint}|${canon(labelLane)}"]?.takeIf { nowMs - it.atMs in 0L..ASSESS_TTL_MS }
            if (a8174 == null) {
                // A voter read after the decision would predict a move it has already observed.
                inc("CAPTURE_VOTERS_NOT_READY_AT_DECISION_8174")
                // V5.0.8178 — 8174 dropped every cold decision (5.0.8175: 1081 dropped vs 19 captured, graded=0). The
                // voters are read now on the capture pool; captureNow audits that read against the label start, so a read
                // after the label opens is held out of authority (LABEL_HYGIENE_LATE_VOTER_READ_8169), never graded as proof.
                val ctxCold8178 = try { decisionCtx8144(ts, canon(labelLane), admitted, reason) } catch (_: Throwable) { null }
                val sourceCold8178 = ts.source
                val decisionMode8196 = accountMode8077()
                // V5.0.8273 (R9) — freeze the decision-time features NOW: the cold voter read runs later on the capture pool
                // and must not reconstruct a past prediction from the live, mutating TokenState. The voters read this copy.
                val frozen8273 = try { ts.copy() } catch (_: Throwable) { ts }
                inc("CAPTURE_COLD_FEATURES_FROZEN_8273")
                submitCaptureKeyed8187(ticket8397, admitted) { captureCold8178(frozen8273, labelLane, admitted, nowMs, reason, snap8084, sel8153, ctxCold8178, sourceCold8178, decisionMode8196, ticket8397) }
                return@prepareCapture8397
            }
            val ctx8174 = try { decisionCtx8144(ts, a8174.lane, admitted, reason) } catch (_: Throwable) { null }
            // Timing requires the decision's price, before the asynchronous queue mutates its TokenState.
            try { CortexTiming7900.capture(ts, a8174, nowMs) } catch (_: Throwable) {}
            val source8174 = ts.source
            val decisionMode8196 = accountMode8077()
            submitCaptureKeyed8187(ticket8397, admitted) { captureNow(ts.mint, labelLane, admitted, nowMs, reason, snap8084, sel8153, a8174, ctx8174, source8174, decisionMode8196, ticket8397) }
        }
    }

    /** V5.0.8178 — a cold decision: one voter read on the capture pool (stamped with its own read time), then captureNow. */
    private fun captureCold8178(ts: TokenState, labelLane: String, admitted: Boolean, decisionAtMs: Long, reason: String?,
                                snap8084: LanePlaybook7907.Classification8084?, sel8153: String?,
                                ctx: DecisionCtx8144?, source: String, decisionMode8196: String, ticket8397: CortexCaptureOwnership8397.Ticket) {
        if (!captureOwners8397.current(ticket8397)) { inc("CAPTURE_SUPERSEDED_BEFORE_READ_8397"); return }
        val a = assess(ts, labelLane) ?: run { inc("CAPTURE_COLD_ASSESS_FAILED_8178"); return }
        inc("CAPTURE_COLD_READ_8178")
        captureNow(ts.mint, labelLane, admitted, decisionAtMs, reason, snap8084, sel8153, a, ctx?.takeIf { a.lane == canon(labelLane) }, source, decisionMode8196, ticket8397)
        // Timing needs the decision's price: only a read that still preceded the label start opens a timing sample.
        val p = pending["${ts.mint}|${labelLane.trim().uppercase()}"]
        if (p != null && p.atMs == decisionAtMs && !p.lateRead8169) try { CortexTiming7900.capture(ts, a, decisionAtMs) } catch (_: Throwable) {}
    }

    private fun captureNow(mint: String, labelLane: String, admitted: Boolean, nowMs: Long, reason: String?,
                           snap8084: LanePlaybook7907.Classification8084?, sel8153: String?,
                           a: Assessment, frozenCtx8174: DecisionCtx8144?, frozenSource8174: String, decisionMode8196: String,
                           ticket8397: CortexCaptureOwnership8397.Ticket) {
        if (!captureOwners8397.current(ticket8397)) { inc("CAPTURE_SUPERSEDED_BEFORE_PUBLISH_8397"); return }
        // Decision-time immutable assessment. An asynchronous fresh read is NEVER evidence from an earlier decision.
        val late8169 = lateVoterRead8169(labelLane, nowMs, a.atMs)
        pruneDeadPending8144(nowMs)
        if (pending.size >= MAX_PENDING) pending.entries.removeIf { nowMs - it.value.atMs > PENDING_TTL_MS }
        if (pending.size >= MAX_PENDING) { inc("PENDING_FULL"); return }
        val veto = if (admitted || reason.isNullOrBlank()) null else vetoRuleOf(reason)
        val setup7961 = snap8084?.takeIf { it.lane == a.lane }?.consumerTag8087.orEmpty()
        // V5.0.8088 — a decision whose coin could not be classified (DATA_INCOMPLETE / MATCHES_UNRANKABLE /
        // CLASSIFIER_ERROR) carries no setup prior and opens no joint cell; the scoreboard counts how many
        // decisions ran blind so the UI shows evidence quality beside the NO_TRIGGER cohort, not pooled into it.
        if (snap8084?.tag in LanePlaybook7907.UNCLASSIFIED_8087) inc("SETUP_UNCLASSIFIED_NO_PRIOR_8087_${snap8084?.outcome?.name}")
        // V5.0.8094 — multi-strategy grading: every setup the coin fired (an unclassified coin's too) is graded in its own
        // lane x bucket x setup cell, not only the one consumer tag.
        val fired8094 = snap8084?.takeIf { it.lane == a.lane }?.matched.orEmpty()
            .filter { it.isNotBlank() && it != setup7961 && it != LanePlaybook7907.NO_TRIGGER }.distinct().take(8)
        val captureKey8372 = "$mint|${labelLane.trim().uppercase()}"
        val captured8372 = Pending(a, admitted, nowMs, veto, frozenSource8174, setup7961, fired8094).also {
            it.selected8153 = sel8153.orEmpty()
            it.lateRead8169 = late8169
            it.decisionMode8196 = decisionMode8196
            it.decisionReason8196 = reason
            it.context8372 = frozenCtx8174
        }
        val published8397 = captureOwners8397.publish(ticket8397) {
            // The owner check and both maps commit together. A prior episode is explicitly unresolved, never relabelled.
            pending[captureKey8372]?.takeIf { it.atMs != nowMs }?.let { old ->
                val oldId = "$captureKey8372|${old.atMs}"
                if (gradeBook8144.decision(oldId) == null)
                    gradeBook8144.addDecision(newDecision8144(oldId, captureKey8372, old, old.context8372, verdict8144(captureKey8372, old, old.context8372)))
                gradeBook8144.markLost(oldId)
                inc("CAPTURE_PREVIOUS_EPISODE_SUPERSEDED_8397")
            }
            pending[captureKey8372] = captured8372
            if (frozenCtx8174 != null) pendingCtx8144[captureKey8372] = frozenCtx8174 else pendingCtx8144.remove(captureKey8372)
            val shadowId = "$mint|${a.lane}|${a.atMs}"
            val labelId = "$captureKey8372|$nowMs"
            if (!late8169 && outcomeBook8372.bind(shadowId, labelId, gradeBook8144.decision(labelId)?.executablePct?.isFinite() == true)) {
                // Preserve the exact original prediction, setup and original decision-context mode for its later exit.
                if (outcomeBook8372.shadowPlan(shadowId).action != CortexOutcomeBook8372.Action.OBSERVE_ONLY) {
                    shadowPending8372[shadowId]?.let { original ->
                        captured8372.outcomeKind8394 = original.outcomeKind8394
                        captured8372.outcomeDueAtMs8394 = original.outcomeDueAtMs8394
                    }
                    shadowPending8372[shadowId] = captured8372
                    if (frozenCtx8174 != null) shadowCtx8372[shadowId] = frozenCtx8174
                }
                inc("SHADOW_LABEL_BOUND_8372")
            }
            if (admitted && !late8169 && reason?.startsWith("NATIVE_") != true) noteEntryRead(mint, decisionMode8196 == "PAPER", a, nowMs)
            else if (admitted) inc("ENTRY_READ_LATE_NOT_BOUND_8187")
        }
        if (!published8397) { inc("CAPTURE_SUPERSEDED_BEFORE_PUBLISH_8397"); return }
        try { sweepLost8144(nowMs) } catch (_: Throwable) {}
        // Timing was already captured at decision time, never recomputed behind the queue.
        inc(if (a.bucket == CortexScoreboard7885.Bucket.REFUSE) "SEEN_REFUSE" else if (a.bucket == CortexScoreboard7885.Bucket.STRONG) "SEEN_STRONG" else "SEEN_NEUTRAL")
        synchronized(this) { deferredLabels8397.remove("$captureKey8372|$nowMs") }?.let { label ->
            onLabel(label.mint, label.lane, 60, label.net, label.gross, label.family,
                label.flat, label.witness, label.atMs, label.proof)
        }
        persistPendingMaybe(nowMs)
        try { LanePlaybook7907.persistPendingMaybe(nowMs) } catch (_: Throwable) {}
    }

    // ── V5.0.7920: pending decisions survive a restart ──
    //
    // Labels mature 60 min (240 for runner lanes) after the decision, and every
    // install or restart dropped the in-memory pending set, so a book restarted
    // more often than hourly graded nothing. The newest [PENDING_PERSIST_MAX]
    // pending decisions (finite votes only, sparse) are saved every 2 minutes
    // and restored on load; edges are rebuilt from voter ids.
    private const val PENDING_PERSIST_KEY = "CORTEX_PENDING_7920"
    private const val PENDING_PERSIST_MAX = 1_000
    private const val PENDING_PERSIST_EVERY_MS = 120_000L
    @Volatile private var lastPendingPersistMs = 0L

    private fun fin0(v: Double): Double = if (v.isFinite()) kotlin.math.round(v * 1e4) / 1e4 else 0.0

    private fun persistPendingMaybe(nowMs: Long) {
        // V5.0.7930 — restore before the first save, or the save erases what was pending.
        ensureLoaded()
        if (!loaded) return
        if (nowMs - lastPendingPersistMs < PENDING_PERSIST_EVERY_MS) return
        lastPendingPersistMs = nowMs
        persist()
    }

    /** Caller holds the Cortex lock: the traces and books must describe the same checkpoint. */
    private fun detachAssessment8386(a: Assessment): Assessment = Assessment(a.lane, a.runnerLane, a.raws.copyOf(), a.ids.toList(),
        a.edges.map { it.copyOf() }, a.regime, a.fused.copy(top = a.fused.top.toList()), a.calibratedEdge, a.bucket,
        a.unknownFeatures, a.staleMark, a.atMs,
        a.prediction8076?.let { CortexLedger7885.PredictionSnapshot8076(it.preds.copyOf(), it.pWins.copyOf(), it.base, it.baseWin) },
        a.tailEv8324, a.scoreVersion8372, a.calibrationInput8372)

    private fun detachPending8386(p: Pending): Pending = Pending(detachAssessment8386(p.a), p.legacyAdmitted, p.atMs,
        p.vetoRule, p.source, p.setup7961, p.fired8094.toList()).also {
        it.selected8153 = p.selected8153; it.lateRead8169 = p.lateRead8169; it.decisionMode8196 = p.decisionMode8196
        it.decisionReason8196 = p.decisionReason8196; it.context8372 = p.context8372 // immutable strings and booleans
        it.outcomeKind8394 = p.outcomeKind8394; it.outcomeDueAtMs8394 = p.outcomeDueAtMs8394
    }

    private fun detachGraded8386(g: Graded8006): Graded8006 = Graded8006(detachPending8386(g.p),
        CortexLedger7885.GradeTrace8006(g.trace.preds.copyOf(), g.trace.pWins.copyOf(), g.trace.base, g.trace.baseWin,
            g.trace.scales?.copyOf(), g.trace.scoreAt?.copyOf(), g.trace.shadowNet8404), g.net, g.gross, g.atMs,
        g.bookScales?.copyOf(), g.auxScales?.copyOf(), g.calStamp8322).also {
        it.witnessed8324 = g.witnessed8324; it.jointTrace8372 = g.jointTrace8372.toList()
    }

    /** Caller holds the monitor only while detaching state, not while encoding. */
    private fun pendingSnapshot8076(): () -> String = pendingSnapshot8076(null)

    private fun pendingSnapshot8076(episodes8401: Set<String>?): () -> String = pendingSnapshot8076(episodes8401, null)

    private fun pendingSnapshot8076(episodes8401: Set<String>?, generation8403: Long?): () -> String {
        val now8144 = System.currentTimeMillis()
        val ordinary = if (episodes8401 == null) pending.entries
            .filter { persistablePending8144(it.value.atMs, now8144) }.sortedByDescending { it.value.atMs }.take(PENDING_PERSIST_MAX)
            else emptyList()
        val forwardRows8401 = episodes8401?.mapNotNull { id ->
            val key = id.substringBeforeLast('|')
            val at = id.substringAfterLast('|').toLongOrNull()
            val p = pending[key]?.takeIf { it.atMs == at } ?: pairedPending8401[id] ?: graded8006[id]?.p
            p?.let { Triple(key, detachPending8386(it), false) }
        }.orEmpty()
        val rows8372 = ordinary.map { Triple(it.key, detachPending8386(it.value), false) } + forwardRows8401 +
            (if (episodes8401 == null) shadowPending8372.entries.map { Triple(it.key, detachPending8386(it.value), true) } else emptyList())
        val gradedRows = if (episodes8401 == null) graded8006.entries.sortedByDescending { it.value.atMs }.take(GRADED_PERSIST_MAX_8013)
            .map { it.key to detachGraded8386(it.value) }
            else emptyList() // A companion preserves predictions, never imports gradients without their atomic ledger checkpoint.
        val missing8401 = episodes8401.orEmpty() - rows8372.map { "${it.first}|${it.second.atMs}" }.toSet() - gradedRows.map { it.first }.toSet()
        val bounds = if (episodes8401 == null) positionReads8076.snapshot().map { it.copy(read = detachAssessment8386(it.read)) } else emptyList()
        val sealRows8395 = if (episodes8401 == null) entrySeals8395.snapshot() else emptyList()
        val outcomes = if (episodes8401 == null) outcomeBook8372.detached8386() else null
        val archive = if (episodes8401 == null) traceArchive8372.detached8386(now8144) else null
        return {
            val dict = LinkedHashMap<String, Int>()
            val arr = StringBuilder("[")
            rows8372.forEach { (k, p, shadow8372) ->
                val a = p.a
                val ids = org.json.JSONArray()
                val xs = org.json.JSONArray()
                val pr = org.json.JSONArray(); val pw = org.json.JSONArray(); val frozenEdges8372 = org.json.JSONArray()
                for (i in a.ids.indices) {
                    val r = a.raws.getOrNull(i) ?: continue
                    if (r.isFinite()) {
                        ids.put(dict.getOrPut(a.ids[i]) { dict.size }); xs.put(jsonNumber(r))
                        pr.put(jsonNumber(a.prediction8076?.preds?.getOrNull(i)))
                        pw.put(jsonNumber(a.prediction8076?.pWins?.getOrNull(i)))
                        frozenEdges8372.put(jsonNumbers(a.edges.getOrNull(i)))
                    }
                }
                // All decision-time context must survive a process restart unchanged.
                val ctx8196 = p.context8372?.let { c ->
                    org.json.JSONObject().put("timing", c.timing).put("cost", c.costBand)
                        .put("cohort", c.cohort).put("latency", c.latencyMeasured)
                        .put("executable", c.executable)
                } ?: org.json.JSONObject.NULL
                if (arr.length > 1) arr.append(',')
                arr.append(
                    org.json.JSONObject().put("k", k).put("l", a.lane).put("r", a.runnerLane).put("g", a.regime)
                        .put("b", a.bucket.ordinal).put("e", jsonNumber(a.fused.edgePct)).put("m", jsonNumber(a.fused.laneMean))
                        .put("ce", jsonNumber(a.calibratedEdge)).put("adm", p.legacyAdmitted).put("at", p.atMs)
                        .put("v", p.vetoRule.orEmpty()).put("s", p.source).put("su", p.setup7961).put("ep", PERSIST_KEY).put("i", ids).put("x", xs)
                        .put("pr8076", pr).put("pw8076", pw)
                        .put("base8076", jsonNumber(a.prediction8076?.base)).put("bw8076", jsonNumber(a.prediction8076?.baseWin))
                        .put("lr8169", p.lateRead8169)
                        .put("mode8196", p.decisionMode8196)
                        .put("outcomeKind8394", p.outcomeKind8394).put("outcomeDueAtMs8394", p.outcomeDueAtMs8394)
                        .put("reason8196", p.decisionReason8196.orEmpty())
                        .put("selected8196", p.selected8153)
                        .put("fired8196", org.json.JSONArray(p.fired8094))
                        .put("ctx8196", ctx8196).put("shadow8372", shadow8372)
                        .put("aat8372", a.atMs).put("sv8372", a.scoreVersion8372)
                        .put("staleMark8404", a.staleMark)
                        .put("ee8372", frozenEdges8372).put("provenance8372", provenance8372(a).encode()),
                )
            }
            // V5.0.8013 — graded decisions held for progressive revision ride along (newest decisions first),
            // so a 5 h / 24 h / later re-grade still reaches the voters after an app restart.
            val garr = StringBuilder("[")
            gradedRows.forEach { (k, g) ->
                if (garr.length > 1) garr.append(',')
                garr.append(encodeGraded8372(k, g, dict))
            }
            val seals8395 = StringBuilder("[")
            sealRows8395.forEach { (rk, e, v) ->
                if (seals8395.length > 1) seals8395.append(',')
                val o = e.read?.let { encodeEntryRead8076(it) } ?: org.json.JSONObject()
                seals8395.append(o.put("rk", rk).put("v", v).put("sat", e.sealedAtMs))
            }
            val bound = StringBuilder("[")
            bounds.forEach { b ->
                if (bound.length > 1) bound.append(',')
                bound.append(encodeEntryRead8076(b.read).put("pid", b.positionId).put("mint", b.mint).put("mode", b.mode))
            }
            buildString {
                append("{\"dict\":").append(org.json.JSONArray(dict.keys.toList()))
                append(",\"p\":").append(arr.append(']'))
                append(",\"g8013\":").append(garr.append(']'))
                append(",\"bound8076\":").append(bound.append(']'))
                append(",\"seal8395\":").append(seals8395.append(']'))
                append(",\"outcomes8372\":").append(outcomes?.encode() ?: "null")
                append(",\"cold8372\":").append(archive?.snapshot(now8144) ?: "null")
                append(",\"unpaired8401\":").append(org.json.JSONArray(missing8401.toList()))
                generation8403?.let { append(",\"snapshotGeneration8386\":").append(it) }
                append('}')
            }
        }
    }

    private val pairedPending8401 = java.util.concurrent.ConcurrentHashMap<String, Pending>()

    /** The labeler stores this exact episode subset in the same transaction as its observations. */
    fun checkpointForward8401(episodes: Set<String>): String {
        ensureLoaded()
        check(loaded) { "Cortex not restored; retain prior paired checkpoint" }
        val encode = synchronized(this) { pendingSnapshot8076(episodes) }
        return ForwardCheckpoint8401.pack(encode())
    }

    fun restoreForward8401(raw: String): Boolean {
        ensureLoaded()
        if (!loaded) return false
        val unpacked = ForwardCheckpoint8401.unpack(raw)
        synchronized(this) { restorePending(unpacked, System.currentTimeMillis(), paired8401 = true) }
        val missing = org.json.JSONObject(unpacked).optJSONArray("unpaired8401")
        if (missing != null && missing.length() > 0) counters.computeIfAbsent("FORWARD_CHECKPOINT_ORIGINAL_UNAVAILABLE_8401") { AtomicLong(0) }.addAndGet(missing.length().toLong())
        return true
    }

    /** Exact sparse prediction/weight provenance, shared by hot checkpoints and the bounded compressed archive. */
    private fun encodeGraded8372(key: String, g: Graded8006, dict: MutableMap<String, Int>): org.json.JSONObject {
        val a = g.p.a
        val ids = org.json.JSONArray(); val xs = org.json.JSONArray(); val pr = org.json.JSONArray(); val pw = org.json.JSONArray()
        val rs = org.json.JSONArray(); val sc = org.json.JSONArray(); val ee = org.json.JSONArray()
        for (i in a.ids.indices) {
            val raw = a.raws.getOrNull(i) ?: continue
            if (!raw.isFinite()) continue
            ids.put(dict.getOrPut(a.ids[i]) { dict.size }); xs.put(jsonNumber(raw)); ee.put(jsonNumbers(a.edges.getOrNull(i)))
            pr.put(jsonNumber(g.trace.preds.getOrNull(i))); pw.put(jsonNumber(g.trace.pWins.getOrNull(i)))
            sc.put(g.trace.scoreAt?.getOrNull(i) ?: -1)
            repeat(4) { rs.put(jsonNumber(g.trace.scales?.getOrNull(i * 4 + it))) }
        }
        repeat(3) { rs.put(jsonNumber(g.trace.scales?.getOrNull(a.ids.size * 4 + it))) }
        return org.json.JSONObject().put("k", key).put("l", a.lane).put("g", a.regime).put("b", a.bucket.ordinal)
            .put("adm", g.p.legacyAdmitted).put("at", g.atMs).put("v", g.p.vetoRule.orEmpty()).put("su", g.p.setup7961)
            .put("outcomeKind8394", g.p.outcomeKind8394).put("outcomeDueAtMs8394", g.p.outcomeDueAtMs8394)
            .put("s", g.p.source).put("mode8372", g.p.decisionMode8196).put("reason8372", g.p.decisionReason8196.orEmpty())
            .put("selected8372", g.p.selected8153).put("fired8372", org.json.JSONArray(g.p.fired8094))
            .put("base", jsonNumber(g.trace.base)).put("bw", jsonNumber(g.trace.baseWin)).put("n", jsonNumber(g.net)).put("gr", jsonNumber(g.gross))
            .put("r", a.runnerLane).put("e", jsonNumber(a.fused.edgePct)).put("m", jsonNumber(a.fused.laneMean))
            .put("ce8372", jsonNumber(a.calibratedEdge)).put("sv8372", a.scoreVersion8372).put("aat8372", a.atMs)
            .put("provenance8372", provenance8372(a).encode())
            .put("cal", jsonNumber(g.calStamp8322)).put("w", g.witnessed8324).put("shadowNet8404", g.trace.shadowNet8404)
            .put("jointTrace8372", org.json.JSONArray().also { rows -> g.jointTrace8372.forEach { c ->
                rows.put(org.json.JSONArray().put(c.key).put(c.generation).put(jsonNumber(c.scale)))
            } })
            .put("i", ids).put("x", xs).put("ee8372", ee).put("pr", pr).put("pw", pw)
            .put("rs", rs).put("sc", sc).put("bs", jsonNumbers(g.bookScales)).put("aux", jsonNumbers(g.auxScales))
    }

    private fun jsonNumber(value: Double?): Any = value?.takeIf { it.isFinite() } ?: org.json.JSONObject.NULL
    private fun jsonNumbers(values: DoubleArray?): org.json.JSONArray = org.json.JSONArray().also { a ->
        values?.forEach { a.put(jsonNumber(it)) }
    }
    private fun readNumbers(o: org.json.JSONObject, key: String): DoubleArray? = o.optJSONArray(key)?.let { a ->
        if (a.length() == 0) null else DoubleArray(a.length()) { a.optDouble(it, Double.NaN) }
    }

    /** Caller holds the lock (ensureLoaded). */
    private fun restorePending(raw: String?, nowMs: Long) = restorePending(raw, nowMs, false)

    private fun restorePending(raw: String?, nowMs: Long, paired8401: Boolean) {
        val root = org.json.JSONObject(raw ?: return)
        val jd = root.optJSONArray("dict") ?: return
        val dict = List(jd.length()) { jd.optString(it) }
        val arr = root.optJSONArray("p") ?: return
        val buckets = CortexScoreboard7885.Bucket.values()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val k = o.optString("k")
            val at = o.optLong("at")
            val shadow8372 = o.optBoolean("shadow8372", false)
            if (k.isBlank() || (if (shadow8372) shadowPending8372.containsKey(k)
                else if (paired8401) pending[k]?.atMs == at || pairedPending8401.containsKey("$k|$at") else pending.containsKey(k)) ||
                nowMs - at > (if (shadow8372) GRADED_KEEP_MS_8013 else PENDING_TTL_MS) || at > nowMs) continue
            // V5.0.8187 — a decision past the labeler's reach (it keeps an observation 70 min at most) cannot be graded: it is
            // not restored as "pending" (5.0.8175 counted 626 restored, 608 of them pruned unlabelled within a minute).
            if (!shadow8372 && !persistablePending8144(at, nowMs)) { inc("PENDING_RESTORE_PAST_LABEL_8187"); continue }
            // V5.0.7948 — a decision's bucket was assigned by the ledger of its build. One
            // saved before the 7947 label epoch was bucketed on 60/240-minute, spike-blind
            // grades and would be filed into books graded on the 5-minute read.
            if (o.optString("ep") != PERSIST_KEY) { inc("PENDING_EPOCH_DROPPED_7948"); continue }
            val ids = ArrayList<String>()
            val edges = ArrayList<DoubleArray>()
            val raws = ArrayList<Double>()
            val preds = ArrayList<Double>(); val wins = ArrayList<Double>()
            val ji = o.optJSONArray("i") ?: continue
            val jx = o.optJSONArray("x") ?: continue
            val jp = o.optJSONArray("pr8076"); val jw = o.optJSONArray("pw8076")
            for (j in 0 until minOf(ji.length(), jx.length())) {
                val id = dict.getOrNull(ji.optInt(j, -1)) ?: continue
                val frozenEdges = o.optJSONArray("ee8372")?.optJSONArray(j)
                val e = frozenEdges?.let { DoubleArray(it.length()) { n -> it.optDouble(n) } } ?: CortexVoters7885.edgesFor(id) ?: continue
                ids.add(id); edges.add(e); raws.add(jx.optDouble(j))
                preds.add(jp?.optDouble(j, Double.NaN) ?: Double.NaN)
                wins.add(jw?.optDouble(j, Double.NaN) ?: Double.NaN)
            }
            val b = buckets.getOrNull(o.optInt("b", 1)) ?: CortexScoreboard7885.Bucket.NEUTRAL
            val pv8372 = CortexAssessmentProvenance8372.decode(o.optJSONObject("provenance8372"))
            val fused = CortexLedger7885.Fused(pv8372?.rawEdge ?: o.optDouble("e", 0.0), 0.0, pv8372?.laneMean ?: o.optDouble("m", 0.0), 0.0, 0.0, 0.0, 0.0, emptyList(), laneMeanAll8325 = pv8372?.laneMeanAll ?: Double.NaN)
            val a = Assessment(o.optString("l"), o.optBoolean("r"), raws.toDoubleArray(), ids, edges, o.optString("g"),
                fused, o.optDouble("ce", 0.0), b, 0, o.optBoolean("staleMark8404", false), o.optLong("aat8372", at),
                CortexLedger7885.PredictionSnapshot8076(preds.toDoubleArray(), wins.toDoubleArray(),
                    o.optDouble("base8076", Double.NaN), o.optDouble("bw8076", Double.NaN)), scoreVersion8372 = pv8372?.scoreVersion ?: o.optInt("sv8372", 0),
                    calibrationInput8372 = pv8372?.calibrationInput ?: Double.NaN)
            val fired8196 = o.optJSONArray("fired8196")?.let { arr8196 ->
                (0 until arr8196.length()).map { arr8196.optString(it) }.filter { it.isNotBlank() }
            } ?: emptyList()
            val restored8372 = Pending(a, o.optBoolean("adm"), at, o.optString("v").ifBlank { null }, o.optString("s"), o.optString("su"), fired8196).also {
                it.lateRead8169 = o.optBoolean("lr8169", false)
                it.decisionMode8196 = o.optString("mode8196", "UNKNOWN").takeIf { m -> m == "LIVE" || m == "PAPER" } ?: "UNKNOWN"
                it.decisionReason8196 = o.optString("reason8196").ifBlank { null }
                it.selected8153 = o.optString("selected8196")
                it.outcomeKind8394 = o.optString("outcomeKind8394", "FORWARD")
                it.outcomeDueAtMs8394 = o.optLong("outcomeDueAtMs8394", at + 5 * 60_000L)
            }
            if (shadow8372) shadowPending8372[k] = restored8372
            else if (paired8401 && pending.containsKey(k)) pairedPending8401["$k|$at"] = restored8372
            else pending[k] = restored8372
            o.optJSONObject("ctx8196")?.let { c ->
                val ctx = DecisionCtx8144(c.optString("timing"), c.optString("cost"), c.optString("cohort"), c.optBoolean("latency"), c.optBoolean("executable"))
                restored8372.context8372 = ctx
                if (shadow8372) shadowCtx8372[k] = ctx else if (pending[k] === restored8372) pendingCtx8144[k] = ctx
            }
            if (pending[k]?.decisionMode8196 == "UNKNOWN") inc("PENDING_RESTORED_UNVERIFIED_MODE_8196")
            inc("PENDING_RESTORED")
        }
        root.optJSONObject("outcomes8372")?.let { outcomeBook8372.decode(it) }
        root.optJSONObject("cold8372")?.let { traceArchive8372.restore(it, nowMs) }
        restoreGraded8013(root, dict, nowMs)
        root.optJSONArray("seal8395")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val rk = o.optString("rk"); val v = o.optLong("v"); val sat = o.optLong("sat")
                if (rk.isBlank() || v <= 0L || nowMs - sat > SEAL_MAX_AGE_MS_8395) continue
                entrySeals8395.restore(rk, v, if (o.has("ids")) decodeEntryRead8076(o) else null, sat)
            }
        }
        root.optJSONArray("bound8076")?.let { bound ->
            for (i in 0 until bound.length()) {
                val o = bound.optJSONObject(i) ?: continue
                val a = decodeEntryRead8076(o) ?: continue
                positionReads8076.bind(o.optString("pid"), o.optString("mint"), o.optString("mode"), a)
            }
        }
    }

    /** V5.0.8013 — graded decisions still on the progressive schedule, back after a restart. */
    private fun restoreGraded8013(root: org.json.JSONObject, dict: List<String>, nowMs: Long) {
        val garr = root.optJSONArray("g8013") ?: return
        for (i in 0 until garr.length()) {
            val o = garr.optJSONObject(i) ?: continue
            val (id, g) = decodeGraded8372(o, dict, nowMs) ?: continue
            if (graded8006.containsKey(id)) continue
            graded8006[id] = g
            inc("GRADED_RESTORED_8013")
        }
    }

    private fun decodeGraded8372(o: org.json.JSONObject, dict: List<String>, nowMs: Long): Pair<String, Graded8006>? {
        val key = o.optString("k"); val at = o.optLong("at")
        if (key.isBlank() || at <= 0L || nowMs - at !in 0L..GRADED_KEEP_MS_8013) return null
        val ids = ArrayList<String>(); val edges = ArrayList<DoubleArray>()
        val raws = ArrayList<Double>(); val preds = ArrayList<Double>(); val pws = ArrayList<Double>()
        val scales = ArrayList<Double>(); val counts = ArrayList<Int>()
        val rs = o.optJSONArray("rs"); val sc = o.optJSONArray("sc")
        val ji = o.optJSONArray("i") ?: return null; val jx = o.optJSONArray("x") ?: return null
        val jp = o.optJSONArray("pr") ?: return null; val jw = o.optJSONArray("pw") ?: return null
        for (j in 0 until minOf(ji.length(), jx.length(), jp.length(), jw.length())) {
            val id = dict.getOrNull(ji.optInt(j, -1)) ?: continue
            val frozenEdges = o.optJSONArray("ee8372")?.optJSONArray(j)
            val e = frozenEdges?.let { DoubleArray(it.length()) { n -> it.optDouble(n) } } ?: CortexVoters7885.edgesFor(id) ?: continue
            ids.add(id); edges.add(e); raws.add(jx.optDouble(j)); preds.add(jp.optDouble(j)); pws.add(jw.optDouble(j))
            counts.add(sc?.optInt(j, -1) ?: -1)
            repeat(4) { scales.add(rs?.optDouble(j * 4 + it, Double.NaN) ?: Double.NaN) }
        }
        val b = CortexScoreboard7885.Bucket.values().getOrNull(o.optInt("b", 1)) ?: CortexScoreboard7885.Bucket.NEUTRAL
        val pv8372 = CortexAssessmentProvenance8372.decode(o.optJSONObject("provenance8372"))
        val fused = CortexLedger7885.Fused(pv8372?.rawEdge ?: o.optDouble("e", 0.0), 0.0, pv8372?.laneMean ?: o.optDouble("m", 0.0), 0.0, 0.0, 0.0, 0.0, emptyList(), laneMeanAll8325 = pv8372?.laneMeanAll ?: Double.NaN)
        val a = Assessment(o.optString("l"), o.optBoolean("r", false), raws.toDoubleArray(), ids, edges, o.optString("g"),
            fused, pv8372?.calibratedEdge ?: o.optDouble("ce8372", 0.0), b, 0, false, o.optLong("aat8372", at), scoreVersion8372 = pv8372?.scoreVersion ?: o.optInt("sv8372", 0),
            calibrationInput8372 = pv8372?.calibrationInput ?: Double.NaN)
        val fired = o.optJSONArray("fired8372")?.let { f -> (0 until f.length()).map { f.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
        val p = Pending(a, o.optBoolean("adm"), at, o.optString("v").ifBlank { null }, o.optString("s"), o.optString("su"), fired).also {
            it.decisionMode8196 = o.optString("mode8372", "UNKNOWN")
            it.decisionReason8196 = o.optString("reason8372").ifBlank { null }
            it.selected8153 = o.optString("selected8372")
            it.outcomeKind8394 = o.optString("outcomeKind8394", "FORWARD")
            it.outcomeDueAtMs8394 = o.optLong("outcomeDueAtMs8394", at + 5 * 60_000L)
        }
        repeat(3) { scales.add(rs?.optDouble(ji.length() * 4 + it, Double.NaN) ?: Double.NaN) }
        val trace = CortexLedger7885.GradeTrace8006(preds.toDoubleArray(), pws.toDoubleArray(), o.optDouble("base", 0.0), o.optDouble("bw", 0.5),
            if (rs != null) scales.toDoubleArray() else null, if (sc != null) counts.toIntArray() else null, o.optBoolean("shadowNet8404", false))
        val id = if (key.endsWith("|$at")) key else "$key|$at"
        return id to Graded8006(p, trace, o.optDouble("n", Double.NaN), o.optDouble("gr", Double.NaN), at,
            readNumbers(o, "bs"), readNumbers(o, "aux"), o.optDouble("cal", Double.NaN)).also {
            it.witnessed8324 = o.optBoolean("w", false)
            it.jointTrace8372 = o.optJSONArray("jointTrace8372")?.let { rows ->
                (0 until rows.length()).mapNotNull { index ->
                    val row = rows.optJSONArray(index) ?: return@mapNotNull null
                    val key8372 = row.optString(0); val generation = row.optLong(1); val scale = row.optDouble(2, Double.NaN)
                    if (key8372.isBlank() || generation <= 0L || !scale.isFinite()) null else JointContribution8372(key8372, generation, scale)
                }
            }.orEmpty()
        }
    }

    /**
     * V5.0.8187 — ForwardReturnLabeler7731: the 5-minute label of (mint, labelLane) will never come (no mark inside its window,
     * or the observation was dropped as a basis error). The decision is filed unresolved in its cohort NOW and leaves the
     * pending set (it used to linger unlabelled until the 90-minute prune, invisible to every counter).
     */
    fun onLabelLost8187(mint: String, labelLane: String, reason: String, decisionAtMs8371: Long = 0L) {
        ensureLoaded()
        val key = "$mint|${labelLane.trim().uppercase()}"
        if (decisionAtMs8371 > 0L) synchronized(this) {
            captureOwners8397.terminal(key, decisionAtMs8371)
            deferredLabels8397.remove("$key|$decisionAtMs8371")
        }
        val p = pending[key]?.takeIf { decisionAtMs8371 <= 0L || it.atMs == decisionAtMs8371 }
            ?: pairedPending8401["$key|$decisionAtMs8371"] ?: pending[key] ?: return
        if (decisionAtMs8371 > 0L && p.atMs != decisionAtMs8371) {
            inc("LABEL_LOST_EPISODE_MISMATCH_8371")
            return
        }
        if (!pending.remove(key, p) && !pairedPending8401.remove("$key|${p.atMs}", p)) return
        synchronized(this) {
            captureOwners8397.terminal(key, p.atMs)
            val id = "$key|${p.atMs}"
            val ctx = p.context8372
            if (ctx != null) pendingCtx8144.remove(key, ctx)
            if (gradeBook8144.decision(id) == null) gradeBook8144.addDecision(newDecision8144(id, key, p, ctx, verdict8144(key, p, ctx)))
            gradeBook8144.markLost(id)
        }
        try { LanePlaybook7907.dropPending8144(mint, labelLane, decisionAtMs8372 = p.atMs) } catch (_: Throwable) {}
        inc("LABEL_LOST_${reason.take(24)}_8187")
    }

    private val observationTerminals8395 = java.util.concurrent.ConcurrentHashMap<String,String>()
    private const val OBSERVATION_TERMINAL_PREFIX_8395 = "CORTEX_OBSERVATION_TERMINAL_8395|"

    /** Durable labeler outbox retries this exact episode until its missing-coverage receipt is checkpointed. */
    fun observationUnavailable8395(mint: String, lane: String, atMs: Long, reason: String): Boolean {
        ensureLoaded()
        if (!loaded || !LearningPersistence.ready() || atMs <= 0L) return false
        val id = "$mint|${canon(lane)}|$atMs"
        val receipt = OBSERVATION_TERMINAL_PREFIX_8395 + id
        if (LearningPersistence.load(receipt) != null) return true
        onLabelLost8187(mint,lane,reason,atMs)
        synchronized(this) {
            val original = gradeBook8144.decision(id)
            val disposition = if (original == null) "ORIGINAL_PREDICTION_UNAVAILABLE"
                else if (original.executablePct.isFinite()) "EARLIER_ACCEPTED_OUTCOME_PRESERVED" else "UNAVAILABLE_COVERAGE_RETAINED"
            observationTerminals8395.putIfAbsent(receipt, org.json.JSONObject().put("decisionId",id).put("reason",reason)
                .put("disposition",disposition).put("at",atMs).toString())
        }
        return false
    }

    private const val FORWARD_RECEIPT_8404 = "CORTEX_FORWARD_RECEIPT_8404|"
    private val forwardReady8404 = ConcurrentHashMap<String, String>()
    private val forwardLearned8404 = ConcurrentHashMap.newKeySet<String>()
    private val forwardDelivering8404 = ConcurrentHashMap.newKeySet<String>()

    /** Null means capture is still in flight; a durable outcome must not freeze a temporary cache miss. */
    fun freezeForwardOutcome8404(mint: String, lane: String, atMs: Long): String? {
        ensureLoaded()
        if (!loaded) return null
        val key = "$mint|${lane.trim().uppercase()}"
        val encode = synchronized(this) {
            if (captureOwners8397.awaiting(key, atMs)) return null
            pendingSnapshot8076(setOf("$key|$atMs"))
        }
        return ForwardCheckpoint8401.pack(encode())
    }

    /** Called from the producer's durable outbox. A scheduling/in-memory grade is never an acknowledgement. */
    fun deliverForwardOutcome8404(d: ForwardOutcomeDelivery8404): Boolean {
        ensureLoaded()
        if (!loaded || !LearningPersistence.ready()) return false
        val id = d.id; val receipt = FORWARD_RECEIPT_8404 + d.deliveryId
        if (LearningPersistence.load(receipt) != null) return true
        if (forwardReady8404.containsKey(receipt) || !forwardDelivering8404.add(id)) return false
        try {
            val key = "${d.mint}|${d.lane}"
            val original = synchronized(this) {
                val existing = pending[key]?.takeIf { it.atMs == d.atMs } ?: pairedPending8401[id]
                if (existing == null) {
                    // This frozen prediction was already owned by a durable outcome. Delivery delay must not
                    // expire it as ordinary pending work; the embedded proof retains the real observation time.
                    restorePending(ForwardCheckpoint8401.unpack(d.frozenPrediction), d.atMs, paired8401 = true)
                }
                pending[key]?.takeIf { it.atMs == d.atMs } ?: pairedPending8401[id] ?: gradedTrace8372(id)?.p
            }
            if (d.revision && synchronized(this) { gradeBook8144.decision(id) == null } &&
                LearningPersistence.load(FORWARD_RECEIPT_8404 + id) == null) return false // first label still in delivery
            if (original == null) {
                forwardReady8404[receipt] = org.json.JSONObject().put("id", id)
                    .put("disposition", "ORIGINAL_PREDICTION_UNAVAILABLE").toString()
                inc("FORWARD_ORIGINAL_UNAVAILABLE_8404")
                return false
            }
            if (d.revision) reviseLabel8006(d.mint, d.lane, d.previousNet, d.net, d.previousGross, d.gross, d.witness, d.atMs, d.proof)
            else onLabel(d.mint, d.lane, 60, d.net, d.gross, d.family, d.flat, d.witness, d.atMs, d.proof)
            val record = synchronized(this) { gradeBook8144.decision(id)?.detached8386() }
            if (record == null) {
                if (durableTrainingKind8372("$key|${original.a.atMs}") != "SHADOW_EXIT") return false
                forwardReady8404[receipt] = org.json.JSONObject().put("id", id)
                    .put("disposition", "ALREADY_TRAINED_SHADOW").toString()
                return false
            }
            val learned = record.modelContribution8395 == id
            // A shadow contribution owns its own source acknowledgement. Never replace that reward
            // with the older forward value when the forward delivery arrives after the shadow exit.
            val sourceNet = synchronized(this) { gradedTrace8372(id)?.net } ?: record.observedNetPct
            if (learned && record.outcomeSource8372 != "SHADOW_EXIT" && !sourceOutcome8372(key, original, sourceNet, revision = true)) {
                inc("FORWARD_SOURCE_ACK_WAIT_8404")
                return false
            }
            synchronized(this) {
                forwardReady8404[receipt] = org.json.JSONObject().put("id", id)
                    .put("disposition", if (learned) "LEARNED" else "OBSERVED_ONLY")
                    .put("source", record.outcomeSource8372).toString()
                if (learned) forwardLearned8404.add(receipt)
                pending.remove(key, original); pairedPending8401.remove(id, original)
            }
            return false
        } finally { forwardDelivering8404.remove(id) }
    }

    /** ForwardReturnLabeler7731.tick: a horizon label booked for (mint, labelLane). */
    fun onLabel(mint: String, labelLane: String, horizonMin: Int, netPct: Double, grossPct: Double, fam8080: DoubleArray? = null,
                flat8322: Boolean = false, witness8324: Boolean = false, decisionAtMs8371: Long = 0L,
                proof8395: CortexOutcomeProof8395? = null) {
        if (horizonMin != 60 || !netPct.isFinite()) return
        ensureLoaded()
        val key = "$mint|${labelLane.trim().uppercase()}"
        val p = synchronized(this) {
            val captured = pending[key]?.takeIf { decisionAtMs8371 <= 0L || it.atMs == decisionAtMs8371 }
                ?: pairedPending8401["$key|$decisionAtMs8371"] ?: pending[key]
            if (decisionAtMs8371 > 0L && captured?.atMs != decisionAtMs8371 && captureOwners8397.awaiting(key, decisionAtMs8371)) {
                val id = "$key|$decisionAtMs8371"
                if (deferredLabels8397.containsKey(id) || deferredLabels8397.size < CAPTURE_QUEUE_HARD_8187 + 32) {
                    deferredLabels8397.putIfAbsent(id, DeferredLabel8397(mint, labelLane, decisionAtMs8371, netPct, grossPct,
                        fam8080?.copyOf(), flat8322, witness8324, proof8395))
                    inc("LABEL_WAITING_FOR_EXACT_CAPTURE_8397")
                } else inc("LABEL_CAPTURE_WAIT_FULL_8397")
                return
            }
            if (captured == null) { inc("LABEL_ORIGINAL_CAPTURE_MISSING_8397"); return }
            if (decisionAtMs8371 > 0L && captured.atMs != decisionAtMs8371) {
                inc("LABEL_EPISODE_MISMATCH_8371")
                return
            }
            if (!pending.remove(key, captured) && !pairedPending8401.remove("$key|${captured.atMs}", captured)) return
            captureOwners8397.terminal(key, captured.atMs)
            captured
        }
        // V5.0.7946 — every lane is graded at THE read (slot 60 = 5 minutes). Runner lanes
        // used to wait for the 4-hour label; with the spike tiers credited in the label,
        // a runner's first minutes carry its capture, and the Cortex learns 48x sooner.
        val id8372 = "$key|${p.atMs}"
        if (!gradingForward8372.add(id8372)) return
        try {
        if (durableTrainingKind8372(id8372) != null || durableTrainingKind8372("$key|${p.a.atMs}") == "SHADOW_EXIT" ||
            synchronized(this) { outcomeBook8372.forwardPlan(id8372).action == CortexOutcomeBook8372.Action.OBSERVE_ONLY ||
                gradeBook8144.decision(id8372)?.modelContribution8395 == id8372 || gradedTrace8372(id8372) != null }) {
            p.context8372?.let { pendingCtx8144.remove(key, it) }
            inc("FORWARD_AFTER_SHADOW_OBSERVED_ONLY_8372")
            return
        }
        // V5.0.8144 — every label is a grade record; only an executable decision, first of its coin's episode in this lane,
        // grades the voters, the bucket / setup / veto books and calibration (CortexLabelHygiene8144).
        val lat8144 = latencyMeasured8144(p.a.lane)
        val (validation8395, cap8187) = synchronized(this) {
            recordLabel8144(key, p, netPct, grossPct, lat8144, proof8395) to labelCapFor8187(key, p)
        }
        val hyg8144 = validation8395.first
        val accepted8395 = validation8395.second.accepted
        val witnessed8395 = proof8395?.witnessed8395() == true
        // V5.0.8153 — a label first priced after the fill-latency window kept the unbuyable decision price: observed only.
        val late8153 = lateFirstPrice8153(mint, labelLane, key, p.atMs)
        // V5.0.8169 — voters read after the label started saw part of the graded move: observed record only, no books.
        val lateRead8169 = p.lateRead8169 && hyg8144.authority && !late8153
        // V5.0.8187 — a late voter read is observed only but not "unresolved" (its label came): own flag, episode reopened.
        if (lateRead8169) synchronized(this) { gradeBook8144.markLateRead8187("$key|${p.atMs}") }
        // V5.0.8322 — a flat five-minute mark (no price other than the entry ever seen) is observed only: it taught every
        // voter and cohort "lost the round trip" on a frozen / repeated mark (the 8319 rebase down=859 vs up=99 split).
        val flat = CortexFlatMark8322.observedOnly8322(flat8322, hyg8144.authority, late8153, lateRead8169)
        if (flat) synchronized(this) { gradeBook8144.markFlat8322("$key|${p.atMs}") }
        if (!accepted8395 || !hyg8144.authority || late8153 || lateRead8169 || flat) {
            try { LanePlaybook7907.dropPending8144(mint, labelLane, decisionAtMs8372 = p.atMs) } catch (_: Throwable) {}
            if (p.a.staleMark) inc("LABEL_HYGIENE_STALE_MARK_8404")
            else if (flat) inc("LABEL_HYGIENE_FLAT_MARK_8322")
            else if (lateRead8169) inc("LABEL_HYGIENE_LATE_VOTER_READ_8169")
            else if (late8153 && hyg8144.authority) inc("LABEL_HYGIENE_LATE_FIRST_PRICE_8153") else inc("LABEL_HYGIENE_${hyg8144.tag}_8144")
            synchronized(this) { noteObservedOnly8153(key, p.atMs) }
            return
        }
        // V5.0.8322 — every book below takes the RAW label (owner directive 8072: no win caps). 8187 clamped it to the cohort's
        // per-label cap here, so the runner tail the pond pays on (+744..+1342% runners) never reached a book. A label above
        // the plausibility cap is only flagged (LABEL_ABOVE_CAP_8322); the robust reads remain as diagnostics.
        val net8187 = netPct
        val gross8187 = grossPct
        val fam8187 = fam8080
        if (netPct > cap8187) inc("LABEL_ABOVE_CAP_8322")
        try { LanePlaybook7907.onLabel(mint, labelLane, net8187, gross8187, fam8187, p.setup7961, decisionAtMs8372 = p.atMs) } catch (_: Throwable) {}
        gradeDecision8372(key, p, net8187, gross8187, witnessed8395)
        } finally { gradingForward8372.remove(id8372) }
    }

    /** Every gradeable YES and NO teaches discovery; each source contribution has one token-episode owner. */
    private fun sourceOutcome8372(key: String, p: Pending, netPct: Double, revision: Boolean): Boolean {
        if (p.source.isBlank() || !netPct.isFinite()) return true
        val mint = key.substringBeforeLast('|')
        val tokenEpisode = "$mint|${Math.floorDiv(p.atMs, CortexLabelHygiene8144.EPISODE_MS_8144)}"
        val decisionId = "$key|${p.atMs}"
        return try {
            // Replayed delivery may have committed the model before the separate source book. Insertion is idempotent.
            com.lifecyclebot.engine.ScannerSourceBrain.recordDecisionOutcome8372(
                p.source, tokenEpisode, decisionId, p.decisionMode8196, netPct.coerceAtLeast(-100.0), p.atMs)
            if (revision) com.lifecyclebot.engine.ScannerSourceBrain.reviseDecisionOutcome8372(
                p.source, tokenEpisode, decisionId, p.decisionMode8196, netPct.coerceAtLeast(-100.0), p.atMs)
            com.lifecyclebot.engine.ScannerSourceBrain.decisionDeliveryAcknowledged8372(
                p.source, tokenEpisode, decisionId, p.decisionMode8196, netPct.coerceAtLeast(-100.0), p.atMs)
        } catch (_: Throwable) { inc("SOURCE_OUTCOME_RETRY_NEEDED_8372"); false }
    }

    /** Shared learner mutation: no canonical trade, wallet, or realised-money book is touched. */
    private fun gradeDecision8372(key: String, p: Pending, net8187: Double, gross8187: Double, witness8324: Boolean) {
        sourceOutcome8372(key, p, net8187, revision = false)
        synchronized(this) { gradeModelsLocked8372(key, p, net8187, gross8187, witness8324) }
        afterGrade8372()
    }

    private fun afterGrade8372() {
        refreshExecutable8187(System.currentTimeMillis())
        graded.incrementAndGet()
        if (sincePersist.incrementAndGet() >= PERSIST_EVERY) { sincePersist.set(0); persist() }
    }

    /** A real admission may share a native-learning observation; it still needs its own exact immutable entry read. */
    fun noteExecutionAdmission8372(ts: TokenState, laneRaw: String, nowMs: Long): Boolean {
        val a = assessCache["${ts.mint}|${canon(laneRaw)}"]?.takeIf { nowMs - it.atMs in 0L..ASSESS_TTL_MS } ?: return false
        noteEntryRead(ts.mint, accountMode8077() == "PAPER", a, nowMs)
        return true
    }

    /**
     * A selected, model-filled exit learns the ORIGINAL token/lane prediction once. Both opinions are eligible;
     * unsafe/unpriced observations stay unlearnable. The receipt and model state commit atomically before ACK.
     */
    fun onShadowOutcome8372(mint: String, lane: String, assessmentAtMs: Long, netPct: Double, grossPct: Double,
                            setup: String, nowMs: Long, executable: Boolean): Boolean {
        if (!netPct.isFinite() || !grossPct.isFinite() || !executable || nowMs <= assessmentAtMs)
            return onShadowUnavailable8372(mint, lane, assessmentAtMs, "INVALID_EXECUTABLE_OUTCOME")
        ensureLoaded()
        if (!loaded || !LearningPersistence.ready()) return false
        val shadowId = "$mint|${canon(lane)}|$assessmentAtMs"
        val receipt = "CORTEX_SHADOW_RECEIPT_8372|$shadowId"
        if (LearningPersistence.load(receipt) != null) {
            synchronized(this) {
                shadowPending8372.remove(shadowId); shadowCtx8372.remove(shadowId)
                outcomeBook8372.retireAcknowledged8372(shadowId, trainingOwnershipDurable = true)
            }
            return true
        }
        var delivered: Pending? = null
        var first = false
        var targetId = ""
        var missingOriginal8372 = false
        synchronized(this) {
            val plan = outcomeBook8372.shadowPlan(shadowId)
            targetId = plan.decisionId
            if (gradingForward8372.contains(targetId)) return false
            if (plan.action == CortexOutcomeBook8372.Action.RETRY) {
                // A pre-upgrade/unsaved prediction cannot be reconstructed from its now-known winner or loser.
                inc("SHADOW_ORIGINAL_PREDICTION_MISSING_8372")
                missingOriginal8372 = true
                return@synchronized
            }
            if (plan.action == CortexOutcomeBook8372.Action.OBSERVE_ONLY) {
                if (outcomeBook8372.terminal8372(shadowId)?.learned == true)
                    delivered = shadowPending8372[shadowId] ?: gradedTrace8372(targetId)?.p
            } else {
                val original = shadowPending8372[shadowId] ?: return false
                val ctx = original.context8372
                val key = targetId.substringBeforeLast('|')
                val decisionAt = targetId.substringAfterLast('|').toLongOrNull() ?: return false
                val p = if (original.setup7961.isBlank() && setup.isNotBlank()) Pending(original.a, original.legacyAdmitted,
                    decisionAt, original.vetoRule, original.source, setup, original.fired8094).also {
                    it.selected8153 = original.selected8153.ifBlank { setup }; it.lateRead8169 = original.lateRead8169
                    it.decisionMode8196 = original.decisionMode8196; it.decisionReason8196 = original.decisionReason8196
                    it.context8372 = ctx
                } else original
                if (ctx?.executable != true || p.lateRead8169 || p.a.staleMark) {
                    outcomeBook8372.finishedShadow(shadowId, learned = false)
                    inc("SHADOW_UNEXECUTABLE_OBSERVED_ONLY_8372")
                } else if (plan.action == CortexOutcomeBook8372.Action.REVISE) {
                    val g = gradedTrace8372(targetId)
                    if (g != null && g.trace.scales != null && g.trace.scoreAt != null && g.bookScales != null && g.auxScales != null &&
                        gradeBook8144.shadowOutcome8372(targetId, netPct, grossPct, nowMs) &&
                        reviseModelsLocked8372(targetId, g, netPct, grossPct, false, shadowExit8372 = true)) {
                        outcomeBook8372.finishedShadow(shadowId, learned = true, trainedId = targetId)
                        delivered = g.p
                        inc("SHADOW_MODEL_REVISED_8372")
                    } else {
                        outcomeBook8372.finishedShadow(shadowId, learned = false)
                        inc("SHADOW_ORIGINAL_TRACE_UNAVAILABLE_8372")
                    }
                } else {
                    val existing = gradeBook8144.decision(targetId)
                    val verdict = existing?.let { CortexLabelHygiene8144.Verdict8144(it.executable, it.repeat) }
                        ?: verdict8144(key, p, ctx)
                    if (!verdict.authority || existing?.lateEntry8153 == true || existing?.lateRead8187 == true || existing?.flat8322 == true) {
                        outcomeBook8372.finishedShadow(shadowId, learned = false)
                        inc("SHADOW_HYGIENE_OBSERVED_ONLY_8372")
                    } else {
                        if (existing == null) gradeBook8144.addDecision(newDecision8144(targetId, key, p, ctx, verdict))
                        if (!gradeBook8144.shadowOutcome8372(targetId, netPct, grossPct, nowMs)) {
                            outcomeBook8372.finishedShadow(shadowId, learned = false)
                            inc("SHADOW_VALUE_OBSERVED_ONLY_8395")
                            return@synchronized
                        }
                        gradeModelsLocked8372(key, p, netPct, grossPct, false, shadowExit8372 = true)
                        outcomeBook8372.finishedShadow(shadowId, learned = true, trainedId = targetId)
                        delivered = p; first = true
                        inc("SHADOW_MODEL_LEARNED_8372")
                    }
                }
            }
            delivered?.let { shadowPending8372[shadowId] = it }
        }
        if (missingOriginal8372) return onShadowUnavailable8372(mint, lane, assessmentAtMs, "ORIGINAL_PREDICTION_MISSING")
        if (first) afterGrade8372() else if (delivered != null) refreshExecutable8187(nowMs)
        delivered?.let { p ->
            if (!sourceOutcome8372(targetId.substringBeforeLast('|'), p, netPct, revision = !first)) return false
            val playbookDelivered = try {
                val originalLabelLane8372 = targetId.substringBeforeLast('|').substringAfterLast('|')
                LanePlaybook7907.onShadowTerminal8372(mint, originalLabelLane8372, p.atMs, netPct, grossPct, nowMs)
            } catch (_: Throwable) { false }
            if (!playbookDelivered) { inc("SHADOW_PLAYBOOK_DELIVERY_RETRY_8372"); return false }
        }
        synchronized(this) { outcomeBook8372.terminal8372(shadowId)?.let { shadowReady8372[shadowId] = it } }
        // ExploreShadow flushes once after the whole tick. No full model snapshot for every individual exit.
        return false
    }

    /** A failed model fill or missing mark is missing evidence, never a fabricated win/loss or canonical trade. */
    fun onShadowUnavailable8372(mint: String, lane: String, assessmentAtMs: Long, reason: String): Boolean {
        ensureLoaded()
        if (!loaded || !LearningPersistence.ready()) return false
        val id = "$mint|${canon(lane)}|$assessmentAtMs"
        synchronized(this) {
            // Unavailable executable predictions must survive the receipt retiring their pending trace.
            shadowPending8372[id]?.let { p ->
                val target = outcomeBook8372.shadowPlan(id).decisionId.ifBlank { id }
                val key = target.substringBeforeLast('|')
                val v = verdict8144(key,p,p.context8372)
                if (v.authority && !p.lateRead8169 && !p.a.staleMark) {
                    if (gradeBook8144.decision(target)==null) gradeBook8144.addDecision(newDecision8144(target,key,p,p.context8372,v))
                    val awaitingForward8404 = pending[key]?.atMs == p.atMs || pairedPending8401.containsKey(target)
                    if (!awaitingForward8404 && gradeBook8144.markLost(target)) {
                        val unavailableAt8394 = System.currentTimeMillis()
                        p.outcomeDueAtMs8394 = minOf(p.outcomeDueAtMs8394, unavailableAt8394)
                        gradeBook8144.decision(target)?.let { it.outcomeKind8394 = "UNAVAILABLE"; it.outcomeDueAtMs8394 = p.outcomeDueAtMs8394 }
                    } // never overwrites an already observed outcome
                }
            }
            outcomeBook8372.finishedShadow(id, learned = false)
            inc("SHADOW_UNAVAILABLE_8372_${reason.take(32)}")
        }
        if (LearningPersistence.load("CORTEX_SHADOW_RECEIPT_8372|$id") != null) {
            synchronized(this) {
                shadowPending8372.remove(id); shadowCtx8372.remove(id)
                outcomeBook8372.retireAcknowledged8372(id, trainingOwnershipDurable = true)
            }
            return true
        }
        synchronized(this) {
            // A simulator ticket can survive an unsaved original prediction. Record missing evidence, never a reward.
            shadowReady8372[id] = outcomeBook8372.terminal8372(id)
                ?: CortexOutcomeBook8372.Terminal8372(id, "", "", false)
        }
        return false
    }

    /** A durable ownership marker is separate from an unavailable simulation receipt. Missing marks never become losses. */
    private fun durableTrainingKind8372(id: String): String? {
        durableTraining8372[id]?.let { return it }
        val raw = try { LearningPersistence.load(TRAINING_RECEIPT_8372 + id) } catch (_: Throwable) { null } ?: return null
        val kind = try { org.json.JSONObject(raw).optString("kind").takeIf { it == "SHADOW_EXIT" || it == "FORWARD" } } catch (_: Throwable) { null } ?: return null
        if (durableTraining8372.size >= 16_000) durableTraining8372.clear() // cache only; the exact receipt remains durable
        durableTraining8372[id] = kind
        return kind
    }

    /** One atomic model/ownership checkpoint per shadow tick; no trade, wallet or live-authority mutation. */
    // V5.0.8377 — every checkpoint re-encodes the WHOLE Cortex snapshot (ledger/board/calibration/real/vetoes/joint/grades,
    // ~20 MB resident) into JSON strings. 8376 did that on every shadow tick with any ready outcome, and died OOM at 512 MiB
    // after 3 h 47 m. A checkpoint now waits for SHADOW_FLUSH_MIN_BATCH_8377 ready outcomes or SHADOW_FLUSH_MAX_WAIT_MS_8377,
    // whichever is first. Receipts stay staged (not ACKed) until the checkpoint commits, so durability order is unchanged.
    internal const val SHADOW_FLUSH_MIN_BATCH_8377 = 64
    internal const val SHADOW_FLUSH_MAX_WAIT_MS_8377 = 10_000L
    @Volatile private var shadowFlushedAtMs8377 = 0L

    /** Pure: is a shadow checkpoint due for [ready] staged outcomes, [sinceMs] after the last one? */
    internal fun shadowFlushDue8377(ready: Int, sinceMs: Long): Boolean =
        ready > 0 && (ready >= SHADOW_FLUSH_MIN_BATCH_8377 || sinceMs >= SHADOW_FLUSH_MAX_WAIT_MS_8377)

    internal const val UNAVAILABLE_FLUSH_MIN_BATCH_8396 = 256
    internal const val UNAVAILABLE_FLUSH_MAX_WAIT_MS_8396 = 120_000L

    /**
     * V5.0.8396 — pure: every shadow checkpoint encodes the WHOLE Cortex snapshot (~35 MB of ledger/board/grade-book/
     * pending JSON). 8395's observation deadlines (R2) made "no outcome" terminals a continuous trickle, so the 10 s
     * learned-outcome cadence fired on them alone: heap 490/512 MB CRITICAL, 40 hard trims (16 ineffective), 19.5 %
     * main-thread stall. An unavailable terminal teaches nothing and is already durable in the labeler's outbox
     * (redelivered after a crash), so on its own it waits for a large batch or two minutes. A learned outcome keeps the
     * 8377 cadence and carries any staged unavailable terminals with it.
     */
    internal fun shadowFlushDue8396(learned: Int, unavailable: Int, sinceMs: Long): Boolean =
        if (learned > 0) shadowFlushDue8377(learned + unavailable, sinceMs)
        else unavailable > 0 && (unavailable >= UNAVAILABLE_FLUSH_MIN_BATCH_8396 || sinceMs >= UNAVAILABLE_FLUSH_MAX_WAIT_MS_8396)

    /** Unavailable simulation receipts are not learned outcomes and must use the unavailable cadence. */
    internal fun shadowFlushDue8402(terminals: Collection<CortexOutcomeBook8372.Terminal8372>,
                                   observationTerminals: Int, sinceMs: Long,
                                   forwardLearned: Int = 0, forwardReady: Int = 0): Boolean {
        val shadowLearned = terminals.count { it.learned }
        return shadowFlushDue8396(shadowLearned + forwardLearned,
            terminals.size - shadowLearned + observationTerminals + (forwardReady - forwardLearned).coerceAtLeast(0), sinceMs)
    }

    fun flushShadowOutcomes8372(): Boolean {
        if (shadowReady8372.isEmpty() && observationTerminals8395.isEmpty() && forwardReady8404.isEmpty()) return true
        val now8377 = System.currentTimeMillis()
        if (!shadowFlushDue8402(shadowReady8372.values.toList(), observationTerminals8395.size, now8377 - shadowFlushedAtMs8377,
                forwardLearned8404.size, forwardReady8404.size)) { inc("CORTEX_SHADOW_FLUSH_COALESCED_8377"); return true }
        if (!loaded || !LearningPersistence.ready()) return false
        // A label/shadow tick must keep observing prices while a multi-MB checkpoint runs.
        // Scheduling is not a durable ACK: producers still consult their exact stored receipt.
        enqueueCheckpoint8403(persistPool8094, shadowFlushRunning8372) {
            try { flushShadowOutcomesBlocking8403() }
            catch (_: Throwable) { inc("SHADOW_CHECKPOINT_WORKER_RETRY_8403") } // no ACK; next tick retries
        }
        return false
    }

    internal fun enqueueCheckpoint8403(executor: java.util.concurrent.Executor, running: java.util.concurrent.atomic.AtomicBoolean,
                                       checkpoint: () -> Unit): Boolean {
        if (!running.compareAndSet(false, true)) return false
        return try {
            executor.execute { try { checkpoint() } finally { running.set(false) } }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) { running.set(false); false }
    }

    private fun flushShadowOutcomesBlocking8403(): Boolean {
        shadowFlushedAtMs8377 = System.currentTimeMillis()
            val batch = shadowReady8372.entries.map { it.key to it.value }
            val unavailable8395 = observationTerminals8395.toMap()
            val forward8404 = forwardReady8404.toMap()
            if (batch.isEmpty() && unavailable8395.isEmpty() && forward8404.isEmpty()) return true
            val receipts = HashMap<String, String>(unavailable8395)
            receipts.putAll(forward8404)
            val trained = HashMap<String, String>()
            for ((id, terminal) in batch) {
                receipts["CORTEX_SHADOW_RECEIPT_8372|$id"] = "{\"committed\":true}"
                if (terminal.learned) {
                    listOf(terminal.shadowId, terminal.labelId, terminal.trainedId).filter { it.isNotBlank() }.forEach { trained[it] = "SHADOW_EXIT" }
                } else if (terminal.trainedId.isNotBlank()) trained[terminal.trainedId] = "FORWARD"
            }
            trained.forEach { (id, kind) -> receipts[TRAINING_RECEIPT_8372 + id] = org.json.JSONObject().put("kind", kind).toString() }
            val started = System.nanoTime()
            val durable = durableCloseCommit8198("CORTEX_SHADOW_BATCH_8372|${java.util.UUID.randomUUID()}", receipts)
            if (!durable) return false
            unavailable8395.forEach { (key,value) -> observationTerminals8395.remove(key,value) }
            forward8404.forEach { (key,value) ->
                if (forwardReady8404.remove(key,value)) forwardLearned8404.remove(key)
            }
            val duration8386 = (System.nanoTime() - started) / 1_000_000L
            counters.computeIfAbsent("SHADOW_CHECKPOINT_MS_8372") { AtomicLong(0) }.set(duration8386)
            counters.computeIfAbsent("SHADOW_CHECKPOINT_MAX_MS_8386") { AtomicLong(0) }.updateAndGet { maxOf(it, duration8386) }
            if (durableTraining8372.size + trained.size >= 16_000) durableTraining8372.clear()
            durableTraining8372.putAll(trained)
            synchronized(this) {
                for ((id, terminal) in batch) if (shadowReady8372.remove(id, terminal)) {
                    shadowPending8372.remove(id); shadowCtx8372.remove(id)
                    outcomeBook8372.retireAcknowledged8372(id, trainingOwnershipDurable = true)
                }
            }
            return true
    }

    /** Caller holds the model lock. All external delivery and checkpoint I/O are outside this section. */
    private fun gradeModelsLocked8372(key: String, p: Pending, net8187: Double, gross8187: Double, witness8324: Boolean,
                                      shadowExit8372: Boolean = false) {
            // V5.0.8324 §PROVENANCE — a chain-witnessed runner label is counted as such in every book (its tail reads raw).
            val trace8006 = ledger.grade(p.a.lane, p.a.ids, p.a.edges, p.a.raws, net8187, gross8187, p.a.regime, predictionForGrade8076(p.a), witness8324, shadowNet8404 = shadowExit8372)
            val bookScales = board.record(p.a.lane, p.a.bucket, p.legacyAdmitted, net8187, gross8187, witness8324, shadowNet8404 = shadowExit8372)
            val auxScales = DoubleArray(3) { Double.NaN }
            val calStamp8322 = calibration.learn(p.a.lane, p.a.fused.edgePct, p.a.fused.laneMean, net8187.coerceIn(CortexLedger7885.Y_MIN, CortexLedger7885.Y_MAX), calibrationScore8372(p.a),
                targetBasis8372 = if (shadowExit8372) CortexCalibration7901.TARGET_SHADOW_NET_8372 else CortexCalibration7901.TARGET_FORWARD_NET_8372)
            gradeBook8144.decision("$key|${p.atMs}")?.let { r ->
                r.rawEdge8371 = p.a.fused.edgePct
                r.laneMean8371 = p.a.fused.laneMean
                r.calStamp8371 = calStamp8322
            }
            // Each selected/also-fired setup gets one original contribution. Keep its own decay stamp and
            // cell generation so later horizons replace it without double-counting or touching a recreated cell.
            val jointTrace = ArrayList<JointContribution8372>()
            for (setup in (listOf(p.setup7961) + p.fired8094).filter { it.isNotBlank() }.distinct()) {
                val jk = jointKey7961(p.a.lane, p.a.bucket, setup)
                val st = jointStatForGrade8372(jk, allowEvict = setup == p.setup7961) ?: continue
                st.scale(CortexScoreboard7885.BOOK_DECAY)
                val stamp = st.add(net8187.coerceIn(CortexLedger7885.Y_MIN, CortexLedger7885.Y_MAX),
                    gross8187 >= CortexLedger7885.RUNNER_GROSS_PCT, witness8324, shadowNet8404 = shadowExit8372)
                if (setup == p.setup7961) auxScales[0] = stamp
                jointTrace.add(JointContribution8372(jk, jointGenerations8372.getValue(jk), stamp))
            }
            if (p.fired8094.isNotEmpty()) inc("SETUP_FIRED_GRADED_8094")
            p.vetoRule?.let { r ->
                auxScales[1] = vetoBook.getOrPut(r) { CortexLedger7885.labelStat8187() }
                    .add(net8187.coerceIn(CortexLedger7885.Y_MIN, CortexLedger7885.Y_MAX), gross8187 >= CortexLedger7885.RUNNER_GROSS_PCT, witness8324, shadowNet8404 = shadowExit8372)
                // V5.0.8006 — the same rule per lane: a rule can refuse winners in one lane and losers in another.
                auxScales[2] = vetoBook.getOrPut(vetoLaneKey8006(r, p.a.lane)) { CortexLedger7885.labelStat8187() }
                    .add(net8187.coerceIn(CortexLedger7885.Y_MIN, CortexLedger7885.Y_MAX), gross8187 >= CortexLedger7885.RUNNER_GROSS_PCT, witness8324, shadowNet8404 = shadowExit8372)
            }
            if (trace8006 != null) retain8006("$key|${p.atMs}", p, trace8006, net8187, gross8187, bookScales, auxScales, calStamp8322)
            graded8006["$key|${p.atMs}"]?.jointTrace8372 = jointTrace
            graded8006["$key|${p.atMs}"]?.witnessed8324 = witness8324  // V5.0.8324
            gradeBook8144.decision("$key|${p.atMs}")?.modelContribution8395 = "$key|${p.atMs}"
            outcomeBook8372.learnedForward("$key|${p.atMs}")
    }

    // ── V5.0.8144 — grade records, label hygiene, walk-forward promotion, outcome to owner ──
    //
    // Every graded decision is a CortexGradeRecord8144 (prediction / observed / executable kept apart; exact key lane |
    // setup | regime | timing | cost band | cohort); every Cortex-bound trade one more, closed by the strategy that owned
    // it. Only executable, first-of-episode labels grade the books (CortexLabelHygiene8144: the MOONSHOT REFUSE +213%
    // artefact), and STRONG-side authority needs walk-forward validation on these records as well as the realised cap.
    private const val GRADES_KEY_8144 = "CORTEX_GRADES_8144"

    // ── 8198 — durable, replay-safe close delivery (the Cortex close hold) ──
    /** One durable receipt row per graded canonical close: its existence in the store means the graded state was
     *  committed in the SAME transaction (LearningPersistence.commitOnce8090), so a restart can reach the receipt
     *  check before touching any book/voter/strategy mutation. */
    private const val CLOSE_RECEIPT_KEY_8198 = "CORTEX_CLOSE_RECEIPT_8198|"

    /** Result of one canonical close delivery through FinalizedBusConsumerBridge6465. ACKED only on an accepted
     *  durable outcome (or an explicit no-mutation disposition); RETRY leaves the envelope unacked for redelivery. */
    enum class CloseDelivery8198 { ACKED, RETRY }

    /** tradeId -> grade committed durably (this process saw commitOnce8090 COMMITTED/ALREADY_COMMITTED). */
    private val closeReceipts8198 = ConcurrentHashMap.newKeySet<String>()
    /** tradeId -> grade applied in memory but its durable receipt commit is still pending (DB refused/busy/off-thread).
     *  The gate is set BEFORE any mutation, so a redelivery in this process never grades the same close twice. */
    private val uncommittedCloseGrades8198 = ConcurrentHashMap.newKeySet<String>()

    /** Test seam: the close-path diagnostic counters. */
    internal fun counter8198(name: String): Long = counters[name]?.get() ?: 0L

    private fun closeReceipt8198(tradeId: String): String = CLOSE_RECEIPT_KEY_8198 + tradeId

    /**
     * 8198 — the durability barrier. Captures ledger books + grade book + pending reads + the tradeId receipt in ONE
     *  transaction (commitOnce8090). Returns true only when the outcome is durable (COMMITTED or ALREADY_COMMITTED);
     *  a refused row, a Main-thread caller, an open transaction or a closed store returns false and the close
     *  delivery must NOT acknowledge. Never a bare async-persist-then-ack.
     */
    private fun durableCloseCommit8198(receipt: String, additionalBlobs8372: Map<String, String> = emptyMap()): Boolean =
        synchronized(persistLock8325) { durableCloseCommitLocked8325(receipt, additionalBlobs8372) }

    /** V5.0.8325 — [durableCloseCommit8198]'s body; the caller holds [persistLock8325] (never the Cortex lock). */
    @Volatile private var snapshotGeneration8386 = 0L
    @Volatile private var snapshotWaitMs8386 = 0L
    @Volatile private var snapshotHoldMs8386 = 0L
    @Volatile private var snapshotEncodeMs8386 = 0L
    @Volatile private var snapshotDbMs8386 = 0L

    private fun snapshotEncoder8386(periodic: Boolean): (() -> MutableMap<String, String>)? {
        val started = System.nanoTime()
        return synchronized(this) {
            val acquired = System.nanoTime()
            snapshotWaitMs8386 = (acquired - started) / 1_000_000
            if (periodic && uncommittedCloseGrades8198.isNotEmpty()) return@synchronized null
            val generation = ++snapshotGeneration8386
            val l = ledger.detached8386(); val b = board.detached8386(); val c = calibration.detached8386()
            val real = realLedgers.mapValues { it.value.detached8386() }
            val veto = vetoBook.mapValues { it.value.detached8386() }; val joint = jointBook7961.mapValues { it.value.detached8386() }
            val generations = jointGenerations8372.toMap(); val next = jointNextGeneration8372
            val pend = pendingSnapshot8076(null, generation); val grades = gradeBook8144.detached8386()
            val receipts = HashMap<String, String>()
            receipts.putAll(forwardReady8404)
            uncommittedCloseGrades8198.forEach { receipts[closeReceipt8198(it)] = "{\"committed\":true}" }
            shadowReady8372.forEach { (id, terminal) ->
                receipts["CORTEX_SHADOW_RECEIPT_8372|$id"] = "{\"committed\":true}"
                val kind = if (terminal.learned) "SHADOW_EXIT" else "FORWARD"
                val ids = if (terminal.learned) listOf(terminal.shadowId, terminal.labelId, terminal.trainedId) else listOf(terminal.trainedId)
                ids.filter { it.isNotBlank() }.forEach { receipts[TRAINING_RECEIPT_8372 + it] = "{\"kind\":\"$kind\"}" }
            }
            snapshotHoldMs8386 = (System.nanoTime() - acquired) / 1_000_000
            val encode: () -> MutableMap<String, String> = {
                val encodeAt = System.nanoTime()
                val out = HashMap<String, String>()
                out[PERSIST_KEY] = org.json.JSONObject().put("ledger", l.encode()).put("board", b.encode()).put("calibration", c.encode())
                    .put("real", org.json.JSONObject().also { j -> real.forEach { (k, v) -> j.put(k, v.encode()) } })
                    .put("vetoes", org.json.JSONObject().also { j -> veto.forEach { (k, v) -> j.put(k, v.encode()) } })
                    .put("joint7961", org.json.JSONObject().also { j -> joint.forEach { (k, v) -> j.put(k, v.encode()) } })
                    .put("jointGeneration8372", org.json.JSONObject(generations as Map<*, *>)).put("jointNext8372", next)
                    .put("snapshotGeneration8386", generation).toString()
                // The typed streaming builder emits p/g8013 and the generation directly.
                // Do not inflate the entire pending graph three times merely to add/check keys.
                // JSON objects validate each row when constructed; round-trip tests pin the full wire contract.
                out[PENDING_PERSIST_KEY] = pend()
                out[GRADES_KEY_8144] = grades.encode()
                out.putAll(receipts)
                snapshotEncodeMs8386 = (System.nanoTime() - encodeAt) / 1_000_000
                out
            }
            encode
        }
    }

    private fun durableCloseCommitLocked8325(receipt: String, additionalBlobs8372: Map<String, String> = emptyMap()): Boolean {
        // V5.0.8324 — never commit a snapshot of books that were not read from the store: a boot-replayed close racing the
        // store opening wrote the EMPTY ledger over the stored one (loaded is false until ensureLoaded has read it).
        if (!loaded) { inc("CORTEX_CLOSE_COMMIT_UNLOADED_REFUSED_8324"); return false }
        val encode = snapshotEncoder8386(periodic = false) ?: return false
        val blobs = HashMap(additionalBlobs8372)
        blobs.putAll(encode()) // the captured generation's receipts win over an older caller batch
        val dbAt = System.nanoTime()
        val committed = LearningPersistence.commitOnce8090(receipt, blobs)
        snapshotDbMs8386 = (System.nanoTime() - dbAt) / 1_000_000
        return when (committed) {
            LearningPersistence.Commit8090.COMMITTED, LearningPersistence.Commit8090.ALREADY_COMMITTED -> true
            LearningPersistence.Commit8090.RETRY -> { inc("CORTEX_CLOSE_DURABLE_RETRY_8198"); false }
        }
    }
    private const val WF_REFRESH_MS_8144 = 30_000L
    private const val LOST_SWEEP_EVERY_MS_8144 = 120_000L
    private val gradeBook8144 = CortexGradeBook8144().also { b ->
        b.execOffset8187 = { l -> execOffsetByLane8187[l] ?: 0.0 }  // V5.0.8187
        b.runnerLane8322 = { l -> runnerHorizon8144(l) }             // V5.0.8322 — runner lanes graded on their hold
    }
    private val wfCache8144 = HashMap<String, Pair<Long, CortexWalkForward8144.Result8144>>()
    private val pendingCtx8144 = ConcurrentHashMap<String, DecisionCtx8144>()
    @Volatile private var lastLostSweepMs8144 = 0L

    /** The decision-time context of a grade key, frozen with the decision. */
    private class DecisionCtx8144(val timing: String, val costBand: String, val cohort: String, val latencyMeasured: Boolean, val executable: Boolean)

    private fun latencyMeasured8144(lane: String): Boolean =
        try { com.lifecyclebot.engine.truth.EntryChase7961.laneLatencyMs8094(lane) > 0L } catch (_: Throwable) { false }

    private fun runnerHorizon8144(lane: String): Boolean =
        (try { com.lifecyclebot.engine.TradeExitContract8077.runnerFromEntryLane8104(lane, false) } catch (_: Throwable) { false }) || isRunner(lane)

    private fun decisionCtx8144(ts: TokenState, lane: String, admitted: Boolean, reason: String?): DecisionCtx8144 {
        val timing = try { LanePlaybook7907.timingBandOf8094(ts) } catch (_: Throwable) { LanePlaybook7907.TIMING_UNKNOWN_8094 }
        val liq = if (ts.lastLiquidityUsd.isFinite() && ts.lastLiquidityUsd > 0.0) ts.lastLiquidityUsd else 0.0
        val cost = try {
            com.lifecyclebot.engine.truth.CostLedger7962.costOr7962(lane, Double.NaN, com.lifecyclebot.engine.truth.FieldManual7715.allInCostPct8350(5.0, liq, ts.mint)) +
                com.lifecyclebot.engine.truth.EntryChase7961.lanePenaltyPct7961(lane)
        } catch (_: Throwable) { Double.NaN }
        val cohort = try {
            com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.mcapBand(com.lifecyclebot.engine.truth.TrustedMcap8019.mcap8019(ts))
        } catch (_: Throwable) { "UNK" }
        val native = try { SpecialistBrainBridge7542.cachedSnapshot7650(ts.mint)?.opinions?.get(lane) } catch (_: Throwable) { null }
        val opinion8372 = native?.takeIf { it.authoritative }
        val nativeEligible8196 = opinion8372?.eligible
        // A specialist's judgement NO is an outcome we must learn from, not proof of an impossible fill.
        // Mandate, safety and missing-data refusals still cannot manufacture executable evidence.
        val executable8196 = CortexLabelHygiene8144.counterfactualExecutable8372(
            admitted, reason, nativeEligible8196, opinion8372?.gradeable, opinion8372?.reason,
        )
        com.lifecyclebot.engine.BotImprovementReport8168.noteCortex(lane, admitted, executable8196, reason)
        if (!executable8196) CortexLabelHygiene8144.unexecClass8291(admitted, reason, nativeEligible8196)?.let { inc("LABEL_UNEXEC_CLASS_8291_$it") }
        if (!admitted && nativeEligible8196 == false) inc(if (executable8196) "LABEL_NATIVE_NO_LEARNABLE_8372" else "LABEL_NATIVE_INELIGIBLE_OBSERVED_ONLY_8196")
        if (!admitted && nativeEligible8196 == null) inc("LABEL_NATIVE_ELIGIBILITY_UNVERIFIED_8196")
        return DecisionCtx8144(timing, CortexLabelHygiene8144.costBand8144(cost), cohort, latencyMeasured8144(lane), executable8196)
    }

    /** Caller holds the lock. */
    private fun verdict8144(key: String, p: Pending, ctx: DecisionCtx8144?): CortexLabelHygiene8144.Verdict8144 =
        CortexLabelHygiene8144.Verdict8144(
            !p.a.staleMark && (ctx?.executable ?: (p.decisionReason8196 != null &&
                CortexLabelHygiene8144.executableDecision8144(p.legacyAdmitted, p.decisionReason8196))) &&
                CortexLabelHygiene8144.executableDecision8144(p.legacyAdmitted, p.decisionReason8196),
            CortexLabelHygiene8144.repeatInEpisode8144(p.legacyAdmitted, gradeBook8144.lastGradedAt(key), p.atMs),
        )

    private fun newDecision8144(id: String, key: String, p: Pending, ctx: DecisionCtx8144?, v: CortexLabelHygiene8144.Verdict8144) =
        CortexGradeRecord8144('D', id, key, p.atMs, p.a.lane, p.selected8153.ifBlank { p.setup7961 }.ifBlank { "-" }, p.a.regime.ifBlank { "-" },
            ctx?.timing ?: LanePlaybook7907.TIMING_UNKNOWN_8094, ctx?.costBand ?: "CUNK", ctx?.cohort ?: "UNK",
            p.a.bucket.ordinal, p.a.calibratedEdge, p.legacyAdmitted, v.executable, v.repeat)
            .also {
                it.mode = p.decisionMode8196; it.lateRead8187 = p.lateRead8169; it.tailEv8324 = p.a.tailEv8324
                it.scoreVersion8372 = p.a.scoreVersion8372
            }

    /** Caller holds the lock. The 5-minute label of [p] as a grade record; returns the hygiene verdict. */
    private fun recordLabel8144(key: String, p: Pending, netPct: Double, grossPct: Double, latencyMeasured: Boolean,
                                proof8395: CortexOutcomeProof8395? = null): Pair<CortexLabelHygiene8144.Verdict8144, CortexOutcomeDisposition8395> {
        val ctx = p.context8372
        if (ctx != null) pendingCtx8144.remove(key, ctx)
        val id = "$key|${p.atMs}"
        // A decision swept as unresolved and labelled late keeps the verdict it was filed with.
        val existing = gradeBook8144.decision(id)
        val v = existing?.let { CortexLabelHygiene8144.Verdict8144(it.executable, it.repeat) } ?: verdict8144(key, p, ctx)
        if (existing == null) gradeBook8144.addDecision(newDecision8144(id, key, p, ctx, v))
        val now8395 = System.currentTimeMillis()
        val result = gradeBook8144.labelOutcome8395(id, netPct, grossPct,
            CortexLabelHygiene8144.executablePct8144(netPct, ctx?.latencyMeasured ?: latencyMeasured), true,
            proof8395?.horizonMs ?: 5 * 60_000L, proof8395?.observedAtMs ?: now8395, now8395, proof8395)
        return v to result
    }

    // ── V5.0.8153 — late first prices, observed-only revisions ──
    private const val OBSERVED_ONLY_MAX_8153 = 3_000
    /** "mint|LANE" -> decision time of the newest decision whose label was kept out of the books (observed record only). */
    private val observedOnly8153 = HashMap<String, Long>()

    /** Was the label of decision ([key], [atMs]) first priced late? Consumes the labeler's flag and files it on the record. */
    private fun lateFirstPrice8153(mint: String, labelLane: String, key: String, atMs: Long): Boolean {
        val late = try { com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.lateFirstPrice8153(mint, labelLane, atMs) } catch (_: Throwable) { false }
        if (!late) return false
        synchronized(this) { gradeBook8144.markLateEntry8153("$key|$atMs") }
        return true
    }

    /** Caller holds the lock. */
    private fun noteObservedOnly8153(key: String, atMs: Long) {
        if (observedOnly8153.size >= OBSERVED_ONLY_MAX_8153) {
            val cutoff = System.currentTimeMillis() - GRADED_KEEP_MS_8013
            observedOnly8153.entries.removeIf { it.value < cutoff }
            if (observedOnly8153.size >= OBSERVED_ONLY_MAX_8153) observedOnly8153.entries.minByOrNull { it.value }?.key?.let { observedOnly8153.remove(it) }
        }
        observedOnly8153[key] = atMs
    }

    /**
     * Caller holds the lock. The labeler revises the observation it holds for [key] — after a hygiene-excluded label that
     * is the excluded decision, NOT the older graded decision still retained under the same key. Until 8153 its revisions
     * (a repeat refusal's run) re-graded that older decision's books; now they move the excluded decision's observed record.
     */
    private fun observedOnlyNewer8153(key: String): Boolean {
        val at = observedOnly8153[key] ?: return false
        return at > (graded8006[key]?.atMs ?: Long.MIN_VALUE)
    }

    /** Caller holds the lock. */
    private fun reviseObservedOnly8153(key: String, newNet: Double, newGross: Double) {
        val at = observedOnly8153[key] ?: return
        if (gradeBook8144.label("$key|$at", newNet, newGross, Double.NaN, false)) inc("REVISION_OBSERVED_ONLY_8153")
    }

    /** Decisions with no 5-minute label after LOST_AFTER_MS_8144 are filed unresolved (the cohort's resolved share). */
    private fun sweepLost8144(nowMs: Long) {
        if (nowMs - lastLostSweepMs8144 < LOST_SWEEP_EVERY_MS_8144) return
        lastLostSweepMs8144 = nowMs
        synchronized(this) {
            for ((k, p) in pending.entries) {
                if (nowMs - p.atMs < CortexLabelHygiene8144.LOST_AFTER_MS_8144) continue
                val id = "$k|${p.atMs}"
                if (gradeBook8144.decision(id) != null) continue
                val ctx = p.context8372
                if (gradeBook8144.addDecision(newDecision8144(id, k, p, ctx, verdict8144(k, p, ctx))) && gradeBook8144.markLost(id)) inc("LABEL_UNRESOLVED_8144")
            }
            if (pendingCtx8144.size > MAX_PENDING) pendingCtx8144.keys.removeIf { !pending.containsKey(it) }
        }
    }

    /** Caller holds the lock. Walk-forward result of [lane] (x [setup]) STRONG executable records, refreshed every 30 s. */
    private fun walkForward8144(lane: String, setup: String?): CortexWalkForward8144.Result8144 {
        val ck = "$lane|${setup ?: "*"}"
        val now = System.currentTimeMillis()
        wfCache8144[ck]?.let { (at, cached) -> if (now - at in 0L until WF_REFRESH_MS_8144) return cached }
        val strong = CortexScoreboard7885.Bucket.STRONG.ordinal
        val rivals = listOf(CortexScoreboard7885.Bucket.NEUTRAL.ordinal, CortexScoreboard7885.Bucket.REFUSE.ordinal).map { b ->
            gradeBook8144.executableTimed8196(lane, setup, b) to gradeBook8144.resolvedShare(lane, b, setup)
        }
        val result = CortexWalkForward8144.promoteTimed8196(gradeBook8144.executableTimed8196(lane, setup, strong), rivals, gradeBook8144.resolvedShare(lane, strong, setup))
        if (wfCache8144.size > 400) wfCache8144.clear()
        wfCache8144[ck] = now to result
        return result
    }

    /** V5.0.8188 — the walk-forward result of [lane] (x [setup]) for LiveRiskPolicy7807's size-up proof; null on a fault. */
    fun walkForwardResult8188(lane: String, setup: String? = null): CortexWalkForward8144.Result8144? =
        try { ensureLoaded(); synchronized(this) { walkForward8144(canon(lane), setup) } } catch (_: Throwable) { null }

    /**
     * Caller holds the lock. [labelFraction] after its realised cap ([realisedCapped], CortexRealisedAuthority8136) and the
     * walk-forward cap of this lane (or lane x [setup], whose own realised closes cap it too). Only lowers.
     */
    private fun walkForwardCapped8144(lane: String, setup: String?, labelFraction: Double, realisedCapped: Double): Double {
        if (!(realisedCapped > 0.0)) return 0.0
        val wf = walkForward8144(lane, setup)
        // V5.0.8153 — the lane (not only a setup cell) answers to its owner-routed realised STRONG closes in this mode, and a
        // cohort whose closes shrink a staked wallet holds nothing (LearningEntryTruth8153.realisedEvidenceCap8153).
        val cellRealised = ownerRealisedCap8153(lane, setup)
        val out = CortexWalkForward8144.compose8144(labelFraction, realisedCapped, minOf(CortexWalkForward8144.authorityCap8144(wf), cellRealised))
        if (out < realisedCapped) inc(if (out <= 0.0) "AUTHORITY_DENIED_8144_${wf.verdict.name}" else "AUTHORITY_CAPPED_8144_${wf.verdict.name}")
        return out
    }

    /** Caller holds the lock. V5.0.8153 — the realised-evidence ceiling of [lane] (x [setup]) STRONG closes, owner-routed, this mode only. */
    private fun ownerRealisedCap8153(lane: String, setup: String?): Double =
        LearningEntryTruth8153.realisedEvidenceCap8153(gradeBook8144.realisedSeries(lane, setup, CortexScoreboard7885.Bucket.STRONG.ordinal, accountMode8077()))

    /**
     * Caller holds the lock. V5.0.8153 — the STRONG-side authority this lane actually holds (label fraction x realised cap x
     * walk-forward x owner-routed realised evidence), computed without touching the action counters: what the screens show.
     */
    private fun heldFraction8153(lane: String): Double = authorityFraction8366(lane)  // V5.0.8366 — the one walk-forward test

    /** Caller holds the lock. The pre-8366 held fraction (label x realised x walk-forward x owner-realised): display only. */
    private fun heldFractionLegacy8366(lane: String): Double {
        val f = board.fractionFor7955(lane, accountMode8077())
        if (!(f > 0.0) || calibration.suspends8372(lane, MIN_CONSISTENT_SLOPE, executableOffset8187(lane))) return 0.0
        val s = board.realized["${accountMode8077()}|$lane"]?.getOrNull(CortexScoreboard7885.Bucket.STRONG.ordinal)
        val rc = CortexRealisedAuthority8136.cappedFraction8136(f, s?.n ?: 0.0, s?.mean() ?: 0.0,
            if (s != null && s.n > 1.0) kotlin.math.sqrt(s.variance() / s.n) else Double.POSITIVE_INFINITY)
        if (!(rc > 0.0)) return 0.0
        return CortexWalkForward8144.compose8144(f, rc, minOf(CortexWalkForward8144.authorityCap8144(walkForward8144(lane, null)), ownerRealisedCap8153(lane, null)))
    }

    /** Caller holds the lock. First sight never fires on a lane whose STRONG closes lost, or whose STRONG reads trail a rival. */
    private fun firstSightBarred8144(lane: String): Boolean {
        // The same completed current-verdict proof governs a probe and an overrule. An insufficient sample can still
        // use FirstSight's existing bounded exploration, but a completed losing / inverted proof cannot be bypassed.
        val (rn, rm) = realisedStrong8366(lane)
        val r = authorityTest8366(lane)
        return CortexAuthority8366.revokes8366(rn, rm) ||
            (r.verdict != CortexAuthority8366.Verdict8366.INSUFFICIENT_OOS && r.verdict != CortexAuthority8366.Verdict8366.GRANTED &&
                r.verdict != CortexAuthority8366.Verdict8366.RAMP_8399)   // V5.0.8399 — a ramping lane is not a completed losing proof
    }

    /** Caller holds the lock. The pre-8366 first-sight bar (8136 realised denial or 8144 INVERTED_OOS): display only. */
    private fun firstSightBarredLegacy8366(lane: String): Boolean {
        val s = board.realized["${accountMode8077()}|$lane"]?.getOrNull(CortexScoreboard7885.Bucket.STRONG.ordinal)
        val denied = s != null && CortexRealisedAuthority8136.verdict8136(s.n, s.mean(),
            if (s.n > 1.0) kotlin.math.sqrt(s.variance() / s.n) else Double.POSITIVE_INFINITY) == CortexRealisedAuthority8136.Verdict8136.DENIED_REALISED_NEGATIVE
        return denied || walkForward8144(lane, null).verdict == CortexWalkForward8144.Verdict8144.INVERTED_OOS
    }

    /**
     * V5.0.8200 §THE_LOCKED_DOOR. 5.0.8199 live (40 min): FDG 0 allowed / 7,004 refused, 3,849 of them on lane-wide or
     * cell loss records (STAGE_PROVEN_LOSING 2,251) while this lane's STRONG reads graded positive on executable-net labels
     * (MOONSHOT n91 +37%, SHITCOIN n93 +26%, EXPRESS n99 +21%). The overrule authority waits for realised STRONG closes,
     * which a book that never trades can never earn. The STRONG read on THIS candidate is candidate-specific evidence:
     * returned as the lane's STRONG executable-net record for CandidateAdmission8136 to weigh at its own margins.
     * Null unless the read is fresh STRONG, the coin is not hard-blocked or a Mayhem coin, and the lane is calibrated,
     * not inverted and not barred (STRONG closes lost or STRONG trails a rival out of sample).
     */
    fun strongCohortEvidence8200(ts: TokenState, laneRaw: String): com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136? {
        try {
            CortexAudit8169.call("STRONG_COHORT_8200")
            val a = cachedOrSchedule(ts, laneRaw) ?: return nullWhy8201("NO_ASSESSMENT")
            if (a.bucket != CortexScoreboard7885.Bucket.STRONG) return nullWhy8201("NOT_STRONG")
            if (a.staleMark) return nullWhy8201("STALE_MARK")
            if (ts.safety.tier == com.lifecyclebot.engine.SafetyTier.HARD_BLOCK) return nullWhy8201("HARD_BLOCK")
            if (com.lifecyclebot.engine.MayhemMode7943.liveRefusal(ts) != null) return nullWhy8201("MAYHEM")
            // V5.0.8366 §ONE_AUTHORITY_TEST — the door opens on the one walk-forward test (calibrated, OOS rank IC > 0, OOS top
            // minus bottom > cost, top > 0, 100+ OOS rows, not revoked by 20+ losing realised closes); the evidence offered is
            // the OOS TOP-bucket record (net executable, out of sample). The pre-8366 doors are counted for display.
            val st = synchronized(this) {
                strongDoorLegacy8366(a)?.let { inc("STRONG_COHORT_LEGACY_8366_$it") }
                if (!(authorityFraction8366(a.lane) > 0.0)) nullWhy8201<CortexWalkForward8144.Stat8144>("AUTHORITY_8366_${authorityCode8366(a.lane)}")
                else authorityTest8366(a.lane).let { r -> CortexWalkForward8144.Stat8144(r.topN, r.topMean, r.topSe) }
            } ?: return null
            if (st.n <= 0 || !st.mean.isFinite()) return nullWhy8201("NO_EXECUTABLE_COHORT")
            inc("STRONG_COHORT_OFFERED_8200")
            return com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136(
                com.lifecyclebot.engine.truth.CandidateAdmission8136.KIND_CORTEX_8200, "STRONG@${a.lane}", st.n, st.mean, st.se, netOfCost = true,
            )
        } catch (_: Throwable) { return nullWhy8201("EXCEPTION") }
    }

    /** Caller holds the lock. V5.0.8366 — which pre-8366 door would have shut [a]'s STRONG cohort (null = open): display only. */
    private fun strongDoorLegacy8366(a: Assessment): String? {
        if (calibration.suspends8322(a.lane, MIN_CONSISTENT_SLOPE)) return "UNCALIBRATED"
        if (board.inverted7948(a.lane, accountMode8077())) return "INVERTED"
        if (firstSightBarredLegacy8366(a.lane)) return "BARRED"
        val series = gradeBook8144.executableSeries(a.lane, null, CortexScoreboard7885.Bucket.STRONG.ordinal)
        val wf = walkForward8144(a.lane, null)
        if (!wf.validated) return "NOT_WALK_FORWARD_VALIDATED_${wf.verdict.name}"
        val tail8322 = if (runnerHorizon8144(a.lane)) CortexTailDoor8322.read8322(series) else null
        if (tail8322 != null) return if (tail8322.open) null else "TAIL_${tail8322.why}_8322"
        return if (!(CortexWalkForward8144.median8238(series) > 0.0)) "MEDIAN_NOT_POSITIVE" else null
    }

    /**
     * V5.0.8201 — why a STRONG cohort read was NOT offered as admission evidence, by name (STRONG_COHORT_NULL_8201_<why>):
     * 5.0.8200 shipped the read and still allowed nothing, with no record of which door was shut. Returns null.
     */
    private fun <T> nullWhy8201(why: String): T? {
        inc("STRONG_COHORT_NULL_8201_$why")
        return null
    }

    /** Async (pool): a Cortex-bound position opens its trade record under the entry's exact grade key. */
    private fun openTrade8144(positionId: String, mint: String, mode: String, a: Assessment, openedAtMs: Long) {
        val ts = try { com.lifecyclebot.engine.BotService.status.tokens[mint] } catch (_: Throwable) { null }
        val ctx = ts?.let { decisionCtx8144(it, a.lane, true, null) }
        // V5.0.8153 — the sealed entry identity's setup, else the decision's selected setup, else the classifier now: a later
        // relabel of the token never re-keys the trade.
        val sealed8153 = try { com.lifecyclebot.engine.truth.AateEntryIdentityBook8136.identity8136(positionId) } catch (_: Throwable) { null }
        val (setup, src8153) = LearningEntryTruth8153.entrySetup8153(
            sealed8153?.takeIf { it.mode.equals(mode, true) && it.mint == mint }?.exactSetup, pending["$mint|${a.lane}"]?.selected8153
        ) { ts?.let { t -> try { LanePlaybook7907.selectedSetup8094(t, a.lane, System.currentTimeMillis()) } catch (_: Throwable) { null } } }
        inc("TRADE_SETUP_FROM_${src8153.name}_8153")
        val r = CortexGradeRecord8144('T', positionId, "$mint|${a.lane}", openedAtMs, a.lane, setup, a.regime.ifBlank { "-" },
            ctx?.timing ?: LanePlaybook7907.TIMING_UNKNOWN_8094, ctx?.costBand ?: "CUNK", ctx?.cohort ?: "UNK",
            a.bucket.ordinal, a.calibratedEdge, true, true, false)
        r.mode = mode
        synchronized(this) { if (gradeBook8144.openTrade(r)) inc("TRADE_RECORD_OPENED_8144_$mode") }
    }

    /** Caller holds the lock. The close returns to the strategy that owned the trade (sealed identity first). */
    private fun routeRealised8144(env: com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464.Envelope, mode: String, a: Assessment,
                                  sealed: com.lifecyclebot.engine.truth.AateEntryIdentity8136?, snapshotTag: String?) {
        val trade = gradeBook8144.trade(env.positionId) ?: run {
            inc("REALISED_NO_TRADE_RECORD_8144")
            // V5.0.8153 — the trade record never opened (dropped async job, pre-8146 position): file it from the entry read
            // so the sealed owner still gets its whole-position outcome.
            val r = CortexGradeRecord8144('T', env.positionId, "${env.mint}|${a.lane}", a.atMs, a.lane, "", a.regime.ifBlank { "-" },
                LanePlaybook7907.TIMING_UNKNOWN_8094, "CUNK", "UNK", a.bucket.ordinal, a.calibratedEdge, true, true, false)
            r.mode = mode
            if (!gradeBook8144.openTrade(r)) return
            inc("TRADE_RECORD_FILED_AT_CLOSE_8153")
            r
        }
        val owner = CortexOutcomeRoute8144.owner8144(mode, env.mint, sealed?.mode, sealed?.mint, sealed?.ownerLane, sealed?.exactSetup,
            snapshotTag, trade.lane.ifBlank { a.lane }, trade.setup)
        if (gradeBook8144.closeTrade(env.positionId, owner, env.realizedReturnPct) == null) return
        inc("REALISED_ROUTED_8144_${owner.attribution.name}")
        if (owner.mismatch) inc("REALISED_OWNER_DIFFERS_FROM_ENTRY_8144")
    }

    /**
     * Caller holds the lock. V5.0.8153 — an eligible close with no bound entry read: its sealed owner (else the entry
     * snapshot's setup on the close lane) gets the whole-position outcome on the realised book, under no verdict bucket.
     */
    private fun fileUnboundClose8153(env: com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464.Envelope, mode: String,
                                     sealed: com.lifecyclebot.engine.truth.AateEntryIdentity8136?, snapshotTag: String?): Boolean {
        if (gradeBook8144.trade(env.positionId) != null) return false
        // V5.0.8169 — a close finalized before this process (the bootstrap replays every historical close: 8168 filed 1,345
        // in 9 minutes) is counted apart; the book takes it only if it is newer than the book's oldest trade (openTrade).
        if (env.atMs in 1 until loadedAtMs8169) inc("REALIZED_REPLAYED_8169")
        val (r, owner) = LearningEntryTruth8153.unboundTrade8153(env.positionId, env.mint, mode, env.atMs - env.holdingTimeMs.coerceAtLeast(0L),
            canon(env.lane), sealed?.mode, sealed?.mint, sealed?.ownerLane, sealed?.exactSetup, snapshotTag) ?: run { inc("REALIZED_UNBOUND_NO_OWNER_8153"); return false }
        if (!gradeBook8144.openTrade(r)) { inc("REALIZED_UNBOUND_OLDER_THAN_BOOK_8169"); return false }
        if (gradeBook8144.closeTrade(env.positionId, owner, env.realizedReturnPct) != null) { inc("REALIZED_UNBOUND_FILED_8153_${owner.attribution.name}"); return true }
        return false
    }

    /** Caller holds the lock. V5.0.8187 — "~raw+X%" when a tradeable cohort's stored mean differs from its capped read. */
    private fun rawExec8187(lane: String, bucket: Int, robustMean: Double): String {
        if (!robustMean.isFinite()) return ""
        val raw = CortexWalkForward8144.stat8144(gradeBook8144.executableSeriesRaw8187(lane, bucket))
        return if (raw.n > 0 && kotlin.math.abs(raw.mean - robustMean) > 0.05) "~raw${"%+.1f".format(raw.mean)}%" else ""
    }

    /** Caller holds the lock. V5.0.8187 — an observed cohort read through the label cap, with the raw mean beside when they differ. */
    private fun fObs8187(lane: String, bucket: Int): String {
        val robust = CortexWalkForward8144.stat8144(gradeBook8144.observedSeries(lane, bucket))
        if (robust.n == 0) return "-"
        val raw = CortexWalkForward8144.stat8144(gradeBook8144.observedSeriesRaw8187(lane, bucket))
        return "n${robust.n}/${"%+.1f".format(robust.mean)}%" + if (kotlin.math.abs(raw.mean - robust.mean) > 0.05) "~raw${"%+.1f".format(raw.mean)}%" else ""
    }

    /** Caller holds the lock. Per lane: observed (raw) vs executable (hygiene) STRONG / REFUSE means, resolved shares, verdict. */
    private fun gradeLine8144(): String {
        val s = CortexScoreboard7885.Bucket.STRONG.ordinal; val r = CortexScoreboard7885.Bucket.REFUSE.ordinal
        fun f(xs: List<Double>): String = CortexWalkForward8144.stat8144(xs).let { if (it.n == 0) "-" else "n${it.n}/${"%+.1f".format(it.mean)}%" }
        val lanes = board.books.entries.sortedByDescending { it.value.byBucket.sumOf { b -> b.n } }.take(5).map { it.key }
        return "decisions=${gradeBook8144.size()} trades=${gradeBook8144.tradeCount()} " +
            "objective8322[runner lanes graded on hold-window revision, raw] clampedLegacy8322=${gradeBook8144.clampedLegacy8322()} flat8322=${gradeBook8144.flat8322()} " +
            lanes.joinToString(" · ") { lane ->
            "$lane obs[S=${fObs8187(lane, s)} R=${fObs8187(lane, r)}] " +
                "exec[S=${f(gradeBook8144.executableSeries(lane, null, s))} R=${f(gradeBook8144.executableSeries(lane, null, r))}] " +
                "resolved[S=${"%.2f".format(gradeBook8144.resolvedShare(lane, s))} R=${"%.2f".format(gradeBook8144.resolvedShare(lane, r))}] " +
                "realised8153[S=${f(gradeBook8144.realisedSeries(lane, null, s, accountMode8077()))} all=${f(gradeBook8144.realisedSeries(lane, null, null, accountMode8077()))} " +
                "obj=${LearningObjective8153.score8153(gradeBook8144.realisedSeries(lane, null, null, accountMode8077())).compact()}] " +
                "wf=${walkForward8144(lane, null).compact()}"
        }
    }

    // ── veto audit (Constitution Phase 3): every existing refusal, graded ──
    //
    // The stack's scattered vetoes each name a reason; the forward label of the
    // candidate they refused shows whether that rule refused losers or winners.
    // A rule whose refused set is PROVEN positive (n >= 40, mean - SE > +2%) is
    // flagged as refusing winners. Nothing is relaxed automatically: this is the
    // evidence for moving each veto into the Constitution or retiring it.
    private val vetoBook = HashMap<String, CortexLedger7885.Stat>()

    /**
     * V5.0.7961 — verdict x setup. Each graded decision also books its 5-minute net into
     * "lane|bucket|setup" (setup = LanePlaybook7907 at the decision). 5.0.7958: MOONSHOT's
     * refuse bucket read n63 -5.0% while its LAUNCH_LADDER_PROVEN setup read n109 +12.9%;
     * C3 refused every candidate in the bucket whatever setup fired. The joint cell decides.
     */
    private val jointBook7961 = HashMap<String, CortexLedger7885.Stat>()
    private val jointGenerations8372 = HashMap<String, Long>()
    private var jointNextGeneration8372 = 1L
    private data class JointContribution8372(val key: String, val generation: Long, val scale: Double)

    /** A delayed revision belongs to the original cell instance, not a new cell under the same name. */
    private fun jointStatForGrade8372(key: String, allowEvict: Boolean): CortexLedger7885.Stat? {
        jointBook7961[key]?.let { st ->
            jointGenerations8372.getOrPut(key) { jointNextGeneration8372++ }
            return st
        }
        if (jointBook7961.size >= 600) {
            if (!allowEvict) return null
            jointBook7961.entries.minByOrNull { it.value.n }?.key?.let { old ->
                jointBook7961.remove(old); jointGenerations8372.remove(old)
            }
        }
        return CortexLedger7885.labelStat8187().also {
            jointBook7961[key] = it; jointGenerations8372[key] = jointNextGeneration8372++
        }
    }

    private fun restoreJointGenerations8372(root: org.json.JSONObject) {
        root.optJSONObject("jointGeneration8372")?.let { generations ->
            for (key in generations.keys()) {
                val generation = generations.optLong(key)
                if (generation > 0L && jointBook7961.containsKey(key)) jointGenerations8372.putIfAbsent(key, generation)
            }
        }
        jointNextGeneration8372 = maxOf(jointNextGeneration8372, root.optLong("jointNext8372", 1L),
            (jointGenerations8372.values.maxOrNull() ?: 0L) + 1L)
    }
    private const val JOINT_MIN_N_7961 = 30.0
    private const val JOINT_MARGIN_PCT_7961 = 2.0

    fun jointKey7961(lane: String, bucket: CortexScoreboard7885.Bucket, setup: String): String = "$lane|${bucket.name}|$setup"

    /** V5.0.8083 — the Cortex's graduated authority on one lane x setup cell, with its evidence. */
    data class CellAuthority8083(val fraction: Double, val n: Double, val mean: Double, val se: Double, val baseline: Double)

    /**
     * V5.0.8372 — the same current-verdict authority test, scoped to this candidate's lane x setup. The historical
     * joint book remains diagnostic; it cannot grant an alternative exception when the frozen STRONG cohort loses.
     * A setup alone retains the existing partial-authority ceiling. Caller holds the lock.
     */
    private fun setupCellAuthority8083(lane: String, setup: String): CellAuthority8083? {
        val proof = authorityTest8366(lane, setup)
        val (n, mean) = realisedStrong8366(lane)
        val f = CortexAuthority8366.authority8366(proof,
            calibration.uncalibrated8372(lane, executableOffset8187(lane)), n, mean)
        if (!(f > 0.0)) return null
        return CellAuthority8083(f.coerceAtMost(CortexScoreboard7885.GRAD_PARTIAL_CAP_7955),
            proof.topN.toDouble(), proof.topMean, proof.topSe, proof.bottomMean)
    }

    /** Pure: a joint cell proven to pay (n >= 30, mean - SE > +2% net). */
    fun jointProvenPositive7961(st: CortexLedger7885.Stat?): Boolean {
        if (st == null || st.n < JOINT_MIN_N_7961) return false
        val se = kotlin.math.sqrt(st.variance() / st.n)
        return st.mean() - se > JOINT_MARGIN_PCT_7961
    }

    /**
     * V5.0.8082 §SETUP_DEAD_EARLY (operator: stop flogging a dead horse). A lane's setup whose OWN recent record under
     * this verdict is proven losing (n >= 12 of its last ~100 grades, mean + SE < -2% net) is refused for this lane:
     * the lane keeps trading its other setups (an inner-lane pivot, not a lane switch, not a smaller bet). Refused
     * candidates are still forward-graded into the same cell, so a setup that starts paying again lifts itself.
     */
    private const val JOINT_DEAD_MIN_N_8082 = 12.0

    fun jointProvenDead8082(st: CortexLedger7885.Stat?): Boolean {
        if (st == null || st.n < JOINT_DEAD_MIN_N_8082) return false
        val se = kotlin.math.sqrt(st.variance() / st.n)
        return se.isFinite() && st.mean() + se < -JOINT_MARGIN_PCT_7961
    }

    /** "lane|bucket|setup n/mean" for the scoreboard: the strongest proven cells. */
    private fun jointLine7961(): String = synchronized(this) {
        jointBook7961.entries.filter { it.value.n >= 10.0 }.sortedByDescending { it.value.mean() }.take(6)
            .joinToString(" · ") { (k, st) -> "$k ${CortexLedger7885.fmt8187(st)}${if (jointProvenPositive7961(st)) " PROVEN" else ""}" }  // V5.0.8187 — robust (raw beside)
            .ifBlank { "-" }
    }

    /**
     * V5.0.7953 — a refusal rule whose own refused candidates are proven to win
     * (veto audit: 40+ graded, mean minus one standard error above +2% net) stops
     * refusing live. 5.0.7951: PLAYBOOK_SETUP_PROVEN_LOSING refused 252 SHITCOIN
     * launch-ladder candidates whose forward return was +16.7% (n=40) — the audit
     * printed REFUSING_WINNERS and nothing acted on it.
     */
    fun vetoRefusesWinners7953(reason: String): Boolean {
        ensureLoaded()
        CortexAudit8169.call("VETO_AUDIT")  // V5.0.8169
        val st = synchronized(this) { vetoBook[vetoRuleOf(reason)] } ?: return false
        if (st.n < 40.0) return false
        val se = kotlin.math.sqrt(st.variance() / st.n)
        val retired = st.mean() - se > 2.0 && vetoRobustProof8185(st)  // V5.0.8185
        if (retired) inc("VETO_RETIRED_7953_${vetoRuleOf(reason)}")
        return retired
    }

    /**
     * V5.0.8006 — [vetoRefusesWinners7953] read in the lane the refusal is about: the lane's own
     * record of the rule decides once it has 40 graded refusals, the all-lane record before that.
     */
    fun vetoRefusesWinners8006(reason: String, lane: String): Boolean {
        ensureLoaded()
        CortexAudit8169.call("VETO_AUDIT")  // V5.0.8169
        val rule = vetoRuleOf(reason)
        val l = canon(lane)
        val own = synchronized(this) { vetoBook[vetoLaneKey8006(rule, l)] }
        if (own != null && own.n >= 40.0) {
            val retired = vetoProvenWinners8006(own)
            if (retired) inc("VETO_RETIRED_8006_${rule}@$l")
            return retired
        }
        return vetoRefusesWinners7953(reason)
    }

    /**
     * V5.0.8365 — FdgGate8365: an 8114 evidence door is demoted to advisory when its own lane record of refused candidates
     * holds 100+ graded refusals that proved winners (vetoProvenWinners8006: mean - SE above +2 % net, robust read).
     */
    fun vetoRetiresAt100_8365(reason: String, lane: String): Boolean {
        ensureLoaded()
        return synchronized(this) {
            val st = vetoBook[vetoLaneKey8006(vetoRuleOf(reason), canon(lane))] ?: return@synchronized false
            com.lifecyclebot.engine.truth.FdgGate8365.vetoDemotes8365(st.n, vetoProvenWinners8006(st))
        }
    }

    /** A lane-specific refusal can retire only on that lane's own consistent outcome book. */
    fun ownLaneVetoRefusesWinners8076(reason: String, lane: String): Boolean {
        ensureLoaded()
        CortexAudit8169.call("VETO_AUDIT")  // V5.0.8169
        return synchronized(this) {
            val st = vetoBook[vetoLaneKey8006(vetoRuleOf(reason), canon(lane))] ?: return@synchronized false
            CortexLedger7885.validHiveBin8076(doubleArrayOf(st.n, st.sum, st.sumSq, st.wins, st.runners)) &&
                vetoProvenWinners8006(st)
        }
    }

    /** Pure. V5.0.8006 — refused candidates proven to win: 40+ graded, mean minus one SE above +2% net. */
    fun vetoProvenWinners8006(st: CortexLedger7885.Stat?): Boolean {
        if (st == null || st.n < 40.0) return false
        return st.mean() - kotlin.math.sqrt(st.variance() / st.n) > 2.0 && vetoRobustProof8185(st)  // V5.0.8185
    }

    /**
     * Pure. V5.0.8185 §ROBUST_LABEL_READ — the veto book's refused winners must also be proven on a winsorised read:
     * 5.0.8175's NATIVE_MCAP_TOO_HIGH n141 / +802.9% rested on $1M+ coins labelled at 100x+ inside minutes (a basis
     * error 8180 stopped booking). Stored aggregates are kept; only the read is robust (RobustLabelRead8185).
     */
    fun vetoRobustProof8185(st: CortexLedger7885.Stat?): Boolean {
        if (st == null) return false
        // V5.0.8324 §TAIL_AWARE_VETO_AUDIT — a refused set whose winners are a REAL tail (3+ runners, plausible or chain-witnessed:
        // CortexLedger7885.Stat.tailReal8322) is proven on its raw read: the winsorised lower bound of a tail pond is far below
        // its mean by construction (5.0.8321 NATIVE_THRESHOLD_FAIL_SCORE@SHITCOIN n20 +60.0%~raw+667.7% could never retire).
        // The caller still needs n >= 40 and mean - SE > +2% on that same read; the 8175 basis-error shape stays bounded.
        // V5.0.8325 — withdrawn: a veto retired on a raw tail read admitted live entries on tail evidence alone. The winsorised
        // proof binds again (the raw tail is shown in the veto audit line; it supports nothing live by itself).
        return com.lifecyclebot.engine.truth.RobustLabelRead8185.robustWinnersProven8185(st.n, st.sum, st.sumSq, st.wins, st.variance())
    }

    private fun vetoLaneKey8006(rule: String, lane: String) = "$rule@$lane"

    /**
     * Caller holds the lock. V5.0.8187 — the per-label cap of decision [p] under [key]: its grade record's cap band
     * (RobustLabelRead8185.capForKey8185: the $1M+ band reads at a 4x, every other cohort at an 11x).
     */
    private fun labelCapFor8187(key: String, p: Pending): Double =
        com.lifecyclebot.engine.truth.RobustLabelRead8185.capForKey8185(gradeBook8144.decision("$key|${p.atMs}")?.cohort.orEmpty())

    // ── V5.0.8006 — progressive revision of graded decisions ──
    //
    // The forward labeler revises a 5-minute label at 15 m, 30 m, 1 h, 4 h, 8 h, 12 h and 24 h
    // while the coin lives (7997). The Cortex graded once at 5 minutes and never heard again, so
    // every voter, the scoreboard buckets, the veto audit and the setup×verdict book learned what
    // a coin did in its first five minutes. Graded decisions are kept (bounded; the weakest
    // decisions age out without selecting by outcome) and revisions retain their original weight.
    private class Graded8006(
        val p: Pending, val trace: CortexLedger7885.GradeTrace8006, var net: Double, var gross: Double, val atMs: Long,
        val bookScales: DoubleArray? = null, val auxScales: DoubleArray? = null,
        /** V5.0.8322 — the calibration grade's decay stamp, so a runner lane's hold-window revision re-calibrates it. */
        val calStamp8322: Double = Double.NaN,
    ) {
        /** V5.0.8324 — the booked label was chain-witnessed (the revision replaces it with its own provenance). */
        @Volatile var witnessed8324: Boolean = false
        var jointTrace8372: List<JointContribution8372> = emptyList()
    }
    private val graded8006 = HashMap<String, Graded8006>()
    private val traceArchive8372 = CortexTraceArchive8372()
    private const val MAX_GRADED_8006 = 600
    private const val GRADED_PERSIST_MAX_8013 = MAX_GRADED_8006
    private const val GRADED_KEEP_MS_8013 = 7L * 24L * 3_600_000L + 10L * 60_000L
    private val revised8006 = java.util.concurrent.atomic.AtomicLong(0)
    private val revisedUp8006 = java.util.concurrent.atomic.AtomicLong(0)

    private fun retain8006(key: String, p: Pending, trace: CortexLedger7885.GradeTrace8006, net: Double, gross: Double, bookScales: DoubleArray?, auxScales: DoubleArray?,
                           calStamp8322: Double = Double.NaN) {
        if (graded8006.size >= MAX_GRADED_8006) {
            val cutoff = System.currentTimeMillis() - GRADED_KEEP_MS_8013
            graded8006.entries.removeIf { it.value.atMs < cutoff }
            if (graded8006.size >= MAX_GRADED_8006) evictPastHorizon8324(System.currentTimeMillis())
            if (graded8006.size >= MAX_GRADED_8006) graded8006.entries.minByOrNull { it.value.atMs }?.let { old ->
                archiveGraded8372(old.key, old.value, System.currentTimeMillis())
                graded8006.remove(old.key)
            }
        }
        graded8006[key] = Graded8006(p, trace, net, gross, p.atMs, bookScales, auxScales, calStamp8322)
    }

    private fun archiveGraded8372(id: String, g: Graded8006, nowMs: Long) {
        val dict = LinkedHashMap<String, Int>()
        val row = encodeGraded8372(id, g, dict)
        val payload = org.json.JSONObject().put("dict", org.json.JSONArray(dict.keys.toList())).put("row", row).toString()
        if (!traceArchive8372.put(id, g.atMs, g.atMs + GRADED_KEEP_MS_8013, payload, nowMs)) inc("TRACE_ARCHIVE_CAPACITY_8372")
    }

    /** Decode only the one exact old episode being revised; cold traces never re-enter the hot cache en masse. */
    private fun gradedTrace8372(id: String, nowMs: Long = System.currentTimeMillis()): Graded8006? {
        graded8006[id]?.let { return it }
        val raw = traceArchive8372.get(id, nowMs) ?: return null
        return try {
            val root = org.json.JSONObject(raw); val dict = root.getJSONArray("dict")
            decodeGraded8372(root.getJSONObject("row"), List(dict.length()) { dict.getString(it) }, nowMs)
                ?.takeIf { it.first == id }?.second
        } catch (_: Throwable) { inc("TRACE_ARCHIVE_DECODE_ERROR_8372"); null }
    }

    /**
     * Caller holds the lock. V5.0.8324 — a full trace store drops first the traces past their lane's executable hold (a
     * revision there no longer reaches any book): 8006 dropped the OLDEST, which at 8321 grade rates was every runner-lane
     * decision before its 4 h checkpoint, so the long horizon never reached the voters, the buckets or the runner rate.
     */
    private fun evictPastHorizon8324(nowMs: Long): Int {
        val before = graded8006.size
        graded8006.entries.removeIf { e ->
            !CortexLabelHygiene8144.revisionCounts8144(runnerHorizon8144(e.value.p.a.lane), nowMs - e.value.atMs) &&
                !outcomeBook8372.awaitingShadowFor(e.key)
        }
        val n = before - graded8006.size
        if (n > 0) counters.computeIfAbsent("GRADED_TRACE_PAST_HORIZON_EVICTED_8324") { AtomicLong(0) }.addAndGet(n.toLong())
        return n
    }

    /**
     * V5.0.8371 — revise exactly the observation that produced this grade. Reobservations
     * of the same token/lane are different decisions: an older runner must never rewrite
     * a newer trade verdict. The compact grade record survives full trace eviction.
     */
    private fun reviseGradeOnly8324(key: String, decisionAtMs8371: Long, newNet: Double, newGross: Double,
                                    latencyMeasured: Boolean, witness: Boolean = false, proof8395: CortexOutcomeProof8395? = null) {
        val id = "$key|$decisionAtMs8371"
        val r = gradeBook8144.decision(id) ?: run { inc("REVISION_DECISION_MISSING_8371"); return }
        if (r.outcomeSource8372 == "SHADOW_EXIT") { inc("FORWARD_REVISION_AFTER_SHADOW_8372"); return }
        val exec = CortexLabelHygiene8144.revisionCounts8144(runnerHorizon8144(r.lane),
            (proof8395?.observedAtMs ?: System.currentTimeMillis()) - r.atMs)
        val oldNet = CortexOutcomeProof8395.decode8395(r.revisedOutcomeProof8395.ifBlank { r.firstOutcomeProof8395 })?.netPct ?: r.observedNetPct
        val mayReviseCalibration = exec && r.graded && r.executablePct.isFinite() && oldNet.isFinite()
        val now8395 = System.currentTimeMillis()
        val result8395 = gradeBook8144.labelOutcome8395(id, newNet, newGross,
            CortexLabelHygiene8144.executablePct8144(newNet, latencyMeasured), exec,
            proof8395?.horizonMs ?: (now8395-r.atMs).coerceAtLeast(1L), proof8395?.observedAtMs ?: now8395, now8395, proof8395)
        if (mayReviseCalibration && result8395.accepted && result8395 != CortexOutcomeDisposition8395.DUPLICATE_ACCEPTED) {
            if (calibration.revise8322(r.lane, r.rawEdge8371, r.laneMean8371, oldNet, newNet, r.calStamp8371))
                inc("CALIBRATION_GRADE_ONLY_REVISED_8371")
            // The 600-trace hot store cannot reconstruct every original voter/scoreboard
            // contribution. Count this gap rather than claim the model was fully retrained.
            inc("REVISION_VOTER_TRACE_MISSING_8371")
        }
        inc(if (exec) "REVISION_GRADE_ONLY_8324" else "REVISION_GRADE_ONLY_PAST_HORIZON_8324")
    }

    /** ForwardReturnLabeler7731.revise7997: use the original observation timestamp, never just mint + lane. */
    fun reviseLabel8006(mint: String, labelLane: String, oldNet: Double, newNet: Double, oldGross: Double, newGross: Double,
                        witness8324: Boolean = false, decisionAtMs8371: Long = 0L, proof8395: CortexOutcomeProof8395? = null) {
        if (!newNet.isFinite()) return
        if (decisionAtMs8371 <= 0L) { inc("REVISION_MISSING_EPISODE_ID_8371"); return }
        val key = "$mint|${labelLane.trim().uppercase()}"
        val id = "$key|$decisionAtMs8371"
        if (durableTrainingKind8372(id) == "SHADOW_EXIT") { inc("FORWARD_REVISION_AFTER_SHADOW_8372"); return }
        val lat8144 = latencyMeasured8144(canon(labelLane))
        var revisedPending8372: Pending? = null
        synchronized(this) {
            if (outcomeBook8372.shadowHasLearnedFor(id)) { inc("FORWARD_REVISION_AFTER_SHADOW_8372"); return }
            val record = gradeBook8144.decision(id)
            if (record?.outcomeSource8372 == "SHADOW_EXIT") { inc("FORWARD_REVISION_AFTER_SHADOW_8372"); return }
            if (record?.lateEntry8153 == true || record?.lateRead8187 == true ||
                record?.flat8322 == true || record?.graded == false || (record != null && !record.executablePct.isFinite())) {
                if (gradeBook8144.label(id, newNet, newGross, Double.NaN, false))
                    inc("REVISION_OBSERVED_ONLY_8153")
                return
            }
            val g = gradedTrace8372(id) ?: run {
                reviseGradeOnly8324(key, decisionAtMs8371, newNet, newGross, lat8144, witness8324, proof8395)
                return
            }
            // V5.0.8144 — the observed record follows every horizon; the books only revise inside the lane's executable hold.
            val exec8144 = CortexLabelHygiene8144.revisionCounts8144(runnerHorizon8144(g.p.a.lane),
                (proof8395?.observedAtMs ?: System.currentTimeMillis()) - g.p.atMs)
            val now8395 = System.currentTimeMillis()
            val result8395 = gradeBook8144.labelOutcome8395(id,newNet,newGross,CortexLabelHygiene8144.executablePct8144(newNet,lat8144),exec8144,
                proof8395?.horizonMs ?: (now8395-g.p.atMs).coerceAtLeast(1L),proof8395?.observedAtMs ?: now8395,now8395,proof8395)
            if (!exec8144) { inc("REVISION_PAST_EXEC_HORIZON_8144"); return }
            if (!result8395.accepted) { inc("REVISION_VALUE_OBSERVED_ONLY_8395"); return }
            if (result8395 == CortexOutcomeDisposition8395.DUPLICATE_ACCEPTED) return
            if (!reviseModelsLocked8372(id, g, newNet, newGross, proof8395?.witnessed8395() == true)) return
            revisedPending8372 = g.p
        }
        revisedPending8372?.let { sourceOutcome8372(key, it, newNet, revision = true) }
        revised8006.incrementAndGet()
        if (newNet > oldNet) revisedUp8006.incrementAndGet()
    }

    /** Replace the original weighted contribution, never add a second sample or change its frozen YES/NO. */
    private fun reviseModelsLocked8372(id: String, g: Graded8006, newNet: Double, newGross: Double, witness: Boolean,
                                       shadowExit8372: Boolean = false): Boolean {
        if (g.trace.scales == null || g.trace.scoreAt == null || g.bookScales == null || g.auxScales == null || !g.net.isFinite()) {
            inc("REVISION_LEGACY_WEIGHT_UNKNOWN"); return false
        }
        val oldShadow8404 = g.trace.shadowNet8404
        val newShadow8404 = shadowExit8372 || oldShadow8404
        val a = g.p.a; val oldNet = g.net; val oldGross = g.gross; val wasWitnessed = g.witnessed8324
        ledger.revise8006(a.lane, a.ids, a.edges, a.raws, g.trace, oldNet, newNet, oldGross, newGross, a.regime, wasWitnessed, witness, shadowNet8404 = newShadow8404)
        if (calibration.revise8322(a.lane, a.fused.edgePct, a.fused.laneMean, oldNet, newNet, g.calStamp8322,
                targetBasis8372 = if (shadowExit8372) CortexCalibration7901.TARGET_SHADOW_NET_8372 else null)) inc("CALIBRATION_REVISED_8322")
        board.revise8006(a.lane, a.bucket, g.p.legacyAdmitted, oldNet, newNet, oldGross, newGross, g.bookScales, wasWitnessed, witness, oldShadow8404, newShadow8404)
        val ro = oldGross >= CortexLedger7885.RUNNER_GROSS_PCT; val rn = newGross >= CortexLedger7885.RUNNER_GROSS_PCT
        fun reviseAux(st: CortexLedger7885.Stat?, index: Int) {
            st?.revise(oldNet.coerceAtLeast(CortexLedger7885.Y_MIN), newNet.coerceAtLeast(CortexLedger7885.Y_MIN), ro, rn,
                st.revisionWeight(g.auxScales[index]), wasWitnessed, witness, oldShadow8404, newShadow8404)
        }
        g.p.vetoRule?.let { r -> reviseAux(vetoBook[r], 1); reviseAux(vetoBook[vetoLaneKey8006(r, a.lane)], 2) }
        for (contribution in g.jointTrace8372) {
            if (jointGenerations8372[contribution.key] != contribution.generation) {
                inc("JOINT_REVISION_RECREATED_CELL_8372"); continue
            }
            val st = jointBook7961[contribution.key] ?: continue
            st.revise(oldNet.coerceAtLeast(CortexLedger7885.Y_MIN), newNet.coerceAtLeast(CortexLedger7885.Y_MIN), ro, rn,
                st.revisionWeight(contribution.scale), wasWitnessed, witness, oldShadow8404, newShadow8404)
        }
        if (g.jointTrace8372.isEmpty() && (g.p.setup7961.isNotBlank() || g.p.fired8094.isNotEmpty()))
            inc("JOINT_REVISION_LEGACY_GENERATION_UNKNOWN_8372")
        g.net = newNet; g.gross = newGross; g.witnessed8324 = witness
        if (!graded8006.containsKey(id)) archiveGraded8372(id, g, System.currentTimeMillis())
        return true
    }

    fun progressiveLine8006(): String = "retained=${synchronized(this) { graded8006.size }} cold8372=${traceArchive8372.size()} coldBytes=${traceArchive8372.bytes()} revised=${revised8006.get()} up=${revisedUp8006.get()} archiveLoss=${traceArchive8372.counts().capacityEvicted + traceArchive8372.counts().capacityRejected}"

    /** Pure: a stable rule id from a refusal reason (leading upper-case words, max 4). */
    fun vetoRuleOf(reason: String): String {
        val base = reason.trim().split('_', ':', ' ').filter { w -> w.isNotEmpty() && !w.all { it.isDigit() } }
            .takeWhile { w -> w.all { it.isUpperCase() || it.isDigit() } }
            .take(4).joinToString("_").ifBlank { "UNNAMED" }
        // V5.0.8079 §SETUP_SCOPED_VETO_RETIREMENT. A setup refusal names its lane and setup
        // ("PLAYBOOK_SETUP_PROVEN_LOSING_7907_MOONSHOT_LAUNCH_CONTINUATION") but the four-word cut kept only
        // "PLAYBOOK_SETUP_PROVEN_LOSING", so the veto audit graded every proven-losing setup of a lane as one rule.
        // 5.0.7951's refused SHITCOIN launch-ladder winners retired it for the whole lane and LAUNCH_CONTINUATION
        // (-32.7% MOONSHOT, -38.0% EXPRESS) walked back into live money. The rule is now graded per lane+setup:
        // a winning setup's refusal still retires; a losing setup stays refused.
        if (base in SETUP_SCOPED_RULES_8079) {
            val scope = SETUP_SCOPE_8079.find(reason.trim())?.groupValues?.getOrNull(1).orEmpty()
            if (scope.isNotBlank()) return "$base@$scope"
        }
        return base
    }

    private val SETUP_SCOPED_RULES_8079: Set<String> by lazy { setOf("PLAYBOOK_SETUP_PROVEN_LOSING", "PLAYBOOK_SETUP_EXPECTED_NEGATIVE") }
    private val SETUP_SCOPE_8079: Regex by lazy { Regex("""^PLAYBOOK_SETUP_(?:PROVEN_LOSING|EXPECTED_NEGATIVE)_\d+_([A-Z0-9_]+)""") }

    // ── Constitution (refuse-only; every refusal names its rule) ──

    /** Pure rule check over an assessment. Returns the rule id that refuses, or null. */
    private fun constitutionRefusal(a: Assessment, ts: TokenState, paper: Boolean, refusalProven: Boolean): String? {
        if (!paper && ts.safety.tier == com.lifecyclebot.engine.SafetyTier.HARD_BLOCK) return "C1_HARD_SAFETY"
        // V5.0.7906 — C2_MARK_STALE removed: 5.0.7891 refused 852 live candidates on
        // ts.lastPriceUpdate alone, while most candidates are priced through the
        // canonical mark registry (FieldManual7715 already refuses an impaired
        // quote with the registry in view). Staleness stays an input to overrule
        // and conviction, never a refusal of its own.
        if (a.bucket == CortexScoreboard7885.Bucket.REFUSE && refusalProven) {
            // V5.0.7961 — the lane's refuse bucket is an average over every setup it holds; a
            // setup whose own record INSIDE that bucket is proven positive is not refused.
            val setup = try { LanePlaybook7907.selectedSetup8094(ts, a.lane, System.currentTimeMillis()) } catch (_: Throwable) { null }  // V5.0.8094
            val st = setup?.let { synchronized(this) { cohort8372(a.lane, it)[a.bucket.ordinal] } }
            if (jointProvenPositive7961(st)) { inc("C3_STOOD_DOWN_SETUP_PROVEN_7961"); return null }
            // V5.0.8056 — or the setup's whole lane record is proven (the lane-wide refuse bucket averages every setup;
            // SHITCOIN refuse n243 -12% while its ACCELERATING_TAPE coins read n61 +23%).
            if ((try { LanePlaybook7907.provenSetup8056(ts, a.lane) } catch (_: Throwable) { null }) != null) { inc("C3_STOOD_DOWN_LANE_SETUP_PROVEN_8056"); return null }
            // V5.0.8094 — or this token's own cell / kind is proven in the lane (the refuse bucket averages every coin).
            if (try { com.lifecyclebot.engine.truth.LiveEdgeGate7877.cellOrKindProven8094(ts, a.lane) } catch (_: Throwable) { false }) { inc("C3_STOOD_DOWN_CELL_PROVEN_8094"); return null }
            // V5.0.8395 (R4) — C3 safety is preserved (still refuses); an incomplete-proof negative is counted UNKNOWN.
            return negativeOrUnknown8395("C3_PROVEN_NEGATIVE_EDGE", a.lane)
        }
        if (a.bucket != CortexScoreboard7885.Bucket.REFUSE) {
            val setup8082 = try { LanePlaybook7907.selectedSetup8094(ts, a.lane, System.currentTimeMillis()) } catch (_: Throwable) { null }  // V5.0.8094
            val st8082 = setup8082?.let { synchronized(this) { cohort8372(a.lane, it)[a.bucket.ordinal] } }
            // V5.0.8396 (R4/R5) — a setup-dead negative binds only when its OWN cohort (lane, setup, bucket) has full outcome
            // coverage. 8395 bound it on any n/se test: NEGATIVE_PROOF_UNKNOWN_8395_C6_SETUP_DEAD_8082=38 beside
            // REFUSED_LIVE_C6_SETUP_DEAD_8082=38, while MOONSHOT/CORE/EXPRESS had 85-89% of matured assessments without
            // an outcome — a mean over the observable survivors is not a scoped negative. Uncovered = UNKNOWN, never a
            // refusal and never a BUY: the decision falls to the next precedence level (candidate proof, then lane prior).
            if (jointProvenDead8082(st8082)) {
                if (setupCovered8396(a.lane, setup8082!!, a.bucket.ordinal)) return negativeOrUnknown8395("C6_SETUP_DEAD_8082", a.lane)
                inc("C6_SETUP_DEAD_COVERAGE_INCOMPLETE_NONBINDING_8396")
            }
        }
        if (!paper && a.bucket == CortexScoreboard7885.Bucket.NEUTRAL && lastSlot(ts)) {
            // V5.0.8395 (R4) — a slot is saved only on the shared VALID proof and a STRONG read that could actually execute now.
            val slotPriority8395 = synchronized(this) { slotPriorityCurrent8372(a.lane) && consistent(a.lane) }
            if (slotPriority8395 && CortexPredictiveProof8395.slotReservable(proofStatus8395(a.lane), eligibleStrongNow8395(a.lane, ts.mint)))
                return "C5_SAVE_LAST_SLOT_FOR_STRONG"
            if (slotPriority8395) inc("C5_SLOT_NOT_RESERVED_NO_PROOF_OR_CANDIDATE_8395")
            inc("SHADOW_SAVE_SLOT")
        }
        return null
    }

    // ── Cortex v9: self-consistency (V5.0.7904) ──
    //
    // A record can clear the bar while the Cortex's own predictions are badly
    // calibrated (it said +8% where +1% happened). Authority — refusal,
    // overrule, conviction sizing, slot saving — is only exercised while the
    // lane's calibration slope is at least [MIN_CONSISTENT_SLOPE]: a Cortex
    // whose deviations have stopped meaning what they say loses its say until
    // they do again. Caller holds the lock.
    private const val MIN_CONSISTENT_SLOPE = 0.5

    private fun consistent(lane: String): Boolean {
        val ok = !calibration.suspends8372(lane, MIN_CONSISTENT_SLOPE, executableOffset8187(lane))
        if (!ok) inc("SUSPENDED_INCONSISTENT_$lane")
        return ok
    }

    // ── V5.0.7955 §GRADUATED_AUTHORITY ──
    //
    // A lane's STRONG-side powers scale with CortexScoreboard7885.authorityFraction7955
    // instead of switching on at 40 grades: the conviction size-up is scaled by the
    // fraction, and the overrule / paper choice / stack-shrink overrule open at
    // [OVERRULE_FRACTION_7955]. A self-inconsistent lane (v9) holds 0.
    private const val OVERRULE_FRACTION_7955 = 0.5

    /** Caller holds the lock. */
    private fun laneFraction7955(lane: String): Double =
        laneFractionHeld8187(lane).also { if (!(it > 0.0)) { inc("AUTHORITY_BLOCKED_8187_${lane}_${blockCode8187(lane)}"); inc("AUTHORITY_8366_${lane}_${authorityCode8366(lane)}") } }  // V5.0.8187 / V5.0.8366

    /**
     * Caller holds the lock. The graduated authority [laneFraction7955] returns. V5.0.8366 §ONE_AUTHORITY_TEST — the one
     * walk-forward test ([authorityFraction8366]); the 7955 x 8136 x 8144 x 8153 stack it replaces is still computed for the
     * screens ([legacyFraction8366], [blockCode8187]).
     */
    private fun laneFractionHeld8187(lane: String): Double = authorityFraction8366(lane)

    /** Caller holds the lock. The pre-8366 stacked authority (label fraction x calibration x realised x walk-forward): display only. */
    private fun legacyFraction8366(lane: String): Double {
        val f = board.fractionFor7955(lane, accountMode8077())
        return if (f > 0.0 && consistent(lane)) walkForwardCapped8144(lane, null, f, realisedCapped8136(lane, f)) else 0.0
    }

    // ── V5.0.8366 §ONE_AUTHORITY_TEST (CortexAuthority8366) ──
    private data class AuthorityCache8372(val atMs: Long, val gradeRevision: Long, val value: CortexAuthority8366.Result8366)
    private val auth8366Cache = HashMap<String, AuthorityCache8372>()
    private data class CohortCache8372(val revision: Long, val executionOffset: Double, val buckets: Array<CortexLedger7885.Stat>)
    private val cohortCache8372 = HashMap<String, CohortCache8372>()

    /** Caller holds the lock. Read-only snapshots; never hand these mutable Stat instances to a learner. */
    private fun cohort8372(lane: String, setup: String? = null): Array<CortexLedger7885.Stat> {
        val key = "$lane|${setup ?: "*"}"
        val offset = executableOffset8187(lane)
        cohortCache8372[key]?.let { if (it.revision == gradeBook8144.revision && it.executionOffset == offset) return it.buckets }
        val buckets = gradeBook8144.bucketStats8372(lane, setup)
        if (cohortCache8372.size > 600) cohortCache8372.clear()
        cohortCache8372[key] = CohortCache8372(gradeBook8144.revision, offset, buckets)
        return buckets
    }

    private fun invertedCurrent8372(lane: String): Boolean {
        val b = cohort8372(lane)
        val runner = runnerHorizon8144(lane)
        return CortexScoreboard7885.invertedProven7948(b[2], b[1], runner) ||
            CortexScoreboard7885.invertedProven7948(b[2], b[0], runner)
    }

    /** Existing absolute / relative refusal and last-slot bars, on the same frozen current-predictor cohorts. */
    private fun restCurrent8372(lane: String): CortexLedger7885.Stat = CortexLedger7885.realisedStat8187().also { rest ->
        val b = cohort8372(lane)
        for (i in listOf(CortexScoreboard7885.Bucket.NEUTRAL.ordinal, CortexScoreboard7885.Bucket.STRONG.ordinal)) {
            val s = b[i]
            rest.n += s.n; rest.sum += s.sum; rest.sumSq += s.sumSq
            rest.runners += s.runners; rest.wins += s.wins; rest.witnessed8324 += s.witnessed8324
        }
    }

    private fun evidenceCurrent8372(lane: String): Double {
        val b = cohort8372(lane)
        return CortexScoreboard7885.authorityFraction7955(b[2], b[1], invertedCurrent8372(lane))
    }

    private fun refusalCurrent8372(lane: String, runner: Boolean, paper: Boolean): Boolean =
        refuseCoverage8392(lane) && CortexScoreboard7885.refuseProven(cohort8372(lane)[0], restCurrent8372(lane), runner,
            if (paper) CortexScoreboard7885.MIN_N_PAPER else CortexScoreboard7885.MIN_N_LIVE)

    private fun relativeRefusalCurrent8372(lane: String, runner: Boolean): Boolean =
        refuseCoverage8392(lane) && CortexScoreboard7885.relativeRefuseProven8019(cohort8372(lane)[0], restCurrent8372(lane), evidenceCurrent8372(lane), runner)

    /** Read-only display of the existing refusal paths, without incrementing action counters. */
    private fun refusalPowerCurrent8372(lane: String, runner: Boolean, paper: Boolean): Boolean {
        if (calibration.suspends8372(lane, MIN_CONSISTENT_SLOPE, executableOffset8187(lane))) return false
        if (!paper && refuseShadow8310(lane, null) != null) return false
        return refusalCurrent8372(lane, runner, paper) || refusalAuthority8269(lane, runner, paper) ||
            (!paper && (relativeRefusalCurrent8372(lane, runner) ||
                (heldFraction8153(lane) >= OVERRULE_FRACTION_7955 && refusalAuthority8269(lane, runner, false, relative = true))))
    }

    private fun slotPriorityCurrent8372(lane: String): Boolean {
        val b = cohort8372(lane)
        return CortexScoreboard7885.overruleProven(b[2]) && b[1].n >= CortexScoreboard7885.MIN_N_LIVE &&
            b[2].mean() - b[1].mean() > CortexScoreboard7885.PROOF_MARGIN_PCT
    }

    /** The frozen simulator owns its terminal deadline; a mark/retry never extends it from wall-clock now. */
    fun shadowMaturity8394(mint: String, lane: String, assessmentAtMs: Long, kind: String, dueAtMs: Long) {
        if (dueAtMs <= assessmentAtMs || kind !in setOf("TERMINAL_SCALP", "TERMINAL_RUNNER", "TERMINAL_HOLD")) return
        synchronized(this) {
            val id = "$mint|$lane|$assessmentAtMs"
            val p = shadowPending8372[id] ?: return@synchronized
            if (p.outcomeKind8394 != kind || p.outcomeDueAtMs8394 != dueAtMs) {
                p.outcomeKind8394 = kind; p.outcomeDueAtMs8394 = dueAtMs
                refusalCoverageCache8392.remove(lane)
            }
            val bound = outcomeBook8372.shadowPlan(id).decisionId.ifBlank { id }
            gradeBook8144.decision(bound)?.let { it.outcomeKind8394 = kind; it.outcomeDueAtMs8394 = dueAtMs }
        }
    }

    /** Frozen unresolved decisions participate in coverage, never as invented gains/losses. Caller holds lock. */
    private fun authorityRows8392(lane: String, setup: String? = null): List<CortexAuthority8366.Row8366> {
        // A forward alias retains its terminal owner's deadline, including after restore.
        for ((id, p) in shadowPending8372) {
            gradeBook8144.decision(outcomeBook8372.shadowPlan(id).decisionId.ifBlank { id })?.let {
                it.outcomeKind8394 = p.outcomeKind8394; it.outcomeDueAtMs8394 = p.outcomeDueAtMs8394
            }
        }
        val rows=gradeBook8144.scoredRows8366(lane,runnerHorizon8144(lane),scoreVersion=1,setup=setup).toMutableList()
        val seen=HashSet<String>()
        fun append(id: String,p: Pending) {
            if (!seen.add(id) || gradeBook8144.decision(id)!=null || p.a.lane!=lane || p.a.scoreVersion8372!=1 || p.lateRead8169 || p.a.staleMark) return
            if (setup!=null && p.selected8153.ifBlank { p.setup7961 }!=setup) return
            val key=id.substringBeforeLast('|')
            if (!verdict8144(key,p,p.context8372).authority) return
            rows += CortexAuthority8366.Row8366(p.atMs,p.a.calibratedEdge,Double.NaN,p.a.bucket.ordinal,0L,
                p.outcomeDueAtMs8394)
        }
        for ((key,p) in pending) append("$key|${p.atMs}",p)
        for ((id,p) in pairedPending8401) append(id,p)
        for ((id,p) in shadowPending8372) append(outcomeBook8372.shadowPlan(id).decisionId.ifBlank { id },p)
        return rows
    }

    private data class RefusalCoverage8392(val atMs: Long, val revision: Long, val pendingN: Int, val covered: Boolean)
    private val refusalCoverageCache8392 = HashMap<String,RefusalCoverage8392>()
    private fun refuseCoverage8392(lane: String): Boolean {
        val now=System.currentTimeMillis();val n=pendingCount()+shadowPending8372.size
        refusalCoverageCache8392[lane]?.let {
            if(it.revision==gradeBook8144.revision && it.pendingN==n && now-it.atMs in 0L until WF_REFRESH_MS_8144) return it.covered
        }
        val covered=CortexAuthority8366.cohortCovered8392(authorityRows8392(lane),CortexScoreboard7885.Bucket.REFUSE.ordinal,now)
        if(refusalCoverageCache8392.size>128) refusalCoverageCache8392.clear()
        refusalCoverageCache8392[lane]=RefusalCoverage8392(now,gradeBook8144.revision,n,covered)
        return covered
    }

    private val setupCoverageCache8396 = HashMap<String, RefusalCoverage8392>()
    /** V5.0.8396 — the [setup] cohort's own outcome coverage for [bucket] (same walk-forward rows), cached like [refuseCoverage8392]. */
    private fun setupCovered8396(lane: String, setup: String, bucket: Int): Boolean = synchronized(this) {
        val now = System.currentTimeMillis(); val n = pendingCount() + shadowPending8372.size; val k = "$lane|$setup|$bucket"
        setupCoverageCache8396[k]?.let {
            if (it.revision == gradeBook8144.revision && it.pendingN == n && now - it.atMs in 0L until WF_REFRESH_MS_8144) return@synchronized it.covered
        }
        val covered = try { CortexAuthority8366.cohortCovered8392(authorityRows8392(lane, setup), bucket, now) } catch (_: Throwable) { false }
        if (setupCoverageCache8396.size > 256) setupCoverageCache8396.clear()
        setupCoverageCache8396[k] = RefusalCoverage8392(now, gradeBook8144.revision, n, covered)
        covered
    }

    /** Caller holds the lock. [lane]'s one walk-forward test on its graded executable records, refreshed every 30 s. */
    /** V5.0.8395 (R4) — the one predictive-proof status for [lane] (same walk-forward rows as the positive door). */
    private fun proofStatus8395(lane: String): CortexPredictiveProof8395.Status = try {
        synchronized(this) { CortexPredictiveProof8395.of(lane, gradeBook8144.revision, authorityTest8366(lane)).status }
    } catch (_: Throwable) { CortexPredictiveProof8395.Status.UNKNOWN }

    /** The negative still binds (never a BUY); its proof standing is named so an UNKNOWN negative is visible. */
    private fun negativeOrUnknown8395(rule: String, lane: String): String {
        if (proofStatus8395(lane) != CortexPredictiveProof8395.Status.VALID) inc("NEGATIVE_PROOF_UNKNOWN_8395_$rule")
        return rule
    }

    /** A STRONG read in [lane], other than [mint], fresh and priced: a saved slot would actually be used. */
    private fun eligibleStrongNow8395(lane: String, mint: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        assessCache.entries.any { (k, v) -> v.lane == lane && !k.startsWith("$mint|") && v.bucket == CortexScoreboard7885.Bucket.STRONG &&
            !v.staleMark && nowMs - v.atMs in 0L..ASSESS_TTL_MS }

    private fun authorityTest8366(lane: String, setup: String? = null): CortexAuthority8366.Result8366 {
        val now = System.currentTimeMillis()
        // A cost refresh changes both the executable cohort and its proof margin without adding a grade. Never reuse
        // a previously granted proof at another cost basis, even inside the normal refresh interval.
        val offset = executableOffset8187(lane).takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val cost = try { com.lifecyclebot.engine.truth.CostLedger7962.laneCostPct7962(lane) } catch (_: Throwable) { CortexAuthority8366.MIN_FULL_MARGIN_PCT_8366 }
        val key = "$lane|${setup ?: "*"}|offset=$offset|cost=$cost|pending=${pendingCount()}:${shadowPending8372.size}"
        auth8366Cache[key]?.let { if (it.gradeRevision == gradeBook8144.revision && now - it.atMs in 0L until WF_REFRESH_MS_8144) return it.value }
        val r = CortexAuthority8366.test8372(authorityRows8392(lane, setup), cost, now)
        if (auth8366Cache.size > 200) auth8366Cache.clear()
        auth8366Cache[key] = AuthorityCache8372(now, gradeBook8144.revision, r)
        return r
    }

    /** Caller holds the lock. [lane]'s STRONG-side realised closes in the account mode: (n, mean %). */
    private fun realisedStrong8366(lane: String): Pair<Double, Double> {
        val s = board.realized["${accountMode8077()}|$lane"]?.getOrNull(CortexScoreboard7885.Bucket.STRONG.ordinal)
        return (s?.n ?: 0.0) to (if (s != null && s.n > 0.0) s.mean() else Double.NaN)
    }

    /** Caller holds the lock. The authority [lane] holds: the granted walk-forward fraction unless UNCALIBRATED or revoked. */
    private fun authorityFraction8366(lane: String): Double {
        val (n, mean) = realisedStrong8366(lane)
        return CortexAuthority8366.authority8366(authorityTest8366(lane), calibration.uncalibrated8372(lane, executableOffset8187(lane)), n, mean)
    }

    /** Caller holds the lock. What keeps [lane]'s authority at 0 (or OPEN). */
    private fun authorityCode8366(lane: String): String {
        val (n, mean) = realisedStrong8366(lane)
        return CortexAuthority8366.code8366(authorityTest8366(lane), calibration.uncalibrated8372(lane, executableOffset8187(lane)), n, mean)
    }

    /** Caller holds the lock. "LANE code[test]" for the top lanes, with the legacy stack's fraction beside it. */
    private fun authorityLine8366(): String = "one-test8366[" + board.books.entries.sortedByDescending { it.value.byBucket.sumOf { b -> b.n } }.take(8)
        .joinToString(" · ") { (lane, _) ->
            "$lane ${authorityCode8366(lane)}[${authorityTest8366(lane).compact()} legacy=${"%.2f".format(legacyFraction8366(lane))}/${"%.2f".format(heldFractionLegacy8366(lane))}${if (firstSightBarredLegacy8366(lane)) " legacyBarred" else ""}]"
        }.ifBlank { "-" } + "]"

    /**
     * V5.0.8136 — caller holds the lock. [labelFraction] capped by this lane's realised STRONG closes in the account
     * mode (CortexRealisedAuthority8136): positive labels with losing real closes earn no sizing / admission power.
     */
    private fun realisedCapped8136(lane: String, labelFraction: Double): Double {
        if (!(labelFraction > 0.0)) return 0.0
        val s = board.realized["${accountMode8077()}|$lane"]?.getOrNull(CortexScoreboard7885.Bucket.STRONG.ordinal)
        val n = s?.n ?: 0.0
        val mean = s?.mean() ?: 0.0
        val se = if (s != null && s.n > 1.0) kotlin.math.sqrt(s.variance() / s.n) else Double.POSITIVE_INFINITY
        val capped = CortexRealisedAuthority8136.cappedFraction8136(labelFraction, n, mean, se)
        if (capped < labelFraction) {
            inc(if (capped <= 0.0) "AUTHORITY_DENIED_REALISED_NEGATIVE_8136" else "AUTHORITY_CAPPED_REALISED_UNPROVEN_8136")
        }
        return capped
    }

    // ── V5.0.8187 §EXECUTABLE_LABELS — authority reads the label a trade could have banked ──
    //
    // 8186: SHITCOIN|LAUNCH_LADDER_PROVEN setup record n578 +103% auth=1.00 while its live trades closed n2 -47.9% (SHITCOIN
    // realised n5 -29.6%, canonical PF 0.40). A forward label is a mark move from the (re-based) decision price to the horizon
    // under the simulated lane play; a trade pays the cost at its ticket and gets the fills and exits it actually gets. Per lane:
    //   cost gap   the round trip at our ticket (NetEdge8030: measured or modelled, whichever is higher, plus the chase when the
    //              labels were not re-based at a measured latency) above the cost the labels were charged
    //              (CostLedger7962.labelChargedCostPct8153);
    //   shortfall  on the SAME trades, label minus realised whole-position return, shrunk toward 0 by n / (n + 5);
    //   offset     the larger of the two, 0..60 points.
    // Every authority read of a label book (scoreboard buckets, verdict x setup, veto books, the walk-forward / tradeable
    // series, playbook setup records, the labeler's cell proofs, the bucket a read falls in) subtracts the lane offset; the
    // stored label is unchanged and still shown. A setup whose own realised closes contradict its labels (n >= 2, opposite
    // sign) has its record blended toward them (realisedBlend8187). Refreshed every minute under the Cortex lock; read lock-free.
    private const val EXEC_REFRESH_MS_8187 = 60_000L
    private const val SHORTFALL_SHRINK_K_8187 = 5.0
    const val MAX_EXEC_OFFSET_PCT_8187 = 60.0
    private val execOffsetByLane8187 = ConcurrentHashMap<String, Double>()
    private val realisedBySetup8187 = ConcurrentHashMap<String, DoubleArray>()   // "LANE|SETUP" -> [n, mean]
    @Volatile private var lastExecRefresh8187 = 0L

    /** Pure (V5.0.8187): the lane offset from its cost gap and its paired shortfall ([pairs], [meanShortfallPct]). */
    fun execOffset8187(costGapPct: Double, pairs: Double, meanShortfallPct: Double): Double {
        val gap = if (costGapPct.isFinite()) costGapPct.coerceAtLeast(0.0) else 0.0
        val shrunk = if (pairs >= 1.0 && meanShortfallPct.isFinite()) meanShortfallPct * pairs / (pairs + SHORTFALL_SHRINK_K_8187) else 0.0
        return maxOf(gap, shrunk).coerceIn(0.0, MAX_EXEC_OFFSET_PCT_8187)
    }

    /** Pure (V5.0.8187): the cost the labels were not charged: the round trip at the ticket (+ unmeasured chase) above the charged cost. */
    fun costGap8187(costAtTicketPct: Double, chasePct: Double, chargedPct: Double): Double {
        if (!costAtTicketPct.isFinite() || !chargedPct.isFinite()) return 0.0
        return (costAtTicketPct + (if (chasePct.isFinite()) chasePct.coerceAtLeast(0.0) else 0.0) - chargedPct).coerceAtLeast(0.0)
    }

    /**
     * Pure (V5.0.8187): a label record ([nL] labels, mean [meanL]) blended with the realised closes of the same setup ([nR],
     * [meanR]) when they contradict it (nR >= 2, opposite sign). Labels count at most [LABEL_N_CAP_8187] (they repeat coins
     * and share a market), a realised close counts [REALISED_WEIGHT_8187] labels, and the record's evidence shrinks by the
     * realised share. Returns [n, mean]; the record unchanged when there is no contradiction.
     */
    fun realisedBlend8187(nL: Double, meanL: Double, nR: Double, meanR: Double): DoubleArray {
        if (!(nL > 0.0) || !meanL.isFinite() || !(nR >= 2.0) || !meanR.isFinite() || (meanL > 0.0) == (meanR > 0.0)) return doubleArrayOf(nL, meanL)
        val nEff = minOf(nL, LABEL_N_CAP_8187)
        val wR = REALISED_WEIGHT_8187 * nR
        val mean = (nEff * meanL + wR * meanR) / (nEff + wR)
        return doubleArrayOf(nEff * nEff / (nEff + wR), mean)
    }
    private const val LABEL_N_CAP_8187 = 60.0
    private const val REALISED_WEIGHT_8187 = 30.0

    /**
     * Pure (V5.0.8187): the executable read of a label record [st]: the realised blend, then the lane [offsetPct]. Returns [st]
     * itself when nothing applies, else a read-only copy (realised-money read: the copy is built from the robust read).
     */
    fun executableCopy8187(st: CortexLedger7885.Stat, offsetPct: Double, realisedN: Double, realisedMean: Double): CortexLedger7885.Stat {
        val off = if (offsetPct.isFinite()) offsetPct.coerceAtLeast(0.0) else 0.0
        val m0 = st.mean() + st.effectiveReadOffset8404()
        val blend = realisedBlend8187(st.n, m0, realisedN, realisedMean)
        val blended = blend[0] != st.n || blend[1] != m0
        if (!blended && off <= 0.0) return st
        val n2 = blend[0]; val m2 = blend[1]
        val variance = st.variance()
        return CortexLedger7885.realisedStat8187().also { c ->
            c.n = n2; c.sum = m2 * n2; c.sumSq = (variance + m2 * m2) * n2
            c.wins = st.winRate() * n2; c.runners = st.runnerRate() * n2
            val shadowShare8404 = if (st.n > 0.0) (st.shadowWeight8404 / st.n).coerceIn(0.0, 1.0) else 0.0
            c.shadowWeight8404 = n2 * if (!blended) shadowShare8404 else {
                val labelWeight = minOf(st.n, LABEL_N_CAP_8187)
                val realisedWeight = REALISED_WEIGHT_8187 * realisedN
                (labelWeight * shadowShare8404 + realisedWeight) / (labelWeight + realisedWeight)
            }
            c.readOffset8187 = off
        }
    }

    // ── V5.0.8291 §SETUP_AUTHORITY_ANSWERS_TO_REALISED ──
    //
    // 5.0.8286: LAUNCH_LADDER_PROVEN n824 +83.5% auth=1.00 and COPYCAT_BETA +86% auth=1.00 while every live close lost
    // (-10..-34%). The 8187 realised blend only reads a setup's OWN closes (n >= 2, opposite sign) and those setups had none;
    // the paired shortfall needs a labelled admitted decision per trade (labels lost), so the lane offset fell back to its
    // 3.5-point cost gap. Graduated setup authority (LanePlaybook7907.setupFraction7955) — the power to overrule a live edge
    // refusal (LiveEdgeGate7877.setupAdmits7955 at >= 0.5) and to size conviction — had no realised cap at all. Now it is
    // capped by the realised record: the setup's own closes losing, or the lane's recent closes losing, take it away.
    private const val LANE_REALISED_RECENT_8291 = 10
    private val realisedByLane8291 = ConcurrentHashMap<String, DoubleArray>()   // LANE -> [n, mean, losses]

    /** Pure (V5.0.8291): [n, mean, losses] of the newest [LANE_REALISED_RECENT_8291] closes of a time-ordered series (each clamped). */
    fun laneRealised8291(series: List<Double>): DoubleArray {
        val xs = series.filter { it.isFinite() }.takeLast(LANE_REALISED_RECENT_8291).map { com.lifecyclebot.engine.truth.RobustLabelRead8185.clampLabel8187(it) }
        return doubleArrayOf(xs.size.toDouble(), if (xs.isEmpty()) 0.0 else xs.average(), xs.count { it < 0.0 }.toDouble())
    }

    /**
     * Pure (V5.0.8291): the ceiling realised money puts on a setup's graduated authority (only lowers; 1 = no cap).
     *   - the setup's own closes: 2+ with mean <= 0 -> 0;
     *   - the lane's recent closes: 2+ with mean <= 0 and at least two in three losses -> 0; 2+ with mean <= 0 -> 0.25
     *     (under the 0.5 a setup needs to overrule a live refusal).
     * Re-read every call: authority returns with the realised record.
     */
    fun setupRealisedCap8291(setupN: Double, setupMean: Double, laneN: Double, laneMean: Double, laneLosses: Double): Double {
        if (setupN >= 2.0 && setupMean.isFinite() && setupMean <= 0.0) return 0.0
        if (laneN >= 2.0 && laneMean.isFinite() && laneMean <= 0.0) {
            return if (laneLosses * 3.0 >= laneN * 2.0) 0.0 else LANE_LOSING_CAP_8291
        }
        return 1.0
    }
    const val LANE_LOSING_CAP_8291 = 0.25

    /** V5.0.8291 — the realised authority cap of ([laneRaw], [setup]) in the account mode, read lock-free. */
    fun setupRealisedCap8291(laneRaw: String, setup: String): Double {
        val lane = canon(laneRaw)
        val s = realisedBySetup8187["$lane|$setup"]
        val l = realisedByLane8291[lane]
        return setupRealisedCap8291(s?.getOrNull(0) ?: 0.0, s?.getOrNull(1) ?: Double.NaN, l?.getOrNull(0) ?: 0.0, l?.getOrNull(1) ?: Double.NaN, l?.getOrNull(2) ?: 0.0)
    }

    /** V5.0.8187 — the lane's executable offset (points), read lock-free (0 until measured). */
    fun executableOffset8187(laneRaw: String): Double = execOffsetByLane8187[canon(laneRaw)] ?: 0.0

    /** V5.0.8187 — [closes, mean realised %] of (lane, setup) in the account mode, read lock-free; null when none. */
    fun setupRealised8187(laneRaw: String, setup: String): DoubleArray? = realisedBySetup8187["${canon(laneRaw)}|$setup"]
    /** V5.0.8295 — [closes, mean realised %] of every attributed close of the lane in the account mode (LaneRealisedGovernor8295). */
    fun laneRealised8295(laneRaw: String): DoubleArray? = LaneRealisedGovernor8295.laneOf8295(realisedBySetup8187, canon(laneRaw))

    private val execRefreshing8325 = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Recompute every lane's offset and the setups' realised records; apply them to the books.
     * V5.0.8325 §LOCK_ONLY_FOR_SMALL_STATE — never call while holding the lock (onLabel and onCanonicalClose call it after
     * theirs): the other components' cost reads (CostLedger7962, NetEdge8030, EntryChase7961, ForwardReturnLabeler7731) run
     * BEFORE the lock, the grade-book reads and the book writes under it, the ponds after it (copied rows, 30 s cache). One
     * refresh at a time; a concurrent caller skips (the next label or close re-runs it).
     */
    private fun refreshExecutable8187(nowMs: Long) {
        if (nowMs - lastExecRefresh8187 < EXEC_REFRESH_MS_8187) return
        if (!execRefreshing8325.compareAndSet(false, true)) return
        try {
            lastExecRefresh8187 = nowMs
            val mode = accountMode8077()
            val lanes = synchronized(this) { (board.books.keys + gradeBook8144.lanes8187()).toSet() }
            val costs = HashMap<String, DoubleArray>(lanes.size * 2)
            for (lane in lanes) {
                // The labels' charge already holds the chase when they were not re-based at a measured latency: so does the ticket's.
                val charged = try { com.lifecyclebot.engine.truth.CostLedger7962.labelChargedCostPct8153(lane) } catch (_: Throwable) { Double.NaN }
                val atTicket = try {
                    com.lifecyclebot.engine.truth.NetEdge8030.costPct8030(lane, com.lifecyclebot.engine.truth.NetEdge8030.DEFAULT_TICKET_SOL, 0.0)
                } catch (_: Throwable) { Double.NaN }
                val chase = try {
                    if (com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.rebaseClearsChase8094(com.lifecyclebot.engine.truth.EntryChase7961.laneLatencyMs8094(lane))) 0.0
                    else com.lifecyclebot.engine.truth.EntryChase7961.lanePenaltyPct7961(lane)
                } catch (_: Throwable) { 0.0 }
                costs[lane] = doubleArrayOf(charged, atTicket, chase)
            }
            synchronized(this) {
                for (lane in lanes) {
                    val c = costs[lane] ?: continue
                    val sf = gradeBook8144.pairedShortfall8187(lane, mode)
                    val off = execOffset8187(costGap8187(c[1], c[2], c[0]), sf[0], sf[1])
                    execOffsetByLane8187[lane] = off
                    board.books[lane]?.applyOffset8187(off)
                    val fresh8295 = gradeBook8144.realisedBySetup8187(lane, mode)  // V5.0.8295 — a setup absent in this mode drops (no paper/live carry-over)
                    realisedBySetup8187.keys.removeIf { it.startsWith("$lane|") && it.substringAfter('|') !in fresh8295 }
                    for (setup in fresh8295.entries) realisedBySetup8187["$lane|${setup.key}"] = setup.value
                    // V5.0.8291 — the lane's own recent realised closes in this mode (every setup), for the setup authority cap.
                    realisedByLane8291[lane] = laneRealised8291(gradeBook8144.realisedSeries(lane, null, null, mode))
                }
                for ((k, st) in jointBook7961) st.readOffset8187 = execOffsetByLane8187[k.substringBefore('|')] ?: 0.0
                for ((k, st) in vetoBook) st.readOffset8187 = if ('@' in k) execOffsetByLane8187[k.substringAfterLast('@')] ?: 0.0 else 0.0
            }
            try { refreshPonds8324(lanes) } catch (_: Throwable) { inc("TAIL_POND_REFRESH_FAILED_8324") }  // V5.0.8324 / 8325 — outside the lock
            try { rankings8325(nowMs) } catch (_: Throwable) {}  // V5.0.8325 — the ranking cache the buckets peek, off the hot path
            inc("EXEC_OFFSETS_REFRESHED_8187")
        } finally { execRefreshing8325.set(false) }
    }

    /** V5.0.8187 — "LANE offset(gap/shortfall n)" for the status line. */
    private fun execLine8187(): String = execOffsetByLane8187.entries.filter { it.value > 0.05 }.sortedByDescending { it.value }.take(8)
        .joinToString(" · ") { "${it.key} -${"%.1f".format(it.value)}pt" }.ifBlank { "none measured" }

    // ── V5.0.8187 §AUTHORITY_GRADUATION — which gate holds each lane's STRONG authority at zero ──

    /**
     * Caller holds the lock. The first gate keeping [lane]'s STRONG-side authority at 0, as a stable code (counted on every
     * decision-time read as AUTHORITY_BLOCKED_8187_<lane>_<code>): INVERTED, LABEL_N (under 15 STRONG grades), LABEL_EDGE,
     * CALIBRATION, REALISED_NEGATIVE, WF_<verdict>, OWNER_REALISED; OPEN when nothing blocks.
     */
    private fun blockCode8187(lane: String): String {
        val mode = accountMode8077()
        val b = board.books[lane] ?: return "NO_BOOK"
        if (board.inverted7948(lane, mode)) return "INVERTED"
        if (!(board.evidenceFor8025(lane, mode) > 0.0)) {
            return if (b.byBucket[CortexScoreboard7885.Bucket.STRONG.ordinal].n < CortexScoreboard7885.GRAD_MIN_N_7955) "LABEL_N" else "LABEL_EDGE"
        }
        if (calibration.suspends8372(lane, MIN_CONSISTENT_SLOPE, executableOffset8187(lane))) return "CALIBRATION"
        val s = board.realized["$mode|$lane"]?.getOrNull(CortexScoreboard7885.Bucket.STRONG.ordinal)
        if (s != null && CortexRealisedAuthority8136.verdict8136(s.n, s.mean(),
                if (s.n > 1.0) kotlin.math.sqrt(s.variance() / s.n) else Double.POSITIVE_INFINITY) == CortexRealisedAuthority8136.Verdict8136.DENIED_REALISED_NEGATIVE) return "REALISED_NEGATIVE"
        val wf = walkForward8144(lane, null)
        if (!wf.validated) return "WF_${wf.verdict.name}"
        if (!(ownerRealisedCap8153(lane, null) > 0.0)) return "OWNER_REALISED"
        return "OPEN"
    }

    /** Caller holds the lock. "LANE code[numbers]" for the top lanes: what the next gate needs to see. */
    private fun blockLine8187(): String = board.books.entries.sortedByDescending { it.value.byBucket.sumOf { b -> b.n } }.take(8)
        .joinToString(" · ") { (lane, b) ->
            val code = blockCode8187(lane)
            val strong = CortexScoreboard7885.Bucket.STRONG.ordinal
            val c = gradeBook8144.cohortCounts8187(lane, strong)
            val detail = when {
                code == "LABEL_N" -> "strongN=${b.byBucket[strong].n.toInt()}/${CortexScoreboard7885.GRAD_MIN_N_7955}"
                code == "LABEL_EDGE" -> "strong=${CortexLedger7885.fmt8187(b.byBucket[strong])} neutral=${CortexLedger7885.fmt8187(b.byBucket[CortexScoreboard7885.Bucket.NEUTRAL.ordinal])}"
                code == "CALIBRATION" -> "slope=${"%.2f".format(calibration.slope(lane))}<$MIN_CONSISTENT_SLOPE"
                code.startsWith("WF_") || code == "OPEN" -> "exec=${c[0]}/${CortexWalkForward8144.MIN_SERIES_N_8187} lost=${c[1]} lateFirst=${c[2]} lateRead=${c[3]} " +
                    "resolved=${"%.2f".format(gradeBook8144.resolvedShare(lane, strong))}/${CortexWalkForward8144.MIN_RESOLVED_SHARE_8144}"
                else -> "realisedStrong=${CortexLedger7885.fmt8187(board.realized["${accountMode8077()}|$lane"]?.getOrNull(strong) ?: CortexLedger7885.realisedStat8187())}"
            }
            "$lane $code[$detail]"
        }.ifBlank { "-" }

    /** V5.0.8187 — graded decisions per hour since this process loaded the Cortex. */
    fun gradedPerHour8187(nowMs: Long = System.currentTimeMillis()): Double {
        val hours = (nowMs - loadedAtMs8169).coerceAtLeast(60_000L) / 3_600_000.0
        return graded.get() / hours
    }

    /** Pure (V5.0.8187): the share of this session's eligible closes that found their entry verdict (NaN with none). */
    fun bindingRate8187(bound: Long, unmatched: Long): Double = if (bound + unmatched <= 0L) Double.NaN else bound.toDouble() / (bound + unmatched)

    /** V5.0.8187 — realised binding this session: rate, recoveries, deferred binds, and boot replays counted apart. */
    fun bindingLine8187(): String {
        val bound = counterSum8169("REALIZED_LIVE") + counterSum8169("REALIZED_PAPER")
        val miss = counterSum8169("REALIZED_UNMATCHED")
        val rate = bindingRate8187(bound, miss)
        return "binding8187[rate=${if (rate.isFinite()) "%.0f%%".format(rate * 100.0) else "-"} bound=$bound unmatched=$miss " +
            "fromDecisionRecord=${counterSum8169("REALIZED_BOUND_FROM_DECISION_RECORD_8187")} deferredRead=${counterSum8169("REALIZED_BOUND_DEFERRED_READ_8187")} " +
            "lateReadBound=${counterSum8169("ENTRY_BOUND_LATE_READ_8187")} replays[filed=${counterSum8169("REALIZED_REPLAY_ALREADY_FILED_8187")} " +
            "unmatched=${counterSum8169("REALIZED_REPLAY_UNMATCHED_8187")}]] " + com.lifecyclebot.engine.truth.GradeBind8332.line8332()
    }

    /** V5.0.8187 — the scoreboard card's one-line pulse: graded per hour and this session's realised binding rate. */
    private fun cardLine8187(): String {
        val bound = counterSum8169("REALIZED_LIVE") + counterSum8169("REALIZED_PAPER")
        val rate = bindingRate8187(bound, counterSum8169("REALIZED_UNMATCHED"))
        return "8187: graded/h=${"%.1f".format(gradedPerHour8187())} binding=${if (rate.isFinite()) "%.0f%%".format(rate * 100.0) else "-"} " +
            "clampedAtIngestion=${counterSum8169("LABEL_CLAMPED_AT_INGESTION_8187")} lost=${counterSum8169("LABEL_LOST_")} (robust means; ~raw = stored mean the cap rules out)"
    }

    /** V5.0.8187 — the Cortex's own data economy: this process's credits per assessed / graded decision, first-block share. */
    fun economyLine8187(): String {
        val credits = try { com.lifecyclebot.engine.LoadTelemetry8169.sessionCredits8187() } catch (_: Throwable) { 0.0 }
        val bundle = try { com.lifecyclebot.engine.LoadTelemetry8169.sessionCreditsBy8187("BundleDetector") } catch (_: Throwable) { 0.0 }
        val n = assessed.get(); val g = graded.get()
        return "session8187[credits=${"%.0f".format(credits)} perAssessed=${if (n > 0) "%.1f".format(credits / n) else "-"} " +
            "perGraded=${if (g > 0) "%.1f".format(credits / g) else "-"} firstBlockVoter=${"%.0f".format(bundle)}] " +
            "gradedPerHour=${"%.1f".format(gradedPerHour8187())}"
    }

    /** V5.0.7955 — this lane's graduated Cortex authority in [0, 1] (0 when unread or inconsistent). */
    fun fractionFor7955(laneRaw: String): Double =
        try { ensureLoaded(); synchronized(this) { laneFraction7955(canon(laneRaw)) } } catch (_: Throwable) { 0.0 }

    // ── Cortex v8: capital allocation (V5.0.7902) ──
    //
    // The wallet funds only a few route-minimum trades at a time. When the free
    // cash left would fund just one more, and this lane's record proves its
    // STRONG reads beat its NEUTRAL reads, a NEUTRAL candidate does not take the
    // last slot: it stays free for a STRONG one (rule C5).
    private const val ROUTE_MIN_USD = 5.0
    private const val ROUTE_MIN_FALLBACK_SOL = 0.0435

    private fun lastSlot(ts: TokenState): Boolean = try {
        val solUsd = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
        val routeMin = com.lifecyclebot.engine.truth.EconomicUnitInvariant7061.usdToSol(ROUTE_MIN_USD, solUsd)
            .takeIf { it.isFinite() && it > 0.0 } ?: ROUTE_MIN_FALLBACK_SOL
        val free = com.lifecyclebot.engine.WalletCapacitySeal7868.freeCashFor(ts.mint, com.lifecyclebot.engine.BotService.status.walletSol)
        free.isFinite() && free >= routeMin && free < 2.0 * routeMin
    } catch (_: Throwable) { false }

    /**
     * V5.0.8269 — return REFUSE power only on tradeable, on-time, first-of-episode
     * labels that were negative in BOTH the training and later held-out span.
     * CortexScoreboard7885's old decayed aggregate remains shadow telemetry:
     * it cannot enforce a live veto from repeat-inflated pre-8146 samples.
     * Caller holds Cortex's lock.
     */
    private fun refusalAuthority8269(lane: String, runnerLane: Boolean, paper: Boolean, relative: Boolean = false): Boolean {
        if (!refuseCoverage8392(lane)) return false
        val refuse = CortexScoreboard7885.Bucket.REFUSE.ordinal
        val neutral = CortexScoreboard7885.Bucket.NEUTRAL.ordinal
        val strong = CortexScoreboard7885.Bucket.STRONG.ordinal
        val xs = gradeBook8144.executableTimed8196(lane, null, refuse, scoreVersion = 1)
        val rivals = gradeBook8144.executableTimed8196(lane, null, neutral, scoreVersion = 1) +
            gradeBook8144.executableTimed8196(lane, null, strong, scoreVersion = 1)
        val gross = gradeBook8144.executableGrossSeries8198(lane, null, refuse, scoreVersion = 1)
        val runnerRate = if (gross.isEmpty()) 0.0 else
            gross.count { it >= CortexLedger7885.RUNNER_GROSS_PCT }.toDouble() / gross.size.toDouble()
        val minN = if (relative) CortexScoreboard7885.RELATIVE_MIN_N_8019
            else if (paper) CortexScoreboard7885.MIN_N_PAPER else CortexScoreboard7885.MIN_N_LIVE
        return CortexRefusalTruth8269.proven8269(xs, rivals, gradeBook8144.resolvedShare(lane, refuse, scoreVersion = 1),
            minN, runnerLane, runnerRate, relative)
    }

    /**
     * V5.0.8310 — why this lane's REFUSE power is kept in shadow (CortexInversion8310): its STRONG reads are proven
     * inverted, or its REFUSE column (lane, or the [setup]'s joint cells) reads above its STRONG column. Null: it binds.
     * Caller holds the lock.
     */
    private fun refuseShadow8310(lane: String, setup: String?): String? {
        val b = cohort8372(lane)
        val r = b[CortexScoreboard7885.Bucket.REFUSE.ordinal]
        val s = b[CortexScoreboard7885.Bucket.STRONG.ordinal]
        val setupCohort = if (setup.isNullOrBlank()) null else cohort8372(lane, setup)
        val rs = setupCohort?.get(CortexScoreboard7885.Bucket.REFUSE.ordinal)
        val ss = setupCohort?.get(CortexScoreboard7885.Bucket.STRONG.ordinal)
        return CortexInversion8310.shadowWhy8310(
            invertedCurrent8372(lane),
            r.n, r.mean(), s.n, s.mean(),
            rs?.n ?: 0.0, rs?.mean() ?: Double.NaN, ss?.n ?: 0.0, ss?.mean() ?: Double.NaN,
        )
    }

    /** V5.0.8310 — LIVE: a REFUSE-column refusal on an inverted lane / setup is counted and kept in shadow (true). */
    private fun shadowInvertedRefusal8310(ts: TokenState, a: Assessment): Boolean = try {
        val setup = try { LanePlaybook7907.selectedSetup8094(ts, a.lane, System.currentTimeMillis()) } catch (_: Throwable) { null }
        val why = synchronized(this) { refuseShadow8310(a.lane, setup) }
        if (why != null) { inc("SHADOW_REFUSE_INVERTED_8310"); CortexInversion8310.noteShadowed8310(a.lane, why) }
        why != null
    } catch (_: Throwable) { false }

    /**
     * Called first by LiveEdgeGate7877.liveRefusal (both modes). Non-null means
     * the Cortex refuses this entry under a named rule.
     */
    fun entryRefusal(ts: TokenState, laneRaw: String, paper: Boolean): String? {
        return try {
            CortexAudit8169.call("ENTRY_REFUSAL")
            // V5.0.7930 — LIVE never proceeds without a read: a first-seen live candidate
            // (fast lane, launch-heat promotion) used to be admitted on a cache miss.
            val a = cachedOrSchedule(ts, laneRaw) ?: (if (!paper) assess(ts, laneRaw) else null) ?: run {
                CortexAudit8169.noteGate(canon(laneRaw), read = false, refuseBucket = false, authority = false, refused = false)
                return null
            }
            // V5.0.8279 §SELECT_LIKE_8114 — the 8085–8114 builds vetoed on the scoreboard's own REFUSE record (n >= 40 live,
            // mean + SE below zero, below the lane's other reads, no runner tail). 8269 replaced that with the held-out
            // proof alone and the live veto went silent (8261: shadow refuse=1557, refusedLive=0, live WR 18 %). A
            // refusal protects capital: EITHER proof now carries it. The held-out proof stays for the scoreboard columns.
            val proven = synchronized(this) {
                (refusalCurrent8372(a.lane, a.runnerLane, paper) || refusalAuthority8269(a.lane, a.runnerLane, paper)) && consistent(a.lane)
            }
            // V5.0.8019 — or the relative bar: this lane's REFUSE reads sit measurably below its other reads while
            // its STRONG reads hold authority. Live only (paper already refuses at 20).
            val relative8019 = !paper && !proven && synchronized(this) {
                (relativeRefusalCurrent8372(a.lane, a.runnerLane) ||
                    // A shadowed STRONG side cannot authorize an early relative veto.
                    (heldFraction8153(a.lane) >= OVERRULE_FRACTION_7955 &&
                        refusalAuthority8269(a.lane, a.runnerLane, paper = false, relative = true))) && consistent(a.lane)
            }
            val rule0 = constitutionRefusal(a, ts, paper, proven || relative8019)?.let { if (it == "C3_PROVEN_NEGATIVE_EDGE" && relative8019) "C3_RELATIVE_REFUSE_8019" else it }
                ?: try { CortexTiming7900.waitRefusal(a) } catch (_: Throwable) { null }
            val rule = if (!paper && CortexInversion8310.refuseColumnRule8310(rule0) && shadowInvertedRefusal8310(ts, a)) null else rule0  // V5.0.8310
            // V5.0.8169 — per-lane gate reach: 8168 showed Rp/Rl on most lanes and did refused=0; this proves which lanes'
            // REFUSE reads ever meet the gate and whether the power held there.
            CortexAudit8169.noteGate(a.lane, true, a.bucket == CortexScoreboard7885.Bucket.REFUSE, proven || relative8019, rule != null)
            if (rule == null) {
                if (a.bucket == CortexScoreboard7885.Bucket.REFUSE) inc(if (paper) "SHADOW_REFUSE_PAPER" else "SHADOW_REFUSE_LIVE")
                noteEntryRead(ts.mint, paper, a)
                return null
            }
            val mode = if (paper) "PAPER" else "LIVE"
            inc("REFUSED_${mode}_$rule")
            try {
                PipelineHealthCollector.labelInc("CORTEX_7885_REFUSED_${mode}_$rule")
                if (com.lifecyclebot.engine.ForensicEmitRateLimiter6356.shouldEmit("CORTEX_7885", "${a.lane}|${ts.mint}")) {
                    ForensicLogger.lifecycle(
                        "CORTEX_7885_REFUSED",
                        "mint=${ts.mint.take(10)} sym=${ts.symbol} lane=${a.lane} mode=$mode rule=$rule edge=${"%.2f".format(a.fused.edgePct)} " +
                            "pWin=${"%.2f".format(a.fused.pWin)} runner=${"%.2f".format(a.fused.runnerRate)} effVoters=${"%.1f".format(a.fused.effectiveVoters)} top=${a.fused.top.joinToString(",")}",
                    )
                }
            } catch (_: Throwable) {}
            "CORTEX_7885_${rule}_${a.lane}"
        } catch (_: Throwable) { null }
    }

    /**
     * V5.0.8326 — retired compatibility mode: the 8114 (379ad2fa0) LIVE entry refusal. The scoreboard's own refusal authority or
     * the 8019 relative bar, then the constitution / timing rules; no 8269 held-out proof, no 8310 inverted-column
     * stand-down. Read only by retired compatibility gate (live meme lanes while the mode is ON). Counted, never logged twice.
     */

    /**
     * Called by LiveEdgeGate7877 when it would refuse a LIVE entry. True means the
     * Cortex's proven STRONG record overrules that edge refusal for this candidate.
     */
    /** Pure (V5.0.8094): have this lane x setup's own STRONG reads lost (5+ graded, mean + SE below zero)? */
    fun strongSetupNegative8094(n: Double, meanPct: Double, sePct: Double): Boolean =
        n >= 5.0 && meanPct.isFinite() && sePct.isFinite() && meanPct + sePct < 0.0

    fun overrulesEdgeRefusal(ts: TokenState, laneRaw: String, refusal: String): Boolean {
        return try {
            CortexAudit8169.call("OVERRULE")  // V5.0.8169
            val a = cachedOrSchedule(ts, laneRaw) ?: return false
            if (a.bucket != CortexScoreboard7885.Bucket.STRONG) return false
            if (a.staleMark || ts.safety.tier == com.lifecyclebot.engine.SafetyTier.HARD_BLOCK) return false
            // V5.0.7955 — partial authority overrules soft edge refusals only: never a hard
            // safety / rug / route refusal, never a Mayhem Mode coin.
            if (!softBlock(refusal, false)) return false
            if (com.lifecyclebot.engine.MayhemMode7943.liveRefusal(ts) != null) return false
            val laneFraction8083 = synchronized(this) { laneFraction7955(a.lane) }
            // V5.0.8083 §CANDIDATE_SPECIFIC_AUTHORITY — the Cortex's record on THIS candidate's own setup (its STRONG reads
            // in lane x setup) may carry the soft exception even where the lane-wide record is too thin or diluted
            // (8082: SHITCOIN STRONG n72 +12.3% held lane authority 0). Same graduated, SE-aware, cost-margined test as
            // the lane, on the setup's own cell; calibration and inversion still gate it. Never a hard refusal.
            // V5.0.8094 — the setup this candidate TRADES as (best proven fired setup, else the classified one).
            val setup8083 = try { LanePlaybook7907.selectedSetup8094(ts, a.lane, System.currentTimeMillis()) } catch (_: Throwable) { null }
            // V5.0.8094 forensic — a STRONG lane read never overrules for a setup whose OWN record is negative: the
            // playbook's proven-losing setup, or the Cortex's own STRONG reads on this lane x setup losing (8093:
            // SHITCOIN / EXPRESS STRONG on LAUNCH_CONTINUATION negative while the lane bucket read +28% / +125%).
            val negSetup8094 = setup8083 != null && (
                (try { LanePlaybook7907.provenLosingSetupRefusal8079(ts, a.lane) } catch (_: Throwable) { null }) != null ||
                    synchronized(this) { cohort8372(a.lane, setup8083)[CortexScoreboard7885.Bucket.STRONG.ordinal] }
                        ?.let { st -> strongSetupNegative8094(st.n, st.mean(), if (st.n > 1.0) kotlin.math.sqrt(st.variance() / st.n) else Double.POSITIVE_INFINITY) } == true)
            if (negSetup8094) {
                inc("OVERRIDE_REFUSED_NEGATIVE_SETUP_8094")
                try {
                    PipelineHealthCollector.labelInc("CORTEX_OVERRIDE_REFUSED_NEGATIVE_SETUP_8094_${a.lane}")
                    ForensicLogger.lifecycle("CORTEX_OVERRIDE_REFUSED_NEGATIVE_SETUP_8094",
                        "candidate=${ts.mint} sym=${ts.symbol} lane=${a.lane} setup=$setup8083 superseded=${refusal.take(60)} action=refusal_stands")
                } catch (_: Throwable) {}
                return false
            }
            val cell8083 = setup8083?.let { synchronized(this) { setupCellAuthority8083(a.lane, it) } }
            // Both paths now use the one frozen-verdict test; the historical stacked proof remains diagnostic only.
            val fraction = maxOf(laneFraction8083, cell8083?.fraction ?: 0.0)
            // V5.0.7955 review — a lane's own PROVEN_LOSING / PROVEN_NEGATIVE record yields only to full authority.
            // V5.0.8016 — watch-first's measured-negative cell is a proven record too.
            val provenRefusal = refusal.contains("PROVEN_LOSING") || refusal.contains("PROVEN_NEGATIVE") || refusal.contains("CELL_NEGATIVE") || refusal.contains("BAND_NEGATIVE")
            if (fraction < OVERRULE_FRACTION_7955 || (provenRefusal && fraction < 1.0)) {
                // V5.0.8026 — no override authority yet (maturity): the coin may still be bought at FIRST SIGHT as a
                // route-minimum runner probe when evidence, EV, win rate, data integrity and the cash caps all clear.
                if (firstSight8026(a, ts, refusal)) { inc("FIRST_SIGHT_8026"); return true }
                inc(if (fraction < OVERRULE_FRACTION_7955) "SHADOW_OVERRULE_LIVE" else "SHADOW_OVERRULE_PROVEN_7955"); return false
            }
            inc("OVERRULED_LIVE")
            if (fraction < 1.0) inc("PARTIAL_AUTHORITY_OVERRULE_7955")
            val viaCell8083 = cell8083 != null && cell8083.fraction > laneFraction8083
            if (viaCell8083) inc("SETUP_CELL_OVERRULE_8083")
            // V5.0.8083 — every successful override is explained: candidate, setup, evidence, expected net edge,
            // authority contribution and the soft rule it superseded.
            try {
                ForensicLogger.lifecycle(
                    "CORTEX_OVERRIDE_EXPLAINED_8083",
                    "candidate=${ts.mint} sym=${ts.symbol} lane=${a.lane} setup=${setup8083 ?: "UNCLASSIFIED"} " +
                        "authority=${"%.3f".format(fraction)} source=${if (viaCell8083) "SETUP_CELL" else "LANE"} laneAuthority=${"%.3f".format(laneFraction8083)} " +
                        "cell=${cell8083?.let { "n=${"%.0f".format(it.n)} mean=${"%+.2f".format(it.mean)}% se=${"%.2f".format(it.se)} baseline=${"%+.2f".format(it.baseline)}% expectedNetEdge=${"%+.2f".format(it.mean - it.baseline)}%" } ?: "none"} " +
                        "fusedEdge=${"%+.2f".format(a.fused.edgePct)}% superseded=${refusal.take(80)}",
                )
            } catch (_: Throwable) {}
            try {
                PipelineHealthCollector.labelInc("CORTEX_7885_OVERRULED_EDGE_${a.lane}")
                ForensicLogger.lifecycle(
                    "CORTEX_7885_OVERRULED_EDGE",
                    "mint=${ts.mint.take(10)} sym=${ts.symbol} lane=${a.lane} edge=${"%.2f".format(a.fused.edgePct)} auth=${"%.2f".format(fraction)} was=${refusal.take(60)} top=${a.fused.top.joinToString(",")}",
                )
            } catch (_: Throwable) {}
            true
        } catch (_: Throwable) { false }
    }

    /**
     * V5.0.8078 — the lane-wide live loss book (LiveTruth8064) yields ONLY to full, evidence-earned Cortex authority:
     * a STRONG, fresh read on a lane that is calibrated, not inverted and holds authority 1.0 (full proof on 1,000+
     * lifetime STRONG grades). No partial authority, no first-sight probe, never a hard safety / route / Mayhem refusal.
     * Authority is re-read every call, so it is lost the moment the record stops supporting it.
     */
    fun fullAuthorityOverrules8078(ts: TokenState, laneRaw: String, refusal: String): Boolean {
        return try {
            CortexAudit8169.call("FULL_AUTHORITY")  // V5.0.8169
            val a = cachedOrSchedule(ts, laneRaw) ?: return false
            if (a.bucket != CortexScoreboard7885.Bucket.STRONG) return false
            if (a.staleMark || ts.safety.tier == com.lifecyclebot.engine.SafetyTier.HARD_BLOCK) return false
            if (!softBlock(refusal, false)) return false
            if (com.lifecyclebot.engine.MayhemMode7943.liveRefusal(ts) != null) return false
            val fraction = synchronized(this) { laneFraction7955(a.lane) }
            if (fraction < 1.0) { inc("SHADOW_FULL_AUTHORITY_8078"); return false }
            inc("FULL_AUTHORITY_OVERRULED_8078")
            try {
                PipelineHealthCollector.labelInc("CORTEX_FULL_AUTHORITY_OVERRULED_8078_${a.lane}")
                ForensicLogger.lifecycle(
                    "CORTEX_FULL_AUTHORITY_OVERRULED_8078",
                    "mint=${ts.mint.take(10)} sym=${ts.symbol} lane=${a.lane} edge=${"%.2f".format(a.fused.edgePct)} was=${refusal.take(60)}",
                )
            } catch (_: Throwable) {}
            true
        } catch (_: Throwable) { false }
    }

    /** V5.0.8026 — FirstSight8026 with this lane's evidence, STRONG record and maturity. */
    private fun firstSight8026(a: Assessment, ts: TokenState, refusal: String): Boolean = try {
        val (evidence, strong, maturity) = synchronized(this) {
            val s = cohort8372(a.lane)[CortexScoreboard7885.Bucket.STRONG.ordinal]
            val copy = CortexLedger7885.realisedStat8187().also { c -> c.decode(s.encode()) }
            // V5.0.8034 — -1 marks an inconsistent Cortex or an inverted lane: first sight never fires there.
            Triple(if (consistent(a.lane) && !invertedCurrent8372(a.lane)) evidenceCurrent8372(a.lane) else -1.0,
                copy, CortexScoreboard7885.maturity8025(s.n))
        }
        // V5.0.8144 — no first-sight buy where the lane's STRONG closes lost or its STRONG reads trail a rival out of sample.
        val barred8144 = synchronized(this) { firstSightBarred8144(a.lane) }
        if (barred8144) inc("FIRST_SIGHT_BARRED_8144")
        FirstSight8026.grant8026(ts, a.lane, refusal, if (barred8144) -1.0 else evidence, strong, maturity)
    } catch (_: Throwable) { false }

    // ── Cortex v13: paper choice (v1 Phase 4, V5.0.7915) ──
    //
    // Until now the Cortex only refused. In PAPER it now also CHOOSES: a
    // candidate the legacy stack blocked on a soft (confidence / edge) reason,
    // or that its lane declined, is admitted as a paper position when the
    // Cortex reads it STRONG and that lane's STRONG record is proven on forward
    // labels (the overrule bar) and the Cortex is self-consistent. One choice
    // per lane per [CHOICE_SPACING_MS]. Never in LIVE, never over a hard block
    // (safety, rug, route, mode, size), never on a stale mark. Its positions are
    // booked as PAPER_CHOSEN on whole-position closes, so the choice itself is
    // graded on real exits before anything like it is let near live money.
    private const val CHOICE_SPACING_MS = 5L * 60_000L
    private val lastChoiceAt = ConcurrentHashMap<String, Long>()
    private val chosen = ConcurrentHashMap<String, Long>()   // mint -> chosen at

    /** Pure: is a legacy block soft enough for a proven Cortex read to override in paper? */
    fun softBlock(blockReason: String?, hardLevel: Boolean): Boolean {
        if (hardLevel) return false
        val r = (blockReason ?: return true).uppercase()
        return listOf("HARD", "RUG", "TOKEN_MAP", "ROUTE", "SAFETY", "HONEYPOT", "FREEZE", "MINT_AUTH", "KILL", "PAUSE", "WALLET", "BALANCE", "DUPLICATE", "OPEN_POSITION", "CAPACITY")
            .none { r.contains(it) }
    }

    /** FinalDecisionGate (PAPER only): true admits this soft-blocked candidate as the Cortex's choice. */
    fun paperChoice(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        return try {
            CortexAudit8169.call("PAPER_CHOICE")  // V5.0.8169
            val a = cachedOrSchedule(ts, laneRaw, nowMs) ?: return false
            if (a.bucket != CortexScoreboard7885.Bucket.STRONG) return false
            if (a.staleMark || ts.safety.tier == com.lifecyclebot.engine.SafetyTier.HARD_BLOCK) return false
            // V5.0.7955 — the same graduated threshold as the live overrule.
            val proven = synchronized(this) { laneFraction7955(a.lane) >= OVERRULE_FRACTION_7955 }
            if (!proven) { inc("SHADOW_PAPER_CHOICE"); return false }
            val last = lastChoiceAt[a.lane] ?: 0L
            if (nowMs - last < CHOICE_SPACING_MS) { inc("PAPER_CHOICE_SPACED"); return false }
            lastChoiceAt[a.lane] = nowMs
            if (chosen.size > 2_000) chosen.entries.removeIf { nowMs - it.value > PENDING_TTL_MS * 4 }
            chosen[ts.mint] = nowMs
            noteEntryRead(ts.mint, true, a)
            inc("PAPER_CHOSEN_${a.lane}")
            try {
                PipelineHealthCollector.labelInc("CORTEX_7915_PAPER_CHOSEN_${a.lane}")
                ForensicLogger.lifecycle(
                    "CORTEX_7915_PAPER_CHOSEN",
                    "mint=${ts.mint.take(10)} sym=${ts.symbol} lane=${a.lane} edge=${"%.2f".format(a.calibratedEdge)} pWin=${"%.2f".format(a.fused.pWin)} top=${a.fused.top.joinToString(",")}",
                )
            } catch (_: Throwable) {}
            true
        } catch (_: Throwable) { false }
    }

    // ── Cortex v5: interaction discovery ──
    //
    // Edge often lives in combinations ("strong flow AND young AND thin
    // holders"), which single-voter bins cannot see. For each lane the four
    // currently seated voters with the most authority are crossed pairwise: the
    // cross's raw value is the joint bin (bin_a x bins_b + bin_b), graded and
    // seated exactly like any voter — so a combination earns authority only by
    // out-of-sample skill beyond its parts. Crosses follow the seats: as the
    // seated set changes, new combinations are tried.
    private const val CROSS_TOP = 4

    /** Caller holds the lock. */
    private fun crossVotes(lane: String, raws: DoubleArray): List<CortexLedger7885.Vote> {
        val ids = CortexVoters7885.IDS
        val edges = CortexVoters7885.EDGES
        val evidence = CortexVoters7885.ALL.map { it.evidence }
        val top = ids.indices
            .filter { raws.getOrNull(it)?.isFinite() == true }
            .map { it to (ledger.seats["${ids[it]}|$lane"]?.authority() ?: 0.0) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(CROSS_TOP)
            .map { it.first }
            .sortedBy { ids[it] }
        if (top.size < 2) return emptyList()
        val out = ArrayList<CortexLedger7885.Vote>()
        for (x in top.indices) for (y in x + 1 until top.size) {
            val a = top[x]; val b = top[y]
            val na = edges[a].size + 1; val nb = edges[b].size + 1
            val code = CortexLedger7885.binOf(edges[a], raws[a]) * nb + CortexLedger7885.binOf(edges[b], raws[b])
            val crossEdges = DoubleArray(na * nb - 1) { k -> k + 0.5 }
            out.add(CortexLedger7885.Vote("X_${ids[a]}__${ids[b]}", crossEdges, code.toDouble(), evidence[a] + evidence[b] + "cross"))
        }
        return out
    }

    // ── conviction sizing (plan v2 §D) ──

    // V5.0.7955 — 2.0 -> 2.5: proven edge compounds as size (still scaled by authority,
    // still under every downstream wallet / lane / share / liquidity cap).
    private const val CONVICTION_MAX_MULT = 2.5
    private const val KELLY_FRACTION = 0.25

    /** Pure: quarter-Kelly stake (SOL) from a proven record's mean/variance (percent units). */
    fun kellyStakeSol(meanPct: Double, variancePct2: Double, equitySol: Double): Double {
        if (!meanPct.isFinite() || !variancePct2.isFinite() || variancePct2 <= 0.0 || meanPct <= 0.0 || equitySol <= 0.0) return 0.0
        val f = KELLY_FRACTION * (meanPct / 100.0) / (variancePct2 / 10_000.0)
        return f.coerceIn(0.0, 1.0) * equitySol
    }

    /**
     * Size multiplier for a request on (mint, lane): > 1 only when this
     * candidate's decision-time read is STRONG AND the lane's STRONG record holds
     * authority (V5.0.7955: graduated, scaling the Kelly multiple), or the
     * candidate's setup / lane evidence is proven. Never below 1 (shrinking is not
     * a learning lever at the route minimum); at most 2.5x; downstream caps still apply.
     */
    fun convictionMult(mint: String, laneRaw: String, requestedSol: Double, equitySol: Double): Double {
        CortexAudit8169.call("CONVICTION")  // V5.0.8169
        if (mint.isBlank() || !(requestedSol > 0.0)) return 1.0
        val lane = canon(laneRaw)
        // V5.0.7955 — each stake is scaled by the authority behind it:
        // 1 + fraction x (KellyMult - 1), capped at CONVICTION_MAX_MULT.
        val cortexMult = cortexStake7893(mint, lane, equitySol).let { (stake, f) -> graduatedMult7955(f, stake, requestedSol) }
        // V5.0.7948 — the candidate's own proven evidence sizes it too (see provenEvidenceStake7948).
        val evidenceMult = provenEvidenceStake7948(mint, lane, equitySol).maxOfOrNull { (stake, f) -> graduatedMult7955(f, stake, requestedSol) } ?: 1.0
        val mult = maxOf(cortexMult, evidenceMult)
        if (mult > 1.0) {
            inc("SIZED_UP_$lane")
            try {
                PipelineHealthCollector.labelInc(
                    if (cortexMult >= evidenceMult) "CORTEX_7893_CONVICTION_SIZE_UP_$lane" else "EVIDENCE_KELLY_SIZE_UP_7948_$lane",
                )
            } catch (_: Throwable) {}
        }
        return mult
    }

    /** Pure (V5.0.7955): the request scaled toward a Kelly stake by authority [fraction]: in [1, CONVICTION_MAX_MULT]. */
    fun graduatedMult7955(fraction: Double, kellyStakeSol: Double, requestedSol: Double): Double {
        if (!fraction.isFinite() || fraction <= 0.0) return 1.0
        val kellyMult = stakeMult7948(kellyStakeSol, requestedSol)
        return (1.0 + fraction.coerceAtMost(1.0) * (kellyMult - 1.0)).coerceIn(1.0, CONVICTION_MAX_MULT)
    }

    /**
     * The 7893 STRONG-record quarter-Kelly stake (SOL) on current equity and the lane's
     * graduated authority (V5.0.7955), or (0, 0) when the read or the record does not qualify.
     */
    private fun cortexStake7893(mint: String, lane: String, equitySol: Double): Pair<Double, Double> {
        val none = 0.0 to 0.0
        val a = assessCache["$mint|$lane"] ?: return none
        if (System.currentTimeMillis() - a.atMs > 60_000L) return none
        if (a.bucket != CortexScoreboard7885.Bucket.STRONG || a.staleMark) return none
        return synchronized(this) {
            // The sizing cohort is the same immutable version-1 STRONG verdicts shown and proved by the authority test.
            // Read at the current cost offset, including a cost refresh that did not add a new grade.
            val strong = gradeBook8144.bucketStats8372(a.lane)[CortexScoreboard7885.Bucket.STRONG.ordinal]
            // V5.0.7948 — the lane's authority (not inverted, self-consistent), not the bare STRONG record.
            val f = laneFraction7955(a.lane)
            if (f <= 0.0) {
                inc(if (invertedCurrent8372(a.lane)) "SUSPENDED_INVERTED_SIZE_UP_7948" else "SHADOW_SIZE_UP"); return none
            }
            if (refuseShadow8310(a.lane, null) != null) { inc("SUSPENDED_REFUSE_BEATS_STRONG_SIZE_UP_8310"); CortexInversion8310.noteSizeUpSuspended8310(a.lane); return none }  // V5.0.8310
            if (f < 1.0) inc("PARTIAL_AUTHORITY_SIZE_7955")
            // V5.0.8372 — never exceed this same current STRONG out-of-sample proof's own quarter-Kelly stake.
            // Keep the former size-only floor of 20 STRONG OOS observations even when bounded decision authority is
            // earned earlier. Calibration and realised revocation remain unchanged; no legacy cohort can lend the
            // new predictor profit or a larger stake. All downstream wallet/risk/route caps remain.
            val proof = authorityTest8366(a.lane)
            if (!proof.granted || proof.topN < 20 || !proof.topSe.isFinite()) {
                inc("SIZE_UP_REFUSED_UNVALIDATED_8187_${a.lane}"); return none
            }
            val variance = proof.topSe * proof.topSe * proof.topN
            minOf(kellyStakeSol(strong.mean(), strong.variance(), equitySol),
                kellyStakeSol(proof.topMean, variance, equitySol)) to f
        }
    }

    /**
     * V5.0.7948 §SIZE_COMPOUNDS_WHERE_THE_EDGE_IS_PROVEN.
     *
     * Owner goal: exponential wallet growth. The 7893 conviction size-up never
     * fired (5.0.7947: Cortex seated 0, size-up shadow 101, real 0), so every
     * live order sat at the request whatever the evidence. A candidate whose lane
     * evidence is proven positive — its classified playbook setup in this lane (30+
     * labels, mean-SE > 0) or the lane's own forward labels (LiveEdgeGate7877.
     * laneProvenPositive7941) — is now sized toward quarter-Kelly of that record
     * (a pooled cross-lane cohort may admit a trade, but never sizes it up)
     * on the CURRENT wallet: as winners' proceeds return to the wallet the stake
     * grows with it (compounding), and as it shrinks the stake shrinks. Never
     * below the request (so never under the route minimum the request already
     * cleared), at most [CONVICTION_MAX_MULT]x; every downstream wallet, lane,
     * share and liquidity cap still bounds it. Unproven evidence changes nothing.
     */
    private fun provenEvidenceStake7948(mint: String, lane: String, equitySol: Double): List<Pair<Double, Double>> {
        if (!(equitySol > 0.0)) return emptyList()
        val ts = try { com.lifecyclebot.engine.BotService.status.tokens[mint] } catch (_: Throwable) { null } ?: return emptyList()
        val nowMs = System.currentTimeMillis()
        val setup = try { LanePlaybook7907.provenSetupRecord7948(ts, lane, nowMs) } catch (_: Throwable) { null }
        val laneStat = try { com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.laneStatFor7737(lane) } catch (_: Throwable) { null }
        // V5.0.7955 — a setup record carries its graduated authority as a third element (1.0 when absent).
        val setupStake = if (setup != null && setup.size >= 2) kellyStakeSol(setup[0], setup[1], equitySol) else 0.0
        val setupFraction = if (setup != null && setup.size >= 3) setup[2] else 1.0
        val laneStake = if (laneStat != null && com.lifecyclebot.engine.truth.LiveEdgeGate7877.laneProvenPositive7941(laneStat)) {
            kellyStakeSol(laneStat.meanNet60Pct, cohortVariance7948(laneStat.stderr60Pct, laneStat.n60), equitySol)
        } else 0.0
        // The lane's own proven labels (100+, mean-SE > 0) carry full authority.
        // V5.0.8187 — forward labels alone never raise size (8186: SHITCOIN forward STRONG n63 +41.9%, realised STRONG n2
        // -27.4%, walk-forward IN_SAMPLE_ONLY, 37 size-ups). A forward stake stands only beside independently validated
        // after-cost evidence of the same lane / setup — walk-forward out-of-sample proof or proven realised whole-position
        // closes — and is never larger than that evidence's own Kelly stake, at that evidence's authority.
        if (!(setupStake > 0.0) && !(laneStake > 0.0)) return emptyList()
        val setup8187 = try { LanePlaybook7907.selectedSetup8094(ts, lane, nowMs) } catch (_: Throwable) { null }
        val proof = synchronized(this) { validatedEvidence8187(lane, setup8187) ?: validatedEvidence8187(lane, null) }
            ?: run { inc("SIZE_UP_REFUSED_UNVALIDATED_8187_$lane"); return emptyList() }
        val proofStake = kellyStakeSol(proof.mean, proof.variance, equitySol)
        if (!(proofStake > 0.0)) { inc("SIZE_UP_REFUSED_PROOF_NOT_POSITIVE_8187_$lane"); return emptyList() }
        inc("SIZE_UP_VALIDATED_${proof.source}_8187")
        return listOf(minOf(setupStake, proofStake) to minOf(setupFraction, proof.fraction), minOf(laneStake, proofStake) to proof.fraction)
    }

    /** V5.0.8187 — independently validated after-cost evidence: robust mean %, per-trade variance %², authority, source. */
    class Proof8187(val mean: Double, val variance: Double, val fraction: Double, val source: String)

    /**
     * Pure (V5.0.8187): proof from a realised whole-position series of one cohort and mode (each close read through the label
     * cap — a robust mean): CortexRealisedAuthority8136's REALISED_PROVEN (20+ closes, mean - SE > 0) and a wallet that grew.
     */
    fun realisedProof8187(xs: List<Double>): Proof8187? {
        val capped = xs.filter { it.isFinite() }.map { com.lifecyclebot.engine.truth.RobustLabelRead8185.clampLabel8187(it) }
        val st = CortexWalkForward8144.stat8144(capped)
        if (st.n < 2 || !st.se.isFinite()) return null
        if (CortexRealisedAuthority8136.verdict8136(st.n.toDouble(), st.mean, st.se) != CortexRealisedAuthority8136.Verdict8136.REALISED_PROVEN) return null
        if (!(LearningObjective8153.score8153(capped).growthPerTradePct > 0.0)) return null
        return Proof8187(st.mean, st.se * st.se * st.n, 1.0, "REALISED")
    }

    /** Pure (V5.0.8187): proof from a walk-forward result — validated out of sample, at its authority cap. */
    fun walkForwardProof8187(wf: CortexWalkForward8144.Result8144): Proof8187? {
        if (!wf.validated || wf.oos.n < 2 || !wf.oos.se.isFinite() || !wf.oos.mean.isFinite()) return null
        return Proof8187(wf.oos.mean, wf.oos.se * wf.oos.se * wf.oos.n, CortexWalkForward8144.authorityCap8144(wf), "WALK_FORWARD")
    }

    /** Caller holds the lock. V5.0.8187 — the validated evidence of [lane] (x [setup]) in the account mode, or null. */
    private fun validatedEvidence8187(lane: String, setup: String?): Proof8187? =
        walkForwardProof8187(walkForward8144(lane, setup)) ?: realisedProof8187(gradeBook8144.realisedSeries(lane, setup, null, accountMode8077()))

    /** Pure: a label set's per-label variance (percent^2) from its standard error and count. */
    fun cohortVariance7948(stderrPct: Double, n: Int): Double =
        if (!stderrPct.isFinite() || stderrPct <= 0.0 || n <= 0) 0.0 else stderrPct * stderrPct * n

    /** Pure: the size multiplier a stake justifies over the request: at least 1, at most [CONVICTION_MAX_MULT]. */
    fun stakeMult7948(stakeSol: Double, requestedSol: Double): Double =
        if (!(requestedSol > 0.0) || !stakeSol.isFinite()) 1.0 else (stakeSol / requestedSol).coerceIn(1.0, CONVICTION_MAX_MULT)

    // ── Cortex v15: the legacy size stack, graded (v1 Phase 5, V5.0.7917) ──
    //
    // FinalDecisionGate multiplies the proposal by ~30 legacy factors (win-memory,
    // liquidity, tiers, Kelly, brain chain, consensus damps, policy heads...) and
    // 6552 collapses the product into one bounded shape [0.35, 1.5]. That shape
    // is now a voter, LEGACY_SIZE_SHAPE, graded like any other: does the stack's
    // shrink/grow predict the forward return? In a lane where it has been scored
    // [STACK_MEASURED] times and earned no seat, the stack loses its power to
    // SHRINK a candidate the Cortex reads STRONG on a proven record: that
    // candidate's shape is floored at 1.0 (the lane's own calculated size).
    // Absolute caps (live ceiling, pinned probes, wallet and route caps) still apply.
    const val LEGACY_SIZE_SHAPE = "LEGACY_SIZE_SHAPE"
    private const val STACK_MEASURED = 300
    private val legacyShape = ConcurrentHashMap<String, DoubleArray>()   // mint -> [shape, atMs]

    /** CortexVoters7885: the most recent legacy shape the FDG stack produced for this mint (10 min). */
    fun legacyShapeOf(mint: String, nowMs: Long): Double? =
        legacyShape[mint]?.takeIf { nowMs - it[1].toLong() <= 600_000L }?.get(0)

    /** Pure: the shape after Cortex v15 authority. */
    fun shapeAfterAuthority(bounded: Double, strongProven: Boolean, stackMeasuredNoSkill: Boolean): Double =
        if (strongProven && stackMeasuredNoSkill && bounded < 1.0) 1.0 else bounded

    /** FinalDecisionGate 6552: record the stack's raw shape and return the shape to use. */
    fun sizeShape(ts: TokenState, laneRaw: String, bounded: Double, raw: Double, nowMs: Long = System.currentTimeMillis()): Double {
        return try {
            CortexAudit8169.call("SIZE_SHAPE")  // V5.0.8169
            if (raw.isFinite() && raw > 0.0) {
                if (legacyShape.size > 4_000) legacyShape.entries.removeIf { nowMs - it.value[1].toLong() > 600_000L }
                legacyShape[ts.mint] = doubleArrayOf(raw, nowMs.toDouble())
            }
            if (bounded >= 1.0) return bounded
            val a = assessCache["${ts.mint}|${canon(laneRaw)}"]?.takeIf { nowMs - it.atMs <= 60_000L } ?: return bounded
            if (a.bucket != CortexScoreboard7885.Bucket.STRONG || a.staleMark) return bounded
            val (strongProven, noSkill) = synchronized(this) {
                val seat = ledger.seats["$LEGACY_SIZE_SHAPE|${a.lane}"]
                // V5.0.7955 — the same graduated threshold as the live overrule.
                (laneFraction7955(a.lane) >= OVERRULE_FRACTION_7955 && refuseShadow8310(a.lane, null) == null) to  // V5.0.8310
                    ((seat?.scored ?: 0) >= STACK_MEASURED && (seat?.authority() ?: 0.0) <= 0.0)
            }
            val out = shapeAfterAuthority(bounded, strongProven, noSkill)
            if (out != bounded) {
                inc("STACK_SHRINK_OVERRULED_${a.lane}")
                try { PipelineHealthCollector.labelInc("CORTEX_7917_STACK_SHRINK_OVERRULED_${a.lane}") } catch (_: Throwable) {}
            } else if (strongProven) inc("SHADOW_STACK_OVERRULE")
            out
        } catch (_: Throwable) { bounded }
    }

    // ── progressive enrichment (plan v2 §B): buy a paid feature only while it pays ──

    private const val ENRICH_LEARNING_SCORES = 300
    private const val ENRICH_MEMO_MS_8187 = 60_000L
    private val enrichMemo8187 = ConcurrentHashMap<String, Pair<Long, Boolean>>()
    private const val ENRICH_EXPLORE_RATE = 0.10

    /**
     * Should a paid feature backing [voterId] still be fetched? Yes while it is
     * being learned (< 300 graded scores across lanes) or while any lane seats
     * it. Once it has been measured and seats nowhere, only a 10% exploration
     * slice is bought, so it can earn its way back. Fails open.
     */
    fun enrichmentWorth(voterId: String): Boolean {
        return try {
            ensureLoaded()
            // V5.0.8187 — memoised per voter for a minute: every safety check scanned all ~5,000 seats under the Cortex lock.
            val now8187 = System.currentTimeMillis()
            val worth = enrichMemo8187[voterId]?.takeIf { now8187 - it.first in 0L until ENRICH_MEMO_MS_8187 }?.second ?: synchronized(this) {
                val mine = ledger.seats.entries.filter { it.key.startsWith("$voterId|") }
                val scored = mine.sumOf { it.value.scored }
                scored < ENRICH_LEARNING_SCORES || mine.any { it.value.authority() > 0.0 }
            }.also { enrichMemo8187[voterId] = now8187 to it }
            if (worth) true else {
                val explore = kotlin.random.Random.nextDouble() < ENRICH_EXPLORE_RATE
                inc(if (explore) "ENRICH_EXPLORE_$voterId" else "ENRICH_SKIPPED_$voterId")
                explore
            }
        } catch (_: Throwable) { true }
    }

    // ── OutcomeTruth cross-check: entry verdict -> whole-position close ──

    // V5.0.8082 §ENTRY_READ_PER_LANE — mint|MODE|LANE. Several lanes assess one mint; keyed by mint|MODE the
    // last lane overwrote the others and the bind saw the wrong lane (ENTRY_BIND_MISMATCH_8076) or none.
    private val entryReads = ConcurrentHashMap<String, Assessment>()   // mint|MODE|LANE
    /** V5.0.8395 (R9) — the read each FDG intent sealed, by candidate version; the fill binds exactly this one. */
    private val entrySeals8395 = CortexEntrySeal8395<Assessment>()
    private const val SEAL_MAX_AGE_MS_8395 = 6L * 60L * 60_000L
    private const val SAME_SEAL_SLACK_MS_8395 = 5_000L

    private fun readKey8395(mint: String, mode: String, lane: String): String {
        val normalized = try { CrossAssetCortex7931.normalizeCloseKey(mint) } catch (_: Throwable) { mint }
        return "$normalized|${mode.uppercase()}|${canon(lane)}"
    }

    /**
     * ExecutableOpenGate.publishFdgIntent6519: seal the approving entry read A into this candidate version. A read still
     * being captured leaves the seal PENDING; only a read decided no later than the seal may fill it.
     */
    fun sealEntryRead8395(mint: String, mode: String, lane: String, candidateVersion: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (candidateVersion <= 0L || mint.isBlank()) return false
        val k = readKey8395(mint, mode, lane)
        val a = entryReads[k]?.takeIf { nowMs - entryDecisionAt8187(it, k) in 0L..ENTRY_READ_MATCH_MS }
        val sealed = entrySeals8395.seal(k, candidateVersion, a?.let { detachAssessment8386(it) }, nowMs)
        if (sealed) inc(if (a != null) "ENTRY_READ_SEALED_8395" else "ENTRY_READ_SEAL_PENDING_8395")
        if (entrySeals8395.size() > 3_000) entrySeals8395.prune(nowMs, SEAL_MAX_AGE_MS_8395)
        return sealed
    }
    internal fun entrySealState8395(mint: String, mode: String, lane: String, candidateVersion: Long): CortexEntrySeal8395.State =
        entrySeals8395.resolve(readKey8395(mint, mode, lane), candidateVersion).state
    private val positionReads8076 = CortexEntryReadBook8076<Assessment>()
    private const val ENTRY_READ_MATCH_MS = 15L * 60_000L

    /** The common canonical-open hook covers direct fills and pending-entry promotion. */
    fun bindPosition8076(positionId: String, mint: String, lane: String, mode: String, openedAtMs: Long,
                         learningIdentity8372: String? = null, candidateVersion8395: Long = 0L) {
        val m = mode.uppercase()
        // Only the confirmed-fill seal supplies an alternate learning identity. Lookup and actual position
        // identity stay separate: a ticker prediction must never replace the canonical filled asset.
        val lookupMint = learningIdentity8372?.takeIf { it.isNotBlank() } ?: mint
        val normalized = try { CrossAssetCortex7931.normalizeCloseKey(lookupMint) } catch (_: Throwable) { lookupMint }
        val canonicalMint = try { CrossAssetCortex7931.normalizeCloseKey(mint) } catch (_: Throwable) { mint }
        val laneKey8082 = canon(lane)
        // V5.0.8395 (R9) — a versioned fill binds ONLY the read its intent sealed; never the latest reassessment.
        if (candidateVersion8395 > 0L) {
            val rk = "$normalized|$m|$laneKey8082"
            val res = entrySeals8395.resolve(rk, candidateVersion8395)
            when (res.state) {
                CortexEntrySeal8395.State.SEALED -> {
                    bindRead8187(positionId, mint, canonicalMint, m, res.read!!, openedAtMs)
                    entrySeals8395.release(rk, candidateVersion8395)
                    inc("ENTRY_BOUND_SEALED_8395")
                }
                CortexEntrySeal8395.State.PENDING -> deferBind8187(positionId, mint, normalized, m, laneKey8082, openedAtMs, candidateVersion8395)
                CortexEntrySeal8395.State.UNSEALED -> inc("ENTRY_BIND_UNRESOLVED_UNSEALED_8395")
            }
            return
        }
        val a = entryReads["$normalized|$m|$laneKey8082"] ?: run {
            // Other lanes' reads of this mint are contributor evidence, never the owner's verdict.
            inc(if (entryReads.keys.any { it.startsWith("$normalized|$m|") }) "ENTRY_BIND_OWNER_LANE_UNASSESSED_8082" else "ENTRY_BIND_MISSING_8076")
            deferBind8187(positionId, mint, normalized, m, laneKey8082, openedAtMs)  // V5.0.8187 — the read may still arrive
            return
        }
        // V5.0.8187 — staleness is measured from the DECISION (a cold capture's voters are read on the pool after it).
        if (openedAtMs - entryDecisionAt8187(a, "$normalized|$m|$laneKey8082") !in 0L..ENTRY_READ_MATCH_MS) {
            inc("ENTRY_BIND_STALE_READ_8082")
            deferBind8187(positionId, mint, normalized, m, laneKey8082, openedAtMs)  // V5.0.8187 — this decision's read may follow
            return
        }
        bindRead8187(positionId, mint, canonicalMint, m, a, openedAtMs)
    }

    /** V5.0.8187 — bind [a] to [positionId] and open its trade record (shared by the open hook and a deferred bind). */
    private fun bindRead8187(positionId: String, mint: String, normalized: String, m: String, a: Assessment, openedAtMs: Long) {
        if (positionReads8076.bind(positionId, normalized, m, a)) {
            inc("ENTRY_BOUND_8076")
            // V5.0.8144 — the trade's own grade record (off this thread: never take the Cortex lock under the position lock).
            submit("TRADE_OPEN_8144") { openTrade8144(positionId, mint, m, a, openedAtMs) }
            // Never serialize the learning book while holding the canonical position lock.
            submit("ENTRY_CHECKPOINT") { persist() }
        }
    }

    private fun encodeEntryRead8076(a: Assessment): org.json.JSONObject = org.json.JSONObject()
        .put("l", a.lane).put("g", a.regime).put("r", a.runnerLane).put("at", a.atMs).put("b", a.bucket.ordinal)
        .put("ids", org.json.JSONArray(a.ids)).put("raw", jsonNumbers(a.raws))
        .put("edges8372", org.json.JSONArray().also { ee -> a.edges.forEach { ee.put(jsonNumbers(it)) } })
        .put("pred", jsonNumbers(a.prediction8076?.preds)).put("win", jsonNumbers(a.prediction8076?.pWins))
        .put("base", jsonNumber(a.prediction8076?.base)).put("bw", jsonNumber(a.prediction8076?.baseWin))
        .put("provenance8372", provenance8372(a).encode())

    private fun decodeEntryRead8076(o: org.json.JSONObject): Assessment? {
        val ji = o.optJSONArray("ids") ?: return null
        val jr = o.optJSONArray("raw") ?: return null
        val jp = o.optJSONArray("pred"); val jw = o.optJSONArray("win")
        val ids = ArrayList<String>(); val edges = ArrayList<DoubleArray>()
        val raws = ArrayList<Double>(); val preds = ArrayList<Double>(); val wins = ArrayList<Double>()
        for (i in 0 until minOf(ji.length(), jr.length())) {
            val id = ji.optString(i)
            val frozenEdges = o.optJSONArray("edges8372")?.optJSONArray(i)
            val edge = frozenEdges?.let { e -> DoubleArray(e.length()) { e.optDouble(it) } } ?: CortexVoters7885.edgesFor(id) ?: continue
            ids.add(id); edges.add(edge); raws.add(jr.optDouble(i, Double.NaN))
            preds.add(jp?.optDouble(i, Double.NaN) ?: Double.NaN); wins.add(jw?.optDouble(i, Double.NaN) ?: Double.NaN)
        }
        val bucket = CortexScoreboard7885.Bucket.values().getOrNull(o.optInt("b", -1)) ?: return null
        val pv8372 = CortexAssessmentProvenance8372.decode(o.optJSONObject("provenance8372"))
        val fused = CortexLedger7885.Fused(pv8372?.rawEdge ?: 0.0, 0.0, pv8372?.laneMean ?: 0.0, 0.0, 0.0, 0.0, 0.0, emptyList(), laneMeanAll8325 = pv8372?.laneMeanAll ?: Double.NaN)
        return Assessment(o.optString("l"), o.optBoolean("r"), raws.toDoubleArray(), ids, edges, o.optString("g"),
            fused, pv8372?.calibratedEdge ?: 0.0, bucket, 0, false, pv8372?.assessmentAtMs ?: o.optLong("at"),
            CortexLedger7885.PredictionSnapshot8076(preds.toDoubleArray(), wins.toDoubleArray(), o.optDouble("base", Double.NaN), o.optDouble("bw", Double.NaN)),
            scoreVersion8372 = pv8372?.scoreVersion ?: 0, calibrationInput8372 = pv8372?.calibrationInput ?: Double.NaN)
    }

    private fun noteEntryRead(mint: String, paper: Boolean, a: Assessment, decisionAtMs: Long = a.atMs) {
        if (entryReads.size > 4_000) entryReads.entries.removeIf { System.currentTimeMillis() - it.value.atMs > PENDING_TTL_MS * 4 }
        val normalized8082 = try { CrossAssetCortex7931.normalizeCloseKey(mint) } catch (_: Throwable) { mint }
        entryReads["$normalized8082|${if (paper) "PAPER" else "LIVE"}|${canon(a.lane)}"] = a
        val key8187 = "$normalized8082|${if (paper) "PAPER" else "LIVE"}|${canon(a.lane)}"
        // V5.0.8187 — the read's decision time, and a position that opened before its (asynchronous) read arrived.
        noteReadAt8187(key8187, minOf(decisionAtMs, a.atMs))
        retryDeferredBinds8187(key8187, a)
    }

    // ── V5.0.8187 §REALISED_BINDING — every whole-position close finds its entry verdict ──
    //
    // 5.0.8175: REALIZED_UNMATCHED=1659 / REALIZED_UNBOUND_OLDER_THAN_BOOK_8169=778, realised books n1-n2, auth 0.00. Three
    // gaps: (1) since 8178 a cold decision's entry read is noted on the capture pool, often AFTER the canonical open already
    // asked for it (ENTRY_BIND_MISSING), and nothing retried; (2) the staleness window was measured from the read time, so a
    // read taken after the fill looked like it came from the future; (3) a close with no bound read never looked at the
    // persisted grade records, which hold the admitted decision's verdict. Now an open with no read waits for it (bounded,
    // [DEFERRED_BIND_MS_8187]); staleness is read from the decision; and a close with no read binds to the admitted decision
    // record of the same coin, lane and mode made within [ENTRY_READ_MATCH_MS] before the open (its sealed decision time
    // first), once only (its trade record dedupes boot replays). Boot replays of closes the book already filed are counted
    // apart from real misses.
    private class DeferredBind8187(val positionId: String, val mint: String, val key: String, val openedAtMs: Long, val version8395: Long = 0L)
    private val deferredBinds8187 = ConcurrentHashMap<String, DeferredBind8187>()   // positionId
    private val entryReadAt8187 = ConcurrentHashMap<String, Long>()                  // entry-read key -> decision time
    private const val DEFERRED_BIND_MS_8187 = 10L * 60_000L

    private fun noteReadAt8187(key: String, atMs: Long) {
        if (entryReadAt8187.size > 4_000) entryReadAt8187.keys.removeIf { !entryReads.containsKey(it) }
        entryReadAt8187[key] = atMs
    }

    private fun entryDecisionAt8187(a: Assessment, key: String): Long = entryReadAt8187[key]?.let { minOf(it, a.atMs) } ?: a.atMs

    /** Pure: a pending decision can bind a fast close only when the original
     * immutable voters were ready at decision time and mode matches the actual fill. */
    internal fun pendingReadMatches8196(
        admitted: Boolean, decisionMode: String, closeMode: String,
        readAtMs: Long, decisionAtMs: Long, openedAtMs: Long, lateRead: Boolean,
    ): Boolean = admitted && !lateRead && decisionMode == closeMode &&
        (closeMode == "LIVE" || closeMode == "PAPER") &&
        readAtMs > 0L && readAtMs <= decisionAtMs && readBindable8187(decisionAtMs, openedAtMs)

    /** Pure (V5.0.8187): may a read decided at [decisionAtMs] bind a position opened at [openedAtMs]? */
    fun readBindable8187(decisionAtMs: Long, openedAtMs: Long): Boolean = openedAtMs - decisionAtMs in 0L..ENTRY_READ_MATCH_MS

    private fun deferBind8187(positionId: String, mint: String, normalized: String, mode: String, lane: String, openedAtMs: Long,
                              version8395: Long = 0L) {
        if (positionId.isBlank()) return
        val now = System.currentTimeMillis()
        if (deferredBinds8187.size > 1_000) deferredBinds8187.entries.removeIf { now - it.value.openedAtMs > DEFERRED_BIND_MS_8187 }
        deferredBinds8187[positionId] = DeferredBind8187(positionId, mint, "$normalized|$mode|$lane", openedAtMs, version8395)
        inc("ENTRY_BIND_DEFERRED_8187")
    }

    private fun retryDeferredBinds8187(key: String, a: Assessment) {
        if (deferredBinds8187.isEmpty()) return
        val decidedAt = entryDecisionAt8187(a, key)
        // V5.0.8395 — a late read fills only the PENDING seals it was decided for, then their versioned binds.
        val filled8395 = entrySeals8395.fillLate(key, detachAssessment8386(a), decidedAt, SAME_SEAL_SLACK_MS_8395)
        if (filled8395.isNotEmpty()) inc("ENTRY_READ_SEAL_FILLED_LATE_8395")
        for (d in deferredBinds8187.values.toList()) {
            if (d.key != key) continue
            if (d.version8395 > 0L) {
                val res = entrySeals8395.resolve(key, d.version8395)
                if (res.state != CortexEntrySeal8395.State.SEALED || !deferredBinds8187.remove(d.positionId, d)) continue
                val canonicalMint = try { CrossAssetCortex7931.normalizeCloseKey(d.mint) } catch (_: Throwable) { d.mint }
                bindRead8187(d.positionId, d.mint, canonicalMint, key.substringBeforeLast('|').substringAfterLast('|'), res.read!!, d.openedAtMs)
                entrySeals8395.release(key, d.version8395)
                inc("ENTRY_BOUND_SEALED_LATE_8395")
                continue
            }
            if (!readBindable8187(decidedAt, d.openedAtMs)) continue
            if (!deferredBinds8187.remove(d.positionId, d)) continue
            // Asset identities may contain '|'; mode and lane are the two trailing fields.
            val mode = key.substringBeforeLast('|').substringAfterLast('|')
            val canonicalMint = try { CrossAssetCortex7931.normalizeCloseKey(d.mint) } catch (_: Throwable) { d.mint }
            bindRead8187(d.positionId, d.mint, canonicalMint, mode, a, d.openedAtMs)
            inc("ENTRY_BOUND_LATE_READ_8187")
        }
    }

    /**
     * Caller holds the lock. V5.0.8187 — the entry verdict of a close with no bound read: a deferred bind whose read arrived,
     * else the admitted decision record (bucket only, no voter raws) — after its trade record opens, so it is filed once.
     */
    private fun recoverRead8187(env: com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464.Envelope, closeKey: String, mode: String,
                                sealed: com.lifecyclebot.engine.truth.AateEntryIdentity8136?,
                                snap8332: com.lifecyclebot.engine.truth.EntryStrategySnapshot6450.Snapshot? = null): Assessment? {
        // 8198 — observe, do not consume: a failed/retried delivery must not destroy the deferred bind, and a later
        // arriving read still binds through retryDeferredBinds8187. The entry is cleaned by its own TTL/size bound.
        deferredBinds8187[env.positionId]?.let { d ->
            val a = entryReads[d.key]
            if (a != null && readBindable8187(entryDecisionAt8187(a, d.key), d.openedAtMs)) { inc("REALIZED_BOUND_DEFERRED_READ_8187"); return a }
        }
        if (gradeBook8144.trade(env.positionId) != null) return null
        val openedAt = env.atMs - env.holdingTimeMs.coerceAtLeast(0L)
        // V5.0.8332 — ONE canonical key: the lane the entry snapshot sealed at the fill (same mint) leads, and the sealed entry
        // time is a second open time to look the admitted decision up under (the close's holding time can disagree with it).
        val snapLane8332 = snap8332?.takeIf { it.mint == env.mint && it.mode8090.equals(mode, true) }?.entryLane
        val lanes = listOfNotNull(snapLane8332, sealed?.takeIf { it.mode.equals(mode, true) && it.mint == env.mint }?.ownerLane, canon(env.lane), env.lane.trim().uppercase())
            .map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct()
        val openedAtSnap8332 = com.lifecyclebot.engine.truth.GradeBind8332.openedAt8332(
            snap8332?.takeIf { it.mint == env.mint }?.entryTimestampMs, env.atMs, env.holdingTimeMs)
        val sealedAt = sealed?.takeIf { it.mode.equals(mode, true) && it.mint == env.mint }?.decisionTimestampMs ?: 0L
        // 8196: a fast on-chain close can arrive before the five-minute forward
        // label books its original entry read. The immutable pending decision
        // already has the exact lane/mode/voters. Bind it NOW instead of filing
        // the close as permanently unbound and losing the entry verdict.
        val stillPending8196 = lanes.asSequence()
            .flatMap { lane -> sequenceOf(env.mint, closeKey).distinct().map { mint -> "$mint|$lane" } }
            .mapNotNull { pending[it] }
            .firstOrNull { p ->
                pendingReadMatches8196(p.legacyAdmitted, p.decisionMode8196, mode,
                    p.a.atMs, p.atMs, openedAt, p.lateRead8169)
            }
        if (stillPending8196 != null) {
            inc("REALIZED_BOUND_FROM_PENDING_DECISION_8196")
            return stillPending8196.a
        }
        val d = lanes.asSequence().flatMap { l -> sequenceOf(env.mint, closeKey).distinct().map { m -> m to l } }
            .mapNotNull { (m, l) ->
                (if (sealedAt > 0L) gradeBook8144.admittedDecision8187(m, l, mode, sealedAt + 60_000L, 120_000L) else null)
                    ?: gradeBook8144.admittedDecision8187(m, l, mode, openedAt, ENTRY_READ_MATCH_MS)
                    ?: (if (openedAtSnap8332 != openedAt) gradeBook8144.admittedDecision8187(m, l, mode, openedAtSnap8332, ENTRY_READ_MATCH_MS) else null)   // V5.0.8332
            }.firstOrNull() ?: return null
        val bucket = CortexScoreboard7885.Bucket.values().getOrNull(d.bucket) ?: return null
        val fused = CortexLedger7885.Fused(d.predictedEdgePct.takeIf { it.isFinite() } ?: 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyList())
        val a = Assessment(d.lane, isRunner(d.lane), DoubleArray(0), emptyList(), emptyList(), d.regime.takeIf { it != "-" }.orEmpty(),
            fused, d.predictedEdgePct, bucket, 0, false, d.atMs, scoreVersion8372 = d.scoreVersion8372)
        val r = CortexGradeRecord8144('T', env.positionId, "${env.mint}|${d.lane}", openedAt, d.lane, d.setup, d.regime,
            d.timing, d.costBand, d.cohort, d.bucket, d.predictedEdgePct, true, true, false)
        r.mode = mode
        r.scoreVersion8372 = d.scoreVersion8372
        if (!gradeBook8144.openTrade(r)) { inc("REALIZED_RECOVERY_OLDER_THAN_BOOK_8187"); return null }
        inc("REALIZED_BOUND_FROM_DECISION_RECORD_8187")
        return a
    }

    /**
     * V5.0.8198 — the Cortex OutcomeTruth close is now a DELIVERY (FinalizedBusConsumerBridge6465 delivers envelope to
     * registered consumer "CortexOutcome7885"), not a swallowed direct call inside [CanonicalFinalizedTradeBus6464.publish].
     * It therefore reaches the same quarantine / pre-reset-epoch / exact-economics gates as every learning consumer,
     * and one delivery can only ACK what is durable:
     *  • duplicate delivery of a committed close is a no-op (receipt gate first, before any mutation);
     *  • the entry read is PEEKED and only taken/consumed when the grade is actually applied, so a failed delivery
     *    leaves the assessment bound for a later bind/recovery instead of destroying it mid-grading;
     *  • graded state (ledger + books + grades + pending reads) and the per-trade receipt commit in ONE transaction
     *    (LearningPersistence.commitOnce8090). In-memory grading awaits a durable commit in
     *    [uncommittedCloseGrades8198]; while that is pending, a redelivery re-attempts ONLY the durable commit,
     *    never the grade — partial double-grading is impossible in-process, and across restarts the durable receipt
     *    (or the still-persisted pending read) is the only way back in.
     */
    fun onCanonicalClose(env: com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464.Envelope): CloseDelivery8198 {
        if (!env.terminal) return CloseDelivery8198.ACKED
        ensureLoaded()
        val mode = if (env.mode.equals("live", true)) "LIVE" else if (env.mode.equals("paper", true)) "PAPER" else return CloseDelivery8198.ACKED
        // V5.0.7931 — cross-asset rows close under "solana|<mint>" / ticker / traded mint;
        // all map onto the identity the entry was captured under.
        val closeKey7931 = try { CrossAssetCortex7931.normalizeCloseKey(env.mint) } catch (_: Throwable) { env.mint }
        // V5.0.8082 — the realised books learn only from typed, verified economic finality (LIVE_FINALIZED /
        // LIVE_RECONCILED / PAPER_SIMULATED); an unverified close releases its entry read without teaching.
        val verified8082 = com.lifecyclebot.engine.truth.ExactStrategyPerformance7429.terminalEconomicsEligible8081(env.mode, env.proofState)
        if (!verified8082) inc("REALIZED_UNVERIFIED_FINALITY_8082")
        val eligible = env.learningEligible && env.realizedReturnPct.isFinite() && verified8082
        // V5.0.8144 — the owner of the trade, read outside the lock: the sealed entry identity, else the entry snapshot.
        val sealed8144 = if (!eligible) null else try { com.lifecyclebot.engine.truth.AateEntryIdentityBook8136.identity8136(env.positionId) } catch (_: Throwable) { null }
        // V5.0.8332 — the whole sealed entry snapshot is read here, outside the lock (its lane and entry time are the canonical bind key).
        val snap8332 = if (!eligible) null else try { com.lifecyclebot.engine.truth.EntryStrategySnapshot6450.snapshot(env.positionId) } catch (_: Throwable) { null }
        val snapTag8144 = snap8332?.cortexSetupTag8085
        var gradeMutationsBegun8198 = false
        // V5.0.8325 §NO_IO_UNDER_THE_CORTEX_LOCK — the receipt row is read BEFORE the lock (boot replays ~1,345 closes, each read
        // the store while holding the Cortex monitor). Receipts are only ever added, so a stale "absent" re-checks harmlessly.
        val storedReceipt8325 = eligible && env.tradeId !in closeReceipts8198 &&
            (try { LearningPersistence.load(closeReceipt8198(env.tradeId)) != null } catch (_: Throwable) { false })
        var execRefresh8325 = false
        synchronized(this) {
            val tradeId = env.tradeId
            // A close this process already committed durably is done, whatever the bus redelivers.
            if (tradeId in closeReceipts8198) { inc("CORTEX_CLOSE_DUPLICATE_ACKED_8198"); return CloseDelivery8198.ACKED }
            if (!eligible) {
                // An unverified / learning-ineligible terminal is explicit, not a failure: release the captured entry
                // (it never teaches) and acknowledge. Re-delivery is a harmless null re-release.
                val released8198 = positionReads8076.take(env.positionId, closeKey7931, mode)
                if (released8198 != null) entryReads.remove("$closeKey7931|$mode|${canon(released8198.lane)}")
                if (mode == "PAPER") chosen.remove(env.mint)
                inc("REALIZED_NOT_ELIGIBLE")
                return CloseDelivery8198.ACKED
            }
            // A committed grade from a previous process life: the receipt row proved the state in its transaction.
            if (storedReceipt8325) {
                closeReceipts8198.add(tradeId)
                inc("CORTEX_CLOSE_DURABLE_ALREADY_ACKED_8198")
                return CloseDelivery8198.ACKED
            }
            if (tradeId !in uncommittedCloseGrades8198) {
                // First delivery for this close in this process: grade it once, gate FIRST.
                uncommittedCloseGrades8198.add(tradeId)
                try {
                    // 8198 — PEEK, never take: the entry assessment survives a failed grade or a failed delivery,
                    // and recovery paths can still see it.
                    val a: Assessment? = positionReads8076.peek(env.positionId, closeKey7931, mode)
                        ?: recoverRead8187(env, closeKey7931, mode, sealed8144, snap8332)
                    if (a == null) {
                        // V5.0.8187 — a boot replay of a close the book already filed is not a missed binding.
                        val replay8187 = env.atMs in 1 until loadedAtMs8169
                        val filed8332 = gradeBook8144.trade(env.positionId) != null
                        if (replay8187 && filed8332) inc("REALIZED_REPLAY_ALREADY_FILED_8187")
                        else if (replay8187) inc("REALIZED_REPLAY_UNMATCHED_8187")
                        else inc("REALIZED_UNMATCHED")
                        // V5.0.8332 — every unbound close names why (gradeBind8332[unbound=why]); counters only, no IO under the lock.
                        com.lifecyclebot.engine.truth.GradeBind8332.noteUnbound8332(com.lifecyclebot.engine.truth.GradeBind8332.unboundWhy8332(
                            replay8187, filed8332, snap8332 != null,
                            snap8332 != null && !snap8332.entryLane.equals(env.lane, true), sealed8144 != null))
                        // 8153 — absent entry evidence is explicit: the sealed owner (no voter raws) takes the outcome.
                        if (!fileUnboundClose8153(env, mode, sealed8144, snapTag8144)) {
                            uncommittedCloseGrades8198.remove(tradeId)   // nothing mutated; a redelivery would be the same no-op
                            return CloseDelivery8198.ACKED
                        }
                        // The unbound filing IS graded state: it crosses the same durability barrier below.
                        gradeMutationsBegun8198 = true
                    } else {
                        if (env.atMs >= loadedAtMs8169 || env.atMs <= 0L) inc("REALIZED_BOUND_LIVE_SESSION_8187")
                        gradeMutationsBegun8198 = true
                        // The grade consumes the assessment exactly when it is applied (the peek above left it intact).
                        positionReads8076.take(env.positionId, closeKey7931, mode)
                        entryReads.remove("$closeKey7931|$mode|${canon(a.lane)}", a)
                        val wasChosen = mode == "PAPER" && chosen.remove(env.mint) != null
                        board.recordRealized(mode, a.lane, a.bucket, env.realizedReturnPct)
                        if (wasChosen) board.recordRealized("PAPER_CHOSEN", a.lane, a.bucket, env.realizedReturnPct)
                        // V5.0.7912 — Cortex v12, OutcomeTruth (v1 §2.7): every voter's entry-time
                        // opinion is also graded on the WHOLE-POSITION realised return, per mode,
                        // in its own ledger — skill on real fills and real exits, beside the
                        // forward-label skill that grants authority.
                        realLedgers.getOrPut(mode) { CortexLedger7885(Double.POSITIVE_INFINITY) }  // V5.0.8187 — money, never capped
                            .grade(a.lane, a.ids, a.edges, a.raws, env.realizedReturnPct, env.realizedReturnPct, a.regime, predictionForGrade8076(a))
                        // V5.0.8144 — the realised return goes to the exact strategy that owned the trade.
                        routeRealised8144(env, mode, a, sealed8144, snapTag8144)
                        lastExecRefresh8187 = 0L; execRefresh8325 = true  // V5.0.8187 — a close re-measures the gap (V5.0.8325: after the lock)
                        inc("REALIZED_${mode}")
                        com.lifecyclebot.engine.truth.GradeBind8332.noteBound8332()   // V5.0.8332 — gradeBind8332[bound]
                    }
                } catch (t: Throwable) {
                    // A failure BEFORE the first mutation is a clean retry: the unread gate is released and the next
                    // delivery re-grades from the still-bound (peeked) read. A failure AFTER the first mutation can
                    // never be re-applied without double-grading the additive books, so it is terminal for this
                    // consumer: an explicit, durable exclusion (never an ACK), the half-grade labeled, nothing durable.
                    uncommittedCloseGrades8198.remove(tradeId)
                    inc(if (gradeMutationsBegun8198) "CORTEX_CLOSE_GRADE_FAILED_TERMINAL_8198" else "CORTEX_CLOSE_GRADE_FAILED_RETRYABLE_8198")
                    ForensicLogger.lifecycle(if (gradeMutationsBegun8198) "CORTEX_CLOSE_GRADE_FAILED_TERMINAL_8198" else "CORTEX_CLOSE_GRADE_FAILED_RETRYABLE_8198",
                        "tradeId=${tradeId.take(24)} positionId=${env.positionId.take(24)} mutationsBegun=$gradeMutationsBegun8198 err=${t.javaClass.simpleName}:${(t.message ?: "").take(100)}")
                    if (gradeMutationsBegun8198) {
                        try { com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464.exclude(
                            "CortexOutcome7885", tradeId, "CORTEX_CLOSE_GRADE_FAILED_TERMINAL_8198") } catch (_: Throwable) {}
                        gradeMutationsBegun8198 = false
                        return CloseDelivery8198.RETRY   // the recorded exclusion makes this attempt terminal, not re-graded
                    }
                    return CloseDelivery8198.RETRY       // clean retry: next delivery re-grades
                }
            }
            // Either freshly graded in this process or a redelivery of an uncommitted grade: (re-)attempt the durable
            // receipt commit with the current graded state. Never acknowledge a scheduled write.
        }
        if (execRefresh8325) try { refreshExecutable8187(System.currentTimeMillis()) } catch (_: Throwable) {}
        val durable = try { durableCloseCommit8198(closeReceipt8198(env.tradeId)) } catch (_: Throwable) { false }
        return when (durable) {
            true -> synchronized(this) {
                closeReceipts8198.add(env.tradeId)
                uncommittedCloseGrades8198.remove(env.tradeId)
                inc("CORTEX_CLOSE_DURABLE_ACKED_8198")
                postCloseCheckpoint8198()
                CloseDelivery8198.ACKED
            }
            false -> CloseDelivery8198.RETRY   // state stays uncommitted: redelivery re-attempts the commit, not the grade
        }
    }

    /**
     * 8198 — after a durable close commit, refresh the ordinary checkpoint cadence the old direct path used.
     * V5.0.8327 — no second save: the close commit (durableCloseCommitLocked8325) has just written the same three rows
     * (ledger books, pending reads, grade book) in one transaction, so the queued persist() re-encoded the whole graph a
     * second time per close (two multi-MB encodes per close: heap and the persistence thread). The periodic and entry
     * checkpoints still save; this counts the save the commit made redundant.
     */
    private fun postCloseCheckpoint8198() { inc("CLOSE_CHECKPOINT_WRITTEN_BY_COMMIT_8327") }

    // ── persistence ──

    /** V5.0.8325 — after a failed load, the next attempt waits this long (each attempt decodes the whole book under the lock). */
    private const val LOAD_RETRY_MS_8325 = 30_000L
    @Volatile private var loadRetryAtMs8325 = 0L

    private fun ensureLoaded() {
        if (loaded) return
        // V5.0.8325 — a failed load (VM error / unreadable row) retried on EVERY call: each assessment re-decoded the book while
        // holding the Cortex lock. It now retries at most every [LOAD_RETRY_MS_8325].
        if (System.currentTimeMillis() < loadRetryAtMs8325) return
        // V5.0.7930 — never latch "loaded" before the store opens: an early read came back
        // empty and the next save overwrote the real ledgers with it.
        if (!LearningPersistence.ready()) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            try {
                try { gradeBook8144.decode(LearningPersistence.load(GRADES_KEY_8144)) } catch (_: Throwable) {}  // V5.0.8144
                val raw8324 = LearningPersistence.load(PERSIST_KEY)
                val o = org.json.JSONObject(raw8324 ?: run {
                    LearningPersistence.load(LEGACY_PERSIST_KEY_7947)?.let { legacy ->
                        org.json.JSONObject(legacy).optJSONObject("real")?.let { j -> for (k in j.keys()) j.optJSONObject(k)?.let { realLedgers[k] = CortexLedger7885(Double.POSITIVE_INFINITY).also { l -> l.decode(it) } } }
                        inc("LEGACY_REAL_CARRIED_7947")
                    }
                    try { restorePending(LearningPersistence.load(PENDING_PERSIST_KEY), System.currentTimeMillis()) }
                    catch (pendingFailure8394: Throwable) {
                        if (pendingFailure8394 is VirtualMachineError) throw pendingFailure8394
                        LearningPersistence.decodeFailed8086(PENDING_PERSIST_KEY, pendingFailure8394)
                        inc("CORTEX_PENDING_RECOVERY_INCOMPLETE_8394")
                    }
                    return
                })
                snapshotGeneration8386 = maxOf(snapshotGeneration8386, o.optLong("snapshotGeneration8386", 0L))
                o.optJSONObject("ledger")?.let { ledger.decode(it) }
                o.optJSONObject("board")?.let { board.decode(it) }
                o.optJSONObject("calibration")?.let { calibration.decode(it) }
                o.optJSONObject("real")?.let { j -> for (k in j.keys()) j.optJSONObject(k)?.let { realLedgers[k] = CortexLedger7885(Double.POSITIVE_INFINITY).also { l -> l.decode(it) } } }
                o.optJSONObject("vetoes")?.let { j -> for (k in j.keys()) vetoBook[k] = CortexLedger7885.labelStat8187().also { it.decode(j.optString(k)) } }
                o.optJSONObject("joint7961")?.let { j -> for (k in j.keys()) jointBook7961[k] = CortexLedger7885.labelStat8187().also { it.decode(j.optString(k)) } }
                restoreJointGenerations8372(o)
                try { restorePending(LearningPersistence.load(PENDING_PERSIST_KEY), System.currentTimeMillis()) }
                    catch (pendingFailure8394: Throwable) {
                        if (pendingFailure8394 is VirtualMachineError) throw pendingFailure8394
                        LearningPersistence.decodeFailed8086(PENDING_PERSIST_KEY, pendingFailure8394)
                        inc("CORTEX_PENDING_RECOVERY_INCOMPLETE_8394")
                    }
                // V5.0.8324 §RESTORE_WITHOUT_RESET — a boot that read a far smaller book than one kept aside restores from it.
                try { afterLoad8324(raw8324) } catch (_: Throwable) { inc("CORTEX_RESTORE_FAILED_8324") }
            } catch (e: Throwable) {
                // V5.0.8324 — out of memory (or any VM error) while decoding is not a corrupt book: stay unloaded and retry.
                // 8086 quarantined it and let the next save replace the stored book with what had been decoded so far.
                if (e is VirtualMachineError) { loaded = false; inc("CORTEX_LOAD_VM_ERROR_RETRY_8324") }
                else LearningPersistence.decodeFailed8086(PERSIST_KEY, e)
            } finally {
                // V5.0.8086 — the row is on disk but would not read: stay unloaded (persist() is gated) and retry.
                if (LearningPersistence.readFailed8086(PERSIST_KEY)) loaded = false
                if (!loaded) loadRetryAtMs8325 = System.currentTimeMillis() + LOAD_RETRY_MS_8325
            }
        }
    }

    // ── V5.0.8324 §RESTORE_WITHOUT_RESET — the voter seats, calibration and books survive a lost boot ──
    //
    // 5.0.8321: "seats=3986 seated=2 · vote cleaning seats=5203 seasoned=156", calibration "slope=-" — on 8319 the same
    // store held seasoned=1860 / seated=88 with a slope per lane. 3,986 lane seats and 156 seasoned after 19 minutes is exactly
    // what an EMPTY start grades into, and calibration only learns from a seated deviation, so it stayed empty: the boot did
    // not read the stored book (nothing in 8320-8322 changed the key, the encoding or the seat keys). Three ways a boot loses
    // it: a boot-replayed close committed the unread (empty) books ([durableCloseCommit8198] had no loaded gate); a decode
    // that ran out of memory was quarantined and the partial state saved over it; or the row was missing. The first two are
    // closed above. Here: a healthy book is kept aside once per boot, and a boot that reads far fewer seasoned seats than
    // the kept book (or the quarantined copy) takes back every seat, lane prior, calibration fit and bucket book it knows
    // from more evidence — never a reset, never newer grades thrown away for older ones of the same seat.
    private const val BOOT_BACKUP_KEY_8324 = "CORTEX_7885_V8076_BOOT_8324"
    /** Seasoned lane seats a book needs before it is kept aside or restored from. */
    const val RESTORE_MIN_SEASONED_8324 = 200
    @Volatile private var bootSeasoned8324 = -1
    @Volatile private var restoreNote8324 = "-"

    /** Pure: lane seats (no regime / all-lane cell) with [CortexLedger7885.MIN_SCORED]+ out-of-sample scores. */
    fun seasoned8324(l: CortexLedger7885): Int = l.seats.entries.count {
        !it.key.contains('@') && !it.key.endsWith("|${CortexLedger7885.GLOBAL}") && it.value.scored >= CortexLedger7885.MIN_SCORED
    }

    /** Pure: should a boot that read [loaded] seasoned seats restore from a kept book of [kept]? */
    fun restoreDue8324(loaded: Int, kept: Int): Boolean = kept >= RESTORE_MIN_SEASONED_8324 && loaded * 2 < kept

    /** Pure: the kept-aside row "<seasoned>\n<json>" -> (seasoned, json); null when unreadable. */
    fun parseBackup8324(row: String?): Pair<Int, String>? {
        if (row.isNullOrBlank()) return null
        val n = row.substringBefore('\n', "").trim().toIntOrNull() ?: return null
        val json = row.substringAfter('\n', "")
        return if (json.isBlank()) null else n to json
    }

    /**
     * Pure: merge [from] into [into] — a seat / lane prior is taken when the stored one has more scores / labels; voter pairs
     * are added where absent. Returns the seats taken.
     */
    fun mergeLedger8324(into: CortexLedger7885, from: CortexLedger7885): Int {
        var n = 0
        for ((k, st) in from.seats) {
            val cur = into.seats[k]
            if (cur == null || cur.scored < st.scored) { into.seats[k] = st; n++ }
        }
        for ((k, st) in from.lanes) { val cur = into.lanes[k]; if (cur == null || cur.n < st.n) into.lanes[k] = st }
        for ((k, v) in from.pairs) into.pairs.putIfAbsent(k, v)
        return n
    }

    /** Caller holds the lock (ensureLoaded). */
    private fun afterLoad8324(mainRaw: String?) {
        val now = seasoned8324(ledger)
        bootSeasoned8324 = now
        val kept = parseBackup8324(LearningPersistence.load(BOOT_BACKUP_KEY_8324))
        val keptN = kept?.first ?: 0
        // Keep this boot's book aside when it is healthy and at least as seasoned as the one already kept.
        if (mainRaw != null && now >= RESTORE_MIN_SEASONED_8324 && now >= keptN) {
            LearningPersistence.save(BOOT_BACKUP_KEY_8324, "$now\n$mainRaw")
            restoreNote8324 = "kept=$now"
            return
        }
        val sources = ArrayList<Pair<String, String>>(2)
        if (kept != null && restoreDue8324(now, keptN)) sources.add("BOOT_KEPT" to kept.second)
        if (now < RESTORE_MIN_SEASONED_8324) LearningPersistence.loadQuarantined8324(PERSIST_KEY)?.let { sources.add("QUARANTINE" to it) }
        for ((src, json) in sources) {
            val o = try { org.json.JSONObject(json) } catch (_: Throwable) { null } ?: continue
            val l = CortexLedger7885()
            o.optJSONObject("ledger")?.let { l.decode(it) }
            val n = seasoned8324(l)
            if (!restoreDue8324(seasoned8324(ledger), n)) continue
            val seats = mergeLedger8324(ledger, l)
            val cal = CortexCalibration7901().also { c -> o.optJSONObject("calibration")?.let { c.decode(it) } }
            val lanesCal = calibration.mergeFrom8324(cal)
            val b = CortexScoreboard7885().also { x -> o.optJSONObject("board")?.let { x.decode(it) } }
            for ((k, book) in b.books) {
                val cur = board.books[k]
                if (cur == null || cur.byBucket.sumOf { it.n } < book.byBucket.sumOf { it.n }) board.books[k] = book
            }
            for ((k, v) in b.realized) board.realized.putIfAbsent(k, v)
            o.optJSONObject("vetoes")?.let { j -> for (k in j.keys()) if (!vetoBook.containsKey(k)) vetoBook[k] = CortexLedger7885.labelStat8187().also { it.decode(j.optString(k)) } }
            o.optJSONObject("joint7961")?.let { j -> for (k in j.keys()) if (!jointBook7961.containsKey(k)) jointBook7961[k] = CortexLedger7885.labelStat8187().also { it.decode(j.optString(k)) } }
            restoreJointGenerations8372(o)
            // Outstanding revision weights were stamped on the books just replaced: revisions now go to the grade records only.
            graded8006.clear()
            val staleTraceCount8372 = traceArchive8372.clearRevisionCoordinates8372()
            if (staleTraceCount8372 > 0) counters.computeIfAbsent("TRACE_COORDINATES_RETIRED_AFTER_BOOK_RESTORE_8372") { AtomicLong(0) }
                .addAndGet(staleTraceCount8372.toLong())
            restoreNote8324 = "restored=$src seats=$seats calLanes=$lanesCal from=$n was=$now"
            inc("CORTEX_RESTORED_8324_$src")
            bootSeasoned8324 = seasoned8324(ledger)
            return
        }
        restoreNote8324 = "boot=$now kept=$keptN"
    }

    /** V5.0.7930 — BotService.onDestroy: save now (graded state between periodic saves was lost on restart). */
    // ── V5.0.8001 — the Cortex's voter seats through the hive (HiveEdge8000) ──

    /** This instance's seasoned seats: "S|voter|lane" = [scored, sseModel, sseBase, brierModel, brierBase, bins]
     *  and "B|voter|lane|bin" = [n, sum, sumSq, wins, runners, 0]. */
    fun hiveSnapshot8001(): Map<String, DoubleArray> = synchronized(this) {
        val out = HashMap<String, DoubleArray>()
        for ((id, st) in ledger.seats) {
            if (st.scored < 30) continue
            // V5.0.8291 — a seat seeded from the hive is not echoed back as this install's own until it has 30 local scores.
            if (!hiveExportable8291(st.scored, inheritedSeed8291[id])) continue
            out["S|$id"] = doubleArrayOf(st.scored.toDouble(), st.sseModel, st.sseBase, st.brierModel, st.brierBase, st.bins.size.toDouble())
            for (i in st.bins.indices) {
                val b = st.bins[i]
                if (b.n >= 1.0) out["B|$id|$i"] = doubleArrayOf(b.n, b.sum, b.sumSq, b.wins, b.runners, 0.0)
            }
            if (out.size > 6_000) break
        }
        out
    }

    // ── V5.0.8291 §HIVE_SEATS_ARE_A_PRIOR ──
    /** An inherited seat is seeded with at most this many scores (half of CortexLedger7885.MIN_SCORED). */
    private const val HIVE_SEAT_SEED_8291 = 30.0
    /** voterId|lane -> the scores this session seeded from the hive (echo guard). */
    private val inheritedSeed8291 = ConcurrentHashMap<String, Int>()

    /** Pure (V5.0.8291): the factor that scales [networkN] hive scores down to [HIVE_SEAT_SEED_8291] (1 when under it). */
    fun hiveSeatScale8291(networkN: Double): Double =
        if (!(networkN > 0.0) || !networkN.isFinite()) 0.0 else minOf(1.0, HIVE_SEAT_SEED_8291 / networkN)

    /** Pure (V5.0.8291): may a seat of [scored] scores, [seeded] of them inherited this session (null: none), be exported? */
    fun hiveExportable8291(scored: Int, seeded: Int?): Boolean = seeded == null || scored - seeded >= 30

    /**
     * V5.0.8001/8076 — an empty local seat can inherit a valid network snapshot.
     * Local observations are never overwritten while their progressive labels are outstanding.
     */
    fun hiveInherit8001(): Int {
        ensureLoaded()
        var n = 0
        val hive = com.lifecyclebot.engine.truth.HiveEdge8000
        synchronized(this) {
            for (hk in hive.netKeys8000("CX|S|")) {
                val id = hk.removePrefix("CX|S|")
                val net = hive.net8000(hk) ?: continue
                val parts = id.split('|')
                if (parts.size < 2) continue
                val bins = (CortexVoters7885.edgesFor(parts[0]) ?: continue).size + 1
                if (!CortexLedger7885.validHiveSeat8076(net, bins)) { inc("HIVE_SCORE_INVALID_8076"); continue }
                val importedBins = (0 until bins).map { hive.net8000("CX|B|$id|$it") }
                if (importedBins.all { it == null } || importedBins.any { it != null && !CortexLedger7885.validHiveBin8076(it) }) {
                    inc("HIVE_BIN_INVALID_8076"); continue
                }
                val st = ledger.seat(parts[0], parts.drop(1).joinToString("|"), bins)
                // Seed an empty seat only. Replacing a local book invalidates outstanding label contributions.
                if (st.scored > 0 || st.bins.any { it.n > 0.0 } || st.bins.size != bins) continue
                // V5.0.8291 — the seed is the network's evidence scaled down to [HIVE_SEAT_SEED_8291] scores (its skill ratios, bin
                // means and rates kept): under MIN_SCORED, so an inherited seat holds no authority until this install has graded
                // it itself, and local grades outweigh the foreign ones from the first. It used to arrive at full network weight
                // (5.0.8286: seatsInherited=1354) — instantly "seasoned" on other builds' labels — and was re-exported as local.
                val ks = hiveSeatScale8291(net[0])
                val kb = hiveSeatScale8291(importedBins.sumOf { it?.getOrNull(0) ?: 0.0 })
                st.scored = (net[0] * ks).toInt(); st.sseModel = net[1] * ks; st.sseBase = net[2] * ks; st.brierModel = net[3] * ks; st.brierBase = net[4] * ks
                for (i in st.bins.indices) importedBins[i]?.let { b ->
                    st.bins[i].n = b[0] * kb; st.bins[i].sum = b[1] * kb; st.bins[i].sumSq = b[2] * kb; st.bins[i].wins = b[3] * kb; st.bins[i].runners = b[4] * kb
                }
                inheritedSeed8291[id] = st.scored
                n++
            }
        }
        if (n > 0) try { inc("SEATS_INHERITED_FROM_HIVE_8001") } catch (_: Throwable) {}
        return n
    }

    fun persistNow7930() {
        if (!loaded) return
        // V5.0.8094 — BotService.onDestroy calls this on the MAIN thread; the encode of the whole Cortex graph ran there
        // (8093 watchdog: CortexLedger7885\$Stat.encode / BotService.onDestroy among the ANR call sites; heap 511/512 MB).
        // The save runs on the persistence thread; a main-thread caller waits at most FLUSH_WAIT_MS_8094 for it.
        val work = Runnable {
            lastPendingPersistMs = System.currentTimeMillis()
            persistBlocking8094()
            try { LanePlaybook7907.persistNow7930() } catch (_: Throwable) {}
            try { CortexExit7897.persistNow7930() } catch (_: Throwable) {}
            try { com.lifecyclebot.engine.ExitProfile7955.persistNow7955() } catch (_: Throwable) {} // V5.0.7955
            try { CortexTiming7900.persistNow7930() } catch (_: Throwable) {}
        }
        if (onMainThread8094()) {
            try { persistPool8094.submit(work).get(FLUSH_WAIT_MS_8094, java.util.concurrent.TimeUnit.MILLISECONDS) }
            catch (_: Throwable) { try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CORTEX_FLUSH_CONTINUES_IN_BACKGROUND_8094") } catch (_: Throwable) {} }
        } else work.run()
    }

    // V5.0.8327 — 1.5 s -> 250 ms: 8323 stack samples waited 1.0-1.7 s here on the main thread (BotService.onDestroy). The save
    // itself runs to completion on the persistence thread either way.
    private const val FLUSH_WAIT_MS_8094 = 250L
    private fun onMainThread8094(): Boolean = Thread.currentThread().name == "main"
    private val persistPool8094 = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "cortex-persist-8094").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val persistQueued8094 = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * V5.0.8094 — every periodic / checkpoint save is queued on the one persistence thread, coalesced (at most one
     * queued): grading, assessing and UI threads never encode the graph themselves. The queued flag clears when the
     * save STARTS, so a change made during an encode schedules one more save.
     */
    private fun persist() {
        if (!loaded) return
        if (!persistQueued8094.compareAndSet(false, true)) return
        try {
            persistPool8094.execute { persistQueued8094.set(false); persistBlocking8094() }
        } catch (_: Throwable) { persistQueued8094.set(false) }
    }

    /**
     * V5.0.8325 §NO_IO_UNDER_THE_CORTEX_LOCK — saves are serialised on [persistLock8325], never on the Cortex monitor: 8094's
     * @Synchronized held the monitor through the SQLite writes (multi-MB rows), and every assessment queued behind them.
     * The books are still encoded under the Cortex lock (a consistent snapshot); the writes happen outside it.
     */
    private val persistLock8325 = Any()

    private fun persistBlocking8094() { synchronized(persistLock8325) {
        if (!loaded) return
        // 8198 — never write a snapshot that separates the graded state from its receipt: while a close grade
        // awaits its durable commit, the close commit itself carries the state (see durableCloseCommit8198).
        if (uncommittedCloseGrades8198.isNotEmpty()) return
        try {
            // V5.0.8325 — one consistent snapshot under the Cortex lock (re-checked: no close grade awaiting its receipt), written
            // after it is released; [durableCloseCommit8198] takes [persistLock8325] too, so an older snapshot is never written
            // after a newer close commit.
            val encode = snapshotEncoder8386(periodic = true) ?: return
            val blobs = encode()
            val dbAt = System.nanoTime()
            if (!LearningPersistence.saveTogetherVerified8386(blobs)) inc("CHECKPOINT_RETRY_8386")
            snapshotDbMs8386 = (System.nanoTime() - dbAt) / 1_000_000

        } catch (_: Throwable) {}
    } }

    /** V5.0.8325 — the other components' status lines, read outside the Cortex lock (each takes its own locks). */
    private fun statusExternals8325(): Map<String, String> = mapOf(
        "invariants" to (try { CortexInvariants7911.statusLine() } catch (_: Throwable) { "unavailable" }),
        "tape" to (try { com.lifecyclebot.engine.market.LaunchTape7921.statusLine() } catch (_: Throwable) { "unavailable" }),
        "playbook" to (try { LanePlaybook7907.statusLine() } catch (_: Throwable) { "unavailable" }),
        "timing" to (try { CortexTiming7900.statusLine() } catch (_: Throwable) { "unavailable" }),
        "exit" to (try { CortexExit7897.statusLine() } catch (_: Throwable) { "unavailable" }),
        "stop" to (try { StopAuthority7887.statusLine() } catch (_: Throwable) { "unavailable" }),
    )

    // ── scoreboard (v1 §2.10) ──

    /**
     * V5.0.7919 — the in-app scoreboard card (a section of its own on the
     * Pipeline screen, kept under the 2,000-char section render cap): per lane,
     * what the Cortex has learned and which powers it holds; what it has done.
     */
    fun scoreboardCard(): String {
        ensureLoaded()
        return try {
            synchronized(this) {
                val seated = ledger.seats.entries.count { !it.key.contains('@') && !it.key.endsWith("|${CortexLedger7885.GLOBAL}") && it.value.authority() > 0.0 }
                val sb = StringBuilder()
                sb.append("  assessed=${assessed.get()} graded=${graded.get()} seated=$seated pending=${pending.size}\n")
                sb.append("  ${cardLine8187()}\n")  // V5.0.8187
                sb.append("  lane      graded  strong    refuse    legacySlope powers   (current frozen score v1; executable net %)\n")
                board.books.entries.sortedByDescending { it.value.byBucket.sumOf { b -> b.n } }.take(8).forEach { (lane, b) ->
                    val runner = isRunner(lane)
                    // V5.0.7955 — graduated authority: O from auth >= 0.5, size-up scaled by auth.
                    // V5.0.8153 — the authority actually held (realised + walk-forward caps), not the bare label fraction.
                    val auth7955 = heldFraction8153(lane)
                    val row8153 = LearningEntryTruth8153.verdictRow8153(gradeBook8144, lane, scoreVersion = 1)
                    val powers = buildString {
                        if (refusalPowerCurrent8372(lane, runner, true)) append("Rp ")
                        if (refusalPowerCurrent8372(lane, runner, false)) append("Rl ")
                        if (auth7955 >= OVERRULE_FRACTION_7955) append("O ")
                        if (auth7955 > 0.0) append("auth=${"%.2f".format(auth7955)} ")
                        if (calibration.suspends8372(lane, MIN_CONSISTENT_SLOPE, executableOffset8187(lane))) append("SUSPENDED ")
                        if (invertedCurrent8372(lane)) append("INVERTED")
                    }.trim().ifBlank { "shadow" }
                    val currentGraded = row8153.strongN + row8153.neutralN + row8153.refuseN
                    sb.append("  ${lane.take(9).padEnd(9)} ${currentGraded.toString().padStart(6)}  " +
                        "${fmtRow8153(row8153.strongN, row8153.strongMean).padEnd(9)} ${fmtRow8153(row8153.refuseN, row8153.refuseMean).padEnd(9)} ${"%.2f".format(calibration.slope(lane))}  $powers\n")
                }
                fun sum(prefix: String) = counters.entries.filter { it.key.startsWith(prefix) }.sumOf { it.value.get() }
                sb.append("  did: refusedPaper=${sum("REFUSED_PAPER")} refusedLive=${sum("REFUSED_LIVE")} overruled=${sum("OVERRULED_LIVE")} " +
                    "paperChosen=${sum("PAPER_CHOSEN_")} sizedUp=${sum("SIZED_UP_")} stackOverruled=${sum("STACK_SHRINK_OVERRULED_")} partialAuth=${sum("PARTIAL_AUTHORITY_")}\n")
                sb.append("  shadow: refuse=${sum("SHADOW_REFUSE")} overrule=${sum("SHADOW_OVERRULE")} choice=${sum("SHADOW_PAPER_CHOICE")} size=${sum("SHADOW_SIZE_UP")}\n")
                sb.append("  realised: " + board.realized.entries.sortedBy { it.key }.take(6).joinToString(" · ") { (k, arr) ->
                    "$k ${fmtStat(arr[2])}/${fmtStat(arr[1])}/${fmtStat(arr[0])}"
                }.ifBlank { "none yet" } + "  (strong/neutral/refuse)\n")
                sb.append("  legacyRealisedGate8136 (diagnostic only): denied=${sum("AUTHORITY_DENIED_REALISED_NEGATIVE_8136")} capped=${sum("AUTHORITY_CAPPED_REALISED_UNPROVEN_8136")}\n")
                sb.append("  legacyPromotion8144 (diagnostic only): " + board.books.entries.sortedByDescending { it.value.byBucket.sumOf { b -> b.n } }.take(4)
                    .joinToString(" · ") { (lane, _) -> "$lane ${walkForward8144(lane, null).verdict.name}" } +
                    " · labels repeat=${sum("LABEL_HYGIENE_REPEAT")} unexec=${sum("LABEL_HYGIENE_UNEXECUTABLE")} unresolved=${sum("LABEL_UNRESOLVED_8144")}\n")
                sb.append("  truth8153: lateFirstPrice=${sum("LABEL_HYGIENE_LATE_FIRST_PRICE_8153")} observedOnlyRevisions=${sum("REVISION_OBSERVED_ONLY_8153")} " +
                    "unboundFiled=${sum("REALIZED_UNBOUND_FILED_8153")} (current frozen v1 cohorts; historical predictions retained separately, never relabeled)\n")
                sb.append("  ${CortexInversion8310.statusLine8310()}\n")  // V5.0.8310
                sb.append("  key: Rp/Rl refuse paper/live · O overrule (auth>=0.5) · auth current STRONG-vs-rest OOS proof · SUSPENDED calibration · INVERTED strong < neutral/refuse")
                sb.toString().take(1_900)
            }
        } catch (t: Throwable) { "  unavailable: ${t.javaClass.simpleName}" }
    }

    // ── V5.0.8069 — the Cortex panel (Learning screen): the same numbers as the scoreboard card, as data ──

    data class UiLane8069(
        val lane: String, val graded: Int,
        val strongN: Int, val strongMean: Double, val neutralN: Int, val neutralMean: Double,
        val refuseN: Int, val refuseMean: Double, val slope: Double, val auth: Double, val powers: String,
        /** V5.0.8153 — the decayed bucket books' means (pre-8146 labels included): display context only, never the verdict stats. */
        val legacyStrongMean8153: Double = Double.NaN, val legacyRefuseMean8153: Double = Double.NaN,
        val source8153: String = "",
        val strongMedian8165: Double = Double.NaN, val neutralMedian8165: Double = Double.NaN, val refuseMedian8165: Double = Double.NaN,
        val strongGrossMedian8198: Double = Double.NaN, val neutralGrossMedian8198: Double = Double.NaN, val refuseGrossMedian8198: Double = Double.NaN,
        /** 8198 — the lane's executable offset (points subtracted from every net read), shown so the net figures are legible. */
        val execOffset8198: Double = 0.0,
        /** 8269 — the original AATE provisional TAKE/REFUSE decision, not the Cortex bucket. */
        val aateTakeN8269: Int = 0, val aateRefuseN8269: Int = 0,
        val aateTakeGrossMedian8269: Double = Double.NaN, val aateRefuseGrossMedian8269: Double = Double.NaN,
        val aateTakeNetMedian8269: Double = Double.NaN, val aateRefuseNetMedian8269: Double = Double.NaN,
        val aateTakeNetMean8269: Double = Double.NaN, val aateRefuseNetMean8269: Double = Double.NaN,
        /** V5.0.8324 — the lane's ranking check (rank IC, top/bottom fifth, verdict); blank under 10 graded records. */
        val inversion8324: String = "",
    )
    data class UiSeat8069(val key: String, val authority: Double, val skillPct: Double, val n: Int)
    data class UiVeto8069(val reason: String, val n: Int, val meanPct: Double, val sePct: Double)
    data class Ui8069(
        val assessed: Long, val graded: Long, val seated: Int, val pending: Int,
        val lanes: List<UiLane8069>, val seats: List<UiSeat8069>, val vetoes: List<UiVeto8069>,
    )

    fun ui8069(): Ui8069? = try {
        ensureLoaded()
        val ranks8325 = rankings8325()  // V5.0.8325 — ranked outside the lock (cached)
        synchronized(this) {
            val seatList = ledger.seats.entries
                .filter { !it.key.contains('@') && !it.key.endsWith("|${CortexLedger7885.GLOBAL}") }
                .map { (k, s) -> UiSeat8069(k, s.authority(), s.skill() * 100.0, s.scored) }
            val lanes = board.books.entries.sortedByDescending { it.value.byBucket.sumOf { b -> b.n } }.take(12).map { (lane, b) ->
                val runner = isRunner(lane)
                val auth = heldFraction8153(lane)  // V5.0.8153 — the authority actually held
                val powers = buildString {
                    if (refusalPowerCurrent8372(lane, runner, true)) append("refuse-paper ")
                    if (refusalPowerCurrent8372(lane, runner, false)) append("refuse-live ")
                    if (auth >= OVERRULE_FRACTION_7955) append("overrule ")
                    if (calibration.suspends8372(lane, MIN_CONSISTENT_SLOPE, executableOffset8187(lane))) append("suspended ")
                    if (invertedCurrent8372(lane)) append("inverted")
                }.trim().ifBlank { "shadow" }
                // V5.0.8153 — the verdict stats come from the tradeable grade records (executable, first of episode, on time);
                // the decayed bucket books still carry the pre-8146 repeat-inflated labels and are kept as legacy fields only.
                val v = LearningEntryTruth8153.verdictRow8153(gradeBook8144, lane, scoreVersion = 1)
                val d8269 = LearningEntryTruth8153.decisionRow8269(gradeBook8144, lane, scoreVersion = 1)
                UiLane8069(lane, v.strongN + v.neutralN + v.refuseN, v.strongN, v.strongMean, v.neutralN, v.neutralMean,
                    v.refuseN, v.refuseMean, calibration.slope(lane), auth, powers,
                    legacyStrongMean8153 = b.byBucket[2].mean(), legacyRefuseMean8153 = b.byBucket[0].mean(), source8153 = "CURRENT_FROZEN_V1_8372; legacy separately retained",
                    strongMedian8165 = v.strongMedian, neutralMedian8165 = v.neutralMedian, refuseMedian8165 = v.refuseMedian,
                    strongGrossMedian8198 = v.strongGrossMedian8198, neutralGrossMedian8198 = v.neutralGrossMedian8198, refuseGrossMedian8198 = v.refuseGrossMedian8198,
                    execOffset8198 = execOffsetByLane8187[lane] ?: 0.0,
                    aateTakeN8269 = d8269.takeN, aateRefuseN8269 = d8269.refuseN,
                    aateTakeGrossMedian8269 = d8269.takeGrossMedian, aateRefuseGrossMedian8269 = d8269.refuseGrossMedian,
                    aateTakeNetMedian8269 = d8269.takeNetMedian, aateRefuseNetMedian8269 = d8269.refuseNetMedian,
                    aateTakeNetMean8269 = d8269.takeNetMean, aateRefuseNetMean8269 = d8269.refuseNetMean,
                    inversion8324 = "current ${authorityTest8366(lane).compact()}" +
                        (ranks8325[lane]?.short?.let { " · frozen-v1 rank: $it" } ?: ""))
            }
            val vetoes = vetoBook.entries.filter { it.value.n >= 5.0 }.sortedByDescending { it.value.n }.take(10).map { (k, st) ->
                UiVeto8069(k, st.n.toInt(), st.mean(), if (st.n > 1.0) kotlin.math.sqrt(st.variance() / st.n) else Double.NaN)
            }
            Ui8069(assessed.get(), graded.get(), seatList.count { it.authority > 0.0 }, pending.size, lanes,
                seatList.filter { it.n > 0 }.sortedByDescending { it.authority * 1000 + it.skillPct }.take(10), vetoes)
        }
    } catch (_: Throwable) { null }

    fun statusLine(): String {
        ensureLoaded()
        val credits = credits7888()
        // V5.0.8325 §LOCK_ONLY_FOR_SMALL_STATE — every other component's status line and the ranking (grade-book sorts) are
        // read BEFORE the Cortex lock: 8323 held the monitor through all of them (assessLockWait8324 max 121 s).
        val ext8325 = statusExternals8325()
        val inversion8325 = inversionLine8324()
        return synchronized(this) {
            val n = assessed.get()
            val avgMs = if (n > 0) assessNanos.get() / n / 1_000_000.0 else 0.0
            val seats: List<Triple<String, CortexLedger7885.Seat, Double>> = ledger.seats.entries
                .filter { !it.key.contains('@') && !it.key.endsWith("|${CortexLedger7885.GLOBAL}") }
                .map { (k, s) -> Triple(k, s, s.authority()) }
            val seated = seats.filter { it.third > 0.0 }.sortedByDescending { it.third }
            val bestByVoter = seats.groupBy { it.first.substringBefore('|') }
                .mapValues { (_, l) -> l.maxByOrNull { it.second.skill() } }
                .values.filterNotNull().sortedByDescending { it.second.skill() }
            val laneLines = board.books.entries.sortedByDescending { it.value.byBucket.sumOf { b -> b.n } }.take(8).joinToString("\n") { (lane, b) ->
                val runner = isRunner(lane)
                val v8153 = LearningEntryTruth8153.verdictRow8153(gradeBook8144, lane, scoreVersion = 1)
                "      $lane(5m${if (runner) ",runner" else ""}${if (invertedCurrent8372(lane)) ",INVERTED" else ""}): " +
                    "currentFrozenV1_8372[refuse=${fmtRow8153(v8153.refuseN, v8153.refuseMean)} " +
                    "neutral=${fmtRow8153(v8153.neutralN, v8153.neutralMean)} " +
                    "strong=${fmtRow8153(v8153.strongN, v8153.strongMean)} held=${"%.2f".format(heldFraction8153(lane))}] " +
                    "legacyDecayed[refuse=${fmtStat(b.byBucket[0])} neutral=${fmtStat(b.byBucket[1])} strong=${fmtStat(b.byBucket[2])}] " +
                    "| legacyAdmit=${fmtStat(b.legacyAdmitted)} legacyRefuse=${fmtStat(b.legacyRefused)} missedStrong=${fmtStat(b.missedStrong)} " +
                    "| authority: paperRefuse=${refusalPowerCurrent8372(lane, runner, true)} liveRefuse=${refusalPowerCurrent8372(lane, runner, false)} liveOverrule=${heldFraction8153(lane) >= OVERRULE_FRACTION_7955} auth=${"%.2f".format(heldFraction8153(lane))}"
            }
            "bar=${CortexScoreboard7885.BAR_VERSION} voters=${CortexVoters7885.votersLine8003()} assessed=$n (${"%.2f".format(avgMs)}ms) captureQ=${captureQueued8187.get()} ${captureOverflowStatsLocked8399()} pending=${pending.size} graded=${graded.get()} implausibleRefused8388=${gradeBook8144.implausibleRefused8388} " +
                "seats=${seats.size} seated=${seated.size}\n" +
                "      vote cleaning (§8007): ${synchronized(this) { ledger.cleanLine8007() }}\n" +
                "      store (§8324 restore without reset): bootSeasoned=$bootSeasoned8324 calibrationLanes=${calibration.laneCount8324()} $restoreNote8324 ${assessWaitLine8324()}\n" +
                "      invariants & provenance v11 (§7911): ${ext8325["invariants"]}\n" +
                "      compute (§2.9 v1, 7909): pool active=${pool.activeCount} queued=${pool.queue.size} captureActive8171=${capturePool8171.activeCount} captureQueued8171=${capturePool8171.queue.size} done=${pool.completedTaskCount} slowVoters=${CortexVoters7885.slowLine()} " +
                "${CortexVoters7885.memoLine8169()} ${CortexAudit8169.captureLine()} ${assessLatencyLine8187()} ${CortexVoters7885.budgetLine8187()}\n" +
                "      data economy (§B.6): creditsToday=${"%.0f".format(credits)} perAssessedDecision=${if (n > 0) "%.1f".format(credits / n) else "-"} perGradedDecision=${if (graded.get() > 0) "%.1f".format(credits / graded.get()) else "-"} " +
                "(day credits / session decisions) ${economyLine8187()}\n" +
                "      graduation (§8187): ${bindingLine8187()} authority=${blockLine8187()} ${synchronized(this) { authorityLine8366() }}\n" +
                "      executable labels (§8187, authority reads label - lane gap): ${execLine8187()}\n" +
                "      outcome truth v12 (§7912, whole-position closes): ${realLedgers.entries.joinToString(" · ") { (mode, l) ->
                    val seated = l.seats.entries.filter { !it.key.contains('@') && !it.key.endsWith("|${CortexLedger7885.GLOBAL}") && it.value.scored > 0 }
                    "$mode closes=${l.lanes.entries.filter { !it.key.contains('@') && it.key != CortexLedger7885.GLOBAL }.sumOf { it.value.n }.toInt()} " +
                        "best=[${seated.sortedByDescending { it.value.skill() }.take(4).joinToString(",") { "${it.key} ${"%+.1f".format(it.value.skill() * 100)}%/n${it.value.scored}" }}]"
                }.ifBlank { "no closes yet" }}\n" +
                "      launch tape v16 (§7921): ${ext8325["tape"]}\n" +
                "      lane playbooks (§7907): ${ext8325["playbook"]}\n" +
                "      calibration v7 (§7901): slope=${calibration.line()} ${calibration.isoLine8366()}\n" +
                "      timing cortex v6 (§7900): ${ext8325["timing"]}\n" +
                "      exit cortex v3 (§7897): ${ext8325["exit"]}\n" +
                "      stop authority (§7887): ${ext8325["stop"]}\n" +
                "      strong-cohort doors (§8201): offered=${counters["STRONG_COHORT_OFFERED_8200"]?.get() ?: 0L} " +
                    counters.entries.filter { it.key.startsWith("STRONG_COHORT_NULL_8201_") }.sortedByDescending { it.value.get() }
                        .joinToString(" ") { "${it.key.removePrefix("STRONG_COHORT_NULL_8201_")}=${it.value.get()}" }.ifBlank { "null=0" } + "\n" +
                "      actions: ${counters.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}\n" +
                "      seated (authority): ${seated.take(12).joinToString(" · ") { (k, s, a) -> "$k a=${"%.2f".format(a)} skill=${"%.1f".format(s.skill() * 100)}% n=${s.scored}" }.ifBlank { "none yet — a seat needs ${CortexLedger7885.MIN_SCORED} graded predictions beating the lane mean out-of-sample" }}\n" +
                "      best skill per voter (return R² · win-prob Brier · log-loss): ${bestByVoter.take(10).joinToString(" · ") { (k, s, _) -> "$k ${"%+.1f".format(s.skill() * 100)}%/${"%+.1f".format(s.brierSkill() * 100)}%/${"%+.1f".format(s.logLossSkill() * 100)}% n${s.scored}" }.ifBlank { "-" }}\n" +
                "      voter failures: ${voterFailures.entries.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "0" }}\n" +
                laneLines.ifBlank { "      lanes: no graded decisions yet" } + "\n" +
                "      verdict x setup (§7961): ${jointLine7961()}\n" +
                "      grade records (§8144 observed vs executable, walk-forward): ${gradeLine8144()}\n" +
                "      ranking (§8324 does the Cortex rank-order forward returns?): ${inversion8325}\n" +
                "      veto audit (refused candidates' forward return): " +
                vetoBook.entries.filter { it.value.n >= 5.0 }.sortedByDescending { it.value.n }.take(12).joinToString(" · ") { (r, st) ->
                    val se = if (st.n > 1.0) kotlin.math.sqrt(st.variance() / st.n) else Double.POSITIVE_INFINITY
                    val flag = if (st.n >= 40.0 && st.mean() - se > 2.0 && vetoRobustProof8185(st)) " REFUSING_WINNERS"
                        else if (st.n >= 40.0 && st.mean() - se > 2.0) " OUTLIER_SUSPECT_8185"  // V5.0.8185 — winners only on implausible labels
                        else if (st.n >= 40.0 && st.suspect8187() && st.rawMean8187() > 2.0) " OUTLIER_SUSPECT_8185"  // V5.0.8187 — read robust, raw shown
                        else if (st.n >= 40.0 && st.mean() + se < -2.0) " ok" else ""
                    "$r ${fmtStat(st)}$flag"
                }.ifBlank { "none yet" } + "\n" +
                "      realised whole-position outcome by entry verdict: " +
                board.realized.entries.sortedBy { it.key }.joinToString(" · ") { (k, arr) ->
                    "$k refuse=${fmtStat(arr[0])} neutral=${fmtStat(arr[1])} strong=${fmtStat(arr[2])}"
                }.ifBlank { "none yet" }
        }
    }
}
