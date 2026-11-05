package com.lifecyclebot.engine.truth

/**
 * V5.0.8535 §P1-14 — ONE normalised RugCheck contract.
 *
 * MoonshotTraderAI's comment said the confirmed danger band 2..14 was refused in both modes while its code refused only
 * a confirmed 0 and penalised 2..14 by (15 - score); FinalDecisionGate (`low_rc_penalty_only`) and the authorizer
 * (`isConfirmedRugForLive7948`: 0 only) agree with the CODE. The 8528 decision ("numeric rug risk is graded evidence,
 * explicit safety refusals retained") is the contract; the comment was stale. Nothing here loosens a veto:
 *   CONFIRMED_RUG  score 0 (provider `rugged` / risk >= 99) — refused in both modes, everywhere
 *   DANGER         confirmed 2..14 — graded (a penalty on the native score, FDG penalty, learner feature), never a door
 *   PENDING        1 (the provider's pending sentinel) or an unfetched negative — no verdict, no penalty
 *   SAFE           15 and above
 */
object RugCheckContract8535 {

    enum class State { CONFIRMED_RUG, DANGER, PENDING, SAFE }

    const val DANGER_MAX_8535 = 14

    fun of8535(score: Int): State = when {
        score == 0 -> State.CONFIRMED_RUG
        score == 1 || score < 0 -> State.PENDING
        score <= DANGER_MAX_8535 -> State.DANGER
        else -> State.SAFE
    }

    /** The only RugCheck door: a confirmed rug. */
    fun refuses8535(score: Int): Boolean = of8535(score) == State.CONFIRMED_RUG

    /** The graded penalty of the danger band on a 0-100 native score (0 outside it). */
    fun penalty8535(score: Int): Int = if (of8535(score) == State.DANGER) 15 - score else 0
}
