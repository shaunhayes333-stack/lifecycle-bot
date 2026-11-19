package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CurveCustody8881
import io.github.novacrypto.base58.Base58
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8881 — owner: "the meme trader isnt a pumpfun trader exclusively is a solana network trader" / "dont buy unlocked
 * drain coins". A Meteora DBC or Raydium LaunchLab curve is proven in program custody from its own account and vault, so
 * the LP rule exempts it exactly as it exempts a pump.fun curve; anything that does not read as such a curve stays as it was.
 */
class Aate8881CurveCustodyTest {
    private val mint = "7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmW2hr"
    private val vault = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin"
    private val pool = "HLnpSz9h2S4hiLQ43rnSD9XkcUThA7B8hQMKmDaiTLcC"
    private val wsol = "So11111111111111111111111111111111111111112"
    private val usdc = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    private val now = 50_000_000L

    @Before fun setUp() = CurveCustody8881.resetForTest8881()
    @After fun tearDown() = CurveCustody8881.resetForTest8881()

    private fun put(b: ByteArray, off: Int, key: String) = Base58.base58Decode(key).copyInto(b, off)
    private fun putU64(b: ByteArray, off: Int, v: Long) { for (i in 0 until 8) b[off + i] = ((v ushr (8 * i)) and 0xFFL).toByte() }
    private fun disc(b: ByteArray, d: IntArray) { for (i in 0 until 8) b[i] = d[i].toByte() }

    private fun dbc(baseMint: String = mint, migrated: Int = 0, progress: Int = 0, reserveLamports: Long = 2_500_000_000L): ByteArray =
        ByteArray(8 + 416).also { b ->
            disc(b, CurveCustody8881.DBC_DISC_8881)
            put(b, 136, baseMint); put(b, 200, vault); putU64(b, 240, reserveLamports)
            b[305] = migrated.toByte(); b[308] = progress.toByte()
        }

    private fun launchLab(baseMint: String = mint, quoteMint: String = wsol, status: Int = 0, realQuote: Long = 3_000_000_000L): ByteArray =
        ByteArray(8 + 421).also { b ->
            disc(b, CurveCustody8881.LAUNCHLAB_DISC_8881)
            b[17] = status.toByte(); putU64(b, 61, realQuote)
            put(b, 205, baseMint); put(b, 237, quoteMint); put(b, 301, vault)
        }

