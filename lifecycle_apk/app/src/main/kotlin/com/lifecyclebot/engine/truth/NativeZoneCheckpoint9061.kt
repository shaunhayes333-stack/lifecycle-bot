package com.lifecyclebot.engine.truth

/** Coalesces small journal saves on the existing lab worker; no training or new executor. */
internal class NativeZoneCheckpoint9061(private val intervalMs:Long=30_000L) {
    private var busy=false
    private var last:Long?=null
    @Synchronized fun acquire(now:Long):Boolean {
        if(now<0L||busy)return false
        val prior=last
        if(prior!=null && now>=prior && now-prior<intervalMs)return false
        busy=true;last=now;return true
    }
    @Synchronized fun release(){busy=false}
    companion object {
        fun limit(pressure:String):Int=when(pressure.uppercase()){
            "CRITICAL"->8
            "HARD"->32
            else->128
        }
        fun compact(pressure:String)=pressure.equals("NORMAL",true)
    }
}
