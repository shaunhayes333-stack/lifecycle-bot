package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8551 §ONE_DATA_STORE — the single market-data store every brain and reader shares (docs/DATA_MAP.md).
 *
 * The store is the TokenState row of the mint in BotService.status.tokens (fed by DataOrchestrator, the scanners, the
 * chain readers). Each migrated field holds ONE [Fact]: the value, who observed it, how strong that source is ([Rank])
 * and WHEN it was observed (the provider's / chain's own time, never the time we happened to copy it). Exactly one write
 * path per field lives here; the producers (DexScreener WS / pair poll / batch, Birdeye, Raydium TVL, the pump.fun curve
 * frames and account reads, the pool vault reads, the restore caches) are callers of that path, not owners of the field.
 * The legacy fields (ts.lastLiquidityUsd, ts.tokenMap.liquidityUsd) are MIRRORS of the fact, so every existing reader
 * reads the store without being rewritten; Aate8551TokenFactsTest refuses any new direct write.
 *
 * The replacement rule ([accepts]) is the same for every field:
 *  - a value that is not a reading (NaN, infinite, <= 0) is not a fact: "not reported" never erases an observation;
 *  - a [Rank.RESTORE] value (archive, meta cache, saved position — observed at an unknown time) only fills an empty slot
 *    or refreshes another restore; any real observation replaces it;
 *  - an observation older than the held fact never replaces it, whatever its rank;
 *  - a newer (or same-time) observation of equal or higher rank replaces the held fact, up or down;
 *  - a newer observation of LOWER rank replaces it only once the stronger fact has aged past its hold window
 *    ([holdMs]: 90 s for the chain, 30 s otherwise) — the chain's depth is not overwritten by an API's figure the next
 *    second, and a dead chain read does not pin the field forever.
 *
 * PRICE_USD (8552) follows the same rule with one difference stated in [acceptsPrice]: a price moves on every trade, so
 * only a CHAIN tick holds the field (60 s, the ChainFedMarks8418 window), and a correction of the held observation (a unit
 * rebase, a capped paper exit) replaces the value but keeps the observation's own time ([correctPrice]).
 *
 * The snapshot fields (V5.0.8553: buy / sell pressure, 5 m / 1 h change, market cap, FDV, top-holder share) follow the
 * price rule ([acceptsPrice]: newest observation wins, a CHAIN fact holds 60 s, restores only fill empty slots) with their
 * own validity ([validFor]: a change can be 0 or negative, a pressure is 0..100), through one writer ([writeSnapshot]).
 * Graduation (D8) has its own sticky marker ([markGraduated]).
 *
 * Migrated: LIQUIDITY_USD (8551), PRICE_USD (8552), the snapshot fields + graduation (8553). See docs/DATA_MAP.md.
 */
object TokenFacts {
    enum class Field { LIQUIDITY_USD, PRICE_USD, BUY_PRESSURE_PCT, SELL_PRESSURE_PCT, CHANGE_5M_PCT, CHANGE_1H_PCT, MCAP_USD, FDV_USD, TOP_HOLDER_PCT, TX_PER_MIN, SUPPLY_WHOLE }

    /** Source strength. Higher outranks lower inside the hold window. */
    object Rank {
        /** Archive / meta cache / saved position: value without a trustworthy observation time. */
        const val RESTORE = 0
        /** Derived or aggregated (intake scanner figure, resolver fill, token-map copy, route cache). */
        const val AGGREGATE = 1
        /** A market-data provider's own figure for the pool (DexScreener pair / WS / batch, Birdeye, Raydium TVL). */
        const val PROVIDER = 2
        /** The chain itself: pool vault balances, the pump.fun curve account / its trade frames. */
        const val CHAIN = 3
    }

    data class Fact(val value: Double, val source: String, val rank: Int, val observedAtMs: Long)

    private const val CHAIN_HOLD_MS = 90_000L
    private const val HOLD_MS = 30_000L
    private const val FUTURE_SKEW_MS = 5_000L

    /** Pure: how long a held fact of [rank] blocks a weaker source. */
    fun holdMs(rank: Int): Long = if (rank >= Rank.CHAIN) CHAIN_HOLD_MS else HOLD_MS

    /** Pure: is [value] a reading at all (finite and above 0)? */
    fun isReading(value: Double): Boolean = value.isFinite() && value > 0.0

    /**
     * Pure — THE replacement rule. [legacyHeld]: the mirrored legacy field already holds a reading that no fact accounts for
     * (a row created with a value, or a copy); a restore never overwrites it, a real observation does.
     */
    fun accepts(prior: Fact?, offered: Fact, legacyHeld: Boolean): Boolean {
        if (!isReading(offered.value)) return false
        if (offered.rank <= Rank.RESTORE) return if (prior == null) !legacyHeld else prior.rank <= Rank.RESTORE
        if (prior == null || prior.rank <= Rank.RESTORE) return true
        if (offered.observedAtMs < prior.observedAtMs) return false
        if (offered.rank >= prior.rank) return true
        return offered.observedAtMs - prior.observedAtMs >= holdMs(prior.rank)
    }