    private fun vaultJson(owner: String = CurveCustody8881.DBC_AUTHORITY_8881, tokenMint: String = wsol, lamports: String = "3000000000",
                          program: String = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA") = JSONObject()
        .put("owner", program)
        .put("data", JSONObject().put("parsed", JSONObject().put("info", JSONObject()
            .put("mint", tokenMint).put("owner", owner).put("state", "initialized")
            .put("tokenAmount", JSONObject().put("amount", lamports)))))

    @Test fun aDbcVirtualPoolIsReadFromItsOwnAccount() {
        val c = CurveCustody8881.curve8881(mint, pool, CurveCustody8881.DBC_PROGRAM_8881, dbc())
        assertNotNull(c)
        assertEquals(CurveCustody8881.VENUE_DBC_8881, c!!.venue)
        assertEquals(vault, c.quoteVault)
        assertEquals(2.5, c.reserveSol, 1e-12)
        assertEquals(CurveCustody8881.DBC_AUTHORITY_8881, c.authority)
        assertNull("another coin's pool", CurveCustody8881.curve8881(usdc, pool, CurveCustody8881.DBC_PROGRAM_8881, dbc()))
        assertNull("migrated", CurveCustody8881.curve8881(mint, pool, CurveCustody8881.DBC_PROGRAM_8881, dbc(migrated = 1)))
        assertNull("curve complete, awaiting migration", CurveCustody8881.curve8881(mint, pool, CurveCustody8881.DBC_PROGRAM_8881, dbc(progress = 1)))
        assertNull("not this program's account", CurveCustody8881.curve8881(mint, pool, "11111111111111111111111111111111", dbc()))
        assertNull("wrong discriminator", CurveCustody8881.curve8881(mint, pool, CurveCustody8881.DBC_PROGRAM_8881, dbc().also { it[0] = 0 }))
        assertNull("short account", CurveCustody8881.curve8881(mint, pool, CurveCustody8881.DBC_PROGRAM_8881, dbc().copyOf(300)))
        assertEquals("the IDL's PoolState discriminator is sha256(\"account:PoolState\")",
            java.security.MessageDigest.getInstance("SHA-256").digest("account:PoolState".toByteArray()).take(8).map { it.toInt() and 0xFF },
            CurveCustody8881.LAUNCHLAB_DISC_8881.toList())
        assertEquals(java.security.MessageDigest.getInstance("SHA-256").digest("account:VirtualPool".toByteArray()).take(8).map { it.toInt() and 0xFF },
            CurveCustody8881.DBC_DISC_8881.toList())
    }

    @Test fun aLaunchLabPoolMustBeFundingAndQuotedInSol() {
        val c = CurveCustody8881.curve8881(mint, pool, CurveCustody8881.LAUNCHLAB_PROGRAM_8881, launchLab())
        assertNotNull(c)
        assertEquals(CurveCustody8881.VENUE_LAUNCHLAB_8881, c!!.venue)
        assertEquals(3.0, c.reserveSol, 1e-12)
        assertEquals(CurveCustody8881.LAUNCHLAB_AUTHORITY_8881, c.authority)
        assertNull("funding ended", CurveCustody8881.curve8881(mint, pool, CurveCustody8881.LAUNCHLAB_PROGRAM_8881, launchLab(status = 1)))
        assertNull("migrated", CurveCustody8881.curve8881(mint, pool, CurveCustody8881.LAUNCHLAB_PROGRAM_8881, launchLab(status = 2)))
        assertNull("a USDC curve is not read in SOL", CurveCustody8881.curve8881(mint, pool, CurveCustody8881.LAUNCHLAB_PROGRAM_8881, launchLab(quoteMint = usdc)))
        assertNull(CurveCustody8881.curve8881(usdc, pool, CurveCustody8881.LAUNCHLAB_PROGRAM_8881, launchLab()))
    }

    @Test fun theVaultMustBeWrappedSolHeldByTheProgramsAuthorityAndClearTheFloor() {
        assertEquals(3.0, CurveCustody8881.vaultSol8881(vaultJson(), CurveCustody8881.DBC_AUTHORITY_8881)!!, 1e-12)
        assertNull("held by anyone else", CurveCustody8881.vaultSol8881(vaultJson(owner = mint), CurveCustody8881.DBC_AUTHORITY_8881))
        assertNull("another program's vault authority", CurveCustody8881.vaultSol8881(vaultJson(), CurveCustody8881.LAUNCHLAB_AUTHORITY_8881))
        assertNull("a USDC vault", CurveCustody8881.vaultSol8881(vaultJson(tokenMint = usdc), CurveCustody8881.DBC_AUTHORITY_8881))
        assertNull("not a token account", CurveCustody8881.vaultSol8881(vaultJson(program = pool), CurveCustody8881.DBC_AUTHORITY_8881))
        assertNull(CurveCustody8881.vaultSol8881(null, CurveCustody8881.DBC_AUTHORITY_8881))
        assertEquals("the smaller of the reserve and the vault", 2.5, CurveCustody8881.custodySol8881(2.5, 3.0)!!, 1e-12)
        assertEquals(0.5, CurveCustody8881.custodySol8881(0.9, 0.5)!!, 1e-12)
        assertNull("under the 0.5 SOL curve exit floor", CurveCustody8881.custodySol8881(0.49, 3.0))
        assertNull(CurveCustody8881.custodySol8881(2.5, null))
    }

    @Test fun aFreshProofMakesTheCurveActiveAtItsOwnAddressAndNeverForAMigratedCoin() {
        assertEquals(false to "", LiquidityOwnership8522.activeCurve8881(mint, false, now))
        CurveCustody8881.putForTest8881(CurveCustody8881.Custody8881(mint, pool, CurveCustody8881.VENUE_DBC_8881, 2.5, now - 1_000L))
        assertEquals(true to pool, LiquidityOwnership8522.activeCurve8881(mint, false, now))
        assertEquals(false to "", LiquidityOwnership8522.activeCurve8881(mint, true, now))
        assertEquals("30 s, the pump.fun account proof's window", false to "", LiquidityOwnership8522.activeCurve8881(mint, false, now + 30_000L))
    }

    private fun report(vararg pools: LiquidityOwnership8522.Pool, unlocked: Boolean = false): SafetyReport = SafetyReport(
        checkedAt = now - 1_000L, rugcheckScore = 99, rugcheckStatus = "CONFIRMED", evaluatedForPaper8522 = false,
        lpOwnership8522 = LiquidityOwnership8522.Evidence(mint, pools.toList(), unlocked, true),
    )

    @Test fun thePoolAtTheProvenCurvesAddressIsTheCurveWhateverItsLabel() {
        val curve = LiquidityOwnership8522.Pool(pool, "meteora_dbc", 0.0, 9_000.0)
        val r = report(curve, unlocked = true)
        val proven = LiquidityOwnership8522.assess(r, pool, true, activeCurveAddress = pool, nowMs = now, expectedMint = mint)
        assertEquals(LiquidityOwnership8522.Status.PROVEN, proven.status)
        assertEquals("ACTIVE_CURVE_LP_NOT_APPLICABLE_8522", proven.reason)
        assertEquals("no custody proof: the provider's finding stands", LiquidityOwnership8522.Status.BLOCKED,
            LiquidityOwnership8522.assess(r, pool, false, activeCurveAddress = pool, nowMs = now, expectedMint = mint).status)
        assertEquals("a proof for another pool proves nothing here", LiquidityOwnership8522.Status.BLOCKED,
            LiquidityOwnership8522.assess(r, pool, true, activeCurveAddress = vault, nowMs = now, expectedMint = mint).status)
        val withAmm = report(curve, LiquidityOwnership8522.Pool("amm", "raydium", 0.0, 50_000.0))
        assertEquals("the curve cannot excuse an unprotected ordinary pool", LiquidityOwnership8522.Status.BLOCKED,
            LiquidityOwnership8522.assess(withAmm, pool, true, activeCurveAddress = pool, nowMs = now, expectedMint = mint).status)
        assertEquals("unlabelled, unproven: still waiting", LiquidityOwnership8522.Status.PENDING,
            LiquidityOwnership8522.assess(report(LiquidityOwnership8522.Pool(pool, "meteora_dbc", -1.0, 9_000.0)), pool, false,
                activeCurveAddress = "", nowMs = now, expectedMint = mint).status)
    }

    @Test fun itIsWiredAtEveryDoorTheLpRuleStands() {
        fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
        val gate = src("engine/PreTradeHardGate.kt")
        assertTrue(gate.contains("val (curveExitSafe8319, curveAddress8522) = LiquidityOwnership8522.activeCurve8881(mint, ts.tokenMap.migratedOrGraduated, now)"))
        assertTrue(gate.contains("if (ownership8522.status != LiquidityOwnership8522.Status.PROVEN && selectedPool8522.isNotBlank())"))
        assertTrue(gate.contains("com.lifecyclebot.engine.truth.CurveCustody8881.request8881(mint, selectedPool8522, now)"))
        val route = src("engine/BuyRouteSafety8522.kt")
        assertTrue(route.contains("val (active, curve) = LiquidityOwnership8522.activeCurve8881(ts.mint, ts.tokenMap.migratedOrGraduated, now)"))
        assertTrue(route.contains("com.lifecyclebot.engine.truth.CurveCustody8881.request8881(ts.mint, pool, now)"))
        val safety = src("engine/TokenSafetyChecker.kt")
        assertTrue(safety.contains("com.lifecyclebot.engine.truth.CurveCustody8881.prove8881(mint, pool8881)"))
        assertTrue(safety.contains("curveOn8881 && lpOwnership8522.pools.none { !it.isCurve && it.address != curveAt8881 && it.depthUsd > 0.0 }"))
        assertTrue(safety.contains("if (onCurve7384) lpUnlockedRisk = false"))
        val executor = src("engine/Executor.kt")
        assertTrue("the pump.fun local buy keeps its own pump-only proof",
            executor.contains("val curve8522 = if (!ts.tokenMap.migratedOrGraduated && LiquidityOwnership8522.activeCurveVerified(ts.mint))"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("CurveCustody8881.statusLine8881()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 70. Meteora DBC and Raydium LaunchLab curves are proven in custody (8881)"))
    }
}
