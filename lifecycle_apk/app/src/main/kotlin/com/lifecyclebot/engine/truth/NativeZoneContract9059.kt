package com.lifecyclebot.engine.truth

/** Native identity and economic research target. No order authority. */
object NativeZoneContract9059 {
    private val id = Regex("[A-Z0-9_@.-]{1,64}")
    private val missing = setOf("NONE", "UNKNOWN", "NO_SETUP", "NO_TRIGGER", "ALLOW", "UNCLASSIFIED")
    private val origins = setOf("PB", "OVERLAY", "DESK", "COHORT")
    private val outcomes = setOf("MATCHED", "NO_MATCH", "DATA_INCOMPLETE", "MATCHES_UNRANKABLE", "NO_PLAYBOOK", "CLASSIFIER_ERROR", "NATIVE", "UNOBSERVED")
    private fun named(s:String) = id.matches(s) && s !in missing && !s.startsWith("OBS_")
    const val EXTRA = 7
    const val NATIVE_HEAD = -1

    /** Selected is the original native choice, never a subsequently reconstructed chart tag.
     *  Alternatives are predicates actually observed to fire on the same classifier frame. */
    data class Signal(val origin:String, val at:Long, val selected:String, val matches:List<String>,
                      val outcome:String, val scorePct:Double, val confidencePct:Double) {
        fun valid(decision:Long):Boolean = origin in origins && at>0 && decision-at in 0L..5_000L &&
            (selected.isEmpty()||named(selected)) && matches.size<=128 && matches.distinct().size==matches.size &&
            matches.all(::named) && outcome in outcomes && (scorePct.isNaN()||scorePct in 0.0..100.0) &&
            (confidencePct.isNaN()||confidencePct in 0.0..100.0)
        fun names():List<String> = (listOf(selected).filter{it.isNotEmpty()}+matches).distinct()
        fun primary():String = selected.ifEmpty { "OBS_$outcome" }
        fun channel():String = if(origin=="DESK"||origin=="COHORT") "$origin.$selected" else origin
    }
    fun signal(origin:String, at:Long, selected:String?, matches:List<String>, outcome:String,
               score:Double, scale:Double, confidence:Double, decision:Long):Signal? {
        if(!scale.isFinite() || scale<=0.0 || !score.isFinite() || score<0.0 || score>scale)return null
        val s=selected.orEmpty()
        // Blank/no-trigger is a classification state, not a strategy. Invalid named IDs are not truncated.
        if(s.isNotEmpty() && s !in missing && !named(s))return null
        val f=Signal(origin,at,s.takeIf(::named).orEmpty(),matches.filter{it !in missing}.distinct(),outcome,
            100.0*score/scale,confidence)
        return f.takeIf{it.valid(decision)}
    }

    /** An explicitly named expert cohort has a signal, but no fabricated numeric score. */
    fun cohort(name:String,at:Long):Signal?=Signal("COHORT",at,name,emptyList(),"NATIVE",Double.NaN,Double.NaN).takeIf{it.valid(at)&&it.selected.isNotBlank()}

    /** Mark-only target at the native reference horizon. Cost deducted exactly once. */
    data class Objective(val targetNetPct:Double,val horizonMs:Long,val policy:String) {
        fun valid()=targetNetPct.isFinite() && targetNetPct>0.0 && horizonMs in 60_000L..NativeZoneSequence9058.HOUR &&
            policy in setOf("LANE", "RING", "DESK")
    }
    fun objective(gross:Double,cost:Double,horizon:Long,policy:String):Objective? {
        if(!gross.isFinite() || !cost.isFinite() || cost<0.0)return null
        return Objective(gross-cost,horizon,policy).takeIf{it.valid()}
    }
    /** For absolute native proposal levels; short uses the opposite signed move. */
    fun grossLevels(price:Double,target:Double,stop:Double,direction:String):Pair<Double,Double>? {
        if(listOf(price,target,stop).any{!it.isFinite()||it<=0.0})return null
        val sign=when(direction){"LONG"->1.0;"SHORT"->-1.0;else->return null}
        val t=sign*(target/price-1.0)*100.0;val s=-sign*(stop/price-1.0)*100.0
        return (t to s).takeIf{t.isFinite()&&t>0&&s.isFinite()&&s in .01..100.0}
    }
    /** Reconstruct the label from a verified continuous prefix. A gap cannot invent a loss. */
    fun outcome(o:Objective?,decision:Long,entry:Long,last:Long,hit:Long,stop:Long):Int? {
        if(o==null)return if(hit==0L)-1 else null
        if(!o.valid()||decision<=0L||decision>Long.MAX_VALUE-o.horizonMs)return null
        if(entry==0L)return if(last==0L&&hit==0L&&stop==0L)-1 else null
        if(entry<decision||last<entry)return null
        val end=decision+o.horizonMs
        if(hit!=0L&&(hit<=entry||hit>last||hit>end||(stop!=0L&&stop<=hit)))return null
        if(stop!=0L&&(stop<=entry||stop>last))return null
        return when {hit!=0L->1;stop in (entry+1)..end||last>=end->0;else->-1}
    }
    /** Keep exact scopes. One market path can teach several fired predicates, but is one asset observation per scope. */
    internal fun scopes(base:NativeZoneSequence9058.Scope,signal:Signal?):List<NativeZoneSequence9058.Scope> =
        if(signal==null)emptyList() else signal.names().map{base.copy(setup=it)}.filter{it.valid()}
    internal fun vector(base:DoubleArray,scope:NativeZoneSequence9058.Scope,signal:Signal?,objective:Objective?,at:Long):DoubleArray {
        require(base.size==NativeZoneSequence9058.WIDTH)
        val x=base.copyOf(base.size+EXTRA)
        val primary=signal?.selected==scope.setup
        // Earlier same-setup flags describe the selected strategy only, not an alternative.
        if(!primary){x[40]=Double.NaN;x[41]=Double.NaN}
        val k=base.size
        x[k]=signal?.scorePct?:Double.NaN;x[k+1]=signal?.confidencePct?:Double.NaN
        x[k+2]=signal?.let{(at-it.at)/1000.0}?:Double.NaN
        x[k+3]=if(signal==null)Double.NaN else if(primary)1.0 else 0.0
        x[k+4]=signal?.matches?.size?.toDouble()?:Double.NaN
        x[k+5]=objective?.targetNetPct?:Double.NaN;x[k+6]=objective?.horizonMs?.div(1000.0)?:Double.NaN
        return x
    }
    fun nativeLabels(label:Int)=IntArray(NativeZoneSequence9058.HEADS){if(it==0)label else -1}
}
