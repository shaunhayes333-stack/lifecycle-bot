from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(rel: str) -> str:
    return (ROOT / rel).read_text()


def write(rel: str, text: str) -> None:
    (ROOT / rel).write_text(text)


def replace_once(rel: str, old: str, new: str, label: str) -> None:
    p = ROOT / rel
    src = p.read_text()
    count = src.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    p.write_text(src.replace(old, new, 1))
    print(f"patched {label}")


registry = "app/src/main/kotlin/com/lifecyclebot/perps/DynamicAltTokenRegistry.kt"

replace_once(
    registry,
    """    private val evaluationInflight6615 = ConcurrentHashMap<String, String>()
    private val evaluationCompleted6615 = ConcurrentHashMap<String, String>()
""",
    """    private val evaluationInflight6615 = ConcurrentHashMap<String, String>()
    // V5.0.6692 — ownership age belongs to the evaluation lease, not to the
    // token metadata row. Using token.lastUpdatedMs made queue age meaningless
    // and could never prove/reap a genuinely wedged owner.
    private val evaluationInflightStartedAt6692 = ConcurrentHashMap<String, Long>()
    private val evaluationCompleted6615 = ConcurrentHashMap<String, String>()
""",
    "inflight ownership timestamps",
)

replace_once(
    registry,
    """    private val evaluationProgressStamp6580 = ConcurrentHashMap<String, Long>()
""",
    """    private val evaluationProgressStamp6580 = ConcurrentHashMap<String, Long>()
    // Canonical identities are themselves `chain|token`. Never parse a progress
    // key with the same pipe delimiter; that reduced `bsc|0x...` to `bsc` and
    // made the global stale reaper unable to find/terminalize the real owner.
    private const val EVAL_PROGRESS_SEPARATOR_6692 = "\\u001F"
""",
    "progress key delimiter",
)

src = read(registry)
gen_marker = """    private fun evaluationGeneration6615(tok: DynToken): String = listOf(
        tok.chainId.trim().lowercase(), tok.tokenAddress.trim().lowercase(), tok.price.toBits(),
        tok.priceChange24h.toBits(), tok.mcap.toBits(), tok.liquidityUsd.toBits(), tok.volume24h.toBits(),
        tok.buys24h, tok.sells24h, tok.source.trim().uppercase(), tok.isTrending, tok.isBoosted,
    ).joinToString("|")

"""
if src.count(gen_marker) != 1:
    raise SystemExit("evaluation generation marker missing")
helper = gen_marker + """    /** V5.0.6692 — terminalize the exact ownership generation without
     * reconstructing it from a newer mutable token row. This is the missing
     * primitive that lets timeout/supersede cleanup actually release leases. */
    private fun expireInflightGeneration6692(identity: String, generation: String, reason: String): Boolean {
        if (!evaluationInflight6615.remove(identity, generation)) return false
        evaluationInflightStartedAt6692.remove(identity)
        evaluationCompleted6615[identity] = generation
        val terminalKey = "$identity|$generation"
        if (!evaluationTerminalKeys6615.add(terminalKey)) return true
        val key = reason.uppercase().replace(Regex("[^A-Z0-9_]+"), "_").take(72)
        evaluationDisposition6567.computeIfAbsent(key) { AtomicLong(0L) }.incrementAndGet()
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_EVAL_TERMINAL_6567|$key")
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_EVAL_EXACT_OWNER_REAPED_6692")
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "CRYPTO_EVAL_EXACT_OWNER_REAPED_6692",
                "identity=$identity generation=${generation.hashCode()} reason=$key action=release_exact_inflight_owner",
            )
        } catch (_: Throwable) {}
        return true
    }

"""
src = src.replace(gen_marker, helper, 1)
write(registry, src)

