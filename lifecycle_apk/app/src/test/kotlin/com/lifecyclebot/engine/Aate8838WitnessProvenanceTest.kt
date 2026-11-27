package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.PrintWitness8750
import com.lifecyclebot.engine.truth.TradeTape8100
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8838 — a subsequent good price cannot legitimise an earlier ghost mark. */
class Aate8838WitnessProvenanceTest {

    @Test fun aLaterTradeCannotRetroactivelyAuthenticateAnEarlierPrice() {
        val mint = "Witness8838" + System.nanoTime().toString()
        val at = System.currentTimeMillis()
        val p = 1.0e-6
        assertTrue(TradeTape8100.note8285(mint, p, 0.5, 0.5 / p, true,
            TradeTape8100.SRC_HELIUS_8169, signature = "8838-A", eventMs = at, recvMs = at,
            priceBasis8750 = PrintWitness8750.Basis.RESERVES))
        assertTrue(PrintWitness8750.witnessedPrint8750(mint, at, p))
        assertFalse("not the witnessed transaction time", PrintWitness8750.witnessedPrint8750(mint, at - 1L))
        assertFalse("not the witnessed price at the same time", PrintWitness8750.witnessedPrint8750(mint, at, p * 0.5))

        val next = at + 1_000L
        val nextP = p * 1.05
        assertTrue(TradeTape8100.note8285(mint, nextP, 0.5, 0.5 / nextP, true,
            TradeTape8100.SRC_HELIUS_8169, signature = "8838-B", eventMs = next, recvMs = next,
            priceBasis8750 = PrintWitness8750.Basis.RESERVES))
        assertTrue(PrintWitness8750.witnessedPrint8750(mint, next, nextP))
        assertFalse("the newest witness never authenticates an earlier mark", PrintWitness8750.witnessedPrint8750(mint, at, p))
    }

    @Test fun unsignedQuoteUpdatesCannotTriggerAConsensusRepricing() {
        val anonymous = listOf(0.140, 0.135, 0.132).mapIndexed { i, p ->
            PrintWitness8750.Out8801(p, 5_000L + i.toLong() * 1_000L, "", "")
        }
        assertFalse("a source can stream three changing quotes without three independent transactions",
            PrintWitness8750.shiftIndependent8801(anonymous))
        assertFalse("wallet fields cannot repair missing transaction identities",
            PrintWitness8750.shiftIndependent8801(anonymous.mapIndexed { i, o ->
                PrintWitness8750.Out8801(o.px, o.at, "", "wallet$i")
            }))
        val signed = anonymous.mapIndexed { i, o ->
            PrintWitness8750.Out8801(o.px, o.at, "signature$i", "wallet$i")
        }
        assertTrue("a real repricing remains eligible with independent transaction proof",
            PrintWitness8750.shiftIndependent8801(signed))
    }

    @Test fun futureObservationsAreNotHistoricalConsensus() {
        val future = (0..4).map { (1.0 + it / 100.0) to (2_000L + 100L * it.toLong()) }
        assertNull("late out-of-order print cannot look ahead to the later market",
            PrintWitness8750.median8800(future, 1_000L))
    }

    @Test fun everyDownstreamTapeMarkBindsWitnessPriceAndTime() {
        val root = File("src/main/kotlin/com/lifecyclebot")
        for (file in listOf(
            "engine/truth/QuietCurve8750.kt",
            "engine/truth/ShadowMark8360.kt",
            "engine/truth/ForwardReturnLabeler7731.kt",
            "perps/CryptoAltTrader.kt",
        )) {
            val src = File(root, file).readText()
            val direct=src.contains("witnessedPrint8750(mint, ") &&
                (src.contains("witnessedPrint8750(mint, tp.second, tp.first)") ||
                    src.contains("witnessedPrint8750(mint, it.second, it.first)"))
            val tape=File(root,"engine/truth/TradeTape8100.kt").readText()
                .substringAfter("internal fun lastWitnessedTrade9058").substringBefore("internal fun resetForTest8100")
            val typed=src.contains("TradeTape8100.lastWitnessedTrade9058(mint)") &&
                tape.contains("it.sourceTimeKnown8386 && !it.priceOnly") &&
                tape.contains("PrintWitness8750.witnessedPrint8750(mint,it.atMs,it.priceSol)")
            assertTrue("$file must bind both witness price and event time, directly or through the typed tape adapter",direct||typed)
            assertFalse("$file must not use the timestamp-only legacy read",
                src.contains("witnessedPrint8750(mint, tp.second)") ||
                    src.contains("witnessedPrint8750(mint, it.second)"))
        }
    }
}
