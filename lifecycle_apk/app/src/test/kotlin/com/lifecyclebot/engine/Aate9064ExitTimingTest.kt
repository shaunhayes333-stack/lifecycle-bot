package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExitQuoteCorroboration7928 as QuoteGate
import com.lifecyclebot.engine.truth.LaunchSniper8748 as Sniper
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class Aate9064ExitTimingTest {
    private val reason="THIN_LIQ_EARLY_RUG_BACKSTOP_-10"
    private val wall=1_800_000_000_000L
    private val mono=50_000L
    @Before fun before(){QuoteGate.resetForTest8521();Sniper.resetClocksForTest9025()}
    @After fun after(){QuoteGate.resetForTest8521();Sniper.resetClocksForTest9025()}

    @Test fun thinPoolStopRevalidatesEvenAfterTheDisplayRecovers() {
        for(mark in listOf(-10.0,-4.2,2.0,Double.NaN)) {
            assertTrue(QuoteGate.applies(reason,mark,true,-10.0))
            var reads=0
            assertFalse(QuoteGate.allowSell("thin-$mark","X",reason,mark,.043678,
                nowMs=1000,positionIdentity8521="p|100|1",stopThresholdPct8521=-10.0,
                sellCostSol8521=.00011){reads++;.041848})
            assertEquals(1,reads)
        }
        assertFalse(QuoteGate.applies(reason,-30.0,false,-10.0))
        for(r in listOf("RUG_DRAIN_EXIT","RUG_PULL_DETECTED","MANUAL_STOP","SHUTDOWN"))
            assertFalse(r,QuoteGate.applies(r,-30.0,true,-10.0))
    }

    @Test fun confirmedLossAndUnavailableQuoteKeepTheExistingBoundedEscape() {
        assertTrue(QuoteGate.allowSell("dump","X",reason,-30.0,.05,nowMs=1000,
            stopThresholdPct8521=-10.0){.04})
        for((elapsed,expected) in listOf(0L to false,5999L to false,6000L to true)) {
            assertEquals(expected,QuoteGate.allowSell("wait-$elapsed","X",reason,-12.0,.05,
                stopThresholdPct8521=-10.0,sellsUnquoted8706={
                    StopMarkConfirm8418.unreadStopFires8706(1000,1000+elapsed,-12.0,true)
                }){null})
        }
        assertTrue(QuoteGate.allowSell("unread-limit","X",reason,-12.0,.05,
            stopThresholdPct8521=-10.0,sellsUnquoted8706={
                StopMarkConfirm8418.unreadStopFires8706(1000,31000,-12.0,false)
            }){null})
    }

    private fun bind(id:String,version:Long,dependent:Boolean,delay:Long) {
        val mint="mint-$id"
        Sniper.openOpportunityForTest9043(id,mint,wall,wall,mono,30_000)
        Sniper.noteAdmission9025(mint,"ADMIT","SPECIFIC_EDGE_PROVEN_8136",wall+delay,
            "MOONSHOT",version,id,mono+delay,fireDependent=dependent)
        Sniper.noteGate9025(mint,true,"ALLOW",wall+delay,"MOONSHOT",version,mono+delay)
        assertTrue(Sniper.bindTicket9043(mint,"MOONSHOT",version,"attempt-$id"))
    }

    @Test fun independentLaterEntriesDoNotTrainLaunchExecutionLatency() {
        val before=Sniper.requiredDelayMs8749()
        repeat(3){i->
            val id="independent-$i";bind(id,i+1L,false,571_000)
            assertNull(Sniper.dispatchRefusal9043("attempt-$id",wall+579_000,mono+579_000))
            Sniper.noteBuyConfirmed8749("mint-$id",wall+579_000,"attempt-$id","sig-$id",mono+579_000)
        }
        assertEquals(before,Sniper.requiredDelayMs8749())
        val line=Sniper.clockLine9025(wall+600_000,mono+600_000)
        assertTrue(line,line.contains("launch=0 independent=3"))
        assertTrue(line,line.contains("support=INDEPENDENT"))
    }

    @Test fun dependentLaunchEntriesKeepTheirDeadlineAndTeachTheirOwnLatency() {
        repeat(3){i->
            val id="launch-$i";bind(id,i+1L,true,15_000)
            assertNull(Sniper.dispatchRefusal9043("attempt-$id",wall+30_000,mono+30_000))
            assertEquals("LAUNCH_OPPORTUNITY_EXPIRED_9043",
                Sniper.dispatchRefusal9043("attempt-$id",wall+30_001,mono+30_001))
            Sniper.noteBuyConfirmed8749("mint-$id",wall+20_000,"attempt-$id","sig-$id",mono+20_000)
        }
        assertEquals(20_000L,Sniper.requiredDelayMs8749())
        assertTrue(Sniper.clockLine9025(wall+30_000,mono+30_000).contains("launch=3 independent=0"))
    }

    @Test fun directSellAndStreamCallbacksReachTheRepairedBoundaries() {
        fun source(name:String)=File("src/main/kotlin/com/lifecyclebot/engine/$name.kt").readText()
        val door=source("Executor").substringAfter("private fun freshExitReasonOrVeto7948(")
            .substringBefore("private fun selectedPositionMatches8521")
        assertTrue(door.contains("StopMarkConfirm8418.THIN_POOL_BACKSTOP_8996"))
        assertTrue(door.indexOf("stopRefutedByVenue8489") in 0 until door.indexOf("stopCorroboratedByQuote7928"))
        val admission=source("truth/CandidateAdmission8136").substringAfter("fireDependent =").substringBefore("})")
        assertTrue(admission.contains("candidate.marketCell8723?.kind == LaunchSniper8748.KIND_8748"))
        val bot=source("BotService")
        val callbacks=bot.substringAfter("EvidenceWait8948.direct8948 =").substringBefore("// 1) PumpPortal WS")
        assertFalse(callbacks.contains("ConfigStore.load(applicationContext)"))
        val worker=bot.substringAfter("private fun fastLaneEvaluate7277(").substringBefore("private fun sealedEntryScore7688")
        assertTrue(worker.contains("processTokenCycleTraced8869(mint, cycleCfg8779(cfg)"))
        assertTrue(bot.substringAfter("private fun cycleCfg8779(").substringBefore("// V5.0.8942").contains("RuntimeModeAuthority.isPaper()"))
    }
}
