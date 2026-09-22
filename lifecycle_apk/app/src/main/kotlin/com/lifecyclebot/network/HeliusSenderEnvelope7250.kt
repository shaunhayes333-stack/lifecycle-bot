package com.lifecyclebot.network

import io.github.novacrypto.base58.Base58
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Builds the fee envelope required by Helius Sender SWQOS.
 *
 * Jupiter supplies an unsigned v0 transaction containing a Compute Budget
 * SetComputeUnitPrice instruction. Sender additionally requires a System
 * Program transfer to one of Helius' published tip accounts. This helper adds
 * that transfer to the compiled v0 message before the wallet signs it.
 *
 * The implementation deliberately preserves address-table lookups instead of
 * resolving/decompiling them. Inserting a writable static key shifts existing
 * readonly-static and lookup-table account indexes; every compiled instruction
 * index is remapped accordingly. Any unsupported/malformed envelope fails
 * closed and remains on the normal Jupiter/Jito/RPC route.
 */
object HeliusSenderEnvelope7250 {
    const val MIN_SWQOS_TIP_LAMPORTS = 5_000L
    private const val MAX_SOLANA_TX_BYTES = 1_232
    private const val VERSIONED_V0_PREFIX = 0x80
    private const val SYSTEM_TRANSFER_DISCRIMINATOR = 2
    private const val COMPUTE_UNIT_PRICE_DISCRIMINATOR = 3

    private val systemProgram = Base58.base58Decode("11111111111111111111111111111111")
    private val computeBudgetProgram = Base58.base58Decode("ComputeBudget111111111111111111111111111111")

    // Published Helius Sender tip accounts. Selection is deterministic per
    // message so retries produce the same transaction and signature.
    private val tipAccounts = listOf(
        "4ACfpUFoaSD9bfPdeu6DBt89gB6ENTeHBXCAi87NhDEE",
        "D2L6yPZ2FmmmTKPgzaMKdhu6EWZcTpLy1Vhx8uvZe7NZ",
        "9bnz4RShgq1hAnLnZbP8kbgBg1kEmcJBYQq3gQbmnSta",
        "5VY91ws6B2hMmBFRsXkoAAdsPHBJwRfBht4DXox3xkwn",
        "2nyhqdwKcJZR2vcqCyrYsaPVdAnFoJjiksCXJ7hfEYgD",
        "2q5pghRs6arqVjRvT5gfgWfWcHWmw1ZuCzphgd5KfWGJ",
        "wyvPkWjVZz1M8fHQnMMCDTQDbkManefNNhweYk5WkcF",
        "3KCKozbAaF75qEU33jtzozcJ29yJuaLJTy2jFdzUY8bT",
        "4vieeGHPYPG2MmyPRcYjdiDmmhN3ww7hsFNap8pVN3Ey",
        "4TQLFNWK8AovT1gFvda5jfw2oJeRMKEmw7aH6MGBJ3or",
    ).map { Base58.base58Decode(it) }

    data class Result(
        val txBase64: String,
        val tipLamports: Long,
        val tipAccount: String,
        val hasComputeUnitPrice: Boolean,
        val wireBytes: Int,
    )

    private data class Instruction(val programIndex: Int, val accounts: IntArray, val data: ByteArray)
    private data class Lookup(val account: ByteArray, val writable: ByteArray, val readonly: ByteArray)

