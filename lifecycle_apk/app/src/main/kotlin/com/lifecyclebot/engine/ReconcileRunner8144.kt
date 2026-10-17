package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.engine.truth.CanonicalTokenAmount
import com.lifecyclebot.engine.truth.ReconcileClassifier8144
import com.lifecyclebot.engine.truth.ReconcileLineageLedger8144
import java.math.BigInteger
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8144 — runs ReconcileClassifier8144 every five minutes on its own daemon thread (never the main
 * thread, never the bot loop) and exposes the verdict as one status line in the pipeline dump.
 *
 * It only READS: canonical lots, the latest wallet token snapshot, the signed transaction ledger, the
 * LIVE journal, the durable lineage stores, the host wallet projection and the frozen sub-ledger
 * (FrozenHeldUnsellable8141, owned elsewhere — read, never written). Its single side effect is to ask
 * the evidence-based adoption bridge (LiveCanonicalRecovery6686.requestAdoptionAsync7718) for a chain
 * holding the bot provably bought that the canonical book does not own; that bridge proves basis itself.
 *
 * Started from WalletReconciler (LIVE only). Also installs, once, the two runtime seams the pure layer
 * leaves open: the durable lineage file and the journal lookup behind ReconcileSignatureGuard8144.
 */
object ReconcileRunner8144 {
    private const val PERIOD_MS_8144 = 5L * 60_000L
    private const val MISMATCH_RECHECK_MS_8171 = 60_000L
    private const val INITIAL_DELAY_MS_8144 = 90_000L
    private const val SNAPSHOT_MAX_AGE_MS_8144 = 6L * 60_000L
    private const val JOURNAL_WINDOW_8144 = 2_000

    private val started = AtomicBoolean(false)
    private val runs = AtomicLong(0)
    @Volatile private var last: ReconcileClassifier8144.Report8144? = null
    @Volatile private var lastAtMs = 0L
    @Volatile private var lastError = ""
    @Volatile private var walletSnap8144: Map<String, CanonicalTokenAmount>? = null
    @Volatile private var walletSnapAtMs8144 = 0L
    @Volatile private var walletSnapComplete8144 = false
    // V5.0.8169 — unsigned journal buys given their local signature from SignedTxLedger8085 on the last pass.
    @Volatile private var sigRecovered8169 = 0
    @Volatile private var sigAmbiguous8169 = 0

