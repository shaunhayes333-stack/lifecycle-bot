package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8169 — ACCOUNTING INTEGRITY (device report 5.0.8168).
 *
 * Pure decisions (no Android) behind four repairs, plus the counters the next report reads:
 *
 * 1. LIVE_BUY_NO_SIGNATURE=12 of 17 journal buys. The SQLite journal never had a `sig` column, so every
 *    LIVE BUY reloaded after a restart came back with sig="" (the 5 rows of the running session still
 *    carried theirs in memory). TradeHistoryStore v10 now persists it; for rows written before that the
 *    reconcile path carries the local signature from SignedTxLedger8085 when exactly one confirmed BUY
 *    of the same mint was signed near the row ([resolveJournalBuySignatures8169]). A chain-proven row
 *    (proofState LIVE_FINALIZED / LIVE_SIG_CONFIRMED) whose signature is still unknown is reported under
 *    its own cause, LIVE_BUY_SIG_NOT_PERSISTED, so LIVE_BUY_NO_SIGNATURE counts only buys that never
 *    had chain proof.
 *
 * 2. Paper cash -5.29 (ledger) vs 9.69 (canonical). The startup open-cost override replaced the ledger's
 *    open cost with the paper projections' 18.25 SOL — more than the account ever funded — and the
 *    identity rebuild then wrote cash = start + realized − fees − openCost < 0. The canonical projection
 *    refuses negative cash, so it kept its old value and the two disagreed. Both writes are now refused
 *    with a named reason when they would drive cash below zero ([openCostOverrideRefusal8169],
 *    [identityCashRefusal8169]); the canonical projection follows the ledger after a good rebuild.
 *
 * 3. Multi-chain paper mean +3.75e36 %. Paper PnL was (mark / entry − 1) · 100 with no plausibility
 *    bound, so one pair/unit-mismatched mark booked an astronomical close. Marks implying more than
 *    [MAX_MARK_MULTIPLE_8169]x are refused ([markMultipleRefusal8169]); low marks are never refused
 *    (a rug exit stays immediate).
 *
 * 4. Mark repair fail=146. Failures were one undifferentiated counter: a `solana|<mint>` identity was
 *    handed to the price cascade with its prefix (never priceable), non-Solana identities were "repaired"
 *    against Solana feeds, and a cascade hot-cache hit (no new observation inside 5 s) returned null and
 *    counted as a failure. Each failure now carries a named reason ([repairFailureReason8169]).
 */
object AccountingIntegrity8169 {

    // ── 1. LIVE BUY signatures ──────────────────────────────────────────────

    /** A signed BUY row of SignedTxLedger8085 may parent a journal row signed within this window. */
    const val SIG_MATCH_WINDOW_MS_8169: Long = 10L * 60_000L

    data class SignedBuy8169(val signature: String, val mint: String, val createdAtMs: Long, val confirmed: Boolean)

    /** Proof states the journal writes only after chain proof of the buy (executor finality / backfill with sig). */
    fun chainProvenProofState8169(proofState: String): Boolean {
        val s = proofState.trim().uppercase()
        return s == "LIVE_FINALIZED" || s == "LIVE_SIG_CONFIRMED"
    }

    data class SigResolution8169(val journal: List<ReconcileClassifier8144.Row8144>, val recovered: Int, val ambiguous: Int)

    /**
     * Pure: give each unsigned LIVE BUY row the signature of the ONE confirmed signed BUY of the same mint
     * created within [SIG_MATCH_WINDOW_MS_8169] of the row and not already carried by another journal row.
     * Two or more candidates is ambiguous and leaves the row unsigned (never guessed).
     */
    fun resolveJournalBuySignatures8169(
        journal: List<ReconcileClassifier8144.Row8144>,
        ledger: List<SignedBuy8169>,
    ): SigResolution8169 {
        if (journal.none { ReconcileClassifier8144.isBuy8144(it.side) && it.sig.isBlank() }) return SigResolution8169(journal, 0, 0)
        val used = journal.mapNotNullTo(HashSet()) { it.sig.takeIf { s -> s.isNotBlank() } }
        val pool = ledger.filter { it.confirmed && it.signature.isNotBlank() && it.mint.isNotBlank() }
        var recovered = 0
        var ambiguous = 0
        val out = journal.map { r ->
            if (!ReconcileClassifier8144.isBuy8144(r.side) || r.sig.isNotBlank() || r.mint.isBlank()) return@map r
            val c = pool.filter {
                it.mint == r.mint && it.signature !in used &&
                    r.ts > 0L && kotlin.math.abs(it.createdAtMs - r.ts) <= SIG_MATCH_WINDOW_MS_8169
            }
            when (c.size) {
                1 -> { used += c[0].signature; recovered++; r.copy(sig = c[0].signature) }
                0 -> r
                else -> { ambiguous++; r }
            }
        }
        return SigResolution8169(out, recovered, ambiguous)
    }

    // ── 2. Paper cash authorities ───────────────────────────────────────────

