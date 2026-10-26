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
        // V5.0.8316 — a route-minimum promotion refused AT SIZE on a 0.29 SOL wallet (8314: QUALITY / BLUECHIP / CORE /
        // CYCLIC / TREASURY qualified hundreds, opened none — refused as SIZE_NOT_EXECUTABLE_7835 with no funnel stamp).
        // The candidate passed every policy gate; the funnel shows "owner selected, refused at size".
        "ROUTE_MIN_PROMOTION_REFUSED_UNMEASURED_EDGE_8188", "ROUTE_MIN_PROMOTION_REFUSED_LOSS_BUDGET_8188",
        "ORDER_SIZE_CONVICTION_REFUSED_MIN_PROMOTION_6909", "SMART_SIZER_V3_SUB_ROUTABLE_REFUSED_7831",
    )

    /** V5.0.8316 — the capital / size-only refusal reasons (read-only, for the funnel report and tests). */
    fun capitalReasons8316(): Set<String> = CAPITAL_REASONS_7951

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
        // superseded by V5.0.8364 — the revealed-bar read put a young lane's FIRST eligible value at 50 while a mature lane's
        // median reads 75, so rarely-eligible lanes (QUALITY / BLUECHIP) lost every cross-lane comparison until they had
        // 8 samples. Before maturity the value is ranked inside the lane's own observed range (a lone value = the median).
        if (population.size < MIN_SAMPLES_7951) return immatureScale8364(v, population)
        val below = population.count { it < v }
        val equal = population.count { it == v }
        val rank = (below + 0.5 * equal) / population.size
        return (50.0 + 50.0 * rank).coerceIn(50.0, 100.0)
    }

    /**
     * Pure (V5.0.8364): the common scale before a lane's eligible population matures — 50 + 50 x the value's position in
     * the lane's own observed [min, max] (a lone value, or no spread, reads 75: the centre of the mature 50..100 scale).
     */
    fun immatureScale8364(value: Double, population: List<Double>): Double {
        val v = value.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: 0.0
        val finite = population.filter { it.isFinite() }
        val lo = minOf(finite.minOrNull() ?: v, v)
        val hi = maxOf(finite.maxOrNull() ?: v, v)
        val rank = if (hi - lo < 1e-9) 0.5 else (v - lo) / (hi - lo)
        return (50.0 + 50.0 * rank).coerceIn(50.0, 100.0)
    }

    /** A chart-reader BUY is strong ownership evidence: lift, floored at 80. */
    fun chartLifted7951(conviction: Double, chartBuy: Boolean): Double =
        if (!chartBuy) conviction else maxOf(CHART_BUY_FLOOR_7951, conviction + CHART_BUY_LIFT_7951).coerceAtMost(100.0)

    // V5.0.8364 — one sample per distinct mint. observe ran once per native snapshot (every ~2.5 s per mint), so a lane
    // re-reading one hot mint filled its 64-slot ring with copies of it and every other candidate's percentile was ranked
    // against that one coin. Insertion order = recency; a mint re-observed replaces its own sample.
    private val eligibleByMint8364 = ConcurrentHashMap<String, LinkedHashMap<String, Double>>()

    private fun observe7951(lane: String, raw: Double, mint: String): List<Double> {
        if (mint.isNotBlank()) {
            val m = eligibleByMint8364.computeIfAbsent(lane) { LinkedHashMap() }
            synchronized(m) {
                m.remove(mint)
                m[mint] = raw
                while (m.size > RING_7951) m.remove(m.keys.first())
                return m.values.toList()
            }
        }
        val ring = eligibleRaw7951.computeIfAbsent(lane) { ArrayDeque() }
        synchronized(ring) {
            ring.addLast(raw)
            while (ring.size > RING_7951) ring.removeFirst()
            return ring.toList()
        }
    }

    private fun population7951(lane: String): List<Double> {
        eligibleByMint8364[lane]?.let { m -> synchronized(m) { if (m.isNotEmpty()) return m.values.toList() } }
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
                val scaled = commonScale7951(raw, observe7951(lane7951(lane), raw, mint))
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
        // V5.0.8136 — every FDG verdict (allow or refuse) terminates its lane's proposal in the candidate's election.
        noteFdgVerdict8136(mint, lane, candidateVersion, shouldTrade, blockReason, nowMs)
        try { HandoffReceiverTasks8153.onFdgVerdict8153(mint, lane7951(lane), shouldTrade, blockReason, nowMs, candidateVersion) } catch (_: Throwable) {}  // V5.0.8153 — the receiver's task is evaluated only on its candidate generation
        try { com.lifecyclebot.engine.truth.AdmissionHandoff8144.onFdgVerdict8144(mint, lane, shouldTrade, blockReason, nowMs) } catch (_: Throwable) {}  // V5.0.8144 — hand-off arrival
        OwnershipChain8364.onFdgVerdict8364(lane, mint, candidateVersion, shouldTrade, blockReason, nowMs)  // V5.0.8364 — the candidate's FDG verdict on its chain
        if (shouldTrade || mint.isBlank()) return
        val l = lane7951(lane)
        if (l !in SpecialistCandidateBooks7803.LANES) return
        val reason = blockReason?.takeIf { it.isNotBlank() } ?: "FDG_NO_REASON"
        noteLaneRefused7960(mint, l, nowMs)
        val capital = capitalOnlyReason7951(reason, capitalNotes7951[mint]?.takeIf { nowMs - it.atMs <= CAPITAL_NOTE_TTL_MS_7951 }?.reason)
        if (capital == null) {
            notePreIntent7951(l, "FDG_$reason")
            stampFdgBlockContinuity8364(l, mint)
            return
        }
        recordCapitalDemand7951(l, mint, nowMs)
        notePreIntent7951(l, "CAPITAL_ONLY_$capital")
        val key = "$l|$mint|$candidateVersion"
        if (stampedCapital7951.size > 4_096) stampedCapital7951.entries.removeIf { nowMs - it.value > DEMAND_WINDOW_MS_7951 }
        if (stampedCapital7951.putIfAbsent(key, nowMs) == null) stampCapitalOwnership7951(l, mint, candidateVersion, capital)
    }

    /**
     * V5.0.8364 — an ordinary FDG refusal stamped nothing in the causal funnel (only the capital-only path did), so a
     * qualified candidate's record read QUALIFY and then nothing: "qualified -> FDG refusal" never showed for the same
     * candidate. The refusal is now stamped as FDG_BLOCK on the SAME record QUALIFIED wrote ("mint:version" of the lane's
     * qualified candidate). Only a candidate the lane actually qualified is stamped — no predecessor is invented, and
     * OWNER / BUY_INTENT stay absent (they follow an FDG allow).
     */
    private fun stampFdgBlockContinuity8364(lane: String, mint: String) {
        try {
            val v = OwnershipChain8364.qualifiedVersion8364(lane, mint) ?: return
            ToolkitSignalSheet.recordDeskStage(lane, "FDG_BLOCK", "$mint:$v")
            PipelineHealthCollector.labelInc("FDG_BLOCK_CAUSAL_CONTINUITY_8364_$lane")
        } catch (_: Throwable) {}
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
        // V5.0.8153 — a lane holding a live hand-off task that the gate refused: the task records why (binding, fit -> next
        // receiver, data, or waiting) before the election consumes the gate reason.
        HandoffTaskRuntime8153.onNotScheduled8153(mint, l)
        // V5.0.8364 — the qualified candidate's chain records why it was not evaluated (read before the election consumes the gate).
        try {
            val p8364 = lane7951(primaryLane)
            OwnershipChain8364.onNotEvaluated8364(l, mint,
                SpecialistElection8136.gateReasonOf8153(mint, l) ?: if (p8364 == l) "PRIMARY_GATED" else "NOT_PRIMARY_$p8364")
        } catch (_: Throwable) {}
        val ready = try { SpecialistCandidateBooks7803.entry(l, mint)?.state == SpecialistCandidateBooks7803.State.READY } catch (_: Throwable) { false }
        // V5.0.8136 — the proposal ends by name in the candidate's election: a gated primary opens the rescue, a gated
        // rescue releases the slot, every other READY lane is a CONTRIBUTOR_SHADOW (it no longer vanishes as a label).
        noteNotEvaluated8136(l, mint, primaryLane, ready)
        if (!ready) return
        val p = lane7951(primaryLane)
        notePreIntent7951(l, if (p == l) "READY_PRIMARY_GATED" else "READY_NOT_EVALUATED_PRIMARY_$p")
        // V5.0.8029 — a specialist that qualified a coin learns from it even when another lane owns it. Only the
        // elected primary reached the FDG, so only it was ever labelled: QUALITY / BLUECHIP / TREASURY cells never
        // filled, watch-first refused them as THIN_CELL for ever, and they never traded (ownerSelected=0 on 555
        // QUALITY candidates). The coin's forward return is now booked in this lane's own cell too, graded on
        // this lane's own stop (LaneParticipation8029). Paper learning only — nothing is bought on it.
        if (p != l) LaneParticipation8029.shadow8029(mint, l)
    }

    /** "REASON=n,..." top four refusals that ended this lane before BUY_INTENT. */
    fun preIntentRefusals7951(lane: String): String {
        val prefix = lane7951(lane) + "|"
        return preIntent7951.entries.filter { it.key.startsWith(prefix) }
            .sortedByDescending { it.value.get() }
            .take(4)
            .joinToString(",") { "${it.key.removePrefix(prefix)}=${it.value.get()}" }
    }

    /**
     * V5.0.8330 — every refusal between QUALIFIED (the lane's own READY proposal) and OWNER_SELECTED (TradeAuthorizer's
     * election after an FDG allow), by name, for the lane's report block: "LANE ownerWhy8330[reason=count,...]".
     * The names are the pre-intent book's ([notePreIntent7951]): READY_NOT_EVALUATED_PRIMARY_<p> (another lane was the
     * cycle primary and no rescue opened), READY_PRIMARY_GATED (its own pre-FDG gate), FDG_<reason> (its FDG refused),
     * CAPITAL_ONLY_<reason>, RESCUE_* (the one-rescue election) — the whole book, not the top four.
     */
    // V5.0.8364 — the line shows refusals only. The pre-intent book also holds every lane's native verdict on every token
    // (DecisionReasons8031: NATIVE_READY on an eligible opinion, NATIVE_<rule> on a refusal of a coin the lane never
    // qualified) and progress markers (READY_HANDED_OFF_*, RESCUE_ELECTED_*, ...). 5.0.8341 read "QUALITY NATIVE_READY=1916"
    // as the top owner refusal — a qualification, not a refusal. Those move to nativeWhyLine8364.
    fun ownerWhyLine8330(lane: String): String? = formatOwnerWhy8330(lane7951(lane), ownerWhyCounts8330(lane).filterKeys { ownerRefusalKey8364(it) })

    /** Pure (V5.0.8364): a pre-intent book key that is a refusal between QUALIFIED and OWNER_SELECTED. */
    fun ownerRefusalKey8364(key: String): Boolean =
        !key.startsWith("NATIVE_") && PROGRESS_PREFIXES_8364.none { key.startsWith(it) }

    private val PROGRESS_PREFIXES_8364 = listOf("READY_HANDED_OFF_", "READY_CELL_PROVEN_EVALUATES", "READY_SHADOW_AUDIT_EVALUATES",
        "RESCUE_ELECTED_", "RUNNER_GRAB_ADMIT")

    /** V5.0.8364 — "LANE nativeWhy8364[ready=n refused{rule=n,...}]": native verdicts on tokens, before any qualification. */
    fun nativeWhyLine8364(lane: String): String? {
        val counts = ownerWhyCounts8330(lane).filterKeys { it.startsWith("NATIVE_") }
        if (counts.isEmpty()) return null
        val ready = counts["NATIVE_READY"] ?: 0L
        val refused = counts.filterKeys { it != "NATIVE_READY" }.entries.sortedByDescending { it.value }.take(6)
            .joinToString(",") { "${it.key.removePrefix("NATIVE_")}=${it.value}" }.ifBlank { "-" }
        return "${lane7951(lane)} nativeWhy8364[ready=$ready refused{$refused}] (native verdicts, not owner refusals)"
    }

    /** The lane's pre-intent book as a map (reason -> count). */
    fun ownerWhyCounts8330(lane: String): Map<String, Long> {
        val prefix = lane7951(lane) + "|"
        return preIntent7951.entries.filter { it.key.startsWith(prefix) }.associate { it.key.removePrefix(prefix) to it.value.get() }
    }

    /** Pure: the report line (null when the lane recorded no pre-intent refusal). Sorted by count, at most 10 names. */
    fun formatOwnerWhy8330(lane: String, counts: Map<String, Long>): String? {
        if (counts.isEmpty()) return null
        val body = counts.entries.sortedByDescending { it.value }.take(10).joinToString(",") { "${it.key}=${it.value}" }
        return "$lane ownerWhy8330[$body] total=${counts.values.sum()}"
    }

    /** Pure: the status the liveness row shows. */
    fun shownStatus7951(baseStatus: String, buyerEnabled: Boolean, intent: Long, residentReady: Int, preIntentRefusals: String): String = when {
        !buyerEnabled -> "BUYER_DISABLED"
        intent == 0L && preIntentRefusals.isNotBlank() -> "REFUSED_BEFORE_INTENT_7951"
        intent == 0L && residentReady == 0 -> "OBSERVING_NO_READY_CANDIDATE"
        else -> baseStatus
    }

    internal fun resetForTests7951() {
        eligibleRaw7951.clear(); eligibleByMint8364.clear(); capitalNotes7951.clear(); demand7951.clear()
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
        // V5.0.8153 — a qualified hand-off receiver's pending task is consumed here: it takes the candidate's one rescue
        // slot through the election (a named receiver beats an unconsumed lottery claim) and evaluates, whoever is primary.
        if (HandoffTaskRuntime8153.admits8153(l, mint, primaryLane, nowMs)) return true
        val fdgRefused = primaryRefused7960(mint, primaryLane, nowMs)
        val native8094 = !fdgRefused && primaryNativeRefused8094(mint, primaryLane, nowMs)
        // V5.0.8136 — a primary its own cycle gated before the FDG (proof / learned floor / pWin), or one that sat silent with
        // no FDG verdict for PRIMARY_SILENT_MS_8136 (its branch never produced a BUY), refused too: the rescue opens.
        val preFdg8136 = !fdgRefused && !native8094 && (SpecialistElection8136.primaryGatedPreFdg8136(mint, lane7951(primaryLane)) ||
            SpecialistElection8136.primarySilent8136(mint, lane7951(primaryLane), nowMs))
        if (!fdgRefused && !native8094 && !preFdg8136) return false
        val ready = try { SpecialistCandidateBooks7803.entry(l, mint)?.state == SpecialistCandidateBooks7803.State.READY } catch (_: Throwable) { false } ||
            (native8094 && coreYieldedTo8119(mint, primaryLane, l)) ||
            // V5.0.8144 — the receiver the primary's fit refusal named is ready by that naming, also when the primary went
            // on to refuse at the FDG (native8094 is then false and the yield never reached its receiver).
            SpecialistElection8136.isNamedReceiver8144(mint, l)
        // V5.0.8094 — a native hand-off goes only to a lane whose OWN strategy for this coin is not losing on its graded
        // record (no fired setup proven and the classified one negative = no hand-off). The withdrawn 8094 hand-off had
        // no such guard; this one does, and every receiving-lane gate (quarantine, rug, FDG, sizing) still runs.
        if (ready && native8094 && !receiverRecordSound8094(mint, l)) {
            try { PipelineHealthCollector.labelInc("READY_HANDOFF_REFUSED_RECEIVER_RECORD_8094_$l") } catch (_: Throwable) {}
            // V5.0.8136 — a named receiver that is refused ends with that precise reason (yield refused, not silent).
            SpecialistElection8136.markIneligible8136(mint, l, "RECEIVER_RECORD_UNSOUND_8094", nowMs)
            return false
        }
        // V5.0.8136 — ONE rescue per candidate. Every READY lane used to be handed the coin at once; now only the best
        // eligible lane on its own scale (independent native evidence, token safety, sound receiver record) takes the
        // slot, and a judgement refusal (safety / rug / token-wide FDG wall) hands it to nobody.
        val trigger8136 = when {
            fdgRefused -> SpecialistElection8136.TRIGGER_FDG
            native8094 -> SpecialistElection8136.TRIGGER_NATIVE
            else -> SpecialistElection8136.TRIGGER_PRE_FDG
        }
        if (ready && !rescueClaim8136(l, mint, primaryLane, trigger8136, nowMs)) {
            // Record lost scheduling competition without making it a permanent
            // candidate-version refusal. Real safety/data refusals were already
            // recorded by the rescue authority and remain intact.
            try {
                PipelineHealthCollector.labelInc("RESCUE_OVERFLOW_7951_$l")
                SpecialistElection8136.noteRescueCompetition(mint, l, nowMs)
            } catch (_: Throwable) {}
            return false
        }
        if (ready) notePreIntent7951(l, if (native8094) "READY_HANDED_OFF_NATIVE_${lane7951(primaryLane)}_8094" else "READY_HANDED_OFF_FROM_${lane7951(primaryLane)}_7960")
        return ready
    }

    /**
     * V5.0.8094 — a READY specialist that is not the cycle primary evaluates the token when ITS OWN graded cell / kind for
     * this token is proven (LiveEdgeGate7877.cellOrKindProven8094). 8094 live: 20 of 29 runners >= +400% were lost to
     * SHADOW_NOT_PRIMARY_8029 (Meta AI +54,809%, JPWHALE +2,016%, PULT +1,915%) while the lanes' own cells read
     * PUMP_PORTAL_WS|MANIPULATED|MC_10K_100K|AGE_LT15M n65 +737%, EXPRESS n66 +703%, SHITCOIN n146 +652%. Every gate of
     * the evaluating lane (quarantine, rug, FDG, edge, sizing) and the one-owner election still decide.
     */
    internal fun readyCellProvenEvaluates8094(lane: String, mint: String, primaryLane: String): Boolean = try {
        val l = lane7951(lane)
        if (l == lane7951(primaryLane) || l !in SpecialistCandidateBooks7803.LANES) false
        // V5.0.8136 — the candidate's one rescue (claimed in handOffAllowed7960, e.g. CORE / TREASURY's named receiver)
        // evaluates on every desk path, also outside the meme ring (MANIPULATED / CASHGEN in meme-only live).
        else if (SpecialistElection8136.holdsRescue8136(mint, l)) true
        else if (SpecialistCandidateBooks7803.entry(l, mint)?.state != SpecialistCandidateBooks7803.State.READY) false
        else {
            val ts = HandoffRoute8180.tokenFor8180(mint)
            val ok = ts != null && com.lifecyclebot.engine.truth.LiveEdgeGate7877.cellOrKindProven8094(ts, l)
            if (ok) notePreIntent7951(l, "READY_CELL_PROVEN_EVALUATES_8094")
            // V5.0.8094 — or the lane's OWN graded record of being shadowed proves the shadowing refuses winners
            // (veto audit 4abd6b13a: SHADOW_NOT_PRIMARY@EXPRESS n500 +30.1%; 10 of 16 runners >= +400% lost to it), and
            // the lane's strategy for this coin is not a losing one. All gates of the evaluating lane still decide.
            val audit = !ok && ts != null && shadowRefusesWinners8094(l) && receiverRecordSound8094(mint, l)
            if (audit) notePreIntent7951(l, "READY_SHADOW_AUDIT_EVALUATES_8094")
            // V5.0.8136 — a proven cell no longer adds an unbounded extra evaluator: it competes for the candidate's ONE
            // rescue slot (own-scale conviction + the proven-cell bonus), so a strong secondary specialist still gets in.
            (ok || audit) && rescueClaim8136(l, mint, primaryLane, SpecialistElection8136.TRIGGER_CELL_PROVEN, System.currentTimeMillis())
        }
    } catch (_: Throwable) { false }

    /** V5.0.8094 — the lane's own veto-audit book of SHADOW_NOT_PRIMARY refusals is proven to win (40+, mean-SE > +2%). */
    internal fun shadowRefusesWinners8094(lane: String): Boolean = try {
        // V5.0.8097 — the lane's own book decides once it has 40 graded refusals; before that the all-lane book does
        // (8095: PLOO +1,216% in SHITCOIN, whose own book was thin, while SHADOW_NOT_PRIMARY all-lane read n500 +14.1%).
        com.lifecyclebot.engine.cortex.Cortex7885.vetoRefusesWinners8006(LaneParticipation8029.SHADOW_REASON, lane7951(lane))
    } catch (_: Throwable) { false }

    // V5.0.8094 — the 7960 hand-off opened only on the primary's FDG refusal; most primaries refuse in their own native
    // brain first (8093: CORE_YIELD_CLEAR_SPECIALIST 15,004 while CASHGEN / QUALITY sat READY_NOT_EVALUATED_PRIMARY_CORE;
    // 8094: MANIPULATED 1,485 qualified, EXPRESS 557, CASHGEN 466 — owner-selected 0). A fresh, graded native refusal on a
    // lane-fit rule opens the same hand-off; a safety / rug / dev / bundle / hard-block refusal never does.
    private fun primaryNativeRefused8094(mint: String, primaryLane: String, nowMs: Long): Boolean = try {
        val snap = SpecialistBrainBridge7542.cachedSnapshot7650(mint)
        snap != null && nativeRefusalHandsOff8094(snap.opinions[lane7951(primaryLane)], snap.evaluatedAtMs, nowMs)
    } catch (_: Throwable) { false }

    /**
     * V5.0.8119 — CORE refused only because [lane] is the clear specialist owner (CORE_YIELD_CLEAR_SPECIALIST_OWNER) and
     * that owner's own native opinion is eligible: the owner evaluates, READY or not. 8117: SAFETY and YouTube ran while
     * CORE yielded and the named owner was never asked. Every gate of the owner lane still decides.
     */
    private fun coreYieldedTo8119(mint: String, primaryLane: String, lane: String): Boolean = try {
        val p = lane7951(primaryLane)
        val ops = SpecialistBrainBridge7542.cachedSnapshot7650(mint)?.opinions
        // V5.0.8132 — any primary that refused on FIT (wrong lane for this coin), not just CORE (8120) / TREASURY (8131).
        val yielded = ops != null && laneFitRefusal8132(ops[p]?.reason)
        // V5.0.8136 — the named owner is the top eligible specialist on its OWN lane scale (ownership conviction on its
        // admission bar), not the raw native score: CASHGEN's raw 76 on a 72 bar out-ranked MOONSHOT's 55 on a 30 bar,
        // so a TREASURY / CORE yield handed the runner coin to the scalper. The name is recorded in the election.
        yielded && yieldReceiver8136(mint, p, ops!!) == lane7951(lane)
    } catch (_: Throwable) { false }

    /**
     * Pure (V5.0.8132): a native refusal that only says "this coin is the wrong shape for MY lane" — cap band, age band,
     * launch phase, role, "not a dip", CORE standing aside. Owner: "go find where else those problems exist". Missed:
     * 34fph3 +1165% / PorkBelly +653% / hozeMZ +545% (TREASURY role floor), MrBeast +739% / 𝕏Mart +1156% (PROJECT_SNIPER
     * phase hand-off), 𝕏Mart +892% (BLUECHIP cap too low), SAFETY / YouTube (CORE yield). Liquidity, score, momentum and
     * safety refusals are judgements, not fit, and stay binding.
     */
    fun laneFitRefusal8132(reason: String?): Boolean {
        val r = reason?.uppercase()?.replace(' ', '_') ?: return false
        return FIT_REASONS_8132.any { r.contains(it) } && HANDOFF_BLOCKING_8094.none { r.contains(it) }
    }

    private val FIT_REASONS_8132 = listOf(
        "MCAP_TOO_LOW", "MCAP_TOO_HIGH", "TOO_OLD", "TOO_YOUNG", "TOO_NEW", "NOT_A_DIP",
        "PHASE_HANDOFF", "ROLE_FLOOR", "CORE_YIELD_CLEAR_SPECIALIST_OWNER", "USE_BLUECHIP",
        // V5.0.8180 — MANIPULATED's "this coin is not a manipulation play" is shape, not a verdict (X +802% lost to it).
        "NO_KNOWN_MANIPULATION_EVIDENCE",
    )

    /** Pure (V5.0.8094): a fresh, graded native refusal on a lane-fit rule (never safety). */
    internal fun nativeRefusalHandsOff8094(op: SpecialistBrainBridge7542.Opinion?, evaluatedAtMs: Long, nowMs: Long): Boolean =
        op != null && !op.eligible && op.authoritative && op.gradeable && nowMs - evaluatedAtMs in 0..REFUSED_HANDOFF_MS_7960 &&
            HANDOFF_BLOCKING_8094.none { op.reason.uppercase().contains(it) }

    private val HANDOFF_BLOCKING_8094 = listOf("RUG", "SAFETY", "HONEYPOT", "BLOCK", "DEV", "BUNDLE", "SCAM", "FREEZE",
        "MINT_AUTH", "BLACKLIST", "ERROR", "ZERO_LIQ", "NO_LIQ", "LIQ_ZERO", "TOXIC", "QUARANTIN")

    /** V5.0.8094 — the receiving lane's strategy for this coin: a proven fired setup, or a classified setup not losing. */
    // V5.0.8190 — only a genuine losing record is unsound (ReceiverRecord8190): a missing TokenState or a NO_TRIGGER read
    // on unobserved setup inputs is a data deferral, carried by rescueIneligible8136 (DEFERRED_*), never a refusal here.
    private fun receiverRecordSound8094(mint: String, lane: String): Boolean =
        ReceiverRecord8190.check8190(mint, lane) != ReceiverRecord8190.UNSOUND_8190

    // ── V5.0.8136 — one causal election contract per candidate (SpecialistElection8136) ──

    /**
     * Pure (V5.0.8136): a refusal no rescue may answer. Natively: the 8094 hand-off blockers (safety / rug / dev /
     * bundle / freeze / toxic ...). At the FDG also a token-wide wall (liquidity, sellability, capital, identity,
     * circuit breaker): every other lane meets the same wall, so handing the coin on only spends a cycle.
     */
    internal fun refusalIsJudgement8136(reason: String?, fdg: Boolean): Boolean {
        val r = reason?.uppercase()?.replace(' ', '_') ?: return false
        return TOKEN_JUDGEMENT_8198.any { r.contains(it) } || (fdg && SpecialistElection8136.fdgWall8136(r))
    }

    /**
     * 8198 — a refusal is a verdict on the TOKEN only when it names token safety or token behaviour. The bare "BLOCK",
     * "TOXIC" and "ERROR" substrings of [HANDOFF_BLOCKING_8094] also matched one lane's judgement of its own strategy
     * (MEMORY_NEGATIVE_BLOCK, SELECTION_QUALITY_FLOOR_BLOCKED_6830, FDG_POLICY_BLOCK_7835, LEARNED_TOXIC_LANE,
     * TOXIC_PATTERN_TACTIC_PIVOT_6482, BRAIN_CONSENSUS_SOFT_BLOCK_*, FDG_LOCAL_BLOCK, a lane evaluation error), so the
     * primary's losing strategy ended the election (PRIMARY_JUDGEMENT) and no other specialist with its own proven
     * setup could take the coin. A lane's strategy record, lane WR floor, lane toxicity or policy head is that lane's
     * fit, never the token's verdict; HARD_BLOCK_* and BEHAVIOR_BLOCK_* stay token-wide.
     */
    // V5.0.8217 — "DEV" matched DEVELOPING / DEVIATION (one lane's chart read); dev-wallet facts are DEV_* (DEV_SELL, DEV_DUMP,
    // LAUNCH_DEV_SOLD), so the token marker is "DEV_".
    private val TOKEN_JUDGEMENT_8198 = listOf("RUG", "SAFETY", "HONEYPOT", "HARD_BLOCK", "BEHAVIOR_BLOCK", "DEV_", "BUNDLE", "SCAM",
        "FREEZE", "MINT_AUTH", "BLACKLIST", "ZERO_LIQ", "NO_LIQ", "LIQ_ZERO", "QUARANTIN")

    private fun contenderOf8136(o: SpecialistBrainBridge7542.Opinion): SpecialistElection8136.Contender8136 =
        SpecialistElection8136.Contender8136(lane7951(o.lane),
            SpecialistElection8136.normalised8136(maxOf(o.score, o.confidence).toDouble(), Double.NaN, o.ownershipConviction7948))

    /** V5.0.8136 — every lane with independent native evidence for [mint] (fresh eligible opinion, else READY book), own scale. */
    private fun contenders8136(mint: String): List<SpecialistElection8136.Contender8136> {
        val out = linkedMapOf<String, SpecialistElection8136.Contender8136>()
        try {
            SpecialistBrainBridge7542.cachedSnapshot7650(mint)?.opinions?.values?.forEach { o ->
                if (o.eligible && o.authoritative) contenderOf8136(o).let { out[it.lane] = it }
            }
        } catch (_: Throwable) {}
        for (l in SpecialistCandidateBooks7803.LANES) {
            if (l in out) continue
            val e = try { SpecialistCandidateBooks7803.entry(l, mint) } catch (_: Throwable) { null } ?: continue
            if (e.state == SpecialistCandidateBooks7803.State.READY) out[l] = SpecialistElection8136.Contender8136(l, e.conviction.coerceIn(0.0, 100.0))
        }
        return out.values.toList()
    }

    /**
     * V5.0.8136 — why [lane] may not be this candidate's rescue (null = it may): the token's hard safety must pass and
     * the lane's own strategy for the coin must not be a losing one (8094 receiver record). DEFERRED_ = data missing.
     */
    internal fun rescueIneligible8136(mint: String, lane: String): String? = try {
        val l = lane7951(lane)
        val ts = HandoffRoute8180.tokenFor8180(mint)  // V5.0.8180 — the same map, by the meme key too
        when {
            ts == null -> HandoffRetention8185.noTokenState8185(mint)  // V5.0.8185 — re-hydrate the open target
            ts.safety.isBlocked || ts.safety.hardBlockReasons.isNotEmpty() -> "TOKEN_SAFETY_BLOCKED"
            else -> ReceiverRecord8190.check8190(mint, l)?.also { v ->  // V5.0.8190 — RECEIVER_RECORD_UNSOUND_8094 only on a genuine losing record
                // V5.0.8222 — a persistent data gap is counted per lane so the handoff audit can see re-fetch cycles.
                if (v == ReceiverRecord8190.DEFER_DATA_GAP_PERSISTENT_8221)
                    try { PipelineHealthCollector.labelInc("RESCUE_DEFERRED_DATA_GAP_PERSISTENT_8222_$l") } catch (_: Throwable) {}
            }
        }
    } catch (_: Throwable) { "DEFERRED_RESCUE_CHECK_ERROR" }

    /**
     * V5.0.8136 — [lane] asks for the candidate's one rescue slot ([trigger] = why the primary is out, or a proven cell).
     * V5.0.8144 — the arbitration runs inside ElectionRescueContract8144.guardedClaim8144: an exception is the named
     * outcome DEFERRED_ARBITRATION_ERROR (the proposal ends DEFERRED by name) and the lane does NOT evaluate. The old
     * handler answered `true`, so a throw let a lane proceed on a claim nobody granted.
     */
    private fun rescueClaim8136(lane: String, mint: String, primaryLane: String, trigger: String, nowMs: Long): Boolean {
        val outcome = ElectionRescueContract8144.guardedClaim8144 {
            val l = lane7951(lane)
            val p = lane7951(primaryLane)
            val refusal = when (trigger) {
                SpecialistElection8136.TRIGGER_NATIVE -> SpecialistBrainBridge7542.cachedSnapshot7650(mint)?.opinions?.get(p)?.reason.orEmpty()
                SpecialistElection8136.TRIGGER_FDG -> SpecialistElection8136.lastFdgRefusal8136(mint, p, nowMs).orEmpty()
                SpecialistElection8136.TRIGGER_PRE_FDG ->
                    if (SpecialistElection8136.primaryGatedPreFdg8136(mint, p)) "PRIMARY_GATED_PRE_FDG" else "PRIMARY_SILENT_NO_FDG"
                else -> ""
            }
            SpecialistElection8136.claimRescueOutcome8144(mint, l, p, trigger, refusal,
                refusalIsJudgement8136(refusal, fdg = trigger == SpecialistElection8136.TRIGGER_FDG),
                contenders8136(mint), rescueIneligible8136(mint, l), nowMs)
        }
        if (outcome == ElectionRescueContract8144.ClaimOutcome8144.DEFERRED_ARBITRATION_ERROR) {
            SpecialistElection8136.noteArbitrationError8144(mint, lane7951(lane), nowMs)
            notePreIntent7951(lane, "RESCUE_ARBITRATION_ERROR_8144")
        }
        if (outcome.executable) notePreIntent7951(lane, "RESCUE_ELECTED_8136_$trigger")
        return outcome.executable
    }

    /** V5.0.8136 — the specialist a fit refusal by [primary] names (top eligible lane on its own scale), recorded once. */
    private fun yieldReceiver8136(mint: String, primary: String, ops: Map<String, SpecialistBrainBridge7542.Opinion>): String? {
        val receiver = SpecialistElection8136.electRescue8136(
            ops.values.filter { it.authoritative && it.eligible }.map { contenderOf8136(it) }, lane7951(primary))
        // V5.0.8144 — the receiver RECORDED for this candidate answers: a refreshed snapshot that ranks another lane first
        // no longer strands the lane the primary already named (it used to fail the receiver's own hand-off check).
        return SpecialistElection8136.noteYield8136(mint, lane7951(primary), receiver)
            .also { named -> if (named != null) HandoffTaskRuntime8153.onYield8153(mint, lane7951(primary), named, ops) }  // V5.0.8153 — the yield is a task
    }

    /**
     * V5.0.8136 — LaneExecutionCoordinator: the cycle primary's own native verdict joins the election. A refusal opens the
     * rescue (a judgement one — or a non-graded one like ALREADY_ / COOLDOWN — never does); a fit refusal names the
     * receiver, which must then reach the decision funnel or end with a precise refusal.
     */
    internal fun noteCycleNative8136(mint: String, nowMs: Long = System.currentTimeMillis()) {
        try {
            val p = SpecialistElection8136.primaryOf8136(mint)
            if (p.isBlank()) return
            val snap = SpecialistBrainBridge7542.cachedSnapshot7650(mint) ?: return
            val op = snap.opinions[p] ?: return
            if (!op.authoritative) return
            // V5.0.8395 — a data-scoped deferral is THIS lane waiting for its inputs: it neither judges the token nor blocks a
            // qualified alternate. Material recovery (the lane now eligible) clears only that data deferral.
            val scope8395 = SpecialistElection8136.refusalScope8395(op.reason, op.gradeable, op.eligible) { refusalIsJudgement8136(it, fdg = false) }
            if (op.eligible) { SpecialistElection8136.clearPrimaryDataDefer8395(mint, p); return }
            SpecialistElection8136.notePrimaryRefusal8136(mint, p, "${SpecialistElection8136.TRIGGER_NATIVE}:${op.reason}",
                scope8395 == SpecialistElection8136.RefusalScope8395.JUDGEMENT, nowMs,
                dataScoped8395 = scope8395 == SpecialistElection8136.RefusalScope8395.DATA)
            if (scope8395 == SpecialistElection8136.RefusalScope8395.DATA) {
                try { PipelineHealthCollector.labelInc("PRIMARY_DATA_DEFER_LANE_SCOPED_8395_${p}") } catch (_: Throwable) {}
            }
            // V5.0.8180 — any graded, non-binding, non-data refusal names the lane that qualified the coin (NativeYield8180).
            val fit8180 = laneFitRefusal8132(op.reason)
            val data8180 = HandoffTaskRuntime8153.blockOf8153(op.reason) == HandoffReceiverTasks8153.Block8153.DATA
            if (NativeYield8180.namesReceiver8180(op.reason, op.gradeable, fit8180, refusalIsJudgement8136(op.reason, fdg = false), data8180)) yieldReceiver8136(mint, p, snap.opinions)
        } catch (_: Throwable) {}
    }

    private fun noteNotEvaluated8136(lane: String, mint: String, primaryLane: String, ready: Boolean) {
        try {
            val score = if (!ready) Double.NaN else contenders8136(mint).firstOrNull { it.lane == lane }?.normalised ?: Double.NaN
            SpecialistElection8136.noteNotEvaluated8136(mint, lane, lane7951(primaryLane), ready, score)
        } catch (_: Throwable) {}
    }

    private fun noteFdgVerdict8136(mint: String, lane: String, candidateVersion: Long, shouldTrade: Boolean, blockReason: String?, nowMs: Long) {
        try {
            val l = lane7951(lane)
            if (mint.isBlank() || l !in SpecialistCandidateBooks7803.LANES) return
            val own = contenders8136(mint).firstOrNull { it.lane == l }
            SpecialistElection8136.onFdgVerdict8136(mint, l, candidateVersion, shouldTrade, blockReason,
                refusalIsJudgement8136(blockReason, fdg = true), own != null, own?.normalised ?: Double.NaN, nowMs)
        } catch (_: Throwable) {}
    }
}
