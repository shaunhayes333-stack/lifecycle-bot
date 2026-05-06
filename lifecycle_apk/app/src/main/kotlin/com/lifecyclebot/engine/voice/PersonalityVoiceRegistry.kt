package com.lifecyclebot.engine.voice

import android.content.Context
import com.lifecyclebot.engine.ErrorLogger
import org.json.JSONObject

/**
 * V5.9.495z18 — Central PersonalityVoiceRegistry.
 *
 * One source of truth for every AATE voice persona. Each profile carries:
 *   • voice_id (default + backup)         — operator pastes from ElevenLabs Voice Library
 *                                            or generates via Voice Design (see ElevenLabsApi)
 *   • model routing (fast/premium/narration)
 *   • voice_settings (stability, similarity, style, speed, speaker_boost)
 *   • emotional_range tag (calm/excited/serious/...)
 *   • safe_phrase_bank   — original catchphrases (no real-person quotes)
 *   • forbidden_phrase_bank — must never appear in generated TTS
 *   • use_case_tags      — hint the event router which events this persona owns
 *   • cache_policy       — aggressive (long-form static lines) | normal | none (live tickers)
 *   • credit_budget_policy — max chars/day soft-limit + per-event ceiling
 *
 * SAFETY: All 8 default personas are FICTIONAL ARCHETYPES. None impersonate
 * real political figures, public figures or living people. RALLY_COMMANDER and
 * PROFESSOR_STATESMAN are parody-safe inspirations only — original wording,
 * voice IDs left blank for the operator to plug in their own ElevenLabs
 * generated/Voice-Library voices that DO NOT clone real people.
 *
 * Voice IDs default to "" (empty) — operator must paste a real voice_id from
 * Voice Library or auto-generate via Voice Design before the persona will speak.
 */
object PersonalityVoiceRegistry {

    private const val PREFS = "personality_voice_registry_v1"
    private const val TAG = "PersonalityVoiceRegistry"

    enum class CachePolicy { AGGRESSIVE, NORMAL, NONE }
    enum class EmotionalRange { CALM, NEUTRAL, EXCITED, SERIOUS, GROUNDING, BOLD, REASSURING, ROBOTIC }

    /** Recommended ElevenLabs models. */
    object Models {
        const val FAST = "eleven_flash_v2_5"      // sub-second latency, live alerts
        const val PREMIUM = "eleven_v3"            // emotion-tag aware, demos
        const val NARRATION = "eleven_multilingual_v2"  // long-form, education
    }

    data class CreditBudget(
        val maxCharsPerDay: Int,
        val maxCharsPerEvent: Int,
    )

    data class VoiceProfile(
        val personaId: String,
        val displayName: String,
        val description: String,

        // Voice IDs — operator overrides via Settings or first-launch Voice Design.
        val defaultVoiceId: String,
        val backupVoiceId: String,

        // Model routing.
        val fastModelId: String,
        val premiumModelId: String,
        val narrationModelId: String,

        // Delivery profile.
        val emotionalRange: EmotionalRange,
        val defaultStability: Double,
        val defaultSimilarityBoost: Double,
        val defaultStyle: Double,
        val defaultSpeed: Double,
        val speakerBoostEnabled: Boolean,

        // Phrase governance.
        val safePhraseBank: List<String>,
        val forbiddenPhraseBank: List<String>,

        // Routing hints.
        val priorityEventRules: List<String>,
        val useCaseTags: List<String>,

        // Cost controls.
        val cachePolicy: CachePolicy,
        val creditBudgetPolicy: CreditBudget,
    )

    // ── DEFAULTS ──────────────────────────────────────────────────────────────

    val CALM_ANALYST = VoiceProfile(
        personaId = "CALM_ANALYST",
        displayName = "Calm Analyst",
        description = "Controlled, neutral, accurate. Default voice for routine bot status and market regime.",
        defaultVoiceId = "",
        backupVoiceId = "",
        fastModelId = Models.FAST,
        premiumModelId = Models.NARRATION,
        narrationModelId = Models.NARRATION,
        emotionalRange = EmotionalRange.NEUTRAL,
        defaultStability = 0.75,
        defaultSimilarityBoost = 0.75,
        defaultStyle = 0.20,
        defaultSpeed = 1.0,
        speakerBoostEnabled = true,
        safePhraseBank = listOf(
            "Scanning the market.",
            "Conditions are stable.",
            "Watching for confirmation.",
            "Holding position.",
            "No qualifying setups right now.",
        ),
        forbiddenPhraseBank = emptyList(),
        priorityEventRules = listOf("token_discovered", "buy_attempt_started", "sell_attempt_started"),
        useCaseTags = listOf("status", "market_regime", "routine"),
        cachePolicy = CachePolicy.AGGRESSIVE,
        creditBudgetPolicy = CreditBudget(maxCharsPerDay = 4_000, maxCharsPerEvent = 200),
    )

