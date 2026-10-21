package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** V5.0.8283 — a token's data lifecycle: what history CAN exist for it yet. UNKNOWN = no resolved birth time. */
enum class Lifecycle8283 { NEW, DEVELOPING, ESTABLISHED, UNKNOWN }

/**
 * V5.0.8283 §LIFECYCLE_AWARE_DATA_REQUIREMENTS (P4).
 *
 * 5.0.8282: DATA_INCOMPLETE_DEFER_8136 1,497 + DATA_DEFER_FETCH_NEVER_COMPLETED_8204 1,327 + DATA_DEFER_BUDGET_EXHAUSTED
 * 388, and runners (KIMOTHY +2153%, Kimchi +4817%, LEONIDA +2226%) missed waiting on inputs. A 15-second-old token
 * cannot have a 5-minute move or three bars of shape, so a defer waiting for that history waited for something no
 * provider could return, while the token's own launch evidence (tape, crowd, flow, ladder) was there.
 *
 * Availability is now per lifecycle: a history-window input ([historyWindowMs8283]) that the token is too young to have
 * is UNAVAILABLE_FOR_AGE — recorded (trace + counters), not waited for — unless the setup being judged reads it.
 * Critical unknowns ([criticalUnknown8283]: identity, executable price, route, actual liquidity, sellability, economic
 * cost, wallet balance) are binding at every age. Nothing is filled in: an unavailable input stays absent (NaN) in the
 * features, no history is fabricated, and the token is judged on what it actually has.
 */
object LifecycleData8283 {
    /** The launch window (SetupFeatureResolver8169.maxEvidenceAge8170: launch data is perishable for 15 minutes). */
    internal const val NEW_MAX_MIN_8283 = 15.0
    /** Up to 4 h a token is developing its longer windows (1 h move, 15 m bars); after that it is established. */
    internal const val DEVELOPING_MAX_MIN_8283 = 240.0
    /** The 5-minute move: TradeTape8100 / DexScreener m5 / the sweep's 5 m change need 5 minutes of trading. */
    internal const val CHG_WINDOW_MS_8283 = 5L * 60_000L
    /** The youngest shape statement: TradeTape8100.youngShape8220 needs three 15 s bars spanning >= 30 s. */
    internal const val SHAPE_WINDOW_MS_8283 = 30_000L

    /** Bar intervals and the history each needs before ONE complete bar can exist. */
    internal val WINDOWS_8283: Map<String, Long> = linkedMapOf("15s" to 15_000L, "1m" to 60_000L, "5m" to 5L * 60_000L,
        "15m" to 15L * 60_000L, "1h" to 60L * 60_000L)

    /** Pure: the lifecycle of a token [ageMin] old. */
    fun lifecycleOf8283(ageMin: Double): Lifecycle8283 = when {
        !ageMin.isFinite() || ageMin < 0.0 -> Lifecycle8283.UNKNOWN
        ageMin < NEW_MAX_MIN_8283 -> Lifecycle8283.NEW
        ageMin < DEVELOPING_MAX_MIN_8283 -> Lifecycle8283.DEVELOPING
        else -> Lifecycle8283.ESTABLISHED
    }

    /** Pure: can a window of [windowMs] exist for a token [ageMin] old? Unknown age makes no claim (the input stays binding). */
    fun windowAvailable8283(windowMs: Long, ageMin: Double): Boolean =
        windowMs <= 0L || !ageMin.isFinite() || ageMin < 0.0 || ageMin * 60_000.0 >= windowMs

    /** Pure: the bar intervals a token [ageMin] old can validly have history for (a 10 s token: none; never 5 m at 10 s). */
    fun availableWindows8283(ageMin: Double): List<String> =
        if (!ageMin.isFinite() || ageMin < 0.0) emptyList()
        else WINDOWS_8283.filter { (_, ms) -> ageMin * 60_000.0 >= ms }.keys.toList()

    /** Pure: the history a missing setup input needs before it can exist at all; 0 = not a history input. */
    internal fun historyWindowMs8283(missing: String): Long = when (missing.substringBefore(':').trim().lowercase()) {
        "chg" -> CHG_WINDOW_MS_8283
        "shape" -> SHAPE_WINDOW_MS_8283
        else -> 0L
    }

    /** Wallet balance, sellability and economic cost named explicitly beside CandidateAdmission8136's critical set. */
    private val CRITICAL_8283 = setOf("WALLET", "BALANCE", "WALLET_BALANCE", "SELLABLE", "COST", "EXECUTABLE_PRICE", "SAFETY")

    /** Pure: identity, executable price, route, actual liquidity, sellability, economic cost, wallet balance — binding at every age. */
    fun criticalUnknown8283(missing: String): Boolean =
        CandidateAdmission8136.criticalMissing8170(missing) || missing.substringBefore(':').trim().uppercase() in CRITICAL_8283

