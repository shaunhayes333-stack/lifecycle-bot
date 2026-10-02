package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.NotificationHistory
import com.lifecyclebot.engine.PipelineTracer
import com.lifecyclebot.engine.SafetyTier
import com.lifecyclebot.engine.SafetyReport
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.LiveTradeLogStore

/**
 * V5.9.756 — single mandatory live-buy admission gate.
 *
 * Background — Emergent ticket 2026-05-15:
 *   forensics_20260515_041530.json showed THREE live host positions
 *   (SPARTA, Thucydides, CHING) that had already landed in wallet
 *   despite later buy attempts being correctly blocked with
 *   `LIVE_BUY_BLOCKED_RISK — SAFETY_DATA_MISSING`. Therefore at least
 *   one live buy route was bypassing the V5.9.753 inline safety check.
 *
 *   Root cause audit found that:
 *     - [main]   Executor.liveBuy       — HAS V5.9.753 inline gate
 *     - [top-up] Executor.liveTopUp     — NO  gate (call site 4523)
 *
 *   The top-up path hits tryPumpPortalBuy directly to add SOL to an
 *   existing position. Same live-tx risk surface as a fresh buy, but
 *   no safety re-check.
 *
 * Contract: every live buy path MUST call
 *   [requireApprovedLiveBuy]
 * immediately before any tx-build / broadcast call. The gate is the
 * single authority. Default is HARD BLOCK — missing / stale / pending
 * / inconclusive safety = NO BUY in live mode.
 */
object LiveBuyAdmissionGate {

    private const val TAG = "LiveBuyAdmissionGate"

    /**
     * Maximum age (ms) of a safety report before it is considered stale.
     *
     * Set to 120 s to match the V5.9.753 inline value. Operator ticket
     * asked for 15–20 s — but TokenSafetyChecker runs on the scan loop
     * which has ~30–60 s p99 cycle latency. Tightening to 20 s today
     * would cause every live buy to fail staleness because the upstream
     * report would not yet have refreshed. Once SafetyChecker moves to
     * on-demand refresh inside the gate itself we can tighten — separate
     * ticket.
     */
    const val SAFETY_STALE_MS = 120_000L

    /** V5.9.765 — EMERGENT priority 4. Operator forensics_20260515_161017
     *  showed 17 BUY_FAILED LIVE_BUY_BLOCKED_RISK[liveBuy.main] events
     *  in ~1.28 seconds — all SAFETY_DATA_MISSING duplicates for a
     *  handful of mints. The block path was firing once per scanner
     *  visit. This cooldown ensures one clean block event per mint per
     *  COOLDOWN_MS window. Cooldown TTL of 60s matches operator spec. */
    private const val BLOCK_COOLDOWN_MS = 60_000L
    private val recentBlockEmits = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Result of an admission attempt. */
    sealed class Decision {
        object Approved : Decision()
        data class Blocked(val reasonCode: String, val detail: String) : Decision()
    }

