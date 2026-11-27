package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.NativeZoneMarket9061
import com.lifecyclebot.engine.truth.NativeZoneContract9059
import com.lifecyclebot.engine.truth.NativeZoneSequence9058
import com.lifecyclebot.engine.truth.NativeZoneNet9058
import com.lifecyclebot.engine.truth.RunnerBarrierLearning9051
import com.lifecyclebot.engine.truth.LanePocketContext9061
import org.junit.Assert.*
import org.junit.Test

class Aate9061LanePopulationAndCompoundTest {
    private val population=NativeZoneMarket9061.Snapshot(20_000.0,5_000.0,1.0,1.0,
        3_000.0,500_000.0,.25,3.0,2_000.0,"CURVE_LAUNCH")

    @Test fun laterNativeLanesAreNotExcludedByTheEightWindowTokenCap() {
        val z=NativeZoneSequence9058()
        val at=100_000L
        fun offer(lane:String,t:Long):Boolean {
            val scope=NativeZoneSequence9058.Scope(lane,"BASE","SOLANA_POOL","LIVE","LONG")
            val mark=NativeZoneSequence9058.Mark("asset","pool",6,t,t,1.0,"TEST")
            return z.reserve("asset",lane,t) && z.enroll(NativeZoneSequence9058.Decision(scope,mark,t,1000L,10.0,2.0,DoubleArray(12),false))
        }
        // Twelve meme menus and eight native desk identities can all observe the same token.
        for(i in 0 until 20)assertTrue("lane $i must receive its own observation",offer("LANE_$i",at+i))
        assertTrue(offer("LANE_0",at+15_000L))
        assertTrue(offer("LANE_0",at+30_000L))
        assertFalse("A fourth overlapping window cannot monopolise this token",offer("LANE_0",at+45_000L))
        assertTrue(offer("LATER_DESK",at+45_001L))
    }

    @Test fun receivingDeskOwnsCapAndAgePopulation() {
        val launch=NativeZoneMarket9061.vector(population)
        val established=NativeZoneMarket9061.vector(population.copy(capMin=1_000_000.0,capMax=Double.NaN,
            ageMin=120.0,ageMax=Double.NaN,liquidityMin=50_000.0))
        assertEquals(1.0,launch[4],0.0);assertEquals(1.0,launch[5],0.0);assertEquals(1.0,launch[6],0.0)
        assertEquals(0.0,established[4],0.0);assertEquals(0.0,established[5],0.0);assertEquals(0.0,established[6],0.0)
        assertEquals(1.0,launch[8],0.0)
        assertEquals(1.0,NativeZoneMarket9061.vector(population.copy(tokenKind="REGISTERED_BLUECHIP"))[9],0.0)
    }

    @Test fun firstSeenLowerBoundDoesNotBecomeYoungLaunchAge() {
        val bound=population.copy(exactAgeMinutes=Double.NaN,minimumAgeMinutes=1.0)
        assertTrue(NativeZoneMarket9061.ageFit(bound).isNaN())
        assertEquals(0.0,NativeZoneMarket9061.ageFit(bound.copy(minimumAgeMinutes=10.0)),0.0)
        assertEquals(1.0,NativeZoneMarket9061.ageFit(bound.copy(minimumAgeMinutes=180.0,ageMin=120.0,ageMax=Double.NaN)),0.0)
        assertTrue(NativeZoneMarket9061.vector(population.copy(capUsd=Double.NaN))[4].isNaN())
        assertTrue(NativeZoneMarket9061.vector(null).all{it.isNaN()})
        assertFalse(population.copy(capUsd=Double.POSITIVE_INFINITY).valid())
        assertFalse(population.copy(exactAgeMinutes=1.0,minimumAgeMinutes=2.0).valid())
    }

    @Test fun causalSequenceKeepsOriginalPopulationForLearningAndPrediction() {
        val at=100_000L
        val scope=NativeZoneSequence9058.Scope("PROJECT_SNIPER","LAUNCH","SOLANA_CURVE","PUMP","LONG")
        val z=NativeZoneSequence9058()
        val pocket=LanePocketContext9061.Snapshot(scope.lane,scope.setup,at,at-1000,true,"age=<5m",3_600_000L,40,20.0,3.0,.2,true)
        fun mark(t:Long,px:Double)=NativeZoneSequence9058.Mark("asset","pump.curve",6,t,t,px,"TEST")
        assertTrue(z.reserve("asset",scope.lane,at))
        assertTrue(z.enroll(NativeZoneSequence9058.Decision(scope,mark(at,1.0),at,1_000,10.0,2.0,
            DoubleArray(12),true,market9061=population,pocket9061=pocket)))
        z.mark(mark(at+1000,1.0),at+1000);z.mark(mark(at+2000,.8),at+2000)
        val row=z.drain().single()
        assertEquals(population,row.market9061)
        assertEquals(pocket,row.pocket9061)
        val input=NativeZoneContract9059.vector(row.features,scope,null,null,at,row.market9061,row.pocket9061)
        assertEquals(73,input.size)
        assertEquals(1.0,input[52+4],0.0)
        assertEquals(1.0,input[64],0.0)
    }

