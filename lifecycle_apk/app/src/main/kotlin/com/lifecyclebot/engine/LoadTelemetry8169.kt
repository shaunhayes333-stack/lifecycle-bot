package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8169 §COMPUTE_LOAD — what the device spends its CPU, Helius credits and heap on, measured per holder / caller.
 *
 * 5.0.8168 (9 min uptime, LIVE): "Root cause likely: LANE_FANOUT_EXPLOSION laneEval/intake=19.00", Helius today=99.2k,
 * heap 511/512 MB CRITICAL. Read against the funnel the three numbers mean something else:
 *  - LANE_EVAL=7,439 against SCAN_CB=7,230 is 1.03 active lane evaluations per candidate cycle. The cycle gate
 *    (BotService.laneEvaluationAllowed7951) already runs one owner (+ one bounded rescue) per cycle; the 19 is how
 *    often a resident candidate is re-cycled per intake event (18.6 cycles per intake), not lanes per candidate.
 *  - The Helius figures are the UTC day's persisted totals (all runs today), not this 9-minute process.
 *  - The census attributes ~60 MB; a hard trim recovered 307 MB: most of the 511 MB was collectable allocation,
 *    and the per-mint caches nobody pruned (native snapshots, desk sheets, bundle analyses) were not in the census.
 * This object holds the pure helpers and the session counters that let the next report say which it is.
 */
object LoadTelemetry8169 {

    private val startedAtMs = System.currentTimeMillis()

    // ── lane fan-out per candidate cycle ──

    /**
     * Pure: active lane evaluations per candidate cycle. Every processTokenCycle invocation (SCAN_CB) is one cycle of
     * one candidate; dividing by intake events counted each re-cycle of a resident candidate as more fan-out.
     * Falls back to the intake denominator only when no cycle count exists (old snapshots / tests).
     */
    fun lanesPerCandidateCycle8169(laneEval: Long, scanCb: Long, intake: Long): Double = when {
        scanCb > 0L -> laneEval.coerceAtLeast(0L).toDouble() / scanCb
        intake > 0L -> laneEval.coerceAtLeast(0L).toDouble() / intake
        else -> 0.0
    }

    /** Pure: how many cycles a candidate runs per intake event (residency, not fan-out). */
    fun cyclesPerIntake8169(scanCb: Long, intake: Long): Double =
        if (intake > 0L && scanCb > 0L) scanCb.toDouble() / intake else 0.0

    /** Pure: the named key a lane-gate skip is counted under (gate reason head, or which side of the election it was). */
    fun laneSkipReason8169(gateReason: String?, lane: String, primaryLane: String): String {
        val g = gateReason.orEmpty().trim()
        if (g.isNotEmpty()) {
            val head = g.substringBefore(':').trim().uppercase().filter { it.isLetterOrDigit() || it == '_' }.take(40)
            if (head.isNotEmpty()) return head
        }
        return if (lane.trim().equals(primaryLane.trim(), ignoreCase = true)) "PRIMARY_GATED_UNNAMED" else "NON_OWNER_NOT_RESCUE"
    }

    private val laneGateCalls = AtomicLong(0)
    private val laneGateAllowed = AtomicLong(0)
    private val laneGateNanos = AtomicLong(0)
    private val laneSkips = ConcurrentHashMap<String, AtomicLong>()
    private val laneSkipsByLane = ConcurrentHashMap<String, AtomicLong>()

    private fun bump(m: ConcurrentHashMap<String, AtomicLong>, k: String, n: Long = 1L) {
        if (m.size > 256 && !m.containsKey(k)) return
        m.computeIfAbsent(k) { AtomicLong(0) }.addAndGet(n)
    }

    /**
     * BotService.shouldRunBuyLaneForCycle: time the cycle gate and, when it does not let [lane] evaluate, count the skip
     * under its name. Called before the election consumes the gate reason (SpecialistElection8136.noteNotEvaluated8136).
     * Never changes the verdict.
     */
    fun timedLaneGate8169(mint: String, lane: String, primaryLane: String, gate: () -> Boolean): Boolean {
        val t0 = System.nanoTime()
        val allowed = gate()
        noteLaneGate8169(mint, lane, primaryLane, allowed, System.nanoTime() - t0)
        return allowed
    }

