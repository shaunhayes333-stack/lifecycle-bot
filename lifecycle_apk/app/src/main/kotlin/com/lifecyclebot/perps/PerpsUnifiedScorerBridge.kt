package com.lifecyclebot.perps

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.v3.core.TradingConfigV3
import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.core.V3BotMode
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import com.lifecyclebot.v3.scanner.SourceType
import com.lifecyclebot.v3.scoring.EducationSubLayerAI
import com.lifecyclebot.v3.scoring.ReflexAI
import com.lifecyclebot.v3.scoring.ScoreCard
import com.lifecyclebot.v3.scoring.UnifiedScorer
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.130 — PERPS UNIFIED SCORER BRIDGE
 * ─────────────────────────────────────────────────────────────────────────
 * Gives every non-meme trader (CryptoAlt, TokenizedStock, Forex, Metals,
 * Commodities) access to the SAME full AI stack that the memetrader enjoys:
 *
 *    • UnifiedScorer (41 layers + AITrustNetwork meta-aggregation)
 *    • 14 V5.9.123 layers (CorrelationHedge, LiquidityExitPath, MEV,
 *      StablecoinFlow, OperatorFingerprint, SessionEdge, ExecCost,
 *      DrawdownCircuit, CapitalEfficiency, TokenDNA, PeerAlpha,
 *      NewsShock, FundingRate, OrderbookImbalance)
 *    • ReflexAI (sub-second reflex exits at price-tick path)
 *    • Real per-layer accuracy learning loop (V5.9.126)
 *    • SentienceOrchestrator visibility — the LLM now sees these layers
 *      mutating across ALL markets, not just memes.
 *
 * USAGE:
 *   1. After a trader computes its technical signal, call
 *      `scoreForEntry(symbol, price, score, conf, liq, mcap, direction, …)`
 *      The bridge synthesises a CandidateSnapshot, invokes UnifiedScorer,
 *      and returns a blended verdict + v3 score.
 *
 *   2. On trade close, each trader calls `recordClose(symbol, pnlPct, isWin)`
 *      which drives the real accuracy loop for every layer that had an
 *      entry prediction stored for that symbol.
 *
 *   3. On each price tick (optional but recommended), trader calls
 *      `reflexGate(symbol, entryPrice, currentPrice, liqUsd, entryLiqUsd)`
 *      and gets back a ReflexVerdict it can honour for hard/partial exits.
 *
 * ─────────────────────────────────────────────────────────────────────────
 */
object PerpsUnifiedScorerBridge {

    private const val TAG = "PerpsV3Bridge"

    /** Blended verdict returned to the calling trader. */
    data class V3Verdict(
        val v3Score: Int,
        val trustMultiplier: Double,
        val blendedScore: Int,
        val topReasons: List<String>,
        val shouldEnter: Boolean,
        val reflexVeto: Boolean = false,  // if true, skip even if blended is high
    )

    /** Keep a lightweight snapshot of per-symbol entry context for close-hook. */
    private data class EntrySnapshot(
        val mintKey: String,
        val symbol: String,
        val direction: String,
        val entryPrice: Double,
        val entryLiqUsd: Double,
        val v3Score: Int,
        val timestamp: Long,
        // V5.9.170 — real reason chain so the education layer learns
        // WHY this trader opened, not just that it opened.
        val entryReason: String = "",
        val traderSource: String = "perps",
    )
    private val openEntries = ConcurrentHashMap<String, EntrySnapshot>()

    private val defaultCtx: TradingContext by lazy {
        TradingContext(
            config = TradingConfigV3(),
            mode = V3BotMode.PAPER,
            marketRegime = "NEUTRAL",
            apiHealthy = true,
            priceFeedsHealthy = true,
        )
    }

    /**
     * One shared UnifiedScorer instance. Default-constructs every AI module
     * (EntryAI, MomentumAI, LiquidityAI, …). Each call to score() internally
     * dispatches to the 41+ layers + AITrustNetwork meta-aggregation +
     * V5.9.124 veto-cap + V5.9.126 real-accuracy capture.
     */
    private val scorer: UnifiedScorer = UnifiedScorer()

