package com.sailens.guidance.processor.decision

import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.DetFirstConfig
import com.sailens.guidance.evaluation.EvaluationFixtures
import com.sailens.guidance.model.analysis.FrameQuality
import com.sailens.guidance.model.analysis.GroundRecognition
import com.sailens.guidance.model.analysis.SceneSnapshot
import com.sailens.guidance.model.analysis.VisionFaults
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.Severity
import com.sailens.guidance.model.scene.SceneEventMessageKeys
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The generator's two gates and its vision faults, which it reads from the qualification's baseline, against the test
 * written out by hand: frame quality counts only while sensor-quality events are on, and a confirmed unrecognised
 * floor both makes its own event and pauses the path prompts (blocked, path complex, narrowing). Every frame quality,
 * floor and switch, with the policy switches off and at launch, on a hard-blocked, a path-complex and a narrowing
 * scene, each with a person ahead.
 */
class EventGeneratorBaselineGateTest {

    private val pathCategories = setOf(EventCategory.BLOCKED, EventCategory.PATH_COMPLEX, EventCategory.NARROWING)

    private val gated = setOf(EventCategory.SENSOR_QUALITY, EventCategory.GROUND_UNRECOGNIZED) + pathCategories

    private fun blocked(quality: FrameQuality, ground: GroundRecognition): SceneSnapshot = EvaluationFixtures.snapshot(
        obstacles = listOf(EvaluationFixtures.obstacle(1)),
        blocked = true,
        frameQuality = quality,
    ).copy(groundRecognition = ground)

    /** Blocked, but not severe enough for a hard block: a path-complex prompt, unless the policy turns those off. */
    private fun pathComplex(quality: FrameQuality, ground: GroundRecognition): SceneSnapshot =
        blocked(quality, ground).let { it.copy(connectivity = it.connectivity.copy(blockageSeverity = Severity.MODERATE)) }

    private fun narrowing(quality: FrameQuality, ground: GroundRecognition): SceneSnapshot =
        EvaluationFixtures.snapshot(obstacles = listOf(EvaluationFixtures.obstacle(1)), frameQuality = quality).let {
            it.copy(groundRecognition = ground, connectivity = it.connectivity.copy(isNarrowing = true))
        }

    @Test
    fun `the gates and the vision faults are the generator's own test, for every frame quality, floor and switch`() {
        var cases = 0
        for (detFirst in listOf(DetFirstConfig(), DetFirstConfig.LAUNCH)) {
            for (sensorQuality in listOf(true, false)) {
                val config = AnalysisConfig(enableSensorQualityEvents = sensorQuality, enableNarrowingEvents = true, detFirst = detFirst)
                val generator = EventGenerator(config)
                for ((sceneName, scene, pathCategory) in listOf(
                    Triple("blocked", ::blocked, EventCategory.BLOCKED),
                    Triple("path complex", ::pathComplex, EventCategory.PATH_COMPLEX.takeUnless { detFirst.pathComplexOff }),
                    Triple("narrowing", ::narrowing, EventCategory.NARROWING),
                )) {
                    val plain = generator.generate(scene(FrameQuality.OK, GroundRecognition.RECOGNIZED), now = 1_000L)
                    val plainPath = plain.filter { it.category in pathCategories }
                    assertEquals("$detFirst $sceneName: the scene's path prompt", listOfNotNull(pathCategory), plainPath.map { it.category })
                    for (quality in FrameQuality.entries) {
                        for (ground in GroundRecognition.entries) {
                            val label = "$detFirst sensorQuality=$sensorQuality $sceneName $quality $ground"
                            val snapshot = scene(quality, ground)

                            // The test as the generator wrote it before it read the qualification.
                            val faultQuality = if (sensorQuality) quality else FrameQuality.OK
                            val floorUnrecognized = ground == GroundRecognition.UNRECOGNIZED
                            val pathPaused = ground == GroundRecognition.UNRECOGNIZED

                            assertEquals(label, VisionFaults(faultQuality, floorUnrecognized), generator.visionFaults(snapshot))

                            val events = generator.generate(snapshot, now = 1_000L)
                            val expectedGated = buildList {
                                when (faultQuality) {
                                    FrameQuality.OBSTRUCTED -> add(EventCategory.SENSOR_QUALITY to SceneEventMessageKeys.CAMERA_BLOCKED)
                                    FrameQuality.TOO_DARK -> add(EventCategory.SENSOR_QUALITY to SceneEventMessageKeys.LOW_LIGHT)
                                    FrameQuality.OK -> Unit
                                }
                                if (floorUnrecognized) add(EventCategory.GROUND_UNRECOGNIZED to SceneEventMessageKeys.GROUND_UNRECOGNIZED)
                                if (!pathPaused) addAll(plainPath.map { it.category to it.messageKey })
                            }
                            assertEquals(label, expectedGated, events.filter { it.category in gated }.map { it.category to it.messageKey })
                            assertEquals(
                                "$label: everything else is not gated",
                                plain.filter { it.category !in gated }.map { it.messageKey },
                                events.filter { it.category !in gated }.map { it.messageKey },
                            )
                            cases++
                        }
                    }
                }
            }
        }
        assertEquals(2 * 2 * 3 * FrameQuality.entries.size * GroundRecognition.entries.size, cases)
    }
}
