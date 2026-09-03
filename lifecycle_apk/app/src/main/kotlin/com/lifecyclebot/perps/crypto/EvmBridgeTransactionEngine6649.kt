package com.lifecyclebot.perps.crypto

import java.math.BigInteger
import org.web3j.crypto.Credentials
import org.web3j.crypto.Hash
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.TransactionEncoder
import org.web3j.utils.Numeric

/**
 * V5.0.6649 — restart-safe EVM transaction spine for bridge orders.
 *
 * The engine is deliberately transport-agnostic.  The adapter supplies an RPC
 * implementation and a durable store, which makes nonce, signing, gas,
 * idempotent rebroadcast and finality testable without ever fabricating a live
 * route.  A submitted transaction is never rebuilt with a new nonce after a
 * crash; the identical signed bytes are rebroadcast until the receipt settles.
 */
object EvmBridgeTransactionEngine6649 {
    data class Request(
        val idempotencyKey: String,
        val chainId: Long,
        val from: String,
        val to: String,
        val data: String,
        val value: BigInteger = BigInteger.ZERO,
        val minimumConfirmations: Int = 2,
    )

    data class Receipt(
        val transactionHash: String,
        val blockNumber: BigInteger,
        val successful: Boolean,
    )

    enum class Stage { SIGNED, SUBMITTED, CONFIRMED, FAILED }

    data class Record(
        val idempotencyKey: String,
        val chainId: Long,
        val nonce: BigInteger,
        val gasPrice: BigInteger,
        val gasLimit: BigInteger,
        val rawTransaction: String,
        val transactionHash: String,
        val stage: Stage,
        val submitAttempts: Int,
        val error: String = "",
    )

    sealed class Outcome {
        data class Confirmed(val record: Record, val receipt: Receipt) : Outcome()
        data class Pending(val record: Record, val reason: String) : Outcome()
        data class Failed(val record: Record?, val reason: String) : Outcome()
    }

    interface Rpc {
        fun pendingNonce(address: String): BigInteger
        fun gasPrice(): BigInteger
        fun estimateGas(from: String, to: String, value: BigInteger, data: String): BigInteger
        fun sendRawTransaction(rawTransaction: String): String
        fun receipt(transactionHash: String): Receipt?
        fun blockNumber(): BigInteger
    }

    interface Store {
        fun load(idempotencyKey: String): Record?
        fun save(record: Record)
    }

