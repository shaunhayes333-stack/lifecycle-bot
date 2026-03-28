package com.lifecyclebot.v3.core

/**
 * AATE V3 Lifecycle States
 * The unlock: scoring-based decisions, not hard blocks
 */
enum class LifecycleState {
    DISCOVERED,
    ELIGIBLE,
    SCORED,
    WATCH,
    EXECUTE_READY,
    EXECUTED,
    BLOCKED_FATAL,
    REJECTED,
    SHADOW_TRACKED,
    CLOSED,
    CLASSIFIED
}

enum class V3BotMode {
    PAPER,
    LEARNING,
    LIVE
}

enum class DecisionBand {
    EXECUTE_SMALL,
    EXECUTE_STANDARD,
    EXECUTE_AGGRESSIVE,
    WATCH,
    REJECT,
    BLOCK_FATAL
}
