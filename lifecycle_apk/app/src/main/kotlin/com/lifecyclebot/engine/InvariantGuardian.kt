package com.lifecyclebot.engine

object InvariantGuardian {
    enum class FaultCode {
        RUNTIME_UI_SPLIT_BRAIN, SELL_RECONCILER_DEAD, HOST_TRACKER_DESYNC,
        LANE_FANOUT_EXPLOSION, EXEC_REQUEST_INFLATION, LEARNING_LEDGER_DUPLICATION,
        PAPER_LIVE_CONTAMINATION, SCANNER_RESTORE_POISONING, MAIN_THREAD_STALL, API_LAYER_DEGRADED,
        FDG_FANOUT_EXPLOSION, FDG_SIGNAL_BYPASS, EXIT_SWEEP_UNSTABLE,
        // V5.9.1518 — PATCH ITEM 7: real choke-state diagnosis flags.
        LEDGER_DRIFT, RECONCILER_STALLED, SELL_RETRY_STORM, SCANNER_INACTIVE,
        CLOSED_BUT_WALLET_HELD, ORPHAN_LIVE_POSITIONS, LIVE_SELL_NO_FINALITY,
        BUY_PENDING_BALANCE_PROOF_STALE, TRACKER_OPEN_DESYNC_CRITICAL,
        LIVE_BUY_CONFIRMED_NOT_VISIBLE_CRITICAL, RECONCILER_BLIND_CRITICAL,
        BALANCE_AUTHORITY_FALSE_ZERO_CRITICAL
    }
    data class Fault(val code: FaultCode, val severity: String, val detail: String, val evidence: Map<String, String> = emptyMap(), val tsMs: Long = System.currentTimeMillis())

