from pathlib import Path

ROOT = Path("lifecycle_apk")
lane = ROOT / "app/src/main/kotlin/com/lifecyclebot/engine/truth/LaneCapitalFairness6732.kt"
gate = ROOT / "app/src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt"
hero = ROOT / "app/src/main/kotlin/com/lifecyclebot/engine/truth/UnifiedAccountSnapshot6635.kt"
spine = ROOT / "app/src/main/kotlin/com/lifecyclebot/engine/truth/ExecutionSpineAcceptance6647.kt"
audit = ROOT / "app/src/main/kotlin/com/lifecyclebot/engine/truth/AcceptanceInvariantAudit6441.kt"
causal = ROOT / "app/src/main/kotlin/com/lifecyclebot/engine/truth/CausalFeedbackAuthority6715.kt"
test = ROOT / "app/src/test/kotlin/com/lifecyclebot/engine/Aate6805CausalIntegrityRepairTest.kt"
golden = ROOT / "app/src/test/kotlin/com/lifecyclebot/engine/GoldenTapeRegressionTest.kt"
smoke = Path("ci/runtime-test.sh")


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    n = text.count(old)
    if n != 1:
        raise SystemExit(f"{label}: expected exactly one match in {path}, found {n}")
    path.write_text(text.replace(old, new, 1))


# 4. Lane concentration ceiling: capital target is not enough; a lane may not
# silently own most of the book even when its expectancy multiplier is high.
replace_once(
    lane,
    "    private const val LANE_HEADROOM_RATIO = 1.10\n",
    """    private const val LANE_HEADROOM_RATIO = 1.10
    // V5.0.6805 §LANE_CONCENTRATION_CEILING — once the meme book has enough
    // inventory for diversification to be meaningful, no single lane may own
    // more than 35% of open positions. Four positions are always allowed during
    // bootstrap so a small book is not deadlocked by percentages.
    private const val LANE_INVENTORY_MAX_SHARE_6805 = 0.35
    private const val LANE_INVENTORY_SHARE_MIN_BOOK_6805 = 10
    private const val LANE_INVENTORY_MIN_ABSOLUTE_6805 = 4
""",
    "lane concentration constants",
)
replace_once(
    lane,
    """    data class Headroom(
        val hasHeadroom: Boolean,
        val lane: String,
        val usedSol: Double,
        val targetSol: Double,
        val utilization: Double,
    )""",
    """    data class Headroom(
        val hasHeadroom: Boolean,
        val lane: String,
        val usedSol: Double,
        val targetSol: Double,
        val utilization: Double,
        val openPositions: Int = 0,
        val totalMemeOpenPositions: Int = 0,
        val projectedInventoryShare: Double = 0.0,
        val inventoryCapped: Boolean = false,
    )""",
    "headroom inventory fields",
)
replace_once(
    lane,
    """            val positions = CanonicalPositionAuthority6441.openPositions()
            val laneOwned = positions.filter {
                it.mode.equals(mode, true) && (
                    it.lane.equals(nl, true) || (nl == "BLUECHIP" && it.lane.equals("BLUE_CHIP", true))
                )
            }
            val used = laneOwned.sumOf { (it.entryCostSol - it.soldCostBasisSol).coerceAtLeast(0.0) }
            val targetSol = laneTargetSol(nl, sharedEquity)
            val util = if (targetSol > 0.0) used / targetSol else 0.0
            val ok = util < LANE_HEADROOM_RATIO
            if (ok) {
                try { PipelineHealthCollector.labelInc("LANE_HEADROOM_OK_6732_${nl}") } catch (_: Throwable) {}
            } else {
                try { PipelineHealthCollector.labelInc("LANE_HEADROOM_SATURATED_6732_${nl}") } catch (_: Throwable) {}
            }
            Headroom(ok, nl, used, targetSol, util)""",
    """            val positions = CanonicalPositionAuthority6441.openPositions()
            val memeOwned6805 = positions.filter {
                it.mode.equals(mode, true) && normLane(it.lane) in MEME_LANES
            }
            val laneOwned = memeOwned6805.filter { normLane(it.lane) == nl }
            val used = laneOwned.sumOf { (it.entryCostSol - it.soldCostBasisSol).coerceAtLeast(0.0) }
            val targetSol = laneTargetSol(nl, sharedEquity)
            val util = if (targetSol > 0.0) used / targetSol else 0.0
            val projectedLaneOpen6805 = laneOwned.size + 1
            val projectedBookOpen6805 = memeOwned6805.size + 1
            val projectedShare6805 = if (projectedBookOpen6805 > 0)
                projectedLaneOpen6805.toDouble() / projectedBookOpen6805.toDouble() else 0.0
            val inventoryCapped6805 = projectedBookOpen6805 >= LANE_INVENTORY_SHARE_MIN_BOOK_6805 &&
                projectedLaneOpen6805 > LANE_INVENTORY_MIN_ABSOLUTE_6805 &&
                projectedShare6805 > LANE_INVENTORY_MAX_SHARE_6805
            val ok = util < LANE_HEADROOM_RATIO && !inventoryCapped6805
            if (inventoryCapped6805) {
                try {
                    PipelineHealthCollector.labelInc("LANE_INVENTORY_CONCENTRATION_CAPPED_6805")
                    PipelineHealthCollector.labelInc("LANE_INVENTORY_CONCENTRATION_CAPPED_6805_${nl}")
                } catch (_: Throwable) {}
            } else if (ok) {
                try { PipelineHealthCollector.labelInc("LANE_HEADROOM_OK_6732_${nl}") } catch (_: Throwable) {}
            } else {
                try { PipelineHealthCollector.labelInc("LANE_HEADROOM_SATURATED_6732_${nl}") } catch (_: Throwable) {}
            }
            Headroom(
                ok, nl, used, targetSol, util,
                openPositions = laneOwned.size,
                totalMemeOpenPositions = memeOwned6805.size,
                projectedInventoryShare = projectedShare6805,
                inventoryCapped = inventoryCapped6805,
            )""",
    "lane concentration computation",
)