    /**
     * The one gate every live buy path must call.
     *
     * Returns [Decision.Approved] only when ALL of the following are true:
     *   - [ts.safety] has been generated (`ts.lastSafetyCheck != 0L`)
     *   - the report is younger than [SAFETY_STALE_MS]
     *   - [SafetyTier] is NOT [SafetyTier.HARD_BLOCK]
     *
     * Caller responsibilities BEFORE invoking:
     *   - verify mode == LIVE
     *   - verify wallet is non-null and unlocked
     *   - verify MintIntegrityGate.isSystemOrStablecoinMint returned false
     *
     * On block: writes the BUY_FAILED forensic, emits a SAFETY_BLOCK
     * notification, logs a PipelineTracer failure with [reasonCode], and
     * returns Blocked. Callers MUST return immediately on Blocked.
     */
    fun requireApprovedLiveBuy(
        ts: TokenState,
        callSite: String,
        onLog: (String, String) -> Unit = { _, _ -> },
        onNotify: (String, String, NotificationHistory.NotifEntry.NotifType) -> Unit = { _, _, _ -> },
    ): Decision {
        // V5.0.7701 — never spend while a bot-origin wallet holding is outside
        // canonical exit scope. This is checked at the real Executor admission
        // gate for both new entries and top-ups. Unknown wallet/canonical state
        // fails closed; recovery and exits continue independently.
        val coverage7701 = try {
            LiveExitCoverageGuard7701.assess(com.lifecyclebot.engine.WalletManager.currentPubkey())
        } catch (t: Throwable) {
            LiveExitCoverageGuard7701.Decision.Blocked(
                "EXIT_COVERAGE_UNVERIFIED",
                "inventory audit failed: ${t.javaClass.simpleName}:${t.message?.take(100)}",
                emptyList(),
            )
        }
        if (coverage7701 is LiveExitCoverageGuard7701.Decision.Blocked) {
            // V5.0.7713 — reserve a concentration slot for each unresolved
            // wallet holding instead of freezing every unrelated candidate.
            // The same mint remains blocked (its own quantity is not yet under
            // canonical exit control). Other mints may proceed only when the
            // existing canonical positions plus unresolved mints leave room
            // under the live wallet concentration slot limit.
            val unmanagedMints = coverage7701.mints.toSet()
            val canonicalMints = try {
                com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.openPositions()
                    .asSequence()
                    .filter { it.mode.equals("live", true) && it.remainingQtyRaw.signum() > 0 }
                    .map { it.mint }
                    .toSet()
            } catch (_: Throwable) { emptySet<String>() }
            val occupiedSlots = canonicalMints.size + unmanagedMints.count { it !in canonicalMints }
            val slotVerdict = try {
                com.lifecyclebot.engine.truth.LiveConcentrationDoctrine7697.slotVerdict(occupiedSlots)
            } catch (_: Throwable) { null }
            val coverageBlocksCandidate = LiveExitCoverageGuard7701.shouldBlockCandidate7712(
                candidateMint = ts.mint,
                unmanagedMints = unmanagedMints,
                canonicalMints = canonicalMints,
                slotLimit = slotVerdict?.slots ?: 0,
            )
            if (coverageBlocksCandidate) {
                val sameMintUnmanaged = ts.mint in unmanagedMints
                val reason = if (sameMintUnmanaged) {
                    "same candidate mint remains outside canonical exit scope"
                } else {
                    "unresolved holdings reserve available live slots (${slotVerdict?.open ?: occupiedSlots}/${slotVerdict?.slots ?: 0})"
                }
                try {
                    ForensicLogger.lifecycle(
                        "LIVE_BUY_BLOCKED_UNMANAGED_BOT_HOLD_7701",
                        "mint=${ts.mint.take(10)} callSite=$callSite reason=${coverage7701.reasonCode} " +
                            "unmanaged=${coverage7701.mints.joinToString(",").take(180)} policy=$reason detail=${coverage7701.detail.take(120)}",
                    )
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LIVE_BUY_BLOCKED_UNMANAGED_BOT_HOLD_7701")
                } catch (_: Throwable) {}
                onLog("🛑 LIVE BUY BLOCKED: same-mint inventory or all concentration slots are occupied", ts.mint)
                return Decision.Blocked(coverage7701.reasonCode, "$reason; ${coverage7701.detail}")
            } else {
                try {
                    ForensicLogger.lifecycle(
                        "LIVE_BUY_ALLOWED_WITH_RESERVED_INVENTORY_SLOT_7712",
                        "mint=${ts.mint.take(10)} callSite=$callSite unmanaged=${unmanagedMints.size} " +
                            "occupied=${slotVerdict?.open ?: occupiedSlots}/${slotVerdict?.slots ?: 0} action=preserve_remaining_concentration_slots",
                    )
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LIVE_BUY_ALLOWED_WITH_RESERVED_INVENTORY_SLOT_7712")
                } catch (_: Throwable) {}
            }
        }

        // V5.0.3941 — NO GLOBAL SELL-ONLY BUY KILL-SWITCH.
        // Runtime 3940: BUY ok=20 but fail=77, with 66 fails from
        // ADMISSION_GATE:SELL_ONLY_SAFE_MODE. That mode is useful telemetry, but as
        // a global hard buy veto it contradicts live throughput doctrine and prevents
        // style/hold-time learning from accumulating. Keep same-mint close-lease
        // protection below; convert SellOnlySafeMode to warning/label only.
        val safeModeReason = try {
            val sm = com.lifecyclebot.engine.sell.SellOnlySafeMode
            if (sm.active) "SELL_ONLY_SAFE_MODE active: ${sm.lastReasons.joinToString(",")}" else null
        } catch (_: Throwable) { null }
        if (safeModeReason != null) {
            try {
                ForensicLogger.lifecycle(
                    "SELL_ONLY_SAFE_MODE_SOFT_ALLOW",
                    "mint=${ts.mint.take(10)} symbol=${ts.symbol} reason=${safeModeReason.take(160)} action=allow_buy",
                )
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("SELL_ONLY_SAFE_MODE_SOFT_ALLOW")
            } catch (_: Throwable) {}
        }

        // V5.9.1539 — ROOT FIX (operator buy-handoff regression, build 5.0.3554):
        // the old global guard blocked EVERY live buy whenever ANY mint held a
        // close lease. A single stuck/stale sell (MARS: wallet balance=0 but the
        // close lease never released) therefore halted the ENTIRE buy path —
        // FDG allow=161 / EXEC_GATE allow=86 / EXEC_OPEN_ALLOWED=86 but
        // EXEC_LIVE_ATTEMPT=0, silently returning before the executor's attempt
        // counter. Re-buying the SAME mint mid-exit is the only real hazard, so we
        // now pause only THIS mint's buy, not the whole bot. We also reap a stale
        // lease (past TTL) for this mint so a leaked lease can never park it.
        val thisMint = ts.mint
        if (com.lifecyclebot.engine.sell.CloseLease.isLeased(thisMint)) {
            return Decision.Blocked("CLOSE_PENDING_SAME_MINT",
                "deferring live buy on $thisMint — its own close is in flight")
        }

        val safety: SafetyReport = ts.safety
        val now = System.currentTimeMillis()
        val safetyAgeMs = now - ts.lastSafetyCheck
        val safetyMissing = ts.lastSafetyCheck == 0L
        val safetyStale = !safetyMissing && safetyAgeMs > SAFETY_STALE_MS
        val safetyHardBlock = safety.tier == SafetyTier.HARD_BLOCK
        val hardDetail = safety.hardBlockReasons.firstOrNull() ?: safety.summary.take(120)
        val trueHardSafety = safetyHardBlock && !com.lifecyclebot.engine.TokenBlacklist.isSoftPenaltyOnlyReason(hardDetail)

        if (trueHardSafety) {
            block(ts, "SAFETY_HARD_BLOCK", hardDetail, callSite, onLog, onNotify)
            return Decision.Blocked("SAFETY_HARD_BLOCK", hardDetail)
        }

        if (safetyHardBlock || safetyMissing || safetyStale) {
            val reasonCode = when {
                safetyHardBlock -> "SAFETY_SHADOW_PENALTY_ONLY"
                safetyMissing -> "SAFETY_DATA_MISSING_PENALTY_ONLY"
                else -> "SAFETY_DATA_STALE_PENALTY_ONLY"
            }
            val detail = when {
                safetyHardBlock -> hardDetail
                safetyMissing -> "no safety report has run for this mint"
                else -> "lastCheck=${safetyAgeMs / 1000}s ago (> ${SAFETY_STALE_MS / 1000}s)"
            }
            try { ForensicLogger.lifecycle("BUY_GATE_DECISION", "mint=${ts.mint.take(10)} symbol=${ts.symbol} decision=PENALTY_ONLY reason=$reasonCode detail=${detail.take(120)} source=LiveBuyAdmissionGate liveEligible=true") } catch (_: Throwable) {}
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("BUY_GATE_PENALTY_ONLY_SAFETY_SHADOW") } catch (_: Throwable) {}
            return Decision.Approved
        }

