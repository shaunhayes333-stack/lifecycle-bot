package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AdvisorDecisionHistory6463
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8353 — minLiquidityUsd / walletReserveSol are owner-only. The 8351 run auto-applied
 * minLiquidityUsd 8000→10000 while arguing that liquidity blocks were preventing trades.
 * Neither advisor may write these keys; a proposal naming them is an advisory-only row; the
 * pre-advisor value is restored once on the advisor's first tick when a prior is recorded.
 */
class Aate8353AdvisorKeysTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    private fun decision(key: String, action: AdvisorDecisionHistory6463.Action, old: Double, new: Double) =
        AdvisorDecisionHistory6463.Decision(
            atMs = 1L, key = key, delta = new - old, severity = "high", source = "llm", action = action,
            brainAgreement = 0.7, votes = emptyList(), reason = "t", oldValue = old, newValue = new,
        )

    @Test fun ownerOnlyKeysAreOutOfTheAllowlist() {
        for (k in AdvisorKeyAuthority8353.ADVISORY_ONLY_KEYS_8353) {
            assertFalse("$k must not be tunable", LlmParameterTuner.isAllowedKey(k))
            assertFalse("$k must not be offered to the LLM", LlmParameterTuner.allowedKeys().contains(k))
        }
        // superseded by V5.0.8363 — slippageBps is advisory-only too (it fed partial-TP / orphan sells with a forced 0.75 agreement)
        assertEquals(listOf("minLiquidityUsd", "walletReserveSol", "slippageBps"), AdvisorKeyAuthority8353.ADVISORY_ONLY_KEYS_8353)
        val tuner = src("engine/LlmParameterTuner.kt")
        assertFalse(tuner.contains("\"minLiquidityUsd\" to ParamSpec"))
        assertFalse(tuner.contains("\"walletReserveSol\" to ParamSpec"))
        // every other key stays
        // superseded by V5.0.8363 — slippageBps left the allowlist (advisory-only); every other key stays.
        for (k in listOf("stopLossPct", "trailingStopBasePct", "exitScoreThreshold", "entryCooldownSec", "pollSeconds",
            "minHoldMins", "maxHoldMinsHard", "sentimentEntryBoost", "sentimentExitBoost",
            "sentimentBlockThreshold", "behaviorAggressionLevel", "defensiveLossThreshold", "aggressiveWhaleThreshold",
            "convictionMult1", "convictionMult2", "partialSellTriggerPct", "topUpSizeMultiplier", "topUpMaxCount",
            "scanIntervalSecs")) {
            assertTrue("$k must stay tunable", LlmParameterTuner.isAllowedKey(k))
        }
        assertEquals(19, LlmParameterTuner.allowedKeys().size)  // superseded by V5.0.8363 — 21 less slippageBps
        val gemini = src("engine/GeminiCopilot.kt")
        assertFalse(gemini.contains("minLiquidityUsd, walletReserveSol, scanIntervalSecs"))
        assertTrue(gemini.contains("minLiquidityUsd and walletReserveSol are owner-only (V5.0.8353): never put them in a TUNE block."))
    }

    @Test fun proposalOnOwnerKeyIsAdvisoryOnly() {
        assertEquals("ADVISOR_KEY_ADVISORY_ONLY_8353_minLiquidityUsd", AdvisorKeyAuthority8353.advisoryOnlyLabel8353("minLiquidityUsd"))
        assertEquals("ADVISOR_KEY_ADVISORY_ONLY_8353_walletReserveSol", AdvisorKeyAuthority8353.advisoryOnlyLabel8353(" walletReserveSol "))
        assertNull(AdvisorKeyAuthority8353.advisoryOnlyLabel8353("exitScoreThreshold"))
        assertTrue(AdvisorKeyAuthority8353.isAdvisoryOnly8353("minLiquidityUsd"))
        assertFalse(AdvisorKeyAuthority8353.isAdvisoryOnly8353("minDiscoveryScore"))
        assertFalse("non-functional discovery score must not be auto-tunable", LlmParameterTuner.isAllowedKey("minDiscoveryScore"))
        // both advisors keep the row but never hand it to the tuner
        val self = src("engine/SelfHealingAdvisor.kt")
        assertTrue(self.contains("val advisoryOnly8353 = AdvisorKeyAuthority8353.isAdvisoryOnly8353(key)"))
        assertTrue(self.contains("if (!advisoryOnly8353 && !LlmParameterTuner.isAllowedKey(key)) continue"))
        assertTrue(self.contains("if (AdvisorKeyAuthority8353.isAdvisoryOnly8353(s.key)) continue  // V5.0.8353"))
        assertTrue(self.contains("minLiquidityUsd and walletReserveSol are owner-only (V5.0.8353)"))
        val auto = src("engine/truth/AutoPipelineAdvisor6462.kt")
        assertTrue(auto.contains("if (!advisoryOnly8353 && !LlmParameterTuner.isAllowedKey(key)) continue"))
        val gate = auto.substringAfter("for (c in enriched.sortedByDescending { it.brainAgreement })")
        assertTrue(gate.indexOf("if (AdvisorKeyAuthority8353.isAdvisoryOnly8353(c.key)) {") < gate.indexOf("if (!causalDomainAllowsAutoApply6569(c)) {"))
        assertTrue(gate.indexOf("if (AdvisorKeyAuthority8353.isAdvisoryOnly8353(c.key)) {") < gate.indexOf("val applyRes = applyOne(ctx, c)"))
    }

    @Test fun restoreDecisionPriorPresentRestoresOnce() {
        val d = AdvisorKeyAuthority8353.decideRestore8353("minLiquidityUsd", currentValue = 10000.0, priorValue = 8000.0, appliedValue = 10000.0, alreadyDone = false)
        assertEquals(AdvisorKeyAuthority8353.RestoreKind8353.RESTORE, d.kind)
        assertEquals(10000.0, d.from, 0.0)
        assertEquals(8000.0, d.to, 0.0)
        // second boot: remembered, no repeat
        val again = AdvisorKeyAuthority8353.decideRestore8353("minLiquidityUsd", 8000.0, 8000.0, 10000.0, alreadyDone = true)
        assertEquals(AdvisorKeyAuthority8353.RestoreKind8353.ALREADY_DONE, again.kind)
        // owner changed it after the advisor: left alone
        val owner = AdvisorKeyAuthority8353.decideRestore8353("minLiquidityUsd", 6000.0, 8000.0, 10000.0, alreadyDone = false)
        assertEquals(AdvisorKeyAuthority8353.RestoreKind8353.OWNER_CHANGED, owner.kind)
    }

    @Test fun restoreDecisionNoPriorIsNoop() {
        val d = AdvisorKeyAuthority8353.decideRestore8353("walletReserveSol", 0.05, priorValue = null, appliedValue = null, alreadyDone = false)
        assertEquals(AdvisorKeyAuthority8353.RestoreKind8353.NO_PRIOR, d.kind)
        assertEquals(d.from, d.to, 0.0)
        assertNull(AdvisorKeyAuthority8353.priorFor8353("walletReserveSol", emptyList(), emptyList()))
        val a = src("engine/AdvisorKeyAuthority8353.kt")
        assertTrue(a.contains("PipelineHealthCollector.labelInc(\"ADVISOR_RESTORE_NO_PRIOR_8353\")"))
        assertTrue(a.contains("\"ADVISOR_RESTORED_8353_\${key}_\${fmt8353(decision.from)}_\${fmt8353(decision.to)}\""))
        assertTrue(a.contains("prefs.edit().putBoolean(\"restored_\" + key, true).putInt(PREF_COUNT_8353, n.toInt()).apply()"))
    }

    @Test fun priorIsReadFromTimelineThenPersistedTunerLine() {
        val line = "🎛️ LLM tune: minLiquidityUsd 8000 → 10000 (self-healing:high:restrictive liquidity blocks)"
        val rec = AdvisorKeyAuthority8353.parseTuneLine8353(line)
        assertNotNull(rec)
        assertEquals("minLiquidityUsd", rec!!.key)
        assertEquals(8000.0, rec.oldValue, 0.0)
        assertEquals(10000.0, rec.newValue, 0.0)
        assertNull(AdvisorKeyAuthority8353.parseTuneLine8353("🔒 TUNE block rejected"))
        // tuner log, newest first: the newest record for the key wins
        val fromLog = AdvisorKeyAuthority8353.priorFor8353("minLiquidityUsd", emptyList(),
            listOf("🎛️ LLM tune: exitScoreThreshold 60 → 58 (x)", line, "🎛️ LLM tune: minLiquidityUsd 5000 → 8000 (y)"))
        assertEquals(8000.0, fromLog!!.oldValue, 0.0)
        // in-memory timeline wins over the log; a non-applied row does not count
        val fromTimeline = AdvisorKeyAuthority8353.priorFor8353("walletReserveSol",
            listOf(decision("walletReserveSol", AdvisorDecisionHistory6463.Action.LOW_AGREEMENT, 0.05, 0.3),
                   decision("walletReserveSol", AdvisorDecisionHistory6463.Action.AUTO_APPLIED, 0.05, 0.3)),
            listOf("🎛️ LLM tune: walletReserveSol 0.1 → 0.35 (z)"))
        assertEquals(0.05, fromTimeline!!.oldValue, 1e-9)
        assertEquals(0.3, fromTimeline.newValue, 1e-9)
    }

    @Test fun restoreHooksFromAdvisorTickInitAndStatusLineShowsIt() {
        val auto = src("engine/truth/AutoPipelineAdvisor6462.kt")
        val tick = auto.substringAfter("private fun runTick(ctx: Context) {").substringBefore("val rules = deriveRulesCandidates()")
        assertTrue(tick.contains("repairReplayDrivenStrategyMutation6569(ctx)"))
        assertTrue(tick.contains("try { AdvisorKeyAuthority8353.restoreOnce8353(ctx) } catch (_: Throwable) {}"))
        assertTrue(auto.contains("AdvisorKeyAuthority8353.statusSuffix8353()  // V5.0.8353"))
        // superseded by V5.0.8363 — slippageBps joined the owner-only keys
        assertTrue(AdvisorKeyAuthority8353.statusSuffix8353().startsWith("advisoryOnly=[minLiquidityUsd,walletReserveSol,slippageBps] restored8353="))
        // the restore reads the persisted tuner lines (ErrorLogger.init runs after createChannels, so the hook is the advisor's own init)
        val a = src("engine/AdvisorKeyAuthority8353.kt")
        assertTrue(a.contains("ErrorLogger.getLogsByComponent(TUNER_COMPONENT_8353, TUNE_LOG_SCAN_8353)"))
        assertTrue(a.contains("private const val TUNER_COMPONENT_8353 = \"LlmTuner\""))
        assertTrue(src("engine/LlmParameterTuner.kt").contains("\"🎛️ LLM tune: \${c.key} \${fmt(c.oldValue)} → \${fmt(c.newValue)} (\${c.reason})\""))
    }
}
