package com.lifecyclebot.engine

import org.json.JSONObject
import java.util.Locale

/**
 * LP ownership is a spending proof, independent from whether a quote can route.
 * Keep the provider's named finding and each pool's observation: neither a
 * positive score nor a locked side pool can erase an unlocked primary pool.
 */
object LiquidityOwnership8522 {
    private const val MIN_LOCKED_PCT = 70.0 // Existing TokenSafetyChecker live policy.
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

    /** Pure (V5.0.8937): the lock a pool counts at — the provider's [providerPct], or its own accounts' proven [chainPct] when higher. */
    fun lockedAt8937(providerPct: Double, chainPct: Double?): Double =
        if (chainPct != null && chainPct.isFinite() && chainPct in MIN_LOCKED_PCT..100.0 && chainPct > providerPct) chainPct else providerPct

    /** Pure (V5.0.8937): is every ordinary pool with depth among [pools] [proven] from its own accounts (and is there one)? */
    fun allDepthPoolsProven8937(pools: List<Pool>, proven: (Pool) -> Boolean): Boolean =
        pools.filter { !it.isCurve && it.depthUsd > 0.0 }.let { d -> d.isNotEmpty() && d.all(proven) }

    /** Account-origin reserve read, bounded by the existing 30-second execution window. */
    fun activeCurveVerified(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val real = try { com.lifecyclebot.engine.truth.CurveReserves7951.accountObservation8522(mint, nowMs)?.realSol }
            catch (_: Throwable) { null }
        return real != null && real.isFinite() && real >= LaneAgeLiquidityTruth8140.CURVE_EXIT_MIN_REAL_SOL_8319
    }