old_started = """    fun markEvaluationStarted6567(tok: DynToken): Boolean {
        val identity = tok.canonicalIdentity6544
        val generation = evaluationGeneration6615(tok)
        if (evaluationCompleted6615[identity] == generation) {
            evaluationCoalesced6615.incrementAndGet()
            // V5.0.6626 §RUNTIME_LOOP_UNCHOKE §1 — coalesced hot-label increment.
            try { com.lifecyclebot.engine.truth.HotLabelCoalescer6626.inc6626("CRYPTO_EVAL_GENERATION_COALESCED_6615") } catch (_: Throwable) {}
            return false
        }
        var admitted = false
        evaluationInflight6615.compute(identity) { _, active ->
            when {
                active == generation -> active
                else -> {
                    if (active != null) evaluationSuperseded6615.incrementAndGet()
                    admitted = true
                    generation
                }
            }
        }
        if (!admitted) {
            evaluationCoalesced6615.incrementAndGet()
            return false
        }
        evaluationStarted6567.incrementAndGet()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_EVAL_STARTED_6567") } catch (_: Throwable) {}
        return true
    }
"""
new_started = """    fun markEvaluationStarted6567(tok: DynToken): Boolean {
        val identity = tok.canonicalIdentity6544
        val generation = evaluationGeneration6615(tok)
        val now6692 = System.currentTimeMillis()

        // Reap an owner that exceeded the adaptive evidence deadline even when
        // nobody ever re-stamped its progress state. This closes the one-shot
        // silence leak that could survive for tens of minutes.
        val activeBefore6692 = evaluationInflight6615[identity]
        if (activeBefore6692 != null) {
            val born6692 = evaluationInflightStartedAt6692[identity] ?: now6692
            val expired6692 = now6692 - born6692 > adaptiveEvidenceTtlMs6632()
            if (expired6692) {
                expireInflightGeneration6692(identity, activeBefore6692, "STALE_EXPIRED_INFLIGHT_6692")
            } else if (activeBefore6692 != generation) {
                // A new material generation supersedes the old lease. The old
                // START must receive an explicit terminal before the new START.
                if (expireInflightGeneration6692(identity, activeBefore6692, "SUPERSEDED_BY_NEW_GENERATION_6692")) {
                    evaluationSuperseded6615.incrementAndGet()
                }
            }
        }

        if (evaluationCompleted6615[identity] == generation) {
            evaluationCoalesced6615.incrementAndGet()
            try { com.lifecyclebot.engine.truth.HotLabelCoalescer6626.inc6626("CRYPTO_EVAL_GENERATION_COALESCED_6615") } catch (_: Throwable) {}
            return false
        }
        if (evaluationInflight6615.putIfAbsent(identity, generation) != null) {
            evaluationCoalesced6615.incrementAndGet()
            return false
        }
        evaluationInflightStartedAt6692[identity] = now6692
        evaluationStarted6567.incrementAndGet()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CRYPTO_EVAL_STARTED_6567") } catch (_: Throwable) {}
        return true
    }
"""
replace_once(registry, old_started, new_started, "evaluation owner admission/reap")

replace_once(
    registry,
    """            val progressKey6580 = "${tok.canonicalIdentity6544}|$key"
""",
    """            val progressKey6580 = "${tok.canonicalIdentity6544}$EVAL_PROGRESS_SEPARATOR_6692$key"
""",
    "progress key construction",
)

old_sweep_parse = """                        val split = entry.key.indexOf('|')
                        if (split > 0) {
                            val identity6587 = entry.key.substring(0, split)
                            val state6587 = entry.key.substring(split + 1)
                            val stale6587 = getTokenByCanonicalIdentity6544(identity6587)
                            if (stale6587 != null) markEvaluationDisposition6567(stale6587, "STALE_EXPIRED_6587_$state6587")
                        }
"""
new_sweep_parse = """                        val split = entry.key.lastIndexOf(EVAL_PROGRESS_SEPARATOR_6692)
                        if (split > 0) {
                            val identity6587 = entry.key.substring(0, split)
                            val state6587 = entry.key.substring(split + EVAL_PROGRESS_SEPARATOR_6692.length)
                            val activeGeneration6692 = evaluationInflight6615[identity6587]
                            if (activeGeneration6692 != null) {
                                expireInflightGeneration6692(
                                    identity6587,
                                    activeGeneration6692,
                                    "STALE_EXPIRED_6587_$state6587",
                                )
                            }
                        }
"""
replace_once(registry, old_sweep_parse, new_sweep_parse, "global stale reaper canonical identity parsing")

