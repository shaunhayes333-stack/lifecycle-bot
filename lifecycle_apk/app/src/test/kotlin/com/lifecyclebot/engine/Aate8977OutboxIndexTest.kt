package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.ForwardOutcomeOutbox8404
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8977 — the forward outbox answers from its indexes exactly as the pre-8977 scans did: the same first revision, the
 * same superseded rows, the same drain order and rotation, over a long random run against a copy of the old outbox.
 */
class Aate8977OutboxIndexTest {
    /** The pre-8977 outbox, verbatim in behaviour (the reference). */
    private class Old(private val capacity: Int) {
        val rows = LinkedHashMap<String, String>(); val durable = HashSet<String>(); val acknowledged = HashSet<String>(); var cursor = ""
        fun restore(saved: Map<String, String>) { saved.forEach { (id, raw) -> rows[id] = raw; durable.add(id) } }
        fun first(episode: String, horizonMs: Long): Pair<String, String>? =
            rows.entries.firstOrNull { it.key.startsWith("$episode|R|") && it.key.substringAfterLast('|').toLongOrNull() == horizonMs }?.let { it.key to it.value }
        fun stage(id: String, payload: () -> String?, write: (String, String) -> Boolean): Boolean {
            if (!rows.containsKey(id)) { if (rows.size >= capacity) return false; rows[id] = payload() ?: return false }
            if (id in durable) return true
            if (!write(id, rows.getValue(id))) return false
            durable.add(id); return true
        }
        fun supersede(prefix: String, keep: String, protectedHorizonMs: Long, remove: (String) -> Boolean): Int {
            if (prefix.isBlank()) return 0
            var dropped = 0
            for (id in rows.keys.filter { it.startsWith(prefix) && it != keep && it !in acknowledged &&
                (protectedHorizonMs <= 0L || it.substringAfterLast('|').toLongOrNull() != protectedHorizonMs) }) {
                if (id in durable && !remove(id)) continue
                rows.remove(id); durable.remove(id); dropped++
            }
            return dropped
        }
        fun rowsFor(episode: String): Int = rows.keys.count { it == episode || it.startsWith("$episode|R|") }
        fun drain(limit: Int, write: (String, String) -> Boolean, consume: (String) -> Boolean, remove: (String) -> Boolean) {
            val keys = rows.keys.sorted()
            val start = keys.indexOfFirst { it > cursor }.takeIf { it >= 0 } ?: 0
            val batch = (keys.drop(start) + keys.take(start)).take(limit.coerceAtLeast(0))
            for (id in batch) {
                cursor = id
                val raw = rows[id] ?: continue
                try {
                    if (id !in durable) { if (!write(id, raw)) continue; durable.add(id) }
                    if (id !in acknowledged) { if (!consume(raw)) continue; acknowledged.add(id) }
                    if (remove(id)) { rows.remove(id); durable.remove(id); acknowledged.remove(id) }
                } catch (_: Exception) {}
            }
        }
    }

    @Test fun theIndexedOutboxAnswersExactlyAsTheScanDid() {
        val rnd = java.util.Random(8977)
        val horizons = longArrayOf(900_000L, 1_800_000L, 3_600_000L, 14_400_000L)
        val episodes = (0 until 40).map { "m$it|${if (it % 3 == 0) "MOONSHOT" else "CORE"}|${1000 + it}" }
        fun id(): String {
            val ep = episodes[rnd.nextInt(episodes.size)]
            return if (rnd.nextInt(4) == 0) ep else "$ep|R|${rnd.nextInt(50) + 1}|${horizons[rnd.nextInt(horizons.size)]}"
        }
        val old = Old(120); val new = ForwardOutcomeOutbox8404(120)
        val saved = (0 until 30).associate { id() to "s${rnd.nextInt(1000)}" }
        old.restore(saved); new.restore(saved)
        repeat(20_000) { step ->
            when (rnd.nextInt(5)) {
                0 -> { val i = id(); val ok = rnd.nextInt(5) != 0; val p = "p$step"
                    assertEquals(old.stage(i, { p }) { _, _ -> ok }, new.stage(i, { p }) { _, _ -> ok }) }
                1 -> { val ep = episodes[rnd.nextInt(episodes.size)]; val h = horizons[rnd.nextInt(horizons.size)]
                    assertEquals(old.first(ep, h), new.firstRevisionForHorizon8521(ep, h)) }
                2 -> { val ep = episodes[rnd.nextInt(episodes.size)]; val keep = "$ep|R|${rnd.nextInt(50) + 1}|3600000"
                    val prot = if (rnd.nextBoolean()) 3_600_000L else 0L; val del = rnd.nextInt(4) != 0
                    assertEquals(old.supersede("$ep|R|", keep, prot) { del }, new.supersedeRevisions8418("$ep|R|", keep, prot) { del }) }
                3 -> { val lim = rnd.nextInt(40); val ok = rnd.nextInt(3); val seenO = ArrayList<String>(); val seenN = ArrayList<String>()
                    old.drain(lim, { _, _ -> ok != 0 }, { seenO += it; ok != 1 }, { ok == 2 })
                    new.drain(lim, { _, _ -> ok != 0 }, { seenN += it; ok != 1 }, { ok == 2 })
                    assertEquals(seenO, seenN) }
                else -> { val ep = episodes[rnd.nextInt(episodes.size)]; assertEquals(old.rowsFor(ep), new.rowsFor8418(ep)) }
            }
            assertEquals(old.rows.size, new.size())
        }
        for ((k, v) in old.rows) assertEquals(v, new.payload(k))
    }

    @Test fun theIdFieldsAreReadWithoutGuessing() {
        assertEquals("m|CORE|1", ForwardOutcomeOutbox8404.episodeOf8977("m|CORE|1|R|5|3600000"))
        assertNull(ForwardOutcomeOutbox8404.episodeOf8977("m|CORE|1"))
        assertEquals(3_600_000L, ForwardOutcomeOutbox8404.horizonOf8977("m|CORE|1|R|5|3600000"))
        assertNull(ForwardOutcomeOutbox8404.horizonOf8977("m|CORE|1|R|5|"))
        assertNull(ForwardOutcomeOutbox8404.horizonOf8977("m|CORE|x"))
        assertEquals(-5L, ForwardOutcomeOutbox8404.horizonOf8977("a|-5"))
        assertEquals(1000L, ForwardOutcomeOutbox8404.horizonOf8977("plain|1000"))
    }

    @Test fun noScanOrCopyIsLeftOnTheHotPath() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/cortex/ForwardOutcomeDelivery8404.kt").readText()
        assertFalse(src.contains("val keys = rows.keys.sorted()"))
        assertTrue(src.contains("sorted8977.tailSet(cursor, false)"))
        assertTrue(src.contains("val ids = revisions8977[episode] ?: return null"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 162. The forward outbox is indexed (8977)"))
    }
}
