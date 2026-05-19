package com.lifecyclebot.engine

// ═══════════════════════════════════════════════════════════════════════════
// BotServiceIntakeExt.kt — V5.9.1001
//
// Second batch of pure file-move refactor extracted from BotService.kt,
// following the V5.9.1000 pilot (BotServiceLifecycleExt.kt — landed green).
//
// Operator mandate (V5.9.997 session):
//   - "stupidly perfectly. this cant break the whole build."
//   - "parallel stage and build to consider all effects changes make globally"
//   - "absolutely no regression no butterfly effects"
//
// ─── WIDENING PASS (V5.9.1001 part 1) ──────────────────────────────────────
// 8 BotService private members widened to `internal` so the bodies below
// can call them from extension-function scope. Internal is module-private
// (same module = same access semantics inside the app), so no actual API
// surface change for any external caller. Targets:
//   - addLog                  (most-used logger; 7 candidates depend on it)
//   - emitWatchlistCapTrace   (used by selectOrderedMintsForCycle)
//   - marketScanner           (used by runScannerHeartbeat)
//   - bootMemeScanner         (used by runScannerHeartbeat)
//   - broadcastFallbackPrice  (used by tryFallbackPriceData)
//   - dex                     (used by tryFallbackPriceData)
//   - processTokenCycle       (used by synthesizeFallbackPair)
//   - admitProtectedMemeIntake (used by processTokenMergeQueue)
//
// ─── EXTRACTION PASS (V5.9.1001 part 2) ─────────────────────────────────────
// 7 functions moved into this file as `internal fun BotService.X()`:
//   - selectOrderedMintsForCycle   (83 lines)  — meme-watchlist ordering
//   - runScannerHeartbeat           (30 lines)  — scanner pulse check
//   - scanAndSellOrphans            (54 lines)  — orphan position sweep
//   - tryFallbackPriceData          (137 lines) — Dex/Birdeye fallback price
//   - synthesizeFallbackPair         (52 lines) — emergency pair synth
//   - processTokenMergeQueue        (66 lines)  — pump-portal intake drain
//   - cancelKeepAliveAlarm          (6 lines)   — alarm cancel (calls
//                                                 cancelAllRestartAlarms,
//                                                 itself an ext fn after
//                                                 V5.9.1000 — chain works)
//
// SAFETY GATES (pre-flight):
//   [1] Re-audited remaining 70 BotService private members against each
//       body — all 7 candidates resolve to widened internals or
//       companion-prefixed refs only.
//   [2] Companion-object refs that lose implicit binding inside extension
//       functions were pre-fixed: `sc`, `status`, `last` → `BotService.sc`,
//       `BotService.status`, `BotService.last` (Rule #35 lesson from
//       V5.9.1000a build break).
//   [3] Zero external call sites (verified across app/src/main and
//       app/src/test trees) — every caller is inside BotService.kt.
//   [4] No test-tree imports broken (Hard Rule #32 verified clean).
//   [5] Brace-balance delta in BotService.kt: maintained at b=14 baseline.
//   [6] No code-logic changes — every line preserves the same expressions,
//       branches, side effects. Pure file-move.
//
// NET SIZE CHANGE EXPECTED:
//   BotService.kt: ~17,284 → ~16,860 lines (−424 net, incl. 7 stub markers)
//   New file:      ~480 lines (incl. this header)
//
// AFTER THIS LANDS GREEN:
//   V5.9.1002 → Executor.kt persistence helpers (the V5.9.999b saveAll
//               worker pattern + V5.9.999 bounded-RPC helpers can be
//               extracted into ExecutorWalletExt.kt with the same playbook).
//   V5.9.1003 → BotService supervisor block (runReconcileSweep,
//               runFreezeDetectorTick, runFallbackSafetyExit, etc. —
//               these need `wallet` widened first, larger blast radius).
// ═══════════════════════════════════════════════════════════════════════════

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.ConfigStore
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.network.SolanaWallet

