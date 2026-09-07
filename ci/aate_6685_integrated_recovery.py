from pathlib import Path
import re

ROOT = Path("lifecycle_apk")
MAIN = ROOT / "app/src/main/kotlin/com/lifecyclebot"
TEST = ROOT / "app/src/test/kotlin/com/lifecyclebot/engine"


def read(path: Path) -> str:
    return path.read_text()


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"6685 missing anchor: {label}")
    return text.replace(old, new, 1)


def replace_between(text: str, start: str, end: str, replacement: str, label: str) -> str:
    a = text.find(start)
    if a < 0:
        raise SystemExit(f"6685 start anchor missing: {label}")
    b = text.find(end, a + len(start))
    if b < 0:
        raise SystemExit(f"6685 end anchor missing: {label}")
    return text[:a] + replacement + text[b:]


# ═════════════════════════════════════════════════════════════════════════════
# 1) ONE runtime provider authority. 6637 intentionally removed APK-bundled
#    credentials; all consumers must resolve the operator's encrypted config.
# ═════════════════════════════════════════════════════════════════════════════
provider_path = MAIN / "engine/RuntimeProviderAuthority6685.kt"
write(provider_path, r'''package com.lifecyclebot.engine

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

    val PUBLIC_SOLANA_RPCS: List<String> = listOf(
        "https://api.mainnet-beta.solana.com",
        "https://rpc.ankr.com/solana",
        "https://solana-rpc.publicnode.com",
        "https://solana.drpc.org",
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
        add(cfg?.rpcUrl)
        add(configuredHeliusRpc(explicit))
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
''')


# ═════════════════════════════════════════════════════════════════════════════
# 2) WalletManager: remove dead DefaultKeys endpoints and nested failover.
#    Signer validation, endpoint health and SOL/USD refresh are separate domains.
# ═════════════════════════════════════════════════════════════════════════════
wm_path = MAIN / "engine/WalletManager.kt"
wm = read(wm_path)

fallback_start = "        val FALLBACK_RPCS: List<String> get() = listOf("
fallback_end = "        \n\n        private fun sanitizeWalletRpcUrl(raw: String): String {"
if fallback_start in wm:
    wm = replace_between(
        wm,
        fallback_start,
        fallback_end,
        """        // V5.0.6685 — compatibility surface only. Credentialed providers
        // are resolved from encrypted runtime config by RuntimeProviderAuthority6685.
        // Never manufacture Helius/Alchemy URLs from blank DefaultKeys.
        val FALLBACK_RPCS: List<String> get() = RuntimeProviderAuthority6685.PUBLIC_SOLANA_RPCS

""",
        "WalletManager stale fallback fleet",
    )

wm = wm.replace(
    "val savedRpc = sanitizeWalletRpcUrl(config.rpcUrl).ifBlank { FALLBACK_RPCS.first() }",
    "val savedRpc = RuntimeProviderAuthority6685.preferredRpc(config.rpcUrl, instance.ctx)",
)
wm = wm.replace(
    "val chosenRpc = sanitizeWalletRpcUrl(rpcUrl).ifBlank { FALLBACK_RPCS.first() }",
    "val chosenRpc = RuntimeProviderAuthority6685.preferredRpc(rpcUrl, ctx)",
)

