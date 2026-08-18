package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6464 §P1 — AUTHORITY SNAPSHOT VERSIONING (kill AUTHZ_RACE).
 *
 * OPERATOR MANDATE:
 *   "LANE_EVAL/AUTHZ_RACE=1. No entry authority check may use a
 *    mutable read/check/write sequence. Capture immutable authority
 *    snapshot/version at decision creation. Executor validates current
 *    version before mutation. If authority changed: deterministic
 *    revalidate/reject; no race. Target AUTHZ_RACE=0."
 *
 * DESIGN
 * ──────
 * A single monotonically-incrementing version integer serves as the
 * authority epoch. Any observer-relevant state change (position open,
 * position close, lane quarantine, cooldown re-arm, config toggle)
 * calls `bump()`. Decisions capture `AuthoritySnapshot(version)` at
 * creation. Executor validates the mutation site's current version
 * against the snapshot version:
 *   - equal   → PROCEED
 *   - differ  → REVALIDATE (caller re-runs the lane eval on the fresh
 *              authority state; no silent mutation).
 *
 * The revalidate handoff MUST re-check the lane predicate on the new
 * epoch — never fall through to the mutation with stale gates.
 */
object AuthoritySnapshotVersion6464 {

    private val version = AtomicLong(0L)
    private val bumps = AtomicLong(0L)
    private val stalePayloadRevalidates = AtomicLong(0L)

    /** Bump the authority epoch. Any state mutation that could affect
     *  a pending decision MUST call this. */
    fun bump(reason: String = "unspecified") {
        val v = version.incrementAndGet()
        bumps.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "AUTHORITY_VERSION_BUMPED_6464",
                "version=$v reason=$reason",
            )
        } catch (_: Throwable) {}
    }

    /** Snapshot value the caller stores on a decision. */
    fun snapshotVersion(): Long = version.get()

    /**
     * Called from the executor mutation site with the decision's
     * captured snapshotVersion. Returns true when the snapshot is
     * still current; false when the caller MUST revalidate.
     */
    fun validate(snapshotVersion: Long, siteTag: String): Boolean {
        val cur = version.get()
        if (cur == snapshotVersion) return true
        stalePayloadRevalidates.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "AUTHORITY_STALE_REVALIDATE_6464",
                "site=$siteTag snapshot=$snapshotVersion current=$cur",
            )
            PipelineHealthCollector.labelInc("AUTHORITY_STALE_REVALIDATE_6464")
        } catch (_: Throwable) {}
        return false
    }

    fun statusLine(): String =
        "version=${version.get()} bumps=${bumps.get()} staleRevalidates=${stalePayloadRevalidates.get()}"

    internal fun resetForTest() {
        version.set(0L); bumps.set(0L); stalePayloadRevalidates.set(0L)
    }
}
