package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8283 §FEATURE_FUNNEL (P5) — per specialist, UNIQUE candidates (mints) reaching each stage of the feature funnel
 * DISCOVERED → NATIVE_EVALUATED → FEATURE_REQUIRED → FEATURE_PRESENT → FEATURE_REPAIRED → NATIVE_QUALIFIED → ADMISSION →
 * FDG → SEALED_TICKET → BUY_CONFIRMED → SELL_FINALIZED → LEARNING_ACK. Evaluation counts inflate with every cycle; a
 * unique-mint count says how many coins each lane actually carried through. A stage nobody notes prints nothing.
 * Diagnostic only: nothing reads it to decide.
 */
object FeatureFunnel8283 {
    enum class Stage8283 {
        DISCOVERED, NATIVE_EVALUATED, FEATURE_REQUIRED, FEATURE_PRESENT, FEATURE_REPAIRED, NATIVE_QUALIFIED,
        ADMISSION, FDG, SEALED_TICKET, BUY_CONFIRMED, SELL_FINALIZED, LEARNING_ACK,
    }

    /** Bound on remembered (lane, stage, mint) triples; past it a first sighting is counted as overflow, not lost silently. */
    internal const val MAX_KEYS_8283 = 60_000

    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val counts = ConcurrentHashMap<String, AtomicLong>()      // "LANE|STAGE"
    private val overflow = AtomicLong(0L)

    /** Notes [mint] reaching [stage] in [lane]; true on its first arrival there (the unique count advanced). */
    fun note8283(lane: String, mint: String, stage: Stage8283): Boolean {
        if (lane.isBlank() || mint.isBlank()) return false
        val l = lane.trim().uppercase()
        val k = "$l|${stage.name}|$mint"
        if (seen.contains(k)) return false
        if (seen.size >= MAX_KEYS_8283) { overflow.incrementAndGet(); return false }
        if (!seen.add(k)) return false
        counts.computeIfAbsent("$l|${stage.name}") { AtomicLong(0L) }.incrementAndGet()
        return true
    }

    /** Has [mint] already reached [stage] in [lane]? */
    internal fun reached8283(lane: String, mint: String, stage: Stage8283): Boolean = seen.contains("${lane.trim().uppercase()}|${stage.name}|$mint")

    /**
     * LanePlaybook7907.classify8084, one fresh classification: evaluated; a core input missing (required) or all present;
     * present after having been required = repaired by arriving evidence.
     */
    fun noteClassified8283(lane: String, mint: String, coreMissing: Boolean) {
        note8283(lane, mint, Stage8283.NATIVE_EVALUATED)
        if (coreMissing) { note8283(lane, mint, Stage8283.FEATURE_REQUIRED); return }
        note8283(lane, mint, Stage8283.FEATURE_PRESENT)
        if (reached8283(lane, mint, Stage8283.FEATURE_REQUIRED)) note8283(lane, mint, Stage8283.FEATURE_REPAIRED)
    }

    fun count8283(lane: String, stage: Stage8283): Long = counts["${lane.trim().uppercase()}|${stage.name}"]?.get() ?: 0L

    /** "funnel8283[SHITCOIN{NATIVE_EVALUATED=40>FEATURE_REQUIRED=31>...} ... overflow=0]": unique mints per lane and stage. */
    fun line8283(): String {
        val lanes = counts.keys.map { it.substringBefore('|') }.distinct().sorted()
        val body = lanes.joinToString(" ") { l ->
            val stages = Stage8283.values().mapNotNull { s -> counts["$l|${s.name}"]?.get()?.takeIf { it > 0L }?.let { "${s.name}=$it" } }
            "$l{${stages.joinToString(">")}}"
        }.ifBlank { "-" }
        return "funnel8283[$body overflow=${overflow.get()}]"
    }

    // ── V5.0.8289 — memory census and pressure trim (MemoryGuard7977 registry) ──
    fun seenCount8289(): Int = seen.size
    /** One remembered "LANE|STAGE|mint" key (~70 chars) and its set node. */
    fun estBytes8289(): Long = seen.size * 190L

    /**
     * CRITICAL pressure only: the remembered triples go (diagnostic: nothing decides on them). The per-stage counts stay;
     * a mint seen again afterwards is counted again, so after a critical trim the unique counts are an upper bound.
     */
    fun trim8289(critical: Boolean): Int {
        if (!critical) return 0
        val n = seen.size
        seen.clear()
        return n
    }

    internal fun clearForTest8283() { seen.clear(); counts.clear(); overflow.set(0L) }
}
