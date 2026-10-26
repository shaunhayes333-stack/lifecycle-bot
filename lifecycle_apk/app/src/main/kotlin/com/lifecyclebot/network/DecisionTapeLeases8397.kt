package com.lifecyclebot.network

import com.lifecyclebot.engine.truth.HeliusCreditEconomy7881

/** Small, expiring leases for actual per-mint logs when the program stream is paced out. */
internal class DecisionTapeLeases8397(
    private val capacity: Int = 6,
    private val durationMs: Long = 60_000L,
    private val reserve: (Double, Long) -> HeliusCreditEconomy7881.Reservation8386? = { n, now ->
        HeliusCreditEconomy7881.reserveDecision8397(HeliusCreditEconomy7881.Consumer.DECISION_TAPE_8397, n, now)
    },
    private val finish: (HeliusCreditEconomy7881.Reservation8386) -> Unit = HeliusCreditEconomy7881::finish8386,
) {
    private data class Lease(val until: Long, val credit: HeliusCreditEconomy7881.Reservation8386)
    private val leases = LinkedHashMap<String, Lease>()

    @Synchronized fun active(mint: String, now: Long): Boolean = leases[mint]?.let {
        now < it.until && it.credit.remaining8397() > 0.0
    } == true

    @Synchronized fun admit(mint: String, now: Long): Boolean {
        expire(now)
        if (active(mint, now)) return true
        if (mint.isBlank() || leases.size >= capacity) return false
        val credit = reserve(50.0, now) ?: return false
        leases[mint] = Lease(now + durationMs, credit)
        return true
    }

    /** The socket's ordinary byte meter remains the only actual-spend writer. */
    @Synchronized fun noteBytes(mint: String, bytes: Int, now: Long): Boolean {
        val lease = leases[mint] ?: return false
        lease.credit.sent8386(HeliusCreditEconomy7881.creditsForWsBytes(bytes.toLong()))
        if (active(mint, now)) return true
        release(mint)
        return false
    }

    @Synchronized fun expire(now: Long): Set<String> {
        val expired = leases.keys.filter { !active(it, now) }.toSet()
        expired.forEach(::release)
        return expired
    }

    @Synchronized fun release(mint: String) { leases.remove(mint)?.let { finish(it.credit) } }
    @Synchronized fun tracked(mint: String): Boolean = mint in leases
    @Synchronized fun clear() { leases.keys.toList().forEach(::release) }
    @Synchronized fun size(): Int = leases.size
}
