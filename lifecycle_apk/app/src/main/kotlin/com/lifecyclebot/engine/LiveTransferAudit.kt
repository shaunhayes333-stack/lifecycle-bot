package com.lifecyclebot.engine

import android.content.Context

/**
 * V5.9.1360 — P0.1 LIVE-MODE TRANSFER AUDIT.
 *
 * Operator-reported failure: the user selects LIVE in the UI but the runtime
 * stays PAPER (FDG_LIVE_ALLOW=0, EXEC_LIVE_ATTEMPT=0) and there is NO visible
 * reason. This emits ONE diagnostic line per loop tick whenever the UI-selected
 * mode is LIVE, so the operator can see exactly where the live handoff stops.
 *
 * OBSERVE-ONLY: reads existing authorities/counters and prints. It does not
 * change routing, sizing, or any gate. The one behavioural guarantee it encodes
 * is the audit rule: firstLiveBlocker is NEVER blank when LIVE is selected but
 * the runtime is not actually live.
 */
object LiveTransferAudit {

    private const val TAG = "LiveTransferAudit"

    fun emit(
        context: Context,
        uiPaperMode: Boolean,
        walletConnected: Boolean,
        walletSol: Double,
        liveTradingEnabled: Boolean,
    ) {
        if (uiPaperMode) return  // only speak when UI selected LIVE
        try {
            val auth = RuntimeModeAuthority.current()
            val runtimeLive = auth.authority == RuntimeModeAuthority.Mode.LIVE

            val readiness = try { com.lifecyclebot.network.LiveReadinessChecker.current() } catch (_: Throwable) { null }
            val readinessState = try { readiness?.state?.name ?: "UNKNOWN" } catch (_: Throwable) { "UNKNOWN" }
            val readinessSummary = try { readiness?.summary ?: "-" } catch (_: Throwable) { "-" }

            val metaContexts = try { AutonomousMetaPolicy.contextCount() } catch (_: Throwable) { -1 }
            val fwdSignatures = try { ForwardOutcomeModel.signatureCount() } catch (_: Throwable) { -1 }
            val uphTrained = try { UnifiedPolicyHead.trainedCount() } catch (_: Throwable) { -1L }
            val paperLearningLoaded = metaContexts > 0 || fwdSignatures > 0 || uphTrained > 0

            val fdgLiveAllow = try { PipelineHealthCollector.fdgLiveAllowCount() } catch (_: Throwable) { -1L }
            val fdgLiveBlock = try { PipelineHealthCollector.fdgLiveBlockCount() } catch (_: Throwable) { -1L }
            val execLiveAttempt = try { PipelineHealthCollector.execLiveAttemptCount() } catch (_: Throwable) { -1L }
            val fdgLiveSeen = if (fdgLiveAllow >= 0 && fdgLiveBlock >= 0) fdgLiveAllow + fdgLiveBlock else -1L

            val firstLiveBlocker: String = run {
                val parts = mutableListOf<String>()
                if (!runtimeLive) parts.add("AUTHORITY_NOT_LIVE")
                if (!liveTradingEnabled) parts.add("LIVE_TRADING_DISABLED")
                if (!walletConnected) parts.add("WALLET_NOT_CONNECTED")
                if (walletSol <= 0.0) parts.add("WALLET_BALANCE_ZERO")
                try { RuntimeModeAuthority.detectDesync()?.let { parts.add("MODE_DESYNC") } } catch (_: Throwable) {}
                parts.firstOrNull() ?: if (fdgLiveAllow <= 0L) "NO_LIVE_CANDIDATE_REACHED_FDG" else ""
            }
            val liveGateAllow = firstLiveBlocker.isBlank()

            val line = buildString {
                append("LIVE_MODE_TRANSFER_AUDIT:")
                append(" uiSelectedMode=LIVE")
                append(" runtimeAuthority=${auth.authority}")
                append(" walletConnected=$walletConnected")
                append(" liveReadiness=$readinessState")
                append(" readinessBlocker=${if (readinessState == "GREEN") "-" else readinessSummary}")
                append(" paperLearningLoaded=$paperLearningLoaded")
                append(" metaContexts=$metaContexts")
                append(" forwardSignatures=$fwdSignatures")
                append(" unifiedPolicyTrained=$uphTrained")
                append(" fdgLiveSeen=$fdgLiveSeen")
                append(" fdgLiveAllow=$fdgLiveAllow")
                append(" execLiveAttempt=$execLiveAttempt")
                append(" liveGateAllow=$liveGateAllow")
                append(" firstLiveBlocker=${if (firstLiveBlocker.isBlank()) "NONE" else firstLiveBlocker}")
            }
            ErrorLogger.info(TAG, line)
        } catch (_: Throwable) {
            // Audit must never break the loop.
        }
    }
}
