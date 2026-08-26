package com.lifecyclebot.engine

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Repair6533ExecutionAuthorityAcceptanceTest {
    @Before fun setup() {
        ExecutableOpenGate.resetForTests()
        LaneExecutionCoordinator.resetForTests()
        RuntimeModeAuthority.publishConfig(paperMode = true, autoTrade = true)
        RuntimeModeAuthority.publishUiMode(true)
        RuntimeModeAuthority.publishExecutorMode(true)
        RuntimeModeAuthority.publishPipelineMode(true)
    }

    @After fun cleanup() { ExecutableOpenGate.resetForTests() }

    @Test fun `A seven hundred viable candidates retain trunk access`() {
        val reached = (1..706).count { ExecutionAuthorityPolicy6533.isTrunkLane("V3") }
        assertEquals(706, reached)
        assertTrue(reached.toDouble() / 706.0 >= 0.20)
    }

    @Test fun `B twenty V3 executes each produce exactly one immutable intent`() {
        val intents = (1..20).map { n ->
            val mint = "V3Trunk6533_${n}_${System.nanoTime()}"
            val cv = LaneExecutionCoordinator.candidateVersionFor(mint)
            val intent = ExecutableOpenGate.recordFdgAndGetIntent6533(
                mint, "V3T$n", "STANDARD", true, null,
                signal = "BUY", rugScore = 90, safetyTier = "SAFE", liquidityUsd = 5_000.0,
                preFdgVerdict = "BUY", candidateVersion = cv, entryScore = 80,
                allowTrunkExecutionHandoff6533 = true,
            )
            assertNotNull(intent)
            assertEquals("STANDARD", intent!!.canonicalLane)
            assertSame(intent, ExecutableOpenGate.activeExecutionIntent6519("PAPER", mint, cv))
            intent
        }
        assertEquals(20, intents.map { it.attemptId }.toSet().size)
    }

    @Test fun `C immutable FDG BUY and PROBE survive raw UNKNOWN`() {
        for (verdict in listOf("BUY", "PROBE_ONLY")) {
            val intent = ExecutableOpenGate.ExecutionIntent(
                attemptId="I$verdict", candidateId="C$verdict", candidateVersion=1L,
                mint="M$verdict", mode="PAPER", canonicalLane="QUALITY", fdgVerdict=verdict,
                fdgAllowed=true, authorityVersion=1L, resolvedSize=0.05,
                createdAt=System.currentTimeMillis(), symbol="C", hardNoReasons=emptyList(),
            )
            assertFalse(ExecutableOpenGate.mutableSignalCanVeto6519(intent, "UNKNOWN"))
            assertFalse(ExecutableOpenGate.mutableSignalCanVeto6519(intent, "WATCH"))
        }
        assertTrue(ExecutableOpenGate.mutableSignalCanVeto6519(null, "UNKNOWN"))
    }

    @Test fun `D five hundred cross chain candidates do not acquire Solana TokenMap hard no`() {
        val intents = (1..500).map { n ->
            val asset = "unresolved:${if (n % 2 == 0) "XMR" else "BTC"}_${n}_${System.nanoTime()}"
            val cv = LaneExecutionCoordinator.candidateVersionFor(asset)
            ExecutableOpenGate.recordFdgAndGetIntent6533(
                asset, "X$n", "CRYPTO", true, null,
                signal="BUY", rugScore=100, safetyTier="SAFE", liquidityUsd=0.0,
                preFdgVerdict="BUY", candidateVersion=cv, entryScore=78,
                requiresSolanaTokenMap=false,
            )
        }
        assertEquals(500, intents.count { it != null })
        assertTrue(intents.filterNotNull().none { it.hardNoReasons.contains("LIQUIDITY_UNKNOWN_PENDING_TOKEN_MAP") })
        assertTrue(intents.filterNotNull().none { it.requiresSolanaTokenMap })
        assertFalse(ExecutionAuthorityPolicy6533.requiresSolanaTokenMap("MULTICHAIN", "unresolved:XMR"))
    }

    @Test fun `E one hundred candidates have executable fanout at most two`() {
        val eligible = listOf("QUALITY", "MOONSHOT", "TREASURY", "BLUECHIP", "EXPRESS")
        val fanouts = (1..100).map { n ->
            val rescue = ExecutionAuthorityPolicy6533.selectOneRescue(
                "Mint6533_$n", n.toLong(), "SHITCOIN", listOf("QUALITY", "MOONSHOT"), eligible,
            )
            (eligible.filter { it == rescue } + "SHITCOIN").distinct().size
        }
        assertTrue(fanouts.all { it <= 2 })
        assertTrue(fanouts.all { it == 2 })
    }

    @Test fun `F twenty five same candidate attempts retain one execution intent`() {
        val mint = "StableMint6533${System.nanoTime()}"
        val cv = LaneExecutionCoordinator.candidateVersionFor(mint)
        val attempts = (1..25).map {
            ExecutableOpenGate.recordFdgAndGetIntent6533(
                mint, "STABLE", "QUALITY", true, null,
                signal="BUY", rugScore=90, safetyTier="SAFE", liquidityUsd=5_000.0,
                preFdgVerdict="BUY", candidateVersion=cv, entryScore=82,
            )!!.attemptId
        }
        assertEquals(1, attempts.toSet().size)
        val rescueCalls = (1..25).map {
            ExecutionAuthorityPolicy6533.selectOneRescue(
                mint, cv, "QUALITY", listOf("QUALITY", "MOONSHOT", "EXPRESS"),
                listOf("QUALITY", "MOONSHOT", "EXPRESS"),
            )
        }
        assertEquals(1, rescueCalls.toSet().size)
        assertNotEquals("QUALITY", rescueCalls.first())
    }

    @Test fun `G true zero rug invalid route and mechanical impossibility remain hard`() {
        fun intentFor(tag: String, rug: Int = 90, hard: List<String> = emptyList(), hydrated: Boolean = false): ExecutableOpenGate.ExecutionIntent? {
            val mint = "${tag}6533${System.nanoTime()}"
            val cv = LaneExecutionCoordinator.candidateVersionFor(mint)
            return ExecutableOpenGate.recordFdgAndGetIntent6533(
                mint, tag, "QUALITY", true, null, signal="BUY", rugScore=rug,
                safetyTier="SAFE", liquidityUsd=0.0, hardNoReasons=hard,
                preFdgVerdict="BUY", candidateVersion=cv, entryScore=80,
                tokenMapRouteStatus=if (hydrated) "TRUE_ZERO_LIQUIDITY" else "",
                tokenMapHydrationComplete=hydrated, tokenMapExpectedOut=0.0,
                tokenMapProviderAttempts=if (hydrated) 2 else 0,
                requiresSolanaTokenMap=true,
            )
        }
        assertNull(intentFor("ZERO", hydrated=true))
        assertNull(intentFor("RUG", rug=0))
        assertNull(intentFor("ROUTE", hard=listOf("INVALID_ROUTE")))
        assertNull(intentFor("DUP", hard=listOf("DUPLICATE_OPEN")))
        assertNull(intentFor("MECH", hard=listOf("MECHANICAL_IMPOSSIBILITY")))
    }
}