    val HYPE_MOONSHOT = VoiceProfile(
        personaId = "HYPE_MOONSHOT",
        displayName = "Hype Moonshot",
        description = "Excited but not reckless. Reserved for strong signals and wins.",
        defaultVoiceId = "",
        backupVoiceId = "",
        fastModelId = Models.FAST,
        premiumModelId = Models.PREMIUM,
        narrationModelId = Models.NARRATION,
        emotionalRange = EmotionalRange.EXCITED,
        defaultStability = 0.45,
        defaultSimilarityBoost = 0.80,
        defaultStyle = 0.75,
        defaultSpeed = 1.05,
        speakerBoostEnabled = true,
        safePhraseBank = listOf(
            "Strong setup. Decision gate cleared.",
            "Big take profit. Locked in.",
            "Moonshot candidate. Watching it climb.",
            "Clean entry. Liquidity behaved.",
            "That one paid.",
        ),
        forbiddenPhraseBank = emptyList(),
        priorityEventRules = listOf("fdg_pass_strong", "take_profit_hit", "sell_confirmed"),
        useCaseTags = listOf("hype", "win", "moonshot"),
        cachePolicy = CachePolicy.NORMAL,
        creditBudgetPolicy = CreditBudget(maxCharsPerDay = 3_000, maxCharsPerEvent = 180),
    )

    val FORENSIC_ALERT = VoiceProfile(
        personaId = "FORENSIC_ALERT",
        displayName = "Forensic Alert",
        description = "Serious, clear, short. For wallet mismatches, failed sells, bridge anomalies.",
        defaultVoiceId = "",
        backupVoiceId = "",
        fastModelId = Models.FAST,
        premiumModelId = Models.NARRATION,
        narrationModelId = Models.NARRATION,
        emotionalRange = EmotionalRange.SERIOUS,
        defaultStability = 0.85,
        defaultSimilarityBoost = 0.75,
        defaultStyle = 0.10,
        defaultSpeed = 1.0,
        speakerBoostEnabled = true,
        safePhraseBank = listOf(
            "Wallet reconciliation mismatch detected.",
            "Sell did not settle. Verifying on chain.",
            "Bridge leg completed. Target token not yet confirmed.",
            "Unmanaged token detected in host wallet.",
            "Tracker is being restored from wallet truth.",
        ),
        forbiddenPhraseBank = listOf(
            "lol", "haha", "lmao", "yay", "woohoo",  // never sound flippant on a forensic alert
        ),
        priorityEventRules = listOf(
            "usdc_bridge_completed",
            "target_token_missing_after_bridge",
            "wallet_holds_unmanaged_token",
            "sell_failed",
        ),
        useCaseTags = listOf("forensic", "alert", "incident"),
        cachePolicy = CachePolicy.NORMAL,
        creditBudgetPolicy = CreditBudget(maxCharsPerDay = 5_000, maxCharsPerEvent = 240),
    )

    val LOSS_RECOVERY_COACH = VoiceProfile(
        personaId = "LOSS_RECOVERY_COACH",
        displayName = "Loss Recovery Coach",
        description = "Calm, grounding, educational. For stop-losses, rug avoidance, toxic-mode freezes.",
        defaultVoiceId = "",
        backupVoiceId = "",
        fastModelId = Models.NARRATION,
        premiumModelId = Models.NARRATION,
        narrationModelId = Models.NARRATION,
        emotionalRange = EmotionalRange.GROUNDING,
        defaultStability = 0.85,
        defaultSimilarityBoost = 0.75,
        defaultStyle = 0.15,
        defaultSpeed = 0.95,
        speakerBoostEnabled = true,
        safePhraseBank = listOf(
            "Stop-loss filled. Damage was contained.",
            "A controlled loss is part of a winning process.",
            "Toxic-mode freeze engaged. Capital is preserved while we wait.",
            "Risk avoided is profit earned.",
            "The system is updating its priors from this outcome.",
        ),
        forbiddenPhraseBank = emptyList(),
        priorityEventRules = listOf("stop_loss_hit", "rugcheck_rejection", "toxic_mode_freeze"),
        useCaseTags = listOf("loss", "education", "recovery"),
        cachePolicy = CachePolicy.AGGRESSIVE,
        creditBudgetPolicy = CreditBudget(maxCharsPerDay = 4_000, maxCharsPerEvent = 280),
    )

