package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8308 — A BOT BUY WHOSE CHAIN PROOF ARRIVES LATE IS STILL A BOT BUY.
 *
 * 5.0.8306 live: the session's two worst losses (EhuiZV, 2bNrYy: -0.0267 of the session's -0.0316 SOL) sit in
 * "Finalized ID reconcile" as ECONOMICS_QUARANTINED, so no learner ever saw them; MemeCausalLearning6568 then retired
 * the closes (no entry snapshot) and ExactStrategyPerformance7429 refused them (no entry identity).
 *
 * Why. FinalizedLearningReconciler7423 calls a CLOSED canonical row with a non-blank quarantineReason
 * ECONOMICS_QUARANTINED. The one producer of such a row is CanonicalPositionAuthority6441.applyProtectiveSellFill7807:
 * a confirmed sell on a mint owned only by a funded protective BASIS_UNCERTAIN_7807 row closes it with NO economics
 * ("no PnL, no learning"). A live bot buy becomes such a row whenever its verification path could not commit the fill
 * at once (Executor.protectLandedLiveBuy7807: TX_TRUTH_DEFERRED when getTransaction lagged past 15 s, PROOF_INCOMPLETE,
 * INVALID_PROOF, ENTRY_PRICE_PROOF_DEFERRED, CANONICAL_COMMIT_REJECTED_6486) — and nothing retried the proof. The row
 * also never got an EntryStrategySnapshot (written only after a successful commit), which is exactly what the two
 * learners refuse on. A fast rug (95 s) closes the row long before anyone looks again.
 *
 * Fix (no data invented, nothing loosened):
 *  - [note8308]: the protective path records the bot's own buy signature; the proof is retried off-thread on a short
 *    ladder (TradeVerifier.verifyBuy: chain tx meta for the owner's token delta, SOL spent and fee — the same reader
 *    the live path uses).
 *  - [beforeProtectiveSell8308]: SellFinalizationCoordinator asks once more, bounded, right before it would close the
 *    protective row without economics.
 *  - a LANDED proof promotes the SAME positionId through CanonicalPositionAuthority6441.promotePendingToOpen (the path a
 *    verified fill already takes for BASIS_UNCERTAIN_7807 rows) with the tx-meta quantity, cost and fee, and the entry
 *    USD/token from that cost x the SOL/USD observed when the buy landed. Then the normal sell path books real
 *    economics, publishes the close, and LearningQuarantineGate6470 releases the BASIS_UNCERTAIN hold for this id.
 *  - only a row that has NEVER sold is promoted: a protective row already partly sold has lost its slice proceeds (the
 *    protective close books none), so its economics are genuinely unprovable and it stays quarantined.
 */
object ProtectiveBasisProof8308 {

    private val RETRY_DELAYS_MS_8308 = longArrayOf(4_000L, 12_000L, 30_000L, 75_000L, 150_000L)
    private const val BACKGROUND_VERIFY_MS_8308 = 12_000L
    private const val PRE_CLOSE_VERIFY_MS_8308 = 5_000L

    private class Pending8308(
        val positionId: String, val mint: String, val signature: String, val wallet: com.lifecyclebot.network.SolanaWallet?,
        val solUsdAtBuy: Double, val candidateVersion: Long, val notedAtMs: Long,
    )

    /** The bot's own buy proof in chain units. */
    data class Econ8308(val qtyRaw: BigInteger, val decimals: Int, val costSol: Double, val feeSol: Double, val entryUsd: Double)

