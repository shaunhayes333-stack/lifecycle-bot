package com.lifecyclebot.engine

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6281 — Self-Healing LLM Advisor.
 *
 * V5.0.6571 — AUTONOMOUS SELF-HEALING (operator: "should be making the
 * required changes in an Autonomous state. not require user intervention").
 * Parsed suggestions now auto-apply through the existing LlmParameterTuner
 * pipeline (same allowlist / step-cap / phase gate / persistence path an
 * LLM TUNE block would use) instead of parking in AdvisorInbox for one-
 * tap accept. AdvisorInbox is still populated for audit visibility so
 * operator can see everything the advisor did without gating on their
 * approval.
 *
 * Cadence:
 *  - On-demand via runNowAsync() (button tap in PipelineHealth).
 *  - Auto: fires when starvation is detected AND ≥ 5 minutes since the
 *    last successful advisory run (see maybeAutoAdvise()).
 */
object SelfHealingAdvisor {

    private const val TAG = "SelfHealingAdvisor"
    private const val MIN_INTERVAL_MS = 5L * 60_000L
    private const val MAX_REPORT_CHARS = 18_000

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lastRunMs = AtomicLong(0L)
    @Volatile private var running: Boolean = false

    data class Suggestion(
        val id: String,
        val createdAtMs: Long,
        val key: String,
        val delta: Double,
        val reason: String,
        val expectedImpact: String,
        val severity: String,  // "high"|"med"|"low"
    )

    data class RunResult(
        val ok: Boolean,
        val error: String?,
        val suggestions: List<Suggestion>,
        val rawReply: String?,
    )

    fun lastRunAtMs(): Long = lastRunMs.get()
    fun isRunning(): Boolean = running

    /** On-demand advisor call. Callback runs on Dispatchers.Main-safe (delegated by caller). */
    fun runNowAsync(ctx: Context, callback: (RunResult) -> Unit) {
        if (running) {
            callback(RunResult(false, "advisor already running", emptyList(), null))
            return
        }
        scope.launch {
            running = true
            val res = try {
                runBlocking(ctx)
            } catch (t: Throwable) {
                RunResult(false, t.message ?: t.javaClass.simpleName, emptyList(), null)
            } finally {
                running = false
                lastRunMs.set(System.currentTimeMillis())
            }
            try { callback(res) } catch (_: Throwable) {}
        }
    }

    /**
     * Called from the bot loop when starvation is detected. Rate-limited
     * to at most one call every MIN_INTERVAL_MS. Silent on failure.
     */
    fun maybeAutoAdvise(ctx: Context, reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastRunMs.get() < MIN_INTERVAL_MS) return
        if (running) return
        scope.launch {
            running = true
            try {
                val res = runBlocking(ctx)
                try {
                    ForensicLogger.lifecycle(
                        "SELF_HEALING_ADVISOR_AUTO_RUN_6281",
                        "reason=$reason suggestions=${res.suggestions.size} ok=${res.ok} err=${res.error ?: "none"}",
                    )
                    PipelineHealthCollector.labelInc("SELF_HEALING_ADVISOR_AUTO_RUN_6281")
                } catch (_: Throwable) {}
            } catch (_: Throwable) {
            } finally {
                running = false
                lastRunMs.set(System.currentTimeMillis())
            }
        }
    }

    private suspend fun runBlocking(ctx: Context): RunResult = withContext(Dispatchers.IO) {
        val report = buildReport()
        if (report.isBlank()) {
            return@withContext RunResult(false, "report unavailable", emptyList(), null)
        }
        val allowlist = LlmParameterTuner.allowedKeys().joinToString(", ")
        val systemPrompt = SYSTEM_PROMPT_TEMPLATE.replace("{ALLOW}", allowlist)
        val userPrompt = buildUserPrompt(report)

        val reply = try {
            GeminiCopilot.rawText(userPrompt = userPrompt, systemPrompt = systemPrompt, temperature = 0.35, maxTokens = 900)
        } catch (t: Throwable) {
            null
        }
        if (reply.isNullOrBlank()) {
            return@withContext RunResult(false, "llm unavailable", emptyList(), null)
        }

        val parsed = parseAdvisorReply(reply)
        if (parsed.isNotEmpty()) {
            AdvisorInbox.addAll(parsed)
            // V5.0.6571 — AUTONOMOUS SELF-HEALING.
            // Auto-apply suggestions instead of waiting for operator tap.
            // The application path reuses LlmParameterTuner.extractAndApply
            // so every safety valve — phase gate (bootstrap/learning/mature),
            // free-range step-cap ramp, allowlist, per-key min/max, per-call
            // adjustment count — is exactly the same as if the LLM had
            // emitted a native <<TUNE>>…<<ENDTUNE>> block. AdvisorInbox is
            // still populated so operator can audit what changed.
            val applied = try { autoApplySuggestions(ctx, parsed) } catch (_: Throwable) { null }
            try {
                ForensicLogger.lifecycle(
                    "SELF_HEALING_ADVISOR_SUGGESTIONS_6281",
                    "count=${parsed.size} keys=${parsed.joinToString("|") { it.key }} " +
                        "autoApplied=${applied?.changes?.size ?: 0} " +
                        "autoRejected=${applied?.rejected?.size ?: 0}",
                )
                PipelineHealthCollector.labelInc("SELF_HEALING_ADVISOR_SUGGESTIONS_6281")
                if ((applied?.changes?.size ?: 0) > 0) {
                    PipelineHealthCollector.labelInc("SELF_HEALING_ADVISOR_AUTOAPPLIED_6571")
                    for (c in applied?.changes.orEmpty()) {
                        // Every accepted change ticks its own key so the
                        // operator can see the parameter drift per-metric.
                        PipelineHealthCollector.labelInc("SELF_HEALING_TUNE_APPLIED_6571|${c.key}")
                        // Book-keep the corresponding advisor suggestion
                        // as "applied" so the inbox UI does not offer it
                        // again as a pending action.
                        for (s in parsed) if (s.key == c.key) AdvisorInbox.markApplied(s.id)
                        ForensicLogger.lifecycle(
                            "SELF_HEALING_TUNE_APPLIED_6571",
                            "key=${c.key} old=${c.oldValue} new=${c.newValue} reason=${c.reason.take(120)}",
                        )
                    }
                }
                for (r in applied?.rejected.orEmpty()) {
                    PipelineHealthCollector.labelInc("SELF_HEALING_TUNE_REJECTED_6571")
                    ForensicLogger.lifecycle(
                        "SELF_HEALING_TUNE_REJECTED_6571",
                        "detail=${r.take(160)}",
                    )
                }
            } catch (_: Throwable) {}
        }
        RunResult(true, null, parsed, reply)
    }

    /**
     * V5.0.6571 — convert parsed advisor suggestions into a
     * <<TUNE>>...<<ENDTUNE>> block and hand it to LlmParameterTuner. All
     * safety valves (phase gate, step cap, allowlist, per-call adjustment
     * cap) live inside that path so nothing bypasses them here.
     */
    private fun autoApplySuggestions(
        ctx: Context,
        suggestions: List<Suggestion>,
    ): LlmParameterTuner.Applied {
        if (suggestions.isEmpty()) {
            return LlmParameterTuner.Applied(cleanedReply = "", changes = emptyList(), rejected = emptyList())
        }
        val adjustmentsArr = JSONArray()
        for (s in suggestions) {
            adjustmentsArr.put(
                JSONObject()
                    .put("key", s.key)
                    .put("delta", s.delta)
                    .put("reason", "self-healing:${s.severity}:${s.reason.take(120)}"),
            )
        }
        val body = JSONObject().put("adjustments", adjustmentsArr).toString()
        val block = "<<TUNE>>$body<<ENDTUNE>>"
        return LlmParameterTuner.extractAndApply(ctx, block)
    }

    private suspend fun buildReport(): String {
        // V5.0.6417 — REPORT-BUILDER TIMEOUT GUARD.
        // Operator report: self-healing advisor "currently won't generate
        // the report." Root cause: full_builder_timeout_8s in the shared
        // ReportingHub keeps the advisor waiting on the same choked builder.
        // Wrap with a 5s timeout and fall back to the cheap labels dump so
        // the advisor always has SOMETHING to score against.
        return try {
            kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                ReportingHub.buildText(ReportingHub.Kind.UNIFIED_HEALTH).text
            }?.let { txt ->
                if (txt.length > MAX_REPORT_CHARS) txt.take(MAX_REPORT_CHARS) + "\n[TRUNCATED]" else txt
            } ?: run {
                try {
                    PipelineHealthCollector.labelInc("SELF_HEALING_REPORT_TIMEOUT_FALLBACK_6417")
                    ForensicLogger.lifecycle(
                        "SELF_HEALING_REPORT_TIMEOUT_FALLBACK_6417",
                        "reportingHub_timeout_5s falling_back_to_labels_dump",
                    )
                } catch (_: Throwable) {}
                try { PipelineHealthCollector.dumpText().take(MAX_REPORT_CHARS) } catch (_: Throwable) { "" }
            }
        } catch (_: Throwable) {
            try { PipelineHealthCollector.dumpText().take(MAX_REPORT_CHARS) } catch (_: Throwable) { "" }
        }
    }

    private fun buildUserPrompt(report: String): String = """
The live trading bot just published this operational report. You are an
autonomous tuning advisor. Read the report carefully. Identify the top 1-3
concrete parameter adjustments that would most improve WR / net PnL /
throughput without introducing catastrophic risk. Emit ONLY valid JSON.

REPORT:
$report

Respond with pure JSON — no prose, no code fences — of the shape:
{
  "suggestions": [
    {
      "key": "<one of the allowed keys>",
      "delta": <signed number>,
      "severity": "high|med|low",
      "reason": "<short explanation grounded in a specific metric from the report>",
      "expected_impact": "<one line, what improves and by how much>"
    }
  ]
}

If nothing needs tuning, return {"suggestions": []}.
""".trimIndent()

    private fun parseAdvisorReply(reply: String): List<Suggestion> {
        val jsonText = extractJsonObject(reply) ?: return emptyList()
        val obj = try { JSONObject(jsonText) } catch (_: Throwable) { return emptyList() }
        val arr = obj.optJSONArray("suggestions") ?: return emptyList()
        val out = ArrayList<Suggestion>(arr.length())
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val key = row.optString("key").trim()
            if (key.isBlank() || !LlmParameterTuner.isAllowedKey(key)) continue
            val delta = row.opt("delta")?.let {
                when (it) {
                    is Number -> it.toDouble()
                    is String -> it.toDoubleOrNull()
                    else -> null
                }
            } ?: continue
            if (!delta.isFinite() || delta == 0.0) continue
            val reason = row.optString("reason").trim().take(240)
            val impact = row.optString("expected_impact").trim().take(160)
            val severity = row.optString("severity").trim().lowercase().ifBlank { "med" }
            out.add(
                Suggestion(
                    id = UUID.randomUUID().toString().take(8),
                    createdAtMs = now,
                    key = key,
                    delta = delta,
                    reason = reason,
                    expectedImpact = impact,
                    severity = severity,
                )
            )
        }
        return out
    }

    private fun extractJsonObject(raw: String): String? {
        val first = raw.indexOf('{')
        val last = raw.lastIndexOf('}')
        if (first < 0 || last <= first) return null
        return raw.substring(first, last + 1)
    }

    /**
     * Applies one suggestion by constructing a synthetic <<TUNE>> block
     * and handing it to LlmParameterTuner.extractAndApply. Returns
     * (ok, message).
     */
    fun applySuggestion(ctx: Context, suggestion: Suggestion): Pair<Boolean, String> {
        val jsonBlock = JSONObject().apply {
            put("adjustments", JSONArray().apply {
                put(JSONObject().apply {
                    put("key", suggestion.key)
                    put("delta", suggestion.delta)
                    put("reason", "advisor:${suggestion.reason}")
                })
            })
        }
        val synthetic = "<<TUNE>>${jsonBlock}<<ENDTUNE>>"
        val res = try {
            LlmParameterTuner.extractAndApply(ctx, synthetic)
        } catch (t: Throwable) {
            return false to "apply failed: ${t.message}"
        }
        AdvisorInbox.markApplied(suggestion.id)
        try {
            ForensicLogger.lifecycle(
                "SELF_HEALING_ADVISOR_ACCEPTED_6281",
                "key=${suggestion.key} delta=${suggestion.delta} changes=${res.changes.size} rejected=${res.rejected.size}",
            )
            PipelineHealthCollector.labelInc("SELF_HEALING_ADVISOR_ACCEPTED_6281")
        } catch (_: Throwable) {}
        return if (res.changes.isNotEmpty()) {
            val c = res.changes.first()
            try {
                ForensicLogger.lifecycle(
                    "SELF_HEALING_ADVISOR_APPLIED_OK_6417",
                    "id=${suggestion.id} key=${c.key} oldValue=${c.oldValue} newValue=${c.newValue} totalChanges=${res.changes.size}",
                )
                PipelineHealthCollector.labelInc("SELF_HEALING_ADVISOR_APPLIED_OK_6417")
            } catch (_: Throwable) {}
            true to "${c.key}: ${c.oldValue} → ${c.newValue}"
        } else if (res.rejected.isNotEmpty()) {
            try {
                ForensicLogger.lifecycle(
                    "SELF_HEALING_ADVISOR_APPLY_REJECTED_6417",
                    "id=${suggestion.id} key=${suggestion.key} rejected=${res.rejected.joinToString(",")}",
                )
                PipelineHealthCollector.labelInc("SELF_HEALING_ADVISOR_APPLY_REJECTED_6417")
            } catch (_: Throwable) {}
            false to "rejected: ${res.rejected.joinToString(", ")}"
        } else {
            try {
                ForensicLogger.lifecycle(
                    "SELF_HEALING_ADVISOR_APPLY_NO_EFFECT_6417",
                    "id=${suggestion.id} key=${suggestion.key} delta=${suggestion.delta} note=no_change_no_reject",
                )
                PipelineHealthCollector.labelInc("SELF_HEALING_ADVISOR_APPLY_NO_EFFECT_6417")
            } catch (_: Throwable) {}
            false to "no effective change"
        }
    }

    private const val SYSTEM_PROMPT_TEMPLATE = """
You are the AATE self-healing advisor. You never trade, buy, or sell.
You emit tuning suggestions that a human operator will one-tap accept.
Never invent parameter names. Only these keys may be adjusted:
{ALLOW}

Prioritise the highest-impact fix. Ground every reason in a specific
number from the report. Prefer small deltas (single digits or fractions).
Never respond with anything other than one JSON object.
"""
}

/** In-memory + lightweight persisted inbox of pending advisor suggestions. */
object AdvisorInbox {
    private val store = ConcurrentHashMap<String, SelfHealingAdvisor.Suggestion>()
    private val applied = ConcurrentHashMap<String, Long>()
    private const val MAX_PENDING = 30

    fun addAll(items: List<SelfHealingAdvisor.Suggestion>) {
        for (s in items) store[s.id] = s
        pruneIfNeeded()
    }

    fun pending(): List<SelfHealingAdvisor.Suggestion> =
        store.values.filter { !applied.containsKey(it.id) }
            .sortedByDescending { it.createdAtMs }

    fun markApplied(id: String) {
        applied[id] = System.currentTimeMillis()
    }

    fun dismiss(id: String) {
        store.remove(id)
    }

    fun clear() {
        store.clear()
        applied.clear()
    }

    private fun pruneIfNeeded() {
        if (store.size <= MAX_PENDING) return
        val excess = store.size - MAX_PENDING
        val toRemove = store.values.sortedBy { it.createdAtMs }.take(excess)
        for (r in toRemove) store.remove(r.id)
    }
}
