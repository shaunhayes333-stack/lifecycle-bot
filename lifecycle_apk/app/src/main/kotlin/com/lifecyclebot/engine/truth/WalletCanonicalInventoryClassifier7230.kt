package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7230 §WALLET_CANONICAL_INVENTORY — operator diagnosis (7227):
 *
 *   Canonical LIVE active mints: 3
 *   Host wallet projection:      3
 *   Reconciler:                  walletMints=6 held=6 openTracked=3
 *   Screenshot shows: SOL + NEAR-CHAN + Satoshinu + Tensor + AROS
 *
 *   Result: wallet has assets the bot does not manage, several appear
 *   in the UI as RECOVERED_TNSRxc "basis unknown", and the wider
 *   pipeline currently manufactures PnL for these positions from
 *   whatever mark it can find.
 *
 * PURPOSE — every non-zero wallet mint must fall into exactly ONE
 * classification bucket, with a canonical reason.  Unknown-basis
 * positions remain visible and management-eligible but MUST NEVER
 * generate PnL.
 *
 * BUCKETS (operator directive #6):
 *   BOT_CANONICAL_OPEN            — bot opened it and has sealed basis
 *   EXTERNAL_WALLET_HOLDING       — wallet has it, bot did not open it
 *   QUARANTINED_WITH_EXPLICIT_REASON — bot opened it but classification failed
 *   UNSUPPORTED_TOKEN             — non-trading asset (staked, LP, NFT, etc.)
 *
 * Scope: additive/advisory.  This authority does not remove positions
 * or change balances.  It is a truth surface every consumer that
 * computes PnL must consult; unclassified/unknown-basis calls return
 * ClassificationVerdict.pnlAllowed = false and the caller must skip
 * PnL emission for that mint.
 *
 * Preserves §8: canonical accounting, mark sanity, reconciler, FDG
 * sealing, terminal idempotency, capital conservation, stale-ticket
 * protection.  Never touches wallet balances.
 */
object WalletCanonicalInventoryClassifier7230 {

    enum class Bucket {
        BOT_CANONICAL_OPEN,
        EXTERNAL_WALLET_HOLDING,
        QUARANTINED_WITH_EXPLICIT_REASON,
        UNSUPPORTED_TOKEN,
    }

    data class Classification(
        val mint: String,
        val bucket: Bucket,
        val reason: String,
        val pnlAllowed: Boolean,
        val exitEligible: Boolean,
        val sealedBasisPresent: Boolean,
        val classifiedAtMs: Long,
    )

    private val classifications = ConcurrentHashMap<String, Classification>()

    private val classifiedCount = AtomicLong(0L)
    private val reclassifiedCount = AtomicLong(0L)
    private val pnlSuppressedCount = AtomicLong(0L)
    private val pnlAllowedCount = AtomicLong(0L)

    /**
     * Classify a wallet-observed mint.  Called by the reconciler /
     * wallet inventory scanner whenever it observes a non-zero balance.
     *
     *  * botCanonicalOwned         — this mint has a sealed canonical
     *                                open position (BOT_BUY provenance)
     *  * sealedBasisPresent        — CanonicalFillBasisSeal7229 has a
     *                                seal for it
     *  * externalWalletHolding     — the wallet has non-zero balance
     *                                but the bot never opened this
     *                                position
     *  * unsupportedProof          — non-trading asset (e.g. staked
     *                                position, LP, NFT)
     *  * quarantineReason          — explicit reason if bot opened but
     *                                classification failed (empty if none)
     */
    fun classify(
        mint: String,
        botCanonicalOwned: Boolean,
        sealedBasisPresent: Boolean,
        externalWalletHolding: Boolean,
        unsupportedProof: Boolean,
        quarantineReason: String,
    ): Classification {
        val (bucket, reason, pnlAllowed, exitEligible) = when {
            unsupportedProof ->
                Quadruple(Bucket.UNSUPPORTED_TOKEN, "unsupported_asset_proof", false, false)
            quarantineReason.isNotBlank() ->
                Quadruple(Bucket.QUARANTINED_WITH_EXPLICIT_REASON, quarantineReason, false, true)
            botCanonicalOwned && sealedBasisPresent ->
                Quadruple(Bucket.BOT_CANONICAL_OPEN, "bot_owned_with_sealed_basis", true, true)
            botCanonicalOwned && !sealedBasisPresent ->
                Quadruple(Bucket.QUARANTINED_WITH_EXPLICIT_REASON, "bot_owned_basis_unsealed", false, true)
            externalWalletHolding ->
                Quadruple(Bucket.EXTERNAL_WALLET_HOLDING, "external_holding_no_bot_lineage", false, false)
            else ->
                Quadruple(Bucket.QUARANTINED_WITH_EXPLICIT_REASON, "unclassifiable", false, false)
        }

        val out = Classification(
            mint = mint,
            bucket = bucket,
            reason = reason,
            pnlAllowed = pnlAllowed,
            exitEligible = exitEligible,
            sealedBasisPresent = sealedBasisPresent,
            classifiedAtMs = System.currentTimeMillis(),
        )
        val prev = classifications.put(mint, out)
        if (prev == null) classifiedCount.incrementAndGet()
        else if (prev.bucket != out.bucket) reclassifiedCount.incrementAndGet()

        try {
            PipelineHealthCollector.labelInc("WALLET_INVENTORY_CLASSIFIED_7230_${bucket.name}")
            if (prev == null || prev.bucket != out.bucket) {
                ForensicLogger.lifecycle(
                    "WALLET_INVENTORY_CLASSIFIED_7230",
                    "mint=${mint.take(10)} bucket=$bucket reason=$reason " +
                        "pnlAllowed=$pnlAllowed exitEligible=$exitEligible " +
                        "sealedBasis=$sealedBasisPresent",
                )
            }
        } catch (_: Throwable) {}

        return out
    }

    /**
     * Consult before emitting PnL for a wallet-observed mint.  Returns
     * false for any mint whose classification does not allow PnL
     * (external holding, quarantined, unsupported, unclassified).
     * Callers must skip PnL emission and either surface "basis wait"
     * or "external holding" instead — never a manufactured number.
     */
    fun pnlAllowed(mint: String): Boolean {
        val c = classifications[mint]
        if (c == null || !c.pnlAllowed) {
            pnlSuppressedCount.incrementAndGet()
            try { PipelineHealthCollector.labelInc("WALLET_PNL_SUPPRESSED_UNCLASSIFIED_7230") } catch (_: Throwable) {}
            return false
        }
        pnlAllowedCount.incrementAndGet()
        return true
    }

    fun classification(mint: String): Classification? = classifications[mint]

    data class Summary(
        val classified: Long,
        val reclassified: Long,
        val pnlSuppressed: Long,
        val pnlAllowed: Long,
        val bucketCounts: Map<Bucket, Int>,
    )

    fun summary(): Summary {
        val counts = Bucket.values().associateWith { 0 }.toMutableMap()
        classifications.values.forEach { counts[it.bucket] = (counts[it.bucket] ?: 0) + 1 }
        return Summary(
            classified = classifiedCount.get(),
            reclassified = reclassifiedCount.get(),
            pnlSuppressed = pnlSuppressedCount.get(),
            pnlAllowed = pnlAllowedCount.get(),
            bucketCounts = counts,
        )
    }

    fun statusLine(): String {
        val s = summary()
        return "WalletCanonicalInventoryClassifier7230 " +
            "classified=${s.classified} reclassified=${s.reclassified} " +
            "pnlSuppressed=${s.pnlSuppressed} pnlAllowed=${s.pnlAllowed} " +
            "buckets=${s.bucketCounts}"
    }

    internal fun clearForTest() {
        classifications.clear()
        classifiedCount.set(0L); reclassifiedCount.set(0L)
        pnlSuppressedCount.set(0L); pnlAllowedCount.set(0L)
    }

    private data class Quadruple<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