    /** Pure: the time a fact is stored with — restores carry 0, a clock ahead of [nowMs] is clamped to it. */
    fun stampOf(rank: Int, observedAtMs: Long, nowMs: Long): Long = when {
        rank <= Rank.RESTORE -> 0L
        observedAtMs <= 0L -> nowMs
        observedAtMs > nowMs + FUTURE_SKEW_MS -> nowMs
        else -> observedAtMs
    }

    private val accepted = ConcurrentHashMap<String, AtomicLong>()   // "FIELD:source" -> accepted writes
    private val refused = ConcurrentHashMap<String, AtomicLong>()    // "FIELD:why" -> refused offers

    private fun count(map: ConcurrentHashMap<String, AtomicLong>, key: String) {
        if (map.size >= 192 && !map.containsKey(key)) return
        map.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
    }

    /** V5.0.8658 — only these pump.fun-specific price/reserve sources require the pump mint suffix.
     * Ordinary AMM/PumpSwap/DEX quotes remain valid for other launchpads. */
    internal fun pumpCurveMetricSource8658(source: String?): Boolean {
        val s = source?.uppercase().orEmpty()
        return s == "PUMP_FUN_BC_SYNTHETIC" || s.startsWith("PUMP_CURVE_RPC") ||
            s == "PUMP_PORTAL_TRADE_WS_7278" || s.startsWith("HELIUS_CURVE_8354") ||
            s.startsWith("CURVE_RESERVES_7951") || s.startsWith("CURVE_FRAME_7951") ||
            s.startsWith("CURVE_ACCOUNT_7951") || s.startsWith("CURVE_READ_8046") ||
            s.startsWith("CURVE_PRICE_IMPLIED_8173")
    }

    private fun nonPumpCurveOffer8658(ts: TokenState, source: String?): Boolean =
        pumpCurveMetricSource8658(source) && !com.lifecyclebot.network.PumpCurveKeys7269.isPumpMint8658(ts.mint)

    /** The fact [ts] holds for [field], or null when nothing has been observed through the store. */
    fun fact(ts: TokenState, field: Field): Fact? = ts.facts8551[field]

    /**
     * THE liquidity write. Every producer offers its reading here with its source, [rank] and observation time; the store
     * keeps it only under [accepts], mirrors it into ts.lastLiquidityUsd and ts.tokenMap.liquidityUsd, and treats a first
     * reading as the hydration it is (HydrationReeval8343: the deferred lanes resume on the arrival, not the next cycle).
     * Returns true when the stored value changed.
     */
    fun writeLiquidity(ts: TokenState, usd: Double, source: String, rank: Int, observedAtMs: Long,
                       nowMs: Long = System.currentTimeMillis()): Boolean {
        if (nonPumpCurveOffer8658(ts, source)) { count(refused, "LIQUIDITY_USD:NON_PUMP_CURVE_8658"); return false }
        if (!isReading(usd)) { count(refused, "LIQUIDITY_USD:NOT_A_READING"); return false }
        val offered = Fact(usd, source, rank, stampOf(rank, observedAtMs, nowMs))
        var before = 0.0
        var prior: Fact? = null
        synchronized(ts.facts8551) {
            prior = ts.facts8551[Field.LIQUIDITY_USD]
            before = ts.lastLiquidityUsd
            if (!accepts(prior, offered, isReading(before) && prior == null)) {
                count(refused, "LIQUIDITY_USD:" + refusal(prior, offered))
                return false
            }
            ts.facts8551[Field.LIQUIDITY_USD] = offered
            ts.lastLiquidityUsd = usd
            try { ts.tokenMap.liquidityUsd = usd } catch (_: Throwable) {}
        }
        count(accepted, "LIQUIDITY_USD:$source")
        if (rank > Rank.RESTORE) archive(ts, nowMs)
        // The chain replacing an API figure: keep the figure it replaced, so the buy-time cross-check (OnChainDepth8038
        // .verifyBeforeBuy) still compares what the APIs claimed against what the vaults hold.
        if (rank >= Rank.CHAIN && isReading(before) && (prior?.rank ?: Rank.AGGREGATE) < Rank.CHAIN && before != usd)
            try { OnChainDepth8038.noteApi8038(ts.mint, before) } catch (_: Throwable) {}
        // A restore is not an observation: it neither advances the feature revision nor counts as a hydration arrival.
        if (rank <= Rank.RESTORE) return before != usd
        if (before != usd) try { FeatureRevision8283.bumpThrottled8286(ts.mint, FeatureKind8283.LIQUIDITY, nowMs) } catch (_: Throwable) {}
        if (!isReading(before)) try { com.lifecyclebot.engine.HydrationReeval8343.onHydrated8343(ts, usd, nowMs, source) } catch (_: Throwable) {}
        return before != usd
    }