    fun build(unsignedTxBase64: String, expectedPayerBase58: String, tipLamports: Long): Result {
        require(tipLamports >= MIN_SWQOS_TIP_LAMPORTS) { "Sender SWQOS tip below minimum" }
        val tx = Base64.getDecoder().decode(unsignedTxBase64)
        val r = Reader(tx)
        val signatureCount = r.shortVec()
        require(signatureCount == 1) { "Sender envelope supports one required signer, got $signatureCount" }
        r.bytes(signatureCount * 64) // Jupiter's placeholder signature(s).

        require(r.u8() == VERSIONED_V0_PREFIX) { "Sender envelope requires a v0 transaction" }
        val requiredSignatures = r.u8()
        val readonlySigned = r.u8()
        val readonlyUnsigned = r.u8()
        require(requiredSignatures == 1 && readonlySigned == 0) {
            "Sender envelope requires exactly one writable signer"
        }

        val oldStaticCount = r.shortVec()
        require(oldStaticCount in 1..250) { "Invalid static account count $oldStaticCount" }
        val oldKeys = MutableList(oldStaticCount) { r.bytes(32) }
        val expectedPayer = Base58.base58Decode(expectedPayerBase58)
        require(expectedPayer.size == 32 && oldKeys[0].contentEquals(expectedPayer)) {
            "Jupiter fee payer does not match the connected wallet"
        }
        val blockhash = r.bytes(32)
        val instructions = MutableList(r.shortVec()) {
            Instruction(
                programIndex = r.u8(),
                accounts = IntArray(r.shortVec()) { r.u8() },
                data = r.bytes(r.shortVec()),
            )
        }
        val lookups = MutableList(r.shortVec()) {
            Lookup(r.bytes(32), r.bytes(r.shortVec()), r.bytes(r.shortVec()))
        }
        require(r.remaining() == 0) { "Trailing bytes in Jupiter transaction" }

        val computeBudgetIndex = oldKeys.indexOfFirst { it.contentEquals(computeBudgetProgram) }
        val hasComputePrice = computeBudgetIndex >= 0 && instructions.any {
            it.programIndex == computeBudgetIndex && it.data.firstOrNull()?.toInt()?.and(0xff) == COMPUTE_UNIT_PRICE_DISCRIMINATOR
        }
        require(hasComputePrice) { "Jupiter transaction has no SetComputeUnitPrice instruction" }

        val tipKey = chooseAbsentTip(oldKeys, blockhash)
        val writableInsert = oldStaticCount - readonlyUnsigned
        require(writableInsert >= requiredSignatures) { "Invalid readonly unsigned header" }
        val systemOldIndex = oldKeys.indexOfFirst { it.contentEquals(systemProgram) }
        val appendSystem = systemOldIndex < 0
        val addedKeys = 1 + if (appendSystem) 1 else 0
        require(oldStaticCount + addedKeys <= 255) { "Sender envelope account index overflow" }

        fun remap(index: Int): Int = when {
            index < writableInsert -> index
            index < oldStaticCount -> index + 1
            else -> index + addedKeys // loaded address-table accounts
        }

        val newKeys = ArrayList<ByteArray>(oldStaticCount + addedKeys).apply {
            addAll(oldKeys.subList(0, writableInsert))
            add(tipKey)
            addAll(oldKeys.subList(writableInsert, oldStaticCount))
            if (appendSystem) add(systemProgram)
        }
        val tipIndex = writableInsert
        val systemIndex = if (appendSystem) newKeys.lastIndex else remap(systemOldIndex)
        val remappedInstructions = instructions.map { ix ->
            Instruction(remap(ix.programIndex), ix.accounts.map(::remap).toIntArray(), ix.data)
        }.toMutableList()
        require(remappedInstructions.all { ix ->
            ix.programIndex in 0..255 && ix.accounts.all { it in 0..255 }
        }) { "Sender envelope account index overflow after lookup remap" }
        remappedInstructions += Instruction(
            programIndex = systemIndex,
            accounts = intArrayOf(0, tipIndex),
            data = ByteArray(12).also {
                putLe32(it, 0, SYSTEM_TRANSFER_DISCRIMINATOR)
                putLe64(it, 4, tipLamports)
            },
        )

        val out = ByteArrayOutputStream(tx.size + 64)
        out.shortVec(signatureCount)
        out.write(ByteArray(signatureCount * 64))
        out.write(VERSIONED_V0_PREFIX)
        out.write(requiredSignatures)
        out.write(readonlySigned)
        out.write(readonlyUnsigned + if (appendSystem) 1 else 0)
        out.shortVec(newKeys.size)
        newKeys.forEach(out::write)
        out.write(blockhash)
        out.shortVec(remappedInstructions.size)
        remappedInstructions.forEach { ix ->
            out.write(ix.programIndex)
            out.shortVec(ix.accounts.size)
            ix.accounts.forEach(out::write)
            out.shortVec(ix.data.size)
            out.write(ix.data)
        }
        out.shortVec(lookups.size)
        lookups.forEach { lookup ->
            out.write(lookup.account)
            out.shortVec(lookup.writable.size); out.write(lookup.writable)
            out.shortVec(lookup.readonly.size); out.write(lookup.readonly)
        }
        val encoded = out.toByteArray()
        require(encoded.size <= MAX_SOLANA_TX_BYTES) {
            "Sender envelope ${encoded.size} bytes exceeds Solana packet limit $MAX_SOLANA_TX_BYTES"
        }
        return Result(
            txBase64 = Base64.getEncoder().encodeToString(encoded),
            tipLamports = tipLamports,
            tipAccount = Base58.base58Encode(tipKey),
            hasComputeUnitPrice = true,
            wireBytes = encoded.size,
        )
    }

    private fun chooseAbsentTip(keys: List<ByteArray>, blockhash: ByteArray): ByteArray {
        val seed = blockhash.fold(1) { acc, b -> 31 * acc + (b.toInt() and 0xff) }
        for (offset in tipAccounts.indices) {
            val candidate = tipAccounts[Math.floorMod(seed + offset, tipAccounts.size)]
            if (keys.none { it.contentEquals(candidate) }) return candidate
        }
        throw IllegalArgumentException("Every Helius tip account is already present")
    }

    private fun putLe32(dst: ByteArray, off: Int, value: Int) {
        for (i in 0 until 4) dst[off + i] = (value ushr (8 * i)).toByte()
    }

    private fun putLe64(dst: ByteArray, off: Int, value: Long) {
        require(value >= 0L)
        for (i in 0 until 8) dst[off + i] = (value ushr (8 * i)).toByte()
    }

    private class Reader(private val data: ByteArray) {
        private var p = 0
        fun remaining(): Int = data.size - p
        fun u8(): Int {
            require(p < data.size) { "Unexpected end of transaction" }
            return data[p++].toInt() and 0xff
        }
        fun bytes(n: Int): ByteArray {
            require(n >= 0 && p + n <= data.size) { "Malformed transaction length $n" }
            return data.copyOfRange(p, p + n).also { p += n }
        }
        fun shortVec(): Int {
            var out = 0
            var shift = 0
            repeat(3) {
                val b = u8()
                out = out or ((b and 0x7f) shl shift)
                if ((b and 0x80) == 0) return out
                shift += 7
            }
            throw IllegalArgumentException("Invalid Solana shortvec")
        }
    }

    private fun ByteArrayOutputStream.shortVec(value: Int) {
        require(value >= 0)
        var n = value
        do {
            var b = n and 0x7f
            n = n ushr 7
            if (n != 0) b = b or 0x80
            write(b)
        } while (n != 0)
    }
}
