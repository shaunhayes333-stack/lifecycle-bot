package com.lifecyclebot.engine

import com.lifecyclebot.network.SharedHttpClient

import com.lifecyclebot.data.BotStatus
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.network.SolanaWallet
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * StartupReconciler
 *
 * Runs once on bot start (after wallet connects) to verify
 * the bot's internal state matches actual on-chain reality.
 *
 * Problems it catches:
 *   1. Bot thinks it holds a position but on-chain token balance = 0
 *      → Position closed externally (manual sell, rug, etc.)
 *      → Clear the ghost position so bot doesn't try to sell nothing
 *
 *   2. Bot thinks wallet has X SOL but on-chain shows Y SOL
 *      → Could mean the app crashed during a trade
 *      → Alert user and update balance
 *
 *   3. Token account doesn't exist on-chain for a supposed holding
 *      → Token may have been burned or account closed
 *      → Clear the position
 *
 *   4. Orphaned tokens - tokens in wallet that bot doesn't know about
 *      → Bot crashed mid-trade or sell failed
 *      → Auto-sell these tokens back to SOL
 *
 * This runs in ~3-5 seconds on startup and prevents the most dangerous
 * class of bug: the bot operating on stale/incorrect state.
 */
class StartupReconciler(
    private val wallet: SolanaWallet,
    private val status: BotStatus,
    private val onLog: (String) -> Unit,
    private val onAlert: (String, String) -> Unit,
    private val executor: Executor? = null,  // For auto-selling orphaned tokens
    private val autoSellOrphans: Boolean = true,  // Enable auto-sell of orphaned tokens
) {

    data class ReconciliationResult(
        val walletBalanceOnChain: Double,
        val walletBalanceBotState: Double,
        val balanceMismatch: Boolean,
        val ghostPositionsCleared: List<String>,   // mints of cleared ghost positions
        val positionsVerified: List<String>,       // mints confirmed open on-chain
        val warnings: List<String>,
    )

    fun reconcile(): ReconciliationResult {
        val warnings      = mutableListOf<String>()
        val ghostCleared  = mutableListOf<String>()
        val verified      = mutableListOf<String>()

        // ── 1. Get actual SOL balance ─────────────────────────────
        val onChainSol = try {
            wallet.getSolBalance()
        } catch (e: Exception) {
            onLog("Reconcile: could not fetch SOL balance — ${e.message}")
            return ReconciliationResult(0.0, status.walletSol, false,
                emptyList(), emptyList(), listOf("SOL balance fetch failed"))
        }

        val botStateSol = status.walletSol
        val balanceDiff = Math.abs(onChainSol - botStateSol)
        val mismatch    = balanceDiff > 0.01   // >0.01 SOL discrepancy is notable

        if (mismatch && botStateSol > 0) {
            val msg = "Balance mismatch: bot thinks ${"%.4f".format(botStateSol)} SOL, " +
                      "on-chain shows ${"%.4f".format(onChainSol)} SOL " +
                      "(diff: ${"%.4f".format(balanceDiff)} SOL)"
            warnings.add(msg)
            onLog("⚠️ $msg")
            onAlert("Balance Mismatch", msg)
        }

        // Update bot state with real balance
        status.walletSol = onChainSol

        // ── 2. Verify open positions ──────────────────────────────
        val openPositions = status.openPositions

        // V5.9.102: ADOPT ORPHAN TOKENS (always runs — was only on empty book)
        // The original logic only scanned for untracked wallet tokens when
        // openPositions was empty. That meant if the bot had ANY tracked
        // position, real-on-chain-but-untracked tokens (caused by phantom
        // guard wiping positions that actually settled, or tx timing drift)
        // would fall through to scanAndSellOrphanedTokens() below and get
        // LIQUIDATED AT MARKET. Users saw profitable positions auto-dumped.
        // Now we always try to adopt first; only truly unknown mints fall
        // through to the orphan sell path.
        val adoptedMints = mutableListOf<String>()
        try {
            val tokenAccounts = wallet.getTokenAccounts()
            // V5.9.748 — adoption path: only TRULY open positions count as
            // "tracked". V5.9.739 added stale-pendingVerify positions
            // (>120s, qty>0) to openPositions so the UI could show them as
            // "viewable" while waiting for on-chain confirmation. But that
            // visibility relaxation accidentally fed the reconciler too:
            // a stuck-pending position's mint landed in trackedMints, so
            // when the wallet sweep saw the matching wallet token it
            // SKIPPED adoption (mint already "tracked") → the position
            // stayed pendingVerify forever → exits never fired → bot
            // could not sell tokens that were physically in the wallet.
            // Fix: exclude pendingVerify here so the wallet sweep promotes
            // them into real open positions. The orphan-auto-sell sweep
            // below still uses openPositions verbatim (we do NOT want to
            // liquidate a freshly-bought stuck-pending position).
            val trackedMints = openPositions
                .filter { !it.position.pendingVerify }
                .map { it.mint }
                .toSet()
            // Stuck-pending mints — we'll promote these from the wallet
            // sweep instead of adopting as new orphan positions.
            val pendingMints = openPositions
                .filter { it.position.pendingVerify && it.position.qtyToken > 0.0 }
                .map { it.mint }
                .toSet()

            // V5.9.673 — forensic visibility of the wallet sweep. Operator
            // saw an on-chain RMG position (+353% gain, 436195 tokens) go
            // invisible to the bot after a system-kill / restart cycle.
            // Until this dump landed we could not tell whether the sweep
            // even ran, whether getTokenAccounts() returned the mint, or
            // whether DexScreener adoption silently failed downstream.
            try {
                val nonZero = tokenAccounts.filter { (m, q) -> q > 0.0 && m != "So11111111111111111111111111111111111111112" }
                onLog("🔎 WALLET SWEEP: ${tokenAccounts.size} token account(s), ${nonZero.size} non-zero non-SOL, ${trackedMints.size} already tracked")
                nonZero.forEach { (mint, qty) ->
                    val seen = mint in trackedMints
                    onLog("   • ${mint.take(12)}… qty=${"%.4f".format(qty)} alreadyTracked=$seen")
                }
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "WALLET_SWEEP",
                    "accounts=${tokenAccounts.size} nonZero=${nonZero.size} tracked=${trackedMints.size}"
                )
            } catch (_: Throwable) { /* logging must never block reconcile */ }

            // V5.9.256: WALLET TOKEN MEMORY RECOVERY
            // Before the standard adoption path runs, ask WalletTokenMemory if it knows
            // about any tokens in the wallet that aren't yet in status.tokens.
            // This handles the case where the bot restarted and the scanner hasn't had
            // time to re-discover tokens — we reconstruct full TokenState entries from
            // the persistent buy journal so the bot can manage exits immediately.
            try {
                val statusTokensCopy = synchronized(status.tokens) { status.tokens.toMap() }
                val recovered = WalletTokenMemory.reconstructMissingPositions(
                    walletTokens  = tokenAccounts,
                    statusTokens  = statusTokensCopy,
                    onLog         = onLog,
                )
                if (recovered.isNotEmpty()) {
                    synchronized(status.tokens) {
                        for (ts in recovered) {
                            status.tokens[ts.mint] = ts
                        }
                    }
                    onLog("📥 WalletTokenMemory: injected ${recovered.size} recovered position(s) into scanner state")
                }
            } catch (e: Exception) {
                onLog("⚠️ WalletTokenMemory recovery error: ${e.message}")
            }
            tokenAccounts.forEach { (mint, qty) ->
                if (qty <= 0.0) return@forEach
                if (mint == "So11111111111111111111111111111111111111112") return@forEach
                // V5.9.748 — promote stuck-pendingVerify positions whose
                // tokens are confirmed on-chain. This is the actual recovery
                // path for the "tokens arrive in host wallet, bot shows no
                // open position" symptom (operator screenshot 22:38:47):
                // PHANNY, COPIUM, Crack, YilongMa, chyna, WEN all landed in
                // wallet but stayed pendingVerify because the verify polls
                // and watchdog couldn't catch them, leaving the bot blind
                // to its own holdings → could not exit → kept trying to
                // re-buy → Insufficient funds.
                if (mint in pendingMints) {
                    val pendingTs = synchronized(status.tokens) { status.tokens[mint] }
                    if (pendingTs != null) {
                        synchronized(pendingTs) {
                            pendingTs.position = pendingTs.position.copy(
                                qtyToken = qty,
                                pendingVerify = false,
                            )
                        }
                        try { com.lifecyclebot.engine.PositionPersistence.savePosition(pendingTs) } catch (_: Exception) {}
                        try { com.lifecyclebot.engine.WalletTokenMemory.recordBuy(pendingTs) } catch (_: Exception) {}
                        try { com.lifecyclebot.engine.TokenLifecycleTracker.onTokenLanded(mint, qty) } catch (_: Throwable) {}
                        onLog("🛟 RECONCILE-PROMOTE: ${pendingTs.symbol} | ${"%.4f".format(qty)} tokens on-chain — clearing pendingVerify, position now LIVE")
                        ErrorLogger.warn(
                            "StartupReconciler",
                            "🛟 RECONCILE-PROMOTE: ${pendingTs.symbol} ($mint) — was pendingVerify, on-chain qty=$qty, promoting to open."
                        )
                        verified += mint
                    }
                    return@forEach
                }
                if (mint in trackedMints) return@forEach
                // V5.9.253 FIX: was "?: return@forEach" — silently dropped tokens whose mint
                // was never scanned into status.tokens (e.g. tokens from previous sessions, xStock
                // tokens, or anything the scanner hadn't discovered this run). These would sit in
                // the wallet forever, invisible to the bot.
                // Now: if mint is unknown, create a minimal TokenState so adoption can proceed.
                // The orphan auto-sell path below will handle truly unknown/unwanted mints.
                val ts = status.tokens[mint]
                    ?: run {
                        // V5.9.257 FIX: Previously this just deferred to the orphan path
                        // (which does nothing when autoSellOrphans=false). Tokens from
                        // previous sessions, bot restarts, or phantom-wiped positions
                        // that later confirmed were silently abandoned here forever.
                        //
                        // Now: call DexScreener for live price/symbol, build a real
                        // TokenState, adopt it, and let the bot manage exits.
                        // This covers ABC, any meme the bot bought before WalletTokenMemory
                        // was introduced, and any future restart scenario.
                        onLog("🔍 UNKNOWN MINT: ${mint.take(12)}… | qty=${"%.4f".format(qty)} — looking up on DexScreener for adoption")
                        try {
                            val dex = com.lifecyclebot.network.DexscreenerApi()
                            val pair = dex.getBestPair(mint)
                            if (pair != null && pair.candle.priceUsd > 0) {
                                val livePrice = pair.candle.priceUsd
                                val adoptTs = com.lifecyclebot.data.TokenState(
                                    mint        = mint,
                                    symbol      = pair.baseSymbol.ifBlank { mint.take(8) },
                                    name        = pair.baseName.ifBlank { pair.baseSymbol },
                                    pairAddress = pair.pairAddress,
                                    pairUrl     = pair.url,
                                    source      = "WALLET_RECOVERY",
                                    lastPrice   = livePrice,
                                    lastMcap    = pair.candle.marketCap,
                                    lastLiquidityUsd = pair.liquidity,
                                    phase       = "hold",
                                    position    = com.lifecyclebot.data.Position(
                                        qtyToken        = qty,
                                        entryPrice      = livePrice,  // best guess: current price
                                        entryTime       = System.currentTimeMillis() - 300_000L, // assume 5min ago
                                        costSol         = 0.0,        // unknown — no memory of buy
                                        entryScore      = 50.0,
                                        entryPhase      = "wallet_recovery",
                                        highestPrice    = livePrice,
                                        isPaperPosition = false,       // real wallet = live position
                                    ),
                                )
                                synchronized(status.tokens) { status.tokens[mint] = adoptTs }
                                adoptedMints += mint
                                onLog("📥 WALLET RECOVERY (DexScreener): ${adoptTs.symbol} | ${"%.4f".format(qty)} tokens @ \$${livePrice} | mcap=\$${pair.candle.marketCap.toLong()}")
                                // V5.9.495z39 P1 — wallet-recovery re-registration
                                // arrives with `costSol=0.0` (no memory of buy)
                                // and `entryPrice=livePrice` (best guess only).
                                // RecoveryLockTracker blocks profit-taking sells
                                // until chain basis is loaded AND a live quote
                                // proves profit after fees. Without this lock
                                // the bot would immediately TP a treasury-recovered
                                // position into a fake +X% PnL based on the
                                // synthetic entryPrice.
                                try {
                                    com.lifecyclebot.engine.sell.RecoveryLockTracker.lock(
                                        mint = mint,
                                        symbol = adoptTs.symbol,
                                        reason = "WALLET_RECOVERY_DEXSCREENER",
                                    )
                                    com.lifecyclebot.engine.TokenLifecycleTracker.autoImportFromWallet(
                                        mint = mint,
                                        symbol = adoptTs.symbol,
                                        walletUiAmount = qty,
                                        venue = "wallet_recovery",
                                    )
                                } catch (_: Throwable) { /* fail-soft */ }
                                onAlert(
                                    "Wallet Recovery",
                                    "${adoptTs.symbol}: found in wallet, not in bot memory — adopted at current price \$${livePrice}. Bot will manage TP/SL."
                                )
                                adoptTs  // return the new TokenState
                            } else {
                                onLog("⚠️ UNKNOWN MINT (no DexScreener data): ${mint.take(12)}… | qty=${"%.4f".format(qty)} — will appear in orphan path")
                                null
                            }
                        } catch (e: Exception) {
                            onLog("⚠️ UNKNOWN MINT lookup failed: ${mint.take(12)}… | ${e.message}")
                            null
                        } ?: return@forEach
                    }
                // V5.9.251: If position is pendingVerify, the 30s buy-coroutine
                // window expired but tokens DID arrive on-chain. Promote now.
                if (ts.position.pendingVerify && qty > 0.0) {
                    synchronized(ts) {
                        ts.position = ts.position.copy(
                            qtyToken      = qty,
                            pendingVerify = false,
                        )
                    }
                    adoptedMints += mint
                    onLog("✅ PENDING→LIVE: ${ts.symbol} | ${"%.4f".format(qty)} tokens confirmed on-chain (was pendingVerify)")
                    onAlert("Position Confirmed", "${ts.symbol}: buy confirmed on-chain (${"%.4f".format(qty)} tokens). Now actively managed.")
                    return@forEach
                }
                if (ts.position.isOpen) return@forEach
                val adoptPrice = ts.history.lastOrNull()?.priceUsd
                    ?: ts.lastPrice.takeIf { it > 0 }
                    ?: run {
                        // V5.9.253: token IS in status.tokens but has no price history at all.
                        // Still log it and let orphan path handle the sell rather than silently dropping.
                        onLog("⚠️ ADOPT SKIP (no price): ${ts.symbol} | qty=${"%.4f".format(qty)} — falling to orphan path")
                        return@forEach
                    }
                if (adoptPrice <= 0.0) return@forEach
                synchronized(ts) {
                    ts.position = com.lifecyclebot.data.Position(
                        entryPrice      = adoptPrice,
                        entryTime       = System.currentTimeMillis() - 60_000L,
                        qtyToken        = qty,
                        costSol         = 0.0,
                        entryScore      = 50.0,
                        entryPhase      = "adopted_from_wallet",
                        highestPrice    = adoptPrice,
                        isPaperPosition = false,  // V5.9.252 FIX: adopted tokens are LIVE — must execute real sells
                    )
                }
                adoptedMints += mint
                onLog("📥 ADOPTED: ${ts.symbol} | ${"%.4f".format(qty)} tokens @ ${adoptPrice} — bot will manage exits")
                onAlert(
                    "Position Adopted",
                    "${ts.symbol}: ${"%.2f".format(qty)} tokens found on-chain and now tracked. " +
                        "Bot will manage TP/SL from here."
                )
            }
            if (adoptedMints.isNotEmpty()) {
                onLog("📥 Adopted ${adoptedMints.size} on-chain position(s) into bot state")
            }
        } catch (e: Exception) {
            onLog("Reconcile: adopt scan failed — ${e.message}")
            warnings.add("Adopt scan failed: ${e.message}")
        }

        if (openPositions.isEmpty() && adoptedMints.isEmpty()) {
            onLog("Reconcile: no open positions to verify")
            return ReconciliationResult(onChainSol, botStateSol, mismatch,
                ghostCleared, verified, warnings)
        }

        onLog("Reconcile: verifying ${openPositions.size} open position(s)…")

        openPositions.forEach { ts ->
            try {
                // V5.9.122: CRITICAL FIX — skip paper positions entirely.
                // Paper positions have no on-chain existence, so
                // getTokenBalance() always returns 0, and the ghost-detection
                // branch below unconditionally wiped them on every startup.
                // This is THE bug that silently wiped hundreds of thousands
                // of paper dollars across app updates — paper positions
                // were classified as ghosts by a live-chain reconciler that
                // should never have been inspecting them.
                if (ts.position.isPaperPosition) {
                    onLog("Reconcile: 📝 ${ts.symbol} is a paper position — skipping on-chain ghost check")
                    verified.add(ts.mint)
                    return@forEach
                }

                val tokenBalance = getTokenBalance(ts.mint)

                if (tokenBalance <= 0.0) {
                    // Bot thinks we have a position but on-chain balance is 0
                    // Could be: rug pulled, manual sell, tx failed silently
                    val msg = "Ghost position detected: ${ts.symbol} — on-chain balance=0 " +
                              "but bot thinks we hold ${ts.position.qtyToken} tokens"
                    warnings.add(msg)
                    onLog("🧹 $msg — clearing position")
                    onAlert("Position Cleared", "${ts.symbol}: position cleared on startup " +
                            "(no tokens found on-chain)")

                    // Clear the ghost position
                    synchronized(ts) {
                        // V5.9.122: safety net — if a paper position ever slips
                        // past the guard above, refund the paper SOL before
                        // clearing so capital isn't lost silently.
                        if (ts.position.isPaperPosition && ts.position.costSol > 0.0) {
                            try {
                                com.lifecyclebot.engine.BotService.creditUnifiedPaperSol(
                                    ts.position.costSol,
                                    source = "ghost_reconcile_refund[${ts.symbol}]"
                                )
                                onLog("💰 Refunded ${ts.position.costSol} paper SOL from ghost ${ts.symbol}")
                            } catch (_: Exception) { /* non-fatal */ }
                        }
                        ts.position   = com.lifecyclebot.data.Position()
                        ts.lastExitTs = System.currentTimeMillis()
                    }
                    // V5.9.251 FIX: Stamp re-entry cooldown so the bot cannot
                    // immediately re-buy a token that was just manually sold
                    // externally. Without this, StartupReconciler clears the
                    // ghost but recentlyClosedMs has no entry → 5-min cooldown
                    // never fires → instant re-buy on next scan loop.
                    try {
                        BotService.recentlyClosedMs[ts.mint] = System.currentTimeMillis()
                        onLog("⏳ RE-ENTRY LOCK: ${ts.symbol} — 5-min cooldown started (external sell detected)")
                    } catch (_: Exception) { /* non-fatal */ }
                    ghostCleared.add(ts.mint)

                } else {
                    // Position confirmed on-chain
                    onLog("Reconcile: ✅ ${ts.symbol} position confirmed " +
                          "(on-chain: ${"%.2f".format(tokenBalance)} tokens)")
                    verified.add(ts.mint)
                }
            } catch (e: Exception) {
                onLog("Reconcile: could not verify ${ts.symbol} — ${e.message}")
                warnings.add("Could not verify ${ts.symbol}: ${e.message}")
            }
        }

        val summary = "Reconciliation complete: " +
            "${verified.size} verified, ${ghostCleared.size} ghost positions cleared, " +
            "${warnings.size} warnings"
        onLog(summary)

        if (ghostCleared.isNotEmpty()) {
            onAlert("Startup Check", "$summary — check logs for details")
        }

        // ── 3. Scan for orphaned tokens and auto-sell ────────────────
        if (autoSellOrphans && executor != null) {
            scanAndSellOrphanedTokens(warnings)
        }

        return ReconciliationResult(
            walletBalanceOnChain  = onChainSol,
            walletBalanceBotState = botStateSol,
            balanceMismatch       = mismatch,
            ghostPositionsCleared = ghostCleared,
            positionsVerified     = verified,
            warnings              = warnings,
        )
    }

    /**
     * Scan wallet for tokens that aren't tracked by the bot and auto-sell them.
     * These are "orphaned" tokens from crashed trades or failed sells.
     */
    private fun scanAndSellOrphanedTokens(warnings: MutableList<String>) {
        try {
            onLog("🔍 Scanning for orphaned tokens...")
            val tokenAccounts = wallet.getTokenAccounts()
            val trackedMints = status.openPositions.map { it.mint }.toSet()
            
            var orphansFound = 0
            var orphansSold = 0
            
            tokenAccounts.forEach { (mint, qty) ->
                // Skip if quantity is negligible (dust)
                if (qty < 1.0) return@forEach
                
                // Skip if this position is already tracked
                if (mint in trackedMints) return@forEach
                
                // Skip SOL and common wrapped tokens
                if (mint == "So11111111111111111111111111111111111111112") return@forEach

                // V5.9.110: PROTECT Markets-trader holdings from orphan auto-sell.
                // xStocks / PAXG / EURC mints belong to TokenizedStockTrader /
                // MetalsTrader / ForexTrader. Previously the reconciler was
                // liquidating these on every restart — user saw 1 SOL turn into
                // tokens they could see on-chain but with "0 Open" in the UI.
                if (mint in com.lifecyclebot.perps.TokenizedAssetRegistry.allMints()) {
                    val sym = status.tokens[mint]?.symbol ?: mint.take(8)
                    onLog("🛡️ RESPECTED: $sym ($qty tokens) — Markets-trader holding, not orphan")
                    return@forEach
                }

                // V5.9.110: also respect DynamicAltTokenRegistry real Solana mints
                // (CryptoAltTrader holdings like WOJAK, BLAST, etc.).
                try {
                    val altHolding = com.lifecyclebot.perps.DynamicAltTokenRegistry
                        .getAllTokens()
                        .any { it.mint == mint && !it.mint.startsWith("cg:") && !it.mint.startsWith("static:") }
                    if (altHolding) {
                        onLog("🛡️ RESPECTED alt token ${mint.take(8)}… ($qty) — CryptoAlt holding")
                        return@forEach
                    }
                } catch (_: Exception) {}
                
                // This is an orphaned token - not tracked but has balance
                orphansFound++
                val symbol = status.tokens[mint]?.symbol ?: mint.take(8)
                onLog("🧹 Orphaned token found: $symbol ($qty tokens)")
                
                try {
                    // Attempt to sell via Jupiter
                    val sellResult = executor?.sellOrphanedToken(mint, qty, wallet)
                    if (sellResult == true) {
                        orphansSold++
                        onLog("✅ Auto-sold orphaned token: $symbol")
                        onAlert("Orphan Cleanup", "Auto-sold $symbol from previous session")
                    } else {
                        onLog("⚠️ Could not auto-sell $symbol - sell manually via Jupiter")
                        warnings.add("Orphaned token: $symbol - sell manually")
                        onAlert("Orphan Found", "$symbol: $qty tokens found. Sell manually via Jupiter.")
                    }
                } catch (e: Exception) {
                    onLog("⚠️ Error selling $symbol: ${e.message}")
                    warnings.add("Orphaned token: $symbol - error: ${e.message}")
                }
            }
            
            if (orphansFound > 0) {
                onLog("🧹 Orphan scan complete: found $orphansFound, sold $orphansSold")
            } else {
                onLog("✅ No orphaned tokens found")
            }
        } catch (e: Exception) {
            onLog("⚠️ Orphan scan failed: ${e.message}")
            warnings.add("Orphan scan failed: ${e.message}")
        }
    }

    /**
     * Get SPL token balance for our wallet on a specific mint.
     * Returns 0.0 if account doesn't exist.
     */
    private fun getTokenBalance(mint: String): Double {
        return try {
            // Use getTokenAccountsByOwner RPC call
            val http = com.lifecyclebot.network.SharedHttpClient.builder()
                .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val payload = org.json.JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "getTokenAccountsByOwner")
                put("params", org.json.JSONArray().apply {
                    put(wallet.publicKeyB58)
                    put(org.json.JSONObject().apply {
                        put("mint", mint)
                    })
                    put(org.json.JSONObject().apply {
                        put("encoding", "jsonParsed")
                        put("commitment", "confirmed")
                    })
                })
            }

            val req  = okhttp3.Request.Builder()
                .url(wallet.rpcUrl)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .build()

            val resp = http.newCall(req).execute()
            val body = resp.body?.string() ?: return 0.0
            val json = org.json.JSONObject(body)
            val accounts = json.optJSONObject("result")
                ?.optJSONArray("value") ?: return 0.0

            // Sum all token accounts for this mint
            var total = 0.0
            for (i in 0 until accounts.length()) {
                val acct = accounts.optJSONObject(i) ?: continue
                val amount = acct.optJSONObject("account")
                    ?.optJSONObject("data")
                    ?.optJSONObject("parsed")
                    ?.optJSONObject("info")
                    ?.optJSONObject("tokenAmount")
                    ?.optDouble("uiAmount", 0.0) ?: 0.0
                total += amount
            }
            total
        } catch (_: Exception) { 0.0 }
    }
}