    /** Re-mirrors the held facts into the legacy fields after a caller swapped [ts]'s token map (route cache hydrate). */
    fun mirror(ts: TokenState) {
        ts.facts8551[Field.LIQUIDITY_USD]?.let { liq -> try { ts.tokenMap.liquidityUsd = liq.value } catch (_: Throwable) {} }
        ts.facts8551[Field.PRICE_USD]?.let { px ->
            try { val tm = ts.tokenMap; if (px.observedAtMs > tm.priceObservedAtMs7858) { tm.priceUsd = px.value; tm.priceObservedAtMs7858 = px.observedAtMs } } catch (_: Throwable) {}
        }
        // V5.0.8554 — the swapped-in map carries the store's cap, FDV and top-holder share too (not the cached map's)
        try {
            val tm = ts.tokenMap
            ts.facts8551[Field.MCAP_USD]?.let { tm.marketCap = it.value }
            ts.facts8551[Field.FDV_USD]?.let { tm.fdv = it.value }
            ts.facts8551[Field.TOP_HOLDER_PCT]?.let { tm.topHolderConcentrationPct = it.value }
        } catch (_: Throwable) {}
    }

    /** A restore (meta cache / hive / saved position): fills only an empty slot, observation time unknown. */
    fun restoreLiquidity(ts: TokenState, usd: Double, source: String): Boolean = writeLiquidity(ts, usd, source, Rank.RESTORE, 0L)

    // ── PRICE_USD (V5.0.8552) ────────────────────────────────────────────────────────────────────────────────────────

    /** = ChainFedMarks8418's window: a poll may replace a chain tick only once the chain feed has gone quiet. */
    const val PRICE_CHAIN_HOLD_MS = 60_000L

    /**
     * Pure — the price rule. [prior] is the held fact, or the legacy fields read as one (a row created or copied with a
     * price: observed at its lastPriceUpdate, a restore when that is 0). Not a reading -> no; a restore fills only an empty
     * or restored slot; an older observation never replaces; a newer one replaces unless a CHAIN tick holds the field and
     * the offer is weaker and within [PRICE_CHAIN_HOLD_MS] of it.
     */
    fun acceptsPrice(prior: Fact?, offered: Fact): Boolean {
        if (!isReading(offered.value)) return false
        if (offered.rank <= Rank.RESTORE) return prior == null || prior.rank <= Rank.RESTORE
        if (prior == null || prior.rank <= Rank.RESTORE) return true
        // V5.0.8559 — a derived seed (intake cap / supply, curve synthesis) is re-stamped "now" on every intake sighting, so a
        // provider's dated pair price (its fetch time, up to the pair cache's 135 s) could never replace it and the seed stayed
        // the coin's mark (8557: the paper book's EXPRESS book read +5,139,752 %). A provider / chain reading replaces a seed
        // it is no more than [SEED_YIELD_MS] older than.
        if (seedPrice8559(prior.source) && offered.rank >= Rank.PROVIDER && !seedPrice8559(offered.source) &&
            prior.observedAtMs - offered.observedAtMs <= SEED_YIELD_MS) return true
        if (offered.observedAtMs < prior.observedAtMs) return false
        if (offered.rank >= prior.rank || prior.rank < Rank.CHAIN) return true
        return offered.observedAtMs - prior.observedAtMs > PRICE_CHAIN_HOLD_MS
    }

    const val SEED_YIELD_MS = 150_000L

    /** Pure (V5.0.8559): a price derived from a cap / supply or a curve synthesis, not observed in a trade or a pool. */
    fun seedPrice8559(source: String?): Boolean = source == "PUMP_FUN_BC_SYNTHETIC" || source == EntryBasisSeed7280.CHAIN_SUPPLY_CAP_SEED

    /** The price [ts] holds, as a fact: the store's, else its legacy fields read as one (null when it has no price). */
    fun priceFact(ts: TokenState): Fact? = ts.facts8551[Field.PRICE_USD]
        ?: ts.lastPrice.takeIf { isReading(it) }?.let { Fact(it, ts.lastPriceSource, if (ts.lastPriceUpdate > 0L) Rank.AGGREGATE else Rank.RESTORE, ts.lastPriceUpdate) }