    /**
     * Synthesise a CandidateSnapshot from a non-meme trader's signal and run
     * it through the full UnifiedScorer stack. Blends the V3 score with the
     * trader's technical score (60% technical / 40% V3 as the default mix).
     *
     * @param assetClass e.g. "ALT" / "STOCK" / "FOREX" / "METAL" / "COMMODITY"
     *                   (stored in extras for downstream session/regime layers)
     */
    fun scoreForEntry(
        symbol: String,
        assetClass: String,
        price: Double,
        technicalScore: Int,
        technicalConfidence: Int,
        liqUsd: Double,
        mcapUsd: Double,
        priceChangePct: Double,
        direction: String,   // "LONG" | "SHORT"
    ): V3Verdict {
        // V5.9.366 — non-meme asset short-circuit.
        //
        // BUG (user, Feb 2026): "markets still doesn't make leverage trades"
        //   despite Stocks tab clearly showing 5 LONG signals (AAPL/GOOGL/META/
        //   MSFT/NFLX) at score=50.
        //
        // ROOT CAUSE: V3 is the 41-layer meme-coin scorer. It measures
        //   bundled%, holders, freshness, buy-pressure swing, etc. — signals
        //   that are null/neutral/inappropriate for real-world assets like
        //   stocks, forex, metals, commodities. With those inputs zeroed out,
        //   V3 returns a low or negative v3Score, dragging the blended score
        //   (60% technical + 40% V3) below blendedFloor and silently vetoing
        //   every Markets-layer trade.
        //
        // FIX: For STOCK / FOREX / METAL / COMMODITY / PERP_INDEX asset
        //   classes, gate on technicalScore + technicalConfidence directly
        //   (the gates the trader's own analyzer already validated) and
        //   skip the meme-tuned V3 stack. ALT (CryptoAlts) and meme paths
        //   keep the original blended gate.
        val isMemeOrAlt = assetClass.equals("ALT", ignoreCase = true) ||
                          assetClass.equals("MEME", ignoreCase = true) ||
                          assetClass.equals("CRYPTO", ignoreCase = true)
        if (!isMemeOrAlt) {
            // Pure technical gate for real-world assets. Floor is the same
            // 45/35 baseline V3 used as its fallback path — known-good defaults.
            val techOk = technicalScore >= 45 && technicalConfidence >= 35
            if (techOk) {
                ErrorLogger.info(TAG, "[$assetClass] $symbol: TECH-GATE PASS score=$technicalScore conf=$technicalConfidence (V3 bypass — non-meme asset)")
            } else {
                ErrorLogger.debug(TAG, "[$assetClass] $symbol: TECH-GATE veto score=$technicalScore<45 or conf=$technicalConfidence<35")
            }
            return V3Verdict(
                v3Score = 0,
                trustMultiplier = 1.0,
                blendedScore = technicalScore,
                topReasons = listOf("non_meme_tech_gate"),
                shouldEnter = techOk,
            )
        }

        val mintKey = makeMintKey(assetClass, symbol)

        val snap = CandidateSnapshot(
            mint = mintKey,
            symbol = symbol,
            source = SourceType.DEX_TRENDING,  // neutral / non-meme source
            discoveredAtMs = System.currentTimeMillis(),
            ageMinutes = 60.0,                 // traded assets are not "fresh launches"
            liquidityUsd = liqUsd.coerceAtLeast(100_000.0),  // perps are deep
            marketCapUsd = mcapUsd.coerceAtLeast(1_000_000.0),
            buyPressurePct = if (direction == "LONG") 55.0 + (priceChangePct.coerceIn(-10.0, 10.0) / 2.0) else 45.0 - (priceChangePct.coerceIn(-10.0, 10.0) / 2.0),
            volume1mUsd = liqUsd * 0.02,       // rough proxy
            volume5mUsd = liqUsd * 0.08,
            holders = null,
            topHolderPct = null,
            bundledPct = null,
            hasIdentitySignals = false,
            isSellable = true,
            rawRiskScore = null,
            extra = mapOf(
                "assetClass" to assetClass,
                "technicalScore" to technicalScore,
                "technicalConf" to technicalConfidence,
                "direction" to direction,
                "priceChangePct" to priceChangePct,
            ),
        )

        val card: ScoreCard = try {
            scorer.score(snap, defaultCtx)
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "scorer failed for $symbol: ${e.message}")
            return V3Verdict(
                v3Score = 0,
                trustMultiplier = 1.0,
                blendedScore = technicalScore,
                topReasons = listOf("v3_failed_fallback_to_technical"),
                shouldEnter = technicalScore >= 45 && technicalConfidence >= 35,
            )
        }

