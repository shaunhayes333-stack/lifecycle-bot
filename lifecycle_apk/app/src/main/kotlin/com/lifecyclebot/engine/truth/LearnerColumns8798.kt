package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import java.util.concurrent.atomic.AtomicLongArray

/**
 * V5.0.8798 §THE_LEARNERS_SEE_EVERYTHING — owner: "we need to capture every piece of data that the bot already is served,
 * score, use, implement correctly". The playbook frame (LanePlaybook7907.F) already computed ~60 facts per coin from the
 * one store (TokenFacts) and its producers' side caches — launch tape, buyer flow, market sweep, socials, creator history,
 * narrative, insider, bundles, safety — but the learners (EdgeModel8537's dataset rows, TreeRanker8737) read 17-20 of them.
 *
 * This is the one column list beyond EdgeModel8537.Raw's core: every remaining fact of the frame, the evidence no column
 * carried (proven smart-money buyers, launch venue, the SOL trend, the launch rate, time of day, mint / freeze / rugcheck /
 * first-block safety, the launch's own trade count and buy share), and the AGE of each source (seconds since its last
 * observation), so a stale reading is visible to the learner instead of passing as fresh. NaN is unobserved, never 0.
 * Extracted once per decision ([extract], frozen into the decision's Raw), written to the dataset by name, read back by
 * name ([fromRow]); the brief reports each column's live coverage ([statusLine8798]). Adding a column: append a name and
 * its line in [extract] — the dataset, the tree and the coverage report follow.
 */
object LearnerColumns8798 {
    val NAMES: List<String> = listOf(
        // the launch tape and buyer flow (executed trades)
        "tapeCrowd", "tapeNetSol", "buyersPerMin", "buyShare", "largestBuyer", "top3Buyers", "devSold", "fromPeak", "fromFirst",
        "buyTx15s", "buyTxPrev15s", "repeatBuyers60s", "launchTrades", "launchBuyShare", "runupFromLow",
        // the market sweep's opportunity row
        "oppPct", "oppRs", "oppVolAccel", "oppTxAccel", "oppLiqDelta",
        // socials, creator, holders, narrative, insiders, bundles
        "boost", "socials", "cto", "devTokens", "devRugRate", "devRugger", "holders", "holderGrowth", "insider",
        "narrHeat", "narrExhaust", "bundle",
        // safety
        "lpLock", "mintRevoked", "freezeRevoked", "rugScore", "firstBlockSupply", "firstBlockBuyers",
        // chart and context facts
        "volatility", "regime", "ladderProven", "hlReclaim", "secondWave", "colourBuy", "liveKoth", "beta", "migrated",
        "expert", "crowdForming",
        // evidence no column carried
        "smartBuyers5m", "venue", "solChg1h", "solChg24h", "launchRate10m", "hourUtc", "dow",
        // source ages (seconds since the source's own observation)
        "agePrice", "ageBp", "ageChg", "ageLiq", "ageHolders", "secsSincePrint",
        // V5.0.8809 — what the owner reads in the header: Dex Paid (1 / 0) and minutes since it was paid
        "dexPaid", "dexPaidAgeMin",
        // V5.0.8840 — who is behind the launch, from the decoded pump.fun create and trade events (LaunchFacts8840)
        "creatorKnown", "devInitPct", "devHeldPct", "devSoldFrac", "devSold10mFrac", "bundleBuyers", "bundlePct", "sniperBuyers",
        "sniperPct", "top1Pct", "top5Pct", "uniqueBuyers", "devPriorLaunches", "devPriorRunnerRate", "devPriorDumpRate",
        // V5.0.9016 — the rest of what DexScreener gives for free (DexExtras9016): the hottest trending narrative the coin
        // sits in (its cap change over 1 h / 6 h, %, and how many coins are in it), a paid ad (1 = seen in the latest-ads
        // list since the app started and still running, 0 = not seen there, NaN while that list is unread) and its
        // impressions, minutes since its profile was last edited, and its data pool's type tag
        "metaHeat1h", "metaHeat6h", "metaTokens", "dexAd", "dexAdImpr", "profileUpdAgeMin", "poolLabel",
        // V5.0.9020 — the owner's named traders: the largest holder not on the list, their share of the ten largest, how many bought in 30 min
        "top1UnnamedPct", "namedTop10Pct", "namedBuyers30m",
    )
    val SIZE: Int = NAMES.size
    private val INDEX: Map<String, Int> = NAMES.withIndex().associate { it.value to it.index }
    private val REGIMES = listOf("BULL_RIPPING", "NORMAL", "CHOP", "DUMP", "DEAD", "BOOTSTRAP")