// V5.9.962 — extracted from botLoop to keep that method under the JVM
// 64KB bytecode cap. V5.9.960 inline run{} added ~3KB of bytecode and
// V5.9.961 added another ~1.5KB. Together they tripped Back-end (JVM)
// Internal error: Couldn't transform method node. Moving the selection
// into its own method costs ONE JVM method dispatch per cycle (~50ns)
// and frees ~5KB inside botLoop. Same selection logic — fresh + unseen
// + cold rotation with stale eviction.
internal fun BotService.selectOrderedMintsForCycle(
    forcedOpenMints: List<String>,
    otherMints: List<String>,
    orderedMintsRaw: List<String>,
): List<String> {
    val nowMs = System.currentTimeMillis()
    val FRESH_WINDOW_MS = 60_000L
    val PER_CYCLE_CAP = 96
    val STALE_PROCESS_COUNT_THRESHOLD = 12

    val entriesByMint: Map<String, com.lifecyclebot.engine.GlobalTradeRegistry.WatchlistEntry> = try {
        com.lifecyclebot.engine.GlobalTradeRegistry.getWatchlistEntries()
            .associateBy { it.mint }
    } catch (_: Throwable) { emptyMap() }

    val mustInclude = forcedOpenMints.toMutableList()
    val budget = (PER_CYCLE_CAP - mustInclude.size).coerceAtLeast(0)
    if (budget == 0 || otherMints.isEmpty()) {
        try { emitWatchlistCapTrace(PER_CYCLE_CAP, orderedMintsRaw.size, forcedOpenMints.size) } catch (_: Throwable) {}
        return mustInclude.distinct()
    }

    val fresh = mutableListOf<String>()
    val unseen = mutableListOf<String>()
    val cold = mutableListOf<Pair<String, Long>>()
    otherMints.forEach { mint ->
        val entry = entriesByMint[mint]
        when {
            entry == null -> unseen.add(mint)
            (nowMs - entry.addedAt) < FRESH_WINDOW_MS -> fresh.add(mint)
            entry.processCount == 0 -> unseen.add(mint)
            else -> cold.add(mint to entry.lastProcessedAt)
        }
    }

    // V5.9.961 stale eviction
    val staleEvict = mutableListOf<String>()
    val coldFiltered = cold.filter { (mint, _) ->
        val entry = entriesByMint[mint] ?: return@filter true
        if (entry.processCount >= STALE_PROCESS_COUNT_THRESHOLD) {
            staleEvict.add(mint)
            false
        } else true
    }
    if (staleEvict.isNotEmpty()) {
        staleEvict.forEach { mint ->
            try {
                com.lifecyclebot.engine.GlobalTradeRegistry.removeFromWatchlist(mint, "STALE_PROCESS_COUNT")
            } catch (_: Throwable) {}
        }
        try {
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "WATCHLIST_STALE_EVICT",
                "evicted=${staleEvict.size} threshold=$STALE_PROCESS_COUNT_THRESHOLD"
            )
        } catch (_: Throwable) {}
    }

    val coldMutable = coldFiltered.toMutableList()
    coldMutable.sortBy { it.second }

    val picked = mutableListOf<String>()
    picked.addAll(fresh.take(budget))
    if (picked.size < budget) picked.addAll(unseen.take(budget - picked.size))
    if (picked.size < budget) picked.addAll(coldMutable.map { it.first }.take(budget - picked.size))

    try {
        emitWatchlistCapTrace(PER_CYCLE_CAP, orderedMintsRaw.size, forcedOpenMints.size)
        com.lifecyclebot.engine.ForensicLogger.lifecycle(
            "WATCHLIST_RR",
            "cap=$PER_CYCLE_CAP picked=${picked.size} fresh=${fresh.size} unseen=${unseen.size} cold=${cold.size} forcedOpen=${forcedOpenMints.size} total=${orderedMintsRaw.size}"
        )
    } catch (_: Throwable) {}

    return (mustInclude + picked).distinct()
}

/**
 * V5.9.660 — extracted from botLoop to keep it under the JVM 64KB
 * method size limit. Same cadence (every 6 loops, ~30s), same
 * behavior. Operator-facing visibility line for silent scanner
 * failures + auto-recovery if marketScanner went null while running.
 */
