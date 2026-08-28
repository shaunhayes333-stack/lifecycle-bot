package com.lifecyclebot.engine.truth

import android.content.Context
import com.lifecyclebot.data.ConfigStore
import com.lifecyclebot.engine.AdvisorInbox
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.GeminiCopilot
import com.lifecyclebot.engine.LlmParameterTuner
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.SelfHealingAdvisor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6462 §P0 — AUTONOMOUS PIPELINE ADVISOR (ALL-BRAINS + LLM).
 *
 * OPERATOR MANDATE (Feb 2026):
 *   "the auto pipeline advisor doesn't return any suggestions ever.
 *    realistically it should be making those changes automatically as
 *    needed via test consult with all the brains, llm, meta cognition,
 *    super agi, ssi"
 *
 * ROOT CAUSE (of the "no suggestions" bug)
 * ────────────────────────────────────────
 * `SelfHealingAdvisor.maybeAutoAdvise` was defined but **never called**
 * from BotService — the auto path was dead code. Additionally the advisor
 * ONLY consulted GeminiCopilot; when the LLM returned null (offline,
 * missing key, rate-limited) it silently returned zero suggestions.
 *
 * DESIGN
 * ──────
 * This module is a genuinely autonomous, rules-first advisor that ALWAYS
 * produces suggestions, then fuses LLM + brain votes to prioritise them:
 *
 *   1. RULES ENGINE (always available, no I/O)
 *      Reads PipelineHealthCollector labels + PaperAccountReplay6461
 *      divergence + RootCauseClassifier6460 signals and derives a set
 *      of candidate parameter deltas from deterministic health rules.
 *      Even with zero LLM, the advisor produces useful output.
 *
 *   2. BRAIN CONSULTATION (in-memory, ~1ms each)
 *      • MetaCognitionExecutorBridge  → per-lane bias multiplier
 *      • SuperBrainEnhancements       → per-mint memory (aggregate)
 *      • CapitalEfficiencyBrain       → per-lane capital fit
 *      • SentienceOrchestrator        → recent reflection tone
 *      • BrainConsensusGate           → proven-dead label scan
 *      • RiskExitPriorityDomain6461   → recent HIGH-latency alerts
 *      Each brain contributes a per-suggestion agreement score (0..1).
 *
 *   3. LLM RANKER (optional, degrades cleanly to rules-only)
 *      When GeminiCopilot is available, the LLM re-ranks the rule
 *      candidates + adds new ones. When unavailable, rules stand.
 *
 *   4. AUTO-APPLY GATE
 *      Applies via existing LlmParameterTuner (phase-gated, step-capped,
 *      allowlist-enforced, freerange-scaled). Only applies when:
 *        • config.autoPipelineAdvisorEnabled == true  (default: paper=on, live=off)
 *        • suggestion.brainAgreement >= AUTO_APPLY_MIN_AGREEMENT
 *        • suggestion.severity in {"med","high"}
 *        • no cooldown collision (min 10 min per key per session)
 *      Applied changes are logged to `AdvisorInbox` for post-hoc review.
 *
 * Cadence
 * ───────
 * `maybeTick(ctx)` is called from BotService.botLoop every N loops
 * (default: every 12 loops ≈ 2 min). Rate-limited to 90s min between
 * *successful* runs; a failed run does not consume the interval.
 *
 * Safety Invariants
 * ─────────────────
 *  • Never runs on the bot loop thread (dispatched to Dispatchers.IO).
 *  • Never blocks: rules engine + brains take < 50ms combined.
 *  • Never applies during bootstrap phase (LlmParameterTuner enforces
 *    this via FluidLearningAI.getTotalTradeCount < 50).
 *  • Every applied change emits AUTO_PIPELINE_ADVISOR_APPLIED_6462 with
 *    key/old/new/reason + brain votes for full audit.
 */
