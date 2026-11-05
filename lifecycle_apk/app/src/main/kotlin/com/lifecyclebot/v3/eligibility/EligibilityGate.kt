package com.lifecyclebot.v3.eligibility

import com.lifecyclebot.v3.core.TradingConfigV3
import com.lifecyclebot.v3.scanner.CandidateSnapshot

/**
 * V3 Eligibility Result
 */
data class EligibilityResult(
    val passed: Boolean,
    val reason: String
) {
    companion object {
        fun pass() = EligibilityResult(true, "PASS")
        fun fail(reason: String) = EligibilityResult(false, reason)
    }
}

/**
 * V3 Cooldown Manager
 */
class CooldownManager {
    private val cooldowns = mutableMapOf<String, Long>()

    fun setCooldown(mint: String, untilMs: Long) {
        cooldowns[mint] = untilMs
    }

    fun isCoolingDown(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val until = cooldowns[mint] ?: return false
        return nowMs < until
    }

    fun clearExpired(nowMs: Long = System.currentTimeMillis()) {
        cooldowns.entries.removeIf { it.value < nowMs }
    }
}

/**
 * V3 D-Grade Looper Tracker
 *
 * Prevents repeated D-grade proposals from clogging the pipeline.
 * Tracks tokens that have been proposed with:
 *   - quality = D
 *   - confidence < 15
 *
 * If a token has been proposed repeatedly in the last 2 minutes with these
 * characteristics, it gets blocked from re-proposal until either:
 *   - Quality improves above D
 *   - Confidence rises to 15+
 *   - 2 minutes pass since last proposal
 */
object DGradeLooperTracker {

    private data class ProposalRecord(
        val timestamp: Long,
        val quality: String,
        val confidence: Int
    )

    // V5.0.8535 §P2-02 — `mutableMapOf` + `MutableList` were read and mutated from every FDG thread with no lock
    // (8529: four DGradeLooperTracker exceptions). One lock, a bounded ring per mint, the same 2-minute / 10-proposal
    // semantics; the clocks are injectable so the window is testable.
    private val recentProposals = HashMap<String, ArrayDeque<ProposalRecord>>()
    private val lock8535 = Any()
    private const val MAX_PER_MINT_8535 = 64
    private const val MAX_MINTS_8535 = 4_096

    private const val WINDOW_MS = 2 * 60 * 1000L          // 2 minute window
    private const val MAX_D_GRADE_PROPOSALS = 10          // Max D-grade low-conf proposals per window
    private const val D_GRADE_CONF_THRESHOLD = 15         // D-grade looper threshold

    /**
     * Record a proposal for a token.
     */
    fun recordProposal(mint: String, quality: String, confidence: Int, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock8535) {
            cleanOldRecords(nowMs)
            if (recentProposals.size >= MAX_MINTS_8535 && !recentProposals.containsKey(mint)) return
            val records = recentProposals.getOrPut(mint) { ArrayDeque() }
            records.addLast(ProposalRecord(timestamp = nowMs, quality = quality, confidence = confidence))
            while (records.size > MAX_PER_MINT_8535) records.removeFirst()
        }
    }

    /**
     * Check if a D-grade token should be blocked from re-proposal.
     */
    fun shouldBlockDGradeLooper(mint: String, quality: String, confidence: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        // Only applies to D-grade with low confidence
        if (quality != "D" || confidence >= D_GRADE_CONF_THRESHOLD) {
            return false
        }
        synchronized(lock8535) {
            cleanOldRecords(nowMs)
            val records = recentProposals[mint] ?: return false
            val recentDGradeCount = records.count { it.quality == "D" && it.confidence < D_GRADE_CONF_THRESHOLD }
            return recentDGradeCount >= MAX_D_GRADE_PROPOSALS
        }
    }

    /** V5.0.8535 — the proposals of [mint] inside the window (tests / diagnostics). */
    fun proposalCount8535(mint: String, nowMs: Long = System.currentTimeMillis()): Int = synchronized(lock8535) {
        cleanOldRecords(nowMs); recentProposals[mint]?.size ?: 0
    }

    /**
     * Backward-compatible alias in case other files still call the old method name.
     */
    fun shouldBlockCGradeLooper(mint: String, quality: String, confidence: Int): Boolean {
        return shouldBlockDGradeLooper(mint, quality, confidence)
    }

    /** Caller holds [lock8535]. */
    private fun cleanOldRecords(now: Long) {
        val cutoff = now - WINDOW_MS
        val it = recentProposals.entries.iterator()
        while (it.hasNext()) {
            val records = it.next().value
            while (records.isNotEmpty() && records.first().timestamp < cutoff) records.removeFirst()
            if (records.isEmpty()) it.remove()
        }
    }

    /**
     * Clear all tracking (for testing)
     */
    fun clear() {
        synchronized(lock8535) { recentProposals.clear() }
    }
}

