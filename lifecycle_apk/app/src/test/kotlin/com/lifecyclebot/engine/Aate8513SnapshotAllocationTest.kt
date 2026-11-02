package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class Aate8513SnapshotAllocationTest {
    private class CountedIds : AbstractList<String>() {
        var reads=0
        override val size=1
        override fun get(index:Int):String { require(index==0); reads++; return "TEST_8513" }
    }
    @Suppress("UNCHECKED_CAST")
    @Test fun overlappingPendingOwnersDetachOneFrozenEpisodeWithoutRepeatedGraphs() {
        val owner=Cortex7885
        fun field(n:String)=owner.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(owner)
        val pending=field("pending") as MutableMap<String,Any>
        val paired=field("pairedPending8401") as MutableMap<String,Any>
        val superseded=field("superseded8443") as MutableMap<String,Any>
        val at=System.currentTimeMillis(); val key="snapshot8513|QUALITY"; val id="$key|$at"
        val ids=CountedIds(); val raws=doubleArrayOf(42.0)
        val a=Cortex7885.Assessment("QUALITY",false,raws,ids,listOf(doubleArrayOf(0.0)),"TEST",
            CortexLedger7885.Fused(4.0,0.0,0.0,0.0,0.0,0.0,0.0,emptyList()),4.0,
            CortexScoreboard7885.Bucket.STRONG,0,false,at)
        val ctor=Class.forName("${owner.javaClass.name}\$Pending").declaredConstructors.single{it.parameterCount==7}.apply{isAccessible=true}
        val p=ctor.newInstance(a,false,at,null,"TEST","",emptyList<String>())
        try {
            synchronized(owner) {
                pending[key]=p; paired[id]=p; superseded[id]=p
                val method=owner.javaClass.declaredMethods.single{it.name=="pendingSnapshot8076" && it.parameterCount==0}.apply{isAccessible=true}
                val encode=method.invoke(owner) as ()->String
                assertEquals("deduplicate before copying voter graphs under the Cortex monitor",1,ids.reads)
                raws[0]=999.0
                val rows=JSONObject(encode()).getJSONArray("p")
                val mine=(0 until rows.length()).map{rows.getJSONObject(it)}.filter{it.optString("k")==key}
                assertEquals(1,mine.size)
                assertEquals(42.0,mine.single().getJSONArray("x").getDouble(0),0.0)
                assertEquals(at,mine.single().getLong("at"))
                assertTrue(mine.single().getBoolean("paired8417"))
            }
        } finally { synchronized(owner){pending.remove(key);paired.remove(id);superseded.remove(id)} }
    }
}
