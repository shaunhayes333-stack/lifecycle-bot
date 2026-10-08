package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.7868 — the wallet/capacity version a live order size was sealed against.
 *
 * 5.0.7867 live: SEALED_SIZE_EXCEEDS_CURRENT_CAP_7835=44 was the dominant live
 * buy failure while the resolver printed final=0.04272 exec=true. FDG resolved
 * the size from WalletManager.cachedSolBalance() and the SOL price at seal time;
 * liveBuy then recomputed routeAwareSpendCap7842 from its own wallet read and the
 * current price and rejected the immutable size against that new, mutable cap
 * (the route floor lift flips on a one-unit wallet when the share guard or the
 * USD route minimum moves a hair).
 *
 * Authority rule: one final size, sealed with the capacity it was proven
 * against. At execution the sealed size is honoured while that capacity still
 * holds (the wallet still funds it above the reserve, no material wallet or SOL
 * price move). A material change invalidates the intent explicitly
 * (SEALED_INTENT_INVALIDATED_CAPACITY_CHANGED_7868) so the next cycle reseals
 * once on the new snapshot. No size is ever inflated; an unfundable size is
 * still refused.
 */
internal object WalletCapacitySeal7868 {
    data class Seal(val sizeSol: Double, val walletSol: Double, val solUsd: Double, val atMs: Long)

    private const val TTL_MS = 10L * 60_000L
    /** Wallet drop beyond this fraction of the sealed wallet is a material capacity change. */
    internal const val MAX_WALLET_DROP_FRAC = 0.15
    /** SOL/USD move beyond this fraction re-prices the route minimum materially. */
    internal const val MAX_SOL_USD_MOVE_FRAC = 0.07

    private val seals = ConcurrentHashMap<String, Seal>()