connect_start = "    fun connect(privateKeyB58: String, rpcUrl: String): Boolean {"
connect_end = "    fun disconnect() {"
new_connect = r'''    fun connect(privateKeyB58: String, rpcUrl: String): Boolean {
        ErrorLogger.info("Wallet", "connect() called; resolving runtime RPC authority")
        val previousWallet = wallet
        val previousState = _state.value
        _state.value = previousState.copy(
            connectionState = WalletConnectionState.CONNECTING,
            errorMessage = "",
        )

        if (privateKeyB58.isBlank()) {
            val msg = "Private key is empty"
            ErrorLogger.warn("Wallet", msg)
            _state.value = if (previousWallet != null) previousState.copy(errorMessage = msg)
                else WalletState(connectionState = WalletConnectionState.ERROR, errorMessage = msg)
            return false
        }

        val rpcsToTry = RuntimeProviderAuthority6685.rpcCandidates(rpcUrl, ctx)
        if (rpcsToTry.isEmpty()) {
            val msg = "No usable Solana RPC endpoints configured"
            _state.value = if (previousWallet != null) previousState.copy(errorMessage = msg)
                else WalletState(connectionState = WalletConnectionState.ERROR, errorMessage = msg)
            return false
        }

        // V5.0.6685 — validate signer exactly once, outside all network/price
        // try/catch blocks. An RPC/price IllegalArgumentException can never be
        // misreported as an invalid private key, and a mistyped replacement key
        // never destroys an already-connected funded wallet.
        val pubkey = try {
            SolanaWallet(privateKeyB58, rpcsToTry.first()).publicKeyB58
        } catch (e: IllegalArgumentException) {
            ErrorLogger.warn("Wallet", "Signer validation failed: ${e.message}")
            val msg = "Invalid private key format"
            _state.value = if (previousWallet != null) previousState.copy(errorMessage = msg)
                else WalletState(connectionState = WalletConnectionState.ERROR, errorMessage = msg)
            return false
        } catch (e: Throwable) {
            val msg = "Wallet signer initialization failed: ${e.message ?: e.javaClass.simpleName}"
            ErrorLogger.error("Wallet", msg, e)
            _state.value = if (previousWallet != null) previousState.copy(errorMessage = msg)
                else WalletState(connectionState = WalletConnectionState.ERROR, errorMessage = msg)
            return false
        }

        var lastError = "Unknown RPC error"
        for (tryRpc in rpcsToTry) {
            try {
                val candidate = SolanaWallet(privateKeyB58, tryRpc)
                // One endpoint, one probe. WalletManager owns connect-time failover;
                // SolanaWallet must not recursively walk the whole fallback fleet.
                val testBalance = candidate.getSolBalancePrimaryOnly6685()

                currentRpcUrl = tryRpc
                wallet = candidate

                // Price discovery is presentation/accounting enrichment, not signer
                // connectivity. Never disconnect a valid wallet because CoinGecko/
                // another SOL/USD source is temporarily unavailable.
                var solPrice = lastKnownSolPrice.takeIf { it in 50.0..1000.0 } ?: 0.0
                try {
                    val fresh = fetchSolPrice()
                    if (fresh in 50.0..1000.0) {
                        solPrice = fresh
                        lastKnownSolPrice = fresh
                    }
                } catch (priceErr: Throwable) {
                    ErrorLogger.debug("Wallet", "SOL price refresh non-fatal: ${priceErr.message}")
                }

                _state.value = previousState.copy(
                    connectionState = WalletConnectionState.CONNECTED,
                    publicKey = pubkey,
                    solBalance = testBalance,
                    balanceUsd = testBalance * solPrice,
                    solPriceUsd = solPrice,
                    lastRefreshed = System.currentTimeMillis(),
                    errorMessage = "",
                )
                try {
                    ForensicLogger.lifecycle(
                        "WALLET_CONNECTED_RUNTIME_RPC_6685",
                        "pubkey=${pubkey.take(12)} provider=${if (tryRpc.contains("helius", true)) "helius" else "fallback"} balanceRead=true",
                    )
                    PipelineHealthCollector.labelInc("WALLET_CONNECTED_RUNTIME_RPC_6685")
                } catch (_: Throwable) {}
                try { TreasuryManager.handleWalletChange(ctx, pubkey) }
                catch (e: Throwable) { ErrorLogger.warn("Wallet", "treasury wallet-change hook failed: ${e.message}") }
                return true
            } catch (e: Throwable) {
                lastError = e.message ?: e.javaClass.simpleName
                ErrorLogger.warn(
                    "Wallet",
                    "RPC connect probe failed provider=${if (tryRpc.contains("helius", true)) "helius" else "fallback"}: ${lastError.take(120)}",
                )
            }
        }

        val msg = "All ${rpcsToTry.size} RPC endpoints failed: ${lastError.take(120)}"
        ErrorLogger.error("Wallet", msg + if (previousWallet != null) " — preserving previous wallet" else "")
        _state.value = if (previousWallet != null) previousState.copy(errorMessage = msg)
            else WalletState(connectionState = WalletConnectionState.ERROR, errorMessage = msg)
        return false
    }

'''
wm = replace_between(wm, connect_start, connect_end, new_connect, "WalletManager connect authority")
write(wm_path, wm)


# ═════════════════════════════════════════════════════════════════════════════
# 3) SolanaWallet: primary-only connection probe and runtime fallback resolver.
# ═════════════════════════════════════════════════════════════════════════════
sw_path = MAIN / "network/SolanaWallet.kt"
sw = read(sw_path)

