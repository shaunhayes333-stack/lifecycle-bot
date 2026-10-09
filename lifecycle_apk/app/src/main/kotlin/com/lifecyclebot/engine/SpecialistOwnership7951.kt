package com.lifecyclebot.engine

import com.lifecyclebot.engine.market.SpecialistCandidateBooks7803
import com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7951 — SPECIALIST OWNERSHIP: one common conviction scale, and a truthful
 * funnel for candidates that died before BUY_INTENT.
 *
 * Diag 5.0.7949 (146 s live): SHITCOIN residentReady=49, MOONSHOT 74, CORE 33,
 * QUALITY 14, EXPRESS 6 — all ownerSelected=0 buyIntent=0 INTENT_CHOKED, while
 * CASHGEN (6) and BLUECHIP (2) were the only owners. TRADE_AUTHORIZE_ENTERED_7003
 * read CASHGEN=156 TREASURY=3 BLUECHIP=3 and nothing else.
 *
 * Causal reading (code + counters):
 *  - OWNER_SELECTED and BUY_INTENT are stamped only inside TradeAuthorizer.authorize
 *    (BUY_INTENT on entry, OWNER_SELECTED on LaneExecutionCoordinator election) and
 *    in ExecutableOpenGate on a sealed intent. Every lane block in BotService calls
 *    FinalDecisionGate.evaluate FIRST and returns on a block, so any FDG refusal
 *    (PLAYBOOK_NO_TRIGGER_7907, SIZE_NOT_EXECUTABLE_7835, ...) ended the candidate
 *    with no funnel stamp at all: fdgAllow=0 fdgBlock=0 and the lane read
 *    INTENT_CHOKED. ownerSelected is a real gate, but it sits after the FDG.
 *  - Per-token, only the cycle primary (+ one bounded rescue) evaluates. The primary
 *    is the strongest desk hypothesis, whose conviction for native lanes was the RAW
 *    native score — only SHITCOIN was put on its own pass bar (7948). CASHGEN's raw
 *    76 out-ranked every lane whose brain scores on a lower bar (MOONSHOT 38/41).
 *  - TradeAuthorizer re-published READY with the raw V3 score/confidence blend,
 *    overwriting the lane-relative conviction the native READY carried.
 *  - A capital refusal (OrderSizeResolver6441 CAPITAL_BELOW_MIN_EXECUTABLE_6490,
 *    540 in 146 s) inside the FDG became SIZE_NOT_EXECUTABLE_7835 and dropped the
 *    candidate silently, before owner selection: no demand ever reached the funnel
 *    or capital rotation.
 *
 * This object is evidence and bookkeeping only. It never admits, sizes or executes;
 * every hard-safety, FDG and sizing gate still decides.
 */
object SpecialistOwnership7951 {
    private const val MIN_SAMPLES_7951 = 8
    private const val RING_7951 = 64
    private const val CHART_BUY_FLOOR_7951 = 80.0
    private const val CHART_BUY_LIFT_7951 = 15.0
    private const val DEMAND_WINDOW_MS_7951 = 120_000L
    private const val CAPITAL_NOTE_TTL_MS_7951 = 10_000L
    private val CAPITAL_REASONS_7951 = setOf(
        "CAPITAL_BELOW_MIN_EXECUTABLE_6490", "NO_WALLET", "PAPER_CASH_EMPTY_7819",
        "PAPER_CASH_INSUFFICIENT_WITH_FEE_6490",
    )

    private val eligibleRaw7951 = ConcurrentHashMap<String, ArrayDeque<Double>>()
    private data class CapitalNote7951(val reason: String, val atMs: Long)
    private val capitalNotes7951 = ConcurrentHashMap<String, CapitalNote7951>()
    private val demand7951 = ConcurrentHashMap<String, Long>()
    private val stampedCapital7951 = ConcurrentHashMap<String, Long>()
    private val preIntent7951 = ConcurrentHashMap<String, AtomicLong>()

    private fun lane7951(raw: String?): String = try {
        CanonicalLaneIdentity6506.canonical(raw.orEmpty().uppercase().replace("BLUE_CHIP", "BLUECHIP").replace("SHITCOIN_EXPRESS", "EXPRESS"))
    } catch (_: Throwable) { raw.orEmpty().trim().uppercase() }

    // ── common scale (pure) ─────────────────────────────────────────────────

    /** A value against a pass bar on 0..100: the bar reads 50, 100 reads 100. */
    fun barRelative7951(value: Double, bar: Double): Double {
        val v = value.coerceIn(0.0, 100.0)
        if (!bar.isFinite() || bar <= 0.0 || bar >= 100.0) return v
        return if (v >= bar) 50.0 + 50.0 * (v - bar) / (100.0 - bar) else 50.0 * v / bar
    }