    private val scheduler8144 by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "reconcile-8144").apply { isDaemon = true } }
    }

    /** WalletReconciler: the wallet token snapshot it just read (and whether both token programs answered). */
    fun noteWalletSnapshot8144(walletMints: Map<String, CanonicalTokenAmount>) {
        if (walletMints.isEmpty()) return
        walletSnap8144 = walletMints
        walletSnapAtMs8144 = System.currentTimeMillis()
        walletSnapComplete8144 = try { !com.lifecyclebot.engine.truth.WalletSnapshotCompleteness7140.isLastPartial() } catch (_: Throwable) { false }
    }

    fun ensureStarted8144() {
        if (!started.compareAndSet(false, true)) return
        scheduler8144.execute { installHooks8144() }
        // Healthy accounts retain the five-minute full audit; a mismatch is
        // rechecked every minute after recovery was requested, rather than
        // leaving a stale chain exception on screen for another five minutes.
        scheduler8144.scheduleWithFixedDelay({ runSafely8144() }, INITIAL_DELAY_MS_8144, MISMATCH_RECHECK_MS_8171, TimeUnit.MILLISECONDS)
    }

    private fun installHooks8144() {
        try {
            val ctx = com.lifecyclebot.AATEApp.appContextOrNull()
            if (ctx != null) ReconcileLineageLedger8144.install8144(java.io.File(ctx.filesDir, ReconcileLineageLedger8144.FILE_NAME_8144))
        } catch (_: Throwable) {}
        ReconcileSignatureGuard8144.journalLookup8144 = { mint ->
            val onMain = try { android.os.Looper.myLooper() == android.os.Looper.getMainLooper() } catch (_: Throwable) { false }
            if (onMain) emptyList()
            else TradeHistoryStore.liveRowsForMint7370(mint).filter { it.sig.isNotBlank() }.map { it.sig to it.side }
        }
    }

    fun shouldRecheck8171(lastWasHealthy: Boolean, ageMs: Long): Boolean =
        !lastWasHealthy || ageMs < 0L || ageMs >= PERIOD_MS_8144

    private fun runSafely8144() {
        try {
            val now = System.currentTimeMillis()
            if (lastAtMs > 0L && !shouldRecheck8171(last?.ok == true, now - lastAtMs)) return
            runOnce8144(now)
        } catch (t: Throwable) { lastError = t.message ?: t.javaClass.simpleName }
    }

    /** One reconciliation pass. Off the main thread only. */
    internal fun runOnce8144(nowMs: Long = System.currentTimeMillis()): ReconcileClassifier8144.Report8144 {
        val inputs = gather8144(nowMs)
        val report = ReconcileClassifier8144.classify8144(inputs)
        last = report
        lastAtMs = nowMs
        lastError = ""
        runs.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc(if (report.ok) "RECONCILE_8144_OK" else "RECONCILE_8144_EXCEPTIONS")
            report.byCause.forEach { (c, _) -> PipelineHealthCollector.labelInc("RECONCILE_8144_${c.name}") }
            ForensicLogger.lifecycle("RECONCILE_8144", statusLine8144(nowMs).take(400))
        } catch (_: Throwable) {}
        // A chain holding the bot provably bought, missing from the canonical book: hand it to the
        // evidence-based adoption bridge (it proves the basis itself, or leaves the holding observed).
        val missing = report.exceptions.filter { it.cause == ReconcileClassifier8144.Cause8144.CHAIN_BOT_HOLDING_NOT_CANONICAL }
            .mapTo(HashSet()) { it.mint }
        if (missing.isNotEmpty()) try { LiveCanonicalRecovery6686.requestAdoptionAsync7718(missing) } catch (_: Throwable) {}
        return report
    }

    private fun gather8144(nowMs: Long): ReconcileClassifier8144.Inputs8144 {
        val parentMints = HashSet<String>()
        val parentPids = HashSet<String>()
        val evidence = HashSet<String>()

        val lots = try {
            CanonicalPositionAuthority6441.protectiveInventory7807("live").map {
                ReconcileClassifier8144.Lot8144(
                    positionId = it.positionId, mint = it.mint, lifecycle = it.lifecycle.name,
                    originalRaw = it.originalQtyRaw, remainingRaw = it.remainingQtyRaw, entryCostSol = it.entryCostSol,
                    entrySource = it.entryPriceSource, quarantineReason = it.quarantineReason,
                )
            }
        } catch (_: Throwable) { emptyList() }
        // closed and non-protective quarantined LIVE lots still parent their sells
        try {
            (CanonicalPositionAuthority6441.closedPositions().filter { it.mode.equals("live", true) } +
                CanonicalPositionAuthority6441.quarantinedLivePositions7454()).forEach { parentMints += it.mint; parentPids += it.positionId }
        } catch (_: Throwable) {}
        try {
            ReconcileLineageLedger8144.snapshot8144().forEach { parentMints += it.mint; parentPids += it.positionId; evidence += it.mint }
        } catch (_: Throwable) {}
        try {
            com.lifecyclebot.engine.truth.EconomicEventSchema6464.snapshot().forEach { e ->
                if (e is com.lifecyclebot.engine.truth.EconomicEventSchema6464.Buy && e.mode.equals("live", true) && e.mint.isNotBlank()) {
                    parentMints += e.mint; parentPids += e.positionId; evidence += e.mint
                }
            }
        } catch (_: Throwable) {}
        try {
            FillLotLedger6344.snapshotForWallet(WalletManager.currentPubkey()).forEach { parentMints += it.mintAddress; evidence += it.mintAddress }
        } catch (_: Throwable) {}

        val txRows = try { com.lifecyclebot.network.SignedTxLedger8085.allRows8144() } catch (_: Throwable) { emptyList() }
        val failed = setOf(
            com.lifecyclebot.network.SignedTxLedger8085.State.FAILED_ON_CHAIN,
            com.lifecyclebot.network.SignedTxLedger8085.State.EXPIRED_PROVEN,
            com.lifecyclebot.network.SignedTxLedger8085.State.SUBMIT_REJECTED,
        )
        val pending = HashSet<String>()
        for (r in txRows) {
            if (r.mint.isBlank()) continue
            if (r.unresolved) pending += r.mint
            if (r.side == "BUY" && r.state !in failed) evidence += r.mint
            if (r.side == "BUY" && r.state == com.lifecyclebot.network.SignedTxLedger8085.State.CONFIRMED) parentMints += r.mint
        }
        val txs = txRows.map { ReconcileClassifier8144.Tx8144(it.signature, it.mint, it.side, it.state.name) }
        try { CanonicalPositionAuthority6441.pendingEntryPositions6461().filter { it.mode.equals("live", true) }.forEach { pending += it.mint } } catch (_: Throwable) {}

        val journalRows = try {
            TradeHistoryStore.getAllValidTradesSnapshot(JOURNAL_WINDOW_8144).filter { it.mode.equals("live", true) }.map { t ->
                ReconcileClassifier8144.Row8144(
                    side = t.side, mint = t.mint, positionId = t.positionId, sig = t.sig,
                    soldQty = t.soldQtyToken, consumedRaw = t.canonicalConsumedRaw, costSol = t.sol,
                    grossProceedsSol = t.grossProceedsSol, reason = t.reason, ts = t.ts,
                    fillQty = t.entryQtyToken, fillRaw = t.entryRawQty, proofState = t.proofState,
                )
            }
        } catch (_: Throwable) { emptyList() }
        // V5.0.8169 — carry the locally signed BUY signature into rows whose journal copy lost it.
        val sigRes = com.lifecyclebot.engine.truth.AccountingIntegrity8169.resolveJournalBuySignatures8169(
            journalRows,
            txRows.filter { it.side == "BUY" }.map {
                com.lifecyclebot.engine.truth.AccountingIntegrity8169.SignedBuy8169(
                    it.signature, it.mint, it.createdAtMs, it.state == com.lifecyclebot.network.SignedTxLedger8085.State.CONFIRMED,
                )
            },
        )
        sigRecovered8169 = sigRes.recovered
        sigAmbiguous8169 = sigRes.ambiguous
        val journal = sigRes.journal
        journal.forEach { if (ReconcileClassifier8144.isBuy8144(it.side) && it.mint.isNotBlank()) evidence += it.mint }

        val dust = HashSet<String>()
        val trackerRows = try { HostWalletTokenTracker.snapshot() } catch (_: Throwable) { emptyList() }
        for (p in trackerRows) {
            val botSigned = !p.buySignature.isNullOrBlank() &&
                (p.source == HostWalletTokenTracker.PositionSource.BOT_BUY || p.source == HostWalletTokenTracker.PositionSource.TX_PARSE)
            if (botSigned) evidence += p.mint
            when (p.status) {
                HostWalletTokenTracker.PositionStatus.DUST_IGNORED,
                HostWalletTokenTracker.PositionStatus.CLOSED_DUST_UNROUTABLE -> dust += p.mint
                HostWalletTokenTracker.PositionStatus.BUY_PENDING,
                HostWalletTokenTracker.PositionStatus.CONFIRMED_PENDING_BALANCE,
                HostWalletTokenTracker.PositionStatus.SELL_PENDING,
                HostWalletTokenTracker.PositionStatus.SELL_VERIFYING -> pending += p.mint
                else -> {}
            }
        }
        val hostOpen = try { HostWalletTokenTracker.getOpenForAccountingMints() } catch (_: Throwable) { null }

        val fresh = walletSnap8144?.takeIf { nowMs - walletSnapAtMs8144 <= SNAPSHOT_MAX_AGE_MS_8144 }
        val snap = fresh ?: try { WalletAccountCache.snapshot(ttlMs = SNAPSHOT_MAX_AGE_MS_8144) } catch (_: Throwable) { null }
        val complete = fresh != null && walletSnapComplete8144
        val chain: Map<String, BigInteger>? = snap?.mapValues { it.value.raw }
        val frozen = HashSet<String>()
        try { frozen += com.lifecyclebot.engine.truth.WalletTokenAccountStateAuthority7253.snapshot() } catch (_: Throwable) {}
        try { frozen += com.lifecyclebot.engine.sell.FrozenHeldUnsellable8141.heldMints8141() } catch (_: Throwable) {}
        val manual = HashSet<String>()
        chain?.keys?.forEach { m ->
            if (try { LiveCanonicalRecovery6686.isDustUnroutable7714(m) } catch (_: Throwable) { false }) dust += m
            if (try { OwnerManualHoldings7976.isOwnerManual7976(m, nowMs) } catch (_: Throwable) { false }) manual += m
        }
        return ReconcileClassifier8144.Inputs8144(
            lots = lots, chain = chain, chainComplete = complete, txs = txs, journal = journal,
            parentMints = parentMints, parentPositionIds = parentPids, botEvidenceMints = evidence,
            hostOpenMints = hostOpen, frozenMints = frozen, dustMints = dust, pendingMints = pending,
            ownerManualMints = manual,
        )
    }

    fun statusLine8144(nowMs: Long = System.currentTimeMillis()): String =
        ReconcileClassifier8144.statusLine8144(last, if (lastAtMs > 0L) nowMs - lastAtMs else 0L, runs.get(), lastError) +
            " | ${try { ReconcileLineageLedger8144.statusLine8144() } catch (_: Throwable) { "lineage8144 unavailable" }}" +
            " dupSigBlocked=${ReconcileSignatureGuard8144.blockedCount8144()} started=${started.get()}" +
            " sigRecovered8169=$sigRecovered8169 sigAmbiguous8169=$sigAmbiguous8169"
}
