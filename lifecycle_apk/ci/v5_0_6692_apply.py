from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(rel: str) -> str:
    return (ROOT / rel).read_text()


def write(rel: str, text: str) -> None:
    (ROOT / rel).write_text(text)


def replace_once(rel: str, old: str, new: str, label: str) -> None:
    p = ROOT / rel
    s = p.read_text()
    n = s.count(old)
    if n != 1:
        raise SystemExit(f"{label}: expected exactly 1 old block, found {n}")
    p.write_text(s.replace(old, new, 1))
    print(f"patched {label}")


# 1) Retire the V5.0.6689 static Meme inventory choke. Canonical shared cash,
# sizing, same-mint occupancy and sellability remain the economic authorities.
slot = "app/src/main/kotlin/com/lifecyclebot/engine/SlotHealthGate.kt"
replace_once(
    slot,
    """    // Economic safety/turnover ceiling, not a strategy quota. With 14 Meme
    // lanes this still allows broad simultaneous expression while preventing
    // 100-250 funded positions from trapping the shared wallet indefinitely.
    private const val MEME_TURNOVER_ABSOLUTE_CAP_6689 = 24
""",
    """    // V5.0.6692 — STATIC MEME POSITION CAP RETIRED. A fixed inventory
    // count is not an economic authority: position sizes, liquidity and shared
    // account cash vary by orders of magnitude. Canonical sizing, same-mint
    // occupancy, capital reservation and exit sellability now bound exposure.
    // The compatibility accessor below stays for older writer callsites but is
    // deliberately unbounded so it cannot become a second stacked admission gate.
""",
    "SlotHealth static cap declaration",
)
replace_once(
    slot,
    "    fun memeTurnoverAbsoluteCap6689(): Int = MEME_TURNOVER_ABSOLUTE_CAP_6689\n",
    "    fun memeTurnoverAbsoluteCap6689(): Int = Int.MAX_VALUE\n",
    "SlotHealth compatibility cap accessor",
)
slot_text = read(slot)
start = slot_text.index("        // V5.0.6689 — source-of-truth turnover seal.")
end = slot_text.index("        // Stale soft telemetry still fails open;", start)
slot_text = slot_text[:start] + """        // V5.0.6692 — the former global 24-position turnover seal was a
        // self-inflicted choke. Do not defer merely because a static inventory
        // count was reached. The canonical writer still serializes reservations,
        // while OrderSizeResolver/CapitalAuthority determine whether cash and risk
        // actually permit another position.
""" + slot_text[end:]
old_soft = """        if (!candidateConfirmedHighEdge) {
            val activeSellJobs = try { com.lifecyclebot.engine.sell.SellJobRegistry.activeCount() } catch (_: Throwable) { 0 }
            if (activeSellJobs > 0 && openPositionCount.get() >= ENTRY_SOFT_CAP) {
                return DeferDecision(
                    true,
                    "EXITS_PRIORITY sellJobsActive=$activeSellJobs open=${openPositionCount.get()}>=$ENTRY_SOFT_CAP",
                )
            }
        }
"""
new_soft = """        if (!candidateConfirmedHighEdge) {
            val activeSellJobs = try { com.lifecyclebot.engine.sell.SellJobRegistry.activeCount() } catch (_: Throwable) { 0 }
            if (activeSellJobs > 0 && openPositionCount.get() >= ENTRY_SOFT_CAP) {
                // V5.0.6692 — advisory only. Exit pressure may shape sizing/priority
                // but may not amputate every specialist lane while shared cash exists.
                try {
                    PipelineHealthCollector.labelInc("MEME_EXIT_PRIORITY_ADVISORY_6692")
                    ForensicLogger.lifecycle(
                        "MEME_EXIT_PRIORITY_ADVISORY_6692",
                        "sellJobsActive=$activeSellJobs open=${openPositionCount.get()} soft=$ENTRY_SOFT_CAP action=advisory_only_no_entry_block",
                    )
                } catch (_: Throwable) {}
            }
        }
"""
if slot_text.count(old_soft) != 1:
    raise SystemExit(f"SlotHealth soft exit block: expected 1, found {slot_text.count(old_soft)}")