    /**
     * Common-scale ownership conviction from the lane's OWN eligible population.
     * With a mature population: 50 + 50 x the percentile rank among what this lane
     * itself passes. Before that: relative to the weakest value this lane has passed
     * (its revealed bar). Lanes that score on different bars compare fairly.
     */
    fun commonScale7951(raw: Double, population: List<Double>): Double {
        val v = raw.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: 0.0
        if (population.size < MIN_SAMPLES_7951) {
            val bar = minOf(population.filter { it.isFinite() }.minOrNull() ?: v, v)
            return barRelative7951(v, bar).coerceIn(0.0, 100.0)
        }
        val below = population.count { it < v }
        val equal = population.count { it == v }
        val rank = (below + 0.5 * equal) / population.size
        return (50.0 + 50.0 * rank).coerceIn(50.0, 100.0)
    }

    /** A chart-reader BUY is strong ownership evidence: lift, floored at 80. */
    fun chartLifted7951(conviction: Double, chartBuy: Boolean): Double =
        if (!chartBuy) conviction else maxOf(CHART_BUY_FLOOR_7951, conviction + CHART_BUY_LIFT_7951).coerceAtMost(100.0)

    private fun observe7951(lane: String, raw: Double): List<Double> {
        val ring = eligibleRaw7951.computeIfAbsent(lane) { ArrayDeque() }
        synchronized(ring) {
            ring.addLast(raw)
            while (ring.size > RING_7951) ring.removeFirst()
            return ring.toList()
        }
    }

    private fun population7951(lane: String): List<Double> {
        val ring = eligibleRaw7951[lane] ?: return emptyList()
        synchronized(ring) { return ring.toList() }
    }

    private fun rawBlend7951(score: Int, confidence: Double): Double =
        score.coerceIn(0, 100) * 0.60 + confidence.coerceIn(0.0, 100.0) * 0.40

    private fun chartSaysBuy7951(mint: String): Boolean =
        try { com.lifecyclebot.engine.chart.ChartReader7950.saysBuy(mint) } catch (_: Throwable) { false }

    /**
     * Every eligible native opinion carries its ownership conviction on the common
     * scale (replaces raw maxOf(score, confidence) for every lane, not just SHITCOIN).
     */
    fun onCommonScale7951(
        mint: String,
        opinions: Map<String, SpecialistBrainBridge7542.Opinion>,
    ): Map<String, SpecialistBrainBridge7542.Opinion> {
        val chart = chartSaysBuy7951(mint)
        return opinions.mapValues { (lane, o) ->
            if (!o.eligible || !o.authoritative) o else {
                val raw = o.ownershipConviction7948?.takeIf { it.isFinite() }
                    ?: rawBlend7951(o.score, o.confidence.toDouble())
                val scaled = commonScale7951(raw, observe7951(lane7951(lane), raw))
                o.copy(ownershipConviction7948 = chartLifted7951(scaled, chart))
            }
        }
    }

    /** Pure: the READY conviction the authorizer publishes. */
    fun readyConviction7951(nativeReady: Double?, rawBlend: Double, population: List<Double>, chartBuy: Boolean): Double =
        chartLifted7951(nativeReady?.takeIf { it.isFinite() } ?: commonScale7951(rawBlend, population), chartBuy)

    /**
     * TradeAuthorizer READY: keep the native common-scale conviction already
     * published for this exact candidate version; otherwise place the authorizer's
     * own score on this lane's common scale. Never the raw blend again.
     */
    internal fun authorizerReadyConviction7951(lane: String, mint: String, candidateVersion: Long, score: Int, confidence: Double): Double {
        val l = lane7951(lane)
        val existing = try { SpecialistCandidateBooks7803.entry(l, mint) } catch (_: Throwable) { null }
        val nativeReady = existing?.takeIf {
            it.state == SpecialistCandidateBooks7803.State.READY &&
                (candidateVersion <= 0L || it.candidateVersion == candidateVersion)
        }?.conviction
        return readyConviction7951(nativeReady, rawBlend7951(score, confidence), population7951(l), chartSaysBuy7951(mint))
    }

    // ── capital demand ───────────────────────────────────────────────────────

    fun isCapitalRefusal7951(reason: String?): Boolean = reason != null && reason in CAPITAL_REASONS_7951

