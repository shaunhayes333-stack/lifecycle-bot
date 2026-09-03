package com.lifecyclebot.engine

import com.lifecyclebot.perps.crypto.EvmBridgeTransactionEngine6649
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.web3j.crypto.Credentials
import org.web3j.crypto.Hash
import org.web3j.utils.Numeric

class EvmBridgeTransactionEngine6649Test {
    private val credentials = Credentials.create("1".padStart(64, '0'))
    private val to = "0x" + "22".repeat(20)

    private class MemoryStore : EvmBridgeTransactionEngine6649.Store {
        var row: EvmBridgeTransactionEngine6649.Record? = null
        override fun load(idempotencyKey: String) = row?.takeIf { it.idempotencyKey == idempotencyKey }
        override fun save(record: EvmBridgeTransactionEngine6649.Record) { row = record }
    }

    private class FakeRpc : EvmBridgeTransactionEngine6649.Rpc {
        var nonceReads = 0
        var sends = 0
        var mined = false
        var reverted = false
        var lastHash = ""
        override fun pendingNonce(address: String): BigInteger {
            nonceReads++
            return BigInteger.valueOf(7)
        }
        override fun gasPrice() = BigInteger.valueOf(20_000_000_000L)
        override fun estimateGas(from: String, to: String, value: BigInteger, data: String) = BigInteger.valueOf(50_000)
        override fun sendRawTransaction(rawTransaction: String): String {
            sends++
            lastHash = Numeric.toHexString(Hash.sha3(Numeric.hexStringToByteArray(rawTransaction)))
            return lastHash
        }
        override fun receipt(transactionHash: String): EvmBridgeTransactionEngine6649.Receipt? =
            if (!mined) null else EvmBridgeTransactionEngine6649.Receipt(transactionHash, BigInteger.valueOf(100), !reverted)
        override fun blockNumber() = BigInteger.valueOf(102)
    }

    @Test fun signed_payload_and_nonce_survive_pending_retry_then_confirm_once() {
        val rpc = FakeRpc()
        val store = MemoryStore()
        val request = EvmBridgeTransactionEngine6649.Request(
            "position-1:reverse", 1L, credentials.address, to, "0x1234",
        )
        val first = EvmBridgeTransactionEngine6649.execute(request, credentials, rpc, store)
        assertTrue(first is EvmBridgeTransactionEngine6649.Outcome.Pending)
        val raw = store.row!!.rawTransaction
        assertEquals(1, rpc.nonceReads)

        rpc.mined = true
        val second = EvmBridgeTransactionEngine6649.execute(request, credentials, rpc, store)
        assertTrue(second is EvmBridgeTransactionEngine6649.Outcome.Confirmed)
        assertEquals(raw, store.row!!.rawTransaction)
        assertEquals(1, rpc.nonceReads)
        assertEquals(1, rpc.sends)

        val third = EvmBridgeTransactionEngine6649.execute(request, credentials, rpc, store)
        assertTrue(third is EvmBridgeTransactionEngine6649.Outcome.Confirmed)
        assertEquals(1, rpc.sends)
    }

    @Test fun reverted_receipt_is_terminal_and_never_resubmitted() {
        val rpc = FakeRpc()
        val store = MemoryStore()
        val request = EvmBridgeTransactionEngine6649.Request("position-2:approve", 8453L, credentials.address, to, "0xabcd")
        EvmBridgeTransactionEngine6649.execute(request, credentials, rpc, store)
        rpc.mined = true
        rpc.reverted = true
        val failed = EvmBridgeTransactionEngine6649.execute(request, credentials, rpc, store)
        assertTrue(failed is EvmBridgeTransactionEngine6649.Outcome.Failed)
        assertEquals(EvmBridgeTransactionEngine6649.Stage.FAILED, store.row!!.stage)
        val sends = rpc.sends
        EvmBridgeTransactionEngine6649.execute(request, credentials, rpc, store)
        assertEquals(sends, rpc.sends)
    }

    @Test fun erc20_calldata_is_fixed_width_and_chain_independent() {
        val spender = "0x" + "ab".repeat(20)
        val approval = EvmBridgeTransactionEngine6649.approvalData(spender, BigInteger.valueOf(1234))
        val allowance = EvmBridgeTransactionEngine6649.allowanceData(credentials.address, spender)
        assertEquals(138, approval.length)
        assertEquals(138, allowance.length)
        assertTrue(approval.startsWith("0x095ea7b3"))
        assertTrue(allowance.startsWith("0xdd62ed3e"))
    }
}
