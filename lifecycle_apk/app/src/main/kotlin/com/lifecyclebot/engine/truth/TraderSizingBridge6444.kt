package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6444 — TRADER SIZING BRIDGE.
 *
 * OPERATOR MANDATE (V5.0.6443 next actions):
 *   "Trader Sizing Sites: Extend OrderSizeResolver6441 routing to every
 *    trader (BlueChip / Quality / Treasury / Moonshot / Shitcoin) so no
 *    lane sizes outside the canonical resolver."
 *
 * DESIGN
 * ──────
 * Each lane trader has its own sizing quirks (Treasury caps at 0.5 SOL,
 * Moonshot only 0.02, BlueChip escalates with wallet, etc). Rewriting
 * each trader is a per-lane refactor. This bridge is the CANONICAL
 * wrapper every trader can call:
 *
 *   resolveForLane(laneName, requestedSol, walletSol, paperMode)
 *      → resolved size that respects the lane's cap AND the canonical
 *        OrderSizeResolver6441 pipeline (risk → ladder → cash cap →
 *        lane cap → min executable).
 *
 * The bridge holds a per-lane risk cap map so every lane's ceiling is
 * declared in ONE place. Callers that want to override for a specific
 * strategy pass an explicit laneRiskCapSol.
 */
object TraderSizingBridge6444 {

    // V5.0.6630 §D — set of MEME specialist lane keys that must NOT
    // use this generic bridge. Alarm-only for now.
    private val SPECIALIST_LANE_KEYS_6630 = setOf(
        "SHITCOIN", "MOONSHOT", "CORE", "BLUECHIP", "EXPRESS",
        "PROJECT_SNIPER", "CYCLIC", "QUALITY", "DIP_HUNTER",
        "MANIPULATED", "TREASURY", "CASHGEN",
    )

    // V5.0.6552 — lane names are attribution, not eternal SOL ceilings.
    // Hard limits come from wallet-percent, liquidity, and portfolio risk at
    // resolution time; learned lane conviction may shape the proposal.
    private const val DEFAULT_WALLET_RISK_PCT_6552 = 0.12
    private const val DEFAULT_PORTFOLIO_CAP_SOL_6552 = 5.0