# Enforce the inventory ceiling directly at executable-open, not merely as a
# global-throughput bypass decision.
replace_once(
    gate,
    """        val throughputVerdict6727 = try {
            com.lifecyclebot.engine.truth.ExitThroughputAuthority6727.evaluate(modeUpper, lane)
        } catch (_: Throwable) { null }""",
    """        val laneInventory6805 = try {
            com.lifecyclebot.engine.truth.LaneCapitalFairness6732.headroomFor(modeUpper, canonicalSelectedLane)
        } catch (_: Throwable) { null }
        if (laneInventory6805?.inventoryCapped == true) {
            try {
                PipelineHealthCollector.labelInc("EXEC_OPEN_BLOCKED_LANE_INVENTORY_CEILING_6805")
                PipelineHealthCollector.labelInc("EXEC_OPEN_BLOCKED_LANE_INVENTORY_CEILING_6805_${canonicalSelectedLane}")
                ForensicLogger.lifecycle(
                    "EXEC_OPEN_BLOCKED_LANE_INVENTORY_CEILING_6805",
                    "attemptId=$execKey mint=${mint.take(10)} lane=$canonicalSelectedLane " +
                        "open=${laneInventory6805.openPositions}/${laneInventory6805.totalMemeOpenPositions} " +
                        "projectedShare=${"%.3f".format(laneInventory6805.projectedInventoryShare)} cap=0.35 action=defer_until_other_lanes_or_exits_rebalance",
                )
            } catch (_: Throwable) {}
            return blocked(
                "EXEC_OPEN_BLOCKED_LANE_INVENTORY_CEILING_6805",
                "lane=$canonicalSelectedLane projectedShare=${"%.3f".format(laneInventory6805.projectedInventoryShare)} cap=0.35",
                shadow = modeUpper == "PAPER",
            )
        }
        val throughputVerdict6727 = try {
            com.lifecyclebot.engine.truth.ExitThroughputAuthority6727.evaluate(modeUpper, lane)
        } catch (_: Throwable) { null }""",
    "wire lane concentration gate",
)

