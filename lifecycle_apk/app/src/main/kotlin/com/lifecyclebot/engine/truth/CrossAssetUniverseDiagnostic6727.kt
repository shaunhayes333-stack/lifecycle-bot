package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6727 — §CROSS_ASSET_UNIVERSE_DIAGNOSTIC.
 *
 * Operator diagnostic from 6726: "Crypto Universe is also heavily
 * choking before canonical execution. It finds 1,015 identities, but
 * only 219 reach CryptoBrain and 140 reach V3/FDG. Only CRYPTO_ALT is
 * actually alive in the cross-asset producer section; stocks, forex,
 * commodities, metals and perps all show started=0."
 *
 * This authority owns the single-source-of-truth for cross-asset
 * producer liveness so downstream reporting/UI reads a canonical
 * verdict without re-implementing the counter math. Producers call
 * `recordStart(track)` on producer bring-up, `recordCandidate(track)`
 * per emitted identity, and `recordAdvance(track, "cryptobrain")` /
 * `recordAdvance(track, "v3fdg")` / `recordAdvance(track, "canonical")`
 * per successful next-stage handoff.
 *
 * Snapshot returns the funnel per track so the operator dump reveals
 * exactly which track is starved AND at which stage.
 */
object CrossAssetUniverseDiagnostic6727 {

    data class Funnel(
        val track: String,          // CRYPTO_ALT | STOCKS | FOREX | COMMODITIES | METALS | PERPS
        val started: Boolean,
        val candidatesEmitted: Long,
        val advancedToCryptoBrain: Long,
        val advancedToV3Fdg: Long,
        val advancedToCanonical: Long,
        val terminalStaleShared: Long,
        val terminalSpecialistSilence: Long,
        val terminalSizeFloor: Long,
        val terminalExposureCap: Long,
        val terminalPriceUnavailable: Long,
    )

    private data class Counters(
        val started: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
        val candidatesEmitted: AtomicLong = AtomicLong(0),
        val advancedToCryptoBrain: AtomicLong = AtomicLong(0),
        val advancedToV3Fdg: AtomicLong = AtomicLong(0),
        val advancedToCanonical: AtomicLong = AtomicLong(0),
        val terminalStaleShared: AtomicLong = AtomicLong(0),
        val terminalSpecialistSilence: AtomicLong = AtomicLong(0),
        val terminalSizeFloor: AtomicLong = AtomicLong(0),
        val terminalExposureCap: AtomicLong = AtomicLong(0),
        val terminalPriceUnavailable: AtomicLong = AtomicLong(0),
    )

    private val byTrack = java.util.concurrent.ConcurrentHashMap<String, Counters>()

    private fun normTrack(track: String): String =
        track.trim().uppercase().take(20).ifBlank { "UNKNOWN" }

    fun recordStart(track: String) {
        val t = normTrack(track)
        byTrack.computeIfAbsent(t) { Counters() }.started.set(true)
        try { PipelineHealthCollector.labelInc("CROSS_ASSET_PRODUCER_STARTED_6727_$t") } catch (_: Throwable) {}
    }

    fun recordCandidate(track: String) {
        val t = normTrack(track)
        byTrack.computeIfAbsent(t) { Counters() }.candidatesEmitted.incrementAndGet()
    }

    fun recordAdvance(track: String, stage: String) {
        val t = normTrack(track)
        val c = byTrack.computeIfAbsent(t) { Counters() }
        when (stage.trim().lowercase()) {
            "cryptobrain" -> c.advancedToCryptoBrain.incrementAndGet()
            "v3fdg" -> c.advancedToV3Fdg.incrementAndGet()
            "canonical" -> c.advancedToCanonical.incrementAndGet()
        }
    }

    fun recordTerminal(track: String, reason: String) {
        val t = normTrack(track)
        val c = byTrack.computeIfAbsent(t) { Counters() }
        when (reason.trim().uppercase()) {
            "STALE_SHARED", "STALE_SHARED_INTELLIGENCE" -> c.terminalStaleShared.incrementAndGet()
            "SPECIALIST_SILENCE" -> c.terminalSpecialistSilence.incrementAndGet()
            "SIZE_FLOOR", "SIZE_BELOW_FLOOR" -> c.terminalSizeFloor.incrementAndGet()
            "EXPOSURE_CAP" -> c.terminalExposureCap.incrementAndGet()
            "PRICE_UNAVAILABLE", "UNAVAILABLE_PRICE" -> c.terminalPriceUnavailable.incrementAndGet()
        }
    }

    fun snapshot(): List<Funnel> = byTrack.entries.sortedBy { it.key }.map { (t, c) ->
        Funnel(
            track = t,
            started = c.started.get(),
            candidatesEmitted = c.candidatesEmitted.get(),
            advancedToCryptoBrain = c.advancedToCryptoBrain.get(),
            advancedToV3Fdg = c.advancedToV3Fdg.get(),
            advancedToCanonical = c.advancedToCanonical.get(),
            terminalStaleShared = c.terminalStaleShared.get(),
            terminalSpecialistSilence = c.terminalSpecialistSilence.get(),
            terminalSizeFloor = c.terminalSizeFloor.get(),
            terminalExposureCap = c.terminalExposureCap.get(),
            terminalPriceUnavailable = c.terminalPriceUnavailable.get(),
        )
    }
}
