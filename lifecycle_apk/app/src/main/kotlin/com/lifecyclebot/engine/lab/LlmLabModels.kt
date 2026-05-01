package com.lifecyclebot.engine.lab

import org.json.JSONArray
import org.json.JSONObject

/**
 * V5.9.402 — LLM Lab data models.
 *
 * The Lab is the LLM's mini-universe: an isolated paper-trading sandbox where
 * the LLM can invent strategies, traders, and exits. Real-money execution is
 * gated behind a user-approval queue (LabApproval).
 *
 * Everything is JSON-serialisable so we can persist via SharedPreferences and
 * survive process death without pulling in Room/SQL just for the lab.
 */

enum class LabAssetClass {
    MEME, ALT, MARKETS, STOCK, FOREX, METAL, COMMODITY, ANY;

    companion object {
        fun parse(s: String?): LabAssetClass = try {
            valueOf((s ?: "ANY").trim().uppercase())
        } catch (_: Throwable) { ANY }
    }
}

enum class LabStrategyStatus {
    DRAFT,        // freshly minted by LLM, not yet running
    ACTIVE,       // running paper trades
    PROMOTED,     // user has approved real-money trading for this strategy
    ARCHIVED,     // retired (loser, killed by LLM, or user)
}

/**
 * A single LLM-invented strategy.
 *
 * Specs are intentionally tiny scalars rather than a full DSL — keeps the LLM
 * prompt + parser simple and CI-safe. The evaluator (LlmLabTrader) interprets
 * these against live TokenState / market data.
 */
data class LabStrategy(
    val id: String,
    val name: String,
    val rationale: String,
    val asset: LabAssetClass,
    val entryScoreMin: Int,            // 0..100 (composite score gate)
    val entryRegime: String,           // "ANY" | "BULL" | "BEAR" | "CHOP"
    val takeProfitPct: Double,         // e.g. 12.0
    val stopLossPct: Double,           // e.g. -8.0  (negative)
    val maxHoldMins: Int,              // e.g. 120
    val sizingSol: Double,             // paper size per trade
    val parentId: String? = null,      // lineage if evolved from another strategy
    val generation: Int = 1,
    var status: LabStrategyStatus = LabStrategyStatus.DRAFT,
    var paperTrades: Int = 0,
    var paperWins: Int = 0,
    var paperPnlSol: Double = 0.0,
    var liveTrades: Int = 0,
    var liveWins: Int = 0,
    var livePnlSol: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis(),
    var lastTradeAt: Long = 0L,
    var lastEvaluatedAt: Long = 0L,
) {
    fun winRatePct(): Double =
        if (paperTrades > 0) paperWins * 100.0 / paperTrades else 0.0

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("rationale", rationale)
        put("asset", asset.name)
        put("entryScoreMin", entryScoreMin); put("entryRegime", entryRegime)
        put("takeProfitPct", takeProfitPct); put("stopLossPct", stopLossPct)
        put("maxHoldMins", maxHoldMins); put("sizingSol", sizingSol)
        put("parentId", parentId ?: ""); put("generation", generation)
        put("status", status.name)
        put("paperTrades", paperTrades); put("paperWins", paperWins); put("paperPnlSol", paperPnlSol)
        put("liveTrades", liveTrades); put("liveWins", liveWins); put("livePnlSol", livePnlSol)
        put("createdAt", createdAt); put("lastTradeAt", lastTradeAt); put("lastEvaluatedAt", lastEvaluatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): LabStrategy = LabStrategy(
            id = o.optString("id"),
            name = o.optString("name"),
            rationale = o.optString("rationale"),
            asset = LabAssetClass.parse(o.optString("asset")),
            entryScoreMin = o.optInt("entryScoreMin", 60),
            entryRegime = o.optString("entryRegime", "ANY"),
            takeProfitPct = o.optDouble("takeProfitPct", 10.0),
            stopLossPct = o.optDouble("stopLossPct", -8.0),
            maxHoldMins = o.optInt("maxHoldMins", 120),
            sizingSol = o.optDouble("sizingSol", 0.25),
            parentId = o.optString("parentId").ifBlank { null },
            generation = o.optInt("generation", 1),
            status = try { LabStrategyStatus.valueOf(o.optString("status", "DRAFT")) } catch (_: Throwable) { LabStrategyStatus.DRAFT },
            paperTrades = o.optInt("paperTrades"),
            paperWins = o.optInt("paperWins"),
            paperPnlSol = o.optDouble("paperPnlSol"),
            liveTrades = o.optInt("liveTrades"),
            liveWins = o.optInt("liveWins"),
            livePnlSol = o.optDouble("livePnlSol"),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            lastTradeAt = o.optLong("lastTradeAt"),
            lastEvaluatedAt = o.optLong("lastEvaluatedAt"),
        )
    }
}