# 5. Retire journal replay as a UI hero authority. The hero reads the canonical
# capital authority directly; replay remains forensic/repair telemetry elsewhere.
replace_once(
    hero,
    """        // Read-path purity: reconciliation may observe and report deltas, but
        // this UI-facing method must never repair, project, refund, or mutate
        // canonical economic state as a side effect of rendering a balance.
        try { ForensicReconciliation6635.reconcile6635() } catch (_: Throwable) {}

        val paperMode = mode.equals("paper", true)
        val journal = if (paperMode) try { JournalEconomicAuthority6616.currentSnapshot() } catch (_: Throwable) { null } else null
        val capital = try { PaperCapitalAuthority6577.snapshot() } catch (_: Throwable) { null }
        val markAuthority = try { CanonicalCapitalAuthority6450.snapshot() } catch (_: Throwable) { null }

        // V5.0.6756 — UI paper economics MUST be an immutable mutation revision.
        // Do not reconstruct these values independently on every screen read.
        val cash = if (paperMode && journal != null) journal.cashSol else capital?.availableCashSol ?: 0.0
        val realized = if (paperMode && journal != null) journal.realizedPnlSol else capital?.realizedPnlSol ?: 0.0
        val openCost = if (paperMode && journal != null) journal.openMarketValueSol else capital?.openMarketValueSol ?: 0.0
        val equity = if (paperMode && journal != null) journal.equitySol else cash + openCost
        val revision = if (paperMode) journal?.revision ?: -1L else -1L
        val source = if (paperMode) journal?.source ?: "PAPER_LEDGER_WARMUP_FALLBACK" else "LIVE_CAPITAL_AUTHORITY"
""",
    """        // V5.0.6805 §RETIRE_JOURNAL_REPLAY_ACCOUNTING — UI is a pure
        // renderer of canonical capital. TRADE_JOURNAL_REPLAY_6619 is forensic
        // history/recovery only and can no longer decide hero availability,
        // balances, equity, or reconciliation state.
        val paperMode = mode.equals("paper", true)
        val markAuthority = try { CanonicalCapitalAuthority6450.snapshot() } catch (_: Throwable) { null }
        val cash = markAuthority?.cashSol ?: 0.0
        val realized = markAuthority?.realizedPnlSol ?: 0.0
        val openCost = markAuthority?.openMarketValueSol ?: 0.0
        val equity = markAuthority?.totalEquitySol ?: (cash + openCost)
        // Stable value-derived revision: identical canonical economics render the
        // same revision across MEME / MARKETS / CRYPTO hero reads.
        val revision = markAuthority?.hashCode()?.toLong() ?: -1L
        val source = "CANONICAL_CAPITAL_AUTHORITY_6450"
""",
    "hero canonical authority",
)
replace_once(
    hero,
    """        // Market marks remain diagnostic until they are captured in the same
        // immutable account transaction; never splice a second-time mark into
        // a paper hero revision.
        val unrealized = if (paperMode) 0.0 else markAuthority?.unrealizedPnlSol ?: 0.0

        val forensicLine = try { ForensicReconciliation6635.healthLine6635() } catch (_: Throwable) { "" }
        val reconciliationStatus = when {
            forensicLine.contains("status=RECONCILED") -> Status.RECONCILED
            forensicLine.contains("status=FAILED") -> Status.FAILED
            else -> Status.WARMUP
        }
        // A paper hero is not truly reconciled until a journal revision exists.
        val status = if (paperMode && journal == null && reconciliationStatus == Status.RECONCILED)
            Status.WARMUP else reconciliationStatus""",
    """        val unrealized = markAuthority?.unrealizedPnlSol ?: 0.0
        val canonicalDelta6805 = markAuthority?.conservationDeltaSol
        val canonicalHealthy6805 = canonicalDelta6805 != null && canonicalDelta6805.isFinite() &&
            kotlin.math.abs(canonicalDelta6805) <= 1e-4
        val status = when {
            markAuthority == null -> Status.WARMUP
            canonicalHealthy6805 -> Status.RECONCILED
            else -> Status.FAILED
        }
        val forensicLine = if (markAuthority == null) {
            "source=CANONICAL_CAPITAL_AUTHORITY_6450 status=WARMUP"
        } else {
            "source=CANONICAL_CAPITAL_AUTHORITY_6450 status=${status.name} " +
                "cash=${"%.6f".format(markAuthority.cashSol)} openMV=${"%.6f".format(markAuthority.openMarketValueSol)} " +
                "realized=${"%.6f".format(markAuthority.realizedPnlSol)} fees=${"%.6f".format(markAuthority.feesSol)} " +
                "equity=${"%.6f".format(markAuthority.totalEquitySol)} delta=${"%.9f".format(markAuthority.conservationDeltaSol)}"
        }""",
    "hero canonical status",
)