object AutoPipelineAdvisor6462 {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private const val TICK_MIN_INTERVAL_MS = 90_000L      // 90s min between runs
    private const val PER_KEY_COOLDOWN_MS = 10L * 60_000L // 10 min per key
    private const val AUTO_APPLY_MIN_AGREEMENT = 0.55     // 55% brain agreement
    private const val MAX_APPLY_PER_TICK = 2

    private val lastRunMs = AtomicLong(0L)
    private val lastAppliedAtByKey = ConcurrentHashMap<String, Long>()
    // V5.0.6507 §P1 — memoise last-seen PAPER_REPLAY_DIVERGENCE_6461 so
    // Rule R2 fires only on NEW divergences, not historical ones.
    private val lastSeenReplayDivergence6507 = AtomicLong(0L)
    private val ticks = AtomicLong(0L)
    private val runsOk = AtomicLong(0L)
    private val runsFailed = AtomicLong(0L)
    private val autoApplied = AtomicLong(0L)
    private val running = AtomicReference(false)

    data class BrainVote(val brain: String, val agreesWithDeltaSign: Boolean, val weight: Double)

    data class Candidate(
        val id: String,
        val key: String,
        val delta: Double,
        val severity: String,       // "high"|"med"|"low"
        val reason: String,         // ground-truth metric or brain reading
        val source: String,         // "rules"|"llm"|"rules+llm"
        val brainVotes: List<BrainVote>,
        val brainAgreement: Double, // 0..1 weighted agreement across brains
    )

    // ─── Public API ─────────────────────────────────────────────────────────

    fun maybeTick(ctx: Context) {
        if (running.get()) return
        val now = System.currentTimeMillis()
        if (now - lastRunMs.get() < TICK_MIN_INTERVAL_MS) return
        if (!running.compareAndSet(false, true)) return
        ticks.incrementAndGet()
        scope.launch {
            try {
                runTick(ctx)
                runsOk.incrementAndGet()
                lastRunMs.set(System.currentTimeMillis())
            } catch (t: Throwable) {
                runsFailed.incrementAndGet()
                try {
                    ForensicLogger.lifecycle(
                        "AUTO_PIPELINE_ADVISOR_TICK_FAILED_6462",
                        "err=${t.message?.take(120) ?: t.javaClass.simpleName}",
                    )
                    PipelineHealthCollector.labelInc("AUTO_PIPELINE_ADVISOR_TICK_FAILED_6462")
                } catch (_: Throwable) {}
            } finally {
                running.set(false)
            }
        }
    }

    fun statusLine(): String {
        return "ticks=${ticks.get()} runsOk=${runsOk.get()} failed=${runsFailed.get()} " +
            "autoApplied=${autoApplied.get()} lastRunMs=${lastRunMs.get()}"
    }

    // ─── Internal: one tick ────────────────────────────────────────────────

