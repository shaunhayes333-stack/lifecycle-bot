package com.lifecyclebot.engine

import android.content.Context
import com.lifecyclebot.data.ConfigStore
import com.lifecyclebot.engine.truth.AdvisorDecisionHistory6463
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8353 — ADVISOR KEY AUTHORITY: `minLiquidityUsd` and `walletReserveSol` are owner-only.
 *
 * 8351 live run: the self-healing advisor auto-applied minLiquidityUsd 8000 → 10000 with a rationale that
 * said restrictive liquidity blocks were preventing trades — a tightening justified by the harm of
 * tightening. The owner never granted the LLM authority over the liquidity floor or the wallet reserve.
 *
 * Both keys are removed from LlmParameterTuner's allowlist (so neither advisor, nor a native <<TUNE>>
 * block, nor the regression monitor can write them). A proposal that names one of them is kept as an
 * advisory-only row (label ADVISOR_KEY_ADVISORY_ONLY_8353_<key>) and is never applied.
 *
 * One-shot restore: the Advisor Timeline (AdvisorDecisionHistory6463) is an in-memory ring, so after a
 * restart the only persisted before/after record of an auto-apply is LlmParameterTuner's own line in the
 * ErrorLogger database: "🎛️ LLM tune: <key> <old> → <new> (<reason>)" (component "LlmTuner"). The newest
 * record for each key is the prior; it is restored once, and only while the advisor's written value is
 * still the live value (an owner change since then is left alone). The restore is remembered in
 * SharedPreferences so it never repeats.
 */
object AdvisorKeyAuthority8353 {

    private const val KEY_MIN_LIQ_8353 = "minLiquidityUsd"
    private const val KEY_RESERVE_8353 = "walletReserveSol"
    /** V5.0.8363 — slippage is out of the advisor's hands (it fed partial-TP / orphan sells; a tightening got 0.75 forced). */
    private const val KEY_SLIPPAGE_8363 = "slippageBps"

    /** Keys the advisor may discuss but never write. */
    val ADVISORY_ONLY_KEYS_8353: List<String> = listOf(KEY_MIN_LIQ_8353, KEY_RESERVE_8353, KEY_SLIPPAGE_8363)

    private const val PREFS_8353 = "aate_8353_advisor_restore"
    private const val PREF_COUNT_8353 = "restored_count"
    private const val TUNER_COMPONENT_8353 = "LlmTuner"
    private const val TUNE_LOG_SCAN_8353 = 200
    private const val TAG_8353 = "AdvisorKeyAuthority8353"

    private val restored8353 = AtomicLong(0L)
    @Volatile private var checkedThisProcess8353 = false

    // ── Pure classification ─────────────────────────────────────────────────

    fun isAdvisoryOnly8353(key: String): Boolean = key.trim() in ADVISORY_ONLY_KEYS_8353

    /** Pure: the proposal-row label for an advisory-only key; null when the key is an ordinary tunable. */
    fun advisoryOnlyLabel8353(key: String): String? =
        if (isAdvisoryOnly8353(key)) "ADVISOR_KEY_ADVISORY_ONLY_8353_" + key.trim() else null

    /** Records an advisory-only proposal row (counted + forensic); the value is never written. */
    fun noteAdvisoryOnly8353(key: String, delta: Double, reason: String) {
        val label = advisoryOnlyLabel8353(key) ?: return
        try {
            PipelineHealthCollector.labelInc(label)
            ForensicLogger.lifecycle(
                label,
                "key=${key.trim()} delta=${fmt8353(delta)} applied=false authority=owner reason=${reason.take(120)}",
            )
        } catch (_: Throwable) {}
    }

    // ── Pure restore decision ───────────────────────────────────────────────

    enum class RestoreKind8353 { RESTORE, NO_PRIOR, ALREADY_DONE, OWNER_CHANGED }

    data class RestoreDecision8353(val kind: RestoreKind8353, val key: String, val from: Double, val to: Double)

    /** A persisted before/after record of one auto-applied change. */
    data class TuneRecord8353(val key: String, val oldValue: Double, val newValue: Double)