slot_text = slot_text.replace(old_soft, new_soft, 1)
old_snapshot = '"memeTurnoverCap=$MEME_TURNOVER_ABSOLUTE_CAP_6689"'
if slot_text.count(old_snapshot) != 1:
    raise SystemExit("SlotHealth snapshot cap token missing")
slot_text = slot_text.replace(old_snapshot, '"memeTurnoverCap=SHARED_CAPITAL_6692"', 1)
write(slot, slot_text)


# 2) Immutable ticket continuity. A valid sealed specialist ticket may traverse
# a CORE/source trunk; volatile version churn must not erase its owner. A
# nonterminal release must retain the exact ticket it claims to retain.
gate = "app/src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt"
replace_once(
    gate,
    """            .firstOrNull {
                it.mint == mint && it.mode.equals(mode, true) &&
                    it.candidateVersion == candidateVersion &&
                    it.canonicalLane.equals(requestedLane, true)
            }
""",
    """            .firstOrNull {
                it.mint == mint && it.mode.equals(mode, true) &&
                    (it.canonicalLane.equals(requestedLane, true) ||
                        isSourceBucketLane(requestedLane))
            }
""",
    "sealed ticket restore identity",
)
gate_text = read(gate)
needle = "        if (!validSealedDecision6613(candidate)) {\n"
idx = gate_text.index(needle, gate_text.index("private fun resolveSealedIntent6613"))
inject = """        if (candidate.candidateVersion != candidateVersion) {
            try {
                PipelineHealthCollector.labelInc("EXEC_RESTORED_TICKET_VERSION_DRIFT_6692")
                ForensicLogger.lifecycle(
                    "EXEC_RESTORED_TICKET_VERSION_DRIFT_6692",
                    "mint=${mint.take(10)} requestedVersion=$candidateVersion sealedVersion=${candidate.candidateVersion} lane=${candidate.canonicalLane} action=keep_sealed_authority_volatile_version_drift",
                )
            } catch (_: Throwable) {}
        }
        if (!candidate.canonicalLane.equals(requestedLane, true) && isSourceBucketLane(requestedLane)) {
            try {
                PipelineHealthCollector.labelInc("EXEC_RESTORED_SPECIALIST_VIA_TRUNK_6692")
                ForensicLogger.lifecycle(
                    "EXEC_RESTORED_SPECIALIST_VIA_TRUNK_6692",
                    "mint=${mint.take(10)} sealedLane=${candidate.canonicalLane} requestedLane=$requestedLane action=preserve_specialist_owner",
                )
            } catch (_: Throwable) {}
        }
"""
gate_text = gate_text[:idx] + inject + gate_text[idx:]
old_release = """        if (attemptId.isNotBlank()) executionTickets.remove(attemptId)
        allowedAttempts.entries.removeIf { (attemptId.isNotBlank() && it.value.first == attemptId) ||
            it.key == laneKey(mint, lane) }
        restorePenalties.remove(attemptId)
"""
new_release = """        val retainedTicket6548 = if (attemptId.isNotBlank()) executionTickets[attemptId] else null
        val retryStampMs6548 = System.currentTimeMillis()
        // Remove only a conflicting lane residue. Never delete the very ticket
        // this retry slot is supposed to own.
        allowedAttempts.entries.removeIf { entry ->
            entry.key == laneKey(mint, lane) &&
                (attemptId.isBlank() || entry.value.first != attemptId)
        }
        if (retainedTicket6548 != null && ticketLive(retainedTicket6548, retryStampMs6548)) {
            executionTickets[attemptId] = retainedTicket6548
            activeExecutionIntents6519[intentKey6519(
                retainedTicket6548.mode, retainedTicket6548.mint, retainedTicket6548.candidateVersion,
            )] = retainedTicket6548
            allowedAttempts[laneKey(mint, retainedTicket6548.canonicalLane)] = attemptId to retryStampMs6548
            allowedAttempts[mint.trim()] = attemptId to retryStampMs6548
            try {
                PipelineHealthCollector.labelInc("PAPER_TICKET_AUTHORITY_RETAINED_6692")
                ForensicLogger.lifecycle(
                    "PAPER_TICKET_AUTHORITY_RETAINED_6692",
                    "attemptId=$attemptId mint=${mint.take(10)} lane=${retainedTicket6548.canonicalLane} candidateVersion=${retainedTicket6548.candidateVersion} reason=$reason",
                )
            } catch (_: Throwable) {}
        }
        restorePenalties.remove(attemptId)
"""
if gate_text.count(old_release) != 1:
    raise SystemExit(f"nonterminal release deletion block: expected 1, found {gate_text.count(old_release)}")
