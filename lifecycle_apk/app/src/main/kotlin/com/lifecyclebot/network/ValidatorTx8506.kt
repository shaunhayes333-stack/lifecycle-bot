package com.lifecyclebot.network

import com.google.protobuf.ByteString
import com.lifecyclebot.validator.ValidatorFeed as Wire
import io.github.novacrypto.base58.Base58
import org.json.JSONArray
import org.json.JSONObject

/** Convert validator index-based instructions (including v0 loaded addresses) into the same evidence as JSON-RPC. */
internal object ValidatorTx8506 {
    private val times = LinkedHashMap<Long, Long>()
    private val pendingTape = LinkedHashMap<Long, MutableList<Wire.TxInfo>>()
    private val pending = LinkedHashMap<Long, MutableList<ChainLaunchFeed8179.Launch8179>>()
    fun reset() { times.clear(); pending.clear(); pendingTape.clear() }
    private fun key(bytes: ByteString): String = Base58.base58Encode(bytes.toByteArray())
    fun accept(update: Wire.Update, now: Long, processed: Boolean, watch: (String) -> Unit) {
        if (!processed && update.hasBlockMeta()) {
            val block = update.blockMeta
            val ms = block.blockTime.seconds * 1000L
            if (ms <= 0 || now - ms !in -2_000L..30_000L) return
            times[block.slot] = ms
            if (times.size > 96) times.entries.iterator().apply { next(); remove() }
            pending.remove(block.slot)?.forEach { ChainLaunchFeed8179.validatorBirth8506(it.copy(blockTimeMs = ms)) }
            pendingTape.remove(block.slot)?.forEach { info ->
                ValidatorMarket8506.swaps(normalized(info, block.slot, ms), info.meta.logMessagesList, key(info.signature), now)
            }
            return
        }
        if (!update.hasTransaction()) return
        val tx = update.transaction
        val info = tx.transaction
        if (!info.hasTransaction() || !info.hasMeta() || info.meta.hasErr() || info.isVote || info.signature.size() != 64 || tx.slot <= 0) return
        val clock = update.createdAt.seconds * 1000L + update.createdAt.nanos / 1_000_000
        if (clock <= 0 || now - clock !in -2_000L..15_000L) return
        val slotMs = if (processed) 0L else times[tx.slot] ?: 0L
        val logs = info.meta.logMessagesList
        // On swap-heavy program subscriptions discard irrelevant frames before expanding account keys or balances.
        val watched = update.filtersList.any { it.startsWith("tape_") }
        if (!watched && logs.none { it.startsWith("Program log: Instruction: Create") ||
            it.startsWith("Program log: Instruction: Initialize") || it.startsWith("Program log: initialize2") }) return
        val json = normalized(info, tx.slot, slotMs)
        val sig = key(info.signature)
        for (launch in ValidatorMarket8506.launches(json, logs, sig)) {
            if (!processed && slotMs == 0L) {
                val list = pending.getOrPut(tx.slot) { ArrayList() }
                if (list.size < 32) list.add(launch)
                if (pending.size > 32) pending.entries.iterator().apply { next(); remove() }
            }
            watch(launch.mint)
            if (!processed) ChainLaunchFeed8179.validatorLaunch8506(launch)
        }
        if (!processed && slotMs == 0L && watched) {
            val q = pendingTape.getOrPut(tx.slot) { ArrayList() }
            if (q.size < 48 && info.serializedSize < 32_768) q.add(info)
            if (pendingTape.size > 16) pendingTape.entries.iterator().apply { next(); remove() }
        }
        ValidatorMarket8506.swaps(json, logs, sig, now, preview = if (processed) watch else null)
    }

    internal fun normalized(info: Wire.TxInfo, slot: Long, timeMs: Long): JSONObject {
        val msg = info.transaction.message
        val meta = info.meta
        val keys = (msg.accountKeysList + meta.loadedWritableAddressesList + meta.loadedReadonlyAddressesList).map(::key)
        fun ix(v: Wire.Ix): JSONObject {
            val program = keys[v.programIdIndex]
            val accounts = v.accounts.toByteArray().map { keys[it.toInt() and 255] }
            val data = v.data.toByteArray()
            return SolanaSwapEvidence8506.decode(program, accounts, data)
        }
        fun balances(b: List<Wire.Balance>) = JSONArray(b.map { v ->
            val amount = v.uiTokenAmount
            val ui = amount.uiAmountString.ifBlank { amount.amount.toBigDecimalOrNull()?.movePointLeft(amount.decimals.coerceIn(0, 255))?.toPlainString().orEmpty() }
            JSONObject().put("accountIndex", v.accountIndex).put("mint", v.mint).put("owner", v.owner)
                .put("uiTokenAmount", JSONObject().put("uiAmountString", ui))
        })
        val message = JSONObject().put("accountKeys", JSONArray(keys.mapIndexed { i, k -> JSONObject().put("pubkey", k).put("signer", i < msg.header.numRequiredSignatures) }))
            .put("instructions", JSONArray(msg.instructionsList.map(::ix)))
        val m = JSONObject().put("innerInstructions", JSONArray(meta.innerInstructionsList.map { group ->
            JSONObject().put("index", group.index).put("instructions", JSONArray(group.instructionsList.map(::ix)))
        })).put("preTokenBalances", balances(meta.preTokenBalancesList)).put("postTokenBalances", balances(meta.postTokenBalancesList))
        return JSONObject().put("slot", slot).put("blockTime", timeMs / 1000).put("meta", m).put("transaction", JSONObject().put("message", message))
    }
}
