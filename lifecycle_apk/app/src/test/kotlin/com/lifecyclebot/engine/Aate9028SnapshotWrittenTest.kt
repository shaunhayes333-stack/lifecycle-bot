package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexExit7897
import com.lifecyclebot.engine.cortex.CortexLedger7885
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9028 — the Cortex snapshot is written, not built: the ledger goes straight into one sized buffer and parses to the
 * same object the JSONObject tree gave; and the forecast sell does not act on a runner lane whose regret record has never
 * been measured.
 */
class Aate9028SnapshotWrittenTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    private fun ledger(): CortexLedger7885 {
        val seat = "3#" + CortexLedger7885.Seat(3).encode()
        val l = CortexLedger7885()
        l.decode(JSONObject().put("seats", JSONObject().put("RSI|MOONSHOT", seat).put("we\"ird\\key|EXPRESS@HOT", seat).put("tab\tkey", seat))
            .put("pairs", JSONObject().put("a|b", "1.0,2.5,-3.0").put("c|d", "0.0,0.0,0.0")))
        return l
    }

    private fun sameObject(a: JSONObject, b: JSONObject) {
        assertEquals(a.keys().asSequence().toSet(), b.keys().asSequence().toSet())
        for (part in listOf("lanes", "seats", "pairs")) {
            val x = a.getJSONObject(part); val y = b.getJSONObject(part)
            assertEquals(part, x.keys().asSequence().toSet(), y.keys().asSequence().toSet())
            for (k in x.keys()) assertEquals("$part/$k", x.getString(k), y.getString(k))
        }
    }

    @Test fun theWrittenLedgerParsesToTheSameObjectAsTheTree() {
        val l = ledger()
        assertEquals(3, l.seatCount9028())
        val sb = StringBuilder()
        l.appendJson9028(sb)
        sameObject(l.encode(), JSONObject(sb.toString()))
        // and a ledger read back from the written text writes the same text again
        val back = CortexLedger7885().also { it.decode(JSONObject(sb.toString())) }
        sameObject(l.encode(), back.encode())
        // an empty ledger is three empty objects
        assertEquals("{\"lanes\":{},\"seats\":{},\"pairs\":{}}", StringBuilder().also { CortexLedger7885().appendJson9028(it) }.toString())
    }

    @Test fun theBlobKeepsItsOtherMembers() {
        val l = ledger()
        val text = CortexLedger7885.withLedger9028("ledger", l, JSONObject().put("board", JSONObject().put("x", 1)).put("jointNext8372", 5L).put("s", "q\"t"), 64)
        val o = JSONObject(text)
        sameObject(l.encode(), o.getJSONObject("ledger"))
        assertEquals(1, o.getJSONObject("board").getInt("x")); assertEquals(5L, o.getLong("jointNext8372")); assertEquals("q\"t", o.getString("s"))
        assertEquals(setOf("ledger", "board", "jointNext8372", "s"), o.keys().asSequence().toSet())
        val alone = JSONObject(CortexLedger7885.withLedger9028("ledger", l, JSONObject(), 0))
        assertEquals(setOf("ledger"), alone.keys().asSequence().toSet())
        sameObject(l.encode(), alone.getJSONObject("ledger"))
    }

    @Test fun aQuotedStringReadsBackExactly() {
        for (s in listOf("plain|KEY@R", "a\"b\\c", "line\nbreak\rtab\t", "ctl\u0001\u001f", "sep  ", "", "unicode é 漢")) {
            val sb = StringBuilder("{\"k\":"); CortexLedger7885.quote9028(sb, s); sb.append('}')
            assertEquals(s, JSONObject(sb.toString()).getString("k"))
        }
    }

    @Test fun theBufferIsSizedFromTheLastSnapshot() {
        assertEquals(18_000_000 + 2_250_000 + 4_096, CortexLedger7885.sizeHint9028(18_000_000, 0))
        assertEquals(1_000 * 360 + 65_536, CortexLedger7885.sizeHint9028(0, 1_000))
        assertEquals(96 * 1024 * 1024, CortexLedger7885.sizeHint9028(Int.MAX_VALUE, 0))
        assertEquals(96 * 1024 * 1024, CortexLedger7885.sizeHint9028(0, Int.MAX_VALUE))
        val c = src("engine/cortex/Cortex7885.kt")
        assertTrue(c.contains("out[PERSIST_KEY] = CortexLedger7885.withLedger9028(\"ledger\", l, rest9028,"))
        assertFalse("the ledger no longer goes through the tree", c.contains(".put(\"ledger\", l.encode())"))
        val t = src("engine/cortex/CortexTiming7900.kt")
        assertTrue(t.contains("CortexLedger7885.withLedger9028(\"ledger\", ledger, org.json.JSONObject()"))
        assertFalse(t.contains(".put(\"ledger\", ledger.encode())"))
    }

    @Test fun theForecastSellWaitsForARegretRecordOnRunnerLanesOnly() {
        assertEquals(8, CortexExit7897.REGRET_MIN_N_9028)
        for (lane in listOf("MOONSHOT", "SHITCOIN", "EXPRESS", "PROJECT_SNIPER")) {
            assertTrue(lane, CortexExit7897.regretUnmeasured9028(lane, 0))
            assertTrue(lane, CortexExit7897.regretUnmeasured9028(lane, 7))
            assertFalse("measured: the tail-cut guard decides", CortexExit7897.regretUnmeasured9028(lane, 8))
        }
        for (lane in listOf("BLUECHIP", "QUALITY", "TREASURY", "CORE"))
            assertFalse("not a runner lane: unchanged", CortexExit7897.regretUnmeasured9028(lane, 0))
        val ex = src("engine/cortex/CortexExit7897.kt")
        val tail = ex.indexOf("if (tailCutting8153(r.lane))"); val reg = ex.indexOf("if (regretUnmeasured9028(r.lane))"); val sold = ex.indexOf("inc(\"SOLD_\${r.lane}\")")
        assertTrue(tail in 0 until reg && reg < sold)
    }
}
