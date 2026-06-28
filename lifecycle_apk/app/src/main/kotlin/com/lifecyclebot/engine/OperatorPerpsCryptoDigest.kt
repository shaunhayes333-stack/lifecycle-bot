package com.lifecyclebot.engine

/**
 * V5.0.4373 — compact digest for perps/crypto brain status surfaces. Report-only.
 * Inventory names intentionally covered: CryptoFunnel.
 */
object OperatorPerpsCryptoDigest {
    fun status(): String {
        val fluid = try { com.lifecyclebot.perps.crypto.brain.CryptoFluidLearning.summary().take(140) } catch (_: Throwable) { "CryptoFluidLearning unavailable" }
        val tactics = try { com.lifecyclebot.perps.crypto.brain.CryptoTacticSwitcher.summary().take(140) } catch (_: Throwable) { "CryptoTacticSwitcher unavailable" }
        val funnel = try { com.lifecyclebot.perps.crypto.brain.CryptoFunnel.summary().take(140) } catch (_: Throwable) { "CryptoFunnel unavailable" }
        val behavior = try { com.lifecyclebot.perps.crypto.brain.CryptoBehavior.summary().take(140) } catch (_: Throwable) { "CryptoBehavior unavailable" }
        val brain = try { com.lifecyclebot.perps.crypto.brain.CryptoBrain.summary().take(140) } catch (_: Throwable) { "CryptoBrain unavailable" }
        val losing = try { com.lifecyclebot.perps.crypto.brain.CryptoLosingPatternMemory.summary().take(140) } catch (_: Throwable) { "CryptoLosingPatternMemory unavailable" }
        val canonical = try { com.lifecyclebot.perps.crypto.brain.CryptoCanonicalLearning.summary().take(140) } catch (_: Throwable) { "CryptoCanonicalLearning unavailable" }
        val exitTuner = try { com.lifecyclebot.perps.crypto.brain.CryptoLaneExitTuner.summary().take(140) } catch (_: Throwable) { "CryptoLaneExitTuner unavailable" }
        val laneTimeout = "CryptoLaneTimeoutGate status requires lane argument"
        return "OPERATOR_PERPS_CRYPTO_DIGEST_4373 fluid=[$fluid] tactics=[$tactics] funnel=[$funnel] behavior=[$behavior] brain=[$brain] losing=[$losing] canonical=[$canonical] exitTuner=[$exitTuner] laneTimeout=[$laneTimeout] report_only=true no_crypto_gate_change=true no_execution_authority=true"
    }
}