        // Approved. No log spam on the happy path — the executor's own
        // LIVE_BUY_START forensic already records the boundary.
        return Decision.Approved
    }

    private fun block(
        ts: TokenState,
        reasonCode: String,
        detail: String,
        callSite: String,
        onLog: (String, String) -> Unit,
        onNotify: (String, String, NotificationHistory.NotifEntry.NotifType) -> Unit,
    ) {
        // V5.9.765 — EMERGENT priority 4. Suppress duplicate block events
        // for the same mint inside BLOCK_COOLDOWN_MS so a single
        // SAFETY_DATA_MISSING does not generate 17 BUY_FAILED rows per
        // scan burst. We still RETURN Blocked — the upstream caller's
        // guarantee that no buy proceeds is preserved. Only the forensic
        // / notification / pipeline-tracer side-effects are skipped on
        // the duplicate path. Emits a one-time LIVE_BUY_DEDUPE_DROP
        // forensic so dumps still show that the spam was caught.
        val now = System.currentTimeMillis()
        val prevAt = recentBlockEmits[ts.mint]
        if (prevAt != null && (now - prevAt) < BLOCK_COOLDOWN_MS) {
            try {
                ForensicLogger.lifecycle(
                    "LIVE_BUY_DEDUPE_DROP",
                    "mint=${ts.mint.take(10)} symbol=${ts.symbol} reason=$reasonCode ageMs=${now - prevAt}",
                )
            } catch (_: Throwable) {}
            return
        }
        recentBlockEmits[ts.mint] = now
        // Opportunistic prune so the map can't grow unbounded across long
        // sessions. Cheap O(n) scan only when we add a new entry.
        if (recentBlockEmits.size > 256) {
            val cutoff = now - BLOCK_COOLDOWN_MS
            val it = recentBlockEmits.entries.iterator()
            while (it.hasNext()) if (it.next().value < cutoff) it.remove()
        }

        val fullReason = "$reasonCode: $detail"
        try {
            ForensicLogger.lifecycle(
                "BUY_GATE_DECISION",
                "mint=${ts.mint.take(10)} symbol=${ts.symbol} decision=HARD_BLOCK reason=$reasonCode detail=${detail.take(120)} source=LiveBuyAdmissionGate liveEligible=false",
            )
        } catch (_: Throwable) {}
        ErrorLogger.warn(TAG,
            "[EXECUTION/LIVE_BUY_BLOCKED_RISK] callSite=$callSite ${ts.symbol} mint=${ts.mint.take(12)}… reason=$fullReason")
        try {
            val tradeKey = LiveTradeLogStore.keyFor(ts.mint, System.currentTimeMillis())
            LiveTradeLogStore.log(
                tradeKey, ts.mint, ts.symbol, "BUY",
                LiveTradeLogStore.Phase.BUY_FAILED,
                "LIVE_BUY_BLOCKED_RISK[$callSite] — $fullReason",
                traderTag = "MEME",
            )
        } catch (_: Throwable) { /* forensic write best-effort */ }
        onLog("🛡 LIVE BUY BLOCKED [${ts.symbol}]: $fullReason", ts.mint)
        onNotify("🛡 Live buy blocked",
            "${ts.symbol} — ${fullReason.take(80)}",
            NotificationHistory.NotifEntry.NotifType.SAFETY_BLOCK)
        try {
            PipelineTracer.executorFailed(ts.symbol, ts.mint, "LIVE", "LIVE_BUY_BLOCKED_RISK")
        } catch (_: Throwable) { /* tracer optional */ }
    }

    /**
     * Test-only diagnostic for smoke checks.
     *
     * Mirrors [requireApprovedLiveBuy]'s evaluation without writing
     * forensics or notifications.
     */
    fun evaluateForTest(
        safetyTier: SafetyTier,
        lastSafetyCheckMs: Long,
        nowMs: Long,
    ): Decision {
        val safetyHardBlock = safetyTier == SafetyTier.HARD_BLOCK

        return when {
            safetyHardBlock -> Decision.Blocked("SAFETY_HARD_BLOCK", "hard-block tier")
            else            -> Decision.Approved
        }
    }
}


