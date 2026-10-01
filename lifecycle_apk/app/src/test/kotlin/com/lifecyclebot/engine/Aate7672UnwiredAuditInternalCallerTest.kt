package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7672UnwiredAuditInternalCallerTest {
    @Test fun generatorClassifiesSameFileConsumersAsInfileWired() {
        val s = File("../../../ci/triage_unwired.py").readText()
        assertTrue(s.contains("internal_calls=max(0"))
        assertTrue(s.contains("if internal_calls>0: tier=\"E_INFILE\""))
    }

    @Test fun recheckHasExplicitInfileWiredVerdict() {
        val s = File("../../ci/ledger_recheck_7072.py").readText()
        assertTrue(s.contains("INFILE_WIRED"))
        assertTrue(s.contains("remainder = txt[:dm.start()] + txt[dm.end():]"))
    }

    @Test fun knownFalsePositivesReallyHaveSameFileConsumers() {
        val aem = File("src/main/kotlin/com/lifecyclebot/v3/scoring/AdvancedExitManager.kt").readText()
        assertTrue(aem.indexOf("fun calculateTimePressure(") >= 0)
        assertTrue(aem.substringAfter("fun calculateTimePressure(").contains("calculateTimePressure("))

        val strat = File("src/main/kotlin/com/lifecyclebot/perps/strategy/CryptoAltStrategy.kt").readText()
        assertTrue(strat.substringAfter("fun decide(").contains("classifyVolRegime("))
        assertTrue(strat.substringAfter("fun decide(").contains("classifyBtcRegime("))

        val state = File("src/main/kotlin/com/lifecyclebot/perps/crypto/brain/CryptoBrainState.kt").readText()
        assertTrue(state.substringAfter("fun save()").substringBefore("fun saveNow()").contains("saveNow()"))
    }
}
