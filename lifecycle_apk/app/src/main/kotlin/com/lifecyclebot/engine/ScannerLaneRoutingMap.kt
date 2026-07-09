package com.lifecyclebot.engine

/**
 * V5.0.6228 — SCANNER → LANE DESIGN-TIME ROUTING MAP
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Operator directive: "all scanner lanes need matching lanes to on match to
 * the designated trading lanes, layers or tools they are designed to feed."
 *
 * The empirical layer (`ScannerLaneBridge`) learns per-(source, lane) win
 * rate from live outcomes, but starts with ZERO signal — every new source
 * begins as an undifferentiated fanout to every enabled lane. That's how
 * the bot ends up evaluating `PUMP_FUN_NEW` in `BLUECHIP` and hollowing out
 * the lane-eval funnel with wasted work.
 *
 * This map encodes the DESIGN INTENT: which lanes each scanner source was
 * built to feed. It seeds the affinity bridge with a small positive prior
 * for designed pairs so intake fans out to the right lanes on turn one,
 * without waiting for n≥8 empirical samples per (source, lane) tuple.
 *
 * FAIL-OPEN: sources not in the map still route to every enabled lane —
 * the empirical bridge will discover their best affinity naturally. This
 * map is a design-time PRIOR, never a hard block.
 *
 * Doctrine:
 *   • Fresh-liquidity meme sources (PUMP_FUN, PUMP_PORTAL, RAYDIUM_NEW_POOL)
 *     → SHITCOIN, MOONSHOT, EXPRESS, MANIPULATED (memes only)
 *   • Curated / blue-chip sources (SOLANA_BLUECHIP_WATCHLIST, CG_TRENDING)
 *     → BLUECHIP, TREASURY, QUALITY, CASHGEN, PROJECT_SNIPER
 *   • DEX curation / boosted (DEX_BOOSTED, DEX_TRENDING)
 *     → QUALITY, EXPRESS, CASHGEN, DIP_HUNTER
 *   • Restored / cyclic ring inputs (MEME_REGISTRY_RESTORE, RESTORED)
 *     → CYCLIC, plus whatever lane the token originally traded in
 *   • Post-graduation / dex-listed (PUMP_FUN_GRADUATE, MIGRATION)
 *     → MOONSHOT, PROJECT_SNIPER, CYCLIC, QUALITY
 *   • Meteora / liquidity-quality pool sources
 *     → QUALITY, CASHGEN, TREASURY
 */
object ScannerLaneRoutingMap {

    private val LANE_MEME_HOT = setOf("SHITCOIN", "MOONSHOT", "EXPRESS", "MANIPULATED")
    private val LANE_QUALITY_CURATED = setOf("BLUECHIP", "TREASURY", "QUALITY", "CASHGEN")
    private val LANE_DEX_CURATED = setOf("QUALITY", "EXPRESS", "CASHGEN", "DIP_HUNTER")
    private val LANE_POST_GRAD = setOf("MOONSHOT", "PROJECT_SNIPER", "CYCLIC", "QUALITY")
    private val LANE_LIQUIDITY_POOL = setOf("QUALITY", "CASHGEN", "TREASURY")
    private val LANE_CYCLIC_RING = setOf("CYCLIC")

