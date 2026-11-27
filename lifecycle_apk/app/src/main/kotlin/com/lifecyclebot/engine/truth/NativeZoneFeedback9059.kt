package com.lifecyclebot.engine.truth

/** Bounded, immutable publication of independent setup/head challengers. No order authority. */
internal class NativeZoneFeedback9059(private val capacity: Int = 96, private val ttlMs: Long = 86_400_000L) {
    init { require(capacity > 0 && ttlMs > 0) }
    private data class Key(val scope: NativeZoneSequence9058.Scope, val head: Int)
    private data class Published(val at: Long, val fit: NativeZoneNet9058.Fit)
    @Volatile private var book: Map<Key, Published> = emptyMap()

    @Synchronized fun publish(scope: NativeZoneSequence9058.Scope, head: Int, at: Long, fit: NativeZoneNet9058.Fit): Boolean {
        if (!scope.valid() || head !in 0 until NativeZoneSequence9058.HEADS || at <= 0L) return false
        val key = Key(scope,head)
        val old = book
        if (old.values.any { it.at > at } || old[key]?.at == at) return false
        val next = old.filterValues { at-it.at in 0L..ttlMs }.toMutableMap()
        next[key] = Published(at,fit)
        while (next.size > capacity) next.remove(next.minByOrNull { it.value.at }!!.key)
        book = next.toMap()
        return true
    }

    /** Same setup, market, source and direction only. Immutable decision-time receipts, all retained heads. */
    fun predict(scope: NativeZoneSequence9058.Scope, features: DoubleArray, at: Long): List<NativeZoneSequence9058.Prediction> {
        if (!scope.valid() || features.size != NativeZoneSequence9058.WIDTH) return emptyList()
        val snapshot = book
        return (0 until NativeZoneSequence9058.HEADS).mapNotNull { head ->
            val p = snapshot[Key(scope,head)] ?: return@mapNotNull null
            if (at-p.at !in 0L..ttlMs) return@mapNotNull null
            NativeZoneSequence9058.Prediction(head,p.at,p.fit.linear.predict(features),p.fit.neural.predict(features))
                .takeIf { it.valid(at) }
        }
    }
    fun count() = book.size

    data class Graded(val asset: String,val decision: Long,val available: Long,val label: Int,val p: NativeZoneSequence9058.Prediction)
    data class Prospective(val assets: Int,val windows: Int,val neuralBrier: Double,val linearBrier: Double,val baseRate: Double)

    companion object {
        /** Prospective, not replay predictions. One asset has total weight one; duplicate windows never inflate support. */
        fun score(rows: List<Graded>,head: Int,now: Long): Prospective? {
            if (head !in 0 until NativeZoneSequence9058.HEADS) return null
            val groups = rows.filter { it.asset.isNotBlank() && it.decision > 0L && it.available in it.decision..now &&
                it.label in 0..1 && it.p.head==head && it.p.published < it.decision && it.p.valid(it.decision) }
                .groupBy { it.asset }
            var assets=0; var windows=0; var neural=0.0; var linear=0.0; var prior=0.0
            for (group in groups.values) {
                // Conflicting records for the same decision are not independent ground truth.
                val unique=group.groupBy { it.decision }.values.mapNotNull { g -> g.first().takeIf { first -> g.all { it==first } } }
                if (unique.isEmpty()) continue
                assets++;windows+=unique.size
                neural+=unique.map { val e=it.p.neural-it.label;e*e }.average()
                linear+=unique.map { val e=it.p.linear-it.label;e*e }.average()
                prior+=unique.map { it.label.toDouble() }.average()
            }
            return if (assets==0) null else Prospective(assets,windows,neural/assets,linear/assets,prior/assets)
        }
    }
}

/** The native opinion's actual fired setup and signal clock, not an exit-style name or a later reclassification. */
internal data class NativeZoneSignal9059(val lane: String,val firedSetup: String?,val signalAtMs: Long,val mode: String) {
    fun validFor(decisionLane: String,at: Long): Boolean = lane==decisionLane && signalAtMs>0L &&
        at-signalAtMs in 0L..5_000L && mode in setOf("LIVE","PAPER")
    fun setup(): String = firedSetup?.trim()?.uppercase()?.takeIf { it.matches(Regex("[A-Z0-9_@.-]{1,64}")) &&
        it !in setOf("NONE","UNKNOWN","NO_SETUP","NO_TRIGGER") } ?: "UNCLASSIFIED"
}