balance_anchor = "    // ── sign + broadcast ───────────────────────────────────"
primary_probe = r'''    /**
     * V5.0.6685 — connect-time primary-only balance probe.
     * WalletManager owns endpoint failover. This method deliberately performs
     * exactly one JSON-RPC call to this wallet's rpcUrl so Connect cannot create
     * an outer-RPC × inner-RPC × retry explosion.
     */
    fun getSolBalancePrimaryOnly6685(): Double {
        if (android.os.Looper.myLooper() === android.os.Looper.getMainLooper()) {
            throw IllegalStateException("SolanaWallet.getSolBalancePrimaryOnly6685 called from Dispatchers.Main")
        }
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", idGen.getAndIncrement())
            .put("method", "getBalance")
            .put("params", JSONArray().put(publicKeyB58))
        val req = Request.Builder().url(rpcUrl)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MT))
            .build()
        val text = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("RPC HTTP ${resp.code}")
            resp.body?.string() ?: throw RuntimeException("RPC empty response")
        }
        val json = JSONObject(text)
        val err = json.optJSONObject("error")
        if (err != null) throw RuntimeException("RPC error: ${err.optString("message", "unknown")}")
        val result = json.optJSONObject("result") ?: throw RuntimeException("RPC missing result")
        val lamports = result.optLong("value", Long.MIN_VALUE)
        if (lamports == Long.MIN_VALUE) throw RuntimeException("RPC missing result.value")
        return lamports / 1_000_000_000.0
    }

'''
if "getSolBalancePrimaryOnly6685" not in sw:
    sw = replace_once(sw, balance_anchor, primary_probe + balance_anchor, "SolanaWallet primary-only probe")

old_fallback = r'''        // V5.7.8: Try primary RPC first, then ALL fallback RPCs
        val rpcsToTry = mutableListOf(rpcUrl)
        com.lifecyclebot.engine.WalletManager.FALLBACK_RPCS.forEach { fallback ->
            if (fallback != rpcUrl && fallback !in rpcsToTry) rpcsToTry.add(fallback)
        }
'''
new_fallback = r'''        // V5.0.6685 — one runtime provider authority. Explicit wallet RPC
        // stays first; configured encrypted Helius precedes keyless fallbacks.
        val rpcsToTry = com.lifecyclebot.engine.RuntimeProviderAuthority6685.rpcCandidates(rpcUrl)
'''
if "RuntimeProviderAuthority6685.rpcCandidates(rpcUrl)" not in sw:
    sw = replace_once(sw, old_fallback, new_fallback, "SolanaWallet runtime fallback")

sw = sw.replace(
    'val apiKey = try { com.lifecyclebot.data.DefaultKeys.HELIUS } catch (_: Throwable) { "" }',
    'val apiKey = com.lifecyclebot.engine.RuntimeProviderAuthority6685.configuredHeliusKey()',
)
write(sw_path, sw)


# ═════════════════════════════════════════════════════════════════════════════
# 4) Treasury wallet and Jupiter preflight consume the same runtime Helius key.
# ═════════════════════════════════════════════════════════════════════════════
tw_path = MAIN / "engine/TreasuryWalletManager.kt"
tw = read(tw_path)
tw = tw.replace(
    '''        val rpc = cfg.rpcUrl.ifBlank {
            // Fall back to the same Helius free endpoint WalletManager defaults to.
            "https://mainnet.helius-rpc.com/?api-key=${com.lifecyclebot.data.DefaultKeys.HELIUS}"
        }''',
    '''        val rpc = RuntimeProviderAuthority6685.preferredRpc(cfg.rpcUrl, ctx)''',
)
tw = tw.replace(
    '''            val rpc = ConfigStore.load(ctx).rpcUrl.ifBlank {
                "https://mainnet.helius-rpc.com/?api-key=${com.lifecyclebot.data.DefaultKeys.HELIUS}"
            }''',
    '''            val rpc = RuntimeProviderAuthority6685.preferredRpc(ConfigStore.load(ctx).rpcUrl, ctx)''',
)
write(tw_path, tw)