internal fun BotService.runScannerHeartbeat() {
    try {
        val sc = marketScanner
        if (sc == null) {
            ErrorLogger.info("BotService", "🩺 SCANNER_HEARTBEAT: marketScanner=NULL running=${BotService.status.running} watch=${GlobalTradeRegistry.size()}")
            if (BotService.status.running) {
                addLog("🩹 Heartbeat: scanner NULL — auto-recovering")
                bootMemeScanner(reason = "HEARTBEAT_NULL")
            }
        } else {
            val snap = try { sc.getThroughputTelemetrySnapshot() } catch (_: Throwable) { null }
            if (snap != null) {
                ErrorLogger.info(
                    "BotService",
                    "🩺 SCANNER_HEARTBEAT: alive=${snap.alive} ageSec=${snap.ageSec} src=${snap.src} ok=${snap.ok} err=${snap.err} raw=${snap.raw} enq=${snap.enq} cd=${snap.cd} liqRej=${snap.liqRej} watch=${GlobalTradeRegistry.size()}"
                )
            } else {
                ErrorLogger.info("BotService", "🩺 SCANNER_HEARTBEAT: snapshot=null watch=${GlobalTradeRegistry.size()}")
            }
        }
    } catch (e: Throwable) {
        ErrorLogger.debug("BotService", "Scanner heartbeat tick error: ${e.message}")
    }
}

/**
 * Periodic orphan scan during runtime.
 * Catches tokens that failed to sell and are stuck in wallet.
 */
internal fun BotService.scanAndSellOrphans(w: SolanaWallet) {
    try {
        val tokenAccounts = w.getTokenAccounts()
        val trackedMints = synchronized(BotService.status.tokens) {
            BotService.status.tokens.values
                .filter { it.position.isOpen }
                .map { it.mint }
                .toSet()
        }
        
        var orphansFound = 0
        var orphansSold = 0
        
        tokenAccounts.forEach { (mint, qty) ->
            // Skip actual dust (less than $0.01 value typically)
            // For meme tokens, even 0.5 could be significant
            // Better: Skip if qty is essentially zero
            if (qty < 0.0000001) return@forEach
            // Skip tracked positions
            if (mint in trackedMints) return@forEach
            // Skip SOL
            if (mint == "So11111111111111111111111111111111111111112") return@forEach
            
            orphansFound++
            val symbol = BotService.status.tokens[mint]?.symbol ?: mint.take(8)
            addLog("🧹 ORPHAN FOUND: $symbol | qty=$qty | mint=${mint.take(12)}...")
            
            try {
                val sold = executor.sellOrphanedToken(mint, qty, w)
                if (sold) {
                    orphansSold++
                    addLog("✅ ORPHAN SOLD: $symbol")
                } else {
                    addLog("⚠️ ORPHAN SELL FAILED: $symbol - sell manually via Jupiter")
                }
            } catch (e: Exception) {
                addLog("❌ ORPHAN ERROR: $symbol - ${e.message}")
            }
        }
        
        if (orphansFound > 0) {
            addLog("🧹 Orphan scan: found $orphansFound, sold $orphansSold")
        } else {
            addLog("✅ No orphaned tokens found")
        }
    } catch (e: Exception) {
        addLog("⚠️ Orphan scan failed: ${e.message}")
        ErrorLogger.error("BotService", "Orphan scan error: ${e.message}", e)
    }
}