    fun check(s: RuntimeStateSnapshot, uiRunning: Boolean = s.uiState == "RUNNING"): List<Fault> {
        val out = mutableListOf<Fault>()
        val pipe = try { PipelineHealthCollector.snapshot() } catch (_: Throwable) { null }
        val liveJournalRows = pipe?.labelCounts?.get("TRADEJRNL_REC_LIVE") ?: 0L
        val sellFinalizedLabels = (pipe?.labelCounts?.get("LIFECYCLE/SELL_FINALIZED") ?: 0L) +
            (pipe?.labelCounts?.get("LIFECYCLE/SELL_FINALIZED_ONCE") ?: 0L) +
            (pipe?.labelCounts?.get("LIFECYCLE/EXEC_LIVE_SELL_ZERO_BALANCE_CONFIRMED") ?: 0L) +
            (pipe?.labelCounts?.get("LIFECYCLE/SELL_SIG_CONFIRMED") ?: 0L)
        val liveSellPathHasProof = s.reconcilerTotalChecked > 0 || liveJournalRows > 0L || sellFinalizedLabels > 0L
        // V5.9.1164 — runtime active while Main/UI is backgrounded is NORMAL.
        // Navigating to another app/activity must never become a trading fault.
        // Keep RUNTIME_UI_SPLIT_BRAIN enum for old reports, but do not emit it
        // for foreground absence.
        // V5.0.3862 — do not call the sell path dead when live journal/finality
        // evidence proves buys/sells are actually landing. A stale isStarted flag
        // is a watchdog/reporting fault, not trading truth.
        if (s.botLoopActive && !s.sellReconcilerStarted && s.liveOpenPositions > 0 && !liveSellPathHasProof) out += Fault(FaultCode.SELL_RECONCILER_DEAD, "CRITICAL", "running with live open positions but sell reconciler stopped")

        // V5.9.1518 — PATCH ITEM 1/7: ledger drift. Canonical open count exceeding
        // wallet-held mints means we are tracking positions the wallet no longer
        // holds (phantom / CLOSED-but-held) — a P0 authority fault.
        if (s.mode == "LIVE" && s.canonicalOpenPositions > 0 && s.walletHeldMints == 0) {
            // V5.0.3760: canonical>0/walletHeld=0 is not automatically a phantom;
            // it is valid during CONFIRMED_PENDING_BALANCE. Fault only if the same
            // canonical truth is invisible to host/live accounting below.
        } else if (s.mode == "LIVE" && (s.canonicalOpenPositions - s.walletHeldMints) > 0) {
            out += Fault(FaultCode.LEDGER_DRIFT, "CRITICAL",
                "canonicalOpen=${s.canonicalOpenPositions} > walletHeld=${s.walletHeldMints} (drift=${s.canonicalOpenPositions - s.walletHeldMints})")
        }
        // PATCH ITEM 1/7: reconciler stalled — LIVE wallet reconciler only.
        // V5.9.1564 — In PAPER mode canonicalOpen is the simulator book, so
        // wallet reconciler.totalChecked=0 is expected and must not become a
        // CRITICAL root cause / slot-poison signal. Paper ghosts are handled by
        // SellReconciler.paperOrphanCount + forcedOpen reaper, not wallet truth.
        if (s.mode == "LIVE" && s.botLoopActive && s.canonicalOpenPositions > 0 && s.reconcilerTotalChecked == 0 && !liveSellPathHasProof) {
            out += Fault(FaultCode.RECONCILER_STALLED, "CRITICAL",
                "reconciler.totalChecked=0 while canonicalOpen=${s.canonicalOpenPositions}")
        }
        if (s.mode == "LIVE" && s.botLoopActive && s.canonicalOpenPositions > 0 && s.liveOpenPositions == 0) {
            out += Fault(FaultCode.LIVE_BUY_CONFIRMED_NOT_VISIBLE_CRITICAL, "CRITICAL",
                "canonicalOpen=${s.canonicalOpenPositions} but liveOpen=0; confirmed buy invisible")
        }
        if (s.mode == "LIVE" && s.botLoopActive && s.hostTrackerOpenCount == 0 && s.canonicalOpenPositions > 0) {
            out += Fault(FaultCode.TRACKER_OPEN_DESYNC_CRITICAL, "CRITICAL",
                "canonicalOpen=${s.canonicalOpenPositions} but hostTrackerOpen=0")
        }
        if (s.mode == "LIVE" && s.botLoopActive && s.canonicalOpenPositions > 0 && s.reconcilerTotalChecked == 0 && !liveSellPathHasProof) {
            out += Fault(FaultCode.RECONCILER_BLIND_CRITICAL, "CRITICAL",
                "reconciler.totalChecked=0 while pending/open tracker rows exist")
        }
        // V5.0.3757 — proof-first buy health. Pending buy proof older than
        // the verification/recovery window means landed buys are not being
        // completed into authoritative sell authority.
        if (s.mode == "LIVE" && s.botLoopActive && s.staleBuyPendingBalanceProof > 0) {
            out += Fault(FaultCode.BUY_PENDING_BALANCE_PROOF_STALE, "CRITICAL",
                "BUY_PENDING_BALANCE_PROOF stale=${s.staleBuyPendingBalanceProof} older_than=90s")
        }
        // PATCH ITEM 7: orphan live positions must be forced through reconcile.
        if (s.orphanLivePositions > 0) {
            out += Fault(FaultCode.ORPHAN_LIVE_POSITIONS, "HIGH",
                "orphanLive=${s.orphanLivePositions} liveOpen=${s.liveOpenPositions} host=${s.hostTrackerOpenCount} walletHeld=${s.walletHeldMints}")
        }
        // PATCH ITEM 3/7: scanner inactive while runtime RUNNING (and not user-disabled).
        val scannerRecentlyFed = try { PipelineHealthCollector.scannerRecentlyActive(15_000L) } catch (_: Throwable) { false }
        if (uiRunning && s.botLoopActive && !s.scannerActive && !scannerRecentlyFed &&
            !(try { RuntimeRepairState.isScannerUserDisabled() } catch (_: Throwable) { false })) {
            out += Fault(FaultCode.SCANNER_INACTIVE, "HIGH",
                "scannerActive=false while RUNNING (botLoop=${s.botLoopActive}) and no recent SCAN_CB/INTAKE pulse")
        }
        // V5.9.1162 — compare live domain to live wallet truth only. Paper positions
        // are expected to have walletHeldMints=0 and must not be reported as healthy
        // live wallet drift. They are surfaced separately as paperOpenPositions.
        if (s.liveOpenPositions != s.hostTrackerOpenCount && s.mode == "LIVE") out += Fault(FaultCode.HOST_TRACKER_DESYNC, "HIGH", "liveStore=${s.liveOpenPositions} hostLive=${s.hostTrackerOpenCount} paper=${s.paperOpenPositions} walletHeld=${s.walletHeldMints}")
        val laneRatio = if (s.intake > 0) s.laneEval.toDouble() / s.intake else 0.0
        if (s.intake > 0 && laneRatio > 12.0) out += Fault(FaultCode.LANE_FANOUT_EXPLOSION, "HIGH", "laneEval/intake=${"%.2f".format(laneRatio)}")
        // V5.0.3858 — FDG forensic rows are not FDG evaluations. A single
        // approved probe can emit path=…, verdict=…, and FDG_ALLOW rows, which
        // made phaseCounts[FDG]/intake report a fake fanout explosion. Diagnose
        // fanout from unique gate outcomes (allow+block); keep raw FDG rows only
        // as telemetry.
        val fdgDecisions = pipe?.let { (it.phaseAllow["FDG"] ?: 0L) + (it.phaseBlock["FDG"] ?: 0L) }?.takeIf { it > 0L } ?: s.fdg
        val fdgRatio = if (s.intake > 0) fdgDecisions.toDouble() / s.intake else 0.0
        if (s.intake > 0 && fdgRatio > 3.0) out += Fault(FaultCode.FDG_FANOUT_EXPLOSION, "HIGH", "FDG_decisions/intake=${"%.2f".format(fdgRatio)} fdgDecisions=$fdgDecisions rawFdgRows=${pipe?.phaseCounts?.get("FDG") ?: s.fdg} intake=${s.intake}")
        val ignoredSignal = pipe?.labelCounts?.get("LIFECYCLE/FDG_BASE_SIGNAL_BLOCK_IGNORED") ?: 0L
        if (ignoredSignal > 0L) out += Fault(FaultCode.FDG_SIGNAL_BYPASS, "CRITICAL", "FDG_BASE_SIGNAL_BLOCK_IGNORED=$ignoredSignal")
        // V5.0.3740 — live sell finality authority. Doctor must not report NO_FAULT
        // while sell routing has no-signature/slippage failures, blocking leases, or
        // false CLOSED rows based on TX_PARSE/zero/no sell signature.
        if (s.mode == "LIVE") {
            val cumulativeNoSig = pipe?.labelCounts?.get("LIFECYCLE/SELL_NO_CURRENT_HELD_PROOF_NOT_RETRIED") ?: 0L
            val recentCutoffMs = System.currentTimeMillis() - 120_000L
            val noSig = pipe?.recentEvents?.count { ev ->
                ev.tsMs >= recentCutoffMs &&
                    (ev.tag == "LIFECYCLE/SELL_NO_CURRENT_HELD_PROOF_NOT_RETRIED" || ev.message.contains("SELL_NO_CURRENT_HELD_PROOF_NOT_RETRIED", true))
            }?.toLong() ?: 0L
            val slip = (pipe?.labelCounts?.get("LIFECYCLE/SLIPPAGE_EXCEEDED") ?: 0L) +
                (pipe?.recentEvents?.count { it.message.contains("0x1788", true) || it.message.contains("SLIPPAGE_EXCEEDED", true) } ?: 0)
            val closeActive = try { com.lifecyclebot.engine.sell.CloseLease.activeLeaseCount().toLong() } catch (_: Throwable) { 0L }
            val closeBlocking = try { com.lifecyclebot.engine.sell.CloseLease.activeBlockingLeaseCount().toLong() } catch (_: Throwable) { 0L }
            val falseClosed = try { HostWalletTokenTracker.countFalseTxParseClosedRows().toLong() } catch (_: Throwable) { 0L }
            // V5.0.3746 — operator spec items 9 + 11: explicit subfault detection
            // for BALANCE_UNKNOWN_REQUEUE_LOOP and CLOSE_LEASE_LEAK_AFTER_NO_SIGNATURE.
            val waitingProof = pipe?.labelCounts?.get("LIFECYCLE/SELL_WAITING_BALANCE_PROOF") ?: 0L
            val retryTempOnly = pipe?.labelCounts?.get("LIFECYCLE/SELL_RETRY_TEMPORARY_ONLY") ?: 0L
            val execLiveSellOk = pipe?.labelCounts?.get("LIFECYCLE/EXEC_LIVE_SELL_FINALIZED") ?: 0L
            val dupSuppressed = try { com.lifecyclebot.engine.sell.CloseLease.duplicateCloseAttemptsSuppressed } catch (_: Throwable) { 0L }
            val waitStateSize = try { com.lifecyclebot.engine.sell.BalanceProofWaitState.size().toLong() } catch (_: Throwable) { 0L }
            // Subfault A — proof-waits leaking into the active retry queue.
            val balanceUnknownRequeueLoop =
                waitingProof > 0L && retryTempOnly > 0L && closeBlocking > 0L &&
                execLiveSellOk == 0L && noSig > 0L
            // Subfault B — leases held after a no-signature route failure.
            val closeLeaseLeakAfterNoSignature =
                noSig > 0L && closeBlocking > 0L
            if (noSig > 0L || slip > 0L || falseClosed > 0L || (closeBlocking > 0L && noSig > 0L) ||
                balanceUnknownRequeueLoop || closeLeaseLeakAfterNoSignature) {
                val subfaults = buildList {
                    if (balanceUnknownRequeueLoop) add("BALANCE_UNKNOWN_REQUEUE_LOOP")
                    if (closeLeaseLeakAfterNoSignature) add("CLOSE_LEASE_LEAK_AFTER_NO_SIGNATURE")
                }.joinToString(",")
                out += Fault(
                    FaultCode.LIVE_SELL_NO_FINALITY,
                    "CRITICAL",
                    "live sell finality missing/corrupt: noSig=$noSig cumulativeNoSig=$cumulativeNoSig slippageOr1788=$slip " +
                        "close_lease_active=$closeActive close_lease_blocking=$closeBlocking " +
                        "falseTxParseClosed=$falseClosed waitingBalanceProof=$waitingProof " +
                        "retryTempOnly=$retryTempOnly execLiveSellOk=$execLiveSellOk " +
                        "dupSuppressed=$dupSuppressed waitStateSize=$waitStateSize " +
                        if (subfaults.isNotBlank()) "subfault=$subfaults" else "",
                    mapOf(
                        "balance_authority" to "owner_rpc_or_owner_delta_only",
                        "amount_source" to "BalanceProof",
                        "close_lease_active" to closeActive.toString(),
                        "close_lease_blocking" to closeBlocking.toString(),
                        "tx_parse_false_closed" to falseClosed.toString(),
                        "waiting_balance_proof" to waitingProof.toString(),
                        "sell_retry_temporary_only" to retryTempOnly.toString(),
                        "exec_live_sell_finalized" to execLiveSellOk.toString(),
                        "sell_duplicate_suppressed" to dupSuppressed.toString(),
                        "cumulative_no_signature" to cumulativeNoSig.toString(),
                        "wait_state_size" to waitStateSize.toString(),
                        "subfaults" to subfaults,
                    )
                )
            }
        }
        // V5.9.1125 — do NOT treat EXEC=0 + TRADEJRNL_REC>0 as split-brain.
        // The funnel EXEC counter tracks executor invocations, while EXEC_BUY/
        // EXEC_SELL/TRADEJRNL_REC track completed journaled trades. 3092 showed
        // this false-positive guard publishing PAUSE_TRADING and choking all
        // QUALITY entries. Real accounting split-brain belongs in the report,
        // not as an automatic global pause from this semantic mismatch.
        val exitReset = pipe?.labelCounts?.get("LIFECYCLE/EXIT_SWEEP_RESET") ?: 0L
        val exitTimeout = pipe?.labelCounts?.get("LIFECYCLE/EXIT_SWEEP_TIMEOUT") ?: 0L
        val workerTimeout = pipe?.labelCounts?.get("LIFECYCLE/SUPERVISOR_WORKER_TIMEOUT") ?: 0L
        if (exitReset > 10L || exitTimeout > 0L || workerTimeout > 100L) out += Fault(FaultCode.EXIT_SWEEP_UNSTABLE, "HIGH", "exitReset=$exitReset exitTimeout=$exitTimeout workerTimeout=$workerTimeout")
        val actualBuys = (s.exec).coerceAtLeast(0L)
        val execRatio = if (actualBuys > 0) (s.exec.toDouble() / actualBuys) else 0.0
        if (actualBuys > 0 && execRatio > 5.0) out += Fault(FaultCode.EXEC_REQUEST_INFLATION, "HIGH", "execOpenRequest/actualBuys=${"%.2f".format(execRatio)}")
        if (s.learningTrades != s.uniqueClosedPositionIds) out += Fault(FaultCode.LEARNING_LEDGER_DUPLICATION, "CRITICAL", "learning=${s.learningTrades} uniqueClosed=${s.uniqueClosedPositionIds}")
        if (s.mode == "LIVE" && ExecutionRouteGuard.paperBlockedInLiveCount() > 0) out += Fault(FaultCode.PAPER_LIVE_CONTAMINATION, "CRITICAL", "paper route attempted in live=${ExecutionRouteGuard.paperBlockedInLiveCount()}")
        // V5.9.1339 — POISONING CHECK FIXED: compare WINDOWED quarantine rate vs
        // WINDOWED intake, not lifetime-cumulative vs window. The old check used
        // QuarantineStore.suppressedCount() (a lifetime AtomicLong) against the
        // windowed phaseCounts["INTAKE"], so as a session aged the cumulative total
        // ALWAYS overtook intake and tripped a false SCANNER_RESTORE_POISONING fault
        // every tick. That fault drove RuntimeDoctor to force scanner concurrency=2
        // and quarantine MEME_REGISTRY_RESTORE on a perfectly healthy pipeline —
        // i.e. it strangled intake and stalled new trades ("parked, not trading").
        // Now: use the 60s windowed suppressed count, require a real margin (1.5x)
        // AND a minimum intake sample so early-session noise can't trip it.
        val quarantineWindowed = QuarantineStore.suppressedCountWindowed()
        val quarantineLifetime = QuarantineStore.suppressedCount() // telemetry only
        if (s.intake >= 30 && quarantineWindowed > s.intake * 3L / 2L) {
            out += Fault(FaultCode.SCANNER_RESTORE_POISONING, "HIGH", "quarantineWindowed=$quarantineWindowed intake=${s.intake} lifetime=$quarantineLifetime")
        }
        val mainStall = s.topBlockReasons.keys.any { it.contains("MainActivity", true) || it.contains("renderOpenPositions", true) || it.contains("onCreate", true) }
        if (s.anrHints > 0 && mainStall) out += Fault(FaultCode.MAIN_THREAD_STALL, "HIGH", "anrHints=${s.anrHints} top=${s.topBlockReasons.keys.take(3)}")
        val badApis = s.apiHealth.filterValues { it.successRatePct < 70 && it.failures >= 5 }
        // Birdeye may be intentionally locked down by conservation mode. That
        // must not generate RuntimeDoctor fanout mitigations every tick.
        //
        // V5.9.1340 — only SCANNER-CRITICAL APIs may raise API_LAYER_DEGRADED,
        // because that fault throttles scanner concurrency to 2 (RuntimeDoctor).
        // Previously ANY degraded API tripped it, so chronically-dead peripheral
        // services (groq=LLM, x=twitter sentiment, helius=backup RPC,
        // geckoterminal=secondary price) kept the meme scanner permanently choked
        // at concurrency 2 even though the LIVE intake feed (PumpPortal WS) and
        // dexscreener were 100% healthy and pumping 500 tokens. The live feed is
        // what actually drives meme intake/volume — peripheral API outages must
        // not strangle it. Non-critical degradation is logged but does NOT throttle.
        val scannerCriticalApis = setOf("pumpfun", "pumpportal", "dexscreener")
        val criticalBad = badApis.filterKeys { it.lowercase() in scannerCriticalApis }
        if (criticalBad.isNotEmpty()) {
            out += Fault(FaultCode.API_LAYER_DEGRADED, "MEDIUM", "badApis=${criticalBad.keys.joinToString(",")} (scanner-critical)")
        }
        return out
    }
}