gate_text = gate_text.replace(old_release, new_release, 1)
old_ttl = """    const val RETRY_PENDING_TTL_MS_6548: Long = 40_000L

    fun retryPendingFor6548(mint: String): RetryPending6548? {
        val key = mint.trim()
        val entry = retryPending6548[key] ?: return null
        val now = System.currentTimeMillis()
        if (now - entry.stampedAtMs > RETRY_PENDING_TTL_MS_6548) {
"""
new_ttl = """    const val RETRY_PENDING_TTL_MS_6548: Long = 40_000L
    private fun effectiveRetryPendingTtlMs6692(): Long = try {
        maxOf(
            RETRY_PENDING_TTL_MS_6548,
            com.lifecyclebot.engine.truth.AdaptiveTicketTtl6626.paperTicketTtlMs6626(),
        )
    } catch (_: Throwable) { RETRY_PENDING_TTL_MS_6548 }

    fun retryPendingFor6548(mint: String): RetryPending6548? {
        val key = mint.trim()
        val entry = retryPending6548[key] ?: return null
        val now = System.currentTimeMillis()
        if (now - entry.stampedAtMs > effectiveRetryPendingTtlMs6692()) {
"""
if gate_text.count(old_ttl) != 1:
    raise SystemExit(f"retry TTL block: expected 1, found {gate_text.count(old_ttl)}")
gate_text = gate_text.replace(old_ttl, new_ttl, 1)
old_log_ttl = '"attemptId=$attemptId mint=${key.take(10)} lane=$lane reason=$reason ttlMs=$RETRY_PENDING_TTL_MS_6548 " +'
new_log_ttl = '"attemptId=$attemptId mint=${key.take(10)} lane=$lane reason=$reason ttlMs=${effectiveRetryPendingTtlMs6692()} " +'
if gate_text.count(old_log_ttl) != 1:
    raise SystemExit("retry pending log TTL token missing")
gate_text = gate_text.replace(old_log_ttl, new_log_ttl, 1)
write(gate, gate_text)


# 3) Paper journal/canonical convergence and stale-quote economic purity.
tx = "app/src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperTransaction6486.kt"
tx_text = read(tx)
if "import java.util.concurrent.atomic.AtomicLong\n" not in tx_text:
    old_import = "import java.util.concurrent.locks.ReentrantLock\n"
    if old_import not in tx_text:
        raise SystemExit("CanonicalPaperTransaction ReentrantLock import missing")
    tx_text = tx_text.replace(old_import, old_import + "import java.util.concurrent.atomic.AtomicLong\n", 1)
marker = "    private val syntheticUnit = BigInteger.valueOf(1_000_000_000L)\n"
if tx_text.count(marker) != 1:
    raise SystemExit("CanonicalPaperTransaction syntheticUnit marker missing")
