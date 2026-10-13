package com.lifecyclebot.engine.sell

import com.lifecyclebot.network.SignedTxLedger8085

/**
 * V5.0.8087 — one typed result for every live send.
 *
 * Until 8086 the send helpers returned `String?`: a signature meant "it landed" to some callers even when
 * it was adopted from a sent-but-unconfirmed outcome (SELL_RACE_RAYDIUM_LANDED_8083 / SELL_CONFIRMED were
 * stamped on unknown submissions). A signature proves the transaction's identity, never its success.
 * The state comes from SignedTxLedger8085 (chain-driven transitions), not from the provider's reply.
 */
enum class TxState8087 { NOT_SUBMITTED, SUBMITTED_UNKNOWN, RPC_ACCEPTED, CHAIN_CONFIRMED, CHAIN_FAILED, EXPIRED_PROVEN }

data class TxResult8087(
    val state: TxState8087,
    val signature: String,
    val mint: String,
    val side: String,
    val rawQty: Long = 0L,
    val positionId: String = "",
    val route: String = "",
) {
    /** Chain-confirmed: the only state allowed to be logged/counted as landed. */
    val confirmed: Boolean get() = state == TxState8087.CHAIN_CONFIRMED
    /** Possibly executed: confirmation + wallet reconciliation decide; never a failed or a booked fill yet. */
    val pendingFinality: Boolean get() = state == TxState8087.SUBMITTED_UNKNOWN || state == TxState8087.RPC_ACCEPTED
    /** Identity of the transaction — present for every submitted state. */
    val txId: String get() = signature

    companion object {
        fun of(ledger: SignedTxLedger8085.State?): TxState8087 = when (ledger) {
            SignedTxLedger8085.State.CONFIRMED -> TxState8087.CHAIN_CONFIRMED
            SignedTxLedger8085.State.FAILED_ON_CHAIN -> TxState8087.CHAIN_FAILED
            SignedTxLedger8085.State.EXPIRED_PROVEN -> TxState8087.EXPIRED_PROVEN
            SignedTxLedger8085.State.ACCEPTED -> TxState8087.RPC_ACCEPTED
            SignedTxLedger8085.State.SUBMIT_REJECTED, SignedTxLedger8085.State.BUILT -> TxState8087.NOT_SUBMITTED
            SignedTxLedger8085.State.SIGNED, SignedTxLedger8085.State.SUBMIT_ATTEMPTED,
            SignedTxLedger8085.State.OUTCOME_UNKNOWN -> TxState8087.SUBMITTED_UNKNOWN
            // No local ledger row: an externally completed (RFQ/Ultra) execution the provider reported.
            // That is acceptance, not chain truth; TradeVerifier/wallet reconciliation decides.
            null -> TxState8087.RPC_ACCEPTED
        }

        /** Result for a send that returned normally: read the ledger the wallet advanced from chain status. */
        fun afterSend(signature: String, mint: String, side: String, route: String, rawQty: Long = 0L, positionId: String = ""): TxResult8087 {
            val st = of(SignedTxLedger8085.get(signature)?.state)
            count(if (st == TxState8087.CHAIN_CONFIRMED) "TX_RESULT_CHAIN_CONFIRMED_8087" else "TX_RESULT_${st.name}_8087")
            return TxResult8087(st, signature, mint, side, rawQty, positionId, route)
        }

        fun unknown(signature: String, mint: String, side: String, route: String, rawQty: Long = 0L): TxResult8087 {
            count("TX_RESULT_SUBMITTED_UNKNOWN_8087")
            return TxResult8087(TxState8087.SUBMITTED_UNKNOWN, signature, mint, side, rawQty, route = route)
        }

        private fun count(label: String) { try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc(label) } catch (_: Throwable) {} }
    }
}
