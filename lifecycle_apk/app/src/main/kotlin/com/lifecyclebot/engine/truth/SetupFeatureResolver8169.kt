package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8169 §ANY_HEALTHY_SOURCE_FILLS_THE_INPUT — the latest DexScreener flow per mint, from every pair it serves.
 *
 * 5.0.8168: DexScreener answered 90% of 1,553 calls, each pair carrying txns m5/h1 buys+sells, priceChange m5/h1 and
 * liquidity, yet the setup inputs (buy pressure, price change, liquidity) were written only by BotService's own pair
 * poll of a watched token (applyPollFlow7758). A pair fetched by the scanner, the held-mark batch or any other caller
 * was thrown away after its price was read, and a candidate whose last poll was older than 15 minutes read
 * DATA_INCOMPLETE beside a fresh pair answer. Every best pair served now leaves its dated flow here; nothing is
 * derived, a field the pair did not carry stays absent (-1 / NaN).
 */
object PairFlow8169 {
    private const val CAP_8169 = 5_000
    private const val KEEP_MS_8169 = 30L * 60_000L

    /** One pair's flow, as served. Counts -1 and moves / liquidity NaN = the pair did not carry it. */
    class Flow8169(
        val buysM5: Int, val sellsM5: Int, val buysH1: Int, val sellsH1: Int,
        val chg5m: Double, val chg1h: Double, val liquidityUsd: Double, val atMs: Long,
    )

    private val flows = ConcurrentHashMap<String, Flow8169>()
    private val noted = AtomicLong(0L)

    /** Keep [f] as [mint]'s latest flow unless a newer answer is already held. */
    fun note8169(mint: String, f: Flow8169) {
        if (mint.length < 32 || f.atMs <= 0L) return
        val prev = flows[mint]
        if (prev != null && prev.atMs > f.atMs) return
        flows.compute(mint) { _, current -> if (current != null && current.atMs > f.atMs) current else f }
        noted.incrementAndGet()
        // Every actual pair producer wakes its consumers, not just DataOrchestrator's poll.
        CandidateAdmission8136.evidenceArrived8252(mint, CandidateAdmission8136.PAIR_FEATURES_8252, f.atMs)
        if (flows.size > CAP_8169) {
            flows.entries.removeIf { f.atMs - it.value.atMs > KEEP_MS_8169 }
            if (flows.size > CAP_8169) {
                flows.entries.sortedBy { it.value.atMs }.take(flows.size - CAP_8169 * 9 / 10).forEach { flows.remove(it.key, it.value) }
            }
        }
    }

    /** DexscreenerApi.getBestPairInternal: the best pair served for [mint]. */
    fun notePair8169(mint: String, p: com.lifecyclebot.network.PairInfo, atMs: Long = System.currentTimeMillis()) {
        // PairInfo carries h1 counts as 0 when absent: 0/0 is no reading (countShare8169 returns null for it).
        note8169(mint, Flow8169(p.buysM5, p.sellsM5, p.candle.buysH1, p.candle.sellsH1, p.priceChangeM5, p.priceChangeH1, p.liquidity, atMs))
    }

    /** Pure: one DexScreener pair object's flow (the batch /tokens/v1 answer); absent fields stay absent. */
    fun fromJson8169(p: org.json.JSONObject, atMs: Long): Flow8169 {
        val tx = p.optJSONObject("txns")
        val m5 = tx?.optJSONObject("m5")
        val h1 = tx?.optJSONObject("h1")
        val pc = p.optJSONObject("priceChange")
        val liq = p.optJSONObject("liquidity")
        return Flow8169(
            buysM5 = if (m5 != null && m5.has("buys")) m5.optInt("buys", -1) else -1,
            sellsM5 = if (m5 != null && m5.has("sells")) m5.optInt("sells", -1) else -1,
            buysH1 = if (h1 != null && h1.has("buys")) h1.optInt("buys", -1) else -1,
            sellsH1 = if (h1 != null && h1.has("sells")) h1.optInt("sells", -1) else -1,
            chg5m = if (pc != null && pc.has("m5")) pc.optDouble("m5", Double.NaN) else Double.NaN,
            chg1h = if (pc != null && pc.has("h1")) pc.optDouble("h1", Double.NaN) else Double.NaN,
            liquidityUsd = if (liq != null && liq.has("usd")) liq.optDouble("usd", Double.NaN) else Double.NaN,
            atMs = atMs,
        )
    }

    fun peek8169(mint: String): Flow8169? = flows[mint]

    /** "pairFlows=held/noted" for the features8169 line. */
    internal fun line8169(): String = "${flows.size}/${noted.get()}"

