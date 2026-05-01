package com.lifecyclebot.engine.lab

import android.content.Context
import com.lifecyclebot.engine.ErrorLogger
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.402 — LLM Lab persistence + in-memory state.
 *
 * Lives in its own SharedPreferences file ("aate_llm_lab") to stay isolated
 * from the main bot config / learning state. The Lab is the LLM's playground:
 * its strategies, paper positions, and approval queue all live here.
 */
object LlmLabStore {
    private const val TAG = "LlmLabStore"
    private const val PREFS = "aate_llm_lab"

    // Keys
    private const val K_PAPER_BALANCE       = "lab_paper_balance"
    private const val K_LIVE_BALANCE        = "lab_live_balance"
    private const val K_STRATEGIES          = "lab_strategies"
    private const val K_POSITIONS           = "lab_positions"
    private const val K_APPROVALS           = "lab_approvals"
    private const val K_ENABLED             = "lab_enabled"
    private const val K_LAST_CREATION_MS    = "lab_last_creation_ms"

    // Defaults
    const val DEFAULT_PAPER_BALANCE_SOL = 100.0
    // V5.9.408 — promotion threshold lowered 100 → 60. With the lab now
    // trading much faster (10s eval + 36 strategies + 24 open positions),
    // 60 trades is enough proof-of-concept to graduate while still being
    // meaningfully better than guessing.
    const val MIN_TRADES_BEFORE_PROMOTION = 60
    const val MIN_WR_FOR_PROMOTION_PCT = 55.0    // 60 → 55 to match faster cadence
    const val ARCHIVE_LOSER_AFTER_TRADES = 30
    const val ARCHIVE_LOSER_BELOW_WR_PCT = 30.0

    // ── In-memory caches ────────────────────────────────────────────────────
    private val strategies = ConcurrentHashMap<String, LabStrategy>()
    private val positions  = ConcurrentHashMap<String, LabPosition>()
    private val approvals  = ConcurrentHashMap<String, LabApproval>()

    @Volatile private var paperBalance: Double = DEFAULT_PAPER_BALANCE_SOL
    @Volatile private var liveBalance:  Double = 0.0
    @Volatile private var enabled: Boolean = true            // ON by default (paper)
    @Volatile private var lastCreationMs: Long = 0L

    @Volatile private var ctxRef: Context? = null

