package com.lifecyclebot.engine

import com.lifecyclebot.network.SolanaWallet

/**
 * Submission accounting for one sealed entry attempt, independent of position liability.
 * A proven on-chain failure or expiry still used the attempt. It must not be adopted as a
 * pending fill. A duplicate-order exception names an older attempt and contributes nothing.
 * Several safe route fallbacks remain one entry attempt and are charged once.
 */
internal class BuySubmissionReceipt8397(val attemptId: String) {
    @Volatile var signature: String? = null
        private set

    @Synchronized private fun record(signature: String?) {
        if (this.signature == null && !signature.isNullOrBlank()) this.signature = signature
    }

    companion object {
        internal fun submittedSignature8397(error: Throwable): String? = when (error) {
            is SolanaWallet.SentButUnconfirmed7733 -> error.signature
            is SolanaWallet.OnChainFailed8085 -> error.signature
            is SolanaWallet.TxExpiredProven8085 -> error.signature
            else -> null // includes DuplicateOrderPrevented8085 and a definite provider rejection
        }

        /** The actual send boundary. Preserve the original return/throw for route and liability policy. */
        fun <T : String?> observe8397(receipt: BuySubmissionReceipt8397?, send: () -> T): T = try {
            send().also { receipt?.record(it) }
        } catch (error: Throwable) {
            receipt?.record(submittedSignature8397(error))
            throw error
        }
    }
}