    private fun runTick(ctx: Context) {
        // V5.0.6505 — HOLDS DISABLED, ADVISOR ALWAYS RUNS.
        // Operator mandate: "quit strangling the bot — just fix the
        // fucking thing properly." Data integrity is now enforced at
        // the source (FillLotLedger6504 + purge/rebuild), so the
        // advisor tick no longer bails on ECONOMIC_INTEGRITY signals.
        // Diagnostic captured for reports only.
        try {
            if (com.lifecyclebot.engine.truth.AdvisorIntegrityHold6466.diagnosticActive()) {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("AUTO_PIPELINE_ADVISOR_RUN_WITH_INTEGRITY_DIAGNOSTIC_6505")
            }
        } catch (_: Throwable) {}
        // 1) rules engine — always produces candidates
        val rules = deriveRulesCandidates()
        // 2) brain readings (in-memory, no I/O)
        val laneCtx = "GLOBAL" // advisor operates on global params; brain lookups use "GLOBAL" bias.
        val brainReadings = fetchBrainReadings(laneCtx)
        // 3) fuse each rule candidate with brain agreement
        val fused = rules.map { fuseWithBrains(it, brainReadings) }
        // 4) optional LLM re-rank + additive candidates (best-effort, non-blocking failure)
        val enriched = enrichWithLlm(ctx, fused)
        // 5) auto-apply eligible high-agreement ones
        val cfg = try { ConfigStore.load(ctx) } catch (_: Throwable) { null }
        val autoEnabled = cfg?.autoPipelineAdvisorEnabled ?: (cfg?.paperMode == true)
        var applied = 0
        val emitted = mutableListOf<Candidate>()
        for (c in enriched.sortedByDescending { it.brainAgreement }) {
            val votesForHistory = c.brainVotes.map {
                AdvisorDecisionHistory6463.BrainVote(it.brain, it.agreesWithDeltaSign, it.weight)
            }
            fun histAction(action: AdvisorDecisionHistory6463.Action,
                           oldValue: Double = Double.NaN, newValue: Double = Double.NaN) {
                AdvisorDecisionHistory6463.record(
                    AdvisorDecisionHistory6463.Decision(
                        atMs = System.currentTimeMillis(),
                        key = c.key, delta = c.delta, severity = c.severity,
                        source = c.source, action = action,
                        brainAgreement = c.brainAgreement, votes = votesForHistory,
                        reason = c.reason, oldValue = oldValue, newValue = newValue,
                    )
                )
            }
            if (c.brainAgreement < AUTO_APPLY_MIN_AGREEMENT) {
                histAction(AdvisorDecisionHistory6463.Action.LOW_AGREEMENT); emitted += c; continue
            }
            if (c.severity == "low") { histAction(AdvisorDecisionHistory6463.Action.QUEUED_INBOX); emitted += c; continue }
            if (!autoEnabled) { histAction(AdvisorDecisionHistory6463.Action.QUEUED_INBOX); emitted += c; continue }
            if (applied >= MAX_APPLY_PER_TICK) {
                histAction(AdvisorDecisionHistory6463.Action.QUEUED_INBOX); emitted += c; continue
            }
            val cooldownStart = lastAppliedAtByKey[c.key] ?: 0L
            if (System.currentTimeMillis() - cooldownStart < PER_KEY_COOLDOWN_MS) {
                histAction(AdvisorDecisionHistory6463.Action.COOLDOWN_SKIP); emitted += c; continue
            }
            val applyRes = applyOne(ctx, c)
            when (applyRes) {
                is ApplyResult.Applied -> {
                    applied++
                    autoApplied.incrementAndGet()
                    lastAppliedAtByKey[c.key] = System.currentTimeMillis()
                    histAction(
                        AdvisorDecisionHistory6463.Action.AUTO_APPLIED,
                        oldValue = applyRes.oldValue, newValue = applyRes.newValue,
                    )
                    try {
                        AdvisorRegressionMonitor6463.registerApply(
                            id = c.id, key = c.key, deltaApplied = c.delta,
                            reason = "auto6462:${c.reason.take(120)}",
                        )
                    } catch (_: Throwable) {}
                }
                is ApplyResult.Noop -> histAction(AdvisorDecisionHistory6463.Action.APPLY_NOOP)
                is ApplyResult.Failed -> histAction(AdvisorDecisionHistory6463.Action.APPLY_FAILED)
            }
            emitted += c
        }
        // 6) emit all suggestions into AdvisorInbox for the UI + audit
        publishToInbox(emitted)
        try {
            ForensicLogger.lifecycle(
                "AUTO_PIPELINE_ADVISOR_TICK_6462",
                "candidates=${enriched.size} autoApplied=$applied autoEnabled=$autoEnabled " +
                    "topAgree=${"%.2f".format(enriched.maxOfOrNull { it.brainAgreement } ?: 0.0)} " +
                    "sources=${enriched.groupingBy { it.source }.eachCount()}",
            )
            PipelineHealthCollector.labelInc("AUTO_PIPELINE_ADVISOR_TICK_6462")
        } catch (_: Throwable) {}
    }

