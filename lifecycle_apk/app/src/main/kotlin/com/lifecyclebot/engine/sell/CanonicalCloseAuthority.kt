package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PositionCloseLedger

/**
 * V5.9.1526 — CANONICAL CLOSE AUTHORITY (single source of truth for "is a
 * LIVE position allowed to be marked CLOSED").
 *
 * Operator P0 (live down $150/24h): the tracker was marking positions CLOSED
 * optimistically — on an empty/inconclusive RPC balance map, on a bare
 * signature, or via reconciler force-reap — while the wallet still held the
 * token. The position then bled unmanaged or got double-sold, and the open
 * counts (host tracker / lane / canonical) disagreed.
 *
 * THE RULE (spec items 1,2,3): a LIVE position may transition to CLOSED ONLY
 * when ALL of these hold:
 *    (a) a sell SIGNATURE exists,
 *    (b) the signature is CONFIRMED (caller passes confirmed=true),
 *    (c) the wallet token balance is conclusively <= dust,
 *    (d) the close ledger is stamped (done here, atomically, on success).
 *
 * If the balance is empty/inconclusive (RPC blip), we DO NOT close — we return
 * VERIFY_PENDING and the caller leaves the position open for the reconciler.
 *
 * Paper positions are exempt (no wallet) and close through the legacy path.
 *
 * This object holds NO mutable position state. It is a pure decision + the
 * single atomic ledger-stamp point, so every caller converges on one verdict.
 */
object CanonicalCloseAuthority {

    /** Raw-units dust ceiling. Matches HostWalletTokenTracker.DUST_RAW (1)
     *  and the uiAmount dust used across the tracker (0.000001). */
    const val DUST_UI: Double = 0.000001

    enum class Verdict {
        /** All invariants satisfied — caller may finalize CLOSED. Ledger stamped. */
        CLOSE_CONFIRMED,
        /** Wallet still holds > dust — must NOT close; sell/retry instead. */
        STILL_HELD,
        /** Balance unknown/inconclusive (empty RPC map / poll failure) — leave
         *  OPEN and mark SELL_VERIFY_PENDING; reconciler resolves later. */
        VERIFY_PENDING,
        /** No signature or signature not confirmed — cannot claim a close. */
        NO_CONFIRMED_SIGNATURE,
    }

    data class CloseDecision(
        val verdict: Verdict,
        val closeId: String? = null,
        val detail: String = "",
    )

    /**
     * Authoritative gate. Call this at EVERY live CLOSED transition.
     *
     * @param mint                position mint
     * @param symbol              for logs
     * @param sellSignature       broadcast signature (null/blank = none)
     * @param signatureConfirmed  true only after on-chain confirmation
     * @param walletBalanceUi     fresh on-chain UI balance, or null if the RPC
     *                            read was empty/inconclusive (DO NOT pass 0.0
     *                            for "unknown" — pass null)
     * @param reason              exit reason (for ledger)
     * @param pnlPct              for ledger metadata
     */
    fun evaluate(
        mint: String,
        symbol: String,
        sellSignature: String?,
        signatureConfirmed: Boolean,
        walletBalanceUi: Double?,
        reason: String,
        pnlPct: Int,
    ): CloseDecision {
        if (mint.isBlank()) {
            return CloseDecision(Verdict.NO_CONFIRMED_SIGNATURE, detail = "blank-mint")
        }

        // (a)+(b) — require a confirmed signature to claim ANY close.
        val hasSig = !sellSignature.isNullOrBlank()
        if (!hasSig || !signatureConfirmed) {
            log(mint, symbol, "NO_CONFIRMED_SIGNATURE",
                "sig=${sellSignature?.take(12) ?: "<none>"} confirmed=$signatureConfirmed")
            return CloseDecision(Verdict.NO_CONFIRMED_SIGNATURE,
                detail = "sig=$hasSig confirmed=$signatureConfirmed")
        }

        // (c) — balance must be conclusively <= dust.
        when {
            walletBalanceUi == null -> {
                // Inconclusive: explicitly DO NOT close (spec item 3).
                log(mint, symbol, "SELL_VERIFY_PENDING",
                    "balance=INCONCLUSIVE sig=${sellSignature!!.take(12)} — leaving OPEN")
                return CloseDecision(Verdict.VERIFY_PENDING, detail = "balance-inconclusive")
            }
            walletBalanceUi > DUST_UI -> {
                log(mint, symbol, "CLOSE_REJECTED_STILL_HELD",
                    "balance=$walletBalanceUi > dust sig=${sellSignature!!.take(12)}")
                return CloseDecision(Verdict.STILL_HELD,
                    detail = "balance=$walletBalanceUi")
            }
        }

        // (d) — all invariants pass; stamp the ledger atomically and return id.
        val cid = PositionCloseLedger.markClosed(mint, reason, pnlPct)
        log(mint, symbol, "CLOSE_CONFIRMED_AUTHORITATIVE",
            "closeId=$cid balance=${walletBalanceUi} sig=${sellSignature!!.take(12)} reason=$reason")
        return CloseDecision(Verdict.CLOSE_CONFIRMED, closeId = cid,
            detail = "balance<=dust+sig+confirmed")
    }

    /**
     * Convenience for the dust-remaining accepted case: a confirmed signature
     * exists and the residual is dust (token-account rent dust that cannot be
     * sold). Treated as a valid close (spec item 5: DUST_REMAINING_ACCEPTED).
     */
    fun acceptDustRemaining(
        mint: String, symbol: String, sellSignature: String?, residualUi: Double,
        reason: String, pnlPct: Int,
    ): CloseDecision {
        if (sellSignature.isNullOrBlank()) {
            return CloseDecision(Verdict.NO_CONFIRMED_SIGNATURE, detail = "dust-no-sig")
        }
        val cid = PositionCloseLedger.markClosed(mint, "${reason}_DUST", pnlPct)
        log(mint, symbol, "DUST_REMAINING_ACCEPTED",
            "residual=$residualUi closeId=$cid sig=${sellSignature.take(12)}")
        return CloseDecision(Verdict.CLOSE_CONFIRMED, closeId = cid, detail = "dust-accepted")
    }

    private fun log(mint: String, symbol: String, tag: String, detail: String) {
        try {
            ForensicLogger.lifecycle("CCA_$tag", "mint=${mint.take(10)} symbol=$symbol $detail")
        } catch (_: Throwable) {}
        try { ErrorLogger.debug("CanonicalCloseAuthority", "$tag $symbol $detail") } catch (_: Throwable) {}
    }
}