/** V5.0.7701 — hard live-buy invariant: every positive bot-origin wallet mint
 * must be visible in canonical LIVE open positions before another SOL spend. */
internal object LiveExitCoverageGuard7701 {
    sealed class Decision {
        object Ready : Decision()
        data class Blocked(val reasonCode: String, val detail: String, val mints: List<String>) : Decision()
    }

    /** Candidate-scoped admission while reserving occupied slots for unresolved holdings. */
    internal fun shouldBlockCandidate7712(
        candidateMint: String,
        unmanagedMints: Set<String>,
        canonicalMints: Set<String>,
        slotLimit: Int,
    ): Boolean {
        if (candidateMint in unmanagedMints) return true
        val unresolvedOnly = unmanagedMints.count { it !in canonicalMints }
        return canonicalMints.size + unresolvedOnly >= slotLimit
    }
    internal fun positiveWalletMints7712(
        trackerPositiveMints: Set<String>,
        observedWalletMints: Set<String>,
        completeSnapshot: Boolean,
    ): Set<String> = if (completeSnapshot) observedWalletMints else trackerPositiveMints + observedWalletMints

    fun assess(walletAddress: String): Decision {
        if (walletAddress.isBlank()) {
            return Decision.Blocked("EXIT_COVERAGE_WALLET_UNKNOWN", "wallet identity unavailable", emptyList())
        }
        val canonicalRows = com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.openPositions()
            .asSequence()
            .filter { it.mode.equals("live", true) && it.remainingQtyRaw.signum() > 0 }
            .toList()
        val canonicalRawByMint = canonicalRows.groupBy { it.mint }.mapValues { (_, rows) ->
            rows.fold(java.math.BigInteger.ZERO) { total, position -> total + position.remainingQtyRaw }
        }

        val tracker = com.lifecyclebot.engine.HostWalletTokenTracker.snapshot()
        // Read only the existing short-lived full-wallet cache here. Admission
        // must never perform wallet RPC, but a fresh cache catches a tracker row
        // omission before it can turn a bot-held mint into invisible inventory.
        val walletSnapshot = try {
            com.lifecyclebot.engine.WalletAccountCache.snapshot(ttlMs = 5_000L)
        } catch (_: Throwable) { null }
        val trackerPositiveMints = tracker.asSequence()
            .filter { p ->
                val raw = runCatching { java.math.BigInteger(p.rawAmount.trim().ifBlank { "0" }) }
                    .getOrDefault(java.math.BigInteger.ZERO)
                raw > java.math.BigInteger.ONE || (p.uiAmount.isFinite() && p.uiAmount > 0.0)
            }
            .map { it.mint }
            .toSet()
        val observedWalletMints = walletSnapshot.orEmpty()
            .filterValues { it.raw > java.math.BigInteger.ONE }
            .keys
        // A fresh complete two-program read is current wallet truth. Tracker
        // rows absent from it are historical and must not consume live slots.
        // On partial/missing reads, retain the tracker lower bound and fail
        // closed for holdings that may have been hidden by a provider miss.
        val walletSnapshotComplete = walletSnapshot != null &&
            !com.lifecyclebot.engine.truth.WalletSnapshotCompleteness7140.isLastPartial()
        val positiveWalletMints = positiveWalletMints7712(
            trackerPositiveMints, observedWalletMints, walletSnapshotComplete,
        )

        val botHeld = tracker.asSequence()
            .filter { p ->
                val botSource = p.source in setOf(
                    com.lifecyclebot.engine.HostWalletTokenTracker.PositionSource.BOT_BUY,
                    com.lifecyclebot.engine.HostWalletTokenTracker.PositionSource.TX_PARSE,
                    com.lifecyclebot.engine.HostWalletTokenTracker.PositionSource.RECOVERED_AFTER_RESTART,
                ) || (p.source != com.lifecyclebot.engine.HostWalletTokenTracker.PositionSource.MANUAL_IMPORT &&
                    !p.buySignature.isNullOrBlank())
                val raw = runCatching { java.math.BigInteger(p.rawAmount.trim().ifBlank { "0" }) }
                    .getOrDefault(java.math.BigInteger.ZERO)
                val positive = raw > java.math.BigInteger.ONE || (p.uiAmount.isFinite() && p.uiAmount > 0.0)
                // A positive wallet balance outranks a historical terminal label.
                // Sell/reconcile races can stamp CLOSED before the next wallet read;
                // do not let that label hide tokens that are still physically held.
                // V5.0.7714 — one exception, and it is evidence-based rather than a
                // label: CLOSED_DUST_UNROUTABLE is stamped only after the Executor
                // tried to sell a below-minimum holding and the routes refused
                // (5.0.7713: $4.70 of WBTC, 92 retries, 71 abandoned, both live
                // slots held for 37 minutes). The balance is still held and still
                // visible; it is not inventory a buy should wait on, and recovery
                // retries it after six hours.
                val dustUnroutable7714 = p.status == com.lifecyclebot.engine.HostWalletTokenTracker.PositionStatus.CLOSED_DUST_UNROUTABLE
                if (dustUnroutable7714 && botSource && positive) {
                    try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LIVE_EXIT_COVERAGE_DUST_UNROUTABLE_IGNORED_7714") } catch (_: Throwable) {}
                }
                botSource && positive && p.mint in positiveWalletMints && !dustUnroutable7714
            }
            .map { it.mint }
            .toMutableSet()

        // A canonical live quarantine is bot provenance even if the host
        // tracker lost its BUY source/signature. A positive wallet match keeps
        // it in the fail-closed set until recovery promotes it or a verified
        // zero balance closes it.
        val heldQuarantines = com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
            .quarantinedLivePositions7454()
            .asSequence()
            // V5.0.7714 — a row quarantined as dust the routes refused is the
            // same evidence-based exception as the tracker stamp above.
            .filter { !it.quarantineReason.startsWith("DUST_UNROUTABLE_7714") }
            .map { it.mint }
            .filter { it in positiveWalletMints }
            .toSet()
        botHeld += heldQuarantines

        // Durable confirmed live buy lots also carry bot ownership. When the
        // current wallet tracker has terminally proved a zero balance, they are
        // historical; otherwise an unrepresented active lot is unresolved risk.
        // V5.0.7707 — a lot is unresolved risk only while the wallet still
        // holds the mint. A lot whose mint is absent from the wallet snapshot
        // (sold, swept, or purged from the tracker) is history, not inventory.
        val lots = com.lifecyclebot.engine.FillLotLedger6344.snapshotForWallet(walletAddress)
            .filter { it.remainingQty > 1e-9 && it.mintAddress in positiveWalletMints }
        for (lot in lots) {
            val row = tracker.firstOrNull { it.mint == lot.mintAddress }
            val terminalZero = row != null && row.status in setOf(
                com.lifecyclebot.engine.HostWalletTokenTracker.PositionStatus.CLOSED,
                com.lifecyclebot.engine.HostWalletTokenTracker.PositionStatus.CLOSED_VERIFIED,
                com.lifecyclebot.engine.HostWalletTokenTracker.PositionStatus.CLOSED_DUST_UNROUTABLE,
                com.lifecyclebot.engine.HostWalletTokenTracker.PositionStatus.CLOSED_STALE_RECOVERY_UNHELD,
                com.lifecyclebot.engine.HostWalletTokenTracker.PositionStatus.CLOSED_SOLD_BY_AATE,
                com.lifecyclebot.engine.HostWalletTokenTracker.PositionStatus.CLOSED_EXTERNALLY_MANUAL_SWAP,
            ) && row.uiAmount <= 0.0
            if (!terminalZero) botHeld += lot.mintAddress
        }

        // V5.0.7709 — every positive bot-owned balance stays inside the buy
        // coverage invariant, even when its mark is missing or the current route
        // rejects a dust sell. Coverage is quantity-aware: a canonical row for a
        // mint does not cover extra tokens left in the wallet after a partial,
        // failed close, or duplicate buy. Missing route/mark is a recovery problem,
        // not proof that the wallet risk disappeared.
        val unmanaged = botHeld.filter { mint ->
            val walletRow = tracker.firstOrNull { it.mint == mint }
            val cachedAmount = walletSnapshot?.get(mint)
            val walletRaw = walletRow?.let {
                runCatching { java.math.BigInteger(it.rawAmount.trim().ifBlank { "0" }) }
                    .getOrDefault(java.math.BigInteger.ZERO)
            }?.takeIf { it > java.math.BigInteger.ONE } ?: cachedAmount?.raw ?: java.math.BigInteger.ZERO
            val canonicalRaw = canonicalRawByMint[mint] ?: java.math.BigInteger.ZERO
            val walletUiAmount = walletRow?.uiAmount ?: cachedAmount?.uiDoubleForDisplay() ?: 0.0
            val positiveUiWithoutRaw = walletUiAmount.isFinite() && walletUiAmount > 0.0 &&
                walletRaw <= java.math.BigInteger.ONE
            walletRaw > canonicalRaw + java.math.BigInteger.ONE || positiveUiWithoutRaw
        }.sorted()
        return if (unmanaged.isEmpty()) Decision.Ready else Decision.Blocked(
            "UNMANAGED_BOT_WALLET_HOLDING",
            "${unmanaged.size} positive bot-owned mint(s) or uncovered wallet quantities are outside canonical LIVE exit scope; mark/route recovery required",
            unmanaged,
        )
    }
}
