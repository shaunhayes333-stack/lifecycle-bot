package com.lifecyclebot.engine.truth

/**
 * V5.0.8420 §A_FADED_COUNT_MUST_ACTUALLY_FADE.
 *
 * The windowed learners (SpecialistMiner7972.Stat, ForwardReturnLabeler7731.Tally, TailHunter7996) fade a full record by
 * k = window / (window + 1) on every new outcome: sums are multiplied exactly, but win / runner COUNTS were re-rounded to
 * an Int each time. round(c * (1 - 1/300)) == c for every c <= 149 (and banker's rounding keeps 150), so a count never
 * fell: a combination that won 150 times and then lost 3,000 in a row still reported 150 wins out of 300 (50% win rate,
 * full runner rate) while its mean read -100%. Promotion, runner rates, STRONG buckets and lane win rates all read the
 * frozen count. Each count now fades exactly: (count + carry) * k, the whole part kept, the remainder carried.
 */
object DecayCount8420 {
    /** Pure: fade [count] (with its carried fraction [carry]) by [k]; returns (whole count, new carry in [0, 1)). */
    fun fade(count: Int, carry: Double, k: Double): Pair<Int, Double> {
        if (!k.isFinite() || k >= 1.0 || k < 0.0) return count to carry
        val exact = ((count.toDouble() + (if (carry.isFinite()) carry else 0.0)) * k).coerceAtLeast(0.0)
        val whole = kotlin.math.floor(exact)
        return whole.toInt() to (exact - whole).coerceIn(0.0, 0.999999)
    }
}
