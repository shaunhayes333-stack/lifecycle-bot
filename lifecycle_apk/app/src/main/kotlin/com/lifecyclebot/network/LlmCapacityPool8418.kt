package com.lifecyclebot.network

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8418 §DAISY_CHAIN — the LLM council as ONE pool whose capacity is the sum of its providers' own budgets.
 *
 * Before this, every member had the same ad-hoc cooldowns and the council asked members in a fixed rotation; a member
 * inside its provider's minute was still asked and still spent a slot on a known refusal. Now each provider has its
 * own [CapacityBucket8418] sized to its published free tier, routing goes to whoever has spare capacity weighted by
 * measured health and latency, and a 429 / 5xx / timeout pauses only the bucket that earned it.
 *
 * Members that share one rate limit share one bucket (Pollinations' POST and GET members are one IP budget; the three
 * OVH anonymous slots are one per-IP budget across its models).
 */
object LlmCapacityPool8418 {

    data class Budget8418(val key: String, val rpm: Int, val maxInFlight: Int)

    private val buckets = ConcurrentHashMap<String, CapacityBucket8418>()

    /**
     * Pure: the budget a council member draws from. Published free tiers: Groq 30 rpm/model, OpenRouter `:free`
     * 20 rpm, Gemini Flash free 10 rpm, Cerebras 30 rpm, Mistral free <=1 rps (read as 30), LLM7 anonymous 30 rpm,
     * Pollinations anonymous one reply per ~30 s per IP, OVHcloud anonymous 2 rpm per model per IP (x3 models).
     */
    fun budgetFor8418(member: String): Budget8418 {
        val m = member.trim().lowercase()
        return when {
            m == "groq" -> Budget8418("groq", 30, 2)
            m == "openrouter" -> Budget8418("openrouter", 20, 2)
            m == "anthropic" -> Budget8418("anthropic", 50, 2)
            m == "gemini" -> Budget8418("gemini", 10, 2)
            m == "cerebras" -> Budget8418("cerebras", 30, 2)
            m == "mistral" -> Budget8418("mistral", 30, 2)
            m == "emergent" -> Budget8418("emergent", 20, 2)
            m.startsWith("pollinations") -> Budget8418("pollinations", 2, 1)
            m.startsWith("ovh_keyless") -> Budget8418("ovh", 6, 3)
            m.startsWith("x_") -> {
                val p = LlmProviderPresets8418.preset8418(m.removePrefix("x_"))
                if (p != null) Budget8418(m, p.rpm, p.maxInFlight) else Budget8418(m, 20, 2)
            }
            else -> LlmProviderPresets8418.preset8418(m)?.let { Budget8418(m, it.rpm, it.maxInFlight) } ?: Budget8418(m, 20, 2)
        }
    }

    fun bucketOf8418(member: String): CapacityBucket8418 {
        val b = budgetFor8418(member)
        return buckets.getOrPut(b.key) { CapacityBucket8418(b.key, b.rpm, b.maxInFlight) }
    }

    fun tryAcquire8418(member: String, nowMs: Long): Boolean = bucketOf8418(member).tryAcquire(nowMs)

    fun hasCapacity8418(member: String, nowMs: Long): Boolean = bucketOf8418(member).hasCapacity(nowMs)

    fun release8418(member: String, nowMs: Long, outcome: CapacityBucket8418.Outcome8418, latencyMs: Long, retryAfterMs: Long? = null) {
        bucketOf8418(member).release(nowMs, outcome, latencyMs, retryAfterMs)
    }

    /**
     * Routing order: highest weight first (health x spare capacity / latency); members without capacity go last
     * (the caller skips them). Ties keep the caller's order, so an operator's keyed provider still leads an untried
     * keyless one. Then each bucket's FIRST member comes before any second member of the same bucket, so a parallel
     * fan-out reaches different providers before it doubles up on one.
     */
    fun <T> order8418(items: List<T>, nameOf: (T) -> String, nowMs: Long): List<T> {
        val weighted = items.mapIndexed { i, x -> Triple(x, bucketOf8418(nameOf(x)).weight(nowMs), i) }
        val sorted = weighted.sortedWith(compareByDescending<Triple<T, Double, Int>> { it.second }.thenBy { it.third })
        val firsts = ArrayList<T>(items.size)
        val repeats = ArrayList<T>()
        val seen = HashSet<String>()
        for (t in sorted) {
            if (seen.add(budgetFor8418(nameOf(t.first)).key)) firsts.add(t.first) else repeats.add(t.first)
        }
        firsts.addAll(repeats)
        return firsts
    }

    /** Total requests per minute the pool can serve and what is left this minute, over the buckets in use. */
    fun totals8418(nowMs: Long): Triple<Int, Int, Int> {
        var rpm = 0
        var avail = 0
        var used = 0
        for (b in buckets.values) {
            rpm += b.rpm
            avail += b.availableThisMinute(nowMs)
            used += b.usedLastMinute(nowMs)
        }
        return Triple(rpm, avail, used)
    }

    /** `llmPool8418: total avail=A/R rpm used=U · <bucket cells>` — per-provider requests/min available and used. */
    fun capacityLine8418(nowMs: Long = System.currentTimeMillis()): String {
        if (buckets.isEmpty()) return "llmPool8418: no member asked yet"
        val (rpm, avail, used) = totals8418(nowMs)
        val cells = buckets.values.sortedBy { it.key }.joinToString(" · ") { it.cell(nowMs) }
        return "llmPool8418: total avail=$avail/$rpm rpm used=$used · $cells"
    }

    /** Pure: the council bucket a GeminiCopilot provider draws from, or null for the council itself (it pools inside). */
    fun copilotBucket8418(copilotName: String): String? = when (copilotName.trim().lowercase()) {
        "keyless_fallback", "" -> null
        "gemini_direct" -> "gemini"
        else -> copilotName.trim().lowercase()
    }

    /**
     * Runs one already-acquired call against [member]'s bucket and releases it with the real outcome (an exception
     * is recorded as an error and rethrown). A null [member] runs [body] unaccounted.
     */
    fun pooled8418(member: String?, body: () -> String?): String? {
        if (member == null) return body()
        val t0 = System.currentTimeMillis()
        var outcome = CapacityBucket8418.Outcome8418.ERROR
        try {
            val r = body()
            outcome = if (!r.isNullOrBlank()) CapacityBucket8418.Outcome8418.OK else CapacityBucket8418.Outcome8418.EMPTY
            return r
        } finally {
            val t1 = System.currentTimeMillis()
            release8418(member, t1, outcome, t1 - t0)
        }
    }

    fun resetForTests8418() { buckets.clear() }
}