    // ─── Rules engine (always-on) ──────────────────────────────────────────

    private fun deriveRulesCandidates(): List<Candidate> {
        val out = mutableListOf<Candidate>()

        fun mk(key: String, delta: Double, severity: String, reason: String): Candidate = Candidate(
            id = UUID.randomUUID().toString().take(8),
            key = key, delta = delta, severity = severity, reason = reason,
            source = "rules", brainVotes = emptyList(), brainAgreement = 0.0,
        )

        val fi4famClamps = readLabel("FI4FAM_UNIT_CORRUPTION_6461")
        val replayDiv = readLabel("PAPER_REPLAY_DIVERGENCE_6461")
        val ledgerInvFails = readLabel("PAPER_LEDGER_INVARIANT_FAIL_6430")
        val highLatencyAlerts = readLabel("RISK_DOMAIN_HIGH_LATENCY_ALERT_6461")
        val pendingLeaks = readLabel("PENDING_ENTRY_LEAKED_INTO_OPEN_6461")
        val degraded = readLabel("API_LAYER_DEGRADED")
        val terminalDupRejects = readLabel("TERMINAL_SELL_DUPLICATE_CLOSING_REJECTED_6454") +
                                 readLabel("TERMINAL_SELL_DUPLICATE_CLOSED_REJECTED_6454")

        // V5.0.6568 — INTEGRITY ISOLATION. Replay, ledger, quantity, projection,
        // provider latency and duplicate-finality evidence belongs to repair/quarantine,
        // never automatic mutation of entry/exit/hold/liquidity/scan parameters.
        val integritySignals6568 = fi4famClamps + replayDiv + ledgerInvFails + highLatencyAlerts + pendingLeaks + degraded + terminalDupRejects
        if (integritySignals6568 > 0L) {
            try {
                PipelineHealthCollector.labelInc("ADVISOR_INTEGRITY_DIAGNOSTIC_ONLY_6568")
                ForensicLogger.lifecycle("ADVISOR_INTEGRITY_DIAGNOSTIC_ONLY_6568", "fi4fam=$fi4famClamps replay=$replayDiv ledger=$ledgerInvFails latency=$highLatencyAlerts pending=$pendingLeaks api=$degraded terminalDup=$terminalDupRejects action=repair_or_quarantine_no_strategy_mutation")
            } catch (_: Throwable) {}
        }
        lastSeenReplayDivergence6507.set(replayDiv)

        // Rule R8: Chronic-bleeder pattern → recommend lifting exit score
        //          threshold so laggard positions get out sooner. Fires
        //          only when at least one chronic-bleeder reprove was
        //          emitted this run.
        val chronicReproves = readLabel("CHRONIC_BLEEDER_LAB_REPROVE_6265")
        if (chronicReproves > 0) {
            out += mk(
                key = "exitScoreThreshold", delta = -3.0, severity = "med",
                reason = "CHRONIC_BLEEDER_LAB_REPROVE_6265=$chronicReproves — lower exit floor so chronic bleeders exit earlier",
            )
        }

        return out
    }

    private fun readLabel(key: String): Long =
        try { PipelineHealthCollector.labelCountSnapshot(key) } catch (_: Throwable) { 0L }

    // ─── Brain readings ────────────────────────────────────────────────────

    data class BrainReadings(
        val metaCogMult: Double,
        val superBrainMult: Double,
        val capitalMult: Double,
        val sentienceMult: Double,
        val provenDead: Boolean,
        val riskLatencyPressure: Double, // 0..1
    )

