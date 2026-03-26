package com.lifecyclebot.engine

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

        if (openPositions.isEmpty()) {
            // Even with no tracked positions, scan on-chain token accounts
            // in case the bot crashed mid-buy and missed recording the position
            try {
                val tokenAccounts = wallet.getTokenAccounts()
                tokenAccounts.forEach { (mint, qty) ->
                    val ts = status.tokens[mint]
                    if (ts != null && !ts.position.isOpen && qty > 0) {
                        onLog("Reconcile: untracked ${ts.symbol} — crash recovery")
                        val crashPrice = ts.history.lastOrNull()?.priceUsd
                            ?: ts.lastPrice.takeIf { it > 0 }
                        if (crashPrice != null && crashPrice > 0 && !ts.position.isOpen) {
                            ts.position = com.lifecyclebot.data.Position(
                                entryPrice = crashPrice,
                                entryTime  = System.currentTimeMillis() - 60_000L,
                                qtyToken   = qty,
                                costSol    = 0.0,
                                entryScore = 50.0,
                                entryPhase = "crash_recovery",
                            )
                            onLog("Reconcile: reconstructed ${ts.symbol} @ $crashPrice")
                        }
                        warnings.add("Recovered untracked position: ${ts.symbol}")
                        onAlert("Position Recovered",
                            "${ts.symbol}: crash-recovery position. Exit manually if needed.")
                    }
                }
            } catch (_: Exception) {}
            onLog("Reconcile: no open positions to verify")
            return ReconciliationResult(onChainSol, botStateSol, mismatch,
                ghostCleared, verified, warnings)
        }

        onLog("Reconcile: verifying ${openPositions.size} open position(s)…")

        openPositions.forEach { ts ->
            try {
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
                        ts.position   = com.lifecyclebot.data.Position()
                        ts.lastExitTs = System.currentTimeMillis()
                    }
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
                
                // This is an orphaned token - not tracked but has balance
                orphansFound++
                val symbol = status.tokens[mint]?.symbol ?: mint.take(8)
                onLog("🧹 Orphaned token found: $symbol ($qty tokens)")
                
                try {
                    // Attempt to sell via Jupiter
                    val sellResult = executor?.sellOrphanedToken(mint, qty)
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
            val http = okhttp3.OkHttpClient.Builder()
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