        val v3Score = card.components.sumOf { it.value }
        val trustMult = card.components
            .firstOrNull { it.name.equals("AITrustNetworkAI", ignoreCase = true) }
            ?.let { 1.0 + (it.value / 50.0).coerceIn(-0.20, 0.30) } ?: 1.0

        // Blend: 60% technical / 40% V3-normalised. V3 total is usually in
        // ±40 range so we scale it onto a 0-100 axis.
        val v3Normalised = ((v3Score + 40) * 1.25).coerceIn(0.0, 100.0).toInt()
        val blended = ((technicalScore * 0.60) + (v3Normalised * 0.40) * trustMult)
            .coerceIn(0.0, 120.0)
            .toInt()

        // V5.9.153 — bootstrap-aware entry gate.
        //
        // User 04-23: 'it should be 250+ trades/hr' but log shows ~3000 CryptoAlt
        // SIGNAL lines/hr (IO/HYPE/MOVE/TNSR/KMNO/PIXEL/RON/MAGIC/ENJ/CHZ/WBTC/PAXG
        // /MSOL all at score=50-55 conf=54) and only ~12 actually becoming
        // trades. The hard `technicalScore >= 60` floor vetoed every one of
        // them — exactly the pattern V5.9.97 created for V3 that V5.9.150
        // reverted there but was never touched here.
        //
        // Scale the floor with learning progress so bootstrap gets the volume
        // it needs to train the V3 layers, and the floor tightens as the bot
        // proves itself. At 0% learning: score≥45. At 100%: score≥60.
        val lp = try {
            com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress()
        } catch (_: Exception) { 1.0 }
        val techFloor    = (45 + (lp * 15)).toInt().coerceIn(45, 60)
        val blendedFloor = (40 + (lp * 15)).toInt().coerceIn(40, 55)
        val shouldEnter  = technicalScore >= techFloor &&
                           blended        >= blendedFloor &&
                           v3Score        >  -10

        val topReasons = card.components
            .sortedByDescending { kotlin.math.abs(it.value) }
            .take(4)
            .map { "${it.name}=${it.value}" }

