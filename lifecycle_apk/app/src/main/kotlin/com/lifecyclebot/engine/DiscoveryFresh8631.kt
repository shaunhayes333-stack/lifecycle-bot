package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.LaneCheatSheet8323

/**
 * V5.0.8631 §DISCOVERY_OFFERS_FRESH_CANDIDATES — audits/audit_8629/audit_discovery_now.md D1 / D3 / D5,
 * DIAG_AND_BUILD_8629 Part B Step 1. Owner: the bot must find fresh, real launches (the 5x / 10x candidates) instead of
 * re-evaluating old trending coins that already pumped.
 *
 *  - D1: a re-sighting no longer resets the watchlist admission time (GlobalTradeRegistry.noteResighting8631), so a coin a
 *    trending list re-serves every few minutes leaves the selector's fresh tier after its first 120 s and becomes an
 *    eviction candidate. The fresh tier ([freshTier8631]) also leaves out a coin whose known launch age is past every
 *    runner lane's cap (unknown age never blocks).
 *  - D3: the runner lanes do not evaluate a coin whose KNOWN launch age is over the lane's cap ([runnerLaneTooOld8631]).
 *    [RUNNER_LANE_MAX_AGE_MS_8631] is the one table; LaneAuthority8577's 24 h live refusal reads its lanes from it and
 *    stays as the outer bound. Edge export 8622: coins over 24 h made 0.2 % 2x moves against 2.6-2.9 % under 5 minutes.
 *  - D5: the PumpPortal fast lane fires on a witnessed create inside the sniper window that reached the hot list
 *    ([fastLaneTrigger8631]), not on an estimated liquidity of $2k that a fresh create (~0.02 SOL real) never has.
 *
 * Pure: no Android, unit-tested in Aate8631DiscoveryFreshTest.
 */
object DiscoveryFresh8631 {
    // ── D3: the runner-lane age table ──

    const val SHITCOIN_MAX_AGE_MS_8631 = 6L * 3_600_000L
    const val MOONSHOT_MAX_AGE_MS_8631 = 3L * 3_600_000L
    const val EXPRESS_MAX_AGE_MS_8631 = 3_600_000L
    const val SNIPER_MAX_AGE_MS_8631 = 30L * 60_000L
    const val MANIPULATED_MAX_AGE_MS_8631 = 30L * 60_000L

    /** The one runner-lane age table: lane (canonical name) -> the oldest known launch age it evaluates. */
    val RUNNER_LANE_MAX_AGE_MS_8631: Map<String, Long> = mapOf(
        "SHITCOIN" to SHITCOIN_MAX_AGE_MS_8631,
        "MOONSHOT" to MOONSHOT_MAX_AGE_MS_8631,
        "EXPRESS" to EXPRESS_MAX_AGE_MS_8631,
        "PROJECT_SNIPER" to SNIPER_MAX_AGE_MS_8631,
        "MANIPULATED" to MANIPULATED_MAX_AGE_MS_8631,
    )

    /** The widest runner cap: a coin known older than this is past every runner lane's scope. */
    val MAX_RUNNER_AGE_MS_8631: Long = RUNNER_LANE_MAX_AGE_MS_8631.values.fold(0L) { a, b -> maxOf(a, b) }

    /** Pure: the age cap of [lane] (alias-folded), or null when [lane] is not a runner lane. */
    fun runnerAgeCapMs8631(lane: String?): Long? = RUNNER_LANE_MAX_AGE_MS_8631[LaneCheatSheet8323.key8323(lane)]

    /** Pure: does runner [lane] skip a coin whose launch age is [launchAgeMs]? An unknown age never blocks. */
    fun runnerLaneTooOld8631(lane: String?, launchAgeMs: Long?): Boolean {
        val cap = runnerAgeCapMs8631(lane) ?: return false
        return launchAgeMs != null && launchAgeMs > cap
    }

    // ── D1: the selector's fresh tier ──

    /**
     * Pure: first admission ([addedAtMs], never refreshed by a re-sighting) inside [windowMs], and not a coin whose known
     * launch age is past every runner lane's cap. An unknown age never blocks.
     */
    fun freshTier8631(addedAtMs: Long, nowMs: Long, windowMs: Long, launchAgeMs: Long?): Boolean =
        nowMs - addedAtMs < windowMs && !(launchAgeMs != null && launchAgeMs > MAX_RUNNER_AGE_MS_8631)

    // ── D5: the fresh-launch fast lane ──

    /** Pure: a witnessed PumpPortal create inside the sniper window (FreshLaunchIntake8303.freshCreate8303) on the hot list. */
    fun fastLaneTrigger8631(source: String, createBirthMs: Long?, nowMs: Long, watched: Boolean): Boolean =
        watched && FreshLaunchIntake8303.freshCreate8303(source, createBirthMs, nowMs)
}
