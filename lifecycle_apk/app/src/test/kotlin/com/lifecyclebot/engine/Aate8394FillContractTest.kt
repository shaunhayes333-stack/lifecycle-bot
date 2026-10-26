package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.*
import com.lifecyclebot.engine.cortex.*
import org.junit.Assert.*
import org.junit.Test

class Aate8394FillContractTest {
    private val scalp = RunnerRungBook8153.LADDER_SCALP_EXIT_8394
    @Test fun rejectedAndUnappliedScalpRequestsRetryWithoutAdvancingConfirmedTier() {
        val b=RunnerRungBook8153();val k="mint|1000"
        assertEquals(1,RunnerLive8153.rungOf8153("SCALP_TAKE_PROFIT_8351_T1"))
        assertTrue(b.request8153(k,scalp,1,"SCALP_TAKE_PROFIT_8351_T1",1000))
        b.revert8153(k,RunnerRungBook8153.Revert8153.SUPERSEDED,1001)
        assertEquals(0,b.confirmedTier8153(k,scalp))
        assertTrue(b.request8153(k,scalp,1,"SCALP_TAKE_PROFIT_8351_T1",1002))
        assertTrue(b.advance8153(k,RunnerRungBook8153.Stage8153.TICKET_ACCEPTED,1003))
        assertFalse(b.request8153(k,scalp,2,"SCALP_TAKE_PROFIT_8351_T2",1004))
        b.revert8153(k,RunnerRungBook8153.Revert8153.REJECTED,1005)
        assertTrue(b.request8153(k,scalp,1,"SCALP_TAKE_PROFIT_8351_T1",3000))
        assertTrue(b.confirm8153(k,scalp,1,"confirmed-tx",0.6,0.5))
        assertFalse(b.confirm8153(k,scalp,1,"confirmed-tx",0.6,0.5))
        assertEquals(0.6,b.bankedProceedsSol8153(k),0.0)
        assertFalse(b.request8153(k,scalp,1,"SCALP_TAKE_PROFIT_8351_T1",4000))
        val restored=RunnerRungBook8153();restored.restore8153(b.encode8153())
        assertEquals(1,restored.confirmedTier8153(k,scalp))
        assertEquals(0,restored.confirmedTier8153(k,RunnerRungBook8153.LADDER_SCALP_8153))
        assertFalse(restored.confirm8153(k,scalp,1,"confirmed-tx",0.6,0.5))
        assertEquals(0.6,restored.bankedProceedsSol8153(k),0.0)
    }
    @Test fun receiptForAnotherLadderCannotConsumePendingScalpTier() {
        val b=RunnerRungBook8153();val k="mint|2000"
        assertTrue(b.request8153(k,scalp,1,"SCALP_TAKE_PROFIT_8351_T1",2000))
        assertTrue(b.confirm8153(k,RunnerRungBook8153.LADDER_RUNNER_8153,1,"other-ladder-fill",0.2,0.1))
        assertEquals(0,b.confirmedTier8153(k,scalp));assertNotNull(b.pending8153(k))
    }
    private fun ticket(): Any {
        val rec=ShadowPersist8355.TicketRec8355("test8394", "TEST", "MOONSHOT", "BASE", "BASE", "HIGH", "SMALL",
            100.0,1000,9.28,13.92,50.0,true,true,100.0,1000,
            listOf(ShadowPersist8355.LegRec8355("NOW","SCALP",true,false,100.0,1000,1.0,0.0,0,0.0,0.0)),
            entryPullbackPct8394=4.0,contractVersion8394=8394)
        val method=ExploreShadow8347::class.java.declaredMethods.single { it.name=="ticketFromRec8355" }.apply { isAccessible=true }
        return method.invoke(ExploreShadow8347,rec)!!
    }
    private fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply { isAccessible=true }
    private fun rec(t:Any):ShadowPersist8355.TicketRec8355 = ExploreShadow8347::class.java.declaredMethods.single { it.name=="ticketRec8355" }
        .apply { isAccessible=true }.invoke(ExploreShadow8347,t) as ShadowPersist8355.TicketRec8355
    private fun step(t:Any,px:Double,now:Long,observed:Long) {
        field(t,"lastPx").setDouble(t,px)
        val leg=(field(t,"legs").get(t) as List<*>).single()
        ExploreShadow8347::class.java.declaredMethods.single { it.name=="step8347" }.apply { isAccessible=true }
            .invoke(ExploreShadow8347,t,leg,px,false,now,doubleArrayOf(0.0,0.0),observed)
    }
    @Test fun actualScalpShadowWaitsForObservedFillAndDoesNotBankTheTriggerSpike() {
        val t=ticket();step(t,135.0,100000,100000)
        var l=rec(t).legs.single()
        assertEquals(1.0,l.held,0.0);assertEquals(0.0,l.banked,0.0);assertEquals(0,l.tiers)
        assertEquals("SCALP_TAKE_PROFIT_8351_T1",l.exitWhy8366)
        step(t,135.0,200000,100000) // rereading trigger print is not a fill
        assertEquals(1.0,rec(t).legs.single().held,0.0)
        step(t,95.0,300000,300000)
        l=rec(t).legs.single();assertEquals(0.5,l.held,1e-9);assertTrue(l.banked<0);assertEquals(1,l.tiers)
    }
    @Test fun sustainedProfitThroughFreshExecutionObservationBanksOnlyConfirmedSlice() {
        val t=ticket();step(t,135.0,100000,100000);step(t,135.0,300000,300000)
        val l=rec(t).legs.single();assertEquals(0.5,l.held,1e-9);assertTrue(l.banked>0);assertEquals(1,l.tiers)
        assertEquals("",l.exitWhy8366)
        val restored=ShadowPersist8355.decodeTicket8355(ShadowPersist8355.encodeTicket8355(rec(t)))!!
        assertEquals(l,restored.legs.single());assertEquals(4.0,restored.entryPullbackPct8394,0.0)
    }
    @Test fun pendingSliceSurvivesRestartWithoutConsumingItsTier() {
        val t=ticket();step(t,135.0,100000,100000)
        val r=ShadowPersist8355.decodeTicket8355(ShadowPersist8355.encodeTicket8355(rec(t)))!!
        assertEquals(0,r.legs.single().tiers);assertEquals(0.5,r.legs.single().sellFraction8394,0.0)
        assertEquals(1,r.legs.single().sellTier8394);assertEquals(100000L,r.legs.single().exitAt8366)
    }
    @Test fun productionGradeRowsRespectTerminalMaturityAndPersistIt() {
        val b=CortexGradeBook8144();val at=100000L
        val terminal=CortexGradeRecord8144('D',"mint|MOONSHOT|$at","mint|MOONSHOT",at,"MOONSHOT","BASE","TREND","EARLY","LOW","SMALL",2,20.0,true,true,false)
        terminal.outcomeKind8394="TERMINAL_RUNNER";terminal.outcomeDueAtMs8394=at+6*3600000L
        b.addDecision(terminal)
        var row=b.scoredRows8366("MOONSHOT",true).single()
        assertEquals(at+6*3600000L,row.dueAtMs8392)
        assertTrue(CortexAuthority8366.cohortCovered8392(listOf(row),2,at+30*60000L))
        assertFalse(CortexAuthority8366.cohortCovered8392(listOf(row),2,at+7*3600000L))
        b.label(terminal.id,12.0,20.0,12.0,true,at+20*60000L)
        row=b.scoredRows8366("MOONSHOT",true).single()
        assertTrue(row.netPct.isFinite());assertTrue(row.outcomeAvailableAtMs<row.dueAtMs8392)
        val restored=CortexGradeBook8144();restored.decode(b.encode())
        assertEquals(terminal.outcomeDueAtMs8394,restored.scoredRows8366("MOONSHOT",true).single().dueAtMs8392)
        assertEquals("TERMINAL_RUNNER",restored.decision(terminal.id)!!.outcomeKind8394)
    }
    @Test fun oldSimulationContractsAndPreResetOpenTimesCannotBecomeCurrentProof() {
        assertFalse(ShadowPersist8355.proofEligible8394(null,1000.0,0))
        assertFalse(ShadowPersist8355.proofEligible8394(8394.0,1000.0,2000))
        assertTrue(ShadowPersist8355.proofEligible8394(8394.0,System.currentTimeMillis().toDouble(),0))
    }

