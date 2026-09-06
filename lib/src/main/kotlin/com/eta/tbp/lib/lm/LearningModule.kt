package com.eta.tbp.lib.lm

import com.eta.tbp.lib.cmp.CmpMessage

/** Mirrors Monty's TRAIN/EVALUATE experiment modes. */
enum class ExperimentMode { TRAIN, EVALUATE }

/** Mirrors `abstract_monty_classes.LearningModule`. */
interface LearningModule<State> {
    val lmId: String

    /** Monty: `matching_step` — the modeling step. */
    fun matchingStep(messages: List<CmpMessage>)

    /** Monty: `receive_votes` — the voting step. */
    fun receiveVotes(votes: List<CmpMessage>)

    /** Defines what data are sent to other LMs. */
    fun sendOutVote(): CmpMessage

    fun preEpisode()

    fun postEpisode()

    fun setExperimentMode(mode: ExperimentMode)

    /** Save/load, mirrors Monty's Snapshotable contract. */
    fun state(): State

    fun loadState(state: State)
}