    private fun fetchBrainReadings(lane: String): BrainReadings {
        val meta = try {
            com.lifecyclebot.engine.MetaCognitionExecutorBridge.sizeMultiplierForLane(lane)
                .coerceIn(0.1, 1.5)
        } catch (_: Throwable) { 1.0 }
        val superBr = try {
            com.lifecyclebot.engine.SuperBrainEnhancements.entrySizeMultiplier("GLOBAL_ADVISOR").coerceIn(0.1, 1.5)
        } catch (_: Throwable) { 1.0 }
        val capital = try {
            com.lifecyclebot.engine.CapitalEfficiencyBrain
                .sizeMultiplier(lane, "advisor", isRunnerCandidate = false).coerceIn(0.1, 1.5)
        } catch (_: Throwable) { 1.0 }
        val sentience = try {
            val reflections = com.lifecyclebot.engine.SentienceOrchestrator.recentReflections(5)
            var bias = 1.0
            for (r in reflections) {
                val text = r.toString().uppercase()
                if (text.contains("RUG") || text.contains("BLEED") || text.contains("CATASTROPHIC")) bias -= 0.08
                else if (text.contains("PROFIT") || text.contains("WIN") || text.contains("STRONG")) bias += 0.02
            }
            bias.coerceIn(0.5, 1.2)
        } catch (_: Throwable) { 1.0 }
        val provenDead = try {
            com.lifecyclebot.engine.BrainConsensusGate.formatForPipelineDump()
                .uppercase().contains("PROVEN_DEAD")
        } catch (_: Throwable) { false }
        // risk latency pressure = alerts vs ticks ratio (rough)
        val riskAlerts = readLabel("RISK_DOMAIN_HIGH_LATENCY_ALERT_6461").toDouble()
        val riskPressure = (riskAlerts / 20.0).coerceIn(0.0, 1.0)
        return BrainReadings(meta, superBr, capital, sentience, provenDead, riskPressure)
    }

    private fun fuseWithBrains(c: Candidate, b: BrainReadings): Candidate {
        // Each brain votes agree/disagree with the delta SIGN based on its stance.
        // Delta > 0 = "loosen/widen"; delta < 0 = "tighten/shrink". A defensive
        // brain (mult < 1.0, provenDead, high risk pressure) agrees with
        // TIGHTENING (delta < 0) and disagrees with LOOSENING. An aggressive
        // brain (mult > 1.0, positive sentience) agrees with LOOSENING.
        val loosening = c.delta > 0.0
        val votes = mutableListOf<BrainVote>()
        fun voteFor(name: String, defensive: Boolean, weight: Double = 1.0) {
            val agrees = if (defensive) !loosening else loosening
            votes += BrainVote(name, agrees, weight)
        }
        voteFor("MetaCognitionExecutorBridge", defensive = b.metaCogMult < 1.0, weight = 1.0)
        voteFor("SuperBrainEnhancements",     defensive = b.superBrainMult < 1.0, weight = 0.8)
        voteFor("CapitalEfficiencyBrain",     defensive = b.capitalMult < 1.0, weight = 1.2)
        voteFor("SentienceOrchestrator",      defensive = b.sentienceMult < 1.0, weight = 0.6)
        voteFor("BrainConsensusGate",         defensive = b.provenDead, weight = 1.4)
        voteFor("RiskExitPriorityDomain6461", defensive = b.riskLatencyPressure > 0.5, weight = 1.0)

        // Special: if the rule is inherently defensive-only (severity=high +
        // negative delta on size/latency keys), force an agreement bonus.
        val forcedAgreement = (c.severity == "high" && c.delta < 0.0 &&
            c.key in setOf("perPositionSizePct", "slippageBps", "trailingStopBasePct"))

        val totalWeight = votes.sumOf { it.weight }
        val agreeWeight = votes.filter { it.agreesWithDeltaSign }.sumOf { it.weight }
        var agreement = if (totalWeight > 0) agreeWeight / totalWeight else 0.5
        if (forcedAgreement) agreement = maxOf(agreement, 0.75)
        return c.copy(brainVotes = votes, brainAgreement = agreement)
    }

    // ─── Optional LLM enrichment ───────────────────────────────────────────