    private val pending = ConcurrentHashMap<String, Pending8308>()   // mint -> pending proof
    private val noted = AtomicLong(0)
    private val promoted = AtomicLong(0)
    private val unprovable = AtomicLong(0)
    private val notLanded = AtomicLong(0)
    private val scheduler by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "AATE-ProtectiveProof-8308").apply { isDaemon = true } }
    }

    // ── pure ──

    /**
     * Pure: the chain-proven buy economics from one tx-meta read, or null. Cost excludes the network fee (it is kept as
     * the fee) exactly as Executor's live commit does; the entry USD/token needs an observed SOL/USD in the sane band.
     */
    fun buyEconomics8308(landed: Boolean, rawDelta: BigInteger, decimals: Int, solSpentLamports: Long, feeLamports: Long,
                         uiDelta: Double, solUsd: Double): Econ8308? {
        if (!landed || rawDelta.signum() <= 0 || decimals !in 0..18 || solSpentLamports <= 0L || feeLamports < 0L) return null
        val cost = (solSpentLamports - feeLamports).coerceAtLeast(0L).toDouble() / 1_000_000_000.0
        val fee = feeLamports.toDouble() / 1_000_000_000.0
        if (!(cost > 0.0) || !uiDelta.isFinite() || uiDelta <= 0.0) return null
        if (!solUsd.isFinite() || solUsd !in 5.0..10_000.0) return null
        val px = cost / uiDelta * solUsd
        if (!px.isFinite() || px <= 0.0) return null
        return Econ8308(rawDelta, decimals, cost, fee, px)
    }

    /** Pure: may this protective row be promoted on a buy proof (live, BASIS_UNCERTAIN, never sold)? */
    fun promotable8308(p: CanonicalPositionAuthority6441.Position): Boolean =
        p.mode.equals("live", true) &&
            p.lifecycle == CanonicalPositionAuthority6441.Lifecycle.QUARANTINED &&
            p.quarantineReason.startsWith("BASIS_UNCERTAIN_7807") &&
            p.remainingQtyRaw.signum() > 0 && p.remainingQtyRaw == p.originalQtyRaw &&
            p.soldCostBasisSol <= 1e-12 && p.realizedProceedsSol <= 1e-12

    /** Pure: the buy delivered exactly the quantity the protective row holds (same scale). */
    fun sameInventory8308(p: CanonicalPositionAuthority6441.Position, e: Econ8308): Boolean =
        p.quantityScale == e.decimals && p.remainingQtyRaw == e.qtyRaw

    // ── live ──

    /** Executor.protectLandedLiveBuy7807: remember the bot's own buy signature and retry its chain proof. */
    fun note8308(positionId: String, mint: String, signature: String, wallet: com.lifecyclebot.network.SolanaWallet?, candidateVersion: Long = 0L) {
        if (positionId.isBlank() || mint.isBlank() || signature.isBlank() || signature.startsWith("PHANTOM_")) return
        val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        val p = Pending8308(positionId, mint, signature, wallet, solUsd, candidateVersion, System.currentTimeMillis())
        if (pending.size > 200) pending.entries.removeIf { System.currentTimeMillis() - it.value.notedAtMs > 30L * 60_000L }
        pending[mint] = p
        noted.incrementAndGet()
        try { PipelineHealthCollector.labelInc("PROTECTIVE_BUY_PROOF_RETRY_SCHEDULED_8308") } catch (_: Throwable) {}
        for (d in RETRY_DELAYS_MS_8308) try {
            scheduler.schedule(Runnable { try { attempt8308(mint, BACKGROUND_VERIFY_MS_8308, "RETRY") } catch (_: Throwable) {} }, d, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {}
    }

    /** SellFinalizationCoordinator: one bounded proof attempt before a protective row would close with no economics. */
    fun beforeProtectiveSell8308(mint: String): Boolean {
        if (!pending.containsKey(mint)) return false
        return try { attempt8308(mint, PRE_CLOSE_VERIFY_MS_8308, "PRE_CLOSE") } catch (_: Throwable) { false }
    }

    private fun protectiveRow8308(p: Pending8308): CanonicalPositionAuthority6441.Position? =
        CanonicalPositionAuthority6441.getPosition(p.positionId)?.takeIf { it.mint == p.mint }
            ?: try {
                CanonicalPositionAuthority6441.quarantinedLivePositions7454(p.mint)
                    .firstOrNull { it.quarantineReason.startsWith("BASIS_UNCERTAIN_7807") }
            } catch (_: Throwable) { null }

    /** True when the protective row of [mint] was promoted on its chain proof by this call. */
    private fun attempt8308(mint: String, verifyMs: Long, stage: String): Boolean {
        val p = pending[mint] ?: return false
        synchronized(p) {
            if (pending[mint] !== p) return false
            val row = protectiveRow8308(p)
            if (row == null || !promotable8308(row)) {
                pending.remove(mint, p)
                val why = when {
                    row == null -> "NO_ROW"
                    row.lifecycle == CanonicalPositionAuthority6441.Lifecycle.OPEN ||
                        row.lifecycle == CanonicalPositionAuthority6441.Lifecycle.PARTIALLY_CLOSED -> "ALREADY_OPEN"
                    else -> { unprovable.incrementAndGet(); "UNPROVABLE_${row.lifecycle.name}" }
                }
                label("PROTECTIVE_BUY_PROOF_DROPPED_8308_$why")
                return false
            }
            val w = p.wallet ?: try { com.lifecyclebot.engine.WalletManager.getWallet() } catch (_: Throwable) { null } ?: return false
            val v = try { com.lifecyclebot.engine.TradeVerifier.verifyBuy(w, p.signature, p.mint, timeoutMs = verifyMs) } catch (_: Throwable) { null } ?: return false
            val landed = v.outcome == com.lifecyclebot.engine.TradeVerifier.Outcome.LANDED
            if (v.outcome == com.lifecyclebot.engine.TradeVerifier.Outcome.FAILED_CONFIRMED) {
                pending.remove(mint, p); notLanded.incrementAndGet(); label("PROTECTIVE_BUY_PROOF_TX_FAILED_8308"); return false
            }
            val solUsd = p.solUsdAtBuy.takeIf { it.isFinite() && it in 5.0..10_000.0 }
                ?: try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
            val econ = buyEconomics8308(landed, v.rawTokenDelta, v.decimals, v.solSpentLamports, v.feeLamports, v.uiTokenDelta, solUsd)
                ?: run { label("PROTECTIVE_BUY_PROOF_NOT_YET_8308_$stage"); return false }
            if (!sameInventory8308(row, econ)) {
                // The wallet row holds more (or less) than this buy delivered (an earlier remnant / transfer): the buy's
                // economics do not cover the whole row, so it stays quarantined rather than mixing an unknown basis in.
                pending.remove(mint, p); unprovable.incrementAndGet(); label("PROTECTIVE_BUY_PROOF_QTY_MISMATCH_8308"); return false
            }
            val r =CanonicalPositionAuthority6441.promotePendingToOpen(
                positionId = row.positionId, actualQtyRaw = econ.qtyRaw, actualEntryCostSol = econ.costSol, actualFeesSol = econ.feeSol,
                tokenDecimals = econ.decimals, paperMode = false, quantityScale = econ.decimals,
                actualEntryPriceUsd = econ.entryUsd, actualEntryPriceSource = "LIVE_TX_META_LATE_PROOF_8308",
                candidateVersion7863 = p.candidateVersion,
            )
            if (r != CanonicalPositionAuthority6441.MutateResult.APPLIED) {
                label("PROTECTIVE_BUY_PROOF_PROMOTE_${r.name}_8308")
                if (r == CanonicalPositionAuthority6441.MutateResult.LIFECYCLE_FORBIDDEN) pending.remove(mint, p)
                return false
            }
            pending.remove(mint, p)
            promoted.incrementAndGet()
            try { LearningQuarantineGate6470.releaseProvenBasis8308(row.positionId) } catch (_: Throwable) {}
            try {
                PipelineHealthCollector.labelInc("PROTECTIVE_BUY_PROMOTED_ON_CHAIN_PROOF_8308")
                PipelineHealthCollector.labelInc("PROTECTIVE_BUY_PROMOTED_ON_CHAIN_PROOF_8308_$stage")
                ForensicLogger.lifecycle("PROTECTIVE_BUY_PROMOTED_ON_CHAIN_PROOF_8308",
                    "positionId=${row.positionId.take(40)} mint=${mint.take(12)} sig=${p.signature.take(16)} stage=$stage " +
                        "qtyRaw=${econ.qtyRaw} dec=${econ.decimals} cost=${econ.costSol} fee=${econ.feeSol} entryUsd=${econ.entryUsd} " +
                        "was=${row.quarantineReason.take(60)} action=open_on_tx_meta_economics_learnable")
            } catch (_: Throwable) {}
            return true
        }
    }

    private fun label(l: String) { try { PipelineHealthCollector.labelInc(l) } catch (_: Throwable) {} }

    fun pendingCount8308(): Int = pending.size

    fun statusLine8308(): String =
        "protectiveProof8308[noted=${noted.get()} promoted=${promoted.get()} pending=${pending.size} unprovable=${unprovable.get()} txFailed=${notLanded.get()}]"

    internal fun resetForTest8308() { pending.clear(); noted.set(0); promoted.set(0); unprovable.set(0); notLanded.set(0) }
}