    private fun noteLaneGate8169(mint: String, lane: String, primaryLane: String, allowed: Boolean, nanos: Long) {
        try {
            laneGateCalls.incrementAndGet()
            laneGateNanos.addAndGet(nanos.coerceAtLeast(0L))
            if (allowed) { laneGateAllowed.incrementAndGet(); return }
            // V5.0.8364 — NON_OWNER_NOT_RESCUE split: a lane that never qualified the token vs a qualified one with no slot.
            val reason = OwnershipChain8364.skipKey8364(laneSkipReason8169(
                try { SpecialistElection8136.gateReasonOf8153(mint, lane) } catch (_: Throwable) { null }, lane, primaryLane,
            ), mint, lane)
            bump(laneSkips, reason)
            bump(laneSkipsByLane, lane.trim().uppercase().take(24))
        } catch (_: Throwable) {}
    }

    // ── Helius calls by caller (this process, not the day) ──

    private val HELIUS_PLUMBING = listOf(
        "com.lifecyclebot.network.HeliusCreditMeterInterceptor7881", "com.lifecyclebot.network.SharedHttpClient",
        "com.lifecyclebot.engine.HealthAwareHttp", "com.lifecyclebot.network.HostCircuit", "com.lifecyclebot.engine.LoadTelemetry8169",
        "com.lifecyclebot.network.HttpBodyCap8305",   // V5.0.8305 — the body-cap interceptor is plumbing, never a caller
    )

    /**
     * Pure: the first app frame below the HTTP plumbing, as a short class name ("BundleDetector"). An async OkHttp call
     * has no app frame on the dispatcher thread: it is named by its thread ("async:OkHttp").
     */
    fun callerOf8169(frames: List<String>, threadName: String): String {
        for (f in frames) {
            if (!f.startsWith("com.lifecyclebot.")) continue
            if (HELIUS_PLUMBING.any { f.startsWith(it) }) continue
            val simple = f.substringAfterLast('.').substringBefore('$').take(40)
            if (simple.isNotEmpty()) return simple
        }
        val t = threadName.filter { it.isLetter() || it == '-' }.trim('-').take(24)
        return "async:" + t.ifBlank { "thread" }
    }

    private val ADDRESS = Regex("\"params\"\\s*:\\s*\\[\\s*\"([1-9A-HJ-NP-Za-km-z]{32,44})\"")
    private val NEVER_DEDUPED = setOf("sendTransaction", "simulateTransaction", "sendBundle", "getLatestBlockhash", "getSignatureStatuses")

    /**
     * Pure: identity of a read for duplicate detection — one method on one mint / account. Null for batches, writes,
     * parameterless and execution-status calls (those are never counted as redundant).
     */
    fun readKey8169(path: String, methods: List<String>, body: String): String? {
        if (path.startsWith("/v0/addresses/")) {
            val addr = path.removePrefix("/v0/addresses/").substringBefore('/')
            return if (addr.isBlank()) null else "v0tx:$addr"
        }
        if (methods.size != 1) return null
        val m = methods[0]
        if (m in NEVER_DEDUPED) return null
        val addr = ADDRESS.find(body)?.groupValues?.getOrNull(1) ?: return null
        return "$m:$addr"
    }

    /** Pure: is a read of the same key within [windowMs] of the previous one a duplicate? */
    fun isDuplicate8169(prevMs: Long?, nowMs: Long, windowMs: Long = DUP_WINDOW_MS): Boolean =
        prevMs != null && nowMs >= prevMs && nowMs - prevMs <= windowMs

    private const val DUP_WINDOW_MS = 10_000L
    private const val READ_KEYS_CAP = 4_096

    private val heliusCalls = AtomicLong(0)
    private val heliusCreditsX10 = AtomicLong(0)

    /** V5.0.8187 — Helius credits spent since this process started (the Cortex's per-decision economy reads this, not the day). */
    fun sessionCredits8187(): Double = heliusCreditsX10.get() / 10.0

