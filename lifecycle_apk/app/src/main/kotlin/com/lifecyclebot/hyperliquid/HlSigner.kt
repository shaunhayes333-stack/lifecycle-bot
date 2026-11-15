package com.lifecyclebot.hyperliquid

import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * V5.0.8795 — Hyperliquid's L1 action signature, as the official SDK computes it:
 *  1. connectionId = keccak256( msgpack(action) || nonce as 8 bytes || 0x00 (no vault) | 0x01 + vault address );
 *  2. a "phantom agent" {source: "a" mainnet / "b" testnet, connectionId} is signed as EIP-712 typed data in the
 *     domain {name "Exchange", version "1", chainId 1337, verifyingContract 0x0};
 *  3. secp256k1 with RFC 6979 nonces and low-s (web3j), v = 27 + recovery id.
 * Verified against the SDK's own test vectors (Aate8795HyperliquidTest). The key is an API ("agent") wallet the owner
 * approves on Hyperliquid: it can trade but never withdraw.
 */
object HlSigner {
    data class Sig(val r: String, val s: String, val v: Int)

    private fun keccak(b: ByteArray): ByteArray = org.web3j.crypto.Hash.sha3(b)

    private fun word(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(32)
        val n = minOf(32, raw.size)
        System.arraycopy(raw, raw.size - n, out, 32 - n, n)
        return out
    }

    fun actionHash(action: Map<String, Any?>, vault: String?, nonce: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(HlMsgPack.pack(action))
        for (i in 7 downTo 0) out.write(((nonce ushr (8 * i)) and 0xff).toInt())
        if (vault == null) out.write(0) else {
            out.write(1)
            out.write(org.web3j.utils.Numeric.hexStringToByteArray(vault))
        }
        return keccak(out.toByteArray())
    }

    /** Pure: the EIP-712 digest of the phantom agent for [connectionId]. */
    private fun agentDigest(mainnet: Boolean, connectionId: ByteArray): ByteArray {
        val domainType = keccak("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)".toByteArray())
        val domain = keccak(domainType + keccak("Exchange".toByteArray()) + keccak("1".toByteArray()) +
            word(BigInteger.valueOf(1337L)) + ByteArray(32))
        val agentType = keccak("Agent(string source,bytes32 connectionId)".toByteArray())
        val struct = keccak(agentType + keccak((if (mainnet) "a" else "b").toByteArray()) + connectionId)
        return keccak(byteArrayOf(0x19, 0x01) + domain + struct)
    }

    fun sign(digest: ByteArray, privateKeyHex: String): Sig {
        val kp = org.web3j.crypto.ECKeyPair.create(org.web3j.utils.Numeric.toBigInt(privateKeyHex.trim()))
        val sd = org.web3j.crypto.Sign.signMessage(digest, kp, false)
        return Sig("0x" + BigInteger(1, sd.r).toString(16), "0x" + BigInteger(1, sd.s).toString(16), sd.v[0].toInt() and 0xff)
    }

    fun signL1Action(action: Map<String, Any?>, nonce: Long, privateKeyHex: String, mainnet: Boolean, vault: String? = null): Sig =
        sign(agentDigest(mainnet, actionHash(action, vault, nonce)), privateKeyHex)

    /** The address a key signs as (shown to the owner to match the API wallet they approved). */
    fun addressOf(privateKeyHex: String): String? = try {
        org.web3j.crypto.Credentials.create(privateKeyHex.trim()).address
    } catch (_: Throwable) { null }

    /** Pure: a 32-byte hex key ("0x" optional). */
    fun validKey(privateKeyHex: String): Boolean = privateKeyHex.trim().removePrefix("0x").let { k ->
        k.length == 64 && k.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    /** Pure: a 20-byte hex address. */
    fun validAddress(address: String): Boolean = address.trim().let { a ->
        a.startsWith("0x") && a.length == 42 && a.drop(2).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }
}