    /**
     * Fixed source → design-time lane set. Keys are UPPERCASED substrings that
     * are matched via `contains()` against the incoming source token, so a
     * source like `PUMP_FUN_NEW,SCANNER_DIRECT` still resolves to the meme
     * hot-set. Order is stable, longest keys first, first match wins.
     */
    private val DESIGN_MAP: List<Pair<String, Set<String>>> = listOf(
        // Post-graduation / DEX migration → MOONSHOT + PROJECT_SNIPER + CYCLIC
        "PUMP_FUN_GRADUATE"           to (LANE_POST_GRAD + LANE_MEME_HOT),
        "GRADUATE"                    to (LANE_POST_GRAD + LANE_MEME_HOT),
        "MIGRATION"                   to LANE_POST_GRAD,

        // Fresh-liquidity meme sources → meme hot-lanes only
        "PUMP_FUN_NEW"                to LANE_MEME_HOT,
        "PUMP_PORTAL_WS"              to LANE_MEME_HOT,
        "PUMP_PORTAL"                 to LANE_MEME_HOT,
        "RAYDIUM_NEW_POOL"            to (LANE_MEME_HOT + "PROJECT_SNIPER"),
        "SCANNER_DIRECT_RAYDIUM_NEW"  to (LANE_MEME_HOT + "PROJECT_SNIPER"),

        // Curated blue-chip / macro sources → quality/treasury lanes + CYCLIC ring
        "SOLANA_BLUECHIP_WATCHLIST"   to (LANE_QUALITY_CURATED + LANE_CYCLIC_RING),
        "SCANNER_DIRECT_SOLANA_BLUE"  to (LANE_QUALITY_CURATED + LANE_CYCLIC_RING),
        "COINGECKO_TRENDING"          to (LANE_QUALITY_CURATED + LANE_MEME_HOT + LANE_CYCLIC_RING),
        "COINGECKO_ONCHAIN"           to (LANE_QUALITY_CURATED + LANE_DEX_CURATED),

        // DEX curation / boosted lists → quality/express bucket + CYCLIC compound ring
        "DEX_BOOSTED"                 to (LANE_DEX_CURATED + LANE_CYCLIC_RING),
        "DEX_TRENDING"                to (LANE_DEX_CURATED + LANE_MEME_HOT),
        "DEXSCREENER_WS"              to LANE_DEX_CURATED,

        // Meteora / liquidity-pool sources → quality lanes
        "METEORA"                     to LANE_LIQUIDITY_POOL,
        "GECKOTERMINAL"               to LANE_LIQUIDITY_POOL,

        // Restored / cyclic-ring inputs → CYCLIC always eligible
        "MEME_REGISTRY_RESTORE"       to (LANE_CYCLIC_RING + LANE_MEME_HOT + LANE_QUALITY_CURATED),
        "RESTORED"                    to (LANE_CYCLIC_RING + LANE_MEME_HOT + LANE_QUALITY_CURATED),
        "PROBATION"                   to (LANE_CYCLIC_RING + LANE_MEME_HOT),
    ).sortedByDescending { it.first.length }

    /** UNIVERSAL_LANES = safety-net set that always accepts an intake even
     *  when the source has no design mapping. Kept small so the routing
     *  contract stays meaningful for known sources. */
    private val UNIVERSAL_LANES = setOf("STANDARD", "V3_CORE", "SHADOW_PAPER")

    /**
     * Return the design-time lane set for [source], or null when the source
     * has no mapping. Callers should treat null as "fail-open: any enabled
     * lane may take this token" so unknown/new scanner sources continue to
     * flow through the pipeline instead of being blackholed by the map.
     */
    fun designLanesFor(source: String?): Set<String>? {
        val s = (source ?: "").uppercase()
        if (s.isBlank()) return null
        for ((key, set) in DESIGN_MAP) {
            if (s.contains(key)) return set
        }
        return null
    }

    /**
     * True iff [lane] is an intended downstream for [source] under the
     * design map, OR the source has no mapping (fail-open), OR [lane] is
     * a universal always-accept lane (V3_CORE, STANDARD, SHADOW_PAPER).
     */
    fun isDesignMatch(source: String?, lane: String): Boolean {
        val laneU = lane.uppercase()
        if (laneU in UNIVERSAL_LANES) return true
        val set = designLanesFor(source) ?: return true   // unknown source → allow
        return laneU in set
    }

    /**
     * Design-time prior bias in the same scale as ScannerLaneBridge.affinityBias
     * ([-15..+15]). Returns +3 for a design-time match and -3 for a design-time
     * mismatch (when the source IS mapped but lane isn't in the mapped set).
     * Returns 0 when the source has no mapping (empirical bridge takes over).
     *
     * Kept intentionally small (±3) so the operator's empirical outcome
     * signal can dominate once enough samples accumulate. Zero blast radius:
     * a design-mismatched source that empirically prints on a lane will still
     * end up with a strong positive net bias because the ScannerLaneBridge
     * empirical curve returns ±6..±15 once mature.
     */
    fun designPriorBias(source: String?, lane: String): Int {
        val laneU = lane.uppercase()
        if (laneU in UNIVERSAL_LANES) return 0
        val set = designLanesFor(source) ?: return 0
        return if (laneU in set) +3 else -3
    }

    /**
     * Diagnostic string for the pipeline health report. Shows the design
     * map coverage so the operator can see at a glance which sources have
     * been formally mapped and which are still on fail-open.
     */
    fun formatForPipelineDump(): String {
        val sb = StringBuilder("ScannerLaneRoutingMap (V5.0.6228): ${DESIGN_MAP.size} mapped sources\n")
        for ((key, set) in DESIGN_MAP) {
            sb.append("  ").append(key).append(" → ").append(set.sorted().joinToString(",")).append('\n')
        }
        sb.append("  * unknown sources → all enabled lanes (fail-open)\n")
        sb.append("  * ").append(UNIVERSAL_LANES.joinToString(",")).append(" always accept any source\n")
        return sb.toString().trimEnd()
    }
}