    private val seen = AtomicLongArray(SIZE)
    private val finite = AtomicLongArray(SIZE)
    @Volatile private var rows = 0L

    private fun b(v: Boolean): Double = if (v) 1.0 else 0.0
    private fun tri(v: Boolean?): Double = when (v) { true -> 1.0; false -> 0.0; null -> Double.NaN }
    private fun nonNeg(v: Double): Double = if (v.isFinite() && v >= 0.0) v else Double.NaN
    /** Pure: seconds from [stampMs] to [nowMs]; NaN when there is no stamp. */
    fun ageSec(stampMs: Long?, nowMs: Long): Double =
        if (stampMs == null || stampMs <= 0L || stampMs > nowMs + 5_000L) Double.NaN else maxOf(0.0, (nowMs - stampMs) / 1000.0)

    /**
     * Pure: the launch venue as a category (NaN when no field names one).
     * V5.0.8873 — [poolDex] is the DEX of the coin's chosen DexScreener data pool (DexPools8285): the edge dataset of
     * 17-20 Nov read the venue of 92 % of the decisions on coins off pump.fun as unknown (12,025 NaN, and 946 "other" where
     * a scanner stamped the chain, "solana", not the pool's DEX), and those coins carried the measured edge. A LetsBonk mint
     * with no venue read is LaunchLab-born.
     */
    fun venueOf(mint: String, venue: String, dexId: String, priceDex: String, migrated: Boolean, poolDex: String = ""): Double {
        val s = "$venue|$dexId|$priceDex|$poolDex".uppercase()
        return when {
            // A pump-suffix mint may already trade on an AMM while its migration flag
            // is still hydrating. The explicit observed trading venue outranks the
            // mint suffix, otherwise the learner is told AMM prints are curve prints.
            s.contains("PUMPSWAP") || s.contains("PUMP_AMM") || s.contains("PUMPFUN_AMM") -> 1.0
            s.contains("LAUNCHLAB") || s.contains("BONK") -> 2.0
            s.contains("RAYDIUM") -> 3.0
            s.contains("METEORA") || s.contains("DBC") || s.contains("DLMM") || s.contains("DAMM") -> 4.0
            s.contains("ORCA") -> 5.0
            mint.endsWith("pump") && migrated -> 1.0                                 // migrated, but venue unknown
            mint.endsWith("pump") && !migrated -> 0.0                                    // curve only when no observed AMM venue
            mint.endsWith("bonk") -> 2.0                                                 // V5.0.8873 — LetsBonk / LaunchLab mint, no venue read
            s.replace("UNKNOWN", "").replace("|", "").isBlank() -> Double.NaN
            else -> 6.0
        }
    }