    val RALLY_COMMANDER = VoiceProfile(
        personaId = "RALLY_COMMANDER",
        displayName = "Rally Commander",
        description = "Bold, brash, theatrical fictional archetype. Hype demos and big wins. " +
                      "NOT a real-person clone or political figure.",
        defaultVoiceId = "",
        backupVoiceId = "",
        fastModelId = Models.FAST,
        premiumModelId = Models.PREMIUM,
        narrationModelId = Models.NARRATION,
        emotionalRange = EmotionalRange.BOLD,
        defaultStability = 0.40,
        defaultSimilarityBoost = 0.80,
        defaultStyle = 0.85,
        defaultSpeed = 1.05,
        speakerBoostEnabled = true,
        safePhraseBank = listOf(
            "This setup is looking absolutely massive.",
            "That entry gate was clean. Very clean.",
            "The liquidity is beautiful, the chart is behaving, and the bot likes it.",
            "We do not chase garbage. We let garbage reject itself.",
            "That was a strong pass from the decision gate.",
            "This token tried to be clever. AATE was more clever.",
            "The wallet check is complete. We actually hold the token.",
            "Bridge leg completed. Now confirm the target token, no assumptions.",
            "Unmanaged token detected. That is not acceptable. Tracking starts now.",
            "This is why we reconcile the wallet. Internal memory is not enough.",
            "Profit secured. Very strong exit.",
            "Stop-loss hit. Controlled damage. We live to trade the next one.",
            "The chart said maybe. The forensic layer said no.",
            "No wallet confirmation, no celebration.",
            "We are not pretending USDC is the target token.",
            "The bot found the problem and put it on the board.",
            "That was not a buy until the wallet proved it.",
            "AATE wants facts, not vibes.",
            "This one passed the hype test, but failed the safety test.",
            "Big signal, big caution, smart execution.",
        ),
        // Compliance — these MUST never be generated through this persona.
        forbiddenPhraseBank = listOf(
            "make america", "drain the swamp", "fake news", "build the wall",
            "tremendous", "huge, just huge",  // signature phrases of a real political figure
            // Generic political slogans:
            "make ", " again", "we will win again",
            // Real-person names:
            "donald", "trump", "obama", "biden", "harris", "vance",
        ),
        priorityEventRules = listOf("fdg_pass_strong", "take_profit_hit", "sell_confirmed", "investor_demo"),
        useCaseTags = listOf("hype", "demo", "wins", "celebration", "parody_safe"),
        cachePolicy = CachePolicy.AGGRESSIVE,
        creditBudgetPolicy = CreditBudget(maxCharsPerDay = 3_500, maxCharsPerEvent = 220),
    )

    val PROFESSOR_STATESMAN = VoiceProfile(
        personaId = "PROFESSOR_STATESMAN",
        displayName = "Professor Statesman",
        description = "Calm, articulate, warm fictional archetype. Education, explanation, daily summaries. " +
                      "NOT a real-person clone or political figure.",
        defaultVoiceId = "",
        backupVoiceId = "",
        fastModelId = Models.FAST,
        premiumModelId = Models.PREMIUM,
        narrationModelId = Models.NARRATION,
        emotionalRange = EmotionalRange.REASSURING,
        defaultStability = 0.85,
        defaultSimilarityBoost = 0.75,
        defaultStyle = 0.30,
        defaultSpeed = 0.95,
        speakerBoostEnabled = true,
        safePhraseBank = listOf(
            "Let's look at what the data is actually telling us.",
            "The important thing here is not the hype. It is confirmation.",
            "A good trade begins with patience and ends with discipline.",
            "The wallet is the source of truth.",
            "We do not assume the position exists. We verify it.",
            "The bridge completed, but the final token confirmation is still required.",
            "That distinction matters.",
            "AATE rejected this token because the risk was not justified.",
            "This was not a missed opportunity. This was risk avoided.",
            "The system is learning from the result.",
            "A controlled loss is part of a winning process.",
            "The strongest bots are not emotional. They are accountable.",
            "Before we celebrate, we reconcile.",
            "The forensic layer has identified a mismatch.",
            "The correct response is not panic. It is verification.",
            "This trade needs evidence, not excitement.",
            "The exit was disciplined.",
            "This is how we protect the wallet.",
            "AATE is moving from reaction to understanding.",
            "The lesson is simple: confirmation beats assumption.",
        ),
        forbiddenPhraseBank = listOf(
            "yes we can", "hope and change", "fired up, ready to go",  // real-person signature lines
            "barack", "obama", "michelle",
        ),
        priorityEventRules = listOf(
            "active_position_restored",
            "rugcheck_rejection",
            "stop_loss_hit",
            "daily_summary",
            "investor_demo",
        ),
        useCaseTags = listOf("education", "summary", "explanation", "investor", "parody_safe"),
        cachePolicy = CachePolicy.AGGRESSIVE,
        creditBudgetPolicy = CreditBudget(maxCharsPerDay = 6_000, maxCharsPerEvent = 320),
    )