    /**
     * THE price write. [usd] observed by [source] ([rank]) at [observedAtMs] — the provider's / chain's own time; a restore
     * is stored at 0 ("never observed here"). Kept only under [acceptsPrice]; then mirrored into ts.lastPrice /
     * lastPriceUpdate / lastPriceSource (+ [pool] / [dex] when given) and the token map's price when it is newer there.
     * [source] null keeps the row's source label (a restore whose caller labels it). Returns true when the fact was kept.
     */
    fun writePrice(ts: TokenState, usd: Double, source: String?, rank: Int, observedAtMs: Long,
                   nowMs: Long = System.currentTimeMillis(), pool: String? = null, dex: String? = null): Boolean {
        if (nonPumpCurveOffer8658(ts, source ?: ts.lastPriceSource)) { count(refused, "PRICE_USD:NON_PUMP_CURVE_8658"); return false }
        if (!isReading(usd)) { count(refused, "PRICE_USD:NOT_A_READING"); return false }
        val offered = Fact(usd, source ?: ts.lastPriceSource, rank, stampOf(rank, observedAtMs, nowMs))
        synchronized(ts.facts8551) {
            val prior = priceFact(ts)
            if (!acceptsPrice(prior, offered)) { count(refused, "PRICE_USD:" + refusal(prior, offered)); return false }
            ts.facts8551[Field.PRICE_USD] = offered
            ts.lastPrice = usd
            ts.lastPriceUpdate = offered.observedAtMs
            if (source != null) ts.lastPriceSource = source
            if (!pool.isNullOrBlank()) ts.lastPricePoolAddr = pool
            if (!dex.isNullOrBlank()) ts.lastPriceDex = dex
            try {
                val tm = ts.tokenMap
                if (offered.observedAtMs > 0L && offered.observedAtMs >= tm.priceObservedAtMs7858) { tm.priceUsd = usd; tm.priceObservedAtMs7858 = offered.observedAtMs }
            } catch (_: Throwable) {}
        }
        count(accepted, "PRICE_USD:" + (source ?: "RESTORE").take(48))
        if (rank > Rank.RESTORE) archive(ts, nowMs)
        if (rank > Rank.RESTORE) try { FeatureRevision8283.bumpThrottled8286(ts.mint, FeatureKind8283.PRICE, nowMs) } catch (_: Throwable) {}
        return true
    }

    /** A restore (archive / meta cache / saved position without a source time): fills only an empty slot, stored at time 0. */
    fun restorePrice(ts: TokenState, usd: Double, source: String?): Boolean = writePrice(ts, usd, source, Rank.RESTORE, 0L)

    /**
     * The held observation CORRECTED, not a new one: a unit / basis rebase of the same reading (Executor 7017 / 7059) or a
     * capped paper exit price. The value is replaced; the observation time stays the observation's (a repair never makes a
     * mark look fresher than the reading it repairs). Returns false when there is no observation to correct.
     */
    fun correctPrice(ts: TokenState, usd: Double, source: String): Boolean {
        if (!isReading(usd)) return false
        // nothing held to correct: the value is offered as what it is, a derived reading now
        if (priceFact(ts) == null) return writePrice(ts, usd, source, Rank.AGGREGATE, System.currentTimeMillis())
        synchronized(ts.facts8551) {
            val prior = priceFact(ts) ?: return false
            // V5.0.8559 — a correction is derived: it never carries the CHAIN hold of the reading it repairs
            ts.facts8551[Field.PRICE_USD] = prior.copy(value = usd, source = source, rank = minOf(prior.rank, Rank.AGGREGATE))
            ts.lastPrice = usd
            ts.lastPriceSource = source
        }
        count(accepted, "PRICE_USD:CORRECTION")
        return true
    }

    // ── snapshot fields (V5.0.8553, DATA_MAP D2 / D3 / D6) ─────────────────────────────────────────────────────────────

    /** Pure: is [v] a reading of [field]? Changes may be 0 or negative; pressures and shares are percentages. */
    fun validFor(field: Field, v: Double): Boolean = v.isFinite() && when (field) {
        Field.BUY_PRESSURE_PCT, Field.SELL_PRESSURE_PCT -> v in 0.0..100.0
        Field.TOP_HOLDER_PCT -> v in 0.0..100.0   // both slots are nullable: null is unread, a measured 0 % is a reading (8557)
        Field.CHANGE_5M_PCT, Field.CHANGE_1H_PCT -> v > -100.0
        Field.TX_PER_MIN -> v >= 0.0
        else -> v > 0.0
    }

