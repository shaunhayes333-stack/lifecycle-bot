package com.lifecyclebot.v3.scoring

/**
 * V5.0.8535 §C01 — scaling a group of integer votes without losing their sum.
 *
 * Every soft cap in UnifiedScorer multiplied EACH negative vote by `cap / total` and truncated it with `.toInt()`: fourteen
 * outer-ring votes of -3 (total -42, cap -10) each became `(-3 * 10/42).toInt() = 0`, so fourteen adverse readings summed to
 * **0** instead of -10; `[-12,-6,-3,-3]` became -5,-2,-1,-1 = -9. The same truncation sat in the bootstrap (FLUID×) and
 * generation-equalisation (GENEQ×) scales. Here a group is apportioned to its exact integer target by largest remainder:
 * the sum is the target to the unit, no vote changes sign, no vote is scaled past its own value, and the result does not
 * depend on the order the votes arrive in (ties broken by magnitude, then by position).
 */
object SignedCap8535 {

    /**
     * Pure: [values] scaled toward [targetTotal] as integers whose sum is EXACTLY [targetTotal]. [targetTotal] must carry the
     * same sign as the sum of [values] (or be 0) and lie between 0 and that sum; otherwise [values] is returned unchanged.
     */
    fun apportion(values: List<Int>, targetTotal: Int): List<Int> {
        if (values.isEmpty()) return values
        val total = values.sum()
        if (total == 0 || targetTotal == total) return values
        if (targetTotal == 0) return values.map { 0 }
        if ((total < 0) != (targetTotal < 0) || kotlin.math.abs(targetTotal) > kotlin.math.abs(total)) return values
        val scale = targetTotal.toDouble() / total
        val exact = values.map { it * scale }
        val base = exact.map { it.toInt() }   // toward zero: every vote keeps its sign
        var remainder = targetTotal - base.sum()
        if (remainder == 0) return base
        val step = if (remainder < 0) -1 else 1
        val order = exact.indices.sortedWith(
            compareByDescending<Int> { kotlin.math.abs(exact[it] - base[it]) }
                .thenByDescending { kotlin.math.abs(values[it]) }
                .thenBy { it },
        )
        val out = base.toMutableList()
        var k = 0
        while (remainder != 0 && k < order.size) {
            val i = order[k++]
            if (values[i] == 0 || kotlin.math.abs(out[i] + step) > kotlin.math.abs(values[i])) continue   // never past its own value
            out[i] += step
            remainder -= step
        }
        return out
    }

    /**
     * The soft cap: the negative votes of [components] matching [select] scaled so their sum is exactly [cap] (a negative
     * integer) when it would otherwise be below it; everything else untouched. [tag] (e.g. "SOFTCAP") is appended to the
     * reason of each scaled vote with the factor applied, as the UNIFIED path did; null keeps reasons unchanged.
     */
    fun capNegatives(components: List<ScoreComponent>, cap: Int, tag: String?, select: (ScoreComponent) -> Boolean): List<ScoreComponent> {
        if (cap >= 0) return components
        val idx = components.indices.filter { select(components[it]) && components[it].value < 0 }
        if (idx.isEmpty()) return components
        val total = idx.sumOf { components[it].value }
        if (total >= cap) return components
        val scaled = apportion(idx.map { components[it].value }, cap)
        val factor = cap.toDouble() / total
        val out = components.toMutableList()
        idx.forEachIndexed { k, i ->
            val c = components[i]
            out[i] = c.copy(value = scaled[k], reason = if (tag == null) c.reason else "${c.reason} | $tag×${"%.2f".format(factor)}")
        }
        return out
    }

    /**
     * A non-cap scale ([factor] in 0..1) on the votes of [components] matching [select]: their sum becomes the rounded
     * scaled sum, apportioned as above (8535: FLUID× and GENEQ× truncated each vote on its own). [factor] >= 1 is a no-op.
     */
    fun scaleGroup(components: List<ScoreComponent>, factor: Double, tag: String?, select: (ScoreComponent) -> Boolean): List<ScoreComponent> {
        if (!factor.isFinite() || factor >= 1.0 || factor < 0.0) return components
        val idx = components.indices.filter { select(components[it]) && components[it].value != 0 }
        if (idx.isEmpty()) return components
        val neg = idx.filter { components[it].value < 0 }
        val pos = idx.filter { components[it].value > 0 }
        val out = components.toMutableList()
        for (group in listOf(neg, pos)) {
            if (group.isEmpty()) continue
            val values = group.map { components[it].value }
            val sum = values.sum()
            val target = if (sum < 0) -Math.round(-sum * factor).toInt() else Math.round(sum * factor).toInt()   // half away from zero
            val scaled = apportion(values, target)
            group.forEachIndexed { k, i ->
                val c = components[i]
                if (scaled[k] != c.value) out[i] = c.copy(value = scaled[k], reason = if (tag == null) c.reason else "${c.reason} | $tag×${"%.2f".format(factor)}")
            }
        }
        return out
    }
}