    val AATE_DEFAULT_SENTIENT = VoiceProfile(
        personaId = "AATE_DEFAULT_SENTIENT",
        displayName = "AATE (Default Sentient)",
        description = "Confident AI trading agent. The bot's own voice for general-purpose narration.",
        defaultVoiceId = "",
        backupVoiceId = "",
        fastModelId = Models.FAST,
        premiumModelId = Models.PREMIUM,
        narrationModelId = Models.NARRATION,
        emotionalRange = EmotionalRange.NEUTRAL,
        defaultStability = 0.60,
        defaultSimilarityBoost = 0.80,
        defaultStyle = 0.40,
        defaultSpeed = 1.0,
        speakerBoostEnabled = true,
        safePhraseBank = listOf(
            "Position confirmed. Tracking is live.",
            "I see what you see. The numbers agree.",
            "Holding. Watching the tape.",
            "Decision made. Executing.",
        ),
        forbiddenPhraseBank = emptyList(),
        priorityEventRules = listOf("token_discovered", "target_token_buy_confirmed", "sell_confirmed"),
        useCaseTags = listOf("default", "general", "sentient"),
        cachePolicy = CachePolicy.NORMAL,
        creditBudgetPolicy = CreditBudget(maxCharsPerDay = 5_000, maxCharsPerEvent = 200),
    )

    val EMERGENCY_SYSTEM = VoiceProfile(
        personaId = "EMERGENCY_SYSTEM",
        displayName = "Emergency System",
        description = "Short, robotic, unmistakable. Severe errors only.",
        defaultVoiceId = "",
        backupVoiceId = "",
        fastModelId = Models.FAST,
        premiumModelId = Models.FAST,
        narrationModelId = Models.FAST,
        emotionalRange = EmotionalRange.ROBOTIC,
        defaultStability = 1.0,
        defaultSimilarityBoost = 0.90,
        defaultStyle = 0.0,
        defaultSpeed = 1.0,
        speakerBoostEnabled = true,
        safePhraseBank = listOf(
            "Critical fault.",
            "Wallet authentication failure.",
            "Trading halted.",
            "Manual intervention required.",
        ),
        forbiddenPhraseBank = emptyList(),
        priorityEventRules = listOf("critical_fault", "trading_halted"),
        useCaseTags = listOf("emergency", "critical"),
        cachePolicy = CachePolicy.AGGRESSIVE,
        creditBudgetPolicy = CreditBudget(maxCharsPerDay = 2_000, maxCharsPerEvent = 100),
    )

    /** Stable order — UI lists in this order. */
    val DEFAULTS: List<VoiceProfile> = listOf(
        AATE_DEFAULT_SENTIENT,
        CALM_ANALYST,
        HYPE_MOONSHOT,
        FORENSIC_ALERT,
        LOSS_RECOVERY_COACH,
        RALLY_COMMANDER,
        PROFESSOR_STATESMAN,
        EMERGENCY_SYSTEM,
    )

    private val byId: Map<String, VoiceProfile> = DEFAULTS.associateBy { it.personaId }

    fun all(): List<VoiceProfile> = DEFAULTS

    /** Returns the merged profile (defaults + any operator overrides from prefs). */
    fun get(ctx: Context, personaId: String): VoiceProfile? {
        val base = byId[personaId] ?: return null
        return applyOverrides(ctx, base)
    }

    fun getOrDefault(ctx: Context, personaId: String): VoiceProfile =
        get(ctx, personaId) ?: applyOverrides(ctx, AATE_DEFAULT_SENTIENT)

    // ── Overrides (operator pastes voice_id from ElevenLabs library or design) ────

    private fun applyOverrides(ctx: Context, base: VoiceProfile): VoiceProfile {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return base.copy(
            defaultVoiceId = p.getString("voice_id_${base.personaId}", base.defaultVoiceId).orEmpty(),
            backupVoiceId  = p.getString("backup_voice_id_${base.personaId}", base.backupVoiceId).orEmpty(),
            defaultStability = p.getFloat("stability_${base.personaId}", base.defaultStability.toFloat()).toDouble(),
            defaultSimilarityBoost = p.getFloat("sim_${base.personaId}", base.defaultSimilarityBoost.toFloat()).toDouble(),
            defaultStyle = p.getFloat("style_${base.personaId}", base.defaultStyle.toFloat()).toDouble(),
            defaultSpeed = p.getFloat("speed_${base.personaId}", base.defaultSpeed.toFloat()).toDouble(),
        )
    }