    /**
     * Pure. `priorValue`/`appliedValue` come from the newest auto-applied record for `key` (null when none
     * is recorded). The restore happens once, and only while the live value is still what the advisor wrote.
     */
    fun decideRestore8353(
        key: String,
        currentValue: Double,
        priorValue: Double?,
        appliedValue: Double?,
        alreadyDone: Boolean,
    ): RestoreDecision8353 {
        if (alreadyDone) return RestoreDecision8353(RestoreKind8353.ALREADY_DONE, key, currentValue, currentValue)
        if (priorValue == null || !priorValue.isFinite()) {
            return RestoreDecision8353(RestoreKind8353.NO_PRIOR, key, currentValue, currentValue)
        }
        val applied = appliedValue
        if (applied != null && applied.isFinite() && !near8353(currentValue, applied)) {
            return RestoreDecision8353(RestoreKind8353.OWNER_CHANGED, key, currentValue, currentValue)
        }
        if (near8353(currentValue, priorValue)) {
            return RestoreDecision8353(RestoreKind8353.OWNER_CHANGED, key, currentValue, currentValue)
        }
        return RestoreDecision8353(RestoreKind8353.RESTORE, key, currentValue, priorValue)
    }

    private val TUNE_LINE_RE_8353 = Regex("LLM tune: (\\w+) (\\S+) → (\\S+)")

    /** Pure: parses LlmParameterTuner's persisted "🎛️ LLM tune: key old → new (reason)" line. */
    fun parseTuneLine8353(message: String): TuneRecord8353? {
        val m = TUNE_LINE_RE_8353.find(message) ?: return null
        val key = m.groupValues[1]
        val old = m.groupValues[2].toDoubleOrNull() ?: return null
        val new = m.groupValues[3].toDoubleOrNull() ?: return null
        if (!old.isFinite() || !new.isFinite()) return null
        return TuneRecord8353(key, old, new)
    }

    /**
     * Pure: the newest auto-applied record for `key`. The Advisor Timeline (newest first) is read first,
     * then the persisted tuner lines (newest first). Null when neither holds a prior for the key.
     */
    fun priorFor8353(
        key: String,
        timelineNewestFirst: List<AdvisorDecisionHistory6463.Decision>,
        tuneLinesNewestFirst: List<String>,
    ): TuneRecord8353? {
        for (d in timelineNewestFirst) {
            if (d.key == key && d.action == AdvisorDecisionHistory6463.Action.AUTO_APPLIED &&
                d.oldValue.isFinite() && d.newValue.isFinite()
            ) return TuneRecord8353(key, d.oldValue, d.newValue)
        }
        for (line in tuneLinesNewestFirst) {
            val rec = parseTuneLine8353(line) ?: continue
            if (rec.key == key) return rec
        }
        return null
    }

    // ── One-shot restore (advisor init; off the main thread) ────────────────

