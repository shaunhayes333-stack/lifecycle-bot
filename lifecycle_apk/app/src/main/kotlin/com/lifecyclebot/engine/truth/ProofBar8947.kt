package com.lifecyclebot.engine.truth

/**
 * V5.0.8947 §THE_PROOF_BAR_IS_TIERED
 *
 * Owner, 22 Nov: "is 30 trades too much or enough?" then "go ahead". A fixed count of 30 is the wrong question: 20 coins
 * can already prove a strong edge, and 80 can still be one lucky runner. A per-coin record (each coin's outcome once,
 * oldest first) is graded on what it shows, not on a count alone:
 *
 *  - HOLDS: the mean is above zero by two standard errors, the mean is still positive with the three best coins removed,
 *    and both halves of the record (older, newer) are positive on their own. The median is shown, not required: a runner
 *    edge (most coins a small loss, one in ten a multiple) has a negative median and is exactly what the bot is for; the
 *    ex-top-3 and halves tests are what keep one lucky streak from proving it.
 *  - CAUTIOUS: it HOLDS on 20+ coins. It may open at the careful path's small size and limits only.
 *  - FULL: it HOLDS on 50+ coins and the newest 20 coins are positive. It may admit at full size.
 *
 * The record is re-graded every time it is read, so a record that stops holding stops admitting, and a full record whose
 * newest coins turn negative drops to cautious. On the owner's 22 Nov 20:00 research rows the careful pocket (83 coins)
 * grades FULL (mean +21.6, lower +6.2, without the top three +14.3, halves +25.1 / +18.1, newest 20 +31.8);
 * graded on its first 20 coins it was already CAUTIOUS, ten coins before the old bar of 30 opened it. The base pocket's
 * first 20 coins (lower -2.1) were not, and are still not opened early. Records that keep only running sums (n, sum,
 * sum of squares) cannot be graded this way and stay on PayingCell8723's bar.
 */
object ProofBar8947 {
    private const val CAUTIOUS_N_8947 = 20
    private const val FULL_N_8947 = 50
    private const val RECENT_N_8947 = 20
    private const val PROOF_SE_8947 = 2.0
    private const val TOP_8947 = 3
    const val NONE_8947 = 0
    const val CAUTIOUS_8947 = 1
    const val FULL_8947 = 2

    /** Pure: the tier of a per-coin record [ys] (each coin's outcome once, oldest first). */
    fun tier8947(ys: List<Double>): Int {
        if (ys.size < CAUTIOUS_N_8947 || ys.any { !it.isFinite() } || !holds8947(ys)) return NONE_8947
        return if (ys.size >= FULL_N_8947 && ys.takeLast(RECENT_N_8947).average() > 0.0) FULL_8947 else CAUTIOUS_8947
    }

    /** Pure: does [ys] hold — mean above zero by two SE, positive without the top three, both halves positive? */
    internal fun holds8947(ys: List<Double>): Boolean {
        val n = ys.size
        if (n <= TOP_8947 + 1) return false
        val m = ys.average()
        val se = kotlin.math.sqrt(ys.sumOf { (it - m) * (it - m) } / n / n)
        if (!(m.isFinite() && se.isFinite() && m - PROOF_SE_8947 * se > 0.0)) return false
        if (!(ys.sorted().subList(0, n - TOP_8947).average() > 0.0)) return false
        val h = n / 2
        return ys.subList(0, h).average() > 0.0 && ys.subList(h, n).average() > 0.0
    }

    /** Pure: the tier's name for a status line. */
    private fun name8947(tier: Int): String = when (tier) { FULL_8947 -> "FULL"; CAUTIOUS_8947 -> "CAUTIOUS"; else -> "NONE" }

    /** Pure: a brief grade of [ys] for a status line: tier, median, mean without the top three, halves, newest 20. */
    fun line8947(ys: List<Double>): String {
        val t = name8947(tier8947(ys))
        if (ys.size <= TOP_8947 + 1 || ys.any { !it.isFinite() }) return "$t n${ys.size}"
        val s = ys.sorted()
        val n = ys.size
        val median = if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
        fun f(x: Double) = "%+.1f".format(x)
        return "$t n$n med${f(median)} ex3${f(s.subList(0, n - TOP_8947).average())} halves${f(ys.subList(0, n / 2).average())}/" +
            "${f(ys.subList(n / 2, n).average())} new${f(ys.takeLast(RECENT_N_8947).average())}"
    }
}