    /** V5.0.8187 — credits spent since this process started by one caller (e.g. "BundleDetector"). */
    fun sessionCreditsBy8187(caller: String): Double = byCaller[caller]?.let { synchronized(it) { it[1] / 10.0 } } ?: 0.0
    private val byCaller = ConcurrentHashMap<String, LongArray>()  // [calls, creditsX10, dup]
    private val dupByMethod = ConcurrentHashMap<String, AtomicLong>()
    private val lastRead = ConcurrentHashMap<String, Long>()

    /** HeliusCreditMeterInterceptor7881, after a metered call reached the wire. Never throws, never blocks the call. */
    fun noteHelius8169(path: String, methods: List<String>, body: String, credits: Double, nowMs: Long = System.currentTimeMillis()) {
        try {
            val t = Thread.currentThread()
            val caller = callerOf8169(t.stackTrace.asSequence().map { it.className }.take(96).toList(), t.name)
            val key = readKey8169(path, methods, body)
            val dup = key != null && isDuplicate8169(lastRead.put(key, nowMs), nowMs)
            if (lastRead.size > READ_KEYS_CAP) lastRead.entries.removeIf { nowMs - it.value > DUP_WINDOW_MS }
            heliusCalls.incrementAndGet()
            val c10 = (credits.coerceAtLeast(0.0) * 10.0).toLong()
            heliusCreditsX10.addAndGet(c10)
            if (byCaller.size < 128 || byCaller.containsKey(caller)) {
                val row = byCaller.computeIfAbsent(caller) { LongArray(3) }
                synchronized(row) { row[0] += 1L; row[1] += c10; if (dup) row[2] += 1L }
            }
            if (dup && key != null) bump(dupByMethod, key.substringBefore(':'))
        } catch (_: Throwable) {}
    }

    // ── report ──

    private fun k(v: Double): String = if (v >= 1_000) "%.1fk".format(v / 1_000) else "%.0f".format(v)

    fun statusLine8169(nowMs: Long = System.currentTimeMillis()): String {
        val view = try { PipelineHealthCollector.phaseView8144("SCAN_CB", "INTAKE", "LANE_EVAL").phaseCounts } catch (_: Throwable) { emptyMap() }
        val scan = view["SCAN_CB"] ?: 0L
        val intake = view["INTAKE"] ?: 0L
        val lane = view["LANE_EVAL"] ?: 0L
        val calls = laneGateCalls.get()
        val skipped = calls - laneGateAllowed.get()
        val gateMs = laneGateNanos.get() / 1_000_000.0
        val skips = laneSkips.entries.sortedByDescending { it.value.get() }.take(8).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        val skipLanes = laneSkipsByLane.entries.sortedByDescending { it.value.get() }.take(12).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        val upS = ((nowMs - startedAtMs) / 1000L).coerceAtLeast(1L)
        val callers = byCaller.entries.map { it.key to synchronized(it.value) { it.value.copyOf() } }
            .sortedByDescending { it.second[1] }.take(10)
            .joinToString(" · ") { (c, r) -> "$c=${r[0]}/${k(r[1] / 10.0)}cr" + (if (r[2] > 0) "/dup${r[2]}" else "") }.ifBlank { "-" }
        val dups = dupByMethod.entries.sortedByDescending { it.value.get() }.take(6).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        return "lanes8169[perCycle=${"%.2f".format(lanesPerCandidateCycle8169(lane, scan, intake))} cyclesPerIntake=${"%.1f".format(cyclesPerIntake8169(scan, intake))} " +
            "gateCalls=$calls evaluated=${laneGateAllowed.get()} skipped=$skipped gateMs=${"%.0f".format(gateMs)}(${if (calls > 0) "%.2f".format(gateMs / calls) else "0"}/call) " +
            "skippedBy{$skips} skippedLane{$skipLanes}]\n" +
            "      helius8169 session[${upS}s calls=${heliusCalls.get()} credits=${k(heliusCreditsX10.get() / 10.0)} (day totals are in the Helius section)] " +
            "byCaller[calls/credits/dup${DUP_WINDOW_MS / 1000}s]{$callers} dupReads{$dups}"
    }
}