jup_path = MAIN / "network/JupiterApi.kt"
jup = read(jup_path)
old_paid = '''        val paidRpc = "https://mainnet.helius-rpc.com/?api-key=${com.lifecyclebot.data.DefaultKeys.HELIUS}"
        // Ordered, de-duplicated failover ladder. Caller RPC first (may be blank →
        // skipped), then paid Helius, then public fallbacks.
        val ladder = LinkedHashSet<String>().apply {
            if (rpcUrl.isNotBlank()) add(rpcUrl)
            add(paidRpc)
            add("https://api.mainnet-beta.solana.com")
            add("https://rpc.ankr.com/solana")
        }.toList()'''
new_paid = '''        val paidRpc = com.lifecyclebot.engine.RuntimeProviderAuthority6685.configuredHeliusRpc()
        // V5.0.6685 — caller first, encrypted configured Helius second, then
        // keyless public RPCs. Blank credential URLs never enter the ladder.
        val ladder = com.lifecyclebot.engine.RuntimeProviderAuthority6685
            .rpcCandidates(rpcUrl)
            .let { candidates ->
                if (paidRpc.isBlank()) candidates
                else LinkedHashSet<String>().apply {
                    if (rpcUrl.isNotBlank()) add(rpcUrl)
                    add(paidRpc)
                    candidates.forEach(::add)
                }.toList()
            }'''
if "RuntimeProviderAuthority6685.configuredHeliusRpc()" not in jup:
    jup = replace_once(jup, old_paid, new_paid, "Jupiter configured Helius")
write(jup_path, jup)


# ═════════════════════════════════════════════════════════════════════════════
# 5) Golden Tape: retire only contracts superseded by 6683/6684 authorities.
# ═════════════════════════════════════════════════════════════════════════════
golden_path = TEST / "GoldenTapeRegressionTest.kt"
golden = read(golden_path)
old_ssi = '''        assertTrue("V5.0.6090: SSI pilot must autonomously control paper/live non-safety strategy with wider authority", ssi6090.contains("live 0.55..1.80, paper 0.45..2.10") && ssi6090.contains("return if (paper) m.coerceIn(0.40, 2.25) else m.coerceIn(0.45, 1.90)") && ssi6090.contains("SSI_PILOT_LANE_RESUMED_6090") && !ssi6090.contains("awaiting_control_tower_manualResume"))'''
new_ssi = '''        assertTrue("V5.0.6685: SSI retains autonomous sizing authority but failed lanes must re-enter only through exact Lab proof", ssi6090.contains("live 0.55..1.80, paper 0.45..2.10") && ssi6090.contains("return if (paper) m.coerceIn(0.40, 2.25) else m.coerceIn(0.45, 1.90)") && ssi6090.contains("SSI_PILOT_REPROOF_REQUEST_6684") && !ssi6090.contains("SSI_PILOT_LANE_RESUMED_6090") && !ssi6090.contains("awaiting_control_tower_manualResume"))'''
if old_ssi in golden:
    golden = replace_once(golden, old_ssi, new_ssi, "Golden SSI proof doctrine")
elif "SSI_PILOT_REPROOF_REQUEST_6684" not in golden:
    raise SystemExit("6685 Golden SSI stale anchor missing")

old_6371 = '''        assertTrue("V5.0.6371: same-mint PAPER duplicates must be blocked in ExecutableOpenGate before paperBuy so blocked() installs a cooldown and repeated aliases stop burning buy-path work",
            gate.contains("OPEN-GATE SAME-MINT PAPER COOLDOWN") &&
                gate.contains("EmergentGuardrails.getPositionLayer(mint)") &&
                gate.contains("EXEC_OPEN_SAME_MINT_ALREADY_OPEN_COOLDOWN_6371") &&
                gate.contains("EXEC_OPEN_BLOCKED_SAME_MINT_ALREADY_OPEN_6371") &&
                gate.indexOf("OPEN-GATE SAME-MINT PAPER COOLDOWN") < gate.indexOf("SHADOW_TRAIN_ONLY is NOT an execution veto"))'''
new_6371 = '''        assertTrue("V5.0.6685: same-mint PAPER duplicate cooldown remains while 6683 shadow-train is a real no-open authority",
            gate.contains("OPEN-GATE SAME-MINT PAPER COOLDOWN") &&
                gate.contains("EmergentGuardrails.getPositionLayer(mint)") &&
                gate.contains("EXEC_OPEN_SAME_MINT_ALREADY_OPEN_COOLDOWN_6371") &&
                gate.contains("EXEC_OPEN_BLOCKED_SAME_MINT_ALREADY_OPEN_6371") &&
                gate.contains("BucketExecutionState.isShadowTrainOnly(canonicalSelectedLane, gateScore)") &&
                gate.contains("EXEC_OPEN_BLOCKED_SHADOW_TRAIN_ONLY_6683") &&
                !gate.contains("EXEC_OPEN_SHADOW_TRAIN_SOFT_ALLOW"))'''