    @Test fun pocketEvidenceKeepsItsSetupClockAndHoldingHorizon() {
        val at=100_000L
        val scope=NativeZoneSequence9058.Scope("CORE","BASE","SOLANA_POOL","PAPER","LONG")
        val s=LanePocketContext9061.Snapshot(scope.lane,scope.setup,at,at-1000,true,"age=<5m",14_400_000L,40,20.0,3.0,.2,false)
        val objective=NativeZoneContract9059.Objective(10.0,900_000L,"LANE")
        val x=LanePocketContext9061.vector(s,scope,at,objective)
        assertEquals(17.0,x[3],0.0)
        assertEquals(16.0,x[6],0.0) // four-hour evidence is not a fifteen-minute outcome
        assertEquals(0.0,x[7],0.0) // endpoint research is not managed-exit evidence
        assertTrue(LanePocketContext9061.vector(s,scope.copy(setup="BREAKOUT"),at,objective).all{it.isNaN()})
        assertTrue(LanePocketContext9061.vector(s,scope.copy(lane="BLUECHIP"),at,objective).all{it.isNaN()})
        assertFalse(s.copy(published=at+1).valid(scope.lane,scope.setup,at))
        val later=at+LanePocketContext9061.TTL+1
        assertFalse(s.copy(at=later).valid(scope.lane,scope.setup,later))
        assertEquals(s,LanePocketContext9061.read(LanePocketContext9061.json(s),scope.lane,scope.setup,at))
        assertNull(LanePocketContext9061.read(LanePocketContext9061.json(s).put("basis","LIVE_FILLS"),scope.lane,scope.setup,at))
    }

    @Test fun missingPocketIsNotTheSameAsAnObservedNoMatch() {
        val at=100_000L
        val scope=NativeZoneSequence9058.Scope("CORE","BASE","SOLANA_POOL","LIVE","LONG")
        val no=LanePocketContext9061.Snapshot(scope.lane,scope.setup,at,at-1,false)
        assertTrue(no.valid(scope.lane,scope.setup,at))
        assertEquals(no,LanePocketContext9061.read(LanePocketContext9061.json(no),scope.lane,scope.setup,at))
        assertEquals(0.0,LanePocketContext9061.vector(no,scope,at,null)[0],0.0)
        assertTrue(LanePocketContext9061.vector(null,scope,at,null).all{it.isNaN()})
        assertFalse(no.copy(published=at+1).valid(scope.lane,scope.setup,at))
    }

    @Test fun fiveDollarRingContinuesPastOneMillionWithoutReset() {
        val c=CyclicCompound9061
        assertEquals(5.0,c.DEFAULT_SEED_USD,0.0)
        val reached=c.milestones(5.0,5.0,0,1_310_720.0)!!
        assertEquals(18,reached.tier);assertEquals(1_310_720.0,reached.reachedUsd,0.0)
        assertEquals(2_621_440.0,reached.nextUsd!!,0.0)
        assertTrue(c.validBalances(10_000.0,2_000_000.0))
        assertTrue(c.validBalances(0.0,5.0)) // SOL rate not yet known
        assertEquals(500.0,c.legacySeed(4_000.0,3)!!,0.0)
        val loss=c.milestones(5.0,40.0,3,12.0)!!
        assertEquals(40.0,loss.reachedUsd,0.0) // historical achievement, not invented current equity
        assertEquals(3,loss.tier)
        assertNull(c.milestones(5.0,40.0,2,50.0))
    }

    @Test fun settlementUsesNetReceiptAndNeverRefillsADepletedRing() {
        val c=CyclicCompound9061
        assertEquals(.008,c.afterSettlement(.01,-.002)!!,1e-12)
        assertEquals(0.0,c.afterSettlement(.01,-.01)!!,0.0)
        assertTrue(c.afterSettlement(.01,-.012)!!<0.0) // fee debt is not clamped to free capital
        assertNull(c.afterSettlement(.01,Double.NaN))
        assertFalse(c.validBalances(-.002,-.2))
        assertTrue(c.fitsAllocation(.01,.01))
        assertFalse(c.fitsAllocation(.011,.01))
        assertFalse(c.fitsAllocation(.001,0.0))
        assertFalse(c.fitsAllocation(Double.NaN,.01))
    }

    @Test fun fullPopulationVectorCanActuallyTrainAndPublish() {
        val c=RunnerBarrierLearning9051
        val rows=(0 until 720).map { i ->
            val at=100_000L+i*2*c.HOUR
            val yes=if(i%2==0)1 else 0
            val features=DoubleArray(73){Double.NaN}
            features[52]=if(yes==1)10.0 else 4.0
            features[56]=yes.toDouble()
            RunnerBarrierLearning9051.Sample("asset$i",at,at+c.HOUR,features,IntArray(c.HEADS){yes})
        }
        val fit=NativeZoneNet9058.fit(c.split(rows),0)
        assertNotNull("The complete market population input must fit both learner width bounds",fit)
        assertTrue(fit!!.neural.predict(rows.first().features).isFinite())
    }
}
