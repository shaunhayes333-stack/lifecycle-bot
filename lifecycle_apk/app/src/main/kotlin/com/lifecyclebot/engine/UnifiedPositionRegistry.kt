package com.lifecyclebot.engine

/**
 * V5.9.923 — UNIFIED POSITION REGISTRY
 *
 * Central read-only view of every open position across every lane store.
 *
 * The problem this solves (operator V5.9.922 screenshots):
 *   The bot held two open positions both labelled "Luisa" — one on the
 *   meme card (entry 02:11 @ $0.00000264), one in the Treasury Scalps
 *   card (entry 02:12 @ $0.00000236). Same symbol, different mints,
 *   correlated dump risk. Same pattern with FFD, Tiko, etc.
 *
 *   SYMBOL_FAMILY_DEDUP at BotService.kt:10398 scans only
 *   `status.tokens.values` for `ts.position.isOpen`. That sees positions
 *   held on the ts.position object — but NOT positions held in lane-local
 *   stores (CashGenerationAI.activePositions, MoonshotTraderAI.positions,
 *   etc). So any lane that keeps its own position map was invisible to
 *   the cross-lane dedup, allowing same-family overlap and double-
 *   allocation of capital on correlated meme rugs.
 *
 * What this does:
 *   Single read API that flattens every lane's position store into a list
 *   of (mint, symbol, lane) triples. Dedup guards call into here so that
 *   adding a new lane only requires updating ONE file, not every guard.
 *
 * Doctrine fit:
 *   - Memory rule #20: scanner pool is protected — we are NOT pruning
 *     intake, only blocking REDUNDANT BUY ENTRIES on tokens we already
 *     hold via another lane.
 *   - Memory rule #86: this is a soft-shape veto for entries only —
 *     existing positions are never closed by this layer.
 */
internal object UnifiedPositionRegistry {

    data class OpenSnapshot(
        val mint: String,
        val symbol: String,
        val lane: String,
    )

    fun snapshotAllOpen(): List<OpenSnapshot> {
        val out = ArrayList<OpenSnapshot>(64)

        // 1. Core ts.position positions (Meme / direct lanes on TokenState).
        try {
            val tokens = BotService.status.tokens
            synchronized(tokens) {
                for (ts in tokens.values) {
                    if (ts.position.isOpen && ts.mint.isNotBlank()) {
                        // Use only flags actually present on Position (Models.kt).
                        // tradingMode covers the rest (Moonshot/Manip/Markets etc).
                        val lane = when {
                            ts.position.isTreasuryPosition -> "TS_TREASURY"
                            ts.position.isBlueChipPosition -> "TS_BLUECHIP"
                            ts.position.isShitCoinPosition -> "TS_SHITCOIN"
                            else -> "TS_" + (ts.position.tradingMode.ifBlank { "MEME" })
                        }
                        out.add(OpenSnapshot(ts.mint, ts.symbol, lane))
                    }
                }
            }
        } catch (_: Throwable) {}

        // 2. CashGenerationAI / Treasury private store.
        try {
            for (p in com.lifecyclebot.v3.scoring.CashGenerationAI.getActivePositions()) {
                if (p.mint.isNotBlank()) out.add(OpenSnapshot(p.mint, p.symbol, "TREASURY"))
            }
        } catch (_: Throwable) {}

        // 3. BlueChipTraderAI private store.
        try {
            for (p in com.lifecyclebot.v3.scoring.BlueChipTraderAI.getActivePositions()) {
                if (p.mint.isNotBlank()) out.add(OpenSnapshot(p.mint, p.symbol, "BLUECHIP"))
            }
        } catch (_: Throwable) {}

        // 4. MoonshotTraderAI private store.
        try {
            for (p in com.lifecyclebot.v3.scoring.MoonshotTraderAI.getActivePositions()) {
                if (p.mint.isNotBlank()) out.add(OpenSnapshot(p.mint, p.symbol, "MOONSHOT"))
            }
        } catch (_: Throwable) {}

        // 5. ShitCoinTraderAI private store.
        try {
            for (p in com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getActivePositions()) {
                if (p.mint.isNotBlank()) out.add(OpenSnapshot(p.mint, p.symbol, "SHITCOIN"))
            }
        } catch (_: Throwable) {}

        // 6. QualityTraderAI private store.
        try {
            for (p in com.lifecyclebot.v3.scoring.QualityTraderAI.getActivePositions()) {
                if (p.mint.isNotBlank()) out.add(OpenSnapshot(p.mint, p.symbol, "QUALITY"))
            }
        } catch (_: Throwable) {}

        // 7. ManipulatedTraderAI private store.
        try {
            for (p in com.lifecyclebot.v3.scoring.ManipulatedTraderAI.getActivePositions()) {
                if (p.mint.isNotBlank()) out.add(OpenSnapshot(p.mint, p.symbol, "MANIPULATED"))
            }
        } catch (_: Throwable) {}

        // 8. ProjectSniperAI missions.
        try {
            for (m in com.lifecyclebot.v3.scoring.ProjectSniperAI.getActiveMissions()) {
                if (m.mint.isNotBlank()) out.add(OpenSnapshot(m.mint, m.symbol, "PROJECT_SNIPER"))
            }
        } catch (_: Throwable) {}

        return out
    }

    /**
     * True if any lane holds an open position on this exact mint.
     */
    fun isMintHeldAnywhere(mint: String, excludeLane: String? = null): Boolean {
        if (mint.isBlank()) return false
        return snapshotAllOpen().any { it.mint == mint && it.lane != excludeLane }
    }

    /**
     * True if any lane holds an open position whose symbol shares the
     * given normalised root (first 4 alphanumeric chars, uppercase).
     */
    fun isFamilyHeldAnywhere(symbol: String, excludeMint: String): Boolean {
        if (symbol.isBlank()) return false
        val root = symbol.uppercase().replace(Regex("[^A-Z0-9]"), "").take(4)
        if (root.length < 3) return false
        return snapshotAllOpen().any { snap ->
            if (snap.mint == excludeMint) return@any false
            val otherRoot = snap.symbol.uppercase().replace(Regex("[^A-Z0-9]"), "").take(4)
            otherRoot.length >= 3 && otherRoot == root
        }
    }
}