    /** Capital the paper account can have tied up in open positions: start + realized − fees − treasury. */
    fun fundableOpenCost8169(startingSol: Double, realizedSol: Double, feesSol: Double, treasurySol: Double): Double =
        startingSol + realizedSol - feesSol - treasurySol

    /**
     * Pure: null = the projection open cost may replace the ledger scalar, else the named refusal.
     * Lowering open cost (the 6508 drift repair) is always allowed; raising it past the capital the
     * account ever funded would manufacture negative cash and is refused.
     */
    fun openCostOverrideRefusal8169(projectionSol: Double, priorSol: Double, fundableSol: Double): String? = when {
        !projectionSol.isFinite() -> "OPEN_COST_PROJECTION_NOT_FINITE_8169"
        projectionSol < -1e-9 -> "OPEN_COST_PROJECTION_NEGATIVE_8169"
        !fundableSol.isFinite() -> "FUNDABLE_CAPITAL_NOT_FINITE_8169"
        projectionSol > priorSol && projectionSol > fundableSol + 1e-6 -> "OPEN_COST_EXCEEDS_FUNDED_CAPITAL_8169"
        else -> null
    }

    /** Pure: null = the identity-rebuilt cash may be written, else the named refusal. */
    fun identityCashRefusal8169(cashSol: Double): String? = when {
        !cashSol.isFinite() -> "IDENTITY_CASH_NOT_FINITE_8169"
        cashSol < -1e-9 -> "IDENTITY_CASH_NEGATIVE_8169"
        else -> null
    }

    /** Pure: do the paper ledger (authority) and the canonical projection agree on cash? */
    fun cashAgreement8169(canonicalSol: Double, ledgerSol: Double, toleranceSol: Double = 0.001): String {
        if (!canonicalSol.isFinite() || !ledgerSol.isFinite()) return "UNKNOWN"
        val d = ledgerSol - canonicalSol
        return if (kotlin.math.abs(d) <= toleranceSol) "AGREE" else "DIVERGE(d=${"%.4f".format(d)})"
    }

    private val cashRefusals8169 = ConcurrentHashMap<String, AtomicLong>()
    @Volatile private var lastCashRefusal8169 = ""

    fun noteCashRefusal8169(reason: String, detail: String) {
        cashRefusals8169.getOrPut(reason) { AtomicLong(0L) }.incrementAndGet()
        lastCashRefusal8169 = "$reason $detail".take(140)
    }

    fun cashGuardLine8169(): String {
        val by = cashRefusals8169.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "none" }
        return "cashGuard8169[refused=$by" + (if (lastCashRefusal8169.isNotBlank()) " last=$lastCashRefusal8169" else "") + "]"
    }

    // ── 3. Multi-chain marks ────────────────────────────────────────────────

    /** No discovered pool moves this far inside one hold; a larger multiple is a pair or unit mismatch. */
    const val MAX_MARK_MULTIPLE_8169: Double = 10_000.0

    /**
     * Pure: null = [markUsd] may be read against [referenceUsd] (entry or previous mark), else the named
     * refusal. Only implausible UPSIDE is refused: a mark collapsing toward zero is what a rug looks like
     * and must keep driving the stop.
     */
    fun markMultipleRefusal8169(referenceUsd: Double, markUsd: Double): String? = when {
        !markUsd.isFinite() || markUsd <= 0.0 -> "MARK_NOT_POSITIVE_FINITE_8169"
        !referenceUsd.isFinite() || referenceUsd <= 0.0 -> null
        !(markUsd / referenceUsd).isFinite() || markUsd / referenceUsd > MAX_MARK_MULTIPLE_8169 -> "MARK_MULTIPLE_IMPLAUSIBLE_8169"
        else -> null
    }

    /** Pure: a closed-trade percent the book may average (finite and inside the plausible multiple). */
    fun bookablePct8169(pct: Double): Boolean = pct.isFinite() && pct <= (MAX_MARK_MULTIPLE_8169 - 1.0) * 100.0 && pct >= -100.0 - 1e-6

    // ── 4. Mark identity repair ─────────────────────────────────────────────

    private const val BASE58_8169 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    /** Pure: the bare Solana mint a repair may price, or null for a non-Solana identity. */
    fun repairIdentity8169(identity: String): String? {
        val bare = identity.trim().removePrefix("solana|").trim()
        if (bare.length !in 32..44) return null
        return if (bare.all { it in BASE58_8169 }) bare else null
    }

    /** Pure: the named reason a repair produced no price. */
    fun repairFailureReason8169(
        solanaIdentity: Boolean, threw: Boolean, fanoutContested: Boolean, cascadeHotCacheHit: Boolean,
    ): String = when {
        !solanaIdentity -> "NON_SOLANA_IDENTITY"
        threw -> "EXCEPTION"
        cascadeHotCacheHit -> "NO_NEW_OBSERVATION_HOT_CACHE"
        fanoutContested -> "FEEDS_CONTESTED_CASCADE_EMPTY"
        else -> "NO_FEED_ANSWERED"
    }
}
