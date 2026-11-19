package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.ForecastLossPass8865
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Terminal8136
import com.lifecyclebot.engine.truth.EdgeModel8537
import com.lifecyclebot.engine.truth.FdgGate8365
import com.lifecyclebot.engine.truth.GateOutcome8418
import com.lifecyclebot.engine.truth.LaneAuthority8577
import com.lifecyclebot.engine.truth.MemePaperTrader8544
import com.lifecyclebot.engine.truth.PaperMirror8757
import com.lifecyclebot.engine.truth.RunnerModel8861
import com.lifecyclebot.engine.truth.TrajectoryVerdict8855
import com.lifecyclebot.engine.truth.TreeRanker8737
import com.lifecyclebot.engine.truth.TuningReport8618
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8865 — owner's phone on 5.0.8862, LIVE: four buys in eight minutes, each carrying a forecast loss, each stopped out,
 * two of them opened in a lane that already held one. A forecast loss the record has not earned the right to refuse now
 * takes the lane's one live position; the mirror does not follow a paper book its own record proves losing; the stored
 * tuning report drops the close the paper book dropped; the heap log counts screens alive and the lowest heap it saw.
 */
class Aate8865ForecastLossLadderTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Before fun setup() { GateOutcome8418.resetForTests8418(); TrajectoryVerdict8855.resetForTest8855() }
    @After fun cleanup() { GateOutcome8418.resetForTests8418(); TrajectoryVerdict8855.resetForTest8855() }

    private fun verdict(p: Double, refuses: Boolean = false, pays: Boolean = false) =
        EdgeModel8537.Verdict(p, false, "MIND8842", 900L, 0, Double.NaN, Double.NaN, -6.5, refuses8715 = refuses, lossPays8865 = pays)

    private val base = CandidateAdmission8136.Candidate8136("m8865", "MOONSHOT", null,
        expectedGrossPct = 20.0, costPct = 4.5, genericScore = 80.0, genericFloor = 15.0)
    private fun live(p: Double, slotOpen: Boolean, refuses: Boolean = false, pays: Boolean = false) =
        base.copy(live = true, recordProbe8429 = slotOpen, edge8537 = verdict(p, refuses, pays))

    @Test fun theRecordAnswersAForecastLossOnlyWhenTheCoinsItNamedPaid() {
        assertTrue(EdgeModel8537.forecastLossPays8865(30, 5.0, 2.0))
        assertFalse("too few", EdgeModel8537.forecastLossPays8865(29, 5.0, 2.0))
        assertFalse("inside one standard error of zero", EdgeModel8537.forecastLossPays8865(200, 1.0, 2.0))
        assertFalse("a loss", EdgeModel8537.forecastLossPays8865(200, -3.0, 1.0))
        assertFalse(EdgeModel8537.forecastLossPays8865(200, Double.NaN, 1.0))
        assertFalse(EdgeModel8537.forecastLossPays8865(200, 5.0, Double.POSITIVE_INFINITY))
        // the report's lane-stages: SHITCOIN|FRESH_LAUNCH n75 -14.3 % +/- 7.3 neither refuses (two SE) nor pays
        assertFalse(EdgeModel8537.refusalAuthority8715(75, -14.3, 7.3, 8, -28.5, Double.NaN))
        assertFalse(EdgeModel8537.forecastLossPays8865(75, -14.3, 7.3))
        // the two answers never hold together
        for ((mean, se) in listOf(-10.0 to 1.0, -1.0 to 2.0, 0.0 to 0.5, 3.0 to 1.0, 3.0 to 4.0))
            assertFalse(EdgeModel8537.refusalAuthority8715(100, mean, se, 0, Double.NaN, Double.NaN) && EdgeModel8537.forecastLossPays8865(100, mean, se))
    }

    @Test fun aLaneStageAnswersOnceItHoldsItsOwnRecord() {
        repeat(29) { TrajectoryVerdict8855.revise8855("MOONSHOT", "BASE_START", -5.0, Double.NaN, 12.0) }
        assertNull("29 forecast losses: the lane's label record answers", TrajectoryVerdict8855.forecastLossPays8865("MOONSHOT", "BASE_START"))
        TrajectoryVerdict8855.revise8855("MOONSHOT", "BASE_START", -5.0, Double.NaN, 12.0)
        assertEquals(true, TrajectoryVerdict8855.forecastLossPays8865("moonshot", "base_start"))
        assertEquals(false, TrajectoryVerdict8855.authority8855("MOONSHOT", "BASE_START"))
        assertTrue(TrajectoryVerdict8855.statusLine8855().contains("MOONSHOT|BASE_START[refused=n30/+12.0%±0.0 kept=n0/-% PAYS]"))
        // a cohort that loses on average without proof is neither: paper trades it, live one position a lane
        repeat(40) { i -> TrajectoryVerdict8855.revise8855("CORE", "CONTROLLED_MARKUP", -5.0, Double.NaN, if (i % 4 == 0) 60.0 else -22.0) }
        assertEquals(false, TrajectoryVerdict8855.forecastLossPays8865("CORE", "CONTROLLED_MARKUP"))
        assertEquals(false, TrajectoryVerdict8855.authority8855("CORE", "CONTROLLED_MARKUP"))
        assertTrue(TrajectoryVerdict8855.statusLine8855().contains(" UNEARNED]"))
        // proven losing: refused, and it does not pay
        repeat(30) { TrajectoryVerdict8855.revise8855("EXPRESS", "DUMPING", -5.0, Double.NaN, -18.0) }
        assertEquals(true, TrajectoryVerdict8855.authority8855("EXPRESS", "DUMPING"))
        assertEquals(false, TrajectoryVerdict8855.forecastLossPays8865("EXPRESS", "DUMPING"))
        assertTrue(TrajectoryVerdict8855.statusLine8855().contains(" REFUSES]"))
        // an unread stage is never graded; a forecast of zero or better never feeds the cohort
        repeat(40) { TrajectoryVerdict8855.revise8855("QUALITY", "UNKNOWN", -5.0, Double.NaN, 12.0) }
        assertNull(TrajectoryVerdict8855.forecastLossPays8865("QUALITY", "UNKNOWN"))
        repeat(40) { TrajectoryVerdict8855.revise8855("QUALITY", "BASE_START", 3.0, Double.NaN, 12.0) }
        assertNull(TrajectoryVerdict8855.forecastLossPays8865("QUALITY", "BASE_START"))
        val em = src("engine/truth/EdgeModel8537.kt")
        assertTrue(em.contains("val stagePays8865 = try { TrajectoryVerdict8855.forecastLossPays8865(raw.lane, stage8865) } catch (_: Throwable) { null }"))
        // superseded by V5.0.8868 — the verdict goes on to the conviction tier, so the line ends in a comma
        assertTrue(em.contains("lossPays8865 = p.isFinite() && (stagePays8865 ?: (o != null && forecastLossPays8865(o.refN, o.refMean, o.refSe))),"))
    }

    @Test fun theStrongestEvidenceDecidesHowAnUnearnedForecastLossProceeds() {
        assertEquals(ForecastLossPass8865.RECORD_PAYS, CandidateAdmission8136.forecastLossPass8865(true, true, true))
        assertEquals(ForecastLossPass8865.RECORD_PAYS, CandidateAdmission8136.forecastLossPass8865(true, false, false))
        assertEquals(ForecastLossPass8865.SPECIFIC_PROOF, CandidateAdmission8136.forecastLossPass8865(false, true, true))
        assertEquals(ForecastLossPass8865.SPECIFIC_PROOF, CandidateAdmission8136.forecastLossPass8865(false, true, false))
        assertEquals(ForecastLossPass8865.LANE_SLOT, CandidateAdmission8136.forecastLossPass8865(false, false, true))
        assertEquals(ForecastLossPass8865.NONE, CandidateAdmission8136.forecastLossPass8865(false, false, false))
    }

    @Test fun liveBuysAnUnearnedForecastLossOnlyAsTheLanesOnePosition() {
        val door = CandidateAdmission8136.EDGE_FORECAST_LOSS_SLOT_TAKEN_8865
        // the lane holds nothing live: bought, and counted as the lane's one position
        val t1 = mutableListOf<String>()
        assertNull(CandidateAdmission8136.edgeModelRefusal8537(live(-6.2, slotOpen = true), null, t1))
        assertTrue(t1.toString(), t1.any { it == "forecastLoss8865=lanesOnePosition" })
        assertTrue(CandidateAdmission8136.edgeLetThrough8827().contains("UNEARNED_LIVE_ONE_A_LANE:MOONSHOT="))
        // the lane already holds one (the second MOONSHOT buy of 08:52): refused
        val t2 = mutableListOf<String>()
        assertEquals(door, CandidateAdmission8136.edgeModelRefusal8537(live(-6.6, slotOpen = false), null, t2))
        assertTrue(t2.toString(), t2.any { it == "forecastLoss8865=laneAlreadyHoldsOne" })
        assertTrue(CandidateAdmission8136.edgeLetThrough8827().contains("liveSlotTaken8865="))
        // a forecast of zero or better, or no forecast, is not this door's business whatever the lane holds
        assertNull(CandidateAdmission8136.edgeModelRefusal8537(live(0.0, slotOpen = false), null, mutableListOf()))
        assertNull(CandidateAdmission8136.edgeModelRefusal8537(live(4.0, slotOpen = false), null, mutableListOf()))
        assertNull(CandidateAdmission8136.edgeModelRefusal8537(live(Double.NaN, slotOpen = false), null, mutableListOf()))
        assertNull(CandidateAdmission8136.edgeModelRefusal8537(base.copy(live = true, recordProbe8429 = false), null, mutableListOf()))
        // paper is where it is tried: unchanged, whatever the lane holds
        assertNull(CandidateAdmission8136.edgeModelRefusal8537(base.copy(edge8537 = verdict(-6.6)), null, mutableListOf()))
        assertTrue(CandidateAdmission8136.edgeLetThrough8827().contains("UNEARNED_PAPER:MOONSHOT="))
        // an EARNED refusal is not a position to try: refused with the lane empty, in both books, as before
        assertEquals(CandidateAdmission8136.EDGE_MODEL_NEGATIVE_8537,
            CandidateAdmission8136.edgeModelRefusal8537(live(-6.6, slotOpen = true, refuses = true), null, mutableListOf()))
        assertEquals(CandidateAdmission8136.EDGE_MODEL_NEGATIVE_8537,
            CandidateAdmission8136.edgeModelRefusal8537(base.copy(edge8537 = verdict(-6.6, refuses = true)), null, mutableListOf()))
    }

    @Test fun theRecordOrTheCandidatesOwnProofLetsItThroughWithTheLaneHoldingOne() {
        val pays = mutableListOf<String>()
        assertNull(CandidateAdmission8136.edgeModelRefusal8537(live(-6.6, slotOpen = false, pays = true), null, pays))
        assertTrue(pays.any { it == "forecastLoss8865=recordPays" })
        assertTrue(CandidateAdmission8136.edgeLetThrough8827().contains("RECORD_PAYS_LIVE:MOONSHOT="))
        val supported = CandidateAdmission8136.Evidence8136(CandidateAdmission8136.KIND_SETUP, "PULLBACK", 60, 9.0, 1.0)
        val proof = mutableListOf<String>()
        assertNull(CandidateAdmission8136.edgeModelRefusal8537(live(-6.6, slotOpen = false), supported, proof))
        assertTrue(proof.toString(), proof.any { it.startsWith("forecastLoss8865=specificProof ") })
        // evidence that is thin, outlier-suspect or itself losing is not proof
        val kind = CandidateAdmission8136.KIND_SETUP
        for (e in listOf(CandidateAdmission8136.Evidence8136(kind, "PULLBACK", 2, 60.0, 1.0),
                CandidateAdmission8136.Evidence8136(kind, "PULLBACK", 100, 40.0, 1.0, suspect8290 = true),
                CandidateAdmission8136.Evidence8136(kind, "PULLBACK", 60, -2.0, 1.0)))
            assertEquals(CandidateAdmission8136.EDGE_FORECAST_LOSS_SLOT_TAKEN_8865,
                CandidateAdmission8136.edgeModelRefusal8537(live(-6.6, slotOpen = false), e, mutableListOf()))
    }

    @Test fun theRefusalBindsPastLaneAuthorityAndIsFiledOnItsOwnRecord() {
        val door = CandidateAdmission8136.EDGE_FORECAST_LOSS_SLOT_TAKEN_8865
        assertTrue(CandidateAdmission8136.verifiedRecordDoor8650(door))
        assertTrue(LaneAuthority8577.binding8577(door))
        assertTrue(LaneAuthority8577.binding8577("CANDIDATE_ADMISSION_8136_$door"))
        assertEquals("ADMISSION8136_EDGE_FORECAST_LOSS_SLOT_TAKEN_8865", FdgGate8365.gateKey8392("CANDIDATE_ADMISSION_8136_$door"))
        assertFalse("no hard-safety marker is hidden in the name", FdgGate8365.hardSafety8392(door))
        // the whole decision: the live candidate is refused by it and lane authority leaves it refused
        val c = live(-6.6, slotOpen = false)
        val decided = CandidateAdmission8136.decide8136(c, CandidateAdmission8136::verifiedRecordDoor8650)
        assertEquals(door, decided.reason)
        assertEquals(Terminal8136.REFUSE, decided.terminal)
        assertTrue(decided.specific)
        val wasOn = LaneAuthority8577.enabled8577
        try {
            LaneAuthority8577.enabled8577 = true
            val applied = LaneAuthority8577.apply8577(c, decided, null, "EXPANDING")
            assertEquals(Terminal8136.REFUSE, applied.terminal)
            assertEquals(door, applied.reason)
        } finally { LaneAuthority8577.enabled8577 = wasOn }
        // the same candidate with the lane empty is not refused by this door
        assertNotEquals(door, CandidateAdmission8136.decide8136(c.copy(recordProbe8429 = true), CandidateAdmission8136::verifiedRecordDoor8650).reason)
        // and the same candidate on paper is not refused by it either
        assertNotEquals(door, CandidateAdmission8136.decide8136(c.copy(live = false), CandidateAdmission8136::verifiedRecordDoor8650).reason)
        val adm = src("engine/truth/CandidateAdmission8136.kt")
        val refusal = adm.substringAfter("internal fun edgeModelRefusal8537(").substringBefore("private fun specificProof8843(")
        assertTrue(refusal.contains("return if (v.predPct.isFinite() && v.predPct < 0.0) unearnedForecastLoss8865(c, v, edge, trace) else null"))
        assertTrue(refusal.contains("val localProof8843 = specificProof8843(c, edge)"))
    }

    @Test fun theDoorStandsAsideOnceTheCoinsItRefusedAreProvenToWin() {
        val door = CandidateAdmission8136.EDGE_FORECAST_LOSS_SLOT_TAKEN_8865
        val gate = FdgGate8365.gateKey8392("CANDIDATE_ADMISSION_8136_$door")
        assertEquals(door, CandidateAdmission8136.edgeModelRefusal8537(live(-6.6, slotOpen = false), null, mutableListOf()))
        repeat(99) { i -> GateOutcome8418.book8418(gate, "MOONSHOT", if (i % 5 < 3) 60.0 else -20.0, i % 5 == 0) }
        assertEquals("99 graded refusals are not an audit", door, CandidateAdmission8136.edgeModelRefusal8537(live(-6.6, slotOpen = false), null, mutableListOf()))
        GateOutcome8418.book8418(gate, "MOONSHOT", 60.0, false)
        assertTrue(GateOutcome8418.selectionGateRetired8696("CANDIDATE_ADMISSION_8136_$door", "MOONSHOT"))
        val trace = mutableListOf<String>()
        assertNull(CandidateAdmission8136.edgeModelRefusal8537(live(-6.6, slotOpen = false), null, trace))
        assertTrue(trace.any { it == "forecastLoss8865=doorRetiredByItsOwnRecord" })
        // a record on which its refusals lose keeps it binding
        GateOutcome8418.resetForTests8418()
        repeat(200) { i -> GateOutcome8418.book8418(gate, "MOONSHOT", if (i % 20 == 0) 150.0 else -30.0, i % 20 == 0) }
        assertEquals(door, CandidateAdmission8136.edgeModelRefusal8537(live(-6.6, slotOpen = false), null, mutableListOf()))
    }

    @Test fun theMirrorDoesNotFollowABookItsOwnRecordProvesLosing() {
        // the book the owner chose to mirror, and the same book on 19 Nov
        assertFalse(MemePaperTrader8544.provenLosing8608(176, 7.9, 15.3, 1.46))
        assertTrue(MemePaperTrader8544.provenLosing8608(43, -22.9, 3.9, 0.06))
        assertFalse("29 closes prove nothing", MemePaperTrader8544.provenLosing8608(29, -22.9, 3.9, 0.06))
        val mirror = src("engine/truth/PaperMirror8757.kt").substringAfter("fun mirror8757(").substringBefore("fun economicsClear8758(")
        val check = mirror.indexOf("if (try { MemePaperTrader8544.laneProvenLosing8608(c.lane) } catch (_: Throwable) { false }) {")
        assertTrue(check > mirror.indexOf("stopped8757(nowMs)) return d"))
        assertTrue("before the paper book's decision is asked for", check in 0 until mirror.indexOf("CandidateAdmission8136.decide8136(c.copy(live = false, liveEdge8114 = null))"))
        assertTrue(PaperMirror8757.statusLine8757().startsWith("paperMirror8757[on="))
        assertTrue(PaperMirror8757.statusLine8757().contains(" notFollowedBookProvenLosing8865="))
        // the owner's mirror still passes a learned door whose record has not proven it protects, this one included
        assertFalse(PaperMirror8757.hard8757(CandidateAdmission8136.EDGE_FORECAST_LOSS_SLOT_TAKEN_8865, "MOONSHOT"))
    }

    @Test fun theStoredTuningReportDropsTheCloseThePaperBookDropped() {
        // the report's rows: CORE|LIVE_REFUSE:LIVE_STAGE_LOSES_8626 n=13 mean=+18538294.4 % and CORE|CLOCK n=11
        assertTrue(TuningReport8618.rowImpossible8865(13, 3, 13 * 18_538_294.4, 240_997.827))
        assertTrue(TuningReport8618.rowImpossible8865(11, 2, 2.4099e8, 240_998.052))
        // honest rows stay: losers, and a row of real runners
        assertFalse(TuningReport8618.rowImpossible8865(40, 5, 40 * -7.7, -0.309))
        assertFalse(TuningReport8618.rowImpossible8865(240, 55, 240 * 20.3, 4.9))
        assertFalse(TuningReport8618.rowImpossible8865(10, 6, 10 * 300.0, 3.0))
        assertFalse(TuningReport8618.rowImpossible8865(0, 0, 0.0, 0.0))
        val t = src("engine/truth/TuningReport8618.kt")
        val restore = t.substringAfter("@Synchronized private fun restore8744() {").substringBefore("fun rowImpossible8865(")
        assertTrue(restore.indexOf("repair8865()") > restore.indexOf("pnl += a.optDouble(4, 0.0); holdMs += a.optLong(5)"))
        assertTrue(t.contains("MemePaperTrader8544.rowImpossible8855(doubleArrayOf(n.toDouble(), wins.toDouble(), sumNetPct, pnlSol))"))
        assertTrue(t.contains("if (synchronized(a) { rowImpossible8865(a.n, a.wins, a.sum, a.pnl) }) { gone += e.key; stored.remove() }"))
    }

    @Test fun theRunnerModelSaysWhatItWaitsFor() {
        assertEquals("trainRows=151/160 (the oldest 60% of rows=252)", RunnerModel8861.waitingFor8865(252))
        assertEquals("trainRows=0/160 (the oldest 60% of rows=0)", RunnerModel8861.waitingFor8865(0))
        assertEquals("trainRows=159/160 (the oldest 60% of rows=266)", RunnerModel8861.waitingFor8865(266))
        assertEquals("picks<30 at every share on the validation fifth (53 of rows=267)", RunnerModel8861.waitingFor8865(267))
        assertTrue(src("engine/truth/RunnerModel8861.kt").contains("else \"waiting \${waitingFor8865(rowsN)}\"} \" +"))
    }

    @Test fun theTrainedTreesSurviveARestartWithinOneLearningLine() {
        val r = kotlin.random.Random(8865)
        val xs = List(600) { DoubleArray(5) { j -> if (j == 3 && r.nextDouble() < 0.3) Double.NaN else r.nextDouble() } }
        val ys = xs.map { x -> (if (x[0] > 0.6 && x[2] < 0.3) 25.0 else -8.0) + r.nextDouble() * 6 - 3 }
        val m = TreeRanker8737.train8737(xs, ys, List(xs.size) { 1.0 }, 1_795_049_000_000L, trees = 25)
        assertTrue("the fit found the interaction", m.trees.any { t -> t.feat.any { it >= 0 } })
        val bytes = java.io.ByteArrayOutputStream()
        TreeRanker8737.write8865(java.io.DataOutputStream(bytes), m, 8801, 1_795_024_788_972L)
        fun read(features: Int = 5, epoch: Int = 8801, line: Long = 1_795_024_788_972L, raw: ByteArray = bytes.toByteArray()) =
            TreeRanker8737.read8865(java.io.DataInputStream(java.io.ByteArrayInputStream(raw)), features, epoch, line)
        val back = read()!!
        assertEquals(m.rows, back.rows); assertEquals(m.atMs, back.atMs); assertEquals(m.trees.size, back.trees.size)
        assertEquals(m.base, back.base, 0.0)
        // the restored model answers every question exactly as the fitted one did, an unobserved value included
        for (x in xs.take(200) + listOf(DoubleArray(5) { Double.NaN }, doubleArrayOf(0.9, 0.5, 0.1, Double.NaN, 0.5)))
            assertEquals(m.predict(x), back.predict(x), 0.0)
        // another learning line (a reset), model epoch or feature list: not this build's model
        assertNull(read(line = 1_795_024_788_973L))
        assertNull(read(epoch = 8802))
        assertNull(read(features = 6))
        assertNull("not a model file", read(raw = ByteArray(64)))
        // a short file is the stream's own failure, which the restore counts and survives
        try { read(raw = bytes.toByteArray().copyOf(bytes.size() / 2)); org.junit.Assert.fail("a truncated file read as a model") } catch (_: java.io.EOFException) {}
        // a tree whose split points back at itself would never end: refused when read
        val edges = List(3) { doubleArrayOf(0.5) }
        fun stored(t: TreeRanker8737.Tree): ByteArray = java.io.ByteArrayOutputStream().also {
            TreeRanker8737.write8865(java.io.DataOutputStream(it), TreeRanker8737.Model(1.0, edges, listOf(t), 10, 5L), 8801, 7L)
        }.toByteArray()
        val leafs = booleanArrayOf(false, false, false)
        val sound = TreeRanker8737.Tree(intArrayOf(0, -1, -1), intArrayOf(0, 0, 0), leafs, intArrayOf(1, -1, -1), intArrayOf(2, -1, -1), doubleArrayOf(0.0, -1.0, 2.0))
        val loop = TreeRanker8737.Tree(intArrayOf(0, -1, -1), intArrayOf(0, 0, 0), leafs, intArrayOf(0, -1, -1), intArrayOf(2, -1, -1), doubleArrayOf(0.0, -1.0, 2.0))
        val badFeature = TreeRanker8737.Tree(intArrayOf(3, -1, -1), intArrayOf(0, 0, 0), leafs, intArrayOf(1, -1, -1), intArrayOf(2, -1, -1), doubleArrayOf(0.0, -1.0, 2.0))
        val notFinite = TreeRanker8737.Tree(intArrayOf(0, -1, -1), intArrayOf(0, 0, 0), leafs, intArrayOf(1, -1, -1), intArrayOf(2, -1, -1), doubleArrayOf(0.0, Double.NaN, 2.0))
        fun readSmall(raw: ByteArray) = TreeRanker8737.read8865(java.io.DataInputStream(java.io.ByteArrayInputStream(raw)), 3, 8801, 7L)
        val ok = readSmall(stored(sound))!!
        assertEquals(0.0, ok.predict(doubleArrayOf(0.2, 0.0, 0.0)), 0.0)   // base 1.0, left leaf -1.0
        assertEquals(3.0, ok.predict(doubleArrayOf(0.9, 0.0, 0.0)), 0.0)   // base 1.0, right leaf +2.0
        assertNull(readSmall(stored(loop)))
        assertNull(readSmall(stored(badFeature)))
        assertNull(readSmall(stored(notFinite)))
        val tree = src("engine/truth/TreeRanker8737.kt")
        val retrain = tree.substringAfter("fun maybeRetrain8737(dataset: File?, nowMs: Long) {").substringBefore("exec.execute {")
        assertTrue("the saved model is read before a fit is asked for", retrain.trimStart().startsWith("restore8865()"))
        assertTrue(tree.indexOf("save8865()\n") > tree.indexOf("lastStatus = \"trained rows=\${rows.size} trees=\$TREES ms=\${System.currentTimeMillis() - t0}\""))
        assertTrue(tree.contains("read8865(it, FEATURES.size, EdgeModel8537.MODEL_EPOCH_8642, LearningEpoch8164.epochMs8164())"))
        assertTrue(tree.contains("write8865(it, m, EdgeModel8537.MODEL_EPOCH_8642, LearningEpoch8164.epochMs8164())"))
        assertEquals("the stored model's epoch is the edge model's", 8801, EdgeModel8537.MODEL_EPOCH_8642)
    }

    @Test fun theHeapLogCountsScreensAliveAndTheLowestHeapItSaw() {
        ActivityCensus8865.resetForTest8865()
        try {
            assertTrue(ActivityCensus8865.statusLine8865().startsWith("activities8865[alive=0 stackedNow=none stackedEver=none {-} "))
            ActivityCensus8865.noteCreated8865("SplashActivity")
            ActivityCensus8865.noteCreated8865("MainActivity")
            ActivityCensus8865.noteDestroyed8865("SplashActivity")
            assertTrue(ActivityCensus8865.statusLine8865(), ActivityCensus8865.statusLine8865().startsWith(
                "activities8865[alive=1 stackedNow=none stackedEver=none {MainActivity=1/1/1 SplashActivity=0/1/1} "))
            // a second dashboard built over the first, as 5.0.8862 did from the splash
            ActivityCensus8865.noteCreated8865("MainActivity")
            assertTrue(ActivityCensus8865.statusLine8865(), ActivityCensus8865.statusLine8865().startsWith(
                "activities8865[alive=2 stackedNow=MainActivity x2 stackedEver=MainActivity x2 {MainActivity=2/2/2 SplashActivity=0/1/1} "))
            ActivityCensus8865.noteDestroyed8865("MainActivity")
            assertTrue(ActivityCensus8865.statusLine8865(), ActivityCensus8865.statusLine8865().startsWith(
                "activities8865[alive=1 stackedNow=none stackedEver=MainActivity x2 {MainActivity=1/2/2 SplashActivity=0/1/1} "))
        } finally { ActivityCensus8865.resetForTest8865() }

        // the report's timeline: the lowest sample and the census at that sample
        val floor = HeapLog8618.floor8865(listOf(398.0, 492.0, 361.0, 512.0, 377.0), listOf(96.0, 99.0, 96.0, 104.0, 105.0))
        assertEquals(361.0, floor!!.first, 0.0); assertEquals(96.0, floor.second, 0.0)
        assertNull(HeapLog8618.floor8865(emptyList(), emptyList()))
        assertNull(HeapLog8618.floor8865(listOf(Double.NaN, -1.0), listOf(1.0, 1.0)))
        assertEquals(0.0, HeapLog8618.floor8865(listOf(200.0), emptyList())!!.second, 0.0)

        val app = src("AATEApp.kt")
        assertTrue(app.contains("com.lifecyclebot.engine.ActivityCensus8865.noteCreated8865(activity.javaClass.simpleName)"))
        assertTrue(app.contains("com.lifecyclebot.engine.ActivityCensus8865.noteDestroyed8865(activity.javaClass.simpleName)"))
        val heap = src("engine/HeapLog8618.kt")
        assertTrue(heap.contains("sb.append(\"  SCREENS    \").append(try { ActivityCensus8865.statusLine8865() }"))
        assertTrue(heap.contains("floor8865(snap.map { it.usedMb }, snap.map { it.censusMb })?.let { (used, census) ->"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 56. A forecast loss takes the lane's one live position (8865)"))
    }
}