    // ────────────────────────────────────────────────────────────────────────
    // Init / persistence
    // ────────────────────────────────────────────────────────────────────────
    @Synchronized
    fun init(context: Context) {
        if (ctxRef != null) return
        ctxRef = context.applicationContext
        try {
            val prefs = ctxRef!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            paperBalance   = prefs.getFloat(K_PAPER_BALANCE, DEFAULT_PAPER_BALANCE_SOL.toFloat()).toDouble()
            liveBalance    = prefs.getFloat(K_LIVE_BALANCE, 0f).toDouble()
            enabled        = prefs.getBoolean(K_ENABLED, true)
            lastCreationMs = prefs.getLong(K_LAST_CREATION_MS, 0L)

            strategies.clear()
            JSONArray(prefs.getString(K_STRATEGIES, "[]") ?: "[]").toJsonObjectList()
                .forEach { o -> LabStrategy.fromJson(o).let { strategies[it.id] = it } }

            positions.clear()
            JSONArray(prefs.getString(K_POSITIONS, "[]") ?: "[]").toJsonObjectList()
                .forEach { o -> LabPosition.fromJson(o).let { positions[it.id] = it } }

            approvals.clear()
            JSONArray(prefs.getString(K_APPROVALS, "[]") ?: "[]").toJsonObjectList()
                .forEach { o -> LabApproval.fromJson(o).let { approvals[it.id] = it } }

            ErrorLogger.info(TAG, "🧪 Lab loaded — strategies=${strategies.size} positions=${positions.size} approvals=${approvals.size} paperBalance=${"%.4f".format(paperBalance)}◎")
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "Lab load failed: ${e.message}")
        }
    }

    @Synchronized
    fun save() {
        val ctx = ctxRef ?: return
        try {
            val edit = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            edit.putFloat(K_PAPER_BALANCE, paperBalance.toFloat())
            edit.putFloat(K_LIVE_BALANCE, liveBalance.toFloat())
            edit.putBoolean(K_ENABLED, enabled)
            edit.putLong(K_LAST_CREATION_MS, lastCreationMs)
            edit.putString(K_STRATEGIES, JSONArray().apply { strategies.values.forEach { put(it.toJson()) } }.toString())
            edit.putString(K_POSITIONS,  JSONArray().apply { positions.values.forEach  { put(it.toJson()) } }.toString())
            edit.putString(K_APPROVALS,  JSONArray().apply { approvals.values.forEach  { put(it.toJson()) } }.toString())
            edit.apply()
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "Lab save failed: ${e.message}")
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Toggles + balances
    // ────────────────────────────────────────────────────────────────────────
    fun isEnabled(): Boolean = enabled
    fun setEnabled(on: Boolean) { enabled = on; save() }

    fun getPaperBalance(): Double = paperBalance
    fun adjustPaperBalance(delta: Double) {
        paperBalance = (paperBalance + delta).coerceAtLeast(0.0)
        save()
    }
    fun getLiveBalance(): Double = liveBalance
    fun adjustLiveBalance(delta: Double) {
        liveBalance = (liveBalance + delta).coerceAtLeast(0.0)
        save()
    }

    fun getLastCreationMs(): Long = lastCreationMs
    fun markCreated() { lastCreationMs = System.currentTimeMillis(); save() }

    // ────────────────────────────────────────────────────────────────────────
    // Strategy CRUD
    // ────────────────────────────────────────────────────────────────────────
    fun allStrategies(): List<LabStrategy> = strategies.values.toList()
    fun activeStrategies(): List<LabStrategy> =
        strategies.values.filter { it.status == LabStrategyStatus.ACTIVE || it.status == LabStrategyStatus.PROMOTED }
    fun getStrategy(id: String): LabStrategy? = strategies[id]

    fun addStrategy(s: LabStrategy) {
        strategies[s.id] = s
        ErrorLogger.info(TAG, "🧪 New lab strategy: ${s.name} (${s.asset}, gen=${s.generation})")
        save()
    }

    fun updateStrategy(s: LabStrategy) {
        strategies[s.id] = s
        save()
    }

    fun archiveStrategy(id: String, why: String) {
        val s = strategies[id] ?: return
        if (s.status == LabStrategyStatus.ARCHIVED) return
        strategies[id] = s.copy(status = LabStrategyStatus.ARCHIVED)
        ErrorLogger.info(TAG, "🧪 ARCHIVE strategy ${s.name}: $why")
        save()
    }

    fun removeStrategy(id: String) {
        if (strategies.remove(id) != null) save()
    }

    fun newStrategyId(): String = "lab_" + UUID.randomUUID().toString().take(8)

    // ────────────────────────────────────────────────────────────────────────
    // Position CRUD
    // ────────────────────────────────────────────────────────────────────────
    fun allPositions(): List<LabPosition> = positions.values.toList()
    fun positionsForStrategy(strategyId: String): List<LabPosition> =
        positions.values.filter { it.strategyId == strategyId }
    fun openPosition(strategyId: String, mint: String): LabPosition? =
        positions.values.firstOrNull { it.strategyId == strategyId && it.mint == mint }

    fun addPosition(p: LabPosition) { positions[p.id] = p; save() }
    fun updatePosition(p: LabPosition) { positions[p.id] = p; save() }
    fun removePosition(id: String) { positions.remove(id); save() }
    fun newPositionId(): String = "labp_" + UUID.randomUUID().toString().take(8)

    // ────────────────────────────────────────────────────────────────────────
    // Approval CRUD
    // ────────────────────────────────────────────────────────────────────────
    fun allApprovals(): List<LabApproval> = approvals.values.toList()
    fun pendingApprovals(): List<LabApproval> =
        approvals.values.filter { it.status == LabApprovalStatus.PENDING }
            .sortedByDescending { it.createdAt }

    fun addApproval(a: LabApproval) {
        approvals[a.id] = a
        ErrorLogger.info(TAG, "🧪 NEW APPROVAL REQUEST: ${a.kind} ${a.amountSol}◎ — ${a.reason.take(80)}")
        save()
    }

    fun decideApproval(id: String, status: LabApprovalStatus, by: String = "user") {
        val a = approvals[id] ?: return
        approvals[id] = a.copy(status = status, decidedAt = System.currentTimeMillis(), decidedBy = by)
        save()
    }
    fun newApprovalId(): String = "laba_" + UUID.randomUUID().toString().take(8)

    // ────────────────────────────────────────────────────────────────────────
    // Convenience
    // ────────────────────────────────────────────────────────────────────────
    fun summary(): String {
        val act = strategies.values.count { it.status == LabStrategyStatus.ACTIVE }
        val prom = strategies.values.count { it.status == LabStrategyStatus.PROMOTED }
        val pend = approvals.values.count { it.status == LabApprovalStatus.PENDING }
        val totalPaperPnl = strategies.values.sumOf { it.paperPnlSol }
        return "🧪 Lab: $act active · $prom live · ${pend} pending · " +
               "${"%+.3f".format(totalPaperPnl)}◎ paper · ${"%.2f".format(paperBalance)}◎ bank"
    }
}
