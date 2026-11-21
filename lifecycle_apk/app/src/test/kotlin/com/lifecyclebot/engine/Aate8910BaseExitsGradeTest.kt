package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.BasePocket8904
import com.lifecyclebot.engine.truth.FieldManual7715
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8910 — the base pocket is graded with the exits the lane trades it with (stop first, then the doctrine line from a
 * +10 % held peak, else the hour), and the same base minted on pump.fun has its own record, admitting on the same proof.
 */
class Aate8910BaseExitsGradeTest {
    private val other = "7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmW2hr"
    private val pump = "8KdKRqDWzDsrSrxo4mGA8e48KbG2JK9XgnVEFVXfpump"

    @Test fun theHourUnderTheLanesOwnExitsStopFirst() {
        val cost = 3.0
        val stop = FieldManual7715.stopPctQuiet8830("EXPRESS", cost).coerceIn(3.0, 40.0)
        // the low reached the stop: the stop, whatever the hour did after (the order worst for the pocket)
        assertEquals(-stop - cost, BasePocket8904.doctrineNet8910("EXPRESS", 60.0, -stop - 0.1, 80.0, cost, 57.0), 1e-9)
        // a +30 % held peak that ended under the doctrine line is sold on the line
        val line = RunnerDoctrine8584.paperStopLevel8584(stop, 30.0)
        assertTrue(line > 0.0)
        assertEquals(line - cost, BasePocket8904.doctrineNet8910("EXPRESS", -1.0, -stop + 1.0, 30.0, cost, -4.0), 1e-9)
        // ended above the line: the hour
        assertEquals(40.0 - cost, BasePocket8904.doctrineNet8910("EXPRESS", 40.0, -1.0, 45.0, cost, 37.0), 1e-9)
        // no +10 % peak, stop not reached: the hour
        assertEquals(-2.0 - cost, BasePocket8904.doctrineNet8910("EXPRESS", -2.0, -4.0, 6.0, cost, -5.0), 1e-9)
        // nothing read: the hour's own net, as before
        assertEquals(-5.0, BasePocket8904.doctrineNet8910("EXPRESS", Double.NaN, -4.0, 6.0, cost, -5.0), 1e-9)
    }

    @Test fun aRowFilesTheExitsNetAndKeepsTheHold() {
        val row = JSONObject("{\"t\":1795136269192,\"horizonMs\":3600000,\"mint\":\"$other\",\"lane\":\"EXPRESS\",\"stage\":\"BASE_START\"," +
            "\"liq\":45000,\"cost\":3.0,\"gross\":-20.0,\"net\":-23.0,\"peakPct\":5.0,\"heldPeakPct\":4.0,\"minPct\":-25.0}")
        val r = BasePocket8904.rowOf8904(row)
        assertNotNull(r)
        val stop = FieldManual7715.stopPctQuiet8830("EXPRESS", 3.0).coerceIn(3.0, 40.0)
        assertEquals("the stop took it, not the hour", -stop - 3.0, r!!.net, 1e-9)
        assertEquals(-23.0, r.hold, 1e-9)
        assertNotNull("pump.fun's base is filed too, in its own record", BasePocket8904.rowOf8904(JSONObject(row.toString()).put("mint", pump)))
        assertNull(BasePocket8904.rowOf8904(JSONObject(row.toString()).put("stage", "DUMPING")))
    }

    @Test fun pumpFunsBaseIsItsOwnRecordAndAdmitsOnTheSameProof() {
        BasePocket8904.resetForTest8904()
        for (i in 0 until 30) BasePocket8904.addForTest8904("SHITCOIN", "${i}xpump", 20.0 + (i % 3), i * 3_600_000L)
        assertEquals("the off-pump.fun record is untouched", 0.0, BasePocket8904.record8904()[0], 0.0)
        val p = BasePocket8904.recordPump8910()
        assertEquals(30.0, p[0], 0.0)
        val e = BasePocket8904.evidenceOf8904("SHITCOIN", p, "BASE_PUMP_5K_100K")
        assertNotNull(e); assertEquals("BASE_PUMP_5K_100K", e!!.id)
        val line = BasePocket8904.statusLine8904()
        assertTrue(line, line.startsWith("basePocket8904[UNPROVEN n0 hold8910{n0} pump8910{PROVEN n30/"))
        BasePocket8904.resetForTest8904()
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/BasePocket8904.kt").readText()
        assertTrue(src.contains("(if (origin == \"PUMP\") evidenceOf8904(lane, recordPump8910(), ID_PUMP_8910) else evidenceOf8904(lane, record8904()))"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 98. The majors playbook, strategy records, and the base graded on its exits (8910)"))
    }
}