    /**
     * V5.0.8881 — (active, address) of the bonding curve holding [mint]'s liquidity in program custody, for the LP rule: a
     * pump.fun curve by its fresh account read ([activeCurveVerified], unchanged), else a Meteora DBC / Raydium LaunchLab curve
     * proven from its own account and vault (truth.CurveCustody8881). Never active for a [migrated] coin.
     */
    fun activeCurve8881(mint: String, migrated: Boolean, nowMs: Long = System.currentTimeMillis()): Pair<Boolean, String> {
        if (migrated) return false to ""
        if (activeCurveVerified(mint, nowMs))
            return true to (try { com.lifecyclebot.network.PumpCurveKeys7269.canonicalCurveKey7392(mint).orEmpty() } catch (_: Throwable) { "" })
        val c = try { com.lifecyclebot.engine.truth.CurveCustody8881.custody8881(mint, nowMs) } catch (_: Throwable) { null }
        return if (c != null) true to c.pool else false to ""
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
        chainLock8895: com.lifecyclebot.engine.truth.PoolLock8895.Lock8895? = null,
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
        // V5.0.8881 — the pool at the verified curve's own address IS that curve, whatever a provider labels it (a Meteora DBC
        // or Raydium LaunchLab curve is not "pump_fun"); the identity comes only from the account read, never from the label.
        fun curve8881(p: Pool): Boolean = p.isCurve || (activeCurveVerified && activeCurveAddress.isNotBlank() && p.address == activeCurveAddress)
        val ordinaryPrimary = primary?.takeUnless { curve8881(it) }
        val ordinarySelected = selected?.takeUnless { curve8881(it) }
        val curveOnlyEvidence = evidence.pools.none { !curve8881(it) && it.depthUsd > 0.0 }
        val selectedCurveIdentity = selectedPoolAddress.isBlank() ||
            (activeCurveAddress.isNotBlank() && selectedPoolAddress == activeCurveAddress)
        val curveExempt = activeCurveVerified && selectedCurveIdentity && ordinarySelected == null && ordinaryPrimary == null &&
            (selected?.let { curve8881(it) } == true || curveOnlyEvidence)
        // V5.0.8937 — a pool proven 70 %+ locked from its own accounts (PoolLock8895) is not under the lock, whatever the
        // provider's figure for it; the provider's named unlocked finding stands unless every pool with depth is so proven.
        val chainMint8937 = expectedMint.ifBlank { evidence.mint }
        fun chainProven8937(p: Pool): Boolean = p.address.isNotBlank() &&
            ((chainLock8895 != null && com.lifecyclebot.engine.truth.PoolLock8895.proves8895(chainLock8895, chainMint8937, p.address, nowMs)) ||
                (try { com.lifecyclebot.engine.truth.PoolLock8895.proof8895(chainMint8937, p.address, nowMs) != null } catch (_: Throwable) { false }))
        val chainAll8937 = allDepthPoolsProven8937(evidence.pools.filterNot { curve8881(it) }) { chainProven8937(it) }

        // Explicit adverse evidence always wins over a good numerical score or
        // a different pool. Curve custody is exempt only on actual curve proof.
        if (evidence.explicitUnlocked && !curveExempt && !chainAll8937) {
            return Result(Status.BLOCKED, "LP_UNLOCKED_CONFIRMED_8522", "provider reports unlocked liquidity; pool=$selectedPoolAddress")
        }
        val unsafePool = listOfNotNull(ordinaryPrimary, ordinarySelected).firstOrNull {
            it.lockedPct.isFinite() && it.lockedPct >= 0.0 && it.lockedPct < MIN_LOCKED_PCT && !chainProven8937(it)
        }
        if (unsafePool != null) {
            return Result(Status.BLOCKED, "LP_LOCK_BELOW_REQUIRED_8522",
                "pool=${unsafePool.address} locked=${unsafePool.lockedPct}% required=$MIN_LOCKED_PCT%")
        }
        // Old/paper reports may carry the number without the new pool record.
        // Their adverse observation must not disappear at the live boundary.
        val reportPool8937 = selected ?: primary
        if (!curveExempt && report.lpLockPct.isFinite() && report.lpLockPct >= 0.0 && report.lpLockPct < MIN_LOCKED_PCT &&
            !(reportPool8937 != null && chainProven8937(reportPool8937)) && !chainAll8937) {
            return Result(Status.BLOCKED, "LP_LOCK_BELOW_REQUIRED_8522", "reportedLocked=${report.lpLockPct}% required=$MIN_LOCKED_PCT%")
        }
        if (curveExempt) return Result(Status.PROVEN, "ACTIVE_CURVE_LP_NOT_APPLICABLE_8522", "fresh active curve reserves")
        if (whitelisted) return Result(Status.PROVEN, "VERIFIED_MAJOR_LP_POLICY_8522", "verified exact mint")
        // V5.0.8895 — the selected pool's lock read from its own accounts (PoolLock8895: Meteora DAMM v2 permanent lock, DLMM
        // positions locked 24 h+): 70 % or more answers what the provider report could not. Only an answer that would be
        // PENDING below changes; every BLOCKED above still refuses, and an unlocked or unread pool stays PENDING.
        val chainPool8895 = selected?.address?.takeIf { it.isNotBlank() } ?: selectedPoolAddress
        if (chainLock8895 != null && com.lifecyclebot.engine.truth.PoolLock8895.proves8895(chainLock8895,
                expectedMint.ifBlank { chainLock8895.mint }, chainPool8895, nowMs))
            return Result(Status.PROVEN, com.lifecyclebot.engine.truth.PoolLock8895.PROVEN_REASON_8895,
                "pool=$chainPool8895 venue=${chainLock8895.venue} locked=${"%.1f".format(chainLock8895.lockedPct)}% ${chainLock8895.detail}")

        val age = nowMs - report.checkedAt
        if (report.checkedAt <= 0L || age !in 0L..FRESH_MS) {
            return Result(Status.PENDING, "LP_OWNERSHIP_PROOF_PENDING_8522", "risk report stale or missing; ageMs=$age")
        }
        if (!evidence.riskInventoryComplete || selected == null || selected.address.isBlank()) {
            return Result(Status.PENDING, "LP_OWNERSHIP_PROOF_PENDING_8522", "selected pool has no complete ownership proof; pool=$selectedPoolAddress")
        }
        if (curve8881(selected)) {
            return Result(Status.PENDING, "LP_OWNERSHIP_PROOF_PENDING_8522", "curve label lacks current active-curve proof; pool=${selected.address}")
        }
        if (!selected.lockedPct.isFinite() || selected.lockedPct !in MIN_LOCKED_PCT..100.0) {
            return Result(Status.PENDING, "LP_OWNERSHIP_PROOF_PENDING_8522", "LP ownership unknown; pool=${selected.address}")
        }
        return Result(Status.PROVEN, "LP_OWNERSHIP_PROVEN_8522", "pool=${selected.address} locked=${selected.lockedPct}%")
    }
}
