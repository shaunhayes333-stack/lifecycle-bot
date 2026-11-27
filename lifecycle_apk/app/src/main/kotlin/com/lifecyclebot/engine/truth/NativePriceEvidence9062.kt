package com.lifecyclebot.engine.truth

/** A purpose-specific view of the already-read price candidates, with their original clocks.
 * A newer OFFWATCH quote must not hide a still-fresh witnessed pool mark from native research.
 * No provider calls, synthetic marks, independent flags, or event timestamps are created here.
 */
internal object NativePriceEvidence9062 {
    private const val MAX_MARKS = 2048
    private val marks = LinkedHashMap<String, LabelMark8386>()

    internal fun knownVenue(venue: String): Boolean = venue.equals("pump.curve", true) ||
        (venue.length in 32..44 && venue.all { it in "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz" })

    internal fun select(mint: String, candidates: List<LabelMark8386>, now: Long,
                        supplyDecimals: Int?): LabelMark8386? = candidates.asSequence()
        .filter { it.mint == mint && it.known() && it.independentBasis &&
            LabelEvidence8386.independentSource8418(it.source) && knownVenue(it.venue) }
        .mapNotNull { m ->
            if (supplyDecimals != null && m.decimals >= 0 && m.decimals != supplyDecimals) null
            else {
                val decimals = if (m.decimals >= 0) m.decimals else supplyDecimals ?: -1
                m.copy(decimals = decimals).takeIf { valid(it, now) }
            }
        }
        .maxByOrNull { it.observedAtMs }

    private fun valid(m: LabelMark8386, now: Long): Boolean =
        NativeZoneSequence9058.Mark(m.mint, m.venue, m.decimals, m.observedAtMs,
            m.readAtMs, m.usd, m.source).validAt(now)

    fun publish(mint: String, candidates: List<LabelMark8386>, now: Long, supplyDecimals: Int?) {
        val chosen = select(mint, candidates, now, supplyDecimals)
        synchronized(this) {
            if (chosen != null) {
                val previous = marks[mint]
                // An older observation must not overwrite a newer valid publication.
                if (previous == null || !valid(previous, now) || chosen.observedAtMs >= previous.observedAtMs)
                    marks[mint] = chosen
                while (marks.size > MAX_MARKS) marks.remove(marks.keys.first())
            } else {
                // A failed collection cannot keep contradictory decimals alive through this cache.
                marks[mint]?.let { old ->
                    if (!valid(old, now) || (supplyDecimals != null && old.decimals != supplyDecimals)) marks.remove(mint)
                }
            }
        }
    }

    @Synchronized fun markFor(mint: String, now: Long): LabelMark8386? = marks[mint]?.takeIf { valid(it, now) }
    @Synchronized internal fun resetForTest() = marks.clear()
}