    internal fun clearForTest8169() { flows.clear(); noted.set(0L) }
}

/**
 * V5.0.8169 — one place that decides, per setup input, which source states it: the first source holding a real,
 * fresh reading wins, and an input no source states stays missing with every source's reason.
 *
 * 5.0.8168: setup features were complete for 73 of 15,849 evaluations and DATA_INCOMPLETE dominated every lane's
 * tags, while DexScreener (90%), the market sweep (Jupiter / DexScreener rows with 5m / 1h moves and 1h buy / sell
 * counts) and the executed-trade tape were healthy. LanePlaybook7907 read buy pressure and price change from the
 * token's own stamped poll and the trade tape only. Order, per input:
 *   bp     primary (stamped poll / tape-measured flow) -> tape8100 (executed trades) -> dexPair (any served pair,
 *          5m counts, else 1h counts) -> sweep (1h buy / sell counts)
 *   chg    primary -> tape8100 -> dexPair (5m and 1h moves) -> sweep (Jupiter 5m move and a supplied 1h move)
 *   shape  primary (provider candles) -> tape8100 (one source's executed prints). No other source: the 8094 lesson
 *          (mixed-source ticks misclassified fresh tokens) stands; the shape repair asks Codex for candles instead.
 *   liq    primary -> dexPair -> sweep
 * A fallback reading is a reading only inside the primary's own freshness window ([FRESH_MS_8169] = the playbook's
 * OBS_FRESH_MS_8084). Nothing is interpolated or defaulted.
 */
object SetupFeatureResolver8169 {
    const val PRIMARY = "primary"
    const val TAPE = "tape8100"
    const val PAIR = "dexPair"
    const val SWEEP = "sweep"
    const val CHAIN = "onChainPool"
    internal const val FRESH_MS_8169 = 15L * 60_000L
    /** A mint's shape repair asks Codex at most this often (the key's quota is shared with the chart backfill). */
    const val CODEX_REPAIR_GAP_MS_8169 = 10L * 60_000L

    class Cand8169<T>(val source: String, val value: T?, val why: String)

    /** [value] and its [source] when present; otherwise [why] names every source's reason ("src:WHY|..."). */
    internal class Pick8169<T>(val value: T?, val source: String?, val why: String?)

    /** Pure: the first candidate holding a value, in order. */
    internal fun <T> first8169(cands: List<Cand8169<T>>): Pick8169<T> {
        for (c in cands) if (c.value != null) return Pick8169(c.value, c.source, null)
        return Pick8169(null, null, if (cands.isEmpty()) "NO_SOURCE" else cands.joinToString("|") { "${it.source}:${it.why}" })
    }

    /** Pure: buy share (0..100) from counted transactions — the 5m window when it traded, else the hour; null when neither counted a trade. */
    fun countShare8169(buysM5: Int, sellsM5: Int, buysH1: Int, sellsH1: Int): Double? {
        val m5 = if (buysM5 >= 0 && sellsM5 >= 0) buysM5 + sellsM5 else 0
        if (m5 > 0) return buysM5.toDouble() / m5 * 100.0
        val h1 = if (buysH1 >= 0 && sellsH1 >= 0) buysH1 + sellsH1 else 0
        if (h1 > 0) return buysH1.toDouble() / h1 * 100.0
        return null
    }

    /** Pure: an answer dated inside the freshness window (a future date beyond a minute's clock skew is not fresh). */
    internal fun fresh8169(atMs: Long, nowMs: Long, maxAgeMs: Long = FRESH_MS_8169): Boolean =
        atMs > 0L && nowMs - atMs in -60_000L..maxAgeMs

    /** Launch data is perishable: the ordinary 15m provider cache can be old enough to
     * describe the wrong launch phase while a new mint is in its first minutes.
     * 8170 uses at most 60 seconds for a younger-than-15m launch.
     */
    fun maxEvidenceAge8170(ageMin: Double): Long =
        if (ageMin.isFinite() && ageMin in 0.0..15.0) 60_000L else FRESH_MS_8169


    fun pairBp8169(f: PairFlow8169.Flow8169?, nowMs: Long, maxAgeMs: Long = FRESH_MS_8169): Cand8169<Double> = when {
        f == null -> Cand8169(PAIR, null, "NOT_FETCHED")
        !fresh8169(f.atMs, nowMs, maxAgeMs) -> Cand8169(PAIR, null, "STALE")
        else -> countShare8169(f.buysM5, f.sellsM5, f.buysH1, f.sellsH1)?.let { Cand8169(PAIR, it, "") } ?: Cand8169(PAIR, null, "NO_TRADES")
    }