replace_once(
    registry,
    """        evaluationInflight6615.remove(identity, generation)
        evaluationCompleted6615[identity] = generation
""",
    """        evaluationInflight6615.remove(identity, generation)
        evaluationInflightStartedAt6692.remove(identity)
        evaluationCompleted6615[identity] = generation
""",
    "terminal releases ownership timestamp",
)

replace_once(
    registry,
    """            val oldestInflightAge6615 = evaluationInflight6615.keys.mapNotNull { registry[it]?.lastUpdatedMs }
                .minOrNull()?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) } ?: 0L
""",
    """            val oldestInflightAge6615 = evaluationInflightStartedAt6692.values
                .minOrNull()?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) } ?: 0L
""",
    "queue age uses ownership lease clock",
)

# Move blocking DexScreener price hydration off Dispatchers.Default so a batch
# of unresolved registry rows cannot pin the shared compute pool used by AATE.
trader = "app/src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt"
replace_once(
    trader,
    """                    priceNow = DynamicAltTokenRegistry.refreshPriceForMintBlocking(tok.canonicalIdentity6544)
""",
    """                    priceNow = withContext(Dispatchers.IO) {
                        DynamicAltTokenRegistry.refreshPriceForMintBlocking(tok.canonicalIdentity6544)
                    }
""",
    "dynamic price hydration IO isolation",
)

# Source-contract regression lock.
test_path = ROOT / "app/src/test/kotlin/com/lifecyclebot/engine/Aate6692CryptoBacklogRepairTest.kt"
test_path.write_text(r'''package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate6692CryptoBacklogRepairTest {
    @Test fun `crypto evaluation ownership has a real lease clock and exact-generation reaper`() {
        val src = File("src/main/kotlin/com/lifecyclebot/perps/DynamicAltTokenRegistry.kt").readText()
        assertTrue(src.contains("evaluationInflightStartedAt6692"))
        assertTrue(src.contains("expireInflightGeneration6692"))
        assertTrue(src.contains("STALE_EXPIRED_INFLIGHT_6692"))
        assertTrue(src.contains("SUPERSEDED_BY_NEW_GENERATION_6692"))
        assertTrue(src.contains("CRYPTO_EVAL_EXACT_OWNER_REAPED_6692"))
    }

    @Test fun `progress sweep does not split canonical identity on pipe`() {
        val src = File("src/main/kotlin/com/lifecyclebot/perps/DynamicAltTokenRegistry.kt").readText()
        assertTrue(src.contains("EVAL_PROGRESS_SEPARATOR_6692"))
        assertFalse(src.contains("val split = entry.key.indexOf('|')"))
        assertTrue(src.contains("lastIndexOf(EVAL_PROGRESS_SEPARATOR_6692)"))
    }

    @Test fun `queue age measures inflight ownership not stale token metadata`() {
        val src = File("src/main/kotlin/com/lifecyclebot/perps/DynamicAltTokenRegistry.kt").readText()
        val report = src.substringAfter("fun discoveryReport6544")
        assertTrue(report.contains("evaluationInflightStartedAt6692.values"))
        assertFalse(report.contains("evaluationInflight6615.keys.mapNotNull { registry[it]?.lastUpdatedMs }"))
    }

    @Test fun `blocking dynamic hydration is isolated to IO dispatcher`() {
        val src = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        val scan = src.substringAfter("private suspend fun runDynamicTokenScan")
        val hydrate = scan.substringBefore("val price   = priceNow")
        assertTrue(hydrate.contains("withContext(Dispatchers.IO)"))
        assertTrue(hydrate.contains("refreshPriceForMintBlocking"))
    }
}
''')

# Final anti-rot assertions.
final_registry = read(registry)
final_trader = read(trader)
assert "val split = entry.key.indexOf('|')" not in final_registry
assert "evaluationInflightStartedAt6692.values" in final_registry
assert "expireInflightGeneration6692" in final_registry
assert "withContext(Dispatchers.IO)" in final_trader[final_trader.index("private suspend fun runDynamicTokenScan"):]
print("V5.0.6692 Crypto Universe backlog repair transforms complete")