    /** The extra columns of [ts] at [nowMs], from the frame [f] already computed for the same decision. */
    fun extract(ts: TokenState, f: LanePlaybook7907.F?, nowMs: Long): DoubleArray {
        // V5.0.9031 — every decision's accepted price and cap feed the coin's remembered top (TopMemory9031, the dead-runner rule).
        try { TopMemory9031.note9031(ts.mint, ts.lastPrice, try { TrustedMcap8019.mcap8019(ts) } catch (_: Throwable) { ts.lastMcap }, nowMs) } catch (_: Throwable) {}
        val x = DoubleArray(SIZE) { Double.NaN }
        fun put(name: String, v: Double) { INDEX[name]?.let { x[it] = v } }
        val mint = ts.mint
        if (f != null) {
            put("tapeCrowd", f.tapeCrowd); put("tapeNetSol", f.tapeNetSol); put("buyersPerMin", f.tapeBuyersPerMin)
            put("buyShare", f.tapeBuyShare); put("largestBuyer", f.tapeLargest); put("top3Buyers", f.tapeTop3)
            put("devSold", f.tapeDevSold); put("fromPeak", f.tapeFromPeak); put("fromFirst", f.tapeFromFirst)
            // V5.0.9047: the frame now distinguishes an absent legacy tape from
            // observed zero counts. Existing dataset zeros have no recoverable
            // provenance and remain unchanged; no schema/model-dimension reset.
            put("buyTx15s", f.buyTx15s); put("buyTxPrev15s", f.buyTxPrev15s); put("repeatBuyers60s", f.repeatBuyers60s)
            put("oppPct", f.oppPercentile); put("oppRs", f.oppRs); put("oppVolAccel", f.oppVolAccel); put("oppTxAccel", f.oppTxAccel)
            put("oppLiqDelta", f.oppLiqDelta); put("boost", f.boost); put("socials", f.socials); put("cto", b(f.cto))
            put("devTokens", f.devTokens); put("devRugRate", f.devRugRate); put("devRugger", b(f.devKnownRugger))
            put("holders", f.holders); put("holderGrowth", f.holderGrowth); put("insider", f.insider)
            put("narrHeat", f.narrativeHeat); put("narrExhaust", f.narrativeExhaustion); put("bundle", f.bundleLargest)
            put("lpLock", f.lpLock); put("volatility", f.volatility)
            put("regime", REGIMES.indexOf(f.regime).let { if (it < 0) Double.NaN else it.toDouble() })
            put("ladderProven", b(f.launchLadderProven)); put("hlReclaim", b(f.structureHlReclaim)); put("secondWave", b(f.secondWave7974))
            put("colourBuy", b(f.colourBuy7978)); put("liveKoth", b(f.live7978)); put("beta", b(f.beta7978)); put("migrated", b(f.migrated8308))
            put("expert", b(f.expertEntry7962)); put("crowdForming", b(f.crowdForming))
        }
        try {
            com.lifecyclebot.engine.market.LaunchTape7921.features(mint, nowMs)?.let { t ->
                put("launchTrades", t.trades8287.toDouble()); put("launchBuyShare", t.bpAll8287); put("runupFromLow", t.runupFromLowPct8798)
            }
        } catch (_: Throwable) {}
        val sf = ts.safety
        put("mintRevoked", tri(sf.mintAuthorityDisabled)); put("freezeRevoked", tri(sf.freezeAuthorityDisabled))
        put("rugScore", if (sf.rugcheckScore >= 0) sf.rugcheckScore.toDouble() else Double.NaN)
        put("firstBlockSupply", nonNeg(sf.firstBlockSupplyPct)); put("firstBlockBuyers", if (sf.firstBlockBuyers >= 0) sf.firstBlockBuyers.toDouble() else Double.NaN)
        put("smartBuyers5m", try { SmartMoney8756.provenBuyersKnown8798(mint, 5L * 60_000L, nowMs) } catch (_: Throwable) { Double.NaN })
        put("venue", try { venueOf(mint, ts.tokenMap.venue, ts.tokenMap.dexId, ts.lastPriceDex, ts.tokenMap.migratedOrGraduated,
            poolDex = com.lifecyclebot.network.DexPools8285.dataPool8285(mint)?.dexId.orEmpty()) } catch (_: Throwable) { Double.NaN })   // V5.0.8873
        val sol = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { Double.NaN }
        SolTrend8798.note(sol, nowMs)
        put("solChg1h", SolTrend8798.changePct(60, nowMs)); put("solChg24h", SolTrend8798.changePct(24 * 60, nowMs))
        put("launchRate10m", try { com.lifecyclebot.engine.market.LaunchTape7921.birthsSince8798(10L * 60_000L, nowMs).toDouble() } catch (_: Throwable) { Double.NaN })
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = nowMs }
        put("hourUtc", cal.get(java.util.Calendar.HOUR_OF_DAY).toDouble()); put("dow", cal.get(java.util.Calendar.DAY_OF_WEEK).toDouble())
        put("agePrice", ageSec(ts.lastPriceUpdate, nowMs)); put("ageBp", ageSec(ts.buyPressureObservedAtMs8084, nowMs))
        put("ageChg", ageSec(ts.priceChangeObservedAtMs8084, nowMs))
        put("ageLiq", ageSec(TokenFacts.fact(ts, TokenFacts.Field.LIQUIDITY_USD)?.observedAtMs, nowMs))
        put("ageHolders", ageSec(TokenFacts.holders8798(mint, nowMs)?.observedAtMs, nowMs))
        put("secsSincePrint", ageSec(try { TradeTape8100.lastPrint8360(mint)?.second } catch (_: Throwable) { null }, nowMs))
        // V5.0.8809 — Dex Paid: asked for every coin a decision is made on (one paced keyless call), read once answered. The
        // served pairs now also fill socials / boost when the frame's own peek had nothing yet.
        try {
            val src = com.lifecyclebot.network.DexScreenerSocialSource
            src.wantOrders8809(mint, nowMs)
            src.peek7895(mint)?.let { s ->
                if (s.dexPaid >= 0) put("dexPaid", s.dexPaid.toDouble())
                if (s.dexPaid == 1 && s.paidAtMs in 1L..nowMs) put("dexPaidAgeMin", (nowMs - s.paidAtMs) / 60_000.0)
                INDEX["boost"]?.let { if (x[it].isNaN()) x[it] = s.boostTotal }
                INDEX["socials"]?.let { if (x[it].isNaN()) x[it] = s.socialCount.toDouble() }
            }
        } catch (_: Throwable) {}
        // V5.0.8840 — the launch's chain facts as of now, and its creator's record as of its birth (NaN when not observed)
        try {
            LaunchFacts8840.snapshot8840(mint)?.let { (fa, prior) ->
                put("creatorKnown", b(fa.creatorKnown)); put("devInitPct", fa.devInitPct); put("devHeldPct", fa.devHeldPct)
                put("devSoldFrac", fa.devSoldFrac); put("devSold10mFrac", fa.devSold10mFrac)
                put("bundleBuyers", fa.bundleBuyers.toDouble()); put("bundlePct", fa.bundlePct)
                put("sniperBuyers", fa.sniperBuyers.toDouble()); put("sniperPct", fa.sniperPct)
                put("top1Pct", fa.top1Pct); put("top5Pct", fa.top5Pct); put("uniqueBuyers", fa.uniqueBuyers.toDouble())
                put("top1UnnamedPct", fa.top1UnnamedPct); put("namedTop10Pct", fa.namedTop10Pct)   // V5.0.9020
                if (fa.creatorKnown) {
                    put("devPriorLaunches", prior.first.toDouble())
                    put("devPriorRunnerRate", if (prior.first > 0) prior.second.toDouble() / prior.first else Double.NaN)
                    put("devPriorDumpRate", if (prior.first > 0) prior.third.toDouble() / prior.first else Double.NaN)
                }
            }
        } catch (_: Throwable) {}
        // V5.0.9016 — narratives, ads, profile edits and the pool's type: refreshed off this thread, read from memory here
        try {
            val dx = com.lifecyclebot.network.DexExtras9016
            dx.tick9016(nowMs)
            dx.metaOf9016(mint, nowMs)?.let { m -> put("metaHeat1h", m.capChgH1); put("metaHeat6h", m.capChgH6); put("metaTokens", m.tokens.toDouble()) }
            val ad = dx.adOf9016(mint, nowMs)
            if (ad != null) { put("dexAd", 1.0); put("dexAdImpr", ad.impressions) } else if (dx.adsFresh9016(nowMs)) put("dexAd", 0.0)
            put("profileUpdAgeMin", dx.profileUpdAgeMin9016(mint, nowMs)); put("poolLabel", dx.labelOf9016(mint))
            put("namedBuyers30m", NamedTraders9020.buyers9020(mint, nowMs).toDouble())   // V5.0.9020
        } catch (_: Throwable) {}
        note(x)
        return x
    }

    /** Pure: the columns of one dataset row (JSON), by name; a row written before 8798 reads NaN for every column. */
    fun fromRow(o: org.json.JSONObject): DoubleArray = DoubleArray(SIZE) { i -> EdgeModel8537.column8801(o, NAMES[i]) }   // V5.0.8801

    /**
     * Pure: the observed columns as dataset JSON fields (",\"name\":value" each). An unobserved column is left out (a
     * missing key reads NaN in [fromRow]), so the 60,000-row dataset grows only by what was actually seen.
     */
    fun toRow(x: DoubleArray): String {
        if (x.size != SIZE) return ""
        val sb = StringBuilder(SIZE * 12)
        for (i in 0 until SIZE) {
            val v = x[i]
            if (v.isFinite()) sb.append(",\"").append(NAMES[i]).append("\":").append("%.5g".format(java.util.Locale.US, v))
        }
        return sb.toString()
    }

    /** V5.0.8873 — one named column of an extracted row (NaN when absent). */
    fun valueOf8873(x: DoubleArray, name: String): Double = INDEX[name]?.takeIf { it < x.size }?.let { x[it] } ?: Double.NaN

    /** Pure: the columns as a JSON object of strings (the pending-decision snapshot; NaN travels as "NaN"). */
    fun toJson(x: DoubleArray): org.json.JSONObject = org.json.JSONObject().also { o -> if (x.size == SIZE) for (i in 0 until SIZE) o.put(NAMES[i], x[i].toString()) }

    fun fromJson(o: org.json.JSONObject?): DoubleArray {
        if (o == null) return DoubleArray(0)
        return DoubleArray(SIZE) { i -> o.optString(NAMES[i]).toDoubleOrNull() ?: Double.NaN }
    }

    private fun note(x: DoubleArray) {
        rows++
        for (i in 0 until SIZE) { seen.incrementAndGet(i); if (x[i].isFinite()) finite.incrementAndGet(i) }
    }

    /** The brief: how many decisions carried each column (the lowest first). */
    fun statusLine8798(): String {
        if (rows == 0L) return "learnerColumns8798[no decisions yet]"
        val pct = (0 until SIZE).map { i -> NAMES[i] to (if (seen.get(i) > 0) 100.0 * finite.get(i) / seen.get(i) else 0.0) }
        val low = pct.sortedBy { it.second }
        return "learnerColumns8798[decisions=$rows columns=$SIZE full=${pct.count { it.second >= 99.5 }} " +
            "covered{${low.joinToString(" ") { "${it.first}:${"%.0f".format(it.second)}%" }}} rule=every frame fact + evidence + source " +
            "age is a learner column; NaN = unobserved (never 0); the tree reads them all]"
    }
}