    fun pairChg8169(f: PairFlow8169.Flow8169?, nowMs: Long, maxAgeMs: Long = FRESH_MS_8169): Cand8169<Pair<Double, Double>> = when {
        f == null -> Cand8169(PAIR, null, "NOT_FETCHED")
        !fresh8169(f.atMs, nowMs, maxAgeMs) -> Cand8169(PAIR, null, "STALE")
        f.chg5m.isFinite() && f.chg1h.isFinite() -> Cand8169(PAIR, f.chg5m to f.chg1h, "")
        else -> Cand8169(PAIR, null, "NO_CHANGE")
    }

    fun pairLiq8169(f: PairFlow8169.Flow8169?, nowMs: Long, maxAgeMs: Long = FRESH_MS_8169): Cand8169<Double> = when {
        f == null -> Cand8169(PAIR, null, "NOT_FETCHED")
        !fresh8169(f.atMs, nowMs, maxAgeMs) -> Cand8169(PAIR, null, "STALE")
        f.liquidityUsd.isFinite() && f.liquidityUsd > 0.0 -> Cand8169(PAIR, f.liquidityUsd, "")
        else -> Cand8169(PAIR, null, "NO_LIQUIDITY")
    }

    fun sweepBp8169(r: com.lifecyclebot.engine.market.MarketSweep7297.Row?, nowMs: Long, maxAgeMs: Long = FRESH_MS_8169): Cand8169<Double> = when {
        r == null -> Cand8169(SWEEP, null, "NOT_LISTED")
        !fresh8169(r.observedAtMs8169, nowMs, maxAgeMs) -> Cand8169(SWEEP, null, "STALE")
        else -> countShare8169(-1, -1, r.buysH1, r.sellsH1)?.let { Cand8169(SWEEP, it, "") } ?: Cand8169(SWEEP, null, "NO_SPLIT")
    }

    /** One current, dated liquidity reading for admission DATA classification.
     * Entry authorization STILL requires canonical market-depth, route and quote
     * checks. A cached provider reading is not a safety/sellability bypass.
     */
    fun observedLiquidity8170(mint: String, ageMin: Double, nowMs: Long): Double? {
        val maxAge = maxEvidenceAge8170(ageMin)
        cachedChainLiquidity(mint, nowMs, maxAge).value?.let { return it }
        val p = try { PairFlow8169.peek8169(mint) } catch (_: Throwable) { null }
        val pair = pairLiq8169(p, nowMs, maxAge).value
        if (pair != null) return pair
        val row = try { com.lifecyclebot.engine.market.MarketSweep7297.rowFor8169(mint, nowMs) } catch (_: Throwable) { null }
        return sweepLiq8169(row, nowMs, maxAge).value
    }

    internal fun chainLiquidity(read: OnChainDepth8038.Read?, solUsd: Double, nowMs: Long, maxAgeMs: Long): Cand8169<Double> = when {
        read == null -> Cand8169(CHAIN, null, "NOT_OBSERVED")
        nowMs - read.atMs !in 0L..minOf(maxAgeMs, 90_000L) -> Cand8169(CHAIN, null, "STALE")
        read.pool.isBlank() || !read.quoteSol.isFinite() || read.quoteSol <= 0.0 ||
            !read.baseTokens.isFinite() || read.baseTokens <= 0.0 || !solUsd.isFinite() || solUsd <= 0.0 ->
            Cand8169(CHAIN, null, "INVALID")
        else -> OnChainDepth8038.depthUsd8038(read.quoteSol, solUsd).let {
            if (it.isFinite() && it > 0.0) Cand8169(CHAIN, it, "") else Cand8169(CHAIN, null, "INVALID")
        }
    }

    private fun cachedChainLiquidity(mint: String, nowMs: Long, maxAgeMs: Long): Cand8169<Double> = try {
        chainLiquidity(OnChainDepth8038.freshRead8198(mint, minOf(maxAgeMs, 90_000L), nowMs),
            com.lifecyclebot.engine.WalletManager.lastKnownSolPrice, nowMs, maxAgeMs)
    } catch (_: Throwable) { Cand8169(CHAIN, null, "NOT_OBSERVED") }

    /** The sweep's 1h move is 0.0 when no provider supplied it (Row contract), so only a non-zero 1h move is a reading. */
    fun sweepChg8169(r: com.lifecyclebot.engine.market.MarketSweep7297.Row?, nowMs: Long, maxAgeMs: Long = FRESH_MS_8169): Cand8169<Pair<Double, Double>> = when {
        r == null -> Cand8169(SWEEP, null, "NOT_LISTED")
        !fresh8169(r.observedAtMs8169, nowMs, maxAgeMs) -> Cand8169(SWEEP, null, "STALE")
        r.priceChange5mPct.isFinite() && r.priceChangeH1Pct.isFinite() && r.priceChangeH1Pct != 0.0 ->
            Cand8169(SWEEP, r.priceChange5mPct to r.priceChangeH1Pct, "")
        else -> Cand8169(SWEEP, null, "NO_CHANGE")
    }