    /** OrderSizeResolver6441 refused for capital. Post-intent refusals are demand at once. */
    internal fun onCapitalRefusal7951(lane: String, mint: String, reason: String, postIntent: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank() || !isCapitalRefusal7951(reason)) return
        if (capitalNotes7951.size > 4_096) capitalNotes7951.entries.removeIf { nowMs - it.value.atMs > CAPITAL_NOTE_TTL_MS_7951 }
        capitalNotes7951[mint] = CapitalNote7951(reason, nowMs)
        if (postIntent) recordCapitalDemand7951(lane, mint, nowMs)
    }

    internal fun recordCapitalDemand7951(lane: String, mint: String, nowMs: Long = System.currentTimeMillis()) {
        val l = lane7951(lane)
        if (l.isBlank() || mint.isBlank()) return
        if (demand7951.size > 4_096) demand7951.entries.removeIf { nowMs - it.value > DEMAND_WINDOW_MS_7951 }
        demand7951["$l|$mint"] = nowMs
    }

    /**
     * Per lane: distinct ready / owner-selected candidates refused ONLY for capital
     * in the last ~2 minutes. Capital rotation and the capital report read this.
     */
    fun capitalDemand7951(nowMs: Long = System.currentTimeMillis()): Map<String, Int> {
        demand7951.entries.removeIf { nowMs - it.value > DEMAND_WINDOW_MS_7951 }
        return demand7951.keys.groupingBy { it.substringBefore('|') }.eachCount()
    }

    // ── FDG verdicts that ended a lane before BUY_INTENT ─────────────────────

    /**
     * Called once per fresh FDG verdict. A lane-scoped block is named per lane
     * (it never reached authorize, so the funnel had no stamp). A block that is
     * SIZE_NOT_EXECUTABLE_7835 with a fresh capital refusal on the same mint passed
     * every policy gate and was refused only for capital: it is recorded as
     * OWNER_SELECTED + BUY_INTENT + FDG_BLOCK with a named capital refusal.
     */
    internal fun onFdgVerdict7951(mint: String, lane: String, candidateVersion: Long, shouldTrade: Boolean, blockReason: String?, nowMs: Long = System.currentTimeMillis()) {
        if (shouldTrade || mint.isBlank()) return
        val l = lane7951(lane)
        if (l !in SpecialistCandidateBooks7803.LANES) return
        val reason = blockReason?.takeIf { it.isNotBlank() } ?: "FDG_NO_REASON"
        noteLaneRefused7960(mint, l, nowMs)
        val capital = capitalOnlyReason7951(reason, capitalNotes7951[mint]?.takeIf { nowMs - it.atMs <= CAPITAL_NOTE_TTL_MS_7951 }?.reason)
        if (capital == null) {
            notePreIntent7951(l, "FDG_$reason")
            return
        }
        recordCapitalDemand7951(l, mint, nowMs)
        notePreIntent7951(l, "CAPITAL_ONLY_$capital")
        val key = "$l|$mint|$candidateVersion"
        if (stampedCapital7951.size > 4_096) stampedCapital7951.entries.removeIf { nowMs - it.value > DEMAND_WINDOW_MS_7951 }
        if (stampedCapital7951.putIfAbsent(key, nowMs) == null) stampCapitalOwnership7951(l, mint, candidateVersion, capital)
    }

    /** Pure: the capital reason when an FDG block was refused only for capital. */
    fun capitalOnlyReason7951(fdgBlockReason: String?, freshCapitalReason: String?): String? =
        if (fdgBlockReason == "SIZE_NOT_EXECUTABLE_7835" && isCapitalRefusal7951(freshCapitalReason)) freshCapitalReason else null

    private fun stampCapitalOwnership7951(lane: String, mint: String, candidateVersion: Long, capitalReason: String) {
        try {
            val holder = try { LaneExecutionCoordinator.currentElection6600(mint)?.primaryLane } catch (_: Throwable) { null }
            if (candidateVersion > 0L && (holder.isNullOrBlank() || lane7951(holder) == lane)) {
                val eventId = "$mint:$candidateVersion"
                ToolkitSignalSheet.recordDeskStage(lane, "OWNER_SELECTED", eventId)
                ToolkitSignalSheet.recordDeskStage(lane, "BUY_INTENT", eventId)
                ToolkitSignalSheet.recordDeskStage(lane, "FDG_BLOCK", eventId)
            }
            ToolkitSignalSheet.recordPreSizeRefusal7809(lane, "CAPITAL_ONLY_$capitalReason")
            PipelineHealthCollector.labelInc("SPECIALIST_CAPITAL_ONLY_REFUSAL_7951_$lane")
        } catch (_: Throwable) {}
    }

    internal fun notePreIntent7951(lane: String, reason: String) {
        val l = lane7951(lane)
        val r = reason.substringBefore(':').trim().uppercase().replace(Regex("[^A-Z0-9_]"), "_").take(64)
        if (l !in SpecialistCandidateBooks7803.LANES || r.isBlank()) return
        if (preIntent7951.size > 2_000) preIntent7951.clear()
        preIntent7951.computeIfAbsent("$l|$r") { AtomicLong(0L) }.incrementAndGet()
    }

    /**
     * A lane whose resident book holds a READY proposal for this mint but which the
     * cycle did not let evaluate (not primary / rescue, owned by another lane, or a
     * pre-FDG specialist gate). Counted so the loss before FDG has a name.
     */
    internal fun noteReadyNotEvaluated7951(lane: String, mint: String, primaryLane: String) {
        val l = lane7951(lane)
        if (l !in SpecialistCandidateBooks7803.LANES) return
        val ready = try { SpecialistCandidateBooks7803.entry(l, mint)?.state == SpecialistCandidateBooks7803.State.READY } catch (_: Throwable) { false }
        if (!ready) return
        val p = lane7951(primaryLane)
        notePreIntent7951(l, if (p == l) "READY_PRIMARY_GATED" else "READY_NOT_EVALUATED_PRIMARY_$p")
    }

    /** "REASON=n,..." top four refusals that ended this lane before BUY_INTENT. */
    fun preIntentRefusals7951(lane: String): String {
        val prefix = lane7951(lane) + "|"
        return preIntent7951.entries.filter { it.key.startsWith(prefix) }
            .sortedByDescending { it.value.get() }
            .take(4)
            .joinToString(",") { "${it.key.removePrefix(prefix)}=${it.value.get()}" }
    }

    /** Pure: the status the liveness row shows. */
    fun shownStatus7951(baseStatus: String, buyerEnabled: Boolean, intent: Long, residentReady: Int, preIntentRefusals: String): String = when {
        !buyerEnabled -> "BUYER_DISABLED"
        intent == 0L && preIntentRefusals.isNotBlank() -> "REFUSED_BEFORE_INTENT_7951"
        intent == 0L && residentReady == 0 -> "OBSERVING_NO_READY_CANDIDATE"
        else -> baseStatus
    }

    internal fun resetForTests7951() {
        eligibleRaw7951.clear(); capitalNotes7951.clear(); demand7951.clear()
        stampedCapital7951.clear(); preIntent7951.clear(); refusedAt7960.clear()
    }

    // ── V5.0.7960 — a refused primary hands the candidate on ──
    //
    // 5.0.7958 live: only the cycle primary (+ one rescue) evaluates a token. CASHGEN won
    // primary on candidates its own Cortex read refuses (C3_PROVEN_NEGATIVE_EDGE, 811 blocks),
    // and BLUECHIP / MOONSHOT READY proposals on the same tokens were never evaluated
    // (READY_NOT_EVALUATED_PRIMARY_CASHGEN=58). Once the primary's FDG refuses this mint, the
    // other READY lanes of its desk may evaluate it for [REFUSED_HANDOFF_MS_7960]. Every
    // safety / FDG / sizing gate still decides each of them.
    private const val REFUSED_HANDOFF_MS_7960 = 90_000L
    private val refusedAt7960 = ConcurrentHashMap<String, Long>()

    internal fun noteLaneRefused7960(mint: String, lane: String, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        if (refusedAt7960.size > 4_096) refusedAt7960.entries.removeIf { nowMs - it.value > REFUSED_HANDOFF_MS_7960 }
        refusedAt7960["${lane7951(lane)}|$mint"] = nowMs
    }

    /** True when [primaryLane]'s FDG refused [mint] within the hand-off window. */
    internal fun primaryRefused7960(mint: String, primaryLane: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val at = refusedAt7960["${lane7951(primaryLane)}|$mint"] ?: return false
        return nowMs - at in 0..REFUSED_HANDOFF_MS_7960
    }

    /**
     * A non-primary lane may evaluate this token: its own desk proposal is READY and the
     * primary was refused. Counted (READY_HANDED_OFF_7960) so the hand-off shows.
     */
    internal fun handOffAllowed7960(lane: String, mint: String, primaryLane: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val l = lane7951(lane)
        if (l == lane7951(primaryLane) || l !in SpecialistCandidateBooks7803.LANES) return false
        if (!primaryRefused7960(mint, primaryLane, nowMs)) return false
        val ready = try { SpecialistCandidateBooks7803.entry(l, mint)?.state == SpecialistCandidateBooks7803.State.READY } catch (_: Throwable) { false }
        if (ready) notePreIntent7951(l, "READY_HANDED_OFF_FROM_${lane7951(primaryLane)}_7960")
        return ready
    }
}
