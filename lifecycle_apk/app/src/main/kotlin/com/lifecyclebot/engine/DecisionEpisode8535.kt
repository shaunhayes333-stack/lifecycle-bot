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

    @Synchronized fun noteAdmission8535(lane: String, mint: String, version: Long, terminal: String, reason: String, setup: String?, trace: List<String>, nowMs: Long = System.currentTimeMillis()) {
        val e = episode(lane, mint, version, nowMs) ?: return
        e.admission = "$terminal:$reason"
        e.admissionHash = hash8535(trace)
        if (!setup.isNullOrBlank() && e.firedSetup.isBlank()) e.firedSetup = setup
        e.touchedMs = nowMs; written.incrementAndGet()
    }

    @Synchronized fun noteFdg8535(lane: String, mint: String, version: Long, allow: Boolean, reason: String?, nowMs: Long = System.currentTimeMillis()) {
        val e = episode(lane, mint, version, nowMs) ?: return
        e.fdg = (if (allow) "ALLOW" else "BLOCK") + (reason?.takeIf { it.isNotBlank() }?.let { ":${it.take(48)}" } ?: "")
        e.touchedMs = nowMs; written.incrementAndGet()
    }

    @Synchronized fun noteStage8535(lane: String, mint: String, version: Long, stage: String, nowMs: Long = System.currentTimeMillis()) {
        val e = episode(lane, mint, version, nowMs) ?: return
        if (!e.stages.split(',').contains(stage)) e.stages = if (e.stages.isBlank()) stage else e.stages + "," + stage
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

    @Synchronized fun clearForTest8535() { ring.clear(); written.set(0) }
}