/** V5.0.8798 — the SOL price trend the learners read (one sample a minute, 25 hours, from the bot's own SOL mark). */
object SolTrend8798 {
    private const val SLOTS = 25 * 60
    private val px = DoubleArray(SLOTS) { Double.NaN }
    private val minute = LongArray(SLOTS) { -1L }

    @Synchronized fun note(priceUsd: Double, nowMs: Long) {
        if (!(priceUsd > 0.0) || !priceUsd.isFinite()) return
        val m = nowMs / 60_000L
        val i = (m % SLOTS).toInt()
        if (minute[i] == m) return
        minute[i] = m; px[i] = priceUsd
    }

    /** The % change of SOL over the last [minutes] (NaN until a sample that old exists, within 5 minutes of it). */
    @Synchronized fun changePct(minutes: Int, nowMs: Long): Double {
        val m = nowMs / 60_000L
        val now = latest(m, 0) ?: return Double.NaN
        val then = latest(m - minutes, 5) ?: return Double.NaN
        return (now / then - 1.0) * 100.0
    }

    private fun latest(m: Long, slack: Int): Double? {
        for (k in 0..slack) {
            val mm = m - k
            if (mm < 0) return null
            val i = (mm % SLOTS).toInt()
            if (minute[i] == mm && px[i] > 0.0) return px[i]
        }
        return null
    }
}