tx_text = tx_text.replace(
    marker,
    marker + "    private val lastCanonicalHistoryRepair6692Ms = AtomicLong(0L)\n"
             "    private const val CANONICAL_HISTORY_REPAIR_CADENCE_6692_MS = 60_000L\n",
    1,
)
old_reconcile = """        if (!awaitJournalBoundary6669("pre_replay")) return@withLock false
        JournalEconomicReplay6619.repairOrphanedOpenLots6662()
"""
new_reconcile = """        if (!awaitJournalBoundary6669("pre_replay")) return@withLock false
        // V5.0.6692 — repair missing journal legs from immutable canonical/typed
        // receipts before replay. Throttled so reconciliation never becomes an
        // intake hot path; first pass always runs, later passes run only while
        // ledger/journal divergence remains non-zero.
        val nowRepair6692 = System.currentTimeMillis()
        val divergence6692 = kotlin.math.abs(JournalEconomicReplay6619.latestLedgerDivergenceSol())
        val lastRepair6692 = lastCanonicalHistoryRepair6692Ms.get()
        if ((lastRepair6692 == 0L || divergence6692 > 0.001) &&
            nowRepair6692 - lastRepair6692 >= CANONICAL_HISTORY_REPAIR_CADENCE_6692_MS &&
            lastCanonicalHistoryRepair6692Ms.compareAndSet(lastRepair6692, nowRepair6692)
        ) {
            repairCryptoHistory6659()
            if (!awaitJournalBoundary6669("post_canonical_history_reprojection_6692")) return@withLock false
        }
        JournalEconomicReplay6619.repairOrphanedOpenLots6662()
"""
if tx_text.count(old_reconcile) != 1:
    raise SystemExit(f"reconcile history hook: expected 1, found {tx_text.count(old_reconcile)}")
tx_text = tx_text.replace(old_reconcile, new_reconcile, 1)
old_filter = '.filter { it.mode.equals("paper", true) && it.assetClass != AssetClass.SOLANA_TOKEN }'
if tx_text.count(old_filter) != 1:
    raise SystemExit(f"paper history asset exclusion: expected 1, found {tx_text.count(old_filter)}")
tx_text = tx_text.replace(old_filter, '.filter { it.mode.equals("paper", true) }', 1)
old_close_calc = """        val terminal = qty >= pos.remainingQtyRaw
        val canonicalRealizedPnl6569 = grossProceedsSol - basis - sellFeeSol
"""
new_close_calc = """        val terminal = qty >= pos.remainingQtyRaw
        val staleEmergencyExit6692 = exitReason.startsWith(
            "STALE_QUOTE_EMERGENCY_25PCT_BACKSTOP", ignoreCase = true,
        )
        val effectiveGrossProceeds6692 = if (staleEmergencyExit6692) {
            val boundedGross6692 = (basis * 0.75).coerceAtLeast(0.0)
            val effective6692 = minOf(grossProceedsSol, boundedGross6692)
            EconomicPurityGate6504.markUntrusted(mint, "STALE_QUOTE_UNVERIFIED_EXIT_6692")
            if (kotlin.math.abs(effective6692 - grossProceedsSol) > 1e-9) try {
                PipelineHealthCollector.labelInc("STALE_QUOTE_PAPER_PROCEEDS_CLAMPED_6692")
                ForensicLogger.lifecycle(
                    "STALE_QUOTE_PAPER_PROCEEDS_CLAMPED_6692",
                    "positionId=$positionId mint=${mint.take(10)} basis=$basis proposedGross=$grossProceedsSol boundedGross=$boundedGross6692 effectiveGross=$effective6692 action=synthetic_backstop_cannot_create_profit",
                )
            } catch (_: Throwable) {}
            effective6692
        } else grossProceedsSol
        val canonicalRealizedPnl6569 = effectiveGrossProceeds6692 - basis - sellFeeSol
"""
if tx_text.count(old_close_calc) != 1:
    raise SystemExit(f"stale close arithmetic marker: expected 1, found {tx_text.count(old_close_calc)}")
