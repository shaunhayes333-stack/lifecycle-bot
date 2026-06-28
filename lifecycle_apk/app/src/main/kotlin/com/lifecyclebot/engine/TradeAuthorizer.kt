package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * V4.6 TRADE AUTHORIZER - SINGLE SOURCE OF EXECUTION TRUTH
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * PURPOSE:
 * All execution paths must pass through this gate BEFORE any paper/live buy,
 * ledger insert, or UI position creation.
 *
 * ORDER:
 *   DISCOVERED -> SCORED -> PRE_FILTER -> TRADE_AUTHORIZER -> SIZE -> EXECUTE
 *
 * NEVER:
 *   DISCOVERED -> SCORED -> SIZE -> EXECUTE -> late block
 *
 * V5.1:
 * Supports multi-book execution. Same token may exist in different books
 * (CORE, TREASURY, SHITCOIN, BLUECHIP, MOONSHOT), but not twice in the SAME book.
 *
 * V5.2:
 * Rugcheck policy:
 *   RC 0-1  = REJECT
 *   RC 2-5  = SHADOW_ONLY
 *   RC 6+   = continue through normal gates
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object TradeAuthorizer {

    private const val TAG = "TradeAuth"

    // ───────────────────────────────────────────────────────────────────────────
    // TOKEN STATE
    // ───────────────────────────────────────────────────────────────────────────

    enum class TokenState {
        DISCOVERED,
        WATCHLISTED,
        SCORED,
        BLOCKED,
        SHADOW_TRACKING,
        PAPER_OPEN,
        LIVE_OPEN,
        CLOSED,
        BANNED,
    }

    // ───────────────────────────────────────────────────────────────────────────
    // EXECUTION VERDICT
    // ───────────────────────────────────────────────────────────────────────────

    enum class ExecutionVerdict {
        REJECT,
        SHADOW_ONLY,
        PAPER_EXECUTE,
        LIVE_EXECUTE,
    }

    enum class BlockLevel {
        SOFT,
        HARD,
        PERMANENT,
    }

    data class AuthorizationResult(
        val verdict: ExecutionVerdict,
        val reason: String,
        val blockLevel: BlockLevel? = null,
        val canRetry: Boolean = false,
        val attemptId: String = "",
    ) {
        fun isExecutable(): Boolean {
            return verdict == ExecutionVerdict.PAPER_EXECUTE || verdict == ExecutionVerdict.LIVE_EXECUTE
        }

        val rejectTaxonomy: RejectTaxonomy.Classification
            get() = RejectTaxonomy.classify(reason, blockLevel)

        fun isShadowOnly(): Boolean {
            return verdict == ExecutionVerdict.SHADOW_ONLY
        }
    }

    // ───────────────────────────────────────────────────────────────────────────
    // BOOKS / LOCKS
    // ───────────────────────────────────────────────────────────────────────────

    enum class ExecutionBook {
        CORE,
        TREASURY,
        QUALITY,
        SHITCOIN,
        BLUECHIP,
        MOONSHOT,
        SHADOW,
        DIP_HUNTER,   // V5.9.610: DipHunter has its own book; don't consume CORE locks
        MANIPULATED,  // V5.6.8: Special book that bypasses rugcheck - trades intentionally risky tokens
        CRYPTO,       // V5.9.1159: Crypto Universe canonical execution book
        CYCLIC,       // V5.9.1227: $500→$1M compounding ring owns its own book
    }

    data class TokenLock(
        val mint: String,
        val state: TokenState,
        val book: ExecutionBook,
        val lockedAt: Long,
        val lastDecisionEpoch: Long,
    )

    private val tokenLocks = ConcurrentHashMap<String, TokenLock>()
    private var currentEpoch = 0L

    private fun lockKey(mint: String, book: ExecutionBook): String = "$mint:${book.name}"

    // V5.0.3936 — AUTH-LOCK TRUTH PRUNE.
    // authorize() historically wrote PAPER_OPEN/LIVE_OPEN before the executor
    // actually opened. If a later pre-buy stage returned, the lock could live
    // forever and reject every future lane as ALREADY_OPEN_IN_CORE while wallet
    // truth/open counters were zero. Keep a short in-flight grace, then require
    // live locks to be backed by HostWalletTokenTracker accounting truth.
    private const val LIVE_AUTH_LOCK_GRACE_MS = 15_000L

    private fun isOpenState(state: TokenState?): Boolean = state == TokenState.PAPER_OPEN || state == TokenState.LIVE_OPEN

    private fun liveWalletThinksOpen(mint: String): Boolean = try {
        HostWalletTokenTracker.getOpenForAccountingMints().contains(mint) ||
            HostWalletTokenTracker.getActuallyHeldMints().contains(mint)
    } catch (_: Throwable) { true } // fail closed: do not prune if wallet truth unavailable

    private fun isAuthoritativeOpenLock(lock: TokenLock, isPaperMode: Boolean): Boolean {
        if (!isOpenState(lock.state)) return false
        if (lock.state == TokenState.PAPER_OPEN || isPaperMode) return true
        val ageMs = System.currentTimeMillis() - lock.lockedAt
        if (ageMs <= LIVE_AUTH_LOCK_GRACE_MS) return true
        if (liveWalletThinksOpen(lock.mint)) return true
        try {
            ForensicLogger.lifecycle(
                "STALE_AUTH_LOCK_PRUNED",
                "mint=${lock.mint.take(10)} book=${lock.book.name} ageMs=$ageMs reason=no_wallet_accounting_open"
            )
            PipelineHealthCollector.labelInc("STALE_AUTH_LOCK_PRUNED")
        } catch (_: Throwable) {}
        tokenLocks.remove(lockKey(lock.mint, lock.book), lock)
        return false
    }

    // ───────────────────────────────────────────────────────────────────────────
    // MAIN AUTHORIZATION
    // ───────────────────────────────────────────────────────────────────────────

    fun authorize(
        mint: String,
        symbol: String,
        score: Int,
        confidence: Double,
        quality: String,
        isPaperMode: Boolean,
        requestedBook: ExecutionBook,
        rugcheckScore: Int = 100,
        liquidity: Double = 0.0,
        isBanned: Boolean = false,
        attemptId: String = "",
    ): AuthorizationResult {
        val now = System.currentTimeMillis()
        val normalizedQuality = quality.trim().uppercase()
        val safeConfidence = confidence.coerceIn(0.0, 100.0)

        fun rejectAuth4424(
            reason: String,
            blockLevel: BlockLevel? = null,
            canRetry: Boolean = false,
            attemptIdForResult: String = "",
        ): AuthorizationResult {
            val result = AuthorizationResult(
                verdict = ExecutionVerdict.REJECT,
                reason = reason,
                blockLevel = blockLevel,
                canRetry = canRetry,
                attemptId = attemptIdForResult,
            )
            val taxonomy = result.rejectTaxonomy
            ChokeReliefBus.launch("TRADE_AUTH_REJECT_TAXONOMY_4424", mint) {
                try { RejectTaxonomyLedger.record(taxonomy, requestedBook.name, reason) } catch (_: Throwable) {}
                try { PipelineHealthCollector.labelInc("TRADE_AUTH_REJECT_TAXONOMY_4424_${taxonomy.category.name}") } catch (_: Throwable) {}
                try {
                    ForensicLogger.lifecycle(
                        "TRADE_AUTH_REJECT_TAXONOMY_4424",
                        "mint=${mint.take(10)} symbol=$symbol lane=${requestedBook.name} reason=${reason.take(90)} taxonomy=${taxonomy.category.name} retry=$canRetry hardSafety=${taxonomy.hardSafety} ledger=RejectTaxonomyLedger"
                    )
                } catch (_: Throwable) {}
            }
            return result
        }

        if (RuntimeConfigOverlay.isTradingPaused()) {
            return rejectAuth4424("PREAUTH_BLOCK_RUNTIME_PAUSED", BlockLevel.HARD, canRetry = true)
        }
        if (RuntimeConfigOverlay.isLaneDisabled(requestedBook.name)) {
            try { ForensicLogger.lifecycle("QUALITY_ONLY_PREAUTH_BLOCKED", "lane=${requestedBook.name} symbol=$symbol mint=${mint.take(10)}") } catch (_: Throwable) {}
            return rejectAuth4424("PREAUTH_BLOCK_QUALITY_ONLY_${requestedBook.name}", BlockLevel.SOFT, canRetry = true)
        }

        // V5.9.1470 (spec item 7) — SLOT-HEALTH ADMISSION. Defer (NOT block) a new
        // executable buy for one cycle while slots are dirty (ghost opens, forcedOpen>20,
        // supervisor over cap, or an exit sweep is pending). This is a SOFT, retryable
        // reject — the candidate is re-evaluated next cycle once cleanup catches up. A
        // confirmed high-edge candidate (score>=70 AND conf>=70) bypasses the
        // exit-pending defer so genuine alpha is never starved. Exits never reach here
        // (they run on their own dispatcher), so the -15% floor / exit sweeps are
        // completely unaffected. Fail-open if BotService isn't publishing.
        run {
            val highEdge = score >= 70 && safeConfidence >= 70.0
            val sh = SlotHealthGate.shouldDeferBuy(highEdge)
            if (sh.defer) {
                try {
                    ForensicLogger.lifecycle("EXEC_DEFERRED_SLOT_HEALTH", "mint=${mint.take(10)} symbol=$symbol lane=${requestedBook.name} reason=${sh.reason} highEdge=$highEdge slots=${SlotHealthGate.snapshotLine()}")
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("EXEC_DEFERRED_SLOT_HEALTH")
                } catch (_: Throwable) {}
                return rejectAuth4424("DEFER_SLOT_HEALTH_${sh.reason}", BlockLevel.SOFT, canRetry = true)
            }
        }

        fun releasePrimaryAfterAuthFailure(reason: String) {
            try { LaneExecutionCoordinator.releaseIfPrimary(mint, requestedBook.name, reason) } catch (_: Throwable) {}
        }

        // V5.9.1120 — lane election BEFORE finality/open-request side effects.
        // 3086 showed EXEC_OPEN_REQUEST=538 but EXEC_OPEN_BLOCKED_DUPLICATE_KEY=3423:
        // secondary lanes were reaching ExecutableOpenGate just to be rejected
        // by the canonical execution key. That burns worker budget and causes
        // supervisor timeouts without increasing real trades. Preserve primary
        // lane execution; suppress secondary lanes as telemetry before finality.
        val laneElection = LaneExecutionCoordinator.canRequestExecution(mint, requestedBook.name)
        if (!laneElection.allowed) {
            try {
                ForensicLogger.lifecycle(
                    "LANE_PREAUTH_SUPPRESSED",
                    "mint=${mint.take(10)} symbol=$symbol lane=${requestedBook.name} primary=${laneElection.primaryLane} candidateVersion=${laneElection.candidateVersion} reason=${laneElection.reason}"
                )
            } catch (_: Throwable) {}
            return rejectAuth4424(
                reason = "PREAUTH_${laneElection.reason}",
                blockLevel = BlockLevel.SOFT,
                canRetry = false,
            )
        }

        // V5.9.1093 — finality BEFORE auth side effects.
        // No AUTHORIZED/PAPER_EXECUTE/LIVE_EXECUTE/token lock may appear before
        // EXEC_OPEN_ALLOWED for this same attempt.
        val finalityAttemptId = attemptId.ifBlank {
            ExecutableOpenGate.nextAttemptId(mint, requestedBook.name)
        }
        val finality = ExecutableOpenGate.canOpenExecutablePosition(
            mint = mint,
            symbol = symbol,
            rugScore = rugcheckScore,
            mode = if (isPaperMode) "PAPER" else "LIVE",
            lane = requestedBook.name,
            source = "TradeAuthorizer.preAuth",
            attemptId = finalityAttemptId,
            liveLiquidityUsd = liquidity,
        )
        if (!finality.allowed) {
            ErrorLogger.info(TAG, "❌ REJECT $symbol: FINALITY_${finality.logName} attemptId=${finality.attemptId} reason=${finality.reason}")
            releasePrimaryAfterAuthFailure("FINALITY_${finality.logName}")
            return rejectAuth4424(
                reason = "FINALITY_${finality.logName}:${finality.reason}",
                blockLevel = BlockLevel.SOFT,
                canRetry = false,
                attemptIdForResult = finality.attemptId,
            )
        }

        // GATE 1: permanent ban
        if (isBanned || BannedTokens.isBanned(mint)) {
            ErrorLogger.info(TAG, "❌ REJECT $symbol: BANNED")
            releasePrimaryAfterAuthFailure("BANNED")
            return rejectAuth4424(
                reason = "BANNED",
                blockLevel = BlockLevel.PERMANENT,
                canRetry = false,
            )
        }

        // GATE 2: rugcheck hard policy
        // V5.8: In paper mode, bypass hard rug block — consistent with DistFade and RugPreFilter.
        // Fresh launches often get RC_SCORE=1 before any analysis exists, blocking all learning data.
        // Paper trades with bad rug scores still train the model on outcomes (good learning signal).
        // V5.6.8: MANIPULATED book ALWAYS bypasses rugcheck — it trades intentionally risky tokens
        val bypassRugcheck = requestedBook == ExecutionBook.MANIPULATED

        // V5.9.105: LIVE SAFETY CIRCUIT BREAKER — refuse live trades when the
        // wallet is below the startup floor OR session drawdown halt fired.
        // Paper mode is unaffected so learning loops stay live.
        if (!isPaperMode && LiveSafetyCircuitBreaker.isTripped()) {
            val cbReason = LiveSafetyCircuitBreaker.trippedReason()
            ErrorLogger.info(TAG, "❌ REJECT $symbol: LIVE_SAFETY_CB_TRIPPED — $cbReason")
            releasePrimaryAfterAuthFailure("LIVE_SAFETY_CB")
            return AuthorizationResult(
                verdict = ExecutionVerdict.REJECT,
                reason = "LIVE_SAFETY_CB:$cbReason",
                blockLevel = BlockLevel.HARD,
                canRetry = false,
            )
        }

        when {
            rugcheckScore <= 0 -> {
                // V5.9.495n — operator: "live gate needs to come down to rc 1
                // and $2000 its not trading good tokens because of this".
                // Was: <=1 hard reject + 2..5 shadow-only in live, which kept
                // legitimate fresh-launch tokens (RC=1 unknown/pending +
                // RC=2-5 risky-but-tradeable) out of live entirely. Now only
                // confirmed-rug RC=0 hard-blocks live; RC≥1 falls through to
                // GATE 3 (liquidity) and downstream FDG/sub-trader checks.
                if (isPaperMode || bypassRugcheck) {
                    val bypassReason = if (bypassRugcheck) "MANIPULATED LAYER" else "PAPER LEARNING"
                    ErrorLogger.info(TAG, "⚠️ BYPASS ($bypassReason): $symbol RC_SCORE_$rugcheckScore — allowing entry")
                    // fall through to GATE 3+
                } else {
                    ErrorLogger.info(TAG, "❌ REJECT $symbol: RC_SCORE_$rugcheckScore <= 0 (confirmed rug)")
                    releasePrimaryAfterAuthFailure("RUGCHECK_CATASTROPHIC")
                    return AuthorizationResult(
                        verdict = ExecutionVerdict.REJECT,
                        reason = "RUGCHECK_CATASTROPHIC_$rugcheckScore",
                        blockLevel = BlockLevel.HARD,
                        canRetry = false,
                    )
                }
            }

            rugcheckScore in 1..5 -> {
                // V5.9.495n — was 2..5 SHADOW_ONLY for live. Per operator
                // directive "rc 1 and $2000", RC 1-5 now allowed live too;
                // FDG/safety-checker/liquidity gate handle residual risk.
                if (isPaperMode || bypassRugcheck) {
                    val bypassReason = if (bypassRugcheck) "MANIPULATED LAYER" else "PAPER LEARNING"
                    ErrorLogger.info(TAG, "⚠️ BYPASS ($bypassReason): $symbol RC_SCORE_$rugcheckScore (1-5) — allowing entry")
                } else {
                    ErrorLogger.info(TAG, "🟢 LIVE-RC-LOW $symbol: RC_SCORE_$rugcheckScore (1-5) — allowed per operator floor; FDG/liq still gate")
                }
                // fall through to GATE 3+ in both paper and live
            }
        }

        // GATE 3: live liquidity shape, not hard reject.
        // V5.9.1561 — low but non-zero liquidity is handled by Executor preflight
        // as INTAKE_SIZE_REDUCED / NOT_PROFITABLE_AFTER_COSTS. Only zero route/depth
        // is a true hard block elsewhere.
        if (!isPaperMode && liquidity in 0.0001..1199.9999) {
            ErrorLogger.info(TAG, "📉 SIZE-REDUCE $symbol: LIQUIDITY_${"%.0f".format(liquidity)} < 1200 — continuing to preflight")
            try { ForensicLogger.lifecycle("INTAKE_SIZE_REDUCED", "symbol=$symbol mint=${mint.take(10)} liq=${liquidity.toInt()} stage=TradeAuthorizer") } catch (_: Throwable) {}
        }

        // GATE 4: same-book lock
        val sameBookLock = tokenLocks[lockKey(mint, requestedBook)]
        if (sameBookLock != null) {
            when (sameBookLock.state) {
                TokenState.PAPER_OPEN, TokenState.LIVE_OPEN -> {
                    if (isAuthoritativeOpenLock(sameBookLock, isPaperMode)) {
                        ErrorLogger.info(TAG, "❌ REJECT $symbol: ALREADY_OPEN_IN_${requestedBook.name}")
                        releasePrimaryAfterAuthFailure("ALREADY_OPEN")
                        return AuthorizationResult(
                            verdict = ExecutionVerdict.REJECT,
                            reason = "ALREADY_OPEN",
                            blockLevel = BlockLevel.SOFT,
                            canRetry = false,
                        )
                    }
                }

                TokenState.SHADOW_TRACKING -> {
                    ErrorLogger.debug(TAG, "⬆️ $symbol: Upgrading SHADOW -> ${requestedBook.name}")
                }

                else -> {
                    // allow overwrite of stale/non-open states
                }
            }
        }

        val otherBooks = ExecutionBook.values()
            .filter { it != requestedBook && it != ExecutionBook.SHADOW }
            .filter { book ->
                val lock = tokenLocks[lockKey(mint, book)]
                lock != null && isAuthoritativeOpenLock(lock, isPaperMode)
            }

        // ══════════════════════════════════════════════════════════════════
        // V5.9.1377 — ONE-MINT-ONE-POSITION (P0 #5). Previously this block only
        // LOGGED "Multi-book authorization" and let the open proceed, so the SAME
        // mint could be opened simultaneously in MOONSHOT + SHITCOIN + TREASURY +…
        // A single rugging token then bought 3-4× across lanes and stop-lossed 3-4×,
        // multiplying the bleed and inflating the loss count / streak (snapshot showed
        // the same mints re-bought repeatedly). One physical token = one position.
        //
        // RULE: if the mint is already OPEN in any OTHER real execution book, REJECT.
        // EXCEPTIONS (kept intentionally):
        //   • SHADOW is already excluded above (shadow→real upgrade handled at GATE 4).
        //   • CYCLIC and CRYPTO are SEPARATE UNIVERSES from the meme spine; a cross
        //     between {CYCLIC,CRYPTO} and a meme book is allowed because they model
        //     different strategies on what is, in practice, never the same mint — but
        //     two MEME books (or two of the SAME universe) double-opening one mint is
        //     the bleed bug and is blocked.
        if (otherBooks.isNotEmpty()) {
            val memeBooks = setOf(
                ExecutionBook.CORE, ExecutionBook.TREASURY, ExecutionBook.QUALITY,
                ExecutionBook.SHITCOIN, ExecutionBook.BLUECHIP, ExecutionBook.MOONSHOT,
                ExecutionBook.DIP_HUNTER, ExecutionBook.MANIPULATED,
            )
            val requestedIsMeme = requestedBook in memeBooks
            val conflictingBook = otherBooks.firstOrNull { existing ->
                // Block when BOTH are meme-universe books (the real duplicate-open bug),
                // OR when the existing open is in the SAME universe class as the request.
                (requestedIsMeme && existing in memeBooks) ||
                    existing == requestedBook
            }
            if (conflictingBook != null) {
                ErrorLogger.info(TAG, "❌ REJECT $symbol: ALREADY_OPEN_IN_${conflictingBook.name} (one-mint-one-position; requested=${requestedBook.name})")
                try { com.lifecyclebot.engine.ForensicLogger.lifecycle("DUPLICATE_OPEN_SUPPRESSED", "mint=${mint.take(10)} symbol=$symbol requested=${requestedBook.name} existing=${conflictingBook.name}") } catch (_: Throwable) {}
                releasePrimaryAfterAuthFailure("ALREADY_OPEN_CROSS_BOOK")
                return AuthorizationResult(
                    verdict = ExecutionVerdict.REJECT,
                    reason = "ALREADY_OPEN_CROSS_BOOK",
                    blockLevel = BlockLevel.SOFT,
                    canRetry = false,
                )
            }
            ErrorLogger.debug(
                TAG,
                "✅ $symbol: Cross-universe authorization | existing=${otherBooks.joinToString { it.name }} requested=${requestedBook.name}"
            )
        }

        // GATE 5: promotion gate
        val promotion = checkPromotionGate(
            symbol = symbol,
            quality = normalizedQuality,
            confidence = safeConfidence,
            isPaperMode = isPaperMode,
        )

        if (!promotion.allow) {
            // V5.9.495z52 — operator directive: "it should never create paper
            // positions while running in live mode that's stupid as fuck!!"
            // SHADOW_TRACKING is a paper-mode learning tool. Writing a SHADOW
            // lock during a live run was the source of phantom-position
            // contamination (the operator's `UNKNOWN_QUALITY_MARS` SHADOW_ONLY
            // log line wrote a tokenLock that polluted ALREADY_OPEN /
            // CONCURRENT_CAP forever). In live mode, a failed promotion gate
            // is a clean REJECT — no position record, no lock, no shadow.
            if (isPaperMode) {
                ErrorLogger.info(TAG, "👁️ SHADOW_ONLY $symbol: ${promotion.reason}")
                releasePrimaryAfterAuthFailure("SHADOW_ONLY")
                tokenLocks[lockKey(mint, ExecutionBook.SHADOW)] = TokenLock(
                    mint = mint,
                    state = TokenState.SHADOW_TRACKING,
                    book = ExecutionBook.SHADOW,
                    lockedAt = now,
                    lastDecisionEpoch = currentEpoch,
                )
                return AuthorizationResult(
                    verdict = ExecutionVerdict.SHADOW_ONLY,
                    reason = promotion.reason,
                    blockLevel = BlockLevel.SOFT,
                    canRetry = true,
                )
            }
            // Live: clean reject, no lock written.
            ErrorLogger.info(TAG, "❌ REJECT $symbol: ${promotion.reason} (live, no shadow track)")
            releasePrimaryAfterAuthFailure("PROMOTION_REJECT")
            return AuthorizationResult(
                verdict = ExecutionVerdict.REJECT,
                reason = promotion.reason,
                blockLevel = BlockLevel.SOFT,
                canRetry = true,
            )
        }

        // PASS: authorize execution
        val verdict = if (isPaperMode) ExecutionVerdict.PAPER_EXECUTE else ExecutionVerdict.LIVE_EXECUTE
        val newState = if (isPaperMode) TokenState.PAPER_OPEN else TokenState.LIVE_OPEN

        tokenLocks[lockKey(mint, requestedBook)] = TokenLock(
            mint = mint,
            state = newState,
            book = requestedBook,
            lockedAt = now,
            lastDecisionEpoch = currentEpoch,
        )

        // Clear shadow lock on successful real execution
        tokenLocks.remove(lockKey(mint, ExecutionBook.SHADOW))

        ErrorLogger.info(
            TAG,
            "✅ AUTHORIZED $symbol: ${verdict.name} in ${requestedBook.name} | attemptId=$finalityAttemptId score=$score conf=${safeConfidence.toInt()}% quality=$normalizedQuality"
        )

        return AuthorizationResult(
            verdict = verdict,
            reason = "AUTHORIZED",
            blockLevel = null,
            canRetry = false,
            attemptId = finality.attemptId,
        )
    }

    // ───────────────────────────────────────────────────────────────────────────
    // PROMOTION GATE
    // ───────────────────────────────────────────────────────────────────────────

    data class PromotionResult(
        val allow: Boolean,
        val reason: String,
    )

    private fun checkPromotionGate(
        symbol: String,
        quality: String,
        confidence: Double,
        isPaperMode: Boolean,
    ): PromotionResult {
        // V5.9.495z52 — operator directive: paper-learned thresholds MUST
        // flow into live mode. The previous architecture had:
        //   - paper: PAPER_BOOTSTRAP_PASS (almost always allow)
        //   - live: separate per-quality fluid floors (B: 20-40, C: 15-35,
        //           D/F: hard-block) computed independently from paper learning
        // That ~30pt asymmetry was a primary cause of "live trader is dead".
        // Now: ONE gate for both modes, fluid thresholds derived from
        // FluidLearningAI (the same source paper uses). The only safety
        // rail kept is F-grade with conf<5 (truly garbage signal).
        if (quality == "F" && confidence < 5.0) {
            return PromotionResult(allow = false, reason = "F_grade_zero_conf_${confidence.toInt()}")
        }
        if (quality.isBlank() || quality.uppercase() !in setOf("A+", "A", "B", "C", "D", "F")) {
            // Unknown quality grades (e.g. "MARS", "ORBITAL", "LUNAR" — the
            // Moonshot strategy strings — or "" ) used to hit a separate
            // else branch that REJECTED. Operator log proved this path
            // wrote SHADOW locks live (UNKNOWN_QUALITY_MARS). Treat
            // unknown-quality as conf-only — let confidence be the gate.
            // V5.9.662b — operator: 'meme trader only. moonshot bluechip
            // the quality layer and the v3 layer'. V5.9.662 unblocked
            // Moonshot at MoonshotTraderAI but every candidate then hit
            // here as UNKNOWN_QUALITY_ORBITAL_conf_1_below_5 because
            // brand-new launches score conf≈1. In paper mode learning
            // we want to collect labelled samples, so drop the floor to
            // 1 here (live mode keeps the strict 5 floor — same lesson
            // as everywhere else in the V5.9.662 family of fixes).
            val unknownQualConfFloor = if (isPaperMode) 1.0 else 5.0
            return if (confidence >= unknownQualConfFloor) {
                PromotionResult(true, "UNKNOWN_QUALITY_${quality.ifBlank { "BLANK" }}_conf_${confidence.toInt()}_pass")
            } else {
                PromotionResult(false, "UNKNOWN_QUALITY_${quality.ifBlank { "BLANK" }}_conf_${confidence.toInt()}_below_${unknownQualConfFloor.toInt()}")
            }
        }
        // A+/A/B/C/D all pass at this gate. The DecisionEngine + EligibilityGate
        // upstream already enforced fluid score / liquidity / cooldown / memory
        // gates, all of which are now mode-symmetric (z51). No need to second-
        // guess them with another mode-asymmetric floor here.
        return PromotionResult(true, "${if (isPaperMode) "PAPER" else "LIVE"}_PASS_$quality")
    }

    // ───────────────────────────────────────────────────────────────────────────
    // POSITION / LOCK LIFECYCLE
    // ───────────────────────────────────────────────────────────────────────────

    fun releasePosition(
        mint: String,
        reason: String = "CLOSED",
        book: ExecutionBook? = null,
    ) {
        if (book != null) {
            val removed = tokenLocks.remove(lockKey(mint, book))
            if (removed != null) {
                ErrorLogger.debug(TAG, "🔓 Released $mint from ${removed.book.name}: $reason")
            }
            return
        }

        ExecutionBook.values().forEach { b ->
            val removed = tokenLocks.remove(lockKey(mint, b))
            if (removed != null) {
                ErrorLogger.debug(TAG, "🔓 Released $mint from ${removed.book.name}: $reason")
            }
        }
    }

    fun getTokenState(mint: String, book: ExecutionBook? = null): TokenState? {
        if (book != null) {
            return tokenLocks[lockKey(mint, book)]?.state
        }

        return ExecutionBook.values()
            .firstNotNullOfOrNull { b -> tokenLocks[lockKey(mint, b)]?.state }
    }

    fun getTokenBooks(mint: String): List<ExecutionBook> {
        return ExecutionBook.values().filter { b ->
            tokenLocks[lockKey(mint, b)] != null
        }
    }

    internal fun forceOpenLockForTests(
        mint: String,
        book: ExecutionBook,
        live: Boolean = true,
        ageMs: Long = LIVE_AUTH_LOCK_GRACE_MS + 1_000L,
    ) {
        tokenLocks[lockKey(mint, book)] = TokenLock(
            mint = mint,
            state = if (live) TokenState.LIVE_OPEN else TokenState.PAPER_OPEN,
            book = book,
            lockedAt = System.currentTimeMillis() - ageMs,
            lastDecisionEpoch = currentEpoch,
        )
    }

    internal fun forceAgeOpenLockForTests(mint: String, book: ExecutionBook, ageMs: Long = LIVE_AUTH_LOCK_GRACE_MS + 1_000L) {
        val key = lockKey(mint, book)
        val old = tokenLocks[key] ?: return
        tokenLocks[key] = old.copy(lockedAt = System.currentTimeMillis() - ageMs)
    }

    fun isShadowOnly(mint: String): Boolean {
        return tokenLocks[lockKey(mint, ExecutionBook.SHADOW)]?.state == TokenState.SHADOW_TRACKING
    }

    fun hasOpenPosition(mint: String): Boolean {
        return ExecutionBook.values().any { b ->
            val lock = tokenLocks[lockKey(mint, b)]
            lock != null && isAuthoritativeOpenLock(lock, isPaperMode = false)
        }
    }

    fun hasOpenPositionInBook(mint: String, book: ExecutionBook): Boolean {
        val lock = tokenLocks[lockKey(mint, book)] ?: return false
        return isAuthoritativeOpenLock(lock, isPaperMode = false)
    }

    fun getTokensInBook(book: ExecutionBook): List<String> {
        return tokenLocks
            .filter { it.key.endsWith(":${book.name}") }
            .values
            .map { it.mint }
            .distinct()
    }

    fun getOpenPositions(): List<String> {
        return tokenLocks.values
            .filter { isAuthoritativeOpenLock(it, isPaperMode = false) }
            .map { it.mint }
            .distinct()
    }

    fun getShadowTracking(): List<String> {
        return tokenLocks.values
            .filter { it.state == TokenState.SHADOW_TRACKING }
            .map { it.mint }
            .distinct()
    }

    // ───────────────────────────────────────────────────────────────────────────
    // EPOCH
    // ───────────────────────────────────────────────────────────────────────────

    fun advanceEpoch() {
        currentEpoch = System.currentTimeMillis()
    }

    // ───────────────────────────────────────────────────────────────────────────
    // MAINTENANCE / STATS
    // ───────────────────────────────────────────────────────────────────────────

    fun cleanup() {
        val now = System.currentTimeMillis()
        val staleShadowMs = 30 * 60 * 1000L

        tokenLocks.entries.removeIf { entry ->
            entry.value.state == TokenState.SHADOW_TRACKING &&
                now - entry.value.lockedAt > staleShadowMs
        }
    }

    fun getStats(): String {
        val values = tokenLocks.values
        val paperOpen = values.count { it.state == TokenState.PAPER_OPEN }
        val liveOpen = values.count { it.state == TokenState.LIVE_OPEN && isAuthoritativeOpenLock(it, isPaperMode = false) }
        val shadow = values.count { it.state == TokenState.SHADOW_TRACKING }

        return "TradeAuth: paper_open=$paperOpen live_open=$liveOpen shadow=$shadow total=${tokenLocks.size}"
    }

    fun reset() {
        tokenLocks.clear()
        currentEpoch = 0L
        ErrorLogger.info(TAG, "TradeAuthorizer reset")
    }
}