package com.lifecyclebot.engine.truth

import kotlin.math.*
import java.util.Random

/** Compact nonlinear challenger. Two hidden layers, asset-balanced chronology and held-out metrics. */
internal object NativeZoneNet9058 {
    private const val H1=16;private const val H2=8
    private fun squash(x:Double)=if(x.isFinite())sign(x)*ln1p(abs(x).coerceAtMost(1e12)) else Double.NaN
    private fun sigmoid(z:Double)=1.0/(1.0+exp(-z.coerceIn(-30.0,30.0)))
    private class Norm(val mean:DoubleArray,val sd:DoubleArray){
        fun vector(x:DoubleArray)=DoubleArray(mean.size*2){i->
            val z=squash(x.getOrElse(i/2){Double.NaN})
            if(i%2==1)if(z.isFinite())0.0 else 1.0 else if(z.isFinite())((z-mean[i/2])/sd[i/2]).coerceIn(-6.0,6.0) else 0.0
        }
    }
    class Model internal constructor(private val mean:DoubleArray,private val sd:DoubleArray,private val w:DoubleArray,private val shift:Double){
        fun predict(x:DoubleArray):Double {
            val v=Norm(mean,sd).vector(x);val a=DoubleArray(H1);val b=DoubleArray(H2)
            return sigmoid(forward(v,w,a,b)+shift)
        }
    }
    data class Metrics(val assets:Int,val windows:Int,val brier:Double,val averagePrecision:Double,val baseRate:Double)
    data class Fit(val neural:Model,val linear:RunnerBarrierLearning9051.Model,val metrics:Metrics,val baseline:RunnerBarrierLearning9051.Metrics)
    private fun forward(x:DoubleArray,w:DoubleArray,a:DoubleArray,b:DoubleArray):Double{
        val n=x.size;val o2=H1*(n+1);val o3=o2+H2*(H1+1)
        for(j in 0 until H1){val o=j*(n+1);var z=w[o+n];for(k in 0 until n)z+=w[o+k]*x[k];a[j]=tanh(z)}
        for(j in 0 until H2){val o=o2+j*(H1+1);var z=w[o+H1];for(k in 0 until H1)z+=w[o+k]*a[k];b[j]=tanh(z)}
        var z=w[o3+H2];for(k in 0 until H2)z+=w[o3+k]*b[k];return z
    }
    /** Same evidence and independent support as the existing linear comparator. No test-derived tuning. */
    fun fit(split:RunnerBarrierLearning9051.Split,head:Int,yield:()->Unit={}):Fit?{
        val baseline=RunnerBarrierLearning9051.fit(split,head,yield)?:return null
        val tr=RunnerBarrierLearning9051.windows9055(split.train,head)
        val va=RunnerBarrierLearning9051.windows9055(split.valid,head)
        val te=RunnerBarrierLearning9051.windows9055(split.test,head)
        val width=tr.first().sample.features.size;if(width !in 1..64)return null
        val mean=DoubleArray(width);val sd=DoubleArray(width)
        for(k in 0 until width){var n=0.0;var s=0.0;var ss=0.0
            for(r in tr){val z=squash(r.sample.features[k]);if(z.isFinite()){n+=r.weight;s+=r.weight*z;ss+=r.weight*z*z}}
            if(n>0){mean[k]=s/n;sd[k]=sqrt((ss/n-mean[k]*mean[k]).coerceAtLeast(0.0)).coerceAtLeast(.25)}else sd[k]=1.0
        }
        val norm=Norm(mean,sd);val xs=tr.map{norm.vector(it.sample.features)};val n=2*width
        val o2=H1*(n+1);val o3=o2+H2*(H1+1);val w=DoubleArray(o3+H2+1);val rng=Random(9058L+head)
        fun init(start:Int,units:Int,fanIn:Int){val scale=sqrt(6.0/(fanIn+units));for(j in 0 until units)for(k in 0 until fanIn)w[start+j*(fanIn+1)+k]=(2*rng.nextDouble()-1)*scale}
        init(0,H1,n);init(o2,H2,H1);init(o3,1,H2)
        val total=tr.sumOf{it.weight};val prior=tr.sumOf{it.weight*it.sample.labels[head]}/total
        w[o3+H2]=ln(prior/(1-prior))
        val a=DoubleArray(H1);val b=DoubleArray(H2);val db=DoubleArray(H2);val da=DoubleArray(H1)
        val velocity=DoubleArray(w.size)
        repeat(64){
            yield();val grad=DoubleArray(w.size)
            for(i in tr.indices){val x=xs[i];val e=(sigmoid(forward(x,w,a,b))-tr[i].sample.labels[head])*tr[i].weight/total
                grad[o3+H2]+=e
                for(j in 0 until H2){grad[o3+j]+=e*b[j];db[j]=e*w[o3+j]*(1-b[j]*b[j])}
                for(k in 0 until H1){var z=0.0;for(j in 0 until H2)z+=db[j]*w[o2+j*(H1+1)+k];da[k]=z*(1-a[k]*a[k])}
                for(j in 0 until H2){val o=o2+j*(H1+1);grad[o+H1]+=db[j];for(k in 0 until H1)grad[o+k]+=db[j]*a[k]}
                for(j in 0 until H1){val o=j*(n+1);grad[o+n]+=da[j];for(k in 0 until n)grad[o+k]+=da[j]*x[k]}
            }
            for(k in w.indices){velocity[k]=.8*velocity[k]+grad[k]+.001*w[k];w[k]-=.12*velocity[k]}
        }
        val vz=va.map{forward(norm.vector(it.sample.features),w,a,b)};var shift=0.0
        repeat(8){var g=.1*shift;var h=.1;for(i in va.indices){val p=sigmoid(vz[i]+shift);g+=va[i].weight*(p-va[i].sample.labels[head]);h+=va[i].weight*p*(1-p)};shift=(shift-g/h).coerceIn(-4.0,4.0)}
        val model=Model(mean,sd,w.copyOf(),shift)
        data class Score(val p:Double,val y:Int,val weight:Double)
        val scores=te.map{Score(model.predict(it.sample.features),it.sample.labels[head],it.weight)}.sortedByDescending{it.p}
        val mass=scores.sumOf{it.weight};val pos=scores.sumOf{it.weight*it.y};var ap=0.0;var tp=0.0;var seen=0.0;var i=0
        while(i<scores.size){var j=i;var hit=0.0;while(j<scores.size&&scores[j].p==scores[i].p){hit+=scores[j].weight*scores[j].y;seen+=scores[j].weight;j++};tp+=hit;ap+=hit/pos*tp/seen;i=j}
        val brier=scores.sumOf{val e=it.p-it.y;it.weight*e*e}/mass
        return Fit(model,baseline.model,Metrics(te.map{it.sample.asset}.toSet().size,te.size,brier,ap,pos/mass),baseline.metrics)
    }
}
