package com.lifecyclebot.network

/**
 * Structural gate applied before any server-supplied Solana transaction is
 * signed. It does not claim to validate swap economics; it guarantees that
 * this wallet is the sole required signer and fee payer for a well-formed
 * legacy or v0 message. Unknown message versions and multi-signer payloads
 * fail closed.
 */
internal object SolanaSigningEnvelope {
    data class Validated(
        val signatureOffset: Int,
        val messageOffset: Int,
        val messageVersion: Int,
        val staticAccountCount: Int,
    )

    private data class ShortVec(val value: Int, val nextOffset: Int)

    fun validate(txBytes: ByteArray, expectedSigner: ByteArray): Validated {
        require(expectedSigner.size == 32) { "Expected signer must be a 32-byte public key" }
        require(txBytes.isNotEmpty()) { "Empty Solana transaction" }

        val signatureCount = decodeShortVec(txBytes, 0)
        require(signatureCount.value == 1) {
            "Refusing transaction with ${signatureCount.value} signature slots; exactly one is supported"
        }

        val signatureOffset = signatureCount.nextOffset
        val messageOffset = signatureOffset + 64
        require(messageOffset < txBytes.size) { "Transaction is truncated before its message" }

        val firstMessageByte = txBytes[messageOffset].toInt() and 0xff
        val versioned = firstMessageByte and 0x80 != 0
        val messageVersion = if (versioned) firstMessageByte and 0x7f else -1
        require(!versioned || messageVersion == 0) {
            "Unsupported Solana message version: $messageVersion"
        }

        val headerOffset = if (versioned) messageOffset + 1 else messageOffset
        require(headerOffset + 3 <= txBytes.size) { "Transaction message header is truncated" }

        val requiredSignatures = txBytes[headerOffset].toInt() and 0xff
        val readOnlySigned = txBytes[headerOffset + 1].toInt() and 0xff
        require(requiredSignatures == 1) {
            "Refusing message requiring $requiredSignatures signers; wallet may sign only its own slot"
        }
        require(readOnlySigned <= requiredSignatures) { "Invalid signed-account header" }

        val accountCount = decodeShortVec(txBytes, headerOffset + 3)
        require(accountCount.value >= requiredSignatures) { "Message has fewer accounts than required signers" }
        val firstAccountOffset = accountCount.nextOffset
        val accountBytesEnd = firstAccountOffset + accountCount.value * 32
        require(accountBytesEnd <= txBytes.size) { "Static account-key table is truncated" }

        val feePayer = txBytes.copyOfRange(firstAccountOffset, firstAccountOffset + 32)
        require(feePayer.contentEquals(expectedSigner)) {
            "Refusing transaction whose fee payer/first signer is not this wallet"
        }

        return Validated(
            signatureOffset = signatureOffset,
            messageOffset = messageOffset,
            messageVersion = messageVersion,
            staticAccountCount = accountCount.value,
        )
    }

    private fun decodeShortVec(bytes: ByteArray, start: Int): ShortVec {
        require(start in bytes.indices) { "Short-vector offset is outside transaction" }
        var value = 0
        var shift = 0
        var offset = start
        repeat(3) {
            require(offset < bytes.size) { "Truncated Solana short-vector" }
            val current = bytes[offset].toInt() and 0xff
            value = value or ((current and 0x7f) shl shift)
            offset++
            if (current and 0x80 == 0) {
                require(value <= 0xffff) { "Solana short-vector exceeds u16" }
                return ShortVec(value, offset)
            }
            shift += 7
        }
        throw IllegalArgumentException("Invalid Solana short-vector encoding")
    }
}
