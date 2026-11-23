package com.lifecyclebot.engine.market

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.BotRuntimeController
import com.lifecyclebot.engine.HandoffRoute8180
import com.lifecyclebot.engine.truth.CandleCoverage8284
import com.lifecyclebot.engine.truth.ChainConfirm8950
import com.lifecyclebot.engine.truth.CurveReserves7951
import com.lifecyclebot.engine.truth.OnChainDepth8038
import com.lifecyclebot.engine.truth.PocketHunt8961
import com.lifecyclebot.engine.truth.TokenFacts
import java.util.concurrent.ConcurrentHashMap

/** A bounded discovery read keeps its off-watch state until an actual answer can enter the ordinary protected intake. */
object PocketRecovery8961 {
    data class Pending(val row: MarketSweep7297.Row, val token: TokenState, val lanes: Set<String>,
                       val askedAtMs: Long, val generation: Long,
                       val poolReadStarted: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false))

    private val pending = ConcurrentHashMap<String, Pending>()
    private const val CAP = 32
    private const val TTL_MS = 120_000L
    private fun live(): Boolean = try { !com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false }

    internal fun answerCurrent(askedAtMs: Long, sourceAtMs: Long, nowMs: Long, generation: Long, currentGeneration: Long): Boolean =
        generation == currentGeneration && askedAtMs > 0L && nowMs - askedAtMs in 0L..TTL_MS &&
            sourceAtMs >= askedAtMs && sourceAtMs <= nowMs && nowMs - sourceAtMs <= 15_000L

    /** Never inserts a token into the watchlist or publishes a specialist claim. The original row clocks remain intact. */
    fun request(row: MarketSweep7297.Row, lanes: Set<String>, nowMs: Long) {
        if (!live() || !PocketHunt8961.fresh(row.observedAtMs8169, nowMs)) return
        val watched = HandoffRoute8180.tokenFor8180(row.mint)
        val generation = BotRuntimeController.currentGeneration()
        val pool = com.lifecyclebot.network.DexPools8285.chosen8285(row.mint)?.takeIf { it != row.mint }.orEmpty()
        val ts = watched ?: synchronized(pending) {
            pending.entries.removeIf { nowMs - it.value.askedAtMs !in 0L..TTL_MS || it.value.generation != generation }
            pending[row.mint]?.token ?: run {
                if (pending.size >= CAP) return
                val state = TokenState(mint = row.mint, symbol = row.symbol, name = row.name, pairAddress = pool)
                seed(state, row, nowMs)
                pending[row.mint] = Pending(row, state, lanes.toSet(), nowMs, generation)
                state
            }
        }
        // A pair-book update can race its callback. Finding the pool here must start the same one-shot read,
        // not mark it started and then lose the read to ChainConfirm's existing per-input retry gap.
        if (watched == null && pool.isNotBlank() && poolAvailable(row.mint, pool, nowMs)) return
        ChainConfirm8950.confirm8950(ts, listOf("PRICE", "HISTORY"), "LANE", nowMs)
    }

    /** The shared pair reader found the missing venue: one follow-up through the existing read budgets, not another hunt. */
    fun poolAvailable(mint: String, pool: String, nowMs: Long): Boolean {
        if (!live()) return false
        val p = pending[mint] ?: return false
        return startPoolRead8961(p, pool, nowMs, BotRuntimeController.currentGeneration()) {
            ChainConfirm8950.armDepth8959(mint, nowMs)
            OnChainDepth8038.needDepth8047(p.token, nowMs, decisionPing8959 = true)
            com.lifecyclebot.engine.truth.StageRead8950.askChain8950(p.token, nowMs)
        }
    }

    /** Both discovery and the asynchronous pair callback claim and start the read together. */
    internal fun startPoolRead8961(p: Pending, pool: String, nowMs: Long, generation: Long,
                                   read: () -> Unit): Boolean {
        if (pool.isBlank() || pool == p.token.mint || nowMs - p.askedAtMs !in 0L..TTL_MS ||
            p.generation != generation || !p.poolReadStarted.compareAndSet(false, true)) return false
        p.token.lastPricePoolAddr = pool
        p.token.tokenMap.poolAddress = pool
        p.token.tokenMap.pairAddress = pool
        try {
            read()
        } catch (t: Throwable) {
            p.poolReadStarted.set(false)
            throw t
        }
        return true
    }

    private fun seed(ts: TokenState, row: MarketSweep7297.Row, nowMs: Long) {
        val at = row.observedAtMs8169
        val source = "MARKET_POCKET_8961"
        ts.source = source
        TokenFacts.writePrice(ts, row.priceUsd, source, TokenFacts.Rank.AGGREGATE, at, nowMs = nowMs)
        TokenFacts.writeSnapshot(ts, TokenFacts.Field.MCAP_USD, row.mcapUsd, source, TokenFacts.Rank.AGGREGATE, at, nowMs)
        TokenFacts.writeLiquidity(ts, row.liquidityUsd, source, TokenFacts.Rank.AGGREGATE, at, nowMs)
        if (row.hourChangeObserved8417) TokenFacts.writeSnapshot(ts, TokenFacts.Field.CHANGE_1H_PCT,
            row.priceChangeH1Pct, source, TokenFacts.Rank.AGGREGATE, at, nowMs)
        if (row.priceChange5mPct.isFinite()) TokenFacts.writeSnapshot(ts, TokenFacts.Field.CHANGE_5M_PCT,
            row.priceChange5mPct, source, TokenFacts.Rank.AGGREGATE, at, nowMs)
    }

    /** Only ChainConfirm's witnessed market answer or StageRead's completed candle merge may consume the pending read. */
    fun takeAnswered(mint: String, historyAnswered: Boolean, generation: Long, nowMs: Long): Pending? {
        if (!live()) return null
        val p = pending[mint] ?: return null
        if (generation != BotRuntimeController.currentGeneration()) return null
        val curve = CurveReserves7951.accountObservation8522(mint, nowMs, 15_000L)
        val solUsd = if (curve != null) com.lifecyclebot.engine.WalletManager.freshSolPrice8163(nowMs) else Double.NaN
        val depth = if (curve != null) {
            // A real empty curve must not fall back to the sweep's earlier positive liquidity.
            if (curve.realSol <= 0.0 && answerCurrent(p.askedAtMs, curve.observedAtMs, nowMs, p.generation, generation)) {
                pending.remove(mint, p); return null
            }
            (curve.realSol * solUsd).takeIf { it.isFinite() && it > 0.0 }?.let { it to curve.observedAtMs }
        } else OnChainDepth8038.chainDepthRead8551(mint, nowMs)
        val history = historyAnswered && CandleCoverage8284.oneMinuteBars8284(mint).isNotEmpty()
        // A history answer can beat the liquidity reply. Keep the pending read so the later depth answer can recover it.
        if (depth == null && !(p.token.lastLiquidityUsd.isFinite() && p.token.lastLiquidityUsd > 0.0)) return null
        val sourceAt = if (history) nowMs else depth?.second ?: 0L
        if (!answerCurrent(p.askedAtMs, sourceAt, nowMs, p.generation, generation)) {
            if (p.generation != generation || nowMs - p.askedAtMs !in 0L..TTL_MS) pending.remove(mint, p)
            return null
        }
        if (!pending.remove(mint, p)) return null
        if (depth != null) TokenFacts.writeLiquidity(p.token, depth.first, if (curve != null) "CURVE_READ_8046" else "CHAIN_VAULT_8038",
            TokenFacts.Rank.CHAIN, depth.second, nowMs)
        if (curve != null && solUsd.isFinite() && solUsd > 0.0) {
            TokenFacts.writePrice(p.token, CurveReserves7951.priceSol7951(curve.virtualSol, curve.virtualTokens) * solUsd,
                "CURVE_READ_8046", TokenFacts.Rank.CHAIN, curve.observedAtMs, nowMs = nowMs)
            CurveReserves7951.hydrateMcap8399(p.token, solUsd, nowMs)
        }
        return p
    }

    /** After protected intake accepted, copy only observed facts and the shared, quality-tagged candles. */
    fun applyAnswered(p: Pending, target: TokenState, nowMs: Long) {
        if (target.mint != p.token.mint) return
        val from = p.token
        if (target.lastPricePoolAddr.isBlank()) target.lastPricePoolAddr = from.lastPricePoolAddr
        if (target.tokenMap.poolAddress.isBlank()) target.tokenMap.poolAddress = from.tokenMap.poolAddress
        if (target.tokenMap.pairAddress.isBlank()) target.tokenMap.pairAddress = from.tokenMap.pairAddress
        for ((field, fact) in from.facts8551) {
            if (!PocketHunt8961.fresh(fact.observedAtMs, nowMs)) continue
            when (field) {
                TokenFacts.Field.PRICE_USD -> TokenFacts.writePrice(target, fact.value, fact.source, fact.rank,
                    fact.observedAtMs, pool = from.lastPricePoolAddr, nowMs = nowMs)
                TokenFacts.Field.LIQUIDITY_USD -> TokenFacts.writeLiquidity(target, fact.value, fact.source, fact.rank, fact.observedAtMs, nowMs)
                else -> TokenFacts.writeSnapshot(target, field, fact.value, fact.source, fact.rank, fact.observedAtMs, nowMs)
            }
        }
        for ((quality, bars) in CandleCoverage8284.oneMinuteBars8284(target.mint).groupBy(CandleCoverage8284::qualityOf8284))
            CandleCoverage8284.merge8284(target, bars, quality, "POCKET_CHAIN_READ_8961", from.lastPricePoolAddr, nowMs)
        target.laneAffinity.addAll(p.lanes)
    }
}