    /** The held fact of a snapshot field, or the legacy fields read as one (observed at their stamp; a restore at 0). */
    fun snapshotFact(ts: TokenState, field: Field): Fact? {
        ts.facts8551[field]?.let { return it }
        fun legacy(v: Double, at: Long) = if (validFor(field, v)) Fact(v, "LEGACY", if (at > 0L) Rank.AGGREGATE else Rank.RESTORE, at) else null
        return when (field) {
            // a pressure / change never stamped is the 50 / 0 default, not a reading
            Field.BUY_PRESSURE_PCT -> if (ts.buyPressureObservedAtMs8084 > 0L) legacy(ts.lastBuyPressurePct, ts.buyPressureObservedAtMs8084) else null
            Field.SELL_PRESSURE_PCT -> if (ts.buyPressureObservedAtMs8084 > 0L) legacy(ts.lastSellPressurePct, ts.buyPressureObservedAtMs8084) else null
            Field.CHANGE_5M_PCT -> if (ts.priceChangeObservedAtMs8084 > 0L) legacy(ts.lastPriceChange5m, ts.priceChangeObservedAtMs8084) else null
            Field.CHANGE_1H_PCT -> if (ts.hourChangeObservedAtMs8506 > 0L) legacy(ts.lastPriceChange1h, ts.hourChangeObservedAtMs8506) else null
            Field.MCAP_USD -> legacy(ts.lastMcap, 0L)
            Field.FDV_USD -> legacy(ts.lastFdv, 0L)
            Field.TOP_HOLDER_PCT -> (ts.topHolderPct ?: ts.tokenMap.topHolderConcentrationPct)?.let { legacy(it, 0L) }
            else -> null
        }
    }

    /**
     * THE snapshot-field write: [value] of [field] observed by [source] ([rank]) at [observedAtMs], kept under the price rule
     * with [validFor]; then mirrored into the legacy fields and their observation stamps (pressure / change stamps are what the
     * lanes' freshness windows read, so a refused offer can no longer re-date them). Returns true when the fact was kept.
     */
    fun writeSnapshot(ts: TokenState, field: Field, value: Double, source: String, rank: Int, observedAtMs: Long,
                      nowMs: Long = System.currentTimeMillis()): Boolean {
        if (nonPumpCurveOffer8658(ts, source)) { count(refused, "${field.name}:NON_PUMP_CURVE_8658"); return false }
        if (!validFor(field, value)) { count(refused, "${field.name}:NOT_A_READING"); return false }
        val offered = Fact(value, source, rank, stampOf(rank, observedAtMs, nowMs))
        synchronized(ts.facts8551) {
            val prior = snapshotFact(ts, field)
            val ok = if (offered.rank <= Rank.RESTORE) prior == null || prior.rank <= Rank.RESTORE
                else prior == null || prior.rank <= Rank.RESTORE || (offered.observedAtMs >= prior.observedAtMs &&
                    (offered.rank >= prior.rank || prior.rank < Rank.CHAIN || offered.observedAtMs - prior.observedAtMs > PRICE_CHAIN_HOLD_MS))
            if (!ok) { count(refused, "${field.name}:" + if (prior != null && offered.observedAtMs < prior.observedAtMs) "OLDER_THAN_HELD" else "HELD_BY_STRONGER"); return false }
            ts.facts8551[field] = offered
            mirrorSnapshot(ts, field, value, offered.observedAtMs)
        }
        count(accepted, "${field.name}:" + source.take(40))
        if (rank > Rank.RESTORE && (field == Field.MCAP_USD || field == Field.FDV_USD)) archive(ts, nowMs)
        if (rank > Rank.RESTORE) try {
            val kind = when (field) {
                Field.BUY_PRESSURE_PCT, Field.SELL_PRESSURE_PCT -> FeatureKind8283.POOL_FLOW
                Field.CHANGE_5M_PCT, Field.CHANGE_1H_PCT -> FeatureKind8283.PRICE
                else -> null
            }
            if (kind != null) FeatureRevision8283.bumpThrottled8286(ts.mint, kind, nowMs)
        } catch (_: Throwable) {}
        return true
    }

    private fun mirrorSnapshot(ts: TokenState, field: Field, v: Double, at: Long) {
        when (field) {
            Field.BUY_PRESSURE_PCT -> { ts.lastBuyPressurePct = v; if (at > 0L) ts.buyPressureObservedAtMs8084 = at }
            Field.SELL_PRESSURE_PCT -> ts.lastSellPressurePct = v
            Field.CHANGE_5M_PCT -> { ts.lastPriceChange5m = v; if (at > 0L) ts.priceChangeObservedAtMs8084 = at }
            Field.CHANGE_1H_PCT -> { ts.lastPriceChange1h = v; if (at > 0L) ts.hourChangeObservedAtMs8506 = at }
            Field.MCAP_USD -> { ts.lastMcap = v; try { ts.tokenMap.marketCap = v } catch (_: Throwable) {} }
            Field.FDV_USD -> { ts.lastFdv = v; try { ts.tokenMap.fdv = v } catch (_: Throwable) {} }
            Field.TOP_HOLDER_PCT -> { ts.topHolderPct = v; try { ts.tokenMap.topHolderConcentrationPct = v } catch (_: Throwable) {} }
            else -> {}
        }
    }

