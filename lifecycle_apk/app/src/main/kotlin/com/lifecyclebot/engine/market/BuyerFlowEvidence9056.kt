package com.lifecyclebot.engine.market

/** Research-only coverage of delivered swaps, not proof of complete market coverage. */
object BuyerFlowEvidence9056 {
    data class Snapshot(val cutoffMs: Long, val lastEventMs: Long, val receiptMs: Long,
                        val n60: Int, val buy60: Int, val id60: Int, val buy15: Int, val id15: Int,
                        val signed60: Int, val spanMs: Long, val buy5: Int,
                        val net5: Double, val net15: Double, val netPrev15: Double,
                        val undated60: Int = 0, val undatedBuy60: Int = 0, val publicationClock9058: Boolean = false) {
        fun validFor(decision: Long, asOf: Long): Boolean = decision > 0L && cutoffMs == decision &&
            lastEventMs == asOf && asOf > 0L && asOf <= receiptMs && receiptMs <= decision &&
            decision-asOf in 0L..20_000L && n60 in 1..256 && buy60 in 0..n60 && id60 in 0..buy60 &&
            buy15 in 0..buy60 && id15 in 0..buy15 && id15 <= id60 && id60-id15 <= buy60-buy15 &&
            signed60 in 0..n60 && spanMs in (decision-asOf)..60_000L && buy5 in 0..buy15 &&
            net5.isFinite() && net15.isFinite() && netPrev15.isFinite() &&
            undated60 in 0..(256-n60) && undatedBuy60 in 0..undated60
        // This is identity coverage among delivered buys, not completeness of the exchange feed.
        val identities60: Boolean get() = id60 == buy60 && undatedBuy60 == 0
        val identities15: Boolean get() = id15 == buy15 && undatedBuy60 == 0
        val coverage60: Double get() = if (buy60+undatedBuy60 > 0) id60.toDouble()/(buy60+undatedBuy60) else Double.NaN
        val coverage15: Double get() = if (undatedBuy60==0 && buy15>0) id15.toDouble()/buy15 else Double.NaN
    }

    internal class Builder(private val cutoffMs: Long) {
        private var n=0; private var b=0; private var ids=0; private var b15=0; private var ids15=0
        private var signed=0; private var b5=0; private var oldest=Long.MAX_VALUE; private var newest=0L
        private var net5=0.0; private var net15=0.0; private var netPrev15=0.0
        fun add(atMs: Long, buy: Boolean, knownWallet: Boolean, signedSwap: Boolean, sol: Double) {
            if (cutoffMs <= 0L || atMs <= 0L || atMs > cutoffMs || cutoffMs-atMs > 60_000L || !sol.isFinite() || sol <= 0.0) return
            val age=cutoffMs-atMs; val net=if(buy)sol else -sol
            n++; if(signedSwap)signed++; oldest=minOf(oldest,atMs);newest=maxOf(newest,atMs)
            if(buy){b++;if(knownWallet)ids++}
            if(age<15_000L){net15+=net;if(buy){b15++;if(knownWallet)ids15++}}
            else if(age<30_000L)netPrev15+=net
            if(age<5_000L){net5+=net;if(buy)b5++}
        }
        fun freeze(receiptMs:Long):Snapshot? {
            if(n==0)return null
            val s=Snapshot(cutoffMs,newest,receiptMs,n,b,ids,b15,ids15,signed,cutoffMs-oldest,b5,net5,net15,netPrev15)
            return s.takeIf{it.validFor(cutoffMs,newest)}
        }
    }
}