    fun execute(
        request: Request,
        credentials: Credentials,
        rpc: Rpc,
        store: Store,
        maxReceiptPolls: Int = 1,
    ): Outcome {
        require(request.idempotencyKey.isNotBlank()) { "EVM_IDEMPOTENCY_KEY_EMPTY" }
        require(request.chainId > 0L) { "EVM_CHAIN_ID_INVALID" }
        require(isAddress(request.from) && isAddress(request.to)) { "EVM_ADDRESS_INVALID" }
        require(credentials.address.equals(request.from, ignoreCase = true)) { "EVM_SIGNER_MISMATCH" }

        var record = store.load(request.idempotencyKey)
        if (record != null) {
            if (record.chainId != request.chainId) return Outcome.Failed(record, "EVM_RECOVERY_CHAIN_MISMATCH")
            if (record.stage == Stage.FAILED) return Outcome.Failed(record, record.error.ifBlank { "EVM_PREVIOUS_FAILURE" })
            settlement(record, request.minimumConfirmations, rpc, store)?.let { return it }
            if (record.stage == Stage.CONFIRMED) return Outcome.Failed(record, "EVM_CONFIRMED_RECEIPT_MISSING")
        }

        if (record == null) {
            val nonce = rpc.pendingNonce(request.from)
            val gasPrice = rpc.gasPrice()
            val estimated = rpc.estimateGas(request.from, request.to, request.value, request.data)
            require(nonce >= BigInteger.ZERO) { "EVM_NONCE_INVALID" }
            require(gasPrice > BigInteger.ZERO) { "EVM_GAS_PRICE_INVALID" }
            require(estimated > BigInteger.ZERO) { "EVM_GAS_ESTIMATE_INVALID" }
            val gasLimit = estimated.multiply(BigInteger.valueOf(120L)).divide(BigInteger.valueOf(100L))
            val raw = RawTransaction.createTransaction(
                nonce, gasPrice, gasLimit, request.to, request.value, request.data,
            )
            val signed = TransactionEncoder.signMessage(raw, request.chainId, credentials)
            val rawHex = Numeric.toHexString(signed)
            val hash = Numeric.toHexString(Hash.sha3(signed))
            record = Record(
                idempotencyKey = request.idempotencyKey,
                chainId = request.chainId,
                nonce = nonce,
                gasPrice = gasPrice,
                gasLimit = gasLimit,
                rawTransaction = rawHex,
                transactionHash = hash,
                stage = Stage.SIGNED,
                submitAttempts = 0,
            )
            store.save(record)
        }

        // Idempotent submission: after restart this is the exact same signed
        // payload and nonce.  "already known"/"nonce too low" are verified by
        // receipt lookup rather than treated as permission to build a new tx.
        try {
            val rpcHash = rpc.sendRawTransaction(record.rawTransaction)
            if (rpcHash.isNotBlank() && !rpcHash.equals(record.transactionHash, ignoreCase = true)) {
                val failed = record.copy(stage = Stage.FAILED, error = "EVM_RPC_HASH_MISMATCH")
                store.save(failed)
                return Outcome.Failed(failed, failed.error)
            }
            record = record.copy(stage = Stage.SUBMITTED, submitAttempts = record.submitAttempts + 1)
            store.save(record)
        } catch (t: Throwable) {
            val message = t.message.orEmpty().lowercase()
            if (!message.contains("already known") && !message.contains("nonce too low")) {
                record = record.copy(submitAttempts = record.submitAttempts + 1, error = t.message ?: t.javaClass.simpleName)
                store.save(record)
                return Outcome.Pending(record, "EVM_SUBMIT_RETRY:${record.error}")
            }
        }

        val submittedRecord = requireNotNull(record) { "EVM_RECORD_MISSING_AFTER_SUBMIT" }
        repeat(maxReceiptPolls.coerceAtLeast(1)) {
            settlement(submittedRecord, request.minimumConfirmations, rpc, store)?.let { return it }
        }
        return Outcome.Pending(submittedRecord, "EVM_RECEIPT_PENDING")
    }

    fun approvalData(spender: String, amount: BigInteger): String {
        require(isAddress(spender) && amount >= BigInteger.ZERO)
        return "0x095ea7b3" + spender.removePrefix("0x").lowercase().padStart(64, '0') +
            amount.toString(16).padStart(64, '0')
    }

    fun allowanceData(owner: String, spender: String): String {
        require(isAddress(owner) && isAddress(spender))
        return "0xdd62ed3e" + owner.removePrefix("0x").lowercase().padStart(64, '0') +
            spender.removePrefix("0x").lowercase().padStart(64, '0')
    }

    private fun settlement(record: Record, confirmations: Int, rpc: Rpc, store: Store): Outcome? {
        val receipt = rpc.receipt(record.transactionHash) ?: return null
        if (!receipt.successful) {
            val failed = record.copy(stage = Stage.FAILED, error = "EVM_RECEIPT_REVERTED")
            store.save(failed)
            return Outcome.Failed(failed, failed.error)
        }
        val required = confirmations.coerceAtLeast(1) - 1
        if (rpc.blockNumber() - receipt.blockNumber < BigInteger.valueOf(required.toLong())) return null
        val confirmed = record.copy(stage = Stage.CONFIRMED, error = "")
        store.save(confirmed)
        return Outcome.Confirmed(confirmed, receipt)
    }

    private fun isAddress(value: String): Boolean = value.matches(Regex("^0x[0-9a-fA-F]{40}$"))
}