        return V3Verdict(
            v3Score = v3Score,
            trustMultiplier = trustMult,
            blendedScore = blended,
            topReasons = topReasons,
            shouldEnter = shouldEnter,
        )
    }

    /**
     * Remember this entry so the close-hook can close the learning loop.
     * Called by trader AFTER it decides to execute the buy/short.
     */
    fun registerEntry(
        symbol: String,
        assetClass: String,
        direction: String,
        entryPrice: Double,
        entryLiqUsd: Double,
        v3Score: Int,
        entryReason: String = "",
        traderSource: String = "",
    ) {
        val key = makeMintKey(assetClass, symbol)
        val source = traderSource.ifBlank { assetClass.uppercase() }
        openEntries[key] = EntrySnapshot(
            mintKey = key,
            symbol = symbol,
            direction = direction,
            entryPrice = entryPrice,
            entryLiqUsd = entryLiqUsd,
            v3Score = v3Score,
            timestamp = System.currentTimeMillis(),
            entryReason = entryReason,
            traderSource = source,
        )
        // V5.9.170 — feed entry reason straight into universal firehose.
        if (entryReason.isNotBlank()) {
            try {
                EducationSubLayerAI.recordEntryReason(
                    mint = key,
                    traderSource = source,
                    reason = entryReason,
                    scoreHint = v3Score.toDouble(),
                )
            } catch (_: Exception) {}
        }
    }

    /**
     * V5.9.170 — trader can append a hold reason mid-position. Safe to call
     * every tick — dedupes adjacent duplicates internally.
     */
    fun recordHold(symbol: String, assetClass: String, holdReason: String) {
        if (holdReason.isBlank()) return
        val key = makeMintKey(assetClass, symbol)
        try { EducationSubLayerAI.recordHoldReason(key, holdReason) } catch (_: Exception) {}
    }

    /**
     * Close the learning loop. Drives real per-layer accuracy correlation.
     */
    fun recordClose(
        symbol: String,
        assetClass: String,
        pnlPct: Double,
        exitReason: String = "perps_close",
        lossReason: String = "",
    ) {
        val key = makeMintKey(assetClass, symbol)
        val snap = openEntries.remove(key) ?: return
        try {
            val holdMin = ((System.currentTimeMillis() - snap.timestamp) / 60_000.0)
                .coerceIn(0.0, 10080.0)  // guard against restored/stale entries
            val outcome = EducationSubLayerAI.TradeOutcomeData(
                mint = snap.mintKey,
                symbol = snap.symbol,
                tokenName = snap.symbol,
                pnlPct = pnlPct,
                holdTimeMinutes = holdMin,
                exitReason = exitReason.ifBlank { "perps_close" },
                entryPhase = "perps",
                tradingMode = assetClass.uppercase(),
                discoverySource = snap.traderSource.ifBlank { "PERPS_TRADER" },
                setupQuality = if (snap.v3Score > 10) "A" else if (snap.v3Score > 0) "B" else "C",
                entryMcapUsd = 0.0,
                exitMcapUsd = 0.0,
                tokenAgeMinutes = 0.0,
                buyRatioPct = 50.0,
                volumeUsd = 0.0,
                liquidityUsd = snap.entryLiqUsd,
                holderCount = 0,
                topHolderPct = 0.0,
                holderGrowthRate = 0.0,
                devWalletPct = 0.0,
                bondingCurveProgress = 0.0,
                rugcheckScore = 0.0,
                emaFanState = "NEUTRAL",
                entryScore = snap.v3Score.toDouble(),
                priceFromAth = 0.0,
                maxGainPct = kotlin.math.max(pnlPct, 0.0),
                maxDrawdownPct = kotlin.math.min(pnlPct, 0.0),
                timeToPeakMins = holdMin,
                // V5.9.170 — real reason chain for the education layer.
                entryReason = snap.entryReason,
                traderSource = snap.traderSource.ifBlank { "perps" },
                lossReason   = lossReason,
            )
            EducationSubLayerAI.recordTradeOutcomeAcrossAllLayers(outcome)
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "recordClose failed for $symbol: ${e.message}")
        }
    }

    /**
     * ReflexAI pass-through for non-meme traders. Returns true if the reflex
     * layer wants to hard-exit the position right now (catastrophic candle,
     * liquidity drain). Trader should honour it.
     */
    fun reflexAbort(
        symbol: String,
        assetClass: String,
        currentPrice: Double,
        currentLiqUsd: Double,
    ): Boolean {
        val key = makeMintKey(assetClass, symbol)
        val snap = openEntries[key] ?: return false
        return try {
            // Build a minimal pseudo-TokenState for ReflexAI.
            val pseudo = buildPseudoTokenState(snap, currentPrice, currentLiqUsd)
            val verdict = ReflexAI.evaluate(pseudo, currentPrice, currentLiqUsd)
            verdict == ReflexAI.Reflex.ABORT || verdict == ReflexAI.Reflex.LIQ_DRAIN
        } catch (_: Exception) { false }
    }

    private fun buildPseudoTokenState(
        snap: EntrySnapshot,
        currentPrice: Double,
        currentLiqUsd: Double,
    ): com.lifecyclebot.data.TokenState {
        // ReflexAI only needs pos.isOpen, pos.entryPrice, pos.entryLiquidityUsd
        // and the live price/liq. Build a minimal stub with qtyToken=1 so the
        // isOpen computed getter returns true.
        val pos = com.lifecyclebot.data.Position(
            qtyToken = 1.0,
            entryPrice = snap.entryPrice,
            entryTime = snap.timestamp,
            entryLiquidityUsd = snap.entryLiqUsd,
        ).also { it.highestPrice = maxOf(snap.entryPrice, currentPrice) }
        val ts = com.lifecyclebot.data.TokenState(
            mint = snap.mintKey,
            symbol = snap.symbol,
        )
        ts.position = pos
        ts.lastLiquidityUsd = currentLiqUsd
        ts.lastPrice = currentPrice
        return ts
    }

    private fun makeMintKey(assetClass: String, symbol: String): String =
        "v3bridge:${assetClass.uppercase()}:${symbol.uppercase()}"

    /** For SentienceOrchestrator — visibility into what the bridge is tracking. */
    fun openEntryCount(): Int = openEntries.size
    fun openEntrySymbols(n: Int = 20): List<String> =
        openEntries.values.map { "${it.symbol}(${it.direction})@${it.v3Score}" }.take(n)
}
