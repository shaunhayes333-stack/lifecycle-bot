package com.lifecyclebot.engine

/**
 * V5.0.8418 §A_THROWN_BUY_NAMES_ITS_CAUSE.
 *
 * Phone run 5.0.8418: EXEC_LIVE_BUY_FAIL_REASONS=THROWN:Transaction:1. The reason roll-up
 * (PipelineHealthCollector.liveBuyFailReason) keeps the first whitespace-free token of "reason=", and Executor.liveBuy's
 * catch wrote "reason=THROWN:<message>" — so "Transaction" was not an exception class but the first WORD of the message.
 * The only SolanaWallet exceptions whose message starts with that word are OnChainFailed8085 ("Transaction failed
 * on-chain: <err> (sig=...)") and TxExpiredProven8085 ("Transaction not found after Ns — blockhash expired (proven)"),
 * both thrown by signSendAndConfirm after the transaction was sent. The rest of the message (the on-chain error) was cut.
 *
 * The reason token now carries the exception class AND the redacted message (first 160 chars) with whitespace folded,
 * so it survives the token roll-up whole.
 */
object LiveBuyThrown8418 {
    const val MESSAGE_CHARS_8418 = 160

    /** Pure: [message] with every whitespace run folded to one '_' and trimmed to [MESSAGE_CHARS_8418] chars. */
    fun compact8418(message: String?): String =
        message.orEmpty().trim().replace(Regex("\\s+"), "_").take(MESSAGE_CHARS_8418).ifBlank { "unknown" }

    /** Pure: the whitespace-free reason token for a live buy that threw [error] with the redacted message [safeMessage]. */
    fun reason8418(error: Throwable, safeMessage: String?): String =
        "THROWN:${error.javaClass.simpleName.ifBlank { "Throwable" }}:${compact8418(safeMessage)}"
}
