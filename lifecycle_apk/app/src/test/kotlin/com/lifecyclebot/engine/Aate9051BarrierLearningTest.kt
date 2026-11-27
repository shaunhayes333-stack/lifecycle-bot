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