/**
 * Backward-compatible alias in case the rest of the codebase still references
 * the old object name.
 */
typealias CGradeLooperTracker = DGradeLooperTracker

/**
 * V3 Exposure Guard
 */
class ExposureGuard(
    private val maxOpenPositions: Int = 5,
    private val maxExposurePct: Double = 0.70
) {
    private val openMints = mutableSetOf<String>()

    var currentExposurePct: Double = 0.0

    fun openPosition(mint: String) {
        openMints += mint
    }

    fun closePosition(mint: String) {
        openMints -= mint
    }

    fun isTokenAlreadyOpen(mint: String): Boolean {
        // V5.9.495z6 — operator spec F: ALREADY_OPEN must consult ALL state
        // sources (open position by mint OR wallet token raw balance by mint).
        // V5.9.495z52 — operator directive (5th escalation): rugged-dust ATAs
        // in the wallet kept tokens like ROFL flagged ALREADY_OPEN forever
        // even though `positions=0`. Replaced the unconditional
        // `WalletReconciler.knownMintContains(mint)` (which returns true for
        // ANY SPL ATA including dust) with a TokenLifecycleTracker lookup
        // that only returns true if the bot itself has a confirmed
        // non-dust live meme position for THIS mint.
        if (mint in openMints) return true
        return try {
            val r = com.lifecyclebot.engine.TokenLifecycleTracker.get(mint)
                ?: return false
            !r.buyTx.isNullOrBlank() &&
                r.currentWalletTokenQty > 0.01 &&
                r.status != com.lifecyclebot.engine.TokenLifecycleTracker.Status.SELL_CONFIRMED &&
                r.status != com.lifecyclebot.engine.TokenLifecycleTracker.Status.CLEARED &&
                r.status != com.lifecyclebot.engine.TokenLifecycleTracker.Status.RECONCILE_FAILED &&
                r.venue.lowercase().trim() !in setOf("cryptoalt", "crypto", "perps",
                    "markets", "stocks", "tokenized_stocks", "auto-import")
        } catch (_: Throwable) { false }
    }

    fun isGlobalExposureMaxed(): Boolean {
        // V5.9.1577 — live unchoke: exposure PCT is a sizing/risk signal, not an
        // eligibility veto. Snapshot 23:02 showed GLOBAL_EXPOSURE_MAX_PCT(88%/70%)
        // killing V3 discovery while the wallet still had live tradeable SOL and
        // downstream SmartSizer/CashGen already capped orders to wallet-reserve.
        // Keep the slot-count guard (real capacity), but do not make percentage
        // exposure a terminal V3 reject. Lane/FDG/Executor size caps still shape risk.
        if (com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() && com.lifecyclebot.engine.FreeRangeMode.isWideOpen()) return false
        return openMints.size >= maxOpenPositions
    }

    fun openCount(): Int = openMints.size

    // V5.9.99: expose cap values so EligibilityGate can log which leg fired
    fun maxSlots(): Int = maxOpenPositions
    fun maxPct(): Double = maxExposurePct
}

/**
 * V3 Eligibility Gate
 * ONLY hard blocks - everything else is scoring
 */