# Acceptance witness: replace journal/forensic replay deltas with canonical capital
# conservation and canonical quantity integrity. Keep Observation field names for
# test compatibility, but their source is now canonical only.
replace_once(
    spine,
    '        "HERO_JOURNAL_PARITY_FAIL_6616",\n',
    '',
    "remove hero journal watched label",
)
replace_once(
    spine,
    """            val forensic = try { ForensicReconciliation6635.deltas6647() } catch (_: Throwable) { null }
            // A dispatch begun at the sampling edge may still be legitimately in
            // flight; terminal-cardinality applies after a bounded grace period.
            val cardinality = try {
                CanonicalEntryAuthority6551.cardinalityForWindow6647(
                    start.atMs, (end.atMs - 10_000L).coerceAtLeast(start.atMs),
                )
            } catch (_: Throwable) { null }
            val reconciledDelta: (Double?) -> Double = { value ->
                if (forensic?.reconciled == true && value != null) value else Double.NaN
            }
            val canonicalOpenPositions = try { CanonicalPositionAuthority6441.openPositions() } catch (_: Throwable) { emptyList() }""",
    """            val canonicalCapital6805 = try { CanonicalCapitalAuthority6450.snapshot() } catch (_: Throwable) { null }
            // A dispatch begun at the sampling edge may still be legitimately in
            // flight; terminal-cardinality applies after a bounded grace period.
            val cardinality = try {
                CanonicalEntryAuthority6551.cardinalityForWindow6647(
                    start.atMs, (end.atMs - 10_000L).coerceAtLeast(start.atMs),
                )
            } catch (_: Throwable) { null }
            val canonicalOpenPositions = try { CanonicalPositionAuthority6441.openPositions() } catch (_: Throwable) { emptyList() }
            val canonicalClosedPositions6805 = try { CanonicalPositionAuthority6441.closedPositions() } catch (_: Throwable) { emptyList() }
            val canonicalQuantityHealthy6805 = canonicalOpenPositions.none { it.remainingQtyRaw <= java.math.BigInteger.ZERO } &&
                canonicalClosedPositions6805.none { it.remainingQtyRaw != java.math.BigInteger.ZERO }""",
    "acceptance canonical snapshot",
)
replace_once(
    spine,
    """                cashDeltaSol = reconciledDelta(forensic?.cashSol),
                basisDeltaSol = reconciledDelta(forensic?.basisSol),
                realizedDeltaSol = reconciledDelta(forensic?.realizedSol),
                quantityDeltaRaw = if (forensic?.reconciled == true) forensic.quantityRaw else java.math.BigInteger.ONE,
                heroJournalParityFail = delta("HERO_JOURNAL_PARITY_FAIL_6616"),""",
    """                // Compatibility field names; source is CANONICAL_CAPITAL_AUTHORITY_6450.
                cashDeltaSol = canonicalCapital6805?.conservationDeltaSol ?: Double.NaN,
                basisDeltaSol = 0.0,
                realizedDeltaSol = 0.0,
                quantityDeltaRaw = if (canonicalQuantityHealthy6805) java.math.BigInteger.ZERO else java.math.BigInteger.ONE,
                heroJournalParityFail = 0L,""",
    "acceptance canonical invariant fields",
)