internal fun BotService.tryFallbackPriceData(mint: String, ts: TokenState): Boolean {
    // Try Birdeye first
    try {
        val cfg2 = ConfigStore.load(applicationContext)
        val ov = com.lifecyclebot.network.BirdeyeApi(cfg2.birdeyeApiKey).getTokenOverview(mint)
        if (ov != null && ov.priceUsd > 0) {
            synchronized(ts) {
                ts.lastPrice = ov.priceUsd
                ts.lastPriceUpdate = System.currentTimeMillis()
                ts.lastPriceSource = "BIRDEYE_OVERVIEW"  // V5.9.744
                ts.lastLiquidityUsd = ov.liquidity
                ts.lastMcap = ov.marketCap
                ts.lastFdv = ov.marketCap
                val syntheticCandle = com.lifecyclebot.data.Candle(
                    ts = System.currentTimeMillis(), priceUsd = ov.priceUsd,
                    marketCap = ov.marketCap, volumeH1 = 0.0, volume24h = 0.0,
                    buysH1 = 0, sellsH1 = 0, highUsd = ov.priceUsd,
                    lowUsd = ov.priceUsd, openUsd = ov.priceUsd,
                )
                synchronized(ts.history) {
                    ts.history.addLast(syntheticCandle)
                    if (ts.history.size > 300) ts.history.removeFirst()
                }
            }
            broadcastFallbackPrice(mint, ov.priceUsd)   // V5.9.423
            addLog("📡 Birdeye: ${ts.symbol} \$${ov.priceUsd}", mint)
            return true
        }
    } catch (_: Exception) {}

    // V5.9.423 — DexScreenerOracle (separate code path from dex.getBestPair,
    // different endpoint, different cache). When the pair-based call fails
    // this token-address call often still returns — DexScreener caches
    // token-level and pair-level data independently.
    if (ts.lastPrice <= 0 || (System.currentTimeMillis() - ts.lastPriceUpdate) > 120_000L) {
        try {
            val priceUsd = kotlinx.coroutines.runBlocking {
                com.lifecyclebot.perps.DexScreenerOracle.getPriceByAddress(mint)
            }
            if (priceUsd != null && priceUsd > 0) {
                synchronized(ts) {
                    ts.lastPrice = priceUsd
                    ts.lastPriceUpdate = System.currentTimeMillis()
                    ts.lastPriceSource = "PAIR_FALLBACK"  // V5.9.744
                }
                broadcastFallbackPrice(mint, priceUsd)
                addLog("📊 DexScreener(token): ${ts.symbol} \$${priceUsd}", mint)
                return true
            }
        } catch (_: Throwable) {}
    }

    // V5.9.423 — BirdeyeOracle token-address API (different from BirdeyeApi
    // used above, which is overview-focused; this one is price-focused and
    // hits a separate rate-limit bucket).
    if (ts.lastPrice <= 0 || (System.currentTimeMillis() - ts.lastPriceUpdate) > 120_000L) {
        try {
            val priceUsd = kotlinx.coroutines.runBlocking {
                com.lifecyclebot.perps.BirdeyeOracle.getPriceByAddress(mint)
            }
            if (priceUsd != null && priceUsd > 0) {
                synchronized(ts) {
                    ts.lastPrice = priceUsd
                    ts.lastPriceUpdate = System.currentTimeMillis()
                    ts.lastPriceSource = "PAIR_FALLBACK"  // V5.9.744
                }
                broadcastFallbackPrice(mint, priceUsd)
                addLog("🐦 BirdeyeOracle: ${ts.symbol} \$${priceUsd}", mint)
                return true
            }
        } catch (_: Throwable) {}
    }

    // Try pump.fun API
    // V5.9.423 — also retry pump.fun if the BotService.last successful price is >120s
    // stale. Previously the `if (ts.lastPrice <= 0)` guard meant pump.fun
    // was only consulted on brand-new holds that had never been priced.
    if (ts.lastPrice <= 0 || (System.currentTimeMillis() - ts.lastPriceUpdate) > 120_000L) {
        try {
            val client = com.lifecyclebot.network.SharedHttpClient.builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build()
            // V5.9.861 — health-aware execute: auto-migrate dead hosts + record telemetry
            val originalUrl = "https://frontend-api-v3.pump.fun/coins/$mint"
            val effectiveUrl = try { com.lifecyclebot.engine.AutoEndpointMigrator.rewrite(originalUrl) } catch (_: Throwable) { originalUrl }
            val request = okhttp3.Request.Builder()
                .url(effectiveUrl)
                .header("Accept", "application/json").build()
            val pumpStart = System.currentTimeMillis()
            val response = try {
                client.newCall(request).execute()
            } catch (e: Exception) {
                try { com.lifecyclebot.engine.ApiHealthMonitor.recordNetworkError("pumpfun", e.message) } catch (_: Throwable) {}
                throw e
            }
            try { com.lifecyclebot.engine.ApiHealthMonitor.record("pumpfun", response.code, System.currentTimeMillis() - pumpStart) } catch (_: Throwable) {}
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val json = org.json.JSONObject(body)
                    val mcap = json.optDouble("usd_market_cap", 0.0)
                    // NOTE: pump.fun API's "price" field is in SOL (not USD), so we
                    // compute a correct USD price from usd_market_cap / total_supply.
                    // Pump.fun tokens always have 1B token supply as their standard.
                    val totalSupply = json.optDouble("total_supply", 1_000_000_000.0)
                        .let { if (it <= 0) 1_000_000_000.0 else it }
                    val priceUsd = if (mcap > 0 && totalSupply > 0) mcap / totalSupply else 0.0
                    if (mcap > 0) {
                        synchronized(ts) {
                            ts.lastPrice = priceUsd
                            ts.lastPriceUpdate = System.currentTimeMillis()
                            ts.lastPriceSource = "PUMP_FUN_FRONTEND_API"  // V5.9.744
                            ts.lastPriceDex = "PUMP_FUN"
                            ts.lastMcap = mcap
                            ts.lastFdv = mcap
                            ts.lastLiquidityUsd = mcap * 0.1
                            val syntheticCandle = com.lifecyclebot.data.Candle(
                                ts = System.currentTimeMillis(), priceUsd = priceUsd,
                                marketCap = mcap, volumeH1 = 0.0, volume24h = 0.0,
                                buysH1 = 0, sellsH1 = 0, highUsd = priceUsd,
                                lowUsd = priceUsd, openUsd = priceUsd,
                            )
                            synchronized(ts.history) {
                                ts.history.addLast(syntheticCandle)
                                if (ts.history.size > 300) ts.history.removeFirst()
                            }
                        }
                        addLog("🎯 Pump.fun: ${ts.symbol} mcap=\$${mcap.toInt()} priceUsd=\$${String.format("%.10f", priceUsd)}", mint)
                        broadcastFallbackPrice(mint, priceUsd)   // V5.9.423
                        return true
                    }
                }
            }
        } catch (_: Exception) {}
    }
    return false
}