    /**
     * The setups that read launch observations only (age, tape crowd / flow / dev, ladder, socials, boost, bundle, the
     * opportunity scan, expert wallets) and neither the 5 m / 1 h move nor price-history shape as a required input.
     */
    internal val LAUNCH_EVIDENCE_SETUPS_8283: Set<String> = setOf(
        "LAUNCH_CONTINUATION", "VERIFIED_LAUNCH", "CROWD_FORMING", "LAUNCH_LADDER_PROVEN", "FAST_CROWD", "BROAD_DISTRIBUTION",
        "NET_INFLOW_SURGE", "FIRST_DIP_BOUGHT", "DEV_HOLDS_CROWD_BUYS", "CLEAN_DEV_LAUNCH", "ACCELERATING_TAPE", "GRADUATION_RUN",
        "NO_BUNDLE_CLEAN", "SOCIAL_LAUNCH", "BOOSTED_LAUNCH", "OPP_EARLY_IGNITION", "EXPERT_ENTRY", CandidateAdmission8136.RUNNER_FORMING_8260,
    )

    /** Pure: does judging [setup] need history inputs? Null = nothing fired: no setup is waiting on them. */
    internal fun setupNeedsHistory8283(setup: String?): Boolean = setup != null && setup !in LAUNCH_EVIDENCE_SETUPS_8283

    /** [binding]: still waited for / refused on. [unavailable]: history the token is too young to have, recorded only. */
    data class Split8283(val binding: List<String>, val unavailable: List<String>)

    /** Pure: split a candidate's missing inputs by what can exist at [ageMin] for [setup]. */
    fun split8283(missing: List<String>, ageMin: Double, setup: String?): Split8283 {
        if (missing.isEmpty()) return Split8283(emptyList(), emptyList())
        val needsHistory = setupNeedsHistory8283(setup)
        val binding = ArrayList<String>(missing.size)
        val unavailable = ArrayList<String>(2)
        for (m in missing) {
            val w = historyWindowMs8283(m)
            if (!criticalUnknown8283(m) && w > 0L && !needsHistory && !windowAvailable8283(w, ageMin)) unavailable += m
            else binding += m
        }
        return Split8283(binding, unavailable)
    }

    // ── V5.0.8287 — the launch's own trades decide whether a flow reading can exist yet ──

    /** A flow reading (TradeTape8100.MIN_TRADES_BP / the launch tape's share) needs this many of the token's own trades. */
    internal const val BP_MIN_TRADES_8287 = 6

    /**
     * Pure: can a buy-pressure reading exist yet? 8286: lifecycle8283 judgedOnLaunchEvidence=0, otherMissingStillBinding=22
     * — every launch whose 5 m move was correctly "unavailable" still waited on bp:DEFAULT_UNOBSERVED. For a NEW token
     * whose own trades ARE observed ([observedTrades] = trades the launch tape / trade tape saw for it, null = its trades
     * are not observed at all), fewer than [BP_MIN_TRADES_8287] trades means no flow reading can exist yet: that is "not
     * yet possible", not missing. Unobserved trades, an unknown age or an older token claim nothing (bp stays binding).
     */
    fun bpNotYetPossible8287(ageMin: Double, observedTrades: Int?): Boolean =
        lifecycleOf8283(ageMin) == Lifecycle8283.NEW && observedTrades != null && observedTrades in 0 until BP_MIN_TRADES_8287

    /**
     * V5.0.8287 — [split8283] plus the launch's flow: for a launch-evidence judgement (no setup reading history fired), a
     * bp the token's own observed trades cannot state yet ([bpNotYetPossible8287]) is recorded as unavailable instead of
     * binding. Critical unknowns stay binding at every age; nothing is filled in.
     */
    fun split8287(missing: List<String>, ageMin: Double, setup: String?, observedTrades: Int?): Split8283 {
        val base = split8283(missing, ageMin, setup)
        if (setupNeedsHistory8283(setup) || !bpNotYetPossible8287(ageMin, observedTrades)) return base
        val moved = base.binding.filter { it.substringBefore(':').trim().lowercase() == "bp" && !criticalUnknown8283(it) }
        if (moved.isEmpty()) return base
        return Split8283(base.binding - moved.toSet(), base.unavailable + moved)
    }

    // ── report: lifecycle8283[...] ──

    private val judgedOnLaunch = AtomicLong(0L)
    private val keptBinding = AtomicLong(0L)
    private val unavailableBy = ConcurrentHashMap<String, AtomicLong>()

    /** LanePlaybook7907.dataIncomplete8136: one lifecycle split applied (counted; the unavailable features are named). */
    fun note8283(s: Split8283) {
        if (s.unavailable.isEmpty()) return
        if (s.binding.isEmpty()) judgedOnLaunch.incrementAndGet() else keptBinding.incrementAndGet()
        for (u in s.unavailable) {
            val f = u.substringBefore(':').trim().lowercase()
            if (unavailableBy.size < 16 || unavailableBy.containsKey(f)) unavailableBy.computeIfAbsent(f) { AtomicLong(0L) }.incrementAndGet()
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LIFECYCLE_HISTORY_UNAVAILABLE_8283_$f") } catch (_: Throwable) {}
        }
    }

    /** "lifecycle8283[judgedOnLaunchEvidence=n otherMissingStillBinding=n unavailable{chg=..,shape=..}]". */
    fun statusLine8283(): String =
        "lifecycle8283[judgedOnLaunchEvidence=${judgedOnLaunch.get()} otherMissingStillBinding=${keptBinding.get()} " +
            "unavailable{${unavailableBy.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}}]"

    internal fun clearForTest8283() { judgedOnLaunch.set(0L); keptBinding.set(0L); unavailableBy.clear() }
}
