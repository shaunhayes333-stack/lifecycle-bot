package com.lifecyclebot.engine

/** V5.0.4368 — compact digest for sell/execution/runtime support status hooks. Report-only. */
object OperatorSellRuntimeDigest {
    fun status(): String {
        val sellForensics = try { com.lifecyclebot.engine.sell.SellForensics.snapshot().toString().take(120) } catch (_: Throwable) { "SellForensics unavailable" }
        val balanceWait = try { com.lifecyclebot.engine.sell.BalanceProofWaitState.summary().take(140) } catch (_: Throwable) { "BalanceProofWaitState unavailable" }
        val providerHealth = try { com.lifecyclebot.engine.sell.ExitProviderHealth.summary().take(140) } catch (_: Throwable) { "ExitProviderHealth unavailable" }
        val partialMismatch = try { com.lifecyclebot.engine.sell.PartialSellMismatchDetector.snapshot().size.toString() + " mints" } catch (_: Throwable) { "PartialSellMismatchDetector unavailable" }
        val walletReconciler = try { com.lifecyclebot.engine.execution.PositionWalletReconciler.snapshot().toString().take(140) } catch (_: Throwable) { "PositionWalletReconciler unavailable" }
        val hostCircuit = try { com.lifecyclebot.network.HostCircuitInterceptor.snapshot().size.toString() + " hosts" } catch (_: Throwable) { "HostCircuitInterceptor unavailable" }
        val orphans = try { com.lifecyclebot.collective.LocalOrphanStore.snapshot().size.toString() + " orphans" } catch (_: Throwable) { "LocalOrphanStore unavailable" }
        val labFeed = try { com.lifecyclebot.engine.lab.LabPromotedFeed.summary().toString().take(120) } catch (_: Throwable) { "LabPromotedFeed unavailable" }
        val voice = try { com.lifecyclebot.engine.voice.VoiceDiagnostics.snapshot().toString().take(120) } catch (_: Throwable) { "VoiceDiagnostics unavailable" }
        val safetyRefresh = try { SafetyRefreshQueue.snapshot().size.toString() + " pending" } catch (_: Throwable) { "SafetyRefreshQueue unavailable" }
        val closeLedger = try { PositionCloseLedger.snapshot().size.toString() + " closed" } catch (_: Throwable) { "PositionCloseLedger unavailable" }
        val scannerRejects = try { ScannerHardRejectStore.snapshot(8).take(140) } catch (_: Throwable) { "ScannerHardRejectStore unavailable" }
        val pumpThrottle = try { PumpPortalThrottle.snapshot().toString().take(140) } catch (_: Throwable) { "PumpPortalThrottle unavailable" }
        return "OPERATOR_SELL_RUNTIME_DIGEST_4368 sellForensics=[$sellForensics] balanceWait=[$balanceWait] providerHealth=[$providerHealth] partialMismatch=[$partialMismatch] walletReconciler=[$walletReconciler] hostCircuit=[$hostCircuit] orphans=[$orphans] labFeed=[$labFeed] voice=[$voice] safetyRefresh=[$safetyRefresh] closeLedger=[$closeLedger] scannerRejects=[$scannerRejects] pumpThrottle=[$pumpThrottle] report_only=true no_sell_authority=true no_execution_authority=true"
    }
}
