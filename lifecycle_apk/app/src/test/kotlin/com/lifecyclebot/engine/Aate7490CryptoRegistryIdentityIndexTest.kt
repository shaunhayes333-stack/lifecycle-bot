package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7490CryptoRegistryIdentityIndexTest {
    private fun src() =
        File("src/main/kotlin/com/lifecyclebot/perps/DynamicAltTokenRegistry.kt").readText()

    @Test fun stale_registry_eviction_deindexes_symbol_identity() {
        val s = src()
        assertTrue(s.contains("private fun deindexSymbol7490"))
        val eviction = s.substringAfter("registry.entries.removeIf").substringBefore("if (freshEvicted6547 > 0)")
        assertTrue(eviction.contains("deindexSymbol7490(tok.symbol, tok.canonicalIdentity6544)"))
    }

    @Test fun restore_uses_canonical_chain_token_key() {
        val s = src()
        val restore = s.substringAfter("private fun restoreFromDisk").substringBefore("Background discovery scheduler")
        assertTrue(restore.contains("registry[restoredKey6544] = tok"))
        assertTrue(restore.contains("indexSymbol6493(tok.symbol, restoredKey6544)"))
        assertFalse(restore.contains("registry[mint] = tok"))
    }

    @Test fun symbol_execution_lookup_stays_ambiguity_safe() {
        val s = src()
        assertTrue(s.contains("fun getUniqueExecutableTokenBySymbol6493"))
        assertTrue(s.contains("return candidates.singleOrNull()"))
    }
}
