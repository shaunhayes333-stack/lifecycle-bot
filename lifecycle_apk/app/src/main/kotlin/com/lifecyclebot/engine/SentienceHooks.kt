package com.lifecyclebot.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 *  V5.9.401 — SENTIENCE HOOKS (LLM-driven cross-engine intervention orchestrator)
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 *  Surfaces 9 LLM-driven trade-decision injection points across the AATE
 *  universe (Meme + Markets + Alts). Each hook is FAIL-SAFE: if the LLM
 *  isn't configured, rate-limited, or throws, the hook returns the safe
 *  default (allow / no-op / 1.0×) — trading never blocks on LLM availability.
 *
 *  All LLM calls go through GeminiCopilot.rawText(...) — same plumbing the
 *  sentient brain uses, so we share rate limits, providers, and degradation
 *  state.
 *
 *  Async refreshes are fire-and-forget; results are cached per-symbol for 60s
 *  so hot trade paths never block on a 200-500ms LLM round-trip. Stale or
 *  missing cache → safe default.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object SentienceHooks {

    private const val TAG = "SentienceHooks"

    // ─── Caches ──────────────────────────────────────────────────────────────
    private data class VetoResult(val allow: Boolean, val ts: Long)
    private data class ExitResult(val exit: Boolean, val ts: Long)
    private data class SizeResult(val mult: Double, val ts: Long)

    private val vetoCache = ConcurrentHashMap<String, VetoResult>()
    private val exitCache = ConcurrentHashMap<String, ExitResult>()
    private val sizeCache = ConcurrentHashMap<String, SizeResult>()
    private const val CACHE_TTL_MS = 60_000L

    private const val SYS_TRADE = "You are a concise trading risk officer. Reply in <= 40 chars."

    // ─── Symbiosis state ─────────────────────────────────────────────────────
    private data class EngineRunningPnL(
        var trades: Int = 0,
        var wins: Int = 0,
        var pnlSol: Double = 0.0,
        var lastUpdateMs: Long = System.currentTimeMillis(),
    )
    private val engineState = ConcurrentHashMap<String, EngineRunningPnL>()
    private val crossEngineBias = ConcurrentHashMap<String, Double>().apply {
        put("MEME", 1.0); put("MARKETS", 1.0); put("ALTS", 1.0)
    }

    // ─── Post-mortem accumulator ─────────────────────────────────────────────
    private val recentLosers = java.util.Collections.synchronizedList(
        ArrayList<LoserSnapshot>(64)
    )
    private const val POSTMORTEM_BATCH = 10

    data class LoserSnapshot(
        val engine: String,
        val symbol: String,
        val pnlPct: Double,
        val holdMinutes: Long,
        val reasons: List<String>,
        val regime: String,
        val ts: Long = System.currentTimeMillis(),
    )

    // Soft-rule output of the post-mortem (BehaviorAI / Executor can read this)
    @Volatile var lastPostMortemHint: String = ""
        private set
    @Volatile private var lastPostMortemMs: Long = 0L
    fun lastPostMortemAgeMs(): Long =
        if (lastPostMortemMs == 0L) Long.MAX_VALUE else System.currentTimeMillis() - lastPostMortemMs

    // ─── Auto-tune state ─────────────────────────────────────────────────────
    private val lastAutoTuneMs = AtomicLong(0)
    private const val AUTOTUNE_INTERVAL_MS = 6L * 60L * 60L * 1000L   // 6h

    // ─── Distrust pause state ────────────────────────────────────────────────
    @Volatile private var pausedStrategies: Set<String> = emptySet()
    private val lastDistrustNominateMs = AtomicLong(0)
    private const val DISTRUST_INTERVAL_MS = 30L * 60L * 1000L   // 30min

    private fun llmReady(): Boolean =
        try { GeminiCopilot.isConfigured() && !GeminiCopilot.isAIDegraded() } catch (_: Throwable) { false }

    private fun ask(prompt: String, system: String = SYS_TRADE, maxTokens: Int = 96): String? =
        try { GeminiCopilot.rawText(prompt, system, temperature = 0.4, maxTokens = maxTokens) } catch (_: Throwable) { null }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. PRE-TRADE VETO
    // ─────────────────────────────────────────────────────────────────────────
    /**
     * Returns true=allow, false=veto. Cached 60s per symbol; defaults to
     * ALLOW on cache miss (fire-and-forget background refresh) so the hot
     * entry path never blocks on the LLM.
     */
    fun preTradeVeto(symbol: String, score: Int, conf: Int, reasons: String): Boolean {
        val now = System.currentTimeMillis()
        val cached = vetoCache[symbol]
        if (cached != null && now - cached.ts < CACHE_TTL_MS) return cached.allow

        if (llmReady()) {
            GlobalScope.launch(Dispatchers.IO) {
                runCatching {
                    val q = "Trade entry sanity check. Symbol=$symbol score=$score conf=$conf. " +
                            "Reasons: ${reasons.take(140)}. Reply VETO if obvious rug/avoid, else OK."
                    val advice = ask(q) ?: return@runCatching
                    val allow = !advice.contains("VETO", ignoreCase = true) &&
                                !advice.contains("avoid", ignoreCase = true) &&
                                !advice.contains("rug",   ignoreCase = true)
                    vetoCache[symbol] = VetoResult(allow, System.currentTimeMillis())
                    if (!allow) ErrorLogger.info(TAG, "🛑 LLM VETO suggested for $symbol: ${advice.take(80)}")
                }
            }
        }
        return true
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. EXIT OVERRIDE
    // ─────────────────────────────────────────────────────────────────────────
    /**
     * Returns true=force exit, false=hold. Only fires for positions held
     * ≥3min so chronic noise doesn't burn LLM budget. Cached 60s.
     */
    fun shouldExit(symbol: String, pnlPct: Double, holdMinutes: Long, peakPct: Double): Boolean {
        if (holdMinutes < 3) return false
        val now = System.currentTimeMillis()
        val cached = exitCache[symbol]
        if (cached != null && now - cached.ts < CACHE_TTL_MS) return cached.exit

        if (llmReady()) {
            GlobalScope.launch(Dispatchers.IO) {
                runCatching {
                    val q = "Position exit check. Symbol=$symbol pnl=${"%.1f".format(pnlPct)}% " +
                            "peak=${"%.1f".format(peakPct)}% held=${holdMinutes}min. " +
                            "Reply EXIT or HOLD."
                    val advice = ask(q) ?: return@runCatching
                    val ex = advice.contains("EXIT", ignoreCase = true) &&
                             !advice.contains("HOLD", ignoreCase = true)
                    exitCache[symbol] = ExitResult(ex, System.currentTimeMillis())
                    if (ex) ErrorLogger.info(TAG, "🚨 LLM EXIT suggested for $symbol @ ${"%.1f".format(pnlPct)}%")
                }
            }
        }
        return false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. UNIVERSE-WIDE LLM TRADING — meme version (ready, not auto-wired)
    // ─────────────────────────────────────────────────────────────────────────
    /**
     * Hook for SentientPersonality / chat layer to request a paper buy on a
     * MEME token (mirrors CryptoAltTrader.llmOpenPaperBuy). Logs the request;
     * actual entry still goes through Executor + TradeAuth so all guardrails apply.
     */
    fun requestLlmMemeBuy(symbol: String, sizeSol: Double, reason: String) {
        ErrorLogger.info(TAG, "🤖 LLM-MEME BUY request: $symbol size=${sizeSol}◎ reason=$reason")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. SYMBIOSIS — cross-engine telegraph
    // ─────────────────────────────────────────────────────────────────────────
    /**
     * Called from each engine's close path. Updates engine running PnL
     * and recomputes cross-engine size biases.
     */
    fun recordEngineOutcome(engine: String, pnlSol: Double, isWin: Boolean) {
        val key = engine.uppercase()
        val s = engineState.getOrPut(key) { EngineRunningPnL() }
        synchronized(s) {
            s.trades++
            if (isWin) s.wins++
            s.pnlSol += pnlSol
            s.lastUpdateMs = System.currentTimeMillis()
        }
        rebalanceBiases()

        if (!isWin && pnlSol < 0) {
            recentLosers.add(LoserSnapshot(
                engine = key,
                symbol = "?",
                pnlPct = pnlSol,
                holdMinutes = 0L,
                reasons = emptyList(),
                regime = "",
            ))
            postMortemIfDue()
        }
    }

    /**
     * V5.9.495z21 — mint-aware overload that short-circuits the cross-engine
     * aggregate update when the trade was a partial-bridge / output-mismatch
     * / recovery event (target token never landed). Prevents the running
     * win rate for MEME / LAB engines from being polluted by phantom
     * outcomes. If the mint has no stamped execution status, behaves
     * identically to the legacy 3-arg form.
     */
    fun recordEngineOutcome(engine: String, mint: String, pnlSol: Double, isWin: Boolean) {
        if (!com.lifecyclebot.engine.execution.ExecutionStatusRegistry.shouldTrainStrategy(mint)) {
            return
        }
        recordEngineOutcome(engine, pnlSol, isWin)
    }

    /** Bias multiplier for use in sizing (0.5..1.5). */
    fun crossEngineBias(engine: String): Double =
        crossEngineBias[engine.uppercase()] ?: 1.0

    private fun rebalanceBiases() {
        engineState.forEach { (k, v) ->
            val wr = if (v.trades > 0) v.wins.toDouble() / v.trades else 0.5
            val mult = when {
                v.trades < 5    -> 1.0
                wr >= 0.55      -> min(1.50, 1.0 + (wr - 0.55) * 1.0)
                wr >= 0.40      -> 1.0
                else            -> max(0.50, 0.5 + wr)
            }
            crossEngineBias[k] = mult
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. POST-MORTEM BATCH LEARNING
    // ─────────────────────────────────────────────────────────────────────────
    private fun postMortemIfDue() {
        val snapshot: List<LoserSnapshot>
        synchronized(recentLosers) {
            if (recentLosers.size < POSTMORTEM_BATCH) return
            snapshot = recentLosers.toList()
            recentLosers.clear()
        }
        if (!llmReady()) return
        GlobalScope.launch(Dispatchers.IO) {
            runCatching {
                val payload = snapshot.joinToString("\n") {
                    "- ${it.engine}/${it.symbol} pnl=${"%.1f".format(it.pnlPct)} reasons=${it.reasons.take(3)}"
                }
                val advice = ask(
                    "Recent ${snapshot.size} losing trades:\n$payload\n\n" +
                    "What is the SINGLE biggest pattern? One sentence under 80 chars.",
                    maxTokens = 120
                )
                if (!advice.isNullOrBlank()) {
                    lastPostMortemHint = advice.take(120)
                    lastPostMortemMs = System.currentTimeMillis()
                    ErrorLogger.info(TAG, "🔬 POST-MORTEM: $lastPostMortemHint")
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. PERSONALITY-DRIVEN FILTER
    // ─────────────────────────────────────────────────────────────────────────
    /**
     * Returns true if the user's recent personality memory chat suggests
     * this kind of trade should be skipped. Best-effort, default false.
     */
    fun shouldFilterByPersonality(symbol: String, regime: String): Boolean {
        return try {
            val recent = PersonalityMemoryStore.recentChat(8)
            recent.any { turn ->
                val t = turn.text.lowercase()
                t.contains("avoid") && (regime.isNotBlank() && t.contains(regime.lowercase()))
            }
        } catch (_: Throwable) {
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. DYNAMIC SIZE MULTIPLIER
    // ─────────────────────────────────────────────────────────────────────────
    /**
     * Returns a 0.5..1.5 multiplier for trade size based on cross-engine
     * bias + LLM-recommended scaling. Cached; defaults to 1.0 on cache miss.
     */
    fun suggestSizeMultiplier(engine: String, symbol: String, regime: String): Double {
        val biasFromSymbiosis = crossEngineBias(engine)
        val key = "$engine:$symbol"
        val now = System.currentTimeMillis()
        val cached = sizeCache[key]
        val llmMult = if (cached != null && now - cached.ts < CACHE_TTL_MS) cached.mult else 1.0

        if ((cached == null || now - cached.ts >= CACHE_TTL_MS) && llmReady()) {
            GlobalScope.launch(Dispatchers.IO) {
                runCatching {
                    val advice = ask(
                        "Sizing call. engine=$engine symbol=$symbol regime=$regime. " +
                        "Reply AGGRESSIVE / NORMAL / CAUTIOUS only."
                    ) ?: return@runCatching
                    val proposed = when {
                        advice.contains("AGGRESSIVE", ignoreCase = true) -> 1.25
                        advice.contains("CAUTIOUS",   ignoreCase = true) -> 0.75
                        else -> 1.0
                    }.coerceIn(0.5, 1.5)
                    sizeCache[key] = SizeResult(proposed, System.currentTimeMillis())
                }
            }
        }
        return (biasFromSymbiosis * llmMult).coerceIn(0.5, 1.5)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 8. DISTRUST NOMINATION
    // ─────────────────────────────────────────────────────────────────────────
    fun nominatedPauseList(): Set<String> = pausedStrategies

    /**
     * Asks LLM which currently-distrusted strategies should be temporarily
     * paused (vs permanently distrusted). Self-rate-limits to ~30min.
     */
    fun nominateStrategiesToPause(distrusted: List<String>) {
        if (distrusted.isEmpty() || !llmReady()) return
        val now = System.currentTimeMillis()
        if (now - lastDistrustNominateMs.get() < DISTRUST_INTERVAL_MS) return
        lastDistrustNominateMs.set(now)
        GlobalScope.launch(Dispatchers.IO) {
            runCatching {
                val q = "These strategies are flagged distrusted: ${distrusted.joinToString()}. " +
                        "Which should we pause for 2h instead of permanently distrust? " +
                        "Reply with comma-separated names only."
                val advice = ask(q, maxTokens = 120) ?: return@runCatching
                val nominated = advice.split(",").map { it.trim() }.filter { it.isNotEmpty() && it in distrusted }
                pausedStrategies = nominated.toSet()
                if (nominated.isNotEmpty()) ErrorLogger.info(TAG, "⏸ LLM nominated for 2h-pause: $nominated")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 9. PERIODIC AUTO-TUNE
    // ─────────────────────────────────────────────────────────────────────────
    /**
     * Every 6h, asks LLM to review recent performance and propose ONE
     * parameter nudge via LlmParameterTuner.
     */
    fun maybeAutoTune(context: android.content.Context) {
        val now = System.currentTimeMillis()
        if (now - lastAutoTuneMs.get() < AUTOTUNE_INTERVAL_MS) return
        if (!llmReady()) return
        lastAutoTuneMs.set(now)
        GlobalScope.launch(Dispatchers.IO) {
            runCatching {
                val q = "Self-review: based on the last 6 hours, suggest exactly ONE parameter nudge " +
                        "to improve win rate. Format: 'set <param>=<value>' on a single line."
                val reply = ask(q, system = "You are a quantitative tuner. Reply <=80 chars.", maxTokens = 120)
                    ?: return@runCatching
                LlmParameterTuner.extractAndApply(context, reply)
                ErrorLogger.info(TAG, "🔧 LLM auto-tune cycle: ${reply.take(80)}")
            }
        }
    }

    // ─── Diagnostics ─────────────────────────────────────────────────────────
    fun statusSummary(): String {
        val biases = crossEngineBias.entries.joinToString { "${it.key}=${"%.2f".format(it.value)}×" }
        val pending = synchronized(recentLosers) { recentLosers.size }
        return "biases=[$biases] losersBuffered=$pending paused=${pausedStrategies.size} hint=${lastPostMortemHint.take(40)}"
    }
}
