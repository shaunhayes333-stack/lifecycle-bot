package com.lifecyclebot.hyperliquid

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8797 — live order mechanics on Hyperliquid, for HlDesk:
 *  - reads the account on every dex the book touches (each HIP-3 dex keeps its own balance and positions);
 *  - resolves each coin's asset id and size rules on the network being traded (testnet ids differ from mainnet);
 *  - sets margin mode and leverage before a position opens (isolated where the market requires it), never on an open one;
 *  - sends IOC limits through the mark by a learned allowance (HlRisk.slipLimit of the coin's measured slippage),
 *    reductions first so margin is freed before it is used, and a flip closes before it reopens;
 *  - reads every fill and feeds its price back to HlCosts; a coin whose order is refused rests for [BACKOFF_MS]
 *    (a market that keeps refusing is not retried every cycle).
 */
object HlExecutor {
    private const val BACKOFF_MS = 30L * 60_000L

    data class Live(val markets: Map<String, HlApi.Asset>, val accounts: Map<String, HlApi.Account>) {
        val positions: Map<String, HlApi.Position> get() = accounts.values.flatMap { it.positions }.filter { it.szi != 0.0 }.associateBy { it.coin }
        val equity: Double get() = accounts.values.sumOf { a -> a.accountValue.takeIf { it.isFinite() } ?: 0.0 }
    }

    private val backoffUntil = ConcurrentHashMap<String, Long>()
    private val leverageSet = ConcurrentHashMap<String, Int>()      // coin -> leverage last accepted (signed: < 0 isolated)
    @Volatile private var lastNonce = 0L

    private fun nextNonce(): Long = synchronized(this) { maxOf(System.currentTimeMillis(), lastNonce + 1).also { lastNonce = it } }

    /** Markets and accounts on [base] for Hyperliquid's own dex and each named HIP-3 dex; null when the main dex is silent. */
    fun read(base: String, user: String, dexNames: Set<String>): Live? {
        val names = HlApi.perpDexs(base) ?: listOf("")
        val markets = HashMap<String, HlApi.Asset>()
        val accounts = HashMap<String, HlApi.Account>()
        for (dex in (setOf("") + dexNames)) {
            val idx = names.indexOf(dex)
            if (idx < 0) continue                                     // that dex does not exist on this network
            val m = HlApi.metaAndCtxs(base, dex, idx)
            val acct = HlApi.account(base, user, dex)
            if (m == null || acct == null) { if (dex.isEmpty()) return null else continue }
            for (x in m.assets) markets[x.name] = x
            accounts[dex] = acct
        }
        return Live(markets, accounts)
    }

    private fun send(cfg: HlSettings.Config, base: String, action: Map<String, Any?>): String? {
        val nonce = nextNonce()
        return try { HlApi.exchange(base, action, nonce, HlSigner.signL1Action(action, nonce, cfg.agentKey, mainnet = !cfg.testnet)) }
        catch (_: Throwable) { null }
    }

    private fun ensureLeverage(cfg: HlSettings.Config, base: String, a: HlApi.Asset, lev: Int, isolated: Boolean, note: (String) -> Unit): Boolean {
        val key = if (isolated) -lev else lev
        if (leverageSet[a.name] == key) return true
        val ok = HlApi.parseOk(send(cfg, base, HlWire.updateLeverageAction(a.index, !isolated, lev)))
        note("LEVERAGE ${a.name} ${lev}x ${if (isolated) "isolated" else "cross"} -> ${if (ok) "ok" else "refused"}")
        if (ok) leverageSet[a.name] = key
        return ok
    }

    /** Sends one order; returns the filled size (signed) or 0. */
    private fun order(cfg: HlSettings.Config, base: String, a: HlApi.Asset, isBuy: Boolean, sz: Double, reduceOnly: Boolean,
                      why: String, now: Long, note: (String) -> Unit): Double {
        val slip = HlRisk.slipLimit(HlCosts.costSide(a.name) - HlCosts.feeOf(a.name))
        val px = HlWire.roundPx(a.markPx * (if (isBuy) 1.0 + slip else 1.0 - slip), a.szDecimals)
        if (!(px > 0.0)) return 0.0
        val action = HlWire.orderAction(a.index, isBuy, HlWire.floatToWire(px), HlWire.floatToWire(sz), reduceOnly)
        val result = HlApi.parseOrderResponse(send(cfg, base, action)).firstOrNull()
        val side = if (isBuy) "BUY" else "SELL"
        return when (result) {
            is HlApi.OrderResult.Filled -> {
                HlCosts.observeFill(a.name, isBuy, a.markPx, result.avgPx)
                note("$why $side ${a.name} ${HlWire.floatToWire(result.totalSz)} @ ${result.avgPx} (mark ${a.markPx})${if (reduceOnly) " reduceOnly" else ""}")
                if (isBuy) result.totalSz else -result.totalSz
            }
            is HlApi.OrderResult.Resting -> { note("$why $side ${a.name} resting oid=${result.oid} (IOC should not rest)"); 0.0 }
            is HlApi.OrderResult.Rejected -> {
                backoffUntil[a.name] = now + BACKOFF_MS
                note("$why $side ${a.name} sz=${HlWire.floatToWire(sz)} REFUSED: ${result.error.take(120)} (rests 30 min)")
                0.0
            }
            null -> { note("$why $side ${a.name}: no answer"); 0.0 }
        }
    }

    /** Moves every live position toward [plans]; coins held but not planned are closed. Returns orders sent. */
    fun rebalance(cfg: HlSettings.Config, base: String, live: Live, plans: List<HlRisk.Plan>, why: String, now: Long, note: (String) -> Unit): Int {
        val want = plans.associateBy { it.coin }
        val held = live.positions
        val coins = (want.keys + held.keys).filter { live.markets.containsKey(it) }
        // reductions first: every coin whose exposure falls
        val ordered = coins.sortedBy { c ->
            val cur = (held[c]?.szi ?: 0.0) * (live.markets[c]?.markPx ?: 0.0)
            if (kotlin.math.abs(want[c]?.targetUsd ?: 0.0) < kotlin.math.abs(cur)) 0 else 1
        }
        var sent = 0
        for (c in ordered) {
            val a = live.markets.getValue(c)
            if ((backoffUntil[c] ?: 0L) > now) continue
            var cur = held[c]?.szi ?: 0.0
            val p = want[c]
            for (step in 0 until 2) {                                // a flip: close, then open the other side
                val o = HlRisk.orderFor(cur, p?.targetUsd ?: 0.0, a.markPx, a.szDecimals) ?: break
                // a new position gets its margin mode and leverage first; one already open keeps what it was opened with
                if (!o.third && p != null && cur == 0.0 && !ensureLeverage(cfg, base, a, p.leverage, p.isolated, note)) break
                val filled = order(cfg, base, a, o.first, o.second, o.third, why, now, note)
                sent++
                if (filled == 0.0) break
                cur += filled
                if (kotlin.math.abs(cur) * a.markPx < 1e-6) cur = 0.0
            }
        }
        return sent
    }

    /** Closes every position on every dex read in [live], reduce-only. */
    fun flatten(cfg: HlSettings.Config, base: String, live: Live, why: String, now: Long, note: (String) -> Unit) {
        for ((coin, p) in live.positions) {
            val a = live.markets[coin] ?: continue
            order(cfg, base, a, p.szi < 0.0, kotlin.math.abs(p.szi), true, why, now, note)
        }
    }

    fun backoffLine(now: Long): String = backoffUntil.filter { it.value > now }.keys.joinToString(",").ifBlank { "none" }
}
