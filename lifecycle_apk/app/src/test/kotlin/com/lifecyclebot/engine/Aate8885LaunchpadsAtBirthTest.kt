package com.lifecyclebot.engine

import com.lifecyclebot.network.ChainLaunchFeed8179
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8885 — owner: "the meme trader isnt a pumpfun trader exclusively is a solana network trader. the full network."
 * Moonshot and Boop launches are heard at birth from the chain, by their own programs' creation instructions.
 */
class Aate8885LaunchpadsAtBirthTest {
    private val f = ChainLaunchFeed8179
    private fun invoke(p: String, d: Int) = "Program $p invoke [$d]"
    private fun ok(p: String) = "Program $p success"

    @Test fun eachLaunchpadIsMatchedOnlyByItsOwnCreation() {
        val moon = f.TARGETS_8179.first { it.key == "MOONSHOT" }
        val boop = f.TARGETS_8179.first { it.key == "BOOP" }
        assertEquals("MoonCVVNZFSYkqNXP6bxHLPL6QQJiMagDL3qcqUQTrG", moon.programId)
        assertEquals("boop8hVGQGqehUK2iVEMEnMrL5RbjywRzHKBmBE7ry4", boop.programId)
        assertTrue(moon.curve && boop.curve)
        assertEquals("TokenMint", f.creationInstruction8179(moon, listOf(invoke(moon.programId, 1), "Program log: Instruction: TokenMint", ok(moon.programId))))
        assertEquals("CreateToken", f.creationInstruction8179(boop, listOf(invoke(boop.programId, 1), "Program log: Instruction: CreateToken", ok(boop.programId))))
        assertNull("a buy is not a launch", f.creationInstruction8179(moon, listOf(invoke(moon.programId, 1), "Program log: Instruction: Buy", ok(moon.programId))))
        assertNull("another program's create is never this one's",
            f.creationInstruction8179(boop, listOf(invoke("Other1111111111111111111111111111111111111", 1), "Program log: Instruction: CreateToken")))
    }

    @Test fun aNativeSolCurveIsTheOneMintItsCreationInitialised() {
        val mint = "7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmW2hr"
        assertEquals(mint to f.WSOL_8179, f.nativeCurveMint8885("MOONSHOT", listOf(mint)))
        assertEquals(mint to f.WSOL_8179, f.nativeCurveMint8885("BOOP", setOf(mint)))
        assertNull("two new mints: not decidable", f.nativeCurveMint8885("BOOP", listOf(mint, "Other1111111111111111111111111111111111111")))
        assertNull("only the native-SOL curves", f.nativeCurveMint8885("MET_DBC", listOf(mint)))
        assertNull("never a quote mint", f.nativeCurveMint8885("MOONSHOT", listOf(f.WSOL_8179)))
        val extract = File("src/main/kotlin/com/lifecyclebot/network/ChainLaunchFeed8179.kt").readText()
            .substringAfter("fun extract8179(").substringBefore("fun quoteDepthUsd8179(")
        assertTrue("read only after the layout and the vault pair found nothing",
            extract.indexOf("if (pair == null) pair = nativeCurveMint8885(t.key, inits)") > extract.indexOf("if (pair == null) pair = vaultPairMint8179("))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 74. Moonshot and Boop launches are heard at birth (8885)"))
    }
}