    fun setVoiceId(ctx: Context, personaId: String, voiceId: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("voice_id_$personaId", voiceId).apply()
        ErrorLogger.info(TAG, "🔊 voice_id[$personaId] = ${voiceId.take(12)}…")
    }

    fun setBackupVoiceId(ctx: Context, personaId: String, voiceId: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("backup_voice_id_$personaId", voiceId).apply()
    }

    fun setVoiceSettings(
        ctx: Context, personaId: String,
        stability: Double? = null, similarity: Double? = null,
        style: Double? = null, speed: Double? = null,
    ) {
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        stability?.let  { e.putFloat("stability_$personaId", it.toFloat()) }
        similarity?.let { e.putFloat("sim_$personaId", it.toFloat()) }
        style?.let      { e.putFloat("style_$personaId", it.toFloat()) }
        speed?.let      { e.putFloat("speed_$personaId", it.toFloat()) }
        e.apply()
    }

    /** Per-persona enable toggle (mute one without muting all). */
    fun isEnabled(ctx: Context, personaId: String): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled_$personaId", true)

    fun setEnabled(ctx: Context, personaId: String, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled_$personaId", on).apply()
    }

    // ── Global voice modes ────────────────────────────────────────────────────

    enum class VoiceMode { CRITICAL_ONLY, FULL_PERSONALITY, MUTED }

    fun voiceMode(ctx: Context): VoiceMode {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("voice_mode", VoiceMode.FULL_PERSONALITY.name)
        return runCatching { VoiceMode.valueOf(raw ?: "") }.getOrDefault(VoiceMode.FULL_PERSONALITY)
    }

    fun setVoiceMode(ctx: Context, mode: VoiceMode) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("voice_mode", mode.name).apply()
        ErrorLogger.info(TAG, "🔊 voice mode → $mode")
    }

    fun usePremiumForDemos(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("premium_demos", true)

    fun setUsePremiumForDemos(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("premium_demos", on).apply()
    }

    fun useFlashForLive(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("flash_live", true)

    fun setUseFlashForLive(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("flash_live", on).apply()
    }

    /** Quiet-hours window stored as `HH:mm-HH:mm` in 24h local time. Empty = always on. */
    fun quietHours(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("quiet_hours", "").orEmpty()

    fun setQuietHours(ctx: Context, value: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("quiet_hours", value).apply()
    }

    fun isWithinQuietHours(ctx: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val w = quietHours(ctx).takeIf { it.isNotBlank() } ?: return false
        val parts = w.split("-")
        if (parts.size != 2) return false
        val start = parseHHmm(parts[0]) ?: return false
        val end   = parseHHmm(parts[1]) ?: return false
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
        val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        return if (start <= end) nowMin in start..end else (nowMin >= start || nowMin <= end)
    }

    private fun parseHHmm(s: String): Int? {
        val parts = s.trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    /** JSON snapshot of all profiles + overrides — used by Diagnostics screen. */
    fun snapshotJson(ctx: Context): JSONObject {
        val o = JSONObject()
        o.put("voice_mode", voiceMode(ctx).name)
        o.put("premium_demos", usePremiumForDemos(ctx))
        o.put("flash_live", useFlashForLive(ctx))
        o.put("quiet_hours", quietHours(ctx))
        val arr = org.json.JSONArray()
        for (p in DEFAULTS) {
            val merged = applyOverrides(ctx, p)
            arr.put(JSONObject().apply {
                put("persona_id", merged.personaId)
                put("display_name", merged.displayName)
                put("voice_id", merged.defaultVoiceId)
                put("backup_voice_id", merged.backupVoiceId)
                put("fast_model", merged.fastModelId)
                put("premium_model", merged.premiumModelId)
                put("stability", merged.defaultStability)
                put("similarity_boost", merged.defaultSimilarityBoost)
                put("style", merged.defaultStyle)
                put("speed", merged.defaultSpeed)
                put("enabled", isEnabled(ctx, merged.personaId))
                put("emotional_range", merged.emotionalRange.name)
                put("cache_policy", merged.cachePolicy.name)
                put("max_chars_per_day", merged.creditBudgetPolicy.maxCharsPerDay)
            })
        }
        o.put("personas", arr)
        return o
    }
}