/**
 * V5.9.615 — Build a synthetic PairInfo from already-populated TokenState
 * fallback data (pump.fun API / Birdeye delivered price+mcap+liquidity into
 * ts.lastPrice / ts.lastMcap / ts.lastLiquidityUsd, but DexScreener has no
 * pair). This lets processTokenCycle continue into V3/ShitCoin entry
 * evaluation instead of returning early. Pre-graduation pump.fun tokens
 * are the ShitCoin lane's designed target market.
 *
 * Field semantics:
 *  - candle: synthetic 1-tick candle at current fallback price+mcap.
 *    Volume / buys / sells default to 0 — downstream scorers already
 *    handle "no data yet" via FluidLearningAI bootstrap heuristics.
 *  - liquidity / fdv: copied from ts. For pump.fun bonding-curve tokens
 *    this is mcap * 0.85 (set by tryFallbackPriceData seed path).
 *  - url: tagged so downstream source-detection sees pump.fun, which
 *    correctly routes the token into ShitCoinTraderAI.LaunchPlatform.PUMP_FUN.
 */
internal fun BotService.synthesizeFallbackPair(ts: com.lifecyclebot.data.TokenState): com.lifecyclebot.network.PairInfo? {
    if (ts.lastPrice <= 0.0) return null
    val nowMs = System.currentTimeMillis()
    val candle = com.lifecyclebot.data.Candle(
        ts = nowMs,
        priceUsd = ts.lastPrice,
        marketCap = ts.lastMcap.coerceAtLeast(0.0),
        volumeH1 = 0.0,
        volume24h = 0.0,
        buysH1 = 0,
        sellsH1 = 0,
        highUsd = ts.lastPrice,
        lowUsd = ts.lastPrice,
        openUsd = ts.lastPrice,
    )
    // URL hint so processTokenCycle's source-inference still tags pump.fun
    // correctly when ts.source is empty (the WS feed sets PUMP_PORTAL_WS,
    // but defense-in-depth: anything else lands here too).
    val url = if (ts.source.contains("PUMP", ignoreCase = true)) {
        "https://pump.fun/${ts.mint}"
    } else {
        ""
    }
    return com.lifecyclebot.network.PairInfo(
        pairAddress = "",                              // no on-chain pair yet (bonding curve)
        baseSymbol = ts.symbol.ifBlank { ts.mint.take(6) },
        baseName = ts.name.ifBlank { ts.symbol.ifBlank { ts.mint.take(6) } },
        url = url,
        candle = candle,
        pairCreatedAtMs = ts.addedToWatchlistAt.takeIf { it > 0 } ?: nowMs,
        liquidity = ts.lastLiquidityUsd.coerceAtLeast(0.0),
        fdv = ts.lastFdv.takeIf { it > 0 } ?: ts.lastMcap.coerceAtLeast(0.0),
        baseTokenAddress = ts.mint,
    )
}