    /** Restores (meta cache / hive / saved position): fill only an empty slot, stored at time 0. */
    fun restoreMcap(ts: TokenState, usd: Double): Boolean = writeSnapshot(ts, Field.MCAP_USD, usd, "RESTORE", Rank.RESTORE, 0L)
    fun restoreFdv(ts: TokenState, usd: Double): Boolean = writeSnapshot(ts, Field.FDV_USD, usd, "RESTORE", Rank.RESTORE, 0L)

    private const val TX_FRESH_MS = 5L * 60_000L

    /**
     * V5.0.8553 — THE transaction-velocity reader (swaps per minute): the coin's own executed swaps when the tape holds a
     * flow read (FlowRead8546: 5-minute prints), else the store's fact (the pair poll's 5-minute counts, or its hour's
     * counts as a stand-in) while it is under 5 minutes old, else the live alpha cache. Superseded: only the alpha cache,
     * which is filled on the LIVE buy path alone — the edge dataset read NaN on 559 of 559 decisions, and the
     * TX_VELOCITY_BREAK setup / setup authority / Cortex tx voter never saw a value in paper.
     */
    fun txPerMin(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Double {
        val flow = try { FlowRead8546.read8546(ts.mint, nowMs) } catch (_: Throwable) { null }
        if (flow != null && flow.known) return (flow.buys5m + flow.sells5m) / 5.0
        ts.facts8551[Field.TX_PER_MIN]?.takeIf { nowMs - it.observedAtMs in 0L..TX_FRESH_MS }?.let { return it.value }
        return try { com.lifecyclebot.engine.DataPipeline.cachedAlphaSignals6486(ts.mint)?.txVelocity } catch (_: Throwable) { null } ?: Double.NaN
    }

    // ── side caches add to the store (V5.0.8554 cache bundle) ─────────────────────────────────────────────────────────

    /**
     * A DexScreener pair answer (single pair, price batch or pair batch — every producer of PairFlow8169) ADDS what it
     * carries to the row's facts at the answer's own time: buy / sell pressure (5-minute counts; the hour's counts as an
     * AGGREGATE stand-in), transactions per minute, 5 m / 1 h change, and the pool's liquidity unless the coin is still on
     * its pump.fun curve (the curve account is its depth). Superseded: these lived only in the pair cache, which the
     * resolver, stage router, continuation read and entry flow each pulled from on their own; the store and every other
     * reader never saw them. The cap is not taken here: a cap comes from verified supply (MintSupply8418).
     */
    fun notePairFlow(mint: String, f: PairFlow8169.Flow8169) {
        val ts = try { com.lifecyclebot.engine.BotService.status.tokens[mint.trim()] } catch (_: Throwable) { null } ?: return
        val at = f.atMs
        val m5 = if (f.buysM5 >= 0 && f.sellsM5 >= 0) f.buysM5 + f.sellsM5 else 0
        val h1 = if (f.buysH1 >= 0 && f.sellsH1 >= 0) f.buysH1 + f.sellsH1 else 0
        when {
            m5 > 0 -> {
                writeSnapshot(ts, Field.BUY_PRESSURE_PCT, f.buysM5 * 100.0 / m5, "DEX_PAIR_M5", Rank.PROVIDER, at)
                writeSnapshot(ts, Field.SELL_PRESSURE_PCT, f.sellsM5 * 100.0 / m5, "DEX_PAIR_M5", Rank.PROVIDER, at)
                writeSnapshot(ts, Field.TX_PER_MIN, m5 / 5.0, "DEX_PAIR_M5", Rank.PROVIDER, at)
            }
            h1 > 0 -> {
                writeSnapshot(ts, Field.BUY_PRESSURE_PCT, f.buysH1 * 100.0 / h1, "DEX_PAIR_H1", Rank.AGGREGATE, at)
                writeSnapshot(ts, Field.SELL_PRESSURE_PCT, f.sellsH1 * 100.0 / h1, "DEX_PAIR_H1", Rank.AGGREGATE, at)
                writeSnapshot(ts, Field.TX_PER_MIN, h1 / 60.0, "DEX_PAIR_H1", Rank.AGGREGATE, at)
            }
        }
        if (f.chg5m.isFinite()) writeSnapshot(ts, Field.CHANGE_5M_PCT, f.chg5m, "DEX_PAIR", Rank.PROVIDER, at)
        if (f.chg1h.isFinite()) writeSnapshot(ts, Field.CHANGE_1H_PCT, f.chg1h, "DEX_PAIR", Rank.PROVIDER, at)
        val activeCurve = mint.endsWith("pump", ignoreCase = true) &&
            !(try { com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(mint) } catch (_: Throwable) { false })
        if (!activeCurve && isReading(f.liquidityUsd)) writeLiquidity(ts, f.liquidityUsd, "DEX_PAIR", Rank.PROVIDER, at)
    }

    /** A market-sweep row ADDS its changes and liquidity at the row's own observation time (0 = unknown: not taken). */
    fun noteSweepRow(mint: String, chg5m: Double, chg1h: Double, liquidityUsd: Double, observedAtMs: Long) {
        if (observedAtMs <= 0L) return
        val ts = try { com.lifecyclebot.engine.BotService.status.tokens[mint.trim()] } catch (_: Throwable) { null } ?: return
        if (chg5m.isFinite()) writeSnapshot(ts, Field.CHANGE_5M_PCT, chg5m, "MARKET_SWEEP", Rank.AGGREGATE, observedAtMs)
        if (chg1h.isFinite()) writeSnapshot(ts, Field.CHANGE_1H_PCT, chg1h, "MARKET_SWEEP", Rank.AGGREGATE, observedAtMs)
        val activeCurve = mint.endsWith("pump", ignoreCase = true) &&
            !(try { com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(mint) } catch (_: Throwable) { false })
        if (!activeCurve && isReading(liquidityUsd)) writeLiquidity(ts, liquidityUsd, "MARKET_SWEEP", Rank.AGGREGATE, observedAtMs)
    }

    /** The tape read a reader fell back to ADDS itself (executed swaps: CHAIN, at the read's time). */
    fun noteTapeRead(ts: TokenState, bp: Double?, chg5m: Double?, nowMs: Long = System.currentTimeMillis()) {
        if (bp != null && validFor(Field.BUY_PRESSURE_PCT, bp)) {
            writeSnapshot(ts, Field.BUY_PRESSURE_PCT, bp, "TAPE_8100", Rank.CHAIN, nowMs, nowMs)
            writeSnapshot(ts, Field.SELL_PRESSURE_PCT, 100.0 - bp, "TAPE_8100", Rank.CHAIN, nowMs, nowMs)
        }
        if (chg5m != null && chg5m.isFinite()) writeSnapshot(ts, Field.CHANGE_5M_PCT, chg5m, "TAPE_8100", Rank.CHAIN, nowMs, nowMs)
    }

    private val archivedAt = ConcurrentHashMap<String, Long>()
    private const val ARCHIVE_EVERY_MS = 60_000L

    /** Pure: is an archive refresh of a mint last archived at [lastMs] due at [nowMs]? */
    fun archiveDue(lastMs: Long?, nowMs: Long): Boolean = lastMs == null || nowMs - lastMs !in 0L until ARCHIVE_EVERY_MS

    /**
     * V5.0.8554 — the archive round trip: the store refreshes the mint's TokenMetaCache row (in memory; SQLite on its own
     * 60 s flush) from its facts, at most once a minute per mint, after an observed (non-restore) write. Superseded: the
     * archive kept the first intake figures (a raw intake register even ran after the mirror register and overwrote it)
     * and nothing refreshed it after later polls, so a warm boot restored stale numbers. A warm boot reads these back as
     * RESTORE facts (time 0), never as observations.
     */
    fun archive(ts: TokenState, nowMs: Long = System.currentTimeMillis()) {
        if (ts.mint.isBlank() || !archiveDue(archivedAt[ts.mint], nowMs)) return
        // V5.0.8559 — only the bot's own live row refreshes, and only a row the archive already holds in memory: 8557 created a
        // row for every fact-written mint (transient / desk / frozen rows too), the working set reached 20,145 rows (cap 4,000;
        // dirty rows cannot be evicted) and the process died of OutOfMemoryError. The intake register still creates rows.
        if (!archiveEligible8559(ts)) return
        if (archivedAt.size > 2_000) archivedAt.entries.removeIf { nowMs - it.value > 10L * ARCHIVE_EVERY_MS }
        archivedAt[ts.mint] = nowMs
        val ctx = try { com.lifecyclebot.AATEApp.appContextOrNull() } catch (_: Throwable) { null } ?: return
        val cache = try { com.lifecyclebot.engine.TokenMetaCache.get(ctx) } catch (_: Throwable) { null } ?: return
        if (!cache.resident8559(ts.mint)) return
        fun v(f: Field) = ts.facts8551[f]?.takeIf { it.rank > Rank.RESTORE }?.value
        val px = ts.facts8551[Field.PRICE_USD]?.takeIf { it.rank > Rank.RESTORE }
        try {
            cache.register(
                mint = ts.mint,
                lastPrice = px?.value, lastPriceSource = px?.source?.takeIf { it.isNotBlank() },
                lastPricePoolAddr = ts.lastPricePoolAddr.takeIf { px != null && it.isNotBlank() },
                lastPriceDex = ts.lastPriceDex.takeIf { px != null && it.isNotBlank() },
                lastMcap = v(Field.MCAP_USD), lastLiquidityUsd = v(Field.LIQUIDITY_USD), lastFdv = v(Field.FDV_USD),
            )
            count(accepted, "ARCHIVE:REFRESH")
        } catch (_: Throwable) {}
    }

    /** The bot's own live row for its mint (not a transient, desk or frozen copy). */
    internal fun archiveEligible8559(ts: TokenState): Boolean =
        try { com.lifecyclebot.engine.BotService.status.tokens[ts.mint] === ts } catch (_: Throwable) { false }

    /** The frozen decision copy keeps the facts (source, rank, time) of the row it froze (DATA_MAP D10). */
    fun freezeInto(frozen: TokenState, live: TokenState) {
        try { synchronized(live.facts8551) { frozen.facts8551.putAll(live.facts8551) } } catch (_: Throwable) {}
    }

    /** The one top-holder share reader (DATA_MAP D3: five call sites read the two fields in different orders). */
    fun topHolderPct(ts: TokenState): Double? = snapshotFact(ts, Field.TOP_HOLDER_PCT)?.value

    // ── GRADUATED (V5.0.8553, DATA_MAP D8) ────────────────────────────────────────────────────────────────────────────

    /**
     * THE graduation write. Two readers used to disagree: PumpCurveKeys7269.isGraduated7392 (the curve account said
     * complete — 37 readers: curve depth, curve price witness, sell venue) and tokenMap.migratedOrGraduated (the WS / migration
     * event / venue strings — 31 readers: route choice, lane contracts, Cortex). Each was set by its own producers and never
     * told the other, so a coin could be "graduated" to the router and "on the curve" to the depth reader. A proven
     * graduation ([source]: curve account complete, PumpPortal migration event, DEX WS migration) now sets both, sticky.
     * Returns true when the row's flag flipped.
     */
    fun markGraduated(mint: String, source: String, ts: TokenState? = null): Boolean {
        val m = mint.trim()
        if (m.isBlank()) return false
        try { com.lifecyclebot.network.PumpCurveKeys7269.markGraduated7392(m) } catch (_: Throwable) {}
        val row = ts ?: try { com.lifecyclebot.engine.BotService.status.tokens[m] } catch (_: Throwable) { null }
        var flipped = false
        if (row != null) try {
            val tm = row.tokenMap
            if (!tm.migratedOrGraduated) { tm.migratedOrGraduated = true; flipped = true }
            tm.pumpFunExecutable = false
            tm.pumpFunBondingCurveStatus = "COMPLETED_MIGRATED"
            tm.updatedAtMs = System.currentTimeMillis()
        } catch (_: Throwable) {}
        count(accepted, "GRADUATED:" + source.take(40))
        return flipped
    }

    /** Pure: why [accepts] refused (for the brief). */
    fun refusal(prior: Fact?, offered: Fact): String = when {
        !isReading(offered.value) -> "NOT_A_READING"
        offered.rank <= Rank.RESTORE -> "RESTORE_UNDER_OBSERVATION"
        prior == null -> "LEGACY"
        offered.observedAtMs < prior.observedAtMs -> "OLDER_THAN_HELD"
        else -> "HELD_BY_STRONGER"
    }

    /** True when [ts]'s liquidity is only a restore (no live observation yet): readers may use it, a fresh read is due. */
    fun liquidityRestoredOnly(ts: TokenState): Boolean = ts.facts8551[Field.LIQUIDITY_USD]?.let { it.rank <= Rank.RESTORE } ?: false

    fun statusLine(): String {
        fun top(m: ConcurrentHashMap<String, AtomicLong>) = m.entries.sortedByDescending { it.value.get() }.take(8)
            .joinToString(",") { "${it.key.substringAfter(':')}=${it.value.get()}" }.ifBlank { "-" }
        fun of(f: String, m: ConcurrentHashMap<String, AtomicLong>) = ConcurrentHashMap(m.filterKeys { it.startsWith("$f:") })
        return "tokenFacts8551[liq accepted{${top(of("LIQUIDITY_USD", accepted))}} refused{${top(of("LIQUIDITY_USD", refused))}} " +
            "price accepted{${top(of("PRICE_USD", accepted))}} refused{${top(of("PRICE_USD", refused))}} " +
            "snapshot{" + Field.values().drop(2).joinToString(",") { f ->
                "${f.name.lowercase().substringBefore("_pct").substringBefore("_usd")}=${of(f.name, accepted).values.sumOf { it.get() }}/${of(f.name, refused).values.sumOf { it.get() }}"
            } + "} graduated{${top(of("GRADUATED", accepted))}} " +
            "rule=newest wins; same time -> higher rank; weaker source waits out the hold (liq: chain 90s, else 30s; price/snapshot: chain 60s); restores fill only empty slots]"
    }
}