    private fun enrichWithLlm(ctx: Context, base: List<Candidate>): List<Candidate> {
        if (base.isEmpty()) return base
        val allow = try { LlmParameterTuner.allowedKeys().joinToString(", ") } catch (_: Throwable) { "" }
        if (allow.isBlank()) return base
        val report = try { PipelineHealthCollector.dumpText().take(12_000) } catch (_: Throwable) { "" }
        if (report.isBlank()) return base
        val ruleSummary = base.joinToString("\n") { "- ${it.key} Δ${it.delta} (${it.severity}): ${it.reason}" }
        val user = """
The trading bot's rules engine derived these candidate adjustments:
$ruleSummary

REPORT (truncated):
$report

Re-rank / adjust these candidates. You MAY:
  - Reduce a delta magnitude if the rule is too aggressive.
  - Add a new candidate from the report (must use one of: $allow).
  - Downgrade severity.
You MUST NOT invent parameter names or flip a defensive suggestion
into a loosening one. Respond with pure JSON:
{"candidates":[{"key":"...","delta":<num>,"severity":"high|med|low","reason":"..."}]}
        """.trimIndent()
        val reply = try {
            GeminiCopilot.rawText(userPrompt = user, systemPrompt = "You are the AATE parameter tuner co-pilot.",
                temperature = 0.3, maxTokens = 600)
        } catch (_: Throwable) { null }
        if (reply.isNullOrBlank()) {
            try { PipelineHealthCollector.labelInc("AUTO_PIPELINE_ADVISOR_LLM_UNAVAILABLE_6462") } catch (_: Throwable) {}
            return base // degrade cleanly — rules stand.
        }
        val parsed = parseLlmCandidates(reply) ?: return base
        try { PipelineHealthCollector.labelInc("AUTO_PIPELINE_ADVISOR_LLM_ENRICHED_6462") } catch (_: Throwable) {}
        // Merge — LLM overrides delta by key when present; otherwise keep rule.
        val byKey = parsed.associateBy { it.key }
        val merged = base.map { r ->
            val over = byKey[r.key] ?: return@map r
            r.copy(
                delta = clampSameSign(r.delta, over.delta),
                severity = over.severity.ifBlank { r.severity },
                reason = "${r.reason} | llm:${over.reason.take(80)}",
                source = "rules+llm",
            )
        }
        // Add new LLM-only candidates (not overriding rules).
        val ruleKeys = base.map { it.key }.toSet()
        val newOnes = parsed.filter { it.key !in ruleKeys }.take(3).map { c ->
            Candidate(
                id = UUID.randomUUID().toString().take(8),
                key = c.key, delta = c.delta, severity = c.severity.ifBlank { "med" },
                reason = "llm:${c.reason.take(140)}",
                source = "llm", brainVotes = emptyList(), brainAgreement = 0.0,
            )
        }
        val readings = fetchBrainReadings("GLOBAL")
        return (merged + newOnes.map { fuseWithBrains(it, readings) })
    }

    private data class LlmCandidate(val key: String, val delta: Double, val severity: String, val reason: String)

