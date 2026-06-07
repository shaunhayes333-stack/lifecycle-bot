package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.1391 — AuthorityService (P0-2): single source of truth that converts
 * `(global mode, requested intent, lane, shadow flag)` into ONE authoritative
 * verdict that every executor entry must consult.
 *
 * This wraps the existing primitives:
 *   - [RuntimeModeAuthority] — atomic PAPER/LIVE authority (the global mode).
 *   - [ExecutionRouteGuard]  — paper/live/shadow route invariants per call.
 *   - [BotConfig.shadowPaperEnabled] — whether PAPER under LIVE is allowed
 *     as the explicit SHADOW route.
 *
 * It enforces three operator-mandated invariants:
 *
 *   I1. PAPER mode never runs live-mission code.
 *       `intentFor(requested=LIVE_BUY)` while authority=PAPER → REJECT
 *       (live mission BUY must be physically unreachable from PAPER).
 *
 *   I2. SHADOW_PAPER never creates an executable on-chain BUY intent.
 *       SHADOW route writes paper Position mutations only — there is no
 *       path from SHADOW that returns LIVE_EXECUTE. If the caller asks for
 *       LIVE_BUY while shadowEnabled=true under LIVE authority, the verdict
 *       is LIVE_EXECUTE (real live), not SHADOW. SHADOW is paper-only.
 *
 *   I3. Every execution attempt has gone through TradeAuth + EXEC_GATE.
 *       The verdict from this service alone is not sufficient — callers
 *       must still pass [TradeAuthorizer.authorize] and
 *       [ExecutableOpenGate.canOpenExecutablePosition]. This service does
 *       not bypass them; it only refuses verdicts that violate I1/I2 so
 *       fewer requests reach the deeper gates and so the deeper gates
 *       always see a consistent mode signal.
 *
 * Telemetry counters are exposed for the PipelineHealthCollector so the
 * operator can confirm there has been zero leak in a session.
 */
object AuthorityService {

    private const val TAG = "AuthorityService"

    /** What the caller wants to do. */
    enum class IntentRequest {
        /** Caller wants to open a real live on-chain BUY. */
        LIVE_BUY,
        /** Caller wants to open a paper BUY (paper mode primary, or shadow under live). */
        PAPER_BUY,
        /** Caller wants to track only (no buy, no mutation). */
        SHADOW_TRACK,
    }

    /** What this service says will actually happen. */
    enum class IntentVerdict {
        LIVE_EXECUTE,
        PAPER_EXECUTE,
        SHADOW_ONLY,
        REJECT,
    }

    data class Decision(
        val verdict: IntentVerdict,
        val reason: String,
        val authority: RuntimeModeAuthority.Mode,
        val shadowEnabled: Boolean,
    ) {
        fun isExecutable(): Boolean =
            verdict == IntentVerdict.LIVE_EXECUTE || verdict == IntentVerdict.PAPER_EXECUTE
    }

    // Telemetry
    private val liveExecutesAuthorized = AtomicLong(0L)
    private val paperExecutesAuthorized = AtomicLong(0L)
    private val shadowOnlyAuthorized = AtomicLong(0L)
    private val rejectedI1LiveFromPaper = AtomicLong(0L)
    private val rejectedShadowExecutableLeak = AtomicLong(0L)
    private val rejectedOther = AtomicLong(0L)

