package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.RunnerBarrierLearning9051
import com.lifecyclebot.engine.lab.StrategyLab8755
import org.junit.Assert.*
import org.junit.Test

class Aate9051BarrierLearningTest {
    @Test fun targetsAreOrderedAndCensored() {
        val c=RunnerBarrierLearning9051
        val d=100000L;val e=d+10000L
        val hits=longArrayOf(d+20000,d+40000,d+400000,0,0,0)
        val a=c.labels(d,e,10000,d+c.HOUR,10000,400,d+30000,hits)
        assertEquals(1,a[0]);assertEquals(0,a[1]);assertEquals(0,a[2]);assertEquals(1,a[6]);assertEquals(1,a[12]);assertEquals(0,a[17])
        assertTrue(c.labels(d,e,68000,d+c.HOUR,10000,400,0,hits).all{it==-1})
        assertTrue(c.labels(d,e,10000,d+c.HOUR,30000,400,0,hits).all{it==-1})
        assertTrue(c.labels(d,e,10000,d+200000,10000,40,0,longArrayOf(d+20000,0,0,0,0,0)).all{it==-1})
        assertTrue(c.labels(d,e,10000,d+c.HOUR,10000,400,0,longArrayOf(0,d+40000,0,0,0,0)).all{it==-1})
    }
    @Test fun chronologicalGroupedLearningDoesNotUseTestLabelsForFit() {
        val c=RunnerBarrierLearning9051
        val rows=(0 until 600).map { i -> val t=100000L+i*2*c.HOUR;val y=i%2
            RunnerBarrierLearning9051.Sample("m"+i,t,t+c.HOUR,doubleArrayOf(if(y==1) 3.0 else -3.0,Double.NaN),IntArray(c.HEADS){y}) }
        val split=c.split(rows+rows[0].copy(decision=rows.last().decision+c.HOUR,available=rows.last().decision+2*c.HOUR))
        assertEquals(360,split.train.size);assertEquals(120,split.valid.size);assertEquals(120,split.test.size)
        assertTrue(split.train.map{it.asset}.intersect(split.test.map{it.asset}.toSet()).isEmpty())
        val f=c.fit(split,0)!!
        assertTrue(f.metrics.averagePrecision>.99)
        assertTrue(f.metrics.brier<f.metrics.constantBrier)
        assertTrue(f.model.predict(doubleArrayOf(3.0,Double.NaN))>f.model.predict(doubleArrayOf(-3.0,Double.NaN)))
        assertNull(c.fit(split.copy(test=split.test.map{it.copy(labels=IntArray(c.HEADS){-1})}),0))
        val inverted=split.copy(test=split.test.map{it.copy(labels=IntArray(c.HEADS){h->1-it.labels[h]})})
        val other=c.fit(inverted,0)!!
        assertEquals(f.model.predict(doubleArrayOf(3.0,Double.NaN)),other.model.predict(doubleArrayOf(3.0,Double.NaN)),0.0)
        assertTrue(other.metrics.brier>f.metrics.brier)
    }
    @Test fun laterDecisionWindowsLearnDespiteCensoredFirstObservation() {
        val c=RunnerBarrierLearning9051
        val rows=(0 until 600).flatMap { i ->
            val t=100000L+i*3*c.HOUR
            listOf(-1,0,1).mapIndexed { j,y ->
                val d=t+j*15000L
                RunnerBarrierLearning9051.Sample("asset"+i,d,d+c.HOUR,doubleArrayOf(if(y==1)3.0 else -3.0),IntArray(c.HEADS){y})
            }
        }
        val split=c.split(rows)
        assertEquals(1080,split.train.size);assertEquals(360,split.valid.size);assertEquals(360,split.test.size)
        assertTrue(split.train.all{it.available<split.valid.minOf{r->r.decision}})
        assertTrue(split.valid.all{it.available<split.test.minOf{r->r.decision}})
        val fit=c.fit(split,0)!!
        assertEquals(120,fit.metrics.assets);assertEquals(240,fit.metrics.n);assertEquals(120,fit.metrics.positives)
        assertEquals(360,fit.trainN);assertEquals(120,fit.validN)
        assertTrue(fit.model.predict(doubleArrayOf(3.0))>fit.model.predict(doubleArrayOf(-3.0)))
        assertTrue(fit.metrics.averagePrecision>.99);assertTrue(fit.metrics.brier<fit.metrics.constantBrier)
        assertNull(c.fit(split.copy(test=split.test+split.train.first()),0))
    }
    @Test fun repeatedWindowsDoNotCreateIndependentEvidence() {
        val c=RunnerBarrierLearning9051
        val rows=(0 until 200).map { i -> val t=100000L+i*60000L
            RunnerBarrierLearning9051.Sample("spam",t,t+c.HOUR,doubleArrayOf(i.toDouble()),IntArray(c.HEADS){i%2}) }
        val selected=c.windows9055(rows,0)
        assertEquals(8,selected.size);assertEquals(1.0,selected.sumOf{it.weight},1e-12)
        assertEquals(rows.first().decision,selected.first().sample.decision)
        assertEquals(rows.last().decision,selected.last().sample.decision)
        assertNull(c.fit(RunnerBarrierLearning9051.Split(rows,rows.map{it.copy(asset="v")},rows.map{it.copy(asset="t")}),0))
    }
    @Test fun walletlessVolumeRemainsEvidenceWithoutInventingBuyerCounts9056() {
        val flow = com.lifecyclebot.engine.market.BuyerFlow8666
        val now = 1_800_000L
        flow.clearForTest()
        try {
            flow.note("green9056", "alice", 2.0, true, now-20_000L, "a", now-19_000L)
            flow.note("green9056", "", 4.0, true, now-2_000L, "b", now)
            flow.note("green9056", "seller", 1.0, false, now-10_000L, "c", now)
            val frozen = flow.readForLearning9056("green9056", now)!!
            val j = org.json.JSONObject(com.lifecyclebot.engine.truth.ResearchRows8877.flowJson8924(frozen))
            assertTrue(j.isNull("buyers60"));assertTrue(j.isNull("new15"));assertTrue(j.isNull("repeat60"))
            assertEquals(5.0,j.getDouble("netSol60"),1e-9)
            val e=com.lifecyclebot.engine.truth.ResearchRows8877.flowEvidence9056(j,now)!!
            assertEquals(0.5,e.coverage60,1e-9);assertEquals(0.0,e.coverage15,1e-9)
            assertEquals(4.0,e.net5,1e-9);assertEquals(3.0,e.net15,1e-9);assertEquals(2.0,e.netPrev15,1e-9)
            assertEquals(3,e.signed60)
            val precursor=org.json.JSONObject().put("v",1).put("rawPresent",true).put("atMs",now)
                .put("newBuyers15s8666",0).put("flow",j)
            val features=com.lifecyclebot.engine.truth.SharedRunnerLab9051.features(precursor)!!
            assertEquals(46,features.size)
            assertTrue(features[12].isNaN());assertTrue(features[26].isNaN());assertEquals(5.0,features[30],1e-9)
            assertEquals(0.5,features[36],1e-9);assertEquals(4.0,features[43],1e-9)
            val historical=com.lifecyclebot.engine.truth.SharedRunnerLab9051.upgradeLegacy9056(DoubleArray(36){1.0})!!
            assertEquals(46,historical.size);assertEquals(1.0,historical[0],0.0)
            assertTrue(historical[12].isNaN());assertTrue(historical.drop(26).all{it.isNaN()})
            flow.note("green9056", "bob", 4.0, true, now-2_000L, "b", now+1)
            assertNull(flow.readForLearning9056("green9056",now))
            val repaired=flow.readForLearning9056("green9056",now+1)!!
            assertTrue(repaired.evidence9056!!.identities60)
            assertEquals(2,repaired.distinctBuyers60s)
            assertEquals(3.0,repaired.txPerMin,0.0)
            assertTrue(j.isNull("buyers60"))
            assertNull(com.lifecyclebot.engine.truth.ResearchRows8877.flowEvidence9056(j,now+1))
            flow.note("sellOnly9056", "", 1.0, false, now, "s", now)
            val sell=org.json.JSONObject(com.lifecyclebot.engine.truth.ResearchRows8877.flowJson8924(
                flow.readForLearning9056("sellOnly9056",now)))
            assertEquals(0,sell.getInt("buyers60"));assertEquals(-1.0,sell.getDouble("netSol60"),0.0)
        } finally { flow.clearForTest() }
    }

    @Test fun legacyLabSplitEmbargoesLabelsAndKeepsAssetsDisjoint() {
        val h=3_600_000L
        val rows=(0 until 20).map { i -> val t=10L+i*2*h
            StrategyLab8755.Row(t,"CORE","SRC",1.0,doubleArrayOf(1.0),"m"+i,900000L,t+900000L) }
        val repeated=StrategyLab8755.Row(rows.last().t+2*h,"EXPRESS","SRC",2.0,doubleArrayOf(2.0),"m1",300000L,rows.last().t+2*h+300000L)
        val (tr,te)=StrategyLab8755.causalSplit9051(rows+repeated)
        assertTrue(tr.isNotEmpty());assertTrue(te.isNotEmpty())
        assertTrue(tr.map{it.mint}.intersect(te.map{it.mint}.toSet()).isEmpty())
        assertFalse(te.any{it.mint=="m1"})
        assertTrue(tr.all{it.observedAt<te.minOf{r->r.t}})
    }
}
