package com.lifecyclebot.engine

import android.content.Context
import com.lifecyclebot.AATEApp
import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.ConfigStore

/**
 * V5.0.6685 — canonical runtime provider authority.
 *
 * V5.0.6637 correctly removed production credentials from source/APK, but
 * several later/older wallet and execution paths still constructed Helius and
 * Alchemy URLs from DefaultKeys (now intentionally blank). This class makes the
 * encrypted operator configuration the only credentialed runtime authority.
 *
 * Explicit RPC -> saved RPC -> configured Helius -> keyless public fallbacks.
 * No secret is logged or persisted here.
 */
object RuntimeProviderAuthority6685 {
    const val VERSION = "V5.0.6685_RUNTIME_PROVIDER_AUTHORITY"

    // V5.0.7381 — only the keyless endpoints that still answer JSON-RPC. The list
    // carried ten that do not: ankr (API key required), the three rpcpool/Triton
    // hosts (token required: 403 / timeout), chainstack "/1" (401), extrnode and
    // omniatech (shut down), blastapi and blockpi public (retired / key required),
    // and jito (a block engine, not a general RPC); drpc since 7382. Every ladder walk burned its
    // budget on them before reaching an endpoint that works.
    val PUBLIC_SOLANA_RPCS: List<String> = listOf(
        "https://solana-rpc.publicnode.com",
        "https://api.mainnet-beta.solana.com",
    )

    // V5.0.7382 — drpc added: its keyless tier answered 0 of 14 calls with 4xx on 5.0.7381.
    private val RETIRED_KEYLESS_RPCS_7381: List<String> = listOf(
        "https://solana.drpc.org",
        "https://rpc.ankr.com/solana",
        "https://api.mainnet.rpcpool.com",
        "https://solana-mainnet.core.chainstack.com/1",
        "https://free.rpcpool.com",
        "https://mainnet.rpcpool.com",
        "https://solana-mainnet.rpc.extrnode.com",
        "https://solana-mainnet.public.blastapi.io",
        "https://solana.blockpi.network/v1/rpc/public",
        "https://endpoints.omniatech.io/v1/sol/mainnet/public",
        "https://mainnet.rpc.jito.wtf",
    )

    private fun context(explicit: Context? = null): Context? =
        explicit?.applicationContext ?: AATEApp.appContextOrNull()

    private fun config(explicit: Context? = null): BotConfig? = try {
        context(explicit)?.let { ConfigStore.load(it) }
    } catch (_: Throwable) { null }

    fun sanitizeRpc(raw: String?): String {
        val v = raw?.trim().orEmpty()
        if (v.isBlank()) return ""
        if (!v.startsWith("https://", ignoreCase = true)) return ""
        if (v.contains("solana.public-rpc.com", ignoreCase = true)) return ""
        // V5.0.7381 — a saved RPC field holding one of the retired keyless
        // endpoints would otherwise lead the ladder ahead of Helius.
        if (RETIRED_KEYLESS_RPCS_7381.any { v.trimEnd('/').equals(it, ignoreCase = true) }) return ""
        if (v.contains("mainnet.helius-rpc.com", ignoreCase = true) &&
            (v.endsWith("api-key=") || v.contains("api-key=hive-pattern-learn"))) return ""
        return v
    }

    fun configuredHeliusKey(explicit: Context? = null): String =
        config(explicit)?.heliusApiKey?.trim().orEmpty()

    fun configuredHeliusRpc(explicit: Context? = null): String {
        val key = configuredHeliusKey(explicit)
        return if (key.isBlank()) "" else "https://mainnet.helius-rpc.com/?api-key=$key"
    }

    fun rpcCandidates(primary: String? = null, explicit: Context? = null): List<String> {
        val cfg = config(explicit)
        val ordered = LinkedHashSet<String>()
        fun add(raw: String?) {
            val clean = sanitizeRpc(raw)
            if (clean.isNotBlank()) ordered.add(clean)
        }
        add(primary)
        // V5.0.7277 §THE PAID KEY WAS SECOND IN LINE BEHIND THE FREE ENDPOINT.
        //
        // SettingsBottomSheet saves a blank RPC field as the public
        // api.mainnet-beta.solana.com, and this ladder put cfg.rpcUrl ahead of
        // the configured Helius endpoint. Every consumer that walks the ladder
        // in order (JupiterApi, WalletManager, SolanaWallet, the pump-curve
        // reader, the supply reader) therefore asked the anonymous public node
        // first and the operator's paid Helius key second — which is what
        // LivePreflight has been printing as RPC_LADDER_HEAD REFUSE for a
        // dozen builds while the same snapshot showed Helius at 99%. A saved
        // RPC that is one of the keyless public endpoints is a fallback, not a
        // preference: the authenticated endpoint leads it.
        val savedRpc7277 = sanitizeRpc(cfg?.rpcUrl)
        val savedIsPublic7277 = savedRpc7277.isNotBlank() &&
            PUBLIC_SOLANA_RPCS.any { sanitizeRpc(it).equals(savedRpc7277, ignoreCase = true) }
        if (savedIsPublic7277) {
            add(configuredHeliusRpc(explicit))
            add(savedRpc7277)
            try { PipelineHealthCollector.labelInc("RPC_LADDER_HELIUS_LEADS_PUBLIC_SAVED_RPC_7277") } catch (_: Throwable) {}
        } else {
            add(cfg?.rpcUrl)
            add(configuredHeliusRpc(explicit))
        }
        PUBLIC_SOLANA_RPCS.forEach(::add)
        return ordered.toList()
    }

    fun preferredRpc(primary: String? = null, explicit: Context? = null): String =
        rpcCandidates(primary, explicit).firstOrNull().orEmpty()

    fun statusLine(explicit: Context? = null): String {
        val cfg = config(explicit)
        val helius = if (cfg?.heliusApiKey?.isNotBlank() == true) "configured" else "missing"
        val explicitRpc = if (sanitizeRpc(cfg?.rpcUrl).isNotBlank()) "configured" else "none"
        return "$VERSION helius=$helius explicitRpc=$explicitRpc publicFallbacks=${PUBLIC_SOLANA_RPCS.size}"
    }
}
