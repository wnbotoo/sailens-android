package com.sailens.guidance.evaluation

/**
 * Read-only access to what the frozen `speakerInputs` worked out for one capture. Those fields are
 * private and the file may not change (threshold note §3.4), and a copy of the logic would drift from
 * it, so they are read by reflection: there is one source of these inputs. A renamed or retyped field
 * throws here; `FrozenSpeakerInputsTest` checks that.
 */
internal class FrozenSpeakerInputs(source: PromptPolicyCaptureEvaluation.SpeakerInputs) {
    val offers: List<PromptPolicyCaptureEvaluation.OfferInput> = source.field("offers")
    val queued: List<SpeakerModel.Queued> = source.field("queued")
    val actual: Map<String, SpeakerModel.Actual> = source.field("actual")
    val excluded: Map<String, String> = source.field("excluded")
    val windows: List<Pair<LongRange, String>> = source.field("windows")
    val deviceBusy: List<LongRange> = source.field("deviceBusy")

    private companion object {
        inline fun <reified T> PromptPolicyCaptureEvaluation.SpeakerInputs.field(name: String): T {
            val field = PromptPolicyCaptureEvaluation.SpeakerInputs::class.java.getDeclaredField(name)
            field.isAccessible = true
            return T::class.java.cast(field.get(this))
        }
    }
}