    fun record(mint: String, sizeSol: Double, walletSol: Double, solUsd: Double, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank() || !sizeSol.isFinite() || sizeSol <= 0.0 || !walletSol.isFinite() || walletSol <= 0.0) return
        seals[mint] = Seal(sizeSol, walletSol, solUsd, nowMs)
        reservations[mint] = sizeSol to nowMs
        if (seals.size > 2_000) seals.entries.removeIf { nowMs - it.value.atMs > TTL_MS }
    }

    // ── V5.0.7868 capital reservation ─────────────────────────────────────
    // 5.0.7868 live: SEALED_INTENT_INVALIDATED_CAPACITY_CHANGED_7868=11 — two
    // candidates each sealed a full executable unit against the same wallet
    // (0.0734 SOL, unit 0.0427), the first spent it, the second was invalidated.
    // A sealed live size now RESERVES its SOL until the buy reaches pending-proof
    // (the swap has left the wallet), fails, or RESERVATION_TTL_MS lapses; the
    // next seal is sized against wallet minus other candidates' reservations.
    internal const val RESERVATION_TTL_MS = 90_000L
    private val reservations = ConcurrentHashMap<String, Pair<Double, Long>>()

    /** Pure: SOL reserved by OTHER live seals still inside the TTL. */
    internal fun reservedExcluding(all: Map<String, Pair<Double, Long>>, mint: String, nowMs: Long): Double =
        all.entries.filter { it.key != mint && nowMs - it.value.second <= RESERVATION_TTL_MS }.sumOf { it.value.first }

    /** Wallet SOL this mint may size against: wallet minus other mints' live reservations. */
    fun freeCashFor(mint: String, walletSol: Double, nowMs: Long = System.currentTimeMillis()): Double {
        if (!walletSol.isFinite()) return walletSol
        // Only a candidate with a live sealed BUY intent holds capital.
        reservations.entries.removeIf { (m, v) ->
            nowMs - v.second > RESERVATION_TTL_MS ||
                (try { ExecutableOpenGate.activeExecutionIntent6519("LIVE", m) } catch (_: Throwable) { null }) == null
        }
        val reserved = reservedExcluding(reservations, mint, nowMs)
        if (reserved > 0.0) try { PipelineHealthCollector.labelInc("LIVE_CAPITAL_RESERVED_FOR_SEALED_INTENT_7868") } catch (_: Throwable) {}
        return (walletSol - reserved).coerceAtLeast(0.0)
    }

    /** The swap left the wallet (pending proof), or the buy terminated: release. */
    fun release(mint: String) { if (mint.isNotBlank()) reservations.remove(mint) }

    /** Pure: is the capacity at execution materially different from the capacity at seal? */
    internal fun materiallyChanged(seal: Seal, walletNow: Double, solUsdNow: Double): Boolean {
        if (!walletNow.isFinite() || walletNow < seal.walletSol * (1.0 - MAX_WALLET_DROP_FRAC)) return true
        if (seal.solUsd.isFinite() && seal.solUsd > 0.0 && solUsdNow.isFinite() && solUsdNow > 0.0 &&
            kotlin.math.abs(solUsdNow / seal.solUsd - 1.0) > MAX_SOL_USD_MOVE_FRAC) return true
        return false
    }

    /** Pure decision for the live spend boundary (seal may be null). */
    internal fun decide(
        seal: Seal?, sol: Double, walletNow: Double, solUsdNow: Double, reserveSol: Double,
        baseRefusal: String?, nowMs: Long,
    ): String? {
        // The dynamic route-cap check is not the wallet affordability authority.
        // Even if it reports PASS, never execute a sealed amount that no longer
        // fits the current wallet after the mandatory SOL reserve.
        if (!sol.isFinite() || sol <= 0.0 || !walletNow.isFinite() ||
            !reserveSol.isFinite() || reserveSol < 0.0 ||
            sol > (walletNow - reserveSol) + 1e-12
        ) return "SEALED_SIZE_EXCEEDS_CURRENT_CAP_7835"
        if (baseRefusal == null) return null
        if (baseRefusal != "SEALED_SIZE_EXCEEDS_CURRENT_CAP_7835" && baseRefusal != "SEALED_SIZE_BELOW_CURRENT_MINIMUM_7835") return baseRefusal
        if (seal == null || nowMs - seal.atMs > TTL_MS || kotlin.math.abs(seal.sizeSol - sol) > 1e-9) return baseRefusal
        if (materiallyChanged(seal, walletNow, solUsdNow)) return "SEALED_INTENT_INVALIDATED_CAPACITY_CHANGED_7868"
        if (sol > (walletNow - reserveSol) + 1e-12) return baseRefusal
        return null
    }

    /** The live spend boundary: the sealed size against the capacity it was sealed on. */
    fun executionRefusal(mint: String, sol: Double, walletNow: Double, capNow: Double, minNow: Double): String? {
        val base = SealedExecutionSize7835.boundsRefusal(sol, capNow, minNow)
        val verdict = decide(
            seals[mint], sol, walletNow, WalletManager.lastKnownSolPrice,
            com.lifecyclebot.engine.truth.LiveSpendReserveAuthority7255.RESERVE_SOL, base, System.currentTimeMillis(),
        )
        if (base != null && verdict == null) try {
            PipelineHealthCollector.labelInc("SEALED_SIZE_HONOURED_AT_SEAL_CAPACITY_7868")
            ForensicLogger.lifecycle(
                "SEALED_SIZE_HONOURED_AT_SEAL_CAPACITY_7868",
                "mint=${mint.take(10)} sol=${"%.6f".format(sol)} capNow=${"%.6f".format(capNow)} minNow=${"%.6f".format(minNow)} wallet=${"%.6f".format(walletNow)} wouldHave=$base",
            )
        } catch (_: Throwable) {}
        if (verdict == "SEALED_INTENT_INVALIDATED_CAPACITY_CHANGED_7868") try {
            PipelineHealthCollector.labelInc(verdict)
        } catch (_: Throwable) {}
        if (verdict != null) release(mint)
        return verdict
    }

    /** Route-floor bridge telemetry for the live spend boundary (moved out of liveBuy). */
    fun noteRouteBridge(mint: String, cap: com.lifecyclebot.engine.truth.LiveRiskPolicy7807.RouteAwareSpendCap7842) {
        if (!cap.routeFloorLifted) return
        try {
            PipelineHealthCollector.labelInc("EXEC_ROUTE_MIN_BRIDGED_WALLET_CAP_7842")
            ForensicLogger.lifecycle(
                "EXEC_ROUTE_MIN_BRIDGED_WALLET_CAP_7842",
                "mint=${mint.take(10)} configured=${"%.6f".format(cap.configuredWalletCapSol)} " +
                    "routeMin=${"%.6f".format(cap.routableMinSol)} cap=${"%.6f".format(cap.maxSpendableSol)}",
            )
        } catch (_: Throwable) {}
    }
}