tx_text = tx_text.replace(old_close_calc, new_close_calc, 1)
close_start = tx_text.index("    fun close(positionId: String")
close_end = tx_text.index("    private fun recordCloseProjection6659", close_start)
close_seg = tx_text[close_start:close_end]
old_gross_assign = "            grossProceedsSol = grossProceedsSol, soldCostBasisSol = basis,"
if close_seg.count(old_gross_assign) != 1:
    raise SystemExit("canonical close finalize gross assignment not unique")
close_seg = close_seg.replace(
    old_gross_assign,
    "            grossProceedsSol = effectiveGrossProceeds6692, soldCostBasisSol = basis,",
    1,
)
tx_text = tx_text[:close_start] + close_seg + tx_text[close_end:]
old_label = '            PipelineHealthCollector.labelInc("CROSS_ASSET_HISTORY_REPROJECTED_6660")\n'
if tx_text.count(old_label) != 1:
    raise SystemExit("history reprojected label marker missing")
tx_text = tx_text.replace(
    old_label,
    old_label + '            PipelineHealthCollector.labelInc("PAPER_CANONICAL_HISTORY_REPROJECTED_6692")\n',
    1,
)
write(tx, tx_text)


# 4) If PAPER durable journal and shared ledger disagree, do not allow those
# outcomes to train WR/PF/EV/policy until reconciliation converges.
purity = "app/src/main/kotlin/com/lifecyclebot/engine/truth/EconomicPurityGate6504.kt"
purity_text = read(purity)
old_counter = "    private val exclusions = AtomicLong(0L)\n"
if purity_text.count(old_counter) != 1:
    raise SystemExit("EconomicPurity exclusions counter marker missing")
purity_text = purity_text.replace(
    old_counter,
    old_counter + "    private val globalPaperExclusions6692 = AtomicLong(0L)\n",
    1,
)
old_excluded = """        val historical = try {
            LearningQuarantineGate6470.isQuarantined(positionId = null, mint = mint)
        } catch (_: Throwable) { false }
        val excluded = local || invariantBroken || historical
        if (excluded) {
            exclusions.incrementAndGet()
"""
new_excluded = """        val historical = try {
            LearningQuarantineGate6470.isQuarantined(positionId = null, mint = mint)
        } catch (_: Throwable) { false }
        val unreconciledPaperAccount6692 = try {
            com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() &&
                kotlin.math.abs(JournalEconomicReplay6619.latestLedgerDivergenceSol()) > 0.001
        } catch (_: Throwable) { false }
        val excluded = local || invariantBroken || historical || unreconciledPaperAccount6692
        if (excluded) {
            exclusions.incrementAndGet()
            if (unreconciledPaperAccount6692) globalPaperExclusions6692.incrementAndGet()
"""
if purity_text.count(old_excluded) != 1:
    raise SystemExit(f"EconomicPurity exclude block: expected 1, found {purity_text.count(old_excluded)}")
purity_text = purity_text.replace(old_excluded, new_excluded, 1)
old_detail = '"mint=${mint.take(10)} local=$local invariant=$invariantBroken historical=$historical",'
if purity_text.count(old_detail) != 1:
    raise SystemExit("EconomicPurity log detail marker missing")
purity_text = purity_text.replace(
    old_detail,
    '"mint=${mint.take(10)} local=$local invariant=$invariantBroken historical=$historical paperAccountDiverged=$unreconciledPaperAccount6692",',
    1,
)
old_label_line = '                    PipelineHealthCollector.labelInc("ECONOMIC_PURITY_EXCLUSION_6504")\n'
if purity_text.count(old_label_line) != 1:
    raise SystemExit("EconomicPurity exclusion label marker missing")
