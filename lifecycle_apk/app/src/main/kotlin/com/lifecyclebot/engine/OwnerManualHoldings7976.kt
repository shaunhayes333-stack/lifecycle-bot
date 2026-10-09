package com.lifecyclebot.engine

/**
 * V5.0.7976 — the owner trades by hand in the bot's wallet. The wallet tracker parses
 * every buy it sees in that wallet (TX_PARSE) and the exit-coverage guard counted the
 * owner's manual buys as unmanaged BOT holdings: 5.0.7972 held one (TNSR, bought by
 * hand) "outside canonical exit scope", and CryptoAltTrader refused every live crypto
 * entry on it ("0 crypto action").
 *
 * A tracker row is the owner's manual holding when it was not a bot buy (source is
 * not BOT_BUY), it carries a buy signature, and that signature is not one the bot
 * sent (FillLotLedger6344 buy lots — never cleared by a learning reset) and the bot
 * holds no lot for the mint. Those rows are left alone everywhere: not adopted, not
 * sold, and not counted against the bot's own exit coverage.
 */
object OwnerManualHoldings7976 {

    /** Pure: is this tracker row the owner's manual buy? */
    fun ownerManual7976(isBotBuySource: Boolean, buySignature: String?, botSignatures: Set<String>, botHoldsLot: Boolean): Boolean =
        !isBotBuySource && !buySignature.isNullOrBlank() && buySignature !in botSignatures && !botHoldsLot

    @Volatile private var cache: Pair<Long, Pair<Set<String>, Set<String>>>? = null

    /** (signatures the bot sent, mints the bot holds a lot for), cached 30 s. */
    private fun botEvidence(nowMs: Long): Pair<Set<String>, Set<String>> {
        cache?.let { (at, v) -> if (nowMs - at in 0L..30_000L) return v }
        val sigs = HashSet<String>()
        val mints = HashSet<String>()
        try {
            FillLotLedger6344.snapshotForWallet(WalletManager.currentPubkey()).forEach { lot ->
                if (lot.buyTxSig.isNotBlank()) sigs += lot.buyTxSig
                mints += lot.mintAddress
            }
        } catch (_: Throwable) {}
        val v = sigs to mints
        cache = nowMs to v
        return v
    }

    /** Is [mint] in the wallet only because the owner bought it by hand? */
    fun isOwnerManual7976(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean = try {
        val p = HostWalletTokenTracker.getEntry(mint)
        if (p == null) false else {
            val (sigs, lotMints) = botEvidence(nowMs)
            val manual = ownerManual7976(p.source == HostWalletTokenTracker.PositionSource.BOT_BUY, p.buySignature, sigs, mint in lotMints)
            if (manual) try { PipelineHealthCollector.labelInc("OWNER_MANUAL_HOLDING_EXCLUDED_7976") } catch (_: Throwable) {}
            manual
        }
    } catch (_: Throwable) { false }
}