    private val invocations = AtomicLong(0L)
    private val perLaneInvocations = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Canonical entry point for lane traders. Every BUY sizing call from
     * a lane trader should go through this bridge.
     */
    fun resolveForLane(
        laneName: String,
        requestedSol: Double,
        walletSol: Double,
        paperMode: Boolean,
        overrideLaneRiskCapSol: Double? = null,
        mintForSeal: String = "",
        walletRiskPct: Double = DEFAULT_WALLET_RISK_PCT_6552,
        portfolioCapSol: Double = DEFAULT_PORTFOLIO_CAP_SOL_6552,
    ): OrderSizeResolver6441.Resolution {
        invocations.incrementAndGet()
        perLaneInvocations.computeIfAbsent(laneName) { AtomicLong(0L) }.incrementAndGet()
        val laneKey = laneName.uppercase()
        // V5.0.7226 §A_LIVE_RESOLVE_SIZED_AGAINST_THE_PAPER_BANKROLL.
        //
        // Operator 5.0.7225 (LIVE, wallet 0.0973 SOL): the resolver's own trace
        // read `lastAccount=LIVE lastWalletSol=12.0482 liveResolves=593` — the
        // paper ledger's cash, to four decimals, on a live resolve. 7217 wrote
        // that trace precisely so this would be seen. The feeders are five lane
        // traders (BlueChip 691 invocations, Moonshot, ShitCoin, Quality,
        // SolanaArb) that each hard-code
        //   walletSolProxy = PaperCapitalAuthority6577.cashSol()
        // and pass it here with paperMode = isPaperMode. In paper that is
        // correct. In live it hands OrderSizeResolver6441 a 12 SOL wallet
        // (authoritativeCash = walletSol when !paperMode), so the resolve
        // "approves" 0.47 SOL and the executor's own wallet clamp then has to
        // walk it down to whatever the real wallet allows — which on this wallet
        // was 0.007 SOL of dust.
        //
        // Fixed HERE, at the one bridge all five traders call, rather than in
        // five files: a live resolve is bound to the live wallet authority (the
        // same read FinalDecisionGate's seal uses since 6827). The caller's
        // value is kept only when the live wallet has not been read yet, and
        // that case is counted separately so it cannot hide. Paper is untouched.
        val walletSol7226 = if (paperMode) walletSol else {
            val cached7226: Double = try { com.lifecyclebot.engine.WalletManager.cachedSolBalance() } catch (_: Throwable) { 0.0 }
            val status7226: Double = try { com.lifecyclebot.engine.BotService.status.walletSol } catch (_: Throwable) { 0.0 }
            // V5.0.7789 — status.walletSol is LIVE_WALLET_AUTHORITY_6686;
            // WalletManager is fallback only because its pre-spend cache can lag.
            val live7226: Double = if (status7226.isFinite() && status7226 > 0.0) status7226 else cached7226
            if (live7226.isFinite() && live7226 > 0.0) {
                if (kotlin.math.abs(live7226 - walletSol) > 0.01 * kotlin.math.max(live7226, 1e-9)) {
                    try {
                        com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LIVE_SIZING_WALLET_PROXY_WAS_PAPER_CASH_7226")
                        com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LIVE_SIZING_WALLET_PROXY_WAS_PAPER_CASH_7226_$laneKey")
                        com.lifecyclebot.engine.ForensicLogger.lifecycle(
                            "LIVE_SIZING_WALLET_PROXY_WAS_PAPER_CASH_7226",
                            "lane=$laneKey callerWalletSol=${"%.4f".format(walletSol)} liveWalletSol=${"%.4f".format(live7226)} " +
                                "requestedSol=${"%.4f".format(requestedSol)} action=resolve_against_live_wallet",
                        )
                    } catch (_: Throwable) {}
                }
                live7226
            } else {
                try {
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LIVE_SIZING_WALLET_UNREAD_KEPT_CALLER_VALUE_7226")
                } catch (_: Throwable) {}
                walletSol
            }
        }
        // V5.0.7893 — Cortex conviction (plan v2 §D): on a lane whose STRONG record is
        // proven (n>=40, mean-SE>+2%), a candidate the Cortex reads STRONG is sized
        // toward quarter-Kelly of that record, never below the request, at most 2x;
        // the resolver's lane, wallet and liquidity caps below still bound it.
        // V5.0.7962 — times the decision cell's Kelly multiple (CellAllocator7962), under the same 2.5x cap.
        val requestedSol7893 = requestedSol * try {
            com.lifecyclebot.engine.cortex.FirstSight8026.sizeMult8026(mintForSeal, com.lifecyclebot.engine.RunnerPlay8018.sizeMult8018(mintForSeal, TailHunter7996.sizeMult7996(mintForSeal, com.lifecyclebot.engine.RunnerGrab7967.sizeMult7967(mintForSeal, SpecialistMiner7972.sizeMult7974(mintForSeal, com.lifecyclebot.engine.CellAllocator7962.combinedSizeMult7962(
                com.lifecyclebot.engine.cortex.Cortex7885.convictionMult(mintForSeal, laneKey, requestedSol, walletSol7226),
                mintForSeal, laneKey, paperMode, requestedSol,
            ))))))  // V5.0.8026 — a first-sight probe opens at the request; V5.0.8018 — a re-entry opens at the minimum; V5.0.7967 — a grabbed runner opens at 1.5x; V5.0.7974 — a specialist admit sized by its proven floor; V5.0.7996 — a tail ticket opens at the minimum
        } catch (_: Throwable) { 1.0 }
        // V5.0.7828 — specialists use CanonicalSizingBridge6532 as their primary route.
        // The old "generic misroute -> auto-reroute" wording/counters described a fixed
        // architecture as a fault on every call and hid real last-mile failures.
        try {
            if (laneKey in SPECIALIST_LANE_KEYS_6630) {
                val classForRoute7828 = AssetClass.fromLane(laneKey)
                val memoKey7868 = sizingMemoKey7868(laneKey, mintForSeal, paperMode, requestedSol7893, walletSol7226, overrideLaneRiskCapSol)
                memoHit7868(memoKey7868)?.let { hit ->
                    if (mintForSeal.isNotBlank() && hit.executable) {
                        try { SealedOrderSizeAuthority6497.sealFor(mintForSeal, hit, laneKey) } catch (_: Throwable) {}
                    }
                    return hit
                }
                val canonical7828 = CanonicalSizingBridge6532.resolve(
                    requestedSol = requestedSol7893,
                    assetClass = classForRoute7828,
                    laneName = laneKey,
                    walletSol = walletSol7226,
                    paperMode = paperMode,
                    laneRiskCapSol = overrideLaneRiskCapSol ?: DEFAULT_PORTFOLIO_CAP_SOL_6552,
                    laneMinExecutableSol = if (paperMode) OrderSizeResolver6441.paperExecutableMinimumSol() else 0.001,
                    canonicalAssetId = mintForSeal,
                    symbol = mintForSeal.ifBlank { laneKey },
                    source = "TraderSizingBridge6444.specialist_primary_7828",
                )
                memoPut7868(memoKey7868, canonical7828)
                PipelineHealthCollector.labelInc("SPECIALIST_CANONICAL_SIZING_ROUTE_7828")
                PipelineHealthCollector.labelInc("SPECIALIST_CANONICAL_SIZING_ROUTE_7828_" + laneKey)
                if (mintForSeal.isNotBlank() && canonical7828.executable) {
                    try { SealedOrderSizeAuthority6497.sealFor(mintForSeal, canonical7828, laneKey) } catch (_: Throwable) {}
                }
                return canonical7828
            }
        } catch (_: Throwable) {}
        val dynamicWalletCap = (walletSol7226.coerceAtLeast(0.0) * walletRiskPct.coerceIn(0.0, 1.0))
        val laneCap = overrideLaneRiskCapSol ?: dynamicWalletCap.coerceAtMost(portfolioCapSol)
        return try {
            val r = OrderSizeResolver6441.resolve(
                requestedSol = requestedSol7893,
                laneName = laneKey,
                walletSol = walletSol7226,
                paperMode = paperMode,
                laneRiskCapSol = laneCap,
                laneMinExecutableSol = if (paperMode) OrderSizeResolver6441.paperExecutableMinimumSol() else 0.001,
                // V5.0.6992 — this bridge's only caller is Executor.doBuy, whose
                // sizing stack already contains paperLiveBridgeMult. The resolver
                // now applies PaperLiveIntelligenceBridge for every OTHER trader
                // (they had none of it), so tell it not to apply it twice here.
                bridgeAlreadyApplied6992 = true,
                // V5.0.6911 — forward the mint so the resolver can read
                // EntryConvictionRegistry6909. See CanonicalSizingBridge6532
                // for the evidence: 469 conviction stamps, 0 hits. Blank is
                // still tolerated (sizeForLane forwards a blank mint by
                // design), and a blank simply means conviction is unknown,
                // which resolves to 1.0 and changes nothing.
                mint = mintForSeal,
            )
            // V5.0.6497 §1 — seal executable resolution for the mint so
            // downstream execution readers cannot re-compute a smaller
            // value. TraderSizingBridge6444.sizeForLane forwards blank
            // mint and does NOT seal (unchanged for backward compat).
            if (mintForSeal.isNotBlank() && r.executable) {
                try { SealedOrderSizeAuthority6497.sealFor(mintForSeal, r, laneKey) } catch (_: Throwable) {}
            }
            r
        } catch (t: Throwable) {
            try {
                ForensicLogger.lifecycle(
                    "TRADER_SIZING_BRIDGE_FAIL_6444",
                    "lane=$laneKey err=${t.message?.take(60)}",
                )
            } catch (_: Throwable) {}
            // V5.0.6485 — sizing exceptions cannot claim executable and
            // defer a contradictory rejection into the paper executor.
            OrderSizeResolver6441.Resolution(
                requestedSol = requestedSol, riskSol = 0.0, ladderSol = 0.0,
                cashCapSol = 0.0, laneCapSol = laneCap, finalSizeSol = 0.0,
                executable = false, reason = "BRIDGE_RESOLUTION_FAILED_6485",
            )
        }
    }

