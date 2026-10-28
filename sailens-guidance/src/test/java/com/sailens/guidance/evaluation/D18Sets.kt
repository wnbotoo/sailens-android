package com.sailens.guidance.evaluation

/**
 * Which D1.8 set each capture belongs to, frozen with the thresholds: by session id, not by where the
 * capture sits or whether a `SEALED` marker is there. The marker only decides whether a capture may
 * be looked at ([SealedCaptures]); removing it once the thresholds are frozen must not turn S into
 * data that feeds a duration table. Taken from the D1.8 assignment file (recorded rows only).
 */
internal object D18Sets {

    enum class Group { CALIBRATION, SEALED }

    /** The set of [sessionId]; null for a capture in neither, which the speaker model does not score. */
    fun of(sessionId: String): Group? = when (sessionId) {
        in CALIBRATION -> Group.CALIBRATION
        in SEALED -> Group.SEALED
        else -> null
    }

    /** C: 14 captures. [SpeakerDurations.FROZEN_C] is their pooled table. */
    val CALIBRATION: Set<String> = setOf(
        "6612f79f-1b66-4d0a-9f31-d6f433f4fb92", // C-P-01
        "c5231b1f-2b26-43d4-94ce-3b1c96087749", // C-P-02
        "fbd8d8cc-c6db-4cdf-9a2b-c0c3cf4d0b96", // C-O1-01
        "158bc95a-a98f-4d8d-a705-4f314c05dd33", // C-O1-02
        "23a966c5-2637-4c02-aa85-66989cf8c204", // C-O2-02
        "f9ad8c51-297a-48f5-af76-263868726f9c", // C-O2-03
        "7db2b18a-0cd3-4fc1-8ace-93d1f9b8b8ef", // C-O3-01
        "055128d5-fe68-4094-b244-92b41b7909e5", // C-O3-02
        "7ff55955-f5c2-45a6-8cca-34f721b91ef0", // C-O4-01
        "c3cad463-1e27-4ec0-80e6-5751b2fdb5ca", // C-O4-02
        "07597f6b-9ae7-4e8b-96e0-09a91fe34b56", // C-I1-01
        "2b6abd09-5719-4edc-81e6-7e1c673b5020", // C-I1-02
        "95955220-cd91-4bc3-85a1-db5188f1515c", // C-I2-01
        "405e1c95-3e30-41d4-ac79-56aec6b0c66d", // C-I2-02
    )

    /** S: 12 slots, 13 captures (S-O4-02 was recorded in two parts). */
    val SEALED: Set<String> = setOf(
        "66adf21f-64bf-4a42-9948-5dab8b715902", // S-O1-01
        "5c409337-81dd-4209-8aab-e36969ba3c2d", // S-O1-02
        "0685957a-dc16-44e1-8f9e-47a29009f3f7", // S-O2-01
        "d1850653-a20e-4e4b-82c9-ca82b95ca5e5", // S-O2-02
        "9954fd7f-d1bb-4de5-aead-9111ea553bb5", // S-O3-01
        "86a0eb9b-5cc6-4494-afb6-da0e1757be41", // S-O3-02
        "6dd40854-e4dc-4c17-b8fd-c4fabf228c3c", // S-O4-01
        "fd3fc77d-941c-4e96-8251-bafadce8f7fc", // S-O4-02
        "6ff0a2f9-6893-4746-a38f-3779532e514f", // S-O4-02
        "19e1bf01-4ab8-4790-9739-2a6c7958e96d", // S-I1-01
        "801d2d42-154b-49da-8978-8c78e40c4f46", // S-I1-02
        "5c48f529-09aa-44d6-8413-6a247edb94a5", // S-I2-01
        "1ac9b9e7-176f-4216-84e5-daae0f94e18a", // S-I2-02
    )
}