    /**
     * Map a caller intent onto the authoritative verdict.
     *
     * @param requested  what the caller wants.
     * @param lane       lane name for telemetry.
     * @param source     short caller tag (e.g. `Executor.liveBuy`) for forensic.
     */
    fun decide(
        requested: IntentRequest,
        lane: String,
        source: String,
        shadowEnabled: Boolean,
    ): Decision {
        val authority = try { RuntimeModeAuthority.authority() } catch (_: Throwable) { RuntimeModeAuthority.Mode.PAPER }

        return when (requested) {
            IntentRequest.LIVE_BUY -> {
                // I1: live mission code may only run under LIVE authority.
                if (authority != RuntimeModeAuthority.Mode.LIVE) {
                    rejectedI1LiveFromPaper.incrementAndGet()
                    try {
                        ForensicLogger.lifecycle(
                            "AUTHORITY_REJECT_LIVE_FROM_PAPER",
                            "lane=$lane source=$source authority=$authority"
                        )
                    } catch (_: Throwable) {}
                    Decision(IntentVerdict.REJECT, "LIVE_BUY_REQUESTED_UNDER_PAPER_AUTHORITY", authority, shadowEnabled)
                } else {
                    liveExecutesAuthorized.incrementAndGet()
                    Decision(IntentVerdict.LIVE_EXECUTE, "LIVE_AUTHORITY_OK", authority, shadowEnabled)
                }
            }

            IntentRequest.PAPER_BUY -> {
                when (authority) {
                    RuntimeModeAuthority.Mode.PAPER -> {
                        paperExecutesAuthorized.incrementAndGet()
                        Decision(IntentVerdict.PAPER_EXECUTE, "PAPER_AUTHORITY_OK", authority, shadowEnabled)
                    }
                    RuntimeModeAuthority.Mode.LIVE -> {
                        // Under LIVE authority, a paper buy is only legal as
                        // the explicit SHADOW route AND it MUST stay paper —
                        // never an on-chain swap. The downstream paperBuy()
                        // path enforces this (it never invokes a real swap),
                        // we just label the verdict as SHADOW_ONLY so the
                        // caller can branch its mutations accordingly.
                        if (shadowEnabled) {
                            shadowOnlyAuthorized.incrementAndGet()
                            Decision(IntentVerdict.SHADOW_ONLY, "SHADOW_PAPER_UNDER_LIVE", authority, shadowEnabled)
                        } else {
                            rejectedOther.incrementAndGet()
                            try {
                                ForensicLogger.lifecycle(
                                    "AUTHORITY_REJECT_PAPER_IN_LIVE",
                                    "lane=$lane source=$source shadowEnabled=false"
                                )
                            } catch (_: Throwable) {}
                            Decision(IntentVerdict.REJECT, "PAPER_BUY_IN_LIVE_WITHOUT_SHADOW", authority, shadowEnabled)
                        }
                    }
                }
            }

            IntentRequest.SHADOW_TRACK -> {
                shadowOnlyAuthorized.incrementAndGet()
                Decision(IntentVerdict.SHADOW_ONLY, "SHADOW_TRACK", authority, shadowEnabled)
            }
        }
    }

    /**
     * Hard assertion used by [Executor.liveBuy] entry: live-mission code
     * MUST NOT execute under PAPER authority. Returns true if safe to
     * proceed, false to abort. On violation emits LIVE_LEAK forensic.
     */
    fun assertLiveExecutionAllowed(lane: String, source: String): Boolean {
        if (RuntimeModeAuthority.isLive()) return true
        rejectedI1LiveFromPaper.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "AUTHORITY_LIVE_LEAK_BLOCKED",
                "lane=$lane source=$source authority=PAPER"
            )
        } catch (_: Throwable) {}
        try {
            ErrorLogger.warn(
                TAG,
                "🚨 LIVE_LEAK_BLOCKED: live-mission code reached under PAPER authority | lane=$lane source=$source"
            )
        } catch (_: Throwable) {}
        return false
    }

    /**
     * Hard assertion used at the SHADOW position-write path. SHADOW MUST
     * NOT mutate on-chain or create an executable BUY intent. Returns
     * true if the request is purely paper-position; false if it tried
     * to escalate into an executable form.
     */
    fun assertNoShadowExecutableLeak(
        lane: String,
        source: String,
        isOnChainSwap: Boolean,
    ): Boolean {
        if (!isOnChainSwap) return true
        rejectedShadowExecutableLeak.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "AUTHORITY_SHADOW_EXECUTABLE_LEAK_BLOCKED",
                "lane=$lane source=$source"
            )
        } catch (_: Throwable) {}
        try {
            ErrorLogger.warn(
                TAG,
                "🚨 SHADOW_EXECUTABLE_LEAK_BLOCKED: shadow path attempted on-chain swap | lane=$lane source=$source"
            )
        } catch (_: Throwable) {}
        return false
    }

    // ─── Telemetry ─────────────────────────────────────────────────────

    data class Counters(
        val liveExecutes: Long,
        val paperExecutes: Long,
        val shadowOnly: Long,
        val rejectedLiveFromPaper: Long,
        val rejectedShadowLeak: Long,
        val rejectedOther: Long,
    )

    fun counters(): Counters = Counters(
        liveExecutes = liveExecutesAuthorized.get(),
        paperExecutes = paperExecutesAuthorized.get(),
        shadowOnly = shadowOnlyAuthorized.get(),
        rejectedLiveFromPaper = rejectedI1LiveFromPaper.get(),
        rejectedShadowLeak = rejectedShadowExecutableLeak.get(),
        rejectedOther = rejectedOther.get(),
    )

    fun summary(): String {
        val c = counters()
        return "AuthorityService: liveExec=${c.liveExecutes} paperExec=${c.paperExecutes} " +
            "shadow=${c.shadowOnly} | rejected liveFromPaper=${c.rejectedLiveFromPaper} " +
            "shadowLeak=${c.rejectedShadowLeak} other=${c.rejectedOther}"
    }

    fun resetForTests() {
        liveExecutesAuthorized.set(0L)
        paperExecutesAuthorized.set(0L)
        shadowOnlyAuthorized.set(0L)
        rejectedI1LiveFromPaper.set(0L)
        rejectedShadowExecutableLeak.set(0L)
        rejectedOther.set(0L)
    }
}