    internal fun sweepLiq8169(r: com.lifecyclebot.engine.market.MarketSweep7297.Row?, nowMs: Long, maxAgeMs: Long = FRESH_MS_8169): Cand8169<Double> = when {
        r == null -> Cand8169(SWEEP, null, "NOT_LISTED")
        !fresh8169(r.observedAtMs8169, nowMs, maxAgeMs) -> Cand8169(SWEEP, null, "STALE")
        r.liquidityUsd.isFinite() && r.liquidityUsd > 0.0 -> Cand8169(SWEEP, r.liquidityUsd, "")
        else -> Cand8169(SWEEP, null, "NO_LIQUIDITY")
    }

    /** Pure: why a coin has no launch tape (LaunchTape7921 keeps witnessed pump births for 45 minutes only). */
    fun launchTapeWhy8169(mint: String, ageMin: Double): String = when {
        ageMin.isFinite() && ageMin > 45.0 -> "PAST_LAUNCH_WINDOW"
        !mint.endsWith("pump") -> "NOT_PUMP_LAUNCH"
        else -> "NO_BIRTH_WITNESSED"
    }

    internal class Fill8169(
        val bp: Pick8169<Double>,
        val chg: Pick8169<Pair<Double, Double>>,
        val shape: Pick8169<Boolean>,
        val liq: Pick8169<Double>,
    )

    /** Pure: one evaluation's resolution from already-read candidates (each list in priority order). */
    internal fun resolve8169(
        bp: List<Cand8169<Double>>, chg: List<Cand8169<Pair<Double, Double>>>,
        shape: List<Cand8169<Boolean>>, liq: List<Cand8169<Double>>,
    ): Fill8169 = Fill8169(first8169(bp), first8169(chg), first8169(shape), first8169(liq))

    /**
     * LanePlaybook7907.features: resolve bp / chg / shape / liq for [mint] now. The primary and tape values are the
     * caller's own reads (null = not stated, [*Obs] the primary's provenance name); the DexScreener pair and the sweep
     * row are read here (local caches, no network). Counts every evaluation for the features8169 report line.
     */
    internal fun fillFor8169(
        mint: String, nowMs: Long,
        bpObs: String, bpPrimary: Double?, tapeBp: Double?,
        chgObs: String, chgPrimary: Pair<Double, Double>?, tapeChg: Pair<Double, Double>?,
        shapeObs: String, shapePrimary: Boolean, tapeShape: Boolean,
        liqPrimary: Double?, tapeRead: Boolean,
        launchTape: Boolean, ageMin: Double, swaps: Boolean,
    ): Fill8169 {
        val tapeWhy = if (tapeRead) "TOO_FEW" else try { TradeTape8100.absentWhy8169(mint, nowMs) } catch (_: Throwable) { "NO_PRINTS" }
        val pair = try { PairFlow8169.peek8169(mint) } catch (_: Throwable) { null }
        val row = try { com.lifecyclebot.engine.market.MarketSweep7297.rowFor8169(mint, nowMs) } catch (_: Throwable) { null }
        val maxAge = maxEvidenceAge8170(ageMin)
        val fill = resolve8169(
            bp = listOf(Cand8169(PRIMARY, bpPrimary, bpObs), Cand8169(TAPE, tapeBp, tapeWhy), pairBp8169(pair, nowMs, maxAge), sweepBp8169(row, nowMs, maxAge)),
            chg = listOf(Cand8169(PRIMARY, chgPrimary, chgObs), Cand8169(TAPE, tapeChg, tapeWhy), pairChg8169(pair, nowMs, maxAge), sweepChg8169(row, nowMs, maxAge)),
            shape = listOf(Cand8169(PRIMARY, if (shapePrimary) true else null, shapeObs), Cand8169(TAPE, if (tapeShape) true else null, tapeWhy)),
            liq = listOf(cachedChainLiquidity(mint, nowMs, maxAge), Cand8169(PRIMARY, liqPrimary, "UNKNOWN"), pairLiq8169(pair, nowMs, maxAge), sweepLiq8169(row, nowMs, maxAge)),
        )
        try { count8169(fill, launchTape, if (launchTape) "" else launchTapeWhy8169(mint, ageMin), swaps, if (swaps) "" else tapeWhy) } catch (_: Throwable) {}
        return fill
    }

