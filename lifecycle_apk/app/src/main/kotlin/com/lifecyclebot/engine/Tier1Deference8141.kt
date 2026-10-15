package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.ProtectiveExitClass7807

/**
 * V5.0.8141 — every hold overlay defers to tier 1 (pure: reason strings only, no runtime state).
 *
 * RunnerExitContract8136 declared "no hold overlay may suppress tier 1" ([RunnerExitContract8136.emergency8136]), but
 * Executor's hold gates still read ProtectiveExitClass7807.bypassesHolds, which on a MOONSHOT lane lets only ranks 1-2
 * through and knows a freeze only as FREEZE_AUTH*: a TOKEN_FROZEN or LIQUIDITY_PULLED exit on a runner could be held
 * by the moonbag gate, the style min-hold or the profit-dust deferral. One answer now:
 *
 *  - [tier1]            tier 1 of the exit-authority order (verified emergency, rug, liquidity, freeze, unexecutable,
 *                       operator) — never held, on any lane, paper or live;
 *  - [bypassesHolds8141] the hold-bypass class: ProtectiveExitClass7807 (ranks 1-3, Moonshot ranks 1-2) OR tier 1.
 *
 * Wired at Executor.protectiveEmergencyAdmit7807 (bypass7807: style min-hold, profit dust, wallet-holds admit),
 * Executor.moonbagGate7322 (moonbag + tail bag), MoonbagRunner7322.decide and BotService.planOwnsExit7754. The other
 * overlays already defer: LifecycleHold8065.passes8065, RunnerGrab7967.deferrable7967, TailBag8034.decidePure8034,
 * CortexExit7897.holdVeto, NetEdge8030.structural8030.
 */
object Tier1Deference8141 {

    /** Pure: tier 1 — no hold, moonbag, lifecycle, plan or cortex overlay may suppress it. */
    fun tier1(reason: String?): Boolean = RunnerExitContract8136.emergency8136(reason)

    /** Pure: does [reason] bypass every hold gate for a position on [lane]? */
    fun bypassesHolds8141(reason: String?, lane: String?): Boolean =
        ProtectiveExitClass7807.bypassesHolds(reason, lane) || tier1(reason)

    /** Pure: may a hold overlay act on an exit for [reason]? Never on tier 1. */
    fun holdMayAct8141(reason: String?): Boolean = !tier1(reason)
}