    /** Convenience — returns just the final SOL size. */
    fun sizeForLane(
        laneName: String,
        requestedSol: Double,
        walletSol: Double,
        paperMode: Boolean,
    ): Double = resolveForLane(laneName, requestedSol, walletSol, paperMode).finalSizeSol

    fun statusLine(): String {
        val n = invocations.get()
        val perLane = perLaneInvocations.entries
            .sortedByDescending { it.value.get() }
            .take(5)
            .joinToString(",") { "${it.key}=${it.value.get()}" }
        return "invocations=$n top5=[$perLane]"
    }

    /** Dynamic cap policy for pipeline health inspection. */
    fun declaredCaps(): String = "walletPct=$DEFAULT_WALLET_RISK_PCT_6552 portfolioCapSol=$DEFAULT_PORTFOLIO_CAP_SOL_6552"

    // V5.0.7868 — 5.0.7866 ran SPECIALIST_CANONICAL_SIZING_ROUTE_7828=11126 and
    // ORDER_SIZE_RESOLVED_6441=10168 in ~24 min for ~8 executable entries: every
    // advisory lane evaluation re-ran the full resolver for identical inputs in
    // the same cycle. Identical inputs inside 2 s return the same resolution.
    private const val SIZING_MEMO_TTL_MS_7868 = 2_000L
    private val sizingMemo7868 = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, OrderSizeResolver6441.Resolution>>()

    internal fun sizingMemoKey7868(lane: String, mint: String, paper: Boolean, requestedSol: Double, walletSol: Double, cap: Double?): String =
        "$lane|$mint|$paper|${"%.6f".format(java.util.Locale.ROOT, requestedSol)}|${"%.6f".format(java.util.Locale.ROOT, walletSol)}|${cap?.let { "%.6f".format(java.util.Locale.ROOT, it) } ?: "-"}"

    private fun memoHit7868(key: String, nowMs: Long = System.currentTimeMillis()): OrderSizeResolver6441.Resolution? {
        val e = sizingMemo7868[key] ?: return null
        if (nowMs - e.first > SIZING_MEMO_TTL_MS_7868) return null
        try { PipelineHealthCollector.labelInc("SPECIALIST_SIZING_MEMO_HIT_7868") } catch (_: Throwable) {}
        return e.second
    }

    private fun memoPut7868(key: String, r: OrderSizeResolver6441.Resolution, nowMs: Long = System.currentTimeMillis()) {
        sizingMemo7868[key] = nowMs to r
        if (sizingMemo7868.size > 1_000) sizingMemo7868.entries.removeIf { nowMs - it.value.first > SIZING_MEMO_TTL_MS_7868 }
    }
}
