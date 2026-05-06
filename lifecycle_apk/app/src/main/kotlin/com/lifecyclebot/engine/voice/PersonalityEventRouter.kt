package com.lifecyclebot.engine.voice

/**
 * V5.9.495z18 — Event → Persona routing.
 *
 * A flat enum + table that maps every speakable AATE event to:
 *   • primary persona
 *   • optional persona (for "full personality" mode)
 *   • severity (drives whether voice plays in CRITICAL_ONLY mode)
 *   • model tier (fast / premium / narration)
 *   • optional fixed text (forensic events have mandatory wording so the
 *     bot never sounds flippant when funds are at risk).
 *
 * Pure data — no side effects. Resolved by VoiceManager.speakEvent().
 */
object PersonalityEventRouter {

    enum class Severity { ROUTINE, NOTICE, ALERT, CRITICAL }
    enum class ModelTier { FAST, PREMIUM, NARRATION }

    enum class Event(
        val severity: Severity,
        val primary: String,                  // persona_id
        val optionalFull: String? = null,     // persona_id used in FULL_PERSONALITY mode
        val tier: ModelTier = ModelTier.FAST,
        val fixedText: String? = null,
    ) {
        // ── Discovery / scanning ──────────────────────────────────────────────
        TOKEN_DISCOVERED(Severity.ROUTINE, "CALM_ANALYST", "AATE_DEFAULT_SENTIENT"),

        // ── Decision-gate signals ─────────────────────────────────────────────
        FDG_PASS_STRONG(Severity.NOTICE, "HYPE_MOONSHOT", "RALLY_COMMANDER"),
        BUY_ATTEMPT_STARTED(Severity.ROUTINE, "CALM_ANALYST"),

        // ── Bridge / wallet truth ─────────────────────────────────────────────
        USDC_BRIDGE_COMPLETED(
            Severity.ALERT, "FORENSIC_ALERT",
            fixedText = "USDC bridge leg completed. Waiting for target token confirmation.",
        ),
        TARGET_TOKEN_BUY_CONFIRMED(
            Severity.NOTICE, "AATE_DEFAULT_SENTIENT", "RALLY_COMMANDER",
        ),
        TARGET_TOKEN_MISSING_AFTER_BRIDGE(
            Severity.CRITICAL, "FORENSIC_ALERT",
            fixedText = "Bridge completed, but target token was not confirmed. " +
                        "Do not mark this as a completed buy.",
        ),
        WALLET_HOLDS_UNMANAGED_TOKEN(
            Severity.ALERT, "FORENSIC_ALERT",
            fixedText = "Unmanaged token detected in host wallet. " +
                        "Tracker is being restored from wallet truth.",
        ),
        ACTIVE_POSITION_RESTORED(
            Severity.NOTICE, "PROFESSOR_STATESMAN",
            tier = ModelTier.NARRATION,
            fixedText = "Position restored from wallet reconciliation. Monitoring is now active.",
        ),

        // ── Exits ─────────────────────────────────────────────────────────────
        TAKE_PROFIT_HIT(Severity.NOTICE, "HYPE_MOONSHOT", "RALLY_COMMANDER"),
        STOP_LOSS_HIT(Severity.ALERT, "LOSS_RECOVERY_COACH", "PROFESSOR_STATESMAN",
                      tier = ModelTier.NARRATION),
        SELL_ATTEMPT_STARTED(Severity.ROUTINE, "CALM_ANALYST"),
        SELL_CONFIRMED(Severity.NOTICE, "AATE_DEFAULT_SENTIENT", "RALLY_COMMANDER"),
        SELL_FAILED(Severity.CRITICAL, "FORENSIC_ALERT"),

        // ── Filters ───────────────────────────────────────────────────────────
        RUGCHECK_REJECTION(Severity.NOTICE, "PROFESSOR_STATESMAN",
                           tier = ModelTier.NARRATION),
        TOXIC_MODE_FREEZE(Severity.ALERT, "LOSS_RECOVERY_COACH",
                          tier = ModelTier.NARRATION),

        // ── Reporting ─────────────────────────────────────────────────────────
        DAILY_SUMMARY(Severity.ROUTINE, "PROFESSOR_STATESMAN",
                      tier = ModelTier.NARRATION),
        INVESTOR_DEMO(Severity.ROUTINE, "PROFESSOR_STATESMAN", "AATE_DEFAULT_SENTIENT",
                      tier = ModelTier.PREMIUM),

        // ── Severe ────────────────────────────────────────────────────────────
        CRITICAL_FAULT(Severity.CRITICAL, "EMERGENCY_SYSTEM"),
        TRADING_HALTED(Severity.CRITICAL, "EMERGENCY_SYSTEM"),
        ;

        /** Textual key used for prefs / diagnostics ("token_discovered"). */
        fun key(): String = name.lowercase()
    }

    /** Should this event play in `CRITICAL_ONLY` voice mode? */
    fun playsInCriticalOnly(e: Event): Boolean =
        e.severity == Severity.CRITICAL || e.severity == Severity.ALERT

    /** Resolve which persona to use given the operator's voice mode. */
    fun pickPersonaId(e: Event, fullPersonality: Boolean): String =
        if (fullPersonality && !e.optionalFull.isNullOrBlank()) e.optionalFull!! else e.primary

    /** Map ModelTier to the actual model ID stored on the resolved persona profile. */
    fun resolveModelId(profile: PersonalityVoiceRegistry.VoiceProfile, tier: ModelTier): String = when (tier) {
        ModelTier.FAST       -> profile.fastModelId
        ModelTier.PREMIUM    -> profile.premiumModelId
        ModelTier.NARRATION  -> profile.narrationModelId
    }

    /** Find an event by its lowercase key (for debug / scripted speech). */
    fun byKey(key: String): Event? =
        Event.entries.firstOrNull { it.key().equals(key, ignoreCase = true) || it.name.equals(key, ignoreCase = true) }
}
