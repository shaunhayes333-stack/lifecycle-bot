package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7427ExactStrategyIdentityTest {
    @Test fun entrySnapshotCarriesSeparateStrategyNamespaces() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/EntryStrategySnapshot6450.kt").readText()
        listOf(
            "entryTradeType", "entrySetup", "entryStyle",
            "entryEntryStyle", "entryExitStyle", "entryStrategyVariantId"
        ).forEach { assertTrue("missing $it", s.contains("val $it: String")) }
        assertTrue(s.contains("tradeType7427"))
        assertTrue(s.contains("variant7427"))
    }

    @Test fun paperAndLiveNeverStoreToolkitEntryStyleAsCoarseTactic() {
        val e = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertFalse(e.contains("entryTactic = entryDeskHypothesis6599?.entryStyle"))
        assertFalse(e.contains("entryTactic = liveDeskHypothesis6599?.entryStyle"))
        assertTrue(e.contains("entryTactic = policyField6568(paperPolicySnapshot, \"entryTactic\")"))
        assertTrue(e.contains("entryTactic = policyField6568(ts.position.entryPolicySnapshot, \"entryTactic\")"))
        assertTrue(e.contains("tradeType7427="))
        assertTrue(e.contains("setup7427="))
        assertTrue(e.contains("entryStrategyVariantId = com.lifecyclebot.engine.StrategyHypothesisEngine.pendingStrategyVariantId7427"))
    }

    @Test fun terminalBusCarriesExactEntryIdentity() {
        val bus = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalFinalizedTradeBus6464.kt").readText()
        val rich = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalTradeFinalizedBus6450.kt").readText()
        assertTrue(bus.contains("val entryTradeType: String"))
        assertTrue(bus.contains("val entrySetup: String"))
        assertTrue(bus.contains("val entryStyle: String"))
        assertTrue(bus.contains("val entryStrategyVariantId: String"))
        assertTrue(rich.contains("entryTradeType = entrySnap6567?.entryTradeType"))
        assertTrue(rich.contains("entryStrategyVariantId = entrySnap6567?.entryStrategyVariantId"))
    }

    @Test fun variantOutcomeUsesEntryStampedVariantNotCloseTimeActiveVariant() {
        val h = File("src/main/kotlin/com/lifecyclebot/engine/StrategyHypothesisEngine.kt").readText()
        assertTrue(h.contains("pendingStrategyVariant7427[mint] = v.id"))
        assertTrue(h.contains("val stampedVariantId7427 = pendingStrategyVariant7427.remove(mint)"))
        assertTrue(h.contains("StrategyVariantStore.recordOutcome(\n                        stampedVariantId7427"))
        val outcomeRegion = h.substringAfter("val stampedVariantId7427 = pendingStrategyVariant7427.remove(mint)")
            .substringBefore("maybeResolve(ctx, h)")
        assertFalse(outcomeRegion.contains("StrategyVariantStore.activeFor("))
    }

    @Test fun causalReportNamesExactStrategyIdentity() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/EntryStrategySnapshot6450.kt").readText()
        assertTrue(s.contains("type=${x.groupingBy{it.tradeType}"))
        assertTrue(s.contains("setup=${x.groupingBy{it.setup}"))
        assertTrue(s.contains("style=${x.groupingBy{it.style}"))
        assertTrue(s.contains("variant=${x.groupingBy{it.variantId}"))
    }
}