    @Test fun entryDepthIsIndependentOfWidenedExitStopAndSharedByBothPaths() {
        val depth=ExploreShadow8347.pullbackPct8347(5.0)
        assertEquals(4.0,depth,0.0)
        for(px in listOf(100.0,95.0,93.0)) {
            val ready=ExploreShadow8347.entryReady8394(ExploreShadow8347.Entry8347.PULLBACK,px,100.0,60000,depth)
            assertEquals(px<=96.0,ready)
        }
        val w=ShadowPersist8355.WaitRec8355("mint|MOONSHOT",1000,100.0,depth,8394)
        assertEquals(listOf(w),ShadowPersist8355.decodeWaits8355(ShadowPersist8355.encodeWaits8355(listOf(w))))
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun actualLiveScalpOfferRetriesAndOnlyConsumedReceiptAdvances() {
        val now=System.currentTimeMillis()
        val ts=com.lifecyclebot.data.TokenState(mint="scalp8394-${System.nanoTime()}",position=com.lifecyclebot.data.Position(
            qtyToken=1000.0,entryPrice=100.0,entryTime=now-60000,tradingMode="MOONSHOT",isPaperPosition=false))
        val key="${ts.mint}|${ts.position.entryTime}"
        val states=ScalpExit8351::class.java.getDeclaredField("states").apply { isAccessible=true }.get(ScalpExit8351) as MutableMap<String,Any>
        val state=ScalpExit8351::class.java.declaredClasses.single { it.simpleName=="State8351" }.declaredConstructors
            .first { it.parameterCount==5 }.apply { isAccessible=true }.newInstance(true,9.28,13.92,0.0,0.0)
        states[key]=state
        val offered=ArrayList<String>()
        val book=RunnerLive8153::class.java.getDeclaredField("book").apply { isAccessible=true }.get(RunnerLive8153) as RunnerRungBook8153
        try {
            ScalpExit8351.offer8351(ts,135.0,now) { _,_,reason -> offered+=reason }
            assertEquals(listOf("SCALP_TAKE_PROFIT_8351_T1"),offered)
            ScalpExit8351.offer8351(ts,135.0,now+1) { _,_,reason -> offered+=reason }
            assertEquals(1,offered.size) // no irreversible T2 while T1 pending
            SpikeCapture7943.restoreTier8144(ts)
            ScalpExit8351.offer8351(ts,135.0,now+2) { _,_,reason -> offered+=reason }
            assertEquals(2,offered.size)
            SpikeCapture7943.dispatched8153(ts,offered.last())
            SpikeCapture7943.rearm(ts,1,offered.last())
            ScalpExit8351.offer8351(ts,135.0,now+3000) { _,_,reason -> offered+=reason }
            assertEquals(3,offered.size)
            SpikeCapture7943.onConfirmedPartial8081(ts,offered.last(),java.math.BigInteger.ONE)
            assertEquals(1,book.confirmedTier8153(key,scalp))
            ScalpExit8351.offer8351(ts,125.0,now+4000) { _,_,reason -> offered+=reason }
            assertEquals(3,offered.size) // no duplicate T1 at another qualifying mark
            val recovered=RunnerRungBook8153();recovered.restore8153(book.encode8153())
            assertEquals(1,recovered.confirmedTier8153(key,scalp))
        } finally {
            states.remove(key)
            (RunnerRungBook8153::class.java.getDeclaredField("book").apply { isAccessible=true }.get(book) as MutableMap<String,Any>).remove(key)
        }
    }
    @Test fun actualEvidenceShadowUsesFreshFillObservationAndRetainsPendingSale() {
        val cls=EvidenceShadow8344::class.java.declaredClasses.single { it.simpleName=="Pos8344" }
        val p=cls.declaredConstructors.first { it.parameterCount==10 }.apply { isAccessible=true }
            .newInstance("evidence8394","MOONSHOT",100.0,1000L,9.28,13.92,50.0,21600000L,false,8394)
        val why=EvidenceShadow8344::class.java.declaredMethods.single { it.name=="why8366" }.apply { isAccessible=true }
        field(p,"lastPxAtMs").setLong(p,100000)
        why.invoke(EvidenceShadow8344,p,45.0,145.0,100000L)
        assertEquals(1.0,field(p,"held").getDouble(p),0.0)
        assertEquals(0,field(p,"tiersHit").getInt(p))
        why.invoke(EvidenceShadow8344,p,45.0,145.0,200000L)
        assertEquals(1.0,field(p,"held").getDouble(p),0.0) // unchanged cached observation
        field(p,"lastPxAtMs").setLong(p,300000)
        why.invoke(EvidenceShadow8344,p,-5.0,95.0,300000L)
        assertEquals(0.4,field(p,"held").getDouble(p),1e-9)
        assertTrue(field(p,"bankedGrossPct").getDouble(p)<0)
        assertEquals(1,field(p,"tiersHit").getInt(p))
    }
}
