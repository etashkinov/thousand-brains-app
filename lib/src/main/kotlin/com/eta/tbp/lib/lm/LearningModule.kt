package com.eta.tbp.lib.lm

import com.eta.tbp.lib.cmp.CmpMessage

/** Mirrors Monty's TRAIN/EVALUATE experiment modes. */
enum class ExperimentMode { TRAIN, EVALUATE }

/**
 * Mirrors `abstract_monty_classes.LearningModule`. Real Monty keeps two
 * separate output paths — `get_output()` for the single-hypothesis
 * feed-forward message that flows up the hierarchy, and `send_out_vote()`
 * for lateral voting between sibling LMs, which isn't even typed as a
 * `Message` (`-> Any`, since a vote is a dict keyed by object/hypothesis
 * id, not a CMP message) — so `receive_votes` takes `Collection[Any]`
 * too. This interface mirrors that split rather than collapsing it into
 * one method.
 */
interface LearningModule<State> {
    val lmId: String

    /** Monty: `matching_step` — the modeling step. */
    fun matchingStep(messages: List<CmpMessage>)

    /** Monty: `receive_votes` — the voting step. Votes aren't `CmpMessage`s; shape is producer-defined. */
    fun receiveVotes(votes: List<Any>)

    /** Monty: `send_out_vote` — this LM's lateral vote for sibling LMs. Unused until Phase 8 (no siblings yet). */
    fun sendOutVote(): Any?

    /** Monty: `get_output` — this LM's single-hypothesis feed-forward output; null when there's nothing new. */
    fun getOutput(): CmpMessage?

    fun preEpisode()

    fun postEpisode()

    fun setExperimentMode(mode: ExperimentMode)

    /** Save/load, mirrors Monty's Snapshotable contract. */
    fun state(): State

    fun loadState(state: State)
}
