package com.lifecyclebot.engine.truth

import org.json.JSONObject

/** The pocket known at decision time. Forward research is never a realised live-profit receipt. */
internal object LanePocketContext9061 {
    const val WIDTH = 9
    const val TTL = 86_400_000L
    data class Snapshot(val lane:String,val setup:String,val at:Long,val published:Long,
                        val matched:Boolean,val cell:String="",val horizon:Long=0L,val n:Int=0,
                        val mean:Double=Double.NaN,val margin:Double=Double.NaN,
                        val runnerRate:Double=Double.NaN,val managed:Boolean=false) {
        fun valid(l:String,s:String,t:Long):Boolean = lane==l && setup==s && at==t &&
            lane.isNotBlank() && setup.isNotBlank() && published>0L && t-published in 0L..TTL &&
            if(matched) cell.isNotBlank() && cell.length<=512 && horizon in 1L..TTL && n>=30 &&
                mean.isFinite() && margin.isFinite() && margin>=0.0 && runnerRate in 0.0..1.0
            else cell.isEmpty() && horizon==0L && n==0 && mean.isNaN() && margin.isNaN() && runnerRate.isNaN() && !managed
    }
    fun vector(s:Snapshot?,scope:NativeZoneSequence9058.Scope,at:Long,objective:NativeZoneContract9059.Objective?):DoubleArray {
        val x=DoubleArray(WIDTH){Double.NaN}
        if(s==null || !s.valid(scope.lane,scope.setup,at))return x
        x[0]=if(s.matched)1.0 else 0.0
        x[8]=(at-s.published)/1000.0
        if(!s.matched)return x
        x[1]=kotlin.math.ln1p(s.n.toDouble());x[2]=s.mean;x[3]=s.mean-s.margin;x[4]=s.runnerRate
        x[5]=s.horizon/60_000.0
        x[6]=objective?.let{s.horizon.toDouble()/it.horizonMs}?:Double.NaN
        x[7]=if(s.managed)1.0 else 0.0
        return x
    }
    fun json(s:Snapshot):JSONObject=JSONObject().put("v",1).put("basis","MIXED_FORWARD_MARKS")
        .put("lane",s.lane).put("setup",s.setup).put("at",s.at).put("published",s.published)
        .put("matched",s.matched).put("cell",s.cell).put("horizon",s.horizon).put("n",s.n)
        .put("mean",if(s.mean.isFinite())s.mean else JSONObject.NULL)
        .put("margin",if(s.margin.isFinite())s.margin else JSONObject.NULL)
        .put("runnerRate",if(s.runnerRate.isFinite())s.runnerRate else JSONObject.NULL).put("managed",s.managed)
    fun read(o:JSONObject,lane:String,setup:String,at:Long):Snapshot? = try {
        if(o.getInt("v")!=1 || o.getString("basis")!="MIXED_FORWARD_MARKS")null else
            Snapshot(o.getString("lane"),o.getString("setup"),o.getLong("at"),o.getLong("published"),
                o.getBoolean("matched"),o.getString("cell"),o.getLong("horizon"),o.getInt("n"),
                o.optDouble("mean",Double.NaN),o.optDouble("margin",Double.NaN),o.optDouble("runnerRate",Double.NaN),o.getBoolean("managed"))
                .takeIf{it.valid(lane,setup,at)}
    }catch(_:Exception){null}
}
