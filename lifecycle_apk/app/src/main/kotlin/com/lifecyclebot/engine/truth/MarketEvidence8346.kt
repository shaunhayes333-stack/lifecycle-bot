package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8346 §AN_OBSERVATION_BECOMES_EVIDENCE_WHERE_IT_ARRIVES.
 *
 * Audit 8344 (9 min, LIVE, providers up: Helius 95 %, DexScreener 96 %): fully complete feature evaluations 419 / 6,348,
 * liquidity unknown 1,003, birth hydration launched / resolved 109 / 4, PROJECT_SNIPER 997 unknown-birth deferrals and 0
 * qualified, metric identity breaks 270. "The failure appears to lie in how those observations become fresh, verified,
 * lane-specific evidence." Causes read in code and repaired here:
 *  - BIRTH: the DexScreener batch lane (DexBatchLane8287 / DexscreenerApi.batchPairs8285) — since 8285 the ONE pair read
 *    for setup features and candidate marks — parsed every pair's `pairCreatedAt` and dropped it; only the per-mint best
 *    pair path (parsePair) and the sweep noted it to AdmissionBirth8144. A pump mint's bonding-curve pair IS its create
 *    event, so the cheapest certain birth of a coin whose create frame the throttle dropped was on the wire and unread,
 *    and the Helius signature walk (TokenBirthHydrator7441) was asked instead. The walk also never re-checked the cheap
 *    witnesses before paging: a birth answered by a create frame / chain launch / pair while the walk was queued still
 *    cost its RPC pages. Both fixed; counted in [birthLine8346].
 *  - LIQUIDITY: SetupFeatureResolver8169 resolved liq as chain -> primary -> dexPair -> sweep. An un-graduated pump coin
 *    has no DEX pair (dexPair:NO_LIQUIDITY) and no pool (onChainPool:NOT_OBSERVED), while its curve's real SOL
 *    (CurveReserves7951, from every PumpPortal frame and curve read) is the liquidity — and was not a candidate. It is now
 *    the candidate after the stamped primary and before the pair, so a dexPair NO_LIQUIDITY on a known curve is a reading,
 *    not "unknown". Every curve frame / curve read also lands in the tick liquidity at once (LiquidityTruth8037.
 *    onCurveObserved8346 -> write8343), so the 8343 resume hook fires when the frame arrives, not when a lane next asks.
 *  - SHAPE / TAPE: TradeTape8100 is keyed by the bare mint on both sides (no key-format split); shape is UNKNOWN because
 *    the coin under evaluation has NO prints at all (tape8100:NO_PRINTS 3,981): it was never on the keyed PumpPortal
 *    stream (24 fresh creates + the setup refresher's watches). A pump coin whose shape or swaps are missing at feature
 *    time now asks for its trade stream right there ([requestTape8346] -> PumpFunWS.watchForSetup8191, once a minute per
 *    mint; the 8191 / 8192 / 8193 caps and the daily frame budget still govern the subscription).
 *  - IDENTITY: breaks beyond the 8332 skew band are classified by unit ([unitBreak8346]) and counted per price source,
 *    so the next report names the feed whose price or cap is in the wrong unit instead of a worstBreak magnitude.
 * Nothing is estimated or defaulted: every counter here counts an observation that arrived.
 */
object MarketEvidence8346 {

    // ── birth ──

    private val fromPumpCreate = AtomicLong(0L)
    private val fromChainLaunch = AtomicLong(0L)
    private val fromPairCreated = AtomicLong(0L)
    private val fromSignatureWalk = AtomicLong(0L)
    private val unresolved = AtomicLong(0L)
    private val sniperReleased = AtomicLong(0L)
    private val walkSkippedKnown = AtomicLong(0L)
    private val pairRowsNoted = AtomicLong(0L)

    /** AdmissionBirth8144: a NEW create-frame / pump-record witness for a mint (first time the mint is dated by it). */
    fun noteBirthPumpCreate8346() { fromPumpCreate.incrementAndGet() }
    /** AdmissionBirth8144: a NEW chain mint-initialisation witness (ChainLaunchFeed8179). */
    fun noteBirthChainLaunch8346() { fromChainLaunch.incrementAndGet() }
    /** AdmissionBirth8144: a NEW bonding-curve pair creation witness (DexScreener pairCreatedAt, BIRTH class). */
    fun noteBirthPairCreated8346() { fromPairCreated.incrementAndGet() }
    /** AdmissionBirth8144: a NEW oldest-signature witness (the Helius walk read the history to its end). */
    fun noteBirthSignatureWalk8346() { fromSignatureWalk.incrementAndGet() }
    /** CanonicalTokenBirthTime7440.resolve: no witness at all (the walk is asked). */
    fun noteBirthUnresolved8346() { unresolved.incrementAndGet() }
    /** AdmissionBirth8144.provenAgeMs8144: the sniper's fallback read released a mint the canonical resolver had not. */
    fun noteSniperReleased8346() { sniperReleased.incrementAndGet() }
    /** TokenBirthHydrator7441: a queued walk found the birth already witnessed and spent no RPC page. */
    fun noteWalkSkippedKnown8346() { walkSkippedKnown.incrementAndGet() }
    /** DexscreenerApi.batchPairs8285: one pair row's creation time handed to AdmissionBirth8144. */
    fun notePairRowDated8346() { pairRowsNoted.incrementAndGet() }

    fun birthLine8346(): String =
        "birth8346[fromPumpCreate=${fromPumpCreate.get()} fromChainLaunch=${fromChainLaunch.get()} fromPairCreated=${fromPairCreated.get()} " +
            "fromSignatureWalk=${fromSignatureWalk.get()} unresolved=${unresolved.get()} sniperReleased=${sniperReleased.get()} " +
            "walkSkippedKnown=${walkSkippedKnown.get()} batchPairRowsDated=${pairRowsNoted.get()}]"

    // ── liquidity ──

    /** The resolver's source name for the bonding curve's own real SOL. */
    const val CURVE_8346 = "curve7951"

    private val fromCurveFrame = AtomicLong(0L)
    private val fromCurveRead = AtomicLong(0L)
    private val liqPicks = ConcurrentHashMap<String, AtomicLong>()
    private val liqStillUnknown = AtomicLong(0L)

    /**
     * Pure: the curve as a liquidity candidate for SetupFeatureResolver8169. [realSol] is CurveReserves7951's active-curve
     * real SOL (null = no fresh active curve reading: not a pump coin, graduated, or never observed). A curve nobody has
     * bought into (0 real SOL) is CURVE_EMPTY, never a reading of 0 — the lanes' own floors judge that from the tick.
     */
    fun curveLiq8346(realSol: Double?, solUsd: Double): SetupFeatureResolver8169.Cand8169<Double> {
        val real: Double = realSol ?: return SetupFeatureResolver8169.Cand8169(CURVE_8346, null, "NO_ACTIVE_CURVE")
        return when {
            !real.isFinite() || real < 0.0 -> SetupFeatureResolver8169.Cand8169(CURVE_8346, null, "INVALID")
            real == 0.0 -> SetupFeatureResolver8169.Cand8169(CURVE_8346, null, "CURVE_EMPTY")
            !solUsd.isFinite() || solUsd <= 0.0 -> SetupFeatureResolver8169.Cand8169(CURVE_8346, null, "NO_SOL_PRICE")
            else -> SetupFeatureResolver8169.Cand8169(CURVE_8346, real * solUsd, "")
        }
    }

    /** LiquidityTruth8037.onCurveObserved8346: a curve observation was written into the tick liquidity. */
    fun noteCurveWrite8346(fromAccount: Boolean) { (if (fromAccount) fromCurveRead else fromCurveFrame).incrementAndGet() }

    /** SetupFeatureResolver8169.fillFor8169: which source filled liq ([source] null = none). */
    fun noteLiqPick8346(source: String?) {
        if (source == null) { liqStillUnknown.incrementAndGet(); return }
        liqPicks.computeIfAbsent(source) { AtomicLong(0L) }.incrementAndGet()
    }

    private fun pick(source: String): Long = liqPicks[source]?.get() ?: 0L

    fun liqLine8346(): String =
        "liq8346[fromCurveFrame=${fromCurveFrame.get()} fromCurveRead=${fromCurveRead.get()} pickedCurve=${pick(CURVE_8346)} " +
            "fromDexPair=${pick(SetupFeatureResolver8169.PAIR)} fromPool=${pick(SetupFeatureResolver8169.CHAIN)} " +
            "fromPrimary=${pick(SetupFeatureResolver8169.PRIMARY)} fromSweep=${pick(SetupFeatureResolver8169.SWEEP)} stillUnknown=${liqStillUnknown.get()}]"

    // ── shape / tape ──

    /** A mint asks for its PumpPortal trade stream at most this often from the feature fill. */
    const val TAPE_ASK_GAP_MS_8346 = 60_000L
    private const val TAPE_ASK_CAP_8346 = 4_000

    private val shapeFromTape = AtomicLong(0L)
    private val shapeFromCandles = AtomicLong(0L)
    private val shapeUnknownWhy = ConcurrentHashMap<String, AtomicLong>()
    private val tapeAskedAt = ConcurrentHashMap<String, Long>()
    private val tapeAsked = AtomicLong(0L)
    private val tapeSubscribed = AtomicLong(0L)

    /**
     * Pure: should the feature fill ask PumpPortal for [mint]'s trade stream now? Only a pump coin still on its curve
     * ([pump] && ![graduated]) whose tape has nothing fresh ([tapeWhy] NO_PRINTS / STALE), at most once per
     * [TAPE_ASK_GAP_MS_8346] per mint ([lastAskMs] = the previous ask, null = never). A coin with prints that are merely
     * TOO_FEW is already streamed; asking again would only churn the subscription set.
     */
    fun tapeWanted8346(pump: Boolean, graduated: Boolean, tapeWhy: String?, lastAskMs: Long?, nowMs: Long): Boolean {
        if (!pump || graduated) return false
        val w = tapeWhy.orEmpty()
        if (w != "NO_PRINTS" && w != "STALE") return false
        return lastAskMs == null || nowMs - lastAskMs >= TAPE_ASK_GAP_MS_8346 || nowMs < lastAskMs
    }

    /** Pure: the tape's shape source name maps to the counter it belongs to ("tape" / "candles" / null). */
    fun shapeBucket8346(source: String?): String? = when (source) {
        SetupFeatureResolver8169.TAPE -> "tape"
        SetupFeatureResolver8169.PRIMARY -> "candles"
        null -> null
        else -> "candles"
    }

    /** SetupFeatureResolver8169.fillFor8169: how shape resolved ([why] = every source's reason when it did not). */
    fun noteShape8346(source: String?, why: String?) {
        when (shapeBucket8346(source)) {
            "tape" -> shapeFromTape.incrementAndGet()
            "candles" -> shapeFromCandles.incrementAndGet()
            else -> {
                val k = (why ?: "NO_SOURCE").take(96)
                if (shapeUnknownWhy.size >= 64 && !shapeUnknownWhy.containsKey(k)) shapeUnknownWhy.computeIfAbsent("other") { AtomicLong(0L) }.incrementAndGet()
                else shapeUnknownWhy.computeIfAbsent(k) { AtomicLong(0L) }.incrementAndGet()
            }
        }
    }

    /**
     * SetupFeatureResolver8169.fillFor8169: a pump coin under evaluation with no tape joins the keyed PumpPortal trade
     * stream (PumpFunWS.watchForSetup8191: 8191 setup cap, 8193 metered budget, 8307 PumpSwap routing all apply). Paced
     * per mint; the subscription itself is the socket's decision. Returns true when a subscribe frame was sent now.
     */
    fun requestTape8346(mint: String, tapeWhy: String?, nowMs: Long = System.currentTimeMillis()): Boolean {
        val m = mint.trim()
        if (m.length !in 32..44) return false
        val pump = try { com.lifecyclebot.network.PumpFunDirectApi.isPumpFunMint(m) } catch (_: Throwable) { m.endsWith("pump") }
        val graduated = try { com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(m) } catch (_: Throwable) { false }
        if (!tapeWanted8346(pump, graduated, tapeWhy, tapeAskedAt[m], nowMs)) return false
        if (tapeAskedAt.size >= TAPE_ASK_CAP_8346) tapeAskedAt.entries.removeIf { nowMs - it.value > TAPE_ASK_GAP_MS_8346 }
        tapeAskedAt[m] = nowMs
        tapeAsked.incrementAndGet()
        val sent = try { com.lifecyclebot.network.PumpFunWS.watchForSetup8191(m, nowMs) } catch (_: Throwable) { false }
        if (sent) {
            tapeSubscribed.incrementAndGet()
            try { PipelineHealthCollector.labelInc("TAPE_SUBSCRIBED_AT_FEATURE_FILL_8346") } catch (_: Throwable) {}
        }
        return sent
    }

    fun shapeLine8346(): String {
        val why = shapeUnknownWhy.entries.sortedByDescending { it.value.get() }.take(4).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        return "shape8346[fromTape=${shapeFromTape.get()} fromCandles=${shapeFromCandles.get()} unknownWhy{$why} tapeAsked=${tapeAsked.get()} tapeSubscribed=${tapeSubscribed.get()}]"
    }

    // ── identity ──

    enum class Unit8346 { HELD, SKEW, SOL_VS_USD, DECIMALS_1E3, DECIMALS_1E6, DECIMALS_1E9, OTHER }

    private const val UNIT_TOLERANCE_8346 = 0.15

    private fun near(magnitude: Double, target: Double): Boolean =
        target > 0.0 && kotlin.math.abs(magnitude / target - 1.0) <= UNIT_TOLERANCE_8346

    /**
     * Pure: the unit a BROKEN price / (cap ÷ supply) identity points at. [ratio] = reported price ÷ implied price; its
     * magnitude (max of ratio and 1/ratio) within 15 % of the SOL price names a price or cap in SOL where USD was
     * expected; within 15 % of 1e3 / 1e6 / 1e9 names a supply or amount off by a decimals factor; anything else is OTHER
     * (a transient cap of the 7087 kind, or a feed that priced a different pool). Unknown SOL price: SOL_VS_USD is never
     * claimed.
     */
    fun unitBreak8346(ratio: Double, solUsd: Double): Unit8346 {
        if (!ratio.isFinite() || ratio <= 0.0) return Unit8346.OTHER
        val magnitude = if (ratio >= 1.0) ratio else 1.0 / ratio
        return when {
            solUsd.isFinite() && solUsd > 0.0 && near(magnitude, solUsd) -> Unit8346.SOL_VS_USD
            near(magnitude, 1e3) -> Unit8346.DECIMALS_1E3
            near(magnitude, 1e6) -> Unit8346.DECIMALS_1E6
            near(magnitude, 1e9) -> Unit8346.DECIMALS_1E9
            else -> Unit8346.OTHER
        }
    }

    private val identityHeld = AtomicLong(0L)
    private val identitySkew = AtomicLong(0L)
    private val unitBreaks = ConcurrentHashMap<String, AtomicLong>()   // "SOL_VS_USD@PUMP_PORTAL_WS"

    /** TokenMetricsAuthority7069: the identity held (within epsilon) on one observation. */
    fun noteIdentityHeld8346() { identityHeld.incrementAndGet() }
    /** TokenMetricsAuthority7069: price and cap written at different times, disagreeing inside a market move (8332). */
    fun noteIdentitySkew8346() { identitySkew.incrementAndGet() }

    /** TokenMetricsAuthority7069: a BROKEN identity on [source] at [ratio]; classified by unit against [solUsd]. */
    fun noteIdentityBreak8346(source: String, ratio: Double, solUsd: Double): Unit8346 {
        val u = unitBreak8346(ratio, solUsd)
        val k = "${u.name}@${source.trim().ifBlank { "UNKNOWN" }.take(32)}"
        if (unitBreaks.size >= 64 && !unitBreaks.containsKey(k)) unitBreaks.computeIfAbsent("OTHER@other") { AtomicLong(0L) }.incrementAndGet()
        else unitBreaks.computeIfAbsent(k) { AtomicLong(0L) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("METRICS_IDENTITY_UNIT_8346_${u.name}") } catch (_: Throwable) {}
        return u
    }

    fun identityLine8346(): String {
        val breaks = unitBreaks.entries.sortedByDescending { it.value.get() }.take(6).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        return "identity8346[held=${identityHeld.get()} skew=${identitySkew.get()} unitBreak{$breaks}]"
    }

    internal fun resetForTests8346() {
        for (c in listOf(fromPumpCreate, fromChainLaunch, fromPairCreated, fromSignatureWalk, unresolved, sniperReleased, walkSkippedKnown, pairRowsNoted,
            fromCurveFrame, fromCurveRead, liqStillUnknown, shapeFromTape, shapeFromCandles, tapeAsked, tapeSubscribed, identityHeld, identitySkew)) c.set(0L)
        liqPicks.clear(); shapeUnknownWhy.clear(); tapeAskedAt.clear(); unitBreaks.clear()
    }
}
