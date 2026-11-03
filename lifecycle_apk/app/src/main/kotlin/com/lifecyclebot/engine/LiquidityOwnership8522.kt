package com.lifecyclebot.engine

import org.json.JSONObject
import java.util.Locale

/**
 * LP ownership is a spending proof, independent from whether a quote can route.
 * Keep the provider's named finding and each pool's observation: neither a
 * positive score nor a locked side pool can erase an unlocked primary pool.
 */
object LiquidityOwnership8522 {
    const val MIN_LOCKED_PCT = 70.0 // Existing TokenSafetyChecker live policy.
    private const val FRESH_MS = 120_000L

    data class Pool(
        val address: String,
        val marketType: String,
        val lockedPct: Double,
        val depthUsd: Double,
    ) {
        val isCurve: Boolean get() = marketType.equals("pump_fun", ignoreCase = true)
    }

    data class Evidence(
        val mint: String = "",
        val pools: List<Pool> = emptyList(),
        val explicitUnlocked: Boolean = false,
        val riskInventoryComplete: Boolean = false,
    )

    enum class Status { PROVEN, PENDING, BLOCKED }
    data class Result(val status: Status, val reason: String, val detail: String)

    /** Account-origin reserve read, bounded by the existing 30-second execution window. */
    fun activeCurveVerified(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val real = try { com.lifecyclebot.engine.truth.CurveReserves7951.accountObservation8522(mint, nowMs)?.realSol }
            catch (_: Throwable) { null }
        return real != null && real.isFinite() && real >= LaneAgeLiquidityTruth8140.CURVE_EXIT_MIN_REAL_SOL_8319
    }

    /** Pure parsing. A response for another mint never contributes safety evidence. */
    fun parse(report: JSONObject?, requestedMint: String): Evidence {
        if (report == null || requestedMint.isBlank()) return Evidence()
        val echoed = report.optString("mint", "")
        if (echoed.isNotBlank() && echoed != requestedMint) return Evidence()
        val risks = report.optJSONArray("risks")
        val unlocked = risks != null && (0 until risks.length()).any { i ->
            val name = risks.optJSONObject(i)?.optString("name", "")
                ?.lowercase(Locale.ROOT).orEmpty().replace('_', ' ').replace('-', ' ')
            (Regex("\\blp\\b").containsMatchIn(name) || name.contains("liquidity")) &&
                (name.contains("unlock") || name.contains("not locked"))
        }
        val markets = report.optJSONArray("markets")
        val pools = if (markets == null) emptyList() else (0 until markets.length()).mapNotNull { i ->
            val market = markets.optJSONObject(i) ?: return@mapNotNull null
            val lp = market.optJSONObject("lp")
            val locked = lp?.optDouble("lpLockedPct", Double.NaN)
                ?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: -1.0
            val baseUsd = lp?.optDouble("baseUSD", Double.NaN)?.takeIf { it.isFinite() && it >= 0.0 }
            val quoteUsd = lp?.optDouble("quoteUSD", Double.NaN)?.takeIf { it.isFinite() && it >= 0.0 }
            // Missing balances are not a zero-liquidity observation. Both sides
            // are required before this total can be used as pool-depth proof.
            val depth = if (baseUsd != null && quoteUsd != null && (baseUsd + quoteUsd).isFinite())
                baseUsd + quoteUsd else -1.0
            Pool(
                address = market.optString("pubkey", "").ifBlank { market.optString("address", "") },
                marketType = market.optString("marketType", ""),
                lockedPct = locked,
                depthUsd = depth,
            )
        }
        return Evidence(requestedMint, pools, unlocked, risks != null)
    }

    /** The exact pool's dated depth. Missing balances remain unknown; a new zero cannot borrow older depth. */
    fun observedPoolDepth8522(report: SafetyReport, selectedPoolAddress: String, nowMs: Long,
                             expectedMint: String = ""): Double? {
        val evidence = report.lpOwnership8522
        if (expectedMint.isNotBlank() && evidence.mint.isNotBlank() && evidence.mint != expectedMint) return null
        val pool = selectedPoolAddress.ifBlank {
            evidence.pools.filter { it.depthUsd > 0.0 }.maxByOrNull { it.depthUsd }?.address
                ?: report.liquidityObservedPool8522
        }
        if (pool.isBlank()) return null
        val direct = report.liquidityObservedUsd8522.takeIf {
            report.liquidityObservedPool8522 == pool && report.liquidityObservedAtMs8522 > 0L &&
                nowMs - report.liquidityObservedAtMs8522 in 0L..FRESH_MS && it.isFinite() && it >= 0.0
        }
        if (direct != null) return direct
        if (report.checkedAt <= 0L || nowMs - report.checkedAt !in 0L..FRESH_MS) return null
        return evidence.pools.firstOrNull { it.address == pool }?.depthUsd?.takeIf { it.isFinite() && it >= 0.0 }
    }