# Acceptance audit itself explicitly checks canonical capital conservation.
replace_once(
    audit,
    """        val cash = CanonicalPositionAuthority6441.paperCashSol()
        if (cash >= 0.0) passed.add("cash>=0") else failed.add("cash<0=$cash")
""",
    """        val cash = CanonicalPositionAuthority6441.paperCashSol()
        if (cash >= 0.0) passed.add("cash>=0") else failed.add("cash<0=$cash")
        // V5.0.6805 — acceptance accounting authority is canonical capital only.
        // Journal replay divergence is forensic history and cannot fail acceptance.
        val capital6805 = try { CanonicalCapitalAuthority6450.snapshot() } catch (_: Throwable) { null }
        if (capital6805 != null && capital6805.conservationDeltaSol.isFinite() &&
            kotlin.math.abs(capital6805.conservationDeltaSol) <= 1e-4) {
            passed.add("canonical_capital_conserved_6450")
        } else {
            failed.add("canonical_capital_invalid_6450:${capital6805?.conservationDeltaSol ?: Double.NaN}")
        }
""",
    "acceptance canonical capital check",
)

# 6. Causal feedback: a terminal outcome is exact-band learning truth, not a
# lane-wide epoch bomb. Preserve lane aggregate stats but invalidate only tickets
# in the same mode×lane×score-band cohort.
replace_once(
    causal,
    """            val invalidated = linkedSetOf<String>()
            ks.forEach { k ->
                val s = state(k)
                s.openPositions.remove(env.positionId)
                invalidated.addAll(s.reservedAttempts)
                s.terminalEpoch += 1L
            }
            invalidated.forEach { releaseAttemptLocked(it, removeStamp = true) }""",
    """            val invalidated = linkedSetOf<String>()
            val terminalScopeKeys6805 = ks.filter { it.startsWith("BAND|") }
            // Remove the closed position from both aggregate lane and exact band,
            // but advance freshness only for the exact cohort that learned new truth.
            ks.forEach { k -> state(k).openPositions.remove(env.positionId) }
            terminalScopeKeys6805.forEach { k ->
                val s = state(k)
                invalidated.addAll(s.reservedAttempts)
                s.terminalEpoch += 1L
            }
            invalidated.forEach { releaseAttemptLocked(it, removeStamp = true) }
            emit(
                "CAUSAL_SCOPE_LOCAL_INVALIDATION_6805",
                "positionId=${env.positionId.take(24)} lane=$nl scopes=${terminalScopeKeys6805.joinToString(",")} invalidated=${invalidated.size}",
            )""",
    "band-local terminal invalidation",
)
replace_once(
    causal,
    """                    ks.forEach { k -> state(k).apply {
                        learningRevision += 1L
                        cleanLearnedCloses += 1
                        if (isWin6721) wins += 1 else losses += 1
                    } }""",
    """                    ks.forEach { k -> state(k).apply {
                        if (k.startsWith("BAND|")) learningRevision += 1L
                        cleanLearnedCloses += 1
                        if (isWin6721) wins += 1 else losses += 1
                    } }""",
    "early ack band-local revision",
)
replace_once(
    causal,
    """            ks.forEach { k ->
                val s = state(k)
                s.pendingLearning.remove(positionId)
                s.learningRevision += 1L
                s.cleanLearnedCloses += 1
            }""",
    """            ks.forEach { k ->
                val s = state(k)
                s.pendingLearning.remove(positionId)
                if (k.startsWith("BAND|")) s.learningRevision += 1L
                s.cleanLearnedCloses += 1
            }""",
    "learn ack band-local revision",
)

# Extend dedicated source-regression test generated by the primary script.
t = test.read_text()
if not t.endswith("\n}\n"):
    raise SystemExit("Aate6805 test unexpected ending")