if old_6371 in golden:
    golden = replace_once(golden, old_6371, new_6371, "Golden 6371 shadow authority")
elif "V5.0.6685: same-mint PAPER duplicate cooldown remains" not in golden:
    raise SystemExit("6685 Golden 6371 stale anchor missing")
write(golden_path, golden)


# ═════════════════════════════════════════════════════════════════════════════
# 6) Regression locks for provider/wallet authority and patch-rot prevention.
# ═════════════════════════════════════════════════════════════════════════════
provider_test = TEST / "Aate6685ProviderWalletRecoveryTest.kt"
write(provider_test, r'''package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate6685ProviderWalletRecoveryTest {
    private fun src(path: String) = File("src/main/kotlin/$path").readText()

    @Test fun `configured Helius is one encrypted runtime authority`() {
        val provider = src("com/lifecyclebot/engine/RuntimeProviderAuthority6685.kt")
        assertTrue(provider.contains("ConfigStore.load"))
        assertTrue(provider.contains("cfg?.heliusApiKey"))
        assertTrue(provider.contains("configuredHeliusRpc"))
        assertTrue(provider.contains("Explicit RPC -> saved RPC -> configured Helius"))
    }

    @Test fun `wallet connect owns bounded endpoint failover without nested fleet`() {
        val manager = src("com/lifecyclebot/engine/WalletManager.kt")
        val wallet = src("com/lifecyclebot/network/SolanaWallet.kt")
        assertTrue(manager.contains("RuntimeProviderAuthority6685.rpcCandidates(rpcUrl, ctx)"))
        assertTrue(manager.contains("getSolBalancePrimaryOnly6685()"))
        assertFalse(manager.substringAfter("fun connect(privateKeyB58").substringBefore("fun disconnect()").contains("getSolBalance()"))
        assertTrue(wallet.contains("fun getSolBalancePrimaryOnly6685(): Double"))
        assertTrue(wallet.contains("RuntimeProviderAuthority6685.rpcCandidates(rpcUrl)"))
    }

    @Test fun `stale blank DefaultKeys Helius consumers are removed`() {
        val manager = src("com/lifecyclebot/engine/WalletManager.kt")
        val treasury = src("com/lifecyclebot/engine/TreasuryWalletManager.kt")
        val jupiter = src("com/lifecyclebot/network/JupiterApi.kt")
        val wallet = src("com/lifecyclebot/network/SolanaWallet.kt")
        assertFalse(manager.contains("DefaultKeys.HELIUS"))
        assertFalse(manager.contains("DefaultKeys.ALCHEMY"))
        assertFalse(treasury.contains("DefaultKeys.HELIUS"))
        assertFalse(jupiter.contains("DefaultKeys.HELIUS"))
        assertFalse(wallet.contains("DefaultKeys.HELIUS"))
        assertFalse(manager.contains("api-key=hive-pattern-learn"))
    }

    @Test fun `valid signer is not coupled to price provider health`() {
        val manager = src("com/lifecyclebot/engine/WalletManager.kt")
        val connect = manager.substringAfter("fun connect(privateKeyB58").substringBefore("fun disconnect()")
        assertTrue(connect.contains("validate signer exactly once"))
        assertTrue(connect.contains("SOL price refresh non-fatal"))
        assertTrue(connect.indexOf("wallet = candidate") < connect.indexOf("fetchSolPrice()"))
        assertTrue(connect.contains("preserving previous wallet"))
    }
}
''')

# Source guard: these stale credential consumers must not survive the transform.
for path in [wm_path, tw_path, jup_path, sw_path]:
    text = read(path)
    if "DefaultKeys.HELIUS" in text:
        raise SystemExit(f"6685 stale DefaultKeys.HELIUS remains in {path}")
if "DefaultKeys.ALCHEMY" in read(wm_path):
    raise SystemExit("6685 stale DefaultKeys.ALCHEMY remains in WalletManager")

print("V5.0.6685 integrated adaptive + Helius wallet recovery applied")