purity_text = purity_text.replace(
    old_label_line,
    old_label_line + '                    if (unreconciledPaperAccount6692) PipelineHealthCollector.labelInc("ECONOMIC_PURITY_GLOBAL_PAPER_DIVERGENCE_6692")\n',
    1,
)
old_status = '"untrustedMints=${untrusted.size} queries=${queries.get()} exclusions=${exclusions.get()}"'
if purity_text.count(old_status) != 1:
    raise SystemExit("EconomicPurity status marker missing")
purity_text = purity_text.replace(
    old_status,
    '"untrustedMints=${untrusted.size} queries=${queries.get()} exclusions=${exclusions.get()} globalPaper=${globalPaperExclusions6692.get()}"',
    1,
)
old_reset = "        exclusions.set(0L)\n"
if purity_text.count(old_reset) != 1:
    raise SystemExit("EconomicPurity reset marker missing")
purity_text = purity_text.replace(
    old_reset,
    "        exclusions.set(0L); globalPaperExclusions6692.set(0L)\n",
    1,
)
write(purity, purity_text)


# 5) Remove contradictory regression assertions that literally require the old
# choke, and add a source contract for the 6692 fixes.
t6689 = "app/src/test/kotlin/com/lifecyclebot/engine/Aate6689InventoryTurnoverCompoundingTest.kt"
t = read(t6689)
first_start = t.index("    @Test\n    fun `meme turnover ceiling is canonical and precedes every soft fail open`()")
first_end = t.index("    @Test\n    fun `paper specialist sizing binds compounding ladder to shared account cash`()", first_start)
new_first = """    @Test
    fun `static meme position ceiling is retired in favor of shared capital authority`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/SlotHealthGate.kt").readText()

        assertFalse(src.contains("MEME_TURNOVER_ABSOLUTE_CAP_6689 = 24"))
        assertTrue(src.contains("memeTurnoverAbsoluteCap6689(): Int = Int.MAX_VALUE"))
        assertTrue(src.contains("memeTurnoverCap=SHARED_CAPITAL_6692"))
        val defer = src.substringAfter("fun shouldDeferBuy").substringBefore("fun snapshotLine")
        assertFalse("static inventory count must not return a hard defer", defer.contains("MEME_TURNOVER_CAP="))
        assertTrue(defer.contains("MEME_EXIT_PRIORITY_ADVISORY_6692"))
    }

"""
t = t[:first_start] + new_first + t[first_end:]
soft_start = t.index("    @Test\n    fun `soft exit priority remains below absolute turnover ceiling`()")
soft_end = t.index("    @Test\n    fun `executor canonical writer counts open plus pending atomically`()", soft_start)
new_soft_test = """    @Test
    fun `exit priority is advisory and cannot globally amputate meme entries`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/SlotHealthGate.kt").readText()
        assertTrue(src.contains("ENTRY_SOFT_CAP = 12"))
        assertTrue(src.contains("MEME_EXIT_PRIORITY_ADVISORY_6692"))
        val defer = src.substringAfter("fun shouldDeferBuy").substringBefore("fun snapshotLine")
        assertFalse(defer.contains("EXITS_PRIORITY sellJobsActive="))
    }

"""
t = t[:soft_start] + new_soft_test + t[soft_end:]
write(t6689, t)

t6659 = "app/src/test/kotlin/com/lifecyclebot/engine/Aate6659CryptoRoundTripReconciliationTest.kt"
t = read(t6659)
old_assert = '        assertTrue(transaction.contains("it.assetClass != AssetClass.SOLANA_TOKEN"))\n'
if t.count(old_assert) != 1:
    raise SystemExit("6659 old SOLANA exclusion assertion missing")
t = t.replace(
    old_assert,
    '        assertFalse(transaction.contains("it.assetClass != AssetClass.SOLANA_TOKEN"))\n'
    '        assertTrue(transaction.contains("PAPER_CANONICAL_HISTORY_REPROJECTED_6692"))\n'
    '        val reconcile = transaction.substringAfter("fun reconcileJournalAuthority6663")\n'
    '            .substringBefore("private fun awaitJournalBoundary6669")\n'
    '        assertTrue(reconcile.contains("repairCryptoHistory6659()"))\n',
    1,
)
write(t6659, t)