addition = '''

    @Test fun additionalAuthorityRepairsAreSourcePinned() {
        val lane = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/LaneCapitalFairness6732.kt").readText()
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val hero = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/UnifiedAccountSnapshot6635.kt").readText()
        val spine = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/ExecutionSpineAcceptance6647.kt").readText()
        val audit = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/AcceptanceInvariantAudit6441.kt").readText()
        val causal = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CausalFeedbackAuthority6715.kt").readText()
        assertTrue(lane.contains("LANE_INVENTORY_MAX_SHARE_6805 = 0.35"))
        assertTrue(gate.contains("EXEC_OPEN_BLOCKED_LANE_INVENTORY_CEILING_6805"))
        assertTrue(hero.contains("CANONICAL_CAPITAL_AUTHORITY_6450"))
        assertFalse(hero.contains("JournalEconomicAuthority6616.currentSnapshot()"))
        assertFalse(hero.contains("ForensicReconciliation6635.reconcile6635()"))
        assertFalse(spine.contains("ForensicReconciliation6635.deltas6647()"))
        assertTrue(audit.contains("canonical_capital_conserved_6450"))
        assertTrue(causal.contains("CAUSAL_SCOPE_LOCAL_INVALIDATION_6805"))
        assertTrue(causal.contains("terminalScopeKeys6805 = ks.filter { it.startsWith(\"BAND|\") }"))
    }
'''
test.write_text(t[:-3] + addition + "\n}\n")

# Golden Tape gets an independent lock for the new operator directives.
gt = golden.read_text()
if not gt.endswith("\n}\n"):
    raise SystemExit("Golden Tape unexpected ending")
gt_add = '''

    @Test
    fun V5_0_6805_lane_cap_canonical_hero_and_local_epoch_are_pinned() {
        val lane = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/LaneCapitalFairness6732.kt").readText()
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val hero = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/UnifiedAccountSnapshot6635.kt").readText()
        val spine = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/ExecutionSpineAcceptance6647.kt").readText()
        val causal = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CausalFeedbackAuthority6715.kt").readText()
        assertTrue(lane.contains("LANE_INVENTORY_MAX_SHARE_6805 = 0.35"))
        assertTrue(gate.contains("EXEC_OPEN_BLOCKED_LANE_INVENTORY_CEILING_6805"))
        assertTrue(hero.contains("CANONICAL_CAPITAL_AUTHORITY_6450"))
        assertFalse(hero.contains("JournalEconomicAuthority6616.currentSnapshot()"))
        assertFalse(spine.contains("ForensicReconciliation6635.deltas6647()"))
        assertTrue(causal.contains("CAUSAL_SCOPE_LOCAL_INVALIDATION_6805"))
    }
'''
golden.write_text(gt[:-3] + gt_add + "\n}\n")

# Runtime smoke source preflight references every new repair.
s = smoke.read_text()
needle = 'echo "6805 causal-integrity source contract: PASS"\n'
extra = '''grep -q "LANE_INVENTORY_MAX_SHARE_6805" "$WS/lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/engine/truth/LaneCapitalFairness6732.kt"
grep -q "EXEC_OPEN_BLOCKED_LANE_INVENTORY_CEILING_6805" "$WS/lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt"
grep -q "CANONICAL_CAPITAL_AUTHORITY_6450" "$WS/lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/engine/truth/UnifiedAccountSnapshot6635.kt"
! grep -q "JournalEconomicAuthority6616.currentSnapshot()" "$WS/lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/engine/truth/UnifiedAccountSnapshot6635.kt"
! grep -q "ForensicReconciliation6635.deltas6647()" "$WS/lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/engine/truth/ExecutionSpineAcceptance6647.kt"
grep -q "CAUSAL_SCOPE_LOCAL_INVALIDATION_6805" "$WS/lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/engine/truth/CausalFeedbackAuthority6715.kt"
'''
if needle not in s:
    raise SystemExit("smoke 6805 PASS anchor missing")
smoke.write_text(s.replace(needle, extra + needle, 1))

print("6805 additional authority repairs applied")