    /**
     * [activeCurveVerified] must come from a fresh, active on-chain curve with
     * real exit reserves. A venue name, mint suffix or historical launch source
     * is never sufficient. A selected ordinary AMM still needs its own proof.
     * Missing proof is retryable PENDING, not a token blacklist.
     */
    fun assess(
        report: SafetyReport,
        selectedPoolAddress: String,
        activeCurveVerified: Boolean,
        whitelisted: Boolean = false,
        activeCurveAddress: String = "",
        nowMs: Long = System.currentTimeMillis(),
        expectedMint: String = "",
    ): Result {
        val evidence = report.lpOwnership8522
        val hasProviderEvidence = evidence.mint.isNotBlank() || evidence.riskInventoryComplete ||
            evidence.pools.isNotEmpty() || evidence.explicitUnlocked
        if (expectedMint.isNotBlank() && hasProviderEvidence && evidence.mint != expectedMint) {
            return Result(Status.PENDING, "LP_OWNERSHIP_PROOF_PENDING_8522", "ownership evidence belongs to another or unknown mint")
        }
        val primary = evidence.pools.filter { it.depthUsd > 0.0 }.maxByOrNull { it.depthUsd }
        val selected = if (selectedPoolAddress.isBlank()) primary else
            evidence.pools.firstOrNull { it.address.isNotBlank() && it.address == selectedPoolAddress }
        val ordinaryPrimary = primary?.takeUnless { it.isCurve }
        val ordinarySelected = selected?.takeUnless { it.isCurve }
        val curveOnlyEvidence = evidence.pools.none { !it.isCurve && it.depthUsd > 0.0 }
        val selectedCurveIdentity = selectedPoolAddress.isBlank() ||
            (activeCurveAddress.isNotBlank() && selectedPoolAddress == activeCurveAddress)
        val curveExempt = activeCurveVerified && selectedCurveIdentity && ordinarySelected == null && ordinaryPrimary == null &&
            (selected?.isCurve == true || curveOnlyEvidence)

        // Explicit adverse evidence always wins over a good numerical score or
        // a different pool. Curve custody is exempt only on actual curve proof.
        if (evidence.explicitUnlocked && !curveExempt) {
            return Result(Status.BLOCKED, "LP_UNLOCKED_CONFIRMED_8522", "provider reports unlocked liquidity; pool=$selectedPoolAddress")
        }
        val unsafePool = listOfNotNull(ordinaryPrimary, ordinarySelected).firstOrNull {
            it.lockedPct.isFinite() && it.lockedPct >= 0.0 && it.lockedPct < MIN_LOCKED_PCT
        }
        if (unsafePool != null) {
            return Result(Status.BLOCKED, "LP_LOCK_BELOW_REQUIRED_8522",
                "pool=${unsafePool.address} locked=${unsafePool.lockedPct}% required=$MIN_LOCKED_PCT%")
        }
        // Old/paper reports may carry the number without the new pool record.
        // Their adverse observation must not disappear at the live boundary.
        if (!curveExempt && report.lpLockPct.isFinite() && report.lpLockPct >= 0.0 && report.lpLockPct < MIN_LOCKED_PCT) {
            return Result(Status.BLOCKED, "LP_LOCK_BELOW_REQUIRED_8522", "reportedLocked=${report.lpLockPct}% required=$MIN_LOCKED_PCT%")
        }
        if (curveExempt) return Result(Status.PROVEN, "ACTIVE_CURVE_LP_NOT_APPLICABLE_8522", "fresh active curve reserves")
        if (whitelisted) return Result(Status.PROVEN, "VERIFIED_MAJOR_LP_POLICY_8522", "verified exact mint")

        val age = nowMs - report.checkedAt
        if (report.checkedAt <= 0L || age !in 0L..FRESH_MS) {
            return Result(Status.PENDING, "LP_OWNERSHIP_PROOF_PENDING_8522", "risk report stale or missing; ageMs=$age")
        }
        if (!evidence.riskInventoryComplete || selected == null || selected.address.isBlank()) {
            return Result(Status.PENDING, "LP_OWNERSHIP_PROOF_PENDING_8522", "selected pool has no complete ownership proof; pool=$selectedPoolAddress")
        }
        if (selected.isCurve) {
            return Result(Status.PENDING, "LP_OWNERSHIP_PROOF_PENDING_8522", "curve label lacks current active-curve proof; pool=${selected.address}")
        }
        if (!selected.lockedPct.isFinite() || selected.lockedPct !in MIN_LOCKED_PCT..100.0) {
            return Result(Status.PENDING, "LP_OWNERSHIP_PROOF_PENDING_8522", "LP ownership unknown; pool=${selected.address}")
        }
        return Result(Status.PROVEN, "LP_OWNERSHIP_PROVEN_8522", "pool=${selected.address} locked=${selected.lockedPct}%")
    }
}
