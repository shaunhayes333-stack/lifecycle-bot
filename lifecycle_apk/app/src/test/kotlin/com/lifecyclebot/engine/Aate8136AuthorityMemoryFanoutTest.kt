package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.FrozenHoldingGuard8136
import com.lifecyclebot.engine.truth.AdmissionProspect8136
import com.lifecyclebot.engine.truth.AuthorityProofPrefetch8136
import com.lifecyclebot.engine.truth.AuthorityProofState8136
import com.lifecyclebot.engine.truth.AuthorityProofState8136.State
import com.lifecyclebot.engine.truth.OnChainMintAuthorityProof7248
import com.lifecyclebot.engine.truth.SingleFlight8136
import com.lifecyclebot.engine.truth.WalletTokenAccountStateAuthority7253
import com.lifecyclebot.network.HostCircuitInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** V5.0.8136 — authority-proof states, frozen holdings, pressure-aware retention, fanout contract, circuit recovery. */
class Aate8136AuthorityMemoryFanoutTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private val S = AuthorityProofState8136

    // ── authority proof states ──

    @Test fun anUnansweredProofIsNeverExecutableAndIsAskedAgain() {
        assertEquals(State.UNANSWERED, S.effective8136(null, 1_000L))
        assertFalse(S.executable8136(State.UNANSWERED))
        assertTrue(S.mustAsk8136(State.UNANSWERED))
        assertFalse(S.executable8136(State.STALE))
        assertFalse(S.executable8136(State.CONTRADICTED))
        assertFalse(S.executable8136(State.PROVEN_HOSTILE))
        assertTrue(S.executable8136(State.PROVEN_CLEAN))
        // An unanswered record never overwrites a fresh live proof, and is UNANSWERED itself.
        S.clearForTest8136()
        S.observeUnanswered8136("U8136")
        assertEquals(State.UNANSWERED, S.stateFor8136("U8136"))
        assertEquals(0L, S.ttlMs8136(S.record8136("U8136")!!))
        S.observe8136("C8136", freezeDisabled = true, mintDisabled = true, hostileExtension = null, mutableExtension = null, provider = "helius")
        S.observeUnanswered8136("C8136")
        assertEquals(State.PROVEN_CLEAN, S.stateFor8136("C8136"))
    }

    @Test fun aCleanImmutableProofIsReusedButAMutableOneGoesStale() {
        val immutable = S.merge8136(null, true, true, null, null, "helius", 1_000L)
        assertEquals(State.PROVEN_CLEAN, immutable.state)
        assertTrue(immutable.immutableClean)
        assertEquals(State.PROVEN_CLEAN, S.effective8136(immutable, 1_000L + 30L * 24 * 3_600_000L))
        // Token-2022 with a live transfer-fee authority: clean now, not clean forever.
        val mutable = S.merge8136(null, true, true, null, "transferFeeConfig", "helius", 1_000L)
        assertFalse(mutable.immutableClean)
        assertEquals(State.PROVEN_CLEAN, S.effective8136(mutable, 1_000L + S.CLEAN_MUTABLE_TTL_MS_8136 - 1))
        val later = S.effective8136(mutable, 1_000L + S.CLEAN_MUTABLE_TTL_MS_8136)
        assertEquals(State.STALE, later)
        assertTrue(S.mustAsk8136(later))
        assertFalse(S.executable8136(later))
        // Mint authority still live: freeze is clean (gate-relevant) but the proof is not permanent.
        assertFalse(S.merge8136(null, true, false, null, null, "rpc", 1_000L).immutableClean)
    }

    @Test fun aHostileProofIsCachedThenRefreshedAndAContradictionBlocks() {
        assertEquals(State.PROVEN_HOSTILE, S.classify8136(freezeDisabled = false, hostileExtension = null))
        assertEquals(State.PROVEN_HOSTILE, S.classify8136(freezeDisabled = true, hostileExtension = "permanentDelegate"))
        val hostile = S.merge8136(null, true, true, "transferHook", null, "helius", 1_000L)
        assertEquals(State.PROVEN_HOSTILE, S.effective8136(hostile, 1_000L + S.HOSTILE_TTL_MS_8136 - 1))
        assertFalse(S.mustAsk8136(S.effective8136(hostile, 1_000L + 1)))     // cached: no re-ask inside the TTL
        assertEquals(State.STALE, S.effective8136(hostile, 1_000L + S.HOSTILE_TTL_MS_8136))
        // A revoked authority that "comes back" after an immutable-clean proof is a contradiction, never executable.
        val clean = S.merge8136(null, true, true, null, null, "helius", 1_000L)
        val contra = S.merge8136(clean, false, true, null, null, "rpc", 2_000L)
        assertEquals(State.CONTRADICTED, contra.state)
        assertFalse(S.executable8136(S.effective8136(contra, 2_001L)))
        assertTrue(S.mustAsk8136(contra.state))
        // The next answer decides.
        assertEquals(State.PROVEN_CLEAN, S.merge8136(contra, true, true, null, null, "helius", 3_000L).state)
        S.clearForTest8136()
        S.observe8136("X8136", true, true, null, null, "helius")
        assertEquals(State.CONTRADICTED, S.observe8136("X8136", false, true, null, null, "rpc").state)
        assertTrue(S.contradictions8136() >= 1L)
    }

    @Test fun mutableToken2022AuthoritiesAreRecognised() {
        fun mut(json: String) = OnChainMintAuthorityProof7248.mutableExtension8136(org.json.JSONArray(json))
        assertNull(OnChainMintAuthorityProof7248.mutableExtension8136(null))
        assertEquals("transferFeeConfig", mut("""[{"extension":"transferFeeConfig","state":{"transferFeeConfigAuthority":"Fa8136","newerTransferFee":{"transferFeeBasisPoints":100}}}]"""))
        assertNull(mut("""[{"extension":"transferFeeConfig","state":{"transferFeeConfigAuthority":null,"newerTransferFee":{"transferFeeBasisPoints":100}}}]"""))
        assertEquals("transferHook", mut("""[{"extension":"transferHook","state":{"authority":"Ha8136","programId":null}}]"""))
        assertNull(mut("""[{"extension":"metadataPointer","state":{"authority":"Ma8136"}}]"""))
        // A hostile extension is still hostile (8123 unchanged).
        assertTrue(OnChainMintAuthorityProof7248.hostileExtension8123(org.json.JSONArray("""[{"extension":"nonTransferable"}]""")) != null)
    }

    @Test fun unansweredRetriesAreBounded() {
        assertTrue(S.retryDue8136(0, 0L, 0L, 1_000L))
        assertFalse(S.retryDue8136(1, 1_000L, 1_000L, 2_000L))          // 3 s rung not reached
        assertTrue(S.retryDue8136(1, 1_000L, 1_000L, 4_000L))
        assertFalse(S.retryDue8136(S.MAX_ATTEMPTS_PER_WINDOW_8136, 1_000L, 1_000L, 500_000L))
        assertTrue(S.retryDue8136(S.MAX_ATTEMPTS_PER_WINDOW_8136, 1_000L, 1_000L, 1_000L + S.RETRY_WINDOW_MS_8136))
    }

    @Test fun prefetchIsIdempotentAndRespectsTheLadder() {
        S.clearForTest8136(); AdmissionProspect8136.clearForTest8136()
        S.observe8136("P8136", true, true, null, null, "helius")
        assertFalse(AuthorityProofPrefetch8136.prefetch8136("P8136"))           // fresh clean proof: nothing to do
        assertTrue(AdmissionProspect8136.isActive8136("P8136"))                 // but it is an admission prospect now
        assertFalse(AdmissionProspect8136.isActive8136("P8136", System.currentTimeMillis() + AdmissionProspect8136.ACTIVE_MS_8136))
        // The gate's own unanswered ask counts as the attempt: the next prefetch waits for the ladder.
        AuthorityProofPrefetch8136.noteGateUnanswered8136("Q8136")
        assertFalse(AuthorityProofPrefetch8136.prefetch8136("Q8136"))
        assertFalse(AuthorityProofPrefetch8136.prefetch8136(""))
        assertTrue(AuthorityProofPrefetch8136.statusLine8136().contains("skippedBackoff="))
        val gate = src("engine/truth/FreezeAuthorityHardBlock7238.kt")
        assertTrue(gate.contains("val proof = if (asked8136) firstProof8136 else"))   // one ask per gate call
        assertTrue(gate.contains("AuthorityProofPrefetch8136.noteGateUnanswered8136(mint)"))
        assertTrue(src("engine/FdgReEvalThrottle.kt").contains("AuthorityProofPrefetch8136.prefetchIfLive8136(mint)"))
    }

    @Test fun singleFlightJoinsTheAskAlreadyOnTheWire() {
        val flight = SingleFlight8136<String>()
        val calls = AtomicInteger(0)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var leaderValue: String? = null
        val leader = Thread {
            leaderValue = flight.run("M8136", 5_000L) {
                calls.incrementAndGet(); started.countDown(); release.await(5, TimeUnit.SECONDS); "proof"
            }.value
        }
        leader.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertTrue(flight.inFlight("M8136"))
        var joined: SingleFlight8136.Outcome<String>? = null
        val follower = Thread { joined = flight.run("M8136", 5_000L) { calls.incrementAndGet(); "second" } }
        follower.start()
        Thread.sleep(100)
        release.countDown()
        leader.join(5_000L); follower.join(5_000L)
        assertEquals(1, calls.get())
        assertEquals("proof", leaderValue)
        assertEquals("proof", joined?.value)
        assertEquals(true, joined?.joined)
        assertEquals(1L, flight.leads())
        assertEquals(1L, flight.joins())
        assertFalse(flight.inFlight("M8136"))
        // A thrown block completes the flight with null instead of hanging joiners.
        assertNull(flight.run("T8136", 100L) { throw IllegalStateException("boom") }.value)
        assertFalse(flight.inFlight("T8136"))
    }

    // ── frozen holdings ──

    @Test fun aFrozenTokenAccountIsNeverFinalisedAsZero() {
        val a = WalletTokenAccountStateAuthority7253
        a.applyParsedSnapshot(setOf("frozen8136mint"), setOf("frozen8136mint"))
        assertTrue(FrozenHoldingGuard8136.isFrozen8136("frozen8136mint"))
        assertFalse(FrozenHoldingGuard8136.zeroFinalityAllowed8136(FrozenHoldingGuard8136.isFrozen8136("frozen8136mint")))
        FrozenHoldingGuard8136.clearForTest8136()
        assertTrue(FrozenHoldingGuard8136.noteHeld8136("frozen8136mint"))
        assertFalse(FrozenHoldingGuard8136.noteHeld8136("frozen8136mint"))     // logged once, not every re-check
        assertTrue(FrozenHoldingGuard8136.isHeld8136("frozen8136mint"))
        assertEquals(1, FrozenHoldingGuard8136.heldCount8136())
        // A thaw seen by a wallet read returns the mint to the normal proof path.
        a.applyParsedSnapshot(setOf("frozen8136mint"), emptySet())
        assertTrue(FrozenHoldingGuard8136.zeroFinalityAllowed8136(FrozenHoldingGuard8136.isFrozen8136("frozen8136mint")))
        FrozenHoldingGuard8136.clear8136("frozen8136mint")
        assertFalse(FrozenHoldingGuard8136.isHeld8136("frozen8136mint"))
        val poller = src("engine/sell/BalanceProofPoller.kt")
        val pollOne = poller.substringAfter("private fun pollOne(").substringBefore("SellAmountAuthority.resolve(")
        assertTrue(pollOne.contains("if (frozenHold8136(entry)) return"))
        val zero = poller.substringAfter("private fun onZeroRead7733(").substringBefore("ZERO_BALANCE_CONFIRMED")
        assertTrue(zero.contains("if (frozenHold8136(entry)) return"))
    }

    // ── pressure-aware retention ──

    private fun row(i: Int, watched: Boolean = false, open: Boolean = false, admission: Boolean = false, critical: Boolean = false, addedAt: Long = 940_000L) =
        RetentionPolicy8136.Row("m$i", watched, open, admission, critical, addedAt, addedAt + i)

    @Test fun pressurePrunesFreshCandidatesButProtectsOpenAndAdmission() {
        val now = 1_000_000L                                                    // rows are one minute old
        val rows = (0 until 200).map { row(it) } +
            listOf(row(900, open = true, addedAt = 1L), row(901, admission = true, addedAt = 1L), row(902, critical = true, addedAt = 1L))
        val hard = RetentionPolicy8136.victims8136(rows, RetentionPolicy8136.Pressure.HARD, now)
        assertEquals(rows.size - RetentionPolicy8136.keepTarget8136(RetentionPolicy8136.Pressure.HARD), hard.size)
        assertTrue(hard.none { RetentionPolicy8136.protected8136(it) })
        assertEquals("m0", hard.first().mint)                                   // oldest touch first
        assertEquals(0L, RetentionPolicy8136.minAgeMs8136(RetentionPolicy8136.Pressure.HARD))
        // Without pressure the 8025 age/size rules still hold: a young, small book is untouched.
        assertTrue(RetentionPolicy8136.victims8136(rows, RetentionPolicy8136.Pressure.NORMAL, now).isEmpty())
        val many = (0 until 400).map { row(it) }
        assertTrue(RetentionPolicy8136.victims8136(many, RetentionPolicy8136.Pressure.SOFT, now).isEmpty())
        // Watched rows go only at CRITICAL, and only after every unwatched one.
        val mixed = (0 until 100).map { row(it, watched = true) } + (100 until 150).map { row(it) }
        assertTrue(RetentionPolicy8136.victims8136(mixed, RetentionPolicy8136.Pressure.HARD, now).isEmpty())
        val crit = RetentionPolicy8136.victims8136(mixed, RetentionPolicy8136.Pressure.CRITICAL, now)
        assertEquals(150 - RetentionPolicy8136.keepTarget8136(RetentionPolicy8136.Pressure.CRITICAL), crit.size)
        assertTrue(crit.take(50).none { it.watched })
    }

    @Test fun anIneffectiveHardTrimEscalates() {
        assertEquals(RetentionPolicy8136.Pressure.HARD, RetentionPolicy8136.pressure8136(RetentionPolicy8136.HARD_FRAC_8136))
        assertEquals(RetentionPolicy8136.Pressure.CRITICAL, RetentionPolicy8136.pressure8136(RetentionPolicy8136.HARD_FRAC_8136, escalation = 1))
        assertEquals(RetentionPolicy8136.Pressure.CRITICAL, RetentionPolicy8136.pressure8136(RetentionPolicy8136.CRITICAL_FRAC_8136))
        assertEquals(RetentionPolicy8136.Pressure.SOFT, RetentionPolicy8136.pressure8136(RetentionPolicy8136.SOFT_FRAC_8136))
        assertEquals(RetentionPolicy8136.Pressure.NORMAL, RetentionPolicy8136.pressure8136(Double.NaN))
        // 511/512 MB after the trim: nothing recovered -> escalate; a real drop resets.
        assertFalse(RetentionPolicy8136.recovered8136(0.998, 0.996))
        assertTrue(RetentionPolicy8136.recovered8136(0.95, 0.95 - RetentionPolicy8136.MEANINGFUL_RECOVERY_FRAC_8136))
        assertTrue(RetentionPolicy8136.recovered8136(0.90, 0.80))
        assertEquals(1, RetentionPolicy8136.nextEscalation8136(0, recovered = false))
        assertEquals(RetentionPolicy8136.MAX_ESCALATION_8136, RetentionPolicy8136.nextEscalation8136(RetentionPolicy8136.MAX_ESCALATION_8136, false))
        assertEquals(0, RetentionPolicy8136.nextEscalation8136(2, recovered = true))
        assertTrue(RetentionPolicy8136.chartTapeCap8136(RetentionPolicy8136.Pressure.CRITICAL)!! < RetentionPolicy8136.chartTapeCap8136(RetentionPolicy8136.Pressure.HARD)!!)
        assertTrue(RetentionPolicy8136.archiveCap8136(RetentionPolicy8136.Pressure.CRITICAL) < 600)
        // 8025 pins intact.
        assertEquals(300, MemoryGuard7977.KEEP_TOKENS_8025)
        assertEquals(2, MemoryGuard7977.level7977(0.95))
        assertTrue(MemoryGuard7977.statusLine7977().contains("pressure8136="))
        assertEquals(0, com.lifecyclebot.engine.chart.ChartReader7950.trimUnderPressure8136(10_000) { false })
    }

    // ── FDG fanout measurement contract ──

    @Test fun aFallbackGateCountNeverRaisesFanout() {
        assertEquals(0.0, FdgFanoutContract8136.faultRatio8136(uniqueDecisions = 0L, intake = 10L), 0.0)
        assertEquals(FdgFanoutContract8136.SOURCE_FALLBACK_8136, FdgFanoutContract8136.source8136(0L))
        assertEquals(FdgFanoutContract8136.SOURCE_UNIQUE_8136, FdgFanoutContract8136.source8136(3L))
        assertEquals(5.0, FdgFanoutContract8136.faultRatio8136(50L, 10L), 1e-9)
        assertEquals(2.0, FdgFanoutContract8136.faultRatio8136(40L, 10L, distinctCandidates = 20L), 1e-9)
        FdgReEvalThrottle.clear()
        // Unique counter empty while the snapshot carries 1000 raw FDG rows: no fault (it used to substitute them).
        val faults = InvariantGuardian.check(snap(intake = 10L, fdg = 1_000L))
        assertFalse(faults.any { it.code == InvariantGuardian.FaultCode.FDG_FANOUT_EXPLOSION })
        // Real fanout on the unique counter still faults: 50 decisions on one candidate over 10 intake.
        repeat(50) { FdgReEvalThrottle.noteDecision8087("FAN8136", 1L, "LANE$it", "ev") }
        assertTrue(InvariantGuardian.check(snap(intake = 10L, fdg = 0L)).any { it.code == InvariantGuardian.FaultCode.FDG_FANOUT_EXPLOSION })
        FdgReEvalThrottle.clear()
    }

    @Test fun theThrottleSeparatesSupersededRepeatsAndCandidates() {
        FdgReEvalThrottle.clear()
        FdgReEvalThrottle.noteDecision8087("C8136", 1L, "SHITCOIN", "ev1")
        FdgReEvalThrottle.noteDecision8087("C8136", 1L, "SHITCOIN", "ev1")      // unchanged evidence repeat
        FdgReEvalThrottle.noteDecision8087("C8136", 1L, "SHITCOIN", "ev2")      // superseded
        FdgReEvalThrottle.noteDecision8087("C8136", 1L, "MOONSHOT", "ev1")      // second lane, same candidate
        val c = FdgReEvalThrottle.contract8136()
        assertEquals(1L, c.distinctCandidates)
        assertEquals(3L, c.uniqueDecisions)
        assertEquals(1L, c.unchangedRepeats)
        assertEquals(1L, c.superseded)
        assertEquals(2.0, c.laneDecisionsPerCandidate, 1e-9)
        assertEquals(1L, FdgReEvalThrottle.distinctCandidates8136())
        assertTrue(FdgReEvalThrottle.fanoutLine8087().contains("superseded=1"))
        assertTrue(FdgReEvalThrottle.sealedMints8136().isEmpty())
        assertEquals(0, FdgReEvalThrottle.trim8136())
        assertTrue(src("engine/InvariantGuardian.kt").contains("val fdgDecisions = if (unique8087 > 0L) unique8087 else fdgGateEffective8087"))
        FdgReEvalThrottle.clear()
    }

    // ── provider circuit ──

    @Test fun theCircuitRecoversAndABurstDoesNotClimbTheLadder() {
        assertEquals(15_000L, HostCircuitInterceptor.nxCooldownMs8136(1))
        assertEquals(60_000L, HostCircuitInterceptor.nxCooldownMs8136(2))
        assertEquals(5 * 60_000L, HostCircuitInterceptor.nxCooldownMs8136(3))
        assertEquals(5 * 60_000L, HostCircuitInterceptor.nxCooldownMs8136(99))
        val now = 10_000_000L
        assertEquals(1, HostCircuitInterceptor.nextRateLimitStreak8136(0, now, 0L))
        // Parallel 429s from one burst while the window is running do not climb.
        assertEquals(1, HostCircuitInterceptor.nextRateLimitStreak8136(1, now, now + 30_000L))
        // A 429 after the window expired is a new strike.
        assertEquals(2, HostCircuitInterceptor.nextRateLimitStreak8136(1, now, now - 1L))
        assertEquals(30_000L, HostCircuitInterceptor.rateLimitCooldownMs7297(1, null))
        val hc = src("network/HostCircuitInterceptor.kt")
        assertTrue(hc.contains("state.nxStreak8136.set(0)"))                    // any wire answer resets the DNS ladder
        assertTrue(hc.contains("state.rateLimitStreak7297.set(0)"))             // success still resets the 429 ladder
    }

    private fun snap(intake: Long, fdg: Long): RuntimeStateSnapshot =
        RuntimeStateSnapshot(
            buildVersion = "test",
            buildTag = "test",
            runtimeGeneration = 1L,
            uiState = "RUNNING",
            runtimeState = "RUNNING",
            botLoopActive = true,
            scannerActive = true,
            sellReconcilerStarted = true,
            hostTrackerOpenCount = 0,
            positionStoreOpenCount = 0,
            paperOpenPositions = 0,
            liveOpenPositions = 0,
            walletHeldMints = 0,
            canonicalOpenPositions = 0,
            orphanPaperPositions = 0,
            orphanLivePositions = 0,
            reconcilerTotalChecked = 0,
            mode = "PAPER",
            enabledTraders = "MEME",
            intake = intake,
            safety = 10,
            v3 = 10,
            laneEval = 10,
            fdg = fdg,
            exec = 0,
            exit = 10,
            learningTrades = 0,
            uniqueClosedPositionIds = 0,
            staleSellLocks = 0,
            mainUpdateSkippedInactive = 0,
            anrHints = 0,
            apiHealth = emptyMap(),
            topBlockReasons = emptyMap(),
            activeMitigations = emptyList(),
            timestampMs = 1L,
        )
}