class EligibilityGate(
    private val config: TradingConfigV3,
    private val cooldownManager: CooldownManager,
    private val exposureGuard: ExposureGuard
) {
    fun evaluate(candidate: CandidateSnapshot): EligibilityResult {
        // V5.0.6536 §PROVIDER_DEGRADATION_PROTECTION — the operator directive
        // is: "provider degradation must not hard-starve token hydration when
        // alternative routes are healthy". Previously any candidate whose
        // liquidity providers were down would emit ZERO_LIQUIDITY as a HARD
        // fail (permanent block for the discovery cycle), effectively
        // converting "provider absent" into "false zero-liquidity truth".
        // Now: when the two primary liquidity/mcap providers (Birdeye,
        // CoinGecko) are BOTH in a degraded state (auth-terminal, rate
        // limited, or in a server cool-down) AND we see zero, we treat it
        // as HYDRATION_DEFERRED (soft, retry-eligible) so the alternate
        // hydration paths (DexScreener, Pump, Helius) get a fair shot on
        // the next cycle instead of being amputated by a false-zero read.
        //
        // Zero liquidity = can't trade
        if (candidate.liquidityUsd <= 0.0) {
            val degraded6536 = try {
                val cb = com.lifecyclebot.engine.truth.ProviderCircuitBreaker6402
                val birdeyeBad = cb.isAuthTerminal(
                    com.lifecyclebot.engine.truth.ProviderCircuitBreaker6402.Provider.BIRDEYE
                ) || cb.isRateLimited(
                    com.lifecyclebot.engine.truth.ProviderCircuitBreaker6402.Provider.BIRDEYE
                ) || cb.isServerCoolingDown(
                    com.lifecyclebot.engine.truth.ProviderCircuitBreaker6402.Provider.BIRDEYE
                )
                val geckoBad = cb.isAuthTerminal(
                    com.lifecyclebot.engine.truth.ProviderCircuitBreaker6402.Provider.COINGECKO
                ) || cb.isRateLimited(
                    com.lifecyclebot.engine.truth.ProviderCircuitBreaker6402.Provider.COINGECKO
                ) || cb.isServerCoolingDown(
                    com.lifecyclebot.engine.truth.ProviderCircuitBreaker6402.Provider.COINGECKO
                )
                birdeyeBad && geckoBad
            } catch (_: Throwable) { false }
            return if (degraded6536) {
                try {
                    com.lifecyclebot.engine.PipelineHealthCollector
                        .labelInc("ELIGIBILITY_HYDRATION_DEFERRED_PROVIDERS_DEGRADED_6536")
                    com.lifecyclebot.engine.ForensicLogger.lifecycle(
                        "ELIGIBILITY_HYDRATION_DEFERRED_PROVIDERS_DEGRADED_6536",
                        "mint=${candidate.mint} symbol=${candidate.symbol} " +
                            "expected=alt_providers_hydrate_liquidity observed=birdeye+coingecko_both_degraded " +
                            "action=defer_not_hard_zero",
                    )
                } catch (_: Throwable) {}
                EligibilityResult.fail("HYDRATION_DEFERRED_PROVIDERS_DEGRADED_6536")
            } else {
                EligibilityResult.fail("ZERO_LIQUIDITY")
            }
        }

        // A lower bound cannot satisfy a launch window. Only proven-established
        // candidates with an actual established-lane pool may score without exact birth.
        if (!candidate.hasScorableAge8527) return EligibilityResult.fail("BIRTH_METADATA_HYDRATING_7441")
        val ageForWindow8527 = candidate.provenMinimumAgeMinutes8527
            ?: return EligibilityResult.fail("BIRTH_METADATA_HYDRATING_7441")

        // Too old = stale opportunity — V5.0.8140: for a LAUNCH only. The window (live 6 h) is a
        // launch-lane rule; an old token with a real established-lane pool is scored, not refused
        // (CORE / QUALITY / BLUECHIP / DIP_HUNTER / TREASURY / MOONSHOT need that score). SHITCOIN
        // applies the same window on true age itself (LaneAgeLiquidityTruth8140).
        if (com.lifecyclebot.engine.LaneAgeLiquidityScope8140.v3AgeWindowRefuses8140(ageForWindow8527, config.maxTokenAgeMinutes, candidate.liquidityUsd)) {
            return EligibilityResult.fail("TOO_OLD")
        }
        if (ageForWindow8527 > config.maxTokenAgeMinutes) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("V3_AGE_WINDOW_ESTABLISHED_SCOPE_SCORED_8140") } catch (_: Throwable) {}
        }

        // Below min liquidity = too risky — V5.0.8418: the $500 non-curve floor; an exit-safe active pump.fun curve
        // (owner's 8320 rule: >= 0.5 real SOL observed inside the curve TTL) is scored, as every launch lane already does.
        if (com.lifecyclebot.engine.IntakeUnknownTruth8418.v3FloorRefuses8418(candidate.mint, candidate.liquidityUsd, config.minLiquidityUsd)) {
            return EligibilityResult.fail("LOW_LIQUIDITY")
        }

        // On cooldown = wait
        if (cooldownManager.isCoolingDown(candidate.mint)) {
            return EligibilityResult.fail("COOLDOWN")
        }

        // Already open = no stacking
        if (exposureGuard.isTokenAlreadyOpen(candidate.mint)) {
            return EligibilityResult.fail("ALREADY_OPEN")
        }

        // Global exposure maxed
        if (exposureGuard.isGlobalExposureMaxed()) {
            // V5.9.99: emit which leg of the cap fired so live-mode users
            // can see whether it's the slot count or the exposure pct that's
            // blocking entries — previously both triggered the same opaque
            // GLOBAL_EXPOSURE_MAX message.
            val reason = if (exposureGuard.openCount() >= exposureGuard.maxSlots())
                "GLOBAL_EXPOSURE_MAX_SLOTS(${exposureGuard.openCount()}/${exposureGuard.maxSlots()})"
            else
                "GLOBAL_EXPOSURE_MAX_PCT(${(exposureGuard.currentExposurePct*100).toInt()}%/${(exposureGuard.maxPct()*100).toInt()}%)"
            return EligibilityResult.fail(reason)
        }

        return EligibilityResult.pass()
    }
}