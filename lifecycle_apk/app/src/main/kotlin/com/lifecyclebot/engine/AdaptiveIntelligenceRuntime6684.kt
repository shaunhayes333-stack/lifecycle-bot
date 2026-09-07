package com.lifecyclebot.engine

import android.content.Context
import com.lifecyclebot.engine.lab.LabAssetClass
import com.lifecyclebot.engine.lab.LlmLabEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6684 — durable owner of AATE's adaptive background control plane.
 *
 * Earlier revisions wired Lab / Sentience / SSI directly into BotService and
 * later giant-file refactors silently dropped those call sites while leaving
 * the classes behind. One idempotent process-lifecycle authority now owns:
 *   Lab invention + paper evaluation + promotion
 *   lane re-proof / promotion epochs
 *   sentience reflection
 *   SSI pilot
 *   chronic-bleeder scouting
 *   canonical outcome driven autotuning
 *
 * This object never places an order. Hard safety, FDG, executable finality and
 * canonical accounting remain separate sovereign authorities.
 */
object AdaptiveIntelligenceRuntime6684 {
    const val VERSION = "V5.0.6684_ADAPTIVE_INTELLIGENCE_RUNTIME"
    private val started = AtomicBoolean(false)
    private val loops = AtomicLong(0L)
    private val lastScoutMs = AtomicLong(0L)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext

        try { LlmLabEngine.start(app) } catch (t: Throwable) {
            ErrorLogger.warn("AdaptiveRuntime6684", "lab start failed: ${t.message}")
        }
        try { SentienceOrchestrator.start(app) } catch (t: Throwable) {
            ErrorLogger.warn("AdaptiveRuntime6684", "sentience start failed: ${t.message}")
        }
        try { SsiPilotCouncil.start() } catch (t: Throwable) {
            ErrorLogger.warn("AdaptiveRuntime6684", "ssi start failed: ${t.message}")
        }

        scope.launch {
            ErrorLogger.info("AdaptiveRuntime6684", "🧠 $VERSION started")
            while (isActive) {
                loops.incrementAndGet()
                try { LlmLabEngine.tick { buildUniverse() } } catch (_: Throwable) {
                    try { PipelineHealthCollector.labelInc("ADAPTIVE_LAB_TICK_ERROR_6684") } catch (_: Throwable) {}
                }
                try { LaneAutoPauseGuard.evaluateLive() } catch (_: Throwable) {}
                try { AdaptiveLaneReproof6684.tick() } catch (_: Throwable) {}

                val now = System.currentTimeMillis()
                val prev = lastScoutMs.get()
                if (now - prev >= 60_000L && lastScoutMs.compareAndSet(prev, now)) {
                    try { ChronicBleederScout.tick() } catch (_: Throwable) {}
                }
                try { SentienceHooks.maybeAutoTune(app) } catch (_: Throwable) {}
                delay(10_000L)
            }
        }
    }

    private fun buildUniverse(): List<LlmLabEngine.LabUniverseTick> {
        val out = ArrayList<LlmLabEngine.LabUniverseTick>()
        val regime = try {
            when (com.lifecyclebot.v4.meta.CrossMarketRegimeAI.assessRegime().mode) {
                com.lifecyclebot.v4.meta.GlobalRiskMode.RISK_ON,
                com.lifecyclebot.v4.meta.GlobalRiskMode.TRENDING -> "BULL"
                com.lifecyclebot.v4.meta.GlobalRiskMode.RISK_OFF -> "BEAR"
                else -> "CHOP"
            }
        } catch (_: Throwable) { "ANY" }

        val tokens = try { BotService.status.tokens.values.toList() } catch (_: Throwable) { emptyList() }
        tokens.forEach { ts ->
            val price = ts.lastPrice.takeIf { it > 0.0 } ?: ts.history.lastOrNull()?.priceUsd ?: 0.0
            if (price > 0.0) {
                out += LlmLabEngine.LabUniverseTick(
                    symbol = ts.symbol.ifBlank { ts.mint.take(8) },
                    mint = ts.mint,
                    asset = LabAssetClass.MEME,
                    price = price,
                    score = ts.entryScore.toInt().coerceIn(0, 100),
                    regime = regime,
                )
            }
        }

        fun push(symbol: String, asset: LabAssetClass, price: Double) {
            if (symbol.isBlank() || price <= 0.0) return
            out += LlmLabEngine.LabUniverseTick(symbol, symbol, asset, price, 50, regime)
        }
        try { com.lifecyclebot.perps.CryptoAltTrader.getOpenPositions().forEach { push(it.market.symbol, LabAssetClass.ALT, it.currentPrice) } } catch (_: Throwable) {}
        try { com.lifecyclebot.perps.TokenizedStockTrader.getActivePositions().forEach { push(it.market.symbol, LabAssetClass.STOCK, it.currentPrice) } } catch (_: Throwable) {}
        try { com.lifecyclebot.perps.PerpsTraderAI.getActivePositions().forEach { push(it.market.symbol, LabAssetClass.MARKETS, it.currentPrice) } } catch (_: Throwable) {}
        try { com.lifecyclebot.perps.ForexTrader.getAllPositions().forEach { push(it.market.symbol, LabAssetClass.FOREX, it.currentPrice) } } catch (_: Throwable) {}
        try { com.lifecyclebot.perps.MetalsTrader.getAllPositions().forEach { push(it.market.symbol, LabAssetClass.METAL, it.currentPrice) } } catch (_: Throwable) {}
        try { com.lifecyclebot.perps.CommoditiesTrader.getAllPositions().forEach { push(it.market.symbol, LabAssetClass.COMMODITY, it.currentPrice) } } catch (_: Throwable) {}
        return out
    }

    fun statusLine(): String =
        "$VERSION started=${started.get()} loops=${loops.get()} ${AdaptiveLaneReproof6684.statusLine()}"
}