    /** Called from the advisor's tick init (runs on Dispatchers.IO after ErrorLogger.init). Once per process. */
    fun restoreOnce8353(ctx: Context) {
        if (checkedThisProcess8353) return
        checkedThisProcess8353 = true
        try {
            val prefs = ctx.getSharedPreferences(PREFS_8353, Context.MODE_PRIVATE)
            restored8353.set(prefs.getInt(PREF_COUNT_8353, 0).toLong())
            val timeline: List<AdvisorDecisionHistory6463.Decision> =
                try { AdvisorDecisionHistory6463.recent() } catch (_: Throwable) { emptyList<AdvisorDecisionHistory6463.Decision>() }
            val tuneLines: List<String> = try {
                ErrorLogger.getLogsByComponent(TUNER_COMPONENT_8353, TUNE_LOG_SCAN_8353).map { it.message }
            } catch (_: Throwable) { emptyList<String>() }
            var cfg = ConfigStore.load(ctx)
            for (key in ADVISORY_ONLY_KEYS_8353) {
                val done = prefs.getBoolean("restored_" + key, false)
                val current = currentValue8363(cfg, key)
                val rec = if (done) null else priorFor8353(key, timeline, tuneLines)
                val decision = decideRestore8353(key, current, rec?.oldValue, rec?.newValue, done)
                when (decision.kind) {
                    RestoreKind8353.ALREADY_DONE -> {}
                    RestoreKind8353.NO_PRIOR -> {
                        PipelineHealthCollector.labelInc("ADVISOR_RESTORE_NO_PRIOR_8353")
                        PipelineHealthCollector.labelInc("ADVISOR_RESTORE_NO_PRIOR_8353_" + key)
                    }
                    RestoreKind8353.OWNER_CHANGED -> {
                        PipelineHealthCollector.labelInc("ADVISOR_RESTORE_OWNER_CHANGED_8353_" + key)
                        ForensicLogger.lifecycle(
                            "ADVISOR_RESTORE_OWNER_CHANGED_8353",
                            "key=$key current=${fmt8353(current)} prior=${fmt8353(rec?.oldValue ?: Double.NaN)} " +
                                "advisorWrote=${fmt8353(rec?.newValue ?: Double.NaN)} action=left_alone",
                        )
                        prefs.edit().putBoolean("restored_" + key, true).apply()
                    }
                    RestoreKind8353.RESTORE -> {
                        cfg = withValue8363(cfg, key, decision.to)
                        ConfigStore.save(ctx, cfg)
                        val n = restored8353.incrementAndGet()
                        prefs.edit().putBoolean("restored_" + key, true).putInt(PREF_COUNT_8353, n.toInt()).apply()
                        val label = "ADVISOR_RESTORED_8353_${key}_${fmt8353(decision.from)}_${fmt8353(decision.to)}"
                        PipelineHealthCollector.labelInc(label)
                        ForensicLogger.lifecycle(
                            "ADVISOR_RESTORED_8353",
                            "key=$key from=${fmt8353(decision.from)} to=${fmt8353(decision.to)} " +
                                "source=${if (timeline.any { it.key == key }) "timeline" else "tuner_log"} authority=owner once=true",
                        )
                        ErrorLogger.info(TAG_8353, "restored $key ${fmt8353(decision.from)} -> ${fmt8353(decision.to)} (advisor never had authority)")
                        try {
                            AdvisorDecisionHistory6463.record(
                                AdvisorDecisionHistory6463.Decision(
                                    atMs = System.currentTimeMillis(), key = key,
                                    delta = decision.to - decision.from, severity = "high", source = "owner_restore_8353",
                                    action = AdvisorDecisionHistory6463.Action.REVERTED, brainAgreement = 1.0,
                                    votes = emptyList(), reason = "8353 owner-only key restored to pre-advisor value",
                                    oldValue = decision.from, newValue = decision.to,
                                )
                            )
                        } catch (_: Throwable) {}
                    }
                }
            }
        } catch (t: Throwable) {
            try { ErrorLogger.warn(TAG_8353, "restore check failed: ${t.message}") } catch (_: Throwable) {}
        }
    }

    /** V5.0.8363 — the live value of an advisory-only key. */
    private fun currentValue8363(cfg: com.lifecyclebot.data.BotConfig, key: String): Double = when (key) {
        KEY_MIN_LIQ_8353 -> cfg.minLiquidityUsd
        KEY_SLIPPAGE_8363 -> cfg.slippageBps.toDouble()
        else -> cfg.walletReserveSol
    }

    /** V5.0.8363 — [cfg] with an advisory-only key restored to [v]. */
    private fun withValue8363(cfg: com.lifecyclebot.data.BotConfig, key: String, v: Double): com.lifecyclebot.data.BotConfig = when (key) {
        KEY_MIN_LIQ_8353 -> cfg.copy(minLiquidityUsd = v)
        KEY_SLIPPAGE_8363 -> cfg.copy(slippageBps = Math.round(v).toInt())
        else -> cfg.copy(walletReserveSol = v)
    }

    /** Appended to the advisor status line. */
    fun statusSuffix8353(): String =
        "advisoryOnly=[${ADVISORY_ONLY_KEYS_8353.joinToString(",")}] restored8353=${restored8353.get()}"

    private fun near8353(a: Double, b: Double): Boolean = kotlin.math.abs(a - b) <= 1e-6 * maxOf(1.0, kotlin.math.abs(b))

    private fun fmt8353(v: Double): String {
        if (!v.isFinite()) return "nan"
        return if (v == v.toLong().toDouble() && kotlin.math.abs(v) < 1e12) v.toLong().toString()
        else String.format(java.util.Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
    }

    internal fun resetForTest() { checkedThisProcess8353 = false; restored8353.set(0L) }
}
