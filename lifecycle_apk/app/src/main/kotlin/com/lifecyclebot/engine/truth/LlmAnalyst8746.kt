package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8746 §THE_LLM_ANALYST — owner: "proper llm powered ai". Until now an LLM only wrote the bot's inner monologue
 * (SentienceOrchestrator: "never an economic authority"). Here the owner's council (KeylessLlmClient: Groq / xAI Grok /
 * Gemini / OpenRouter / ...) reads a candidate's decision-time facts and states a probability that the coin is up by more
 * than the round trip in the next hour. That probability is ONE voter of Mind8746: it is frozen with the decision, graded on
 * the forward label (Brier vs the base rate, in the mind's status) and weighted only by what the stacker learns it is worth.
 * Asked only for candidates the tree already rates non-negative, at most [MAX_PER_HOUR] calls an hour, cached [CACHE_MS].
 */
object LlmAnalyst8746 {
    private const val CACHE_MS = 15L * 60_000L
    private const val MAX_PER_HOUR = 60
    private val answers = ConcurrentHashMap<String, Pair<Long, Double>>()
    private val inflight = ConcurrentHashMap.newKeySet<String>()
    private val calls = java.util.ArrayDeque<Long>()
    private val asked = AtomicLong(0L); private val answered = AtomicLong(0L); private val unparsed = AtomicLong(0L)
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "llm-analyst-8746").apply { isDaemon = true } }

    /** The cached probability for [mint], or null. */
    fun prob8746(mint: String, nowMs: Long): Double? = answers[mint]?.takeIf { nowMs - it.first in 0L..CACHE_MS }?.second

    /** Pure: the probability in an answer, or null. */
    fun parse8746(text: String?): Double? {
        val m = Regex("\"?p_up\"?\\s*[:=]\\s*([0-9]*\\.?[0-9]+)").find(text ?: return null) ?: return null
        return m.groupValues[1].toDoubleOrNull()?.takeIf { it in 0.0..1.0 }
    }

    /** Pure: the facts the analyst reads. */
    private fun facts8746(r: EdgeModel8537.Raw, mint: String): String = buildString {
        fun f(k: String, v: Double, d: Int = 1) { if (v.isFinite()) append("$k=").append("%.${d}f".format(v)).append("; ") }
        append("lane=${r.lane}; launchpad=${if (mint.endsWith("pump")) "pump.fun" else "other"}; ")
        f("age_min", r.ageMin); f("mcap_usd", r.mcap, 0); f("pool_liquidity_usd", r.liq, 0); f("run_from_hour_low_pct", r.runup)
        f("drawdown_from_peak_pct", r.dd); f("buy_share_pct", r.bp); f("change_5m_pct", r.chg5m); f("change_1h_pct", r.chg1h)
        f("top_holder_pct", r.top); f("trades_per_min", r.txVel); f("bonding_curve_progress", r.curve, 2)
        f("turnover", r.turnover, 2); f("round_trip_cost_pct", r.costPct)
    }

    /** EdgeModel8537: ask for [mint] in the background when allowed; never blocks. */
    fun request8746(r: EdgeModel8537.Raw, mint: String, nowMs: Long) {
        if (prob8746(mint, nowMs) != null || !inflight.add(mint)) return
        synchronized(calls) {
            while (calls.isNotEmpty() && nowMs - calls.first() > 3_600_000L) calls.removeFirst()
            if (calls.size >= MAX_PER_HOUR) { inflight.remove(mint); return }
            calls.addLast(nowMs)
        }
        asked.incrementAndGet()
        val user = "Solana memecoin at decision time: ${facts8746(r, mint)}\nEstimate the probability that this coin's price is " +
            "higher than now by more than the round-trip cost one hour from now. Answer ONLY JSON: {\"p_up\": 0.00-1.00, \"why\": \"<12 words\"}"
        try {
            exec.execute {
                try {
                    val text = com.lifecyclebot.network.KeylessLlmClient.runChat(
                        "You are a disciplined crypto market analyst. Most new memecoins fall; be calibrated, not hopeful.", user, 80)
                    val p = parse8746(text)
                    if (p != null) { answers[mint] = System.currentTimeMillis() to p; answered.incrementAndGet() } else unparsed.incrementAndGet()
                    if (answers.size > 5_000) answers.entries.removeIf { System.currentTimeMillis() - it.value.first > CACHE_MS }
                } catch (_: Throwable) { unparsed.incrementAndGet() } finally { inflight.remove(mint) }
            }
        } catch (_: Throwable) { inflight.remove(mint) }
        try { PipelineHealthCollector.labelInc("LLM_ANALYST_ASKED_8746") } catch (_: Throwable) {}
    }

    fun statusLine8746(): String = "llmAnalyst8746[asked=${asked.get()} answered=${answered.get()} unparsed=${unparsed.get()} " +
        "cached=${answers.size} maxPerHour=$MAX_PER_HOUR rule=one voter in the mind; weight only from its graded record]"
}