    // ── report: features8169[...] ──

    private val evals = AtomicLong(0L)
    private val complete = AtomicLong(0L)
    private val bySource = ConcurrentHashMap<String, AtomicLong>()     // "bp:dexPair"
    private val missing = ConcurrentHashMap<String, AtomicLong>()      // "bp|primary:STALE|tape8100:NO_PRINTS|..."
    private const val MISSING_KEYS_CAP_8169 = 600

    private fun inc(m: ConcurrentHashMap<String, AtomicLong>, k: String) {
        if (m.size >= MISSING_KEYS_CAP_8169 && !m.containsKey(k)) { m.computeIfAbsent("other") { AtomicLong(0L) }.incrementAndGet(); return }
        m.computeIfAbsent(k) { AtomicLong(0L) }.incrementAndGet()
    }

    private fun <T> countFamily(family: String, p: Pick8169<T>) {
        if (p.source != null) inc(bySource, "$family:${p.source}") else inc(missing, "$family|${p.why ?: "NO_SOURCE"}")
    }

    private fun count8169(f: Fill8169, launchTape: Boolean, launchWhy: String, swaps: Boolean, swapsWhy: String) {
        evals.incrementAndGet()
        if (f.bp.value != null && f.chg.value != null && f.shape.value != null && f.liq.value != null) complete.incrementAndGet()
        countFamily("bp", f.bp); countFamily("chg", f.chg); countFamily("shape", f.shape); countFamily("liq", f.liq)
        if (launchTape) inc(bySource, "launchTape:launchTape7921") else inc(missing, "launchTape|$launchWhy")
        if (swaps) inc(bySource, "swaps:flowOrTape") else inc(missing, "swaps|$TAPE:$swapsWhy")
    }

    private val repairs = ConcurrentHashMap<String, AtomicLong>()      // "codex:ok" / "codex:none" / "codex:rejected"
    private val codexAskedAt = ConcurrentHashMap<String, Long>()

    /** Pure throttle: may the shape repair ask Codex for [mint] now? */
    fun codexRepairDue8169(lastMs: Long?, nowMs: Long): Boolean = lastMs == null || nowMs - lastMs !in 0L until CODEX_REPAIR_GAP_MS_8169

    /** DataOrchestrator: claim the Codex repair slot for [mint] (false = asked too recently). */
    fun claimCodexRepair8169(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!codexRepairDue8169(codexAskedAt[mint], nowMs)) return false
        codexAskedAt[mint] = nowMs
        if (codexAskedAt.size > 3_000) codexAskedAt.entries.removeIf { nowMs - it.value > CODEX_REPAIR_GAP_MS_8169 }
        return true
    }

    /** DataOrchestrator: one shape-repair attempt by [source] ended as [outcome] (ok / none / rejected). */
    fun noteShapeRepair8169(source: String, outcome: String) { inc(repairs, "$source:$outcome") }

    private fun top(m: ConcurrentHashMap<String, AtomicLong>, prefix: String, n: Int): String =
        m.entries.filter { it.key.startsWith(prefix) }.sortedByDescending { it.value.get() }.take(n)
            .joinToString(",") { "${it.key.removePrefix(prefix)}=${it.value.get()}" }.ifBlank { "-" }

    /**
     * features8169[core=bp,chg,shape,liq complete=x/y bySource{...} missing{...} launchTape{...} swaps{...} shapeRepair{...}
     * pairFlows=n]: per family, which source filled the input and, for the evaluations no source could, the reasons.
     */
    fun statusLine8169(): String {
        val fams = listOf("bp", "chg", "shape", "liq")
        val src = fams.joinToString(" ") { f -> "$f[${top(bySource, "$f:", 4)}]" }
        val miss = fams.joinToString(" ") { f -> "$f[${top(missing, "$f|", 2)}]" }
        return "features8169[core=bp,chg,shape,liq complete=${complete.get()}/${evals.get()} bySource{$src} missing{$miss} " +
            "launchTape{${top(bySource, "launchTape:", 1)} missing=${top(missing, "launchTape|", 3)}} " +
            "swaps{${top(bySource, "swaps:", 1)} missing=${top(missing, "swaps|", 3)}} " +
            "shapeRepair{${top(repairs, "", 6)}} pairFlows=${try { PairFlow8169.line8169() } catch (_: Throwable) { "-" }}]"
    }

    internal fun clearForTest8169() {
        evals.set(0L); complete.set(0L); bySource.clear(); missing.clear(); repairs.clear(); codexAskedAt.clear()
    }
}