/**
 * A live paper position owned by the lab. One per (strategy × symbol).
 */
data class LabPosition(
    val id: String,
    val strategyId: String,
    val symbol: String,
    val mint: String,
    val asset: LabAssetClass,
    val entryPrice: Double,
    val sizeSol: Double,
    val entryTime: Long,
    val isLive: Boolean = false,
    var lastSeenPrice: Double = 0.0,
    var peakPnlPct: Double = 0.0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("strategyId", strategyId)
        put("symbol", symbol); put("mint", mint); put("asset", asset.name)
        put("entryPrice", entryPrice); put("sizeSol", sizeSol); put("entryTime", entryTime)
        put("isLive", isLive); put("lastSeenPrice", lastSeenPrice); put("peakPnlPct", peakPnlPct)
    }

    companion object {
        fun fromJson(o: JSONObject): LabPosition = LabPosition(
            id = o.optString("id"),
            strategyId = o.optString("strategyId"),
            symbol = o.optString("symbol"),
            mint = o.optString("mint"),
            asset = LabAssetClass.parse(o.optString("asset")),
            entryPrice = o.optDouble("entryPrice"),
            sizeSol = o.optDouble("sizeSol"),
            entryTime = o.optLong("entryTime"),
            isLive = o.optBoolean("isLive"),
            lastSeenPrice = o.optDouble("lastSeenPrice"),
            peakPnlPct = o.optDouble("peakPnlPct"),
        )
    }
}

enum class LabApprovalStatus { PENDING, APPROVED, DENIED, EXPIRED }
enum class LabApprovalKind {
    PROMOTE_TO_LIVE,        // promote a strategy from paper → real money
    SINGLE_LIVE_TRADE,      // one-shot real-money trade
    TRANSFER_TO_MAIN_PAPER, // move SOL from lab paper bankroll to main paper wallet
}

/**
 * A user-approval request raised by the LLM.
 */
data class LabApproval(
    val id: String,
    val kind: LabApprovalKind,
    val strategyId: String?,
    val symbol: String?,
    val amountSol: Double,
    val reason: String,
    var status: LabApprovalStatus = LabApprovalStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    var decidedAt: Long = 0L,
    var decidedBy: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("kind", kind.name)
        put("strategyId", strategyId ?: ""); put("symbol", symbol ?: "")
        put("amountSol", amountSol); put("reason", reason)
        put("status", status.name); put("createdAt", createdAt); put("decidedAt", decidedAt)
        put("decidedBy", decidedBy)
    }

    companion object {
        fun fromJson(o: JSONObject): LabApproval = LabApproval(
            id = o.optString("id"),
            kind = try { LabApprovalKind.valueOf(o.optString("kind", "SINGLE_LIVE_TRADE")) } catch (_: Throwable) { LabApprovalKind.SINGLE_LIVE_TRADE },
            strategyId = o.optString("strategyId").ifBlank { null },
            symbol = o.optString("symbol").ifBlank { null },
            amountSol = o.optDouble("amountSol"),
            reason = o.optString("reason"),
            status = try { LabApprovalStatus.valueOf(o.optString("status", "PENDING")) } catch (_: Throwable) { LabApprovalStatus.PENDING },
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            decidedAt = o.optLong("decidedAt"),
            decidedBy = o.optString("decidedBy"),
        )
    }
}

internal fun JSONArray.toJsonObjectList(): List<JSONObject> {
    val out = ArrayList<JSONObject>(length())
    for (i in 0 until length()) out.add(getJSONObject(i))
    return out
}
