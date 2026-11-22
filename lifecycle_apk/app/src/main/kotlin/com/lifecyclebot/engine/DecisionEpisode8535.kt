package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8535 §P2-01 — one joined decision episode per (lane, mint, candidate version): the native signed score and the
 * fired strategy, the admission's terminal with its trace hash, the FDG verdict and reason, the chain stages the
 * candidate reached (ticket / exec / close), and the forward-label receipt. The 8528 observability note recorded that
 * no single row joined these, so a report could not say which signed adjustment or gate decided one observed coin.
 *
 * Producers write fields as they happen (OwnershipChain8364 stages and FDG verdicts, SpecialistBrainBridge7542's final
 * opinion, CandidateAdmission8136's decision); nothing is derived. Bounded ring; the brief prints the newest episodes
 * and the count of complete ones (native + admission + FDG present).
 */
object DecisionEpisode8535 {

    const val MAX_8535 = 512

    private class Episode(val lane: String, val mint: String, val version: Long, val openedMs: Long) {
        @Volatile var nativeScore: String = ""
        @Volatile var firedSetup: String = ""
        @Volatile var admission: String = ""
        @Volatile var admissionHash: String = ""
        @Volatile var fdg: String = ""
        @Volatile var stages: String = ""
        @Volatile var label: String = ""
        @Volatile var touchedMs: Long = openedMs
        /** V5.0.8876 — the market-cell evidence kind the candidate carried at admission ("" = none), and what it reached. */
        @Volatile var evidence: String = ""
        val reached8876 = HashSet<String>()
        fun complete(): Boolean = nativeScore.isNotBlank() && admission.isNotBlank() && fdg.isNotBlank()
        fun line(): String = "${mint.take(8)}|$lane|v$version native=${nativeScore.ifBlank { "-" }} setup=${firedSetup.ifBlank { "-" }} " +
            "admission=${admission.ifBlank { "-" }}#${admissionHash.ifBlank { "-" }} fdg=${fdg.ifBlank { "-" }} stages=${stages.ifBlank { "-" }} label=${label.ifBlank { "-" }}"
    }

    private val ring = LinkedHashMap<String, Episode>()
    private val written = AtomicLong(0)

    private fun key(lane: String, mint: String, version: Long) = "${lane.uppercase()}|$mint|$version"

    private fun episode(lane: String, mint: String, version: Long, nowMs: Long): Episode? {
        if (lane.isBlank() || mint.isBlank()) return null
        val k = key(lane, mint, version)
        return ring[k] ?: Episode(lane.uppercase(), mint, version, nowMs).also {
            ring[k] = it
            while (ring.size > MAX_8535) ring.remove(ring.keys.first())
        }
    }

    /** Pure: a short stable hash of an admission trace, so two reports can prove they read the same evidence. */
    fun hash8535(trace: List<String>): String = Integer.toHexString(trace.joinToString("\u0001").hashCode())

    @Synchronized fun noteNative8535(lane: String, mint: String, version: Long, rawScore: Double, scaleMax: Double, eligible: Boolean, firedSetup: String?, nowMs: Long = System.currentTimeMillis()) {
        val e = episode(lane, mint, version, nowMs) ?: return
        e.nativeScore = "${if (rawScore.isFinite()) "%.0f".format(rawScore) else "?"}/${if (scaleMax.isFinite()) "%.0f".format(scaleMax) else "?"}:${if (eligible) "Y" else "N"}"
        if (!firedSetup.isNullOrBlank()) e.firedSetup = firedSetup
        e.touchedMs = nowMs; written.incrementAndGet()
    }

    @Synchronized fun noteAdmission8535(lane: String, mint: String, version: Long, terminal: String, reason: String, setup: String?, trace: List<String>, nowMs: Long = System.currentTimeMillis(),
                                       evidence8876: String = "") {
        val e = episode(lane, mint, version, nowMs) ?: return
        e.admission = "$terminal:$reason"
        if (evidence8876.isNotBlank()) e.evidence = evidence8876.take(32)
        reach8876(e, "COINS")
        reach8876(e, if (terminal == "ADMIT") "ADMIT" else terminal.take(16), if (terminal == "ADMIT") null else reason)
        // V5.0.8895 — a data deferral of an evidence coin names what it waited for (the admission's own defer causes, 8700)
        if (terminal == "DEFER_DATA" && e.evidence.isNotBlank() && e.reached8876.add("DEFER_CAUSES_8895"))
            try { com.lifecyclebot.engine.truth.CandidateAdmission8136.deferCauses8700(trace).forEach { waitCause8895(e.evidence, it) } } catch (_: Throwable) {}
        // V5.0.8943 — a safety refusal of an evidence coin names the hard finding that refused it (the base pocket's 90 of 153
        // on the 13:59 capture were one undivided SAFETY_REFUSED_8136)
        if (reason == "SAFETY_REFUSED_8136" && e.evidence.isNotBlank() && e.reached8876.add("SAFETY_CAUSE_8943"))
            safetyCause8943(trace)?.let { safetyCount8943(e.evidence, it) }
        e.admissionHash = hash8535(trace)
        if (!setup.isNullOrBlank() && e.firedSetup.isBlank()) e.firedSetup = setup
        e.touchedMs = nowMs; written.incrementAndGet()
    }

    @Synchronized fun noteFdg8535(lane: String, mint: String, version: Long, allow: Boolean, reason: String?, nowMs: Long = System.currentTimeMillis()) {
        val e = episode(lane, mint, version, nowMs) ?: return
        e.fdg = (if (allow) "ALLOW" else "BLOCK") + (reason?.takeIf { it.isNotBlank() }?.let { ":${it.take(48)}" } ?: "")
        reach8876(e, if (allow) "FDG_ALLOW" else "FDG_BLOCK", if (allow) null else reason)
        e.touchedMs = nowMs; written.incrementAndGet()
    }

    @Synchronized fun noteStage8535(lane: String, mint: String, version: Long, stage: String, nowMs: Long = System.currentTimeMillis()) {
        val e = episode(lane, mint, version, nowMs) ?: return
        if (!e.stages.split(',').contains(stage)) e.stages = if (e.stages.isBlank()) stage else e.stages + "," + stage
        if (stage in FUNNEL_STAGES_8876) reach8876(e, stage)
        e.touchedMs = nowMs; written.incrementAndGet()
    }

    @Synchronized fun noteLabel8535(lane: String, mint: String, receipt: String, nowMs: Long = System.currentTimeMillis()) {
        // the label arrives by (mint, lane) without a version: it joins the newest episode of that pair
        val e = ring.values.filter { it.lane == lane.uppercase() && it.mint == mint }.maxByOrNull { it.openedMs } ?: return
        e.label = receipt.take(48)
        e.touchedMs = nowMs; written.incrementAndGet()
    }

    @Synchronized fun complete8535(): Int = ring.values.count { it.complete() }

    @Synchronized fun recent8535(n: Int = 6): List<String> = ring.values.sortedByDescending { it.touchedMs }.take(n).map { it.line() }

    @Synchronized fun reportLine8535(lane: String? = null): String {
        val l = lane?.trim()?.uppercase()
        val mine = ring.values.filter { l == null || it.lane == l }
        val recent = mine.sortedByDescending { it.touchedMs }.take(4).map { it.line() }
        return "episodes8535[open=${mine.size} complete=${mine.count { it.complete() }} writes=${written.get()}] " +
            recent.joinToString(" · ").ifBlank { "none" }
    }

    /**
     * V5.0.8876 §WHERE_THE_EVIDENCE_COINS_GO (plan W10) — a coin that carried market-cell evidence (a paying cell, the launch
     * sniper, a proven pattern, the agreement pocket, the LLM's band, ...) is counted at every step it reaches, once per
     * candidate episode: COINS, the admission's terminal (ADMIT / REFUSE / DEFER_DATA ...), FDG_ALLOW / FDG_BLOCK, then the
     * chain's TICKET / EXEC / CLOSE; a refusal's or a block's reason is counted beside it. The report then names where those
     * coins stop instead of anyone guessing. Counted, never acted on.
     */
    private val FUNNEL_STAGES_8876 = setOf("OWNER", "INTENT", "TICKET", "EXEC", "CLOSE")
    private const val MAX_REASONS_8876 = 48
    private class Funnel8876 { val steps = LinkedHashMap<String, Long>(); val why = HashMap<String, Long>(); val wait8895 = HashMap<String, Long>()
        val safety8943 = HashMap<String, Long>() }

    /**
     * Pure (V5.0.8943): the hard safety finding an admission trace names ("safety=HARD_BLOCK:LP only 0% locked — ..."), its
     * numbers folded to N so one rule counts as one ("HARD_BLOCK:LP only N% locked"); null when the trace names none.
     */
    internal fun safetyCause8943(trace: List<String>): String? =
        trace.firstOrNull { it.startsWith("safety=") }?.removePrefix("safety=")?.replace(Regex("\\d+"), "N")?.take(44)?.trim()?.ifBlank { null }

    /** V5.0.8943 — under this object's lock: one safety refusal's finding for an evidence coin of [kind]. */
    private fun safetyCount8943(kind: String, cause: String) {
        val f = funnels8876.getOrPut(kind) { Funnel8876() }
        if (f.safety8943.size < MAX_REASONS_8876 || f.safety8943.containsKey(cause)) f.safety8943[cause] = (f.safety8943[cause] ?: 0L) + 1L
    }

    /** V5.0.8895 — under this object's lock: one data-deferral cause of an evidence coin of [kind]. */
    private fun waitCause8895(kind: String, cause: String) {
        val f = funnels8876.getOrPut(kind) { Funnel8876() }
        val k = cause.take(48)
        if (f.wait8895.size < MAX_REASONS_8876 || f.wait8895.containsKey(k)) f.wait8895[k] = (f.wait8895[k] ?: 0L) + 1L
    }
    private val funnels8876 = LinkedHashMap<String, Funnel8876>()

    /** Called under this object's lock: [e] reached [step] (and why, for a refusal or block). Counted once per episode. */
    private fun reach8876(e: Episode, step: String, why: String? = null) {
        val kind = e.evidence
        if (kind.isBlank() || !e.reached8876.add(step)) return
        val f = funnels8876.getOrPut(kind) { Funnel8876() }
        f.steps[step] = (f.steps[step] ?: 0L) + 1L
        if (!why.isNullOrBlank()) {
            val k = "$step:${why.take(40)}"
            if (f.why.size < MAX_REASONS_8876 || f.why.containsKey(k)) f.why[k] = (f.why[k] ?: 0L) + 1L
        }
    }

    /** The brief: per evidence kind, how many coins reached each step and the commonest refusals / blocks. */
    @Synchronized fun funnelLine8876(): String {
        val order = listOf("COINS", "ADMIT", "REFUSE", "DEFER_DATA", "FDG_ALLOW", "FDG_BLOCK", "OWNER", "INTENT", "TICKET", "EXEC", "CLOSE")
        val body = funnels8876.entries.sortedByDescending { it.value.steps["COINS"] ?: 0L }.joinToString(" ") { (kind, f) ->
            val steps = (order.filter { it in f.steps } + f.steps.keys.filter { it !in order }).joinToString(" ") { "$it=${f.steps[it]}" }
            val why = f.why.entries.sortedByDescending { it.value }.take(4).joinToString(",") { "${it.key}=${it.value}" }.ifBlank { "-" }
            // V5.0.8895 — what its data deferrals waited for (price, move, cost, a buy-door proof, an entry wait ...)
            val wait = f.wait8895.entries.sortedByDescending { it.value }.take(5).joinToString(",") { "${it.key}=${it.value}" }
            // V5.0.8943 — and which hard safety finding its safety refusals were
            val safety = f.safety8943.entries.sortedByDescending { it.value }.take(5).joinToString(",") { "${it.key}=${it.value}" }
            "$kind{$steps why{$why}${if (wait.isBlank()) "" else " wait{$wait}"}${if (safety.isBlank()) "" else " safety8943{$safety}"}}"
        }.ifBlank { "-" }
        return "evidenceFunnel8876[$body rule=coins that carried market-cell evidence, once per candidate at each step they reached]"
    }

    @Synchronized fun clearForTest8535() { ring.clear(); written.set(0); funnels8876.clear() }
}