internal fun BotService.processTokenMergeQueue(loopCount: Int) {
    val mergedTokens = TokenMergeQueue.processQueue()
    for (merged in mergedTokens) {
        val boostLabel = if (merged.multiScannerBoost) " [MULTI-SCANNER]" else ""
        val scannersInfo = if (merged.allScanners.size > 1)
            " (${merged.allScanners.joinToString("+")})" else ""

        val added = admitProtectedMemeIntake(
            mint = merged.mint,
            symbol = merged.symbol,
            name = merged.symbol,
            source = merged.primaryScanner,
            marketCapUsd = merged.marketCapUsd,
            liquidityUsd = merged.liquidityUsd,
            volumeH1 = merged.volumeH1,
            confidence = merged.confidence,
            allSources = merged.allScanners,
            playSound = true,
            operatorLog = true,
        )

        if (added) {
            try { com.lifecyclebot.engine.WatchlistTtlPolicy.mark(merged.symbol, merged.confidence) } catch (_: Throwable) {}
            TradeLifecycle.watchlisted(merged.mint, GlobalTradeRegistry.size(), "merged: ${merged.primaryScanner}$scannersInfo$boostLabel")
        }
    }

    val probationResults = GlobalTradeRegistry.processProbation()
    for (result in probationResults) {
        when (result.action) {
            "PROMOTED" -> {
                addLog("✅ PROMOTED: ${result.symbol} | ${result.reason}", result.mint)
                soundManager.playNewToken()
                try {
                    val probEntry = GlobalTradeRegistry.getProbationEntry(result.mint)
                    admitProtectedMemeIntake(
                        mint = result.mint,
                        symbol = result.symbol,
                        name = result.symbol,
                        source = "PROBATION",
                        marketCapUsd = probEntry?.initialMcap ?: 0.0,
                        liquidityUsd = probEntry?.initialLiquidity ?: 0.0,
                        confidence = 50,
                        allSources = setOf("PROBATION"),
                        playSound = false,
                        operatorLog = false,
                    )
                } catch (e: Exception) {
                    ErrorLogger.debug("BotService", "PROMOTED protected intake hydrate error: ${e.message}")
                }
            }
            "REJECTED" -> {
                // V5.9.626 — probation rejection is execution memory only;
                // protected intake must not shrink or hide the universe.
                ErrorLogger.debug("BotService", "🛡 PROBATION_SHADOW: ${result.symbol} | ${result.reason}")
            }
        }
    }

    if (loopCount % 30 == 0 && TokenMergeQueue.getPendingCount() > 0) {
        addLog("🔀 ${TokenMergeQueue.getStats()}")
    }
    if (loopCount % 30 == 0 && GlobalTradeRegistry.probationSize() > 0) {
        addLog("⏳ ${GlobalTradeRegistry.getProbationStats()}")
    }
}

// V5.9.1000 — cancelAllRestartAlarms() extracted to BotServiceLifecycleExt.kt

internal fun BotService.cancelKeepAliveAlarm() {
    cancelAllRestartAlarms()
    ErrorLogger.info("BotService", "Keep-alive alarms cancelled")
}