t6692 = ROOT / "app/src/test/kotlin/com/lifecyclebot/engine/Aate6692RuntimeRepairTest.kt"
t6692.write_text(r'''package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.6692 regression locks for the 5.0.6691 forensic repair. */
class Aate6692RuntimeRepairTest {
    @Test
    fun `nonterminal release retains immutable ticket and adaptive retry authority`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val release = src.substringAfter("fun releaseAttemptNonTerminal6514")
            .substringBefore("fun terminalizeAttempt6514")
        assertFalse(release.contains("executionTickets.remove(attemptId)"))
        assertTrue(release.contains("PAPER_TICKET_AUTHORITY_RETAINED_6692"))
        assertTrue(src.contains("effectiveRetryPendingTtlMs6692"))
        assertTrue(src.contains("EXEC_RESTORED_SPECIALIST_VIA_TRUNK_6692"))
        assertTrue(src.contains("isSourceBucketLane(requestedLane)"))
    }

    @Test
    fun `static 24 slot meme choke is gone`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/SlotHealthGate.kt").readText()
        assertFalse(src.contains("MEME_TURNOVER_ABSOLUTE_CAP_6689 = 24"))
        assertTrue(src.contains("memeTurnoverAbsoluteCap6689(): Int = Int.MAX_VALUE"))
        assertTrue(src.contains("MEME_EXIT_PRIORITY_ADVISORY_6692"))
    }

    @Test
    fun `paper canonical history repair includes solana and precedes replay`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperTransaction6486.kt").readText()
        assertFalse(src.contains("it.assetClass != AssetClass.SOLANA_TOKEN"))
        val reconcile = src.substringAfter("fun reconcileJournalAuthority6663")
            .substringBefore("private fun awaitJournalBoundary6669")
        assertTrue(reconcile.indexOf("repairCryptoHistory6659()") in 1 until reconcile.indexOf("JournalEconomicReplay6619.replay()"))
        assertTrue(src.contains("PAPER_CANONICAL_HISTORY_REPROJECTED_6692"))
    }

    @Test
    fun `stale quote synthetic backstop cannot manufacture paper profit`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperTransaction6486.kt").readText()
        val close = src.substringAfter("fun close(positionId: String")
            .substringBefore("private fun recordCloseProjection6659")
        assertTrue(close.contains("STALE_QUOTE_EMERGENCY_25PCT_BACKSTOP"))
        assertTrue(close.contains("minOf(grossProceedsSol, boundedGross6692)"))
        assertTrue(close.contains("grossProceedsSol = effectiveGrossProceeds6692"))
        assertTrue(close.contains("EconomicPurityGate6504.markUntrusted"))
    }

    @Test
    fun `paper ledger journal divergence globally quarantines learning`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/EconomicPurityGate6504.kt").readText()
        assertTrue(src.contains("JournalEconomicReplay6619.latestLedgerDivergenceSol()"))
        assertTrue(src.contains("ECONOMIC_PURITY_GLOBAL_PAPER_DIVERGENCE_6692"))
        assertTrue(src.contains("unreconciledPaperAccount6692"))
    }
}
''')


# Final guards: fail instead of silently stacking a contradictory patch.
final_slot = read(slot)
final_gate = read(gate)
final_tx = read(tx)
release_seg = final_gate[
    final_gate.index("fun releaseAttemptNonTerminal6514"):
    final_gate.index("fun terminalizeAttempt6514")
]
assert "MEME_TURNOVER_ABSOLUTE_CAP_6689 = 24" not in final_slot
assert "executionTickets.remove(attemptId)" not in release_seg
assert "grossProceedsSol = effectiveGrossProceeds6692" in final_tx
assert "it.assetClass != AssetClass.SOLANA_TOKEN" not in final_tx
print("V5.0.6692 guarded source transforms complete")
