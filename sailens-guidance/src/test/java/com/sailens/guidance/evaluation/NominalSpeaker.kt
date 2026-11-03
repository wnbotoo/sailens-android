package com.sailens.guidance.evaluation

/**
 * The frozen [SpeakerModel] driven one moment at a time, for a closed-loop run whose offers depend on
 * what the speaker did with the earlier ones. `run` is a batch, so every step re-runs it over the
 * inputs up to now. What it says about a sentence that has ended by now is settled: later inputs
 * cannot change the past. Sentences still speaking are reported as finishing by its final `advance`,
 * and are not looked at until a later run.
 *
 * @param queued the capture's notices and descriptions, which join the inputs when their time comes.
 *   At the same millisecond an offer goes first, as in the open-loop calibration's `offers + queued`.
 */
internal class NominalSpeaker(private val queued: List<SpeakerModel.Queued> = emptyList()) {

    /** What became of an offer the moment it was made. */
    data class Answer(val accepted: Boolean, val reason: String?, val cut: List<String>)

    private val model = SpeakerModel()
    private val offers = mutableListOf<SpeakerModel.Offer>()
    private var predictions: Map<String, SpeakerModel.Prediction> = emptyMap()
    private var queuedIncluded = 0
    private val reported = HashSet<String>()

    private fun rerun() {
        predictions = model.run(offers + queued.take(queuedIncluded)).associateBy { it.id }
    }

    private fun includeQueued(t: Long) {
        val due = queued.count { it.atMs <= t }
        if (due != queuedIncluded) {
            queuedIncluded = due
            rerun()
        }
    }

    /** Sentences that ended by [t], spoken to the end or cut off, not reported before. */
    fun settled(t: Long): List<SpeakerModel.Prediction> {
        includeQueued(t)
        return predictions.values
            .filter { p -> p.accepted && p.id !in reported && p.endAtMs != null && p.endAtMs <= t }
            .sortedBy { it.endAtMs }
            .onEach { reported += it.id }
    }

    fun offer(offer: SpeakerModel.Offer): Answer {
        includeQueued(offer.atMs)
        val speakingBefore = predictions.values.filter { p -> p.accepted && p.id !in reported }.map { it.id }.toSet()
        offers += offer
        rerun()
        val mine = predictions.getValue(offer.id)
        val cut = predictions.values.filter { p -> p.id in speakingBefore && !p.completed && p.endAtMs != null && p.endAtMs <= offer.atMs }
        return Answer(mine.accepted, mine.reason, cut.map { it.id })
    }

    /** Every prediction, over everything up to the end of the session. */
    fun finish(): List<SpeakerModel.Prediction> {
        includeQueued(Long.MAX_VALUE)
        return model.run(offers + queued)
    }
}