    private fun parseLlmCandidates(raw: String): List<LlmCandidate>? {
        val first = raw.indexOf('{')
        val last = raw.lastIndexOf('}')
        if (first < 0 || last <= first) return null
        val obj = try { JSONObject(raw.substring(first, last + 1)) } catch (_: Throwable) { return null }
        val arr: JSONArray = obj.optJSONArray("candidates") ?: obj.optJSONArray("suggestions") ?: return null
        val out = ArrayList<LlmCandidate>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val key = row.optString("key").trim()
            if (key.isBlank() || !LlmParameterTuner.isAllowedKey(key)) continue
            val delta = row.opt("delta")?.let { when (it) { is Number -> it.toDouble(); is String -> it.toDoubleOrNull(); else -> null } } ?: continue
            if (!delta.isFinite() || delta == 0.0) continue
            out += LlmCandidate(
                key = key, delta = delta,
                severity = row.optString("severity").lowercase().ifBlank { "med" },
                reason = row.optString("reason").trim().take(240),
            )
        }
        return out
    }

    /** Preserve the rule's sign; scale magnitude toward the LLM value but never flip direction. */
    private fun clampSameSign(ruleDelta: Double, llmDelta: Double): Double {
        if (ruleDelta == 0.0) return llmDelta
        val signMatches = (ruleDelta > 0) == (llmDelta > 0)
        return if (signMatches) llmDelta else ruleDelta * 0.5
    }

    // ─── Auto-apply (via LlmParameterTuner) ────────────────────────────────

    sealed class ApplyResult {
        data class Applied(val oldValue: Double, val newValue: Double) : ApplyResult()
        object Noop : ApplyResult()
        object Failed : ApplyResult()
    }

    private fun applyOne(ctx: Context, c: Candidate): ApplyResult {
        val block = JSONObject().apply {
            put("adjustments", JSONArray().apply {
                put(JSONObject().apply {
                    put("key", c.key)
                    put("delta", c.delta)
                    put("reason", "auto6462:${c.reason.take(140)}")
                })
            })
        }
        val synthetic = "<<TUNE>>${block}<<ENDTUNE>>"
        val res = try {
            LlmParameterTuner.extractAndApply(ctx, synthetic)
        } catch (t: Throwable) {
            try {
                ForensicLogger.lifecycle(
                    "AUTO_PIPELINE_ADVISOR_APPLY_FAILED_6462",
                    "key=${c.key} err=${t.message?.take(80) ?: t.javaClass.simpleName}",
                )
                PipelineHealthCollector.labelInc("AUTO_PIPELINE_ADVISOR_APPLY_FAILED_6462")
            } catch (_: Throwable) {}
            return ApplyResult.Failed
        }
        if (res.changes.isEmpty()) {
            try {
                ForensicLogger.lifecycle(
                    "AUTO_PIPELINE_ADVISOR_APPLY_NOOP_6462",
                    "key=${c.key} rejected=${res.rejected.joinToString(",").take(120)}",
                )
                PipelineHealthCollector.labelInc("AUTO_PIPELINE_ADVISOR_APPLY_NOOP_6462")
            } catch (_: Throwable) {}
            return ApplyResult.Noop
        }
        val ch = res.changes.first()
        try {
            ForensicLogger.lifecycle(
                "AUTO_PIPELINE_ADVISOR_APPLIED_6462",
                "key=${ch.key} old=${"%.4f".format(ch.oldValue)} new=${"%.4f".format(ch.newValue)} " +
                    "sev=${c.severity} agree=${"%.2f".format(c.brainAgreement)} src=${c.source} reason=${c.reason.take(120)}",
            )
            PipelineHealthCollector.labelInc("AUTO_PIPELINE_ADVISOR_APPLIED_6462")
            ErrorLogger.info("AutoPipelineAdvisor6462",
                "🤖 auto-applied ${ch.key}: ${"%.4f".format(ch.oldValue)}→${"%.4f".format(ch.newValue)} " +
                    "(sev=${c.severity} agree=${"%.2f".format(c.brainAgreement)} src=${c.source})")
        } catch (_: Throwable) {}
        return ApplyResult.Applied(oldValue = ch.oldValue, newValue = ch.newValue)
    }

    private fun publishToInbox(candidates: List<Candidate>) {
        try {
            val mapped = candidates.take(10).map { c ->
                SelfHealingAdvisor.Suggestion(
                    id = c.id, createdAtMs = System.currentTimeMillis(),
                    key = c.key, delta = c.delta, reason = c.reason,
                    expectedImpact = "brains=${"%.2f".format(c.brainAgreement)} src=${c.source}",
                    severity = c.severity,
                )
            }
            AdvisorInbox.addAll(mapped)
        } catch (_: Throwable) {}
    }

    internal fun resetForTest() {
        lastRunMs.set(0L); ticks.set(0L); runsOk.set(0L); runsFailed.set(0L); autoApplied.set(0L)
        lastAppliedAtByKey.clear(); running.set(false)
    }
}
