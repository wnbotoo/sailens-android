package com.sailens.guidance.processor.analysis

import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.model.analysis.GroundRecognition
import com.sailens.guidance.model.perception.SegmentationMask
import com.sailens.guidance.semantics.CityscapesNavigationSemantics
import com.sailens.vision.taxonomy.CityscapesTaxonomy
import org.junit.Assert.assertEquals
import org.junit.Test

class GroundRecognitionAnalyzerTest {

    private var now = 0L
    private val config = AnalysisConfig()

    private fun analyzer(config: AnalysisConfig = this.config) =
        GroundRecognitionAnalyzer(config, CityscapesNavigationSemantics, clock = { now })

    @Test
    fun `sidewalk underfoot is recognized ground`() {
        val analyzer = analyzer()

        val result = analyzer.analyze(mask(bottomClass = CityscapesTaxonomy.SIDEWALK))

        assertEquals(GroundRecognition.RECOGNIZED, result.recognition)
        assertEquals(0f, result.unrecognizedRatio, 0f)
    }

    @Test
    fun `a person or pole right in front is a known obstacle, not unrecognized ground`() {
        val analyzer = analyzer()

        assertEquals(
            GroundRecognition.RECOGNIZED,
            analyzer.analyze(mask(bottomClass = CityscapesTaxonomy.PERSON)).recognition,
        )
        assertEquals(
            GroundRecognition.RECOGNIZED,
            analyzer.analyze(mask(bottomClass = CityscapesTaxonomy.POLE)).recognition,
        )
    }

    @Test
    fun `a recognised wall or fence filling the bottom is not unrecognized ground`() {
        val analyzer = analyzer()

        for (barrier in listOf(CityscapesTaxonomy.WALL, CityscapesTaxonomy.FENCE)) {
            val result = analyzer.analyze(mask(bottomClass = barrier))
            assertEquals(GroundRecognition.RECOGNIZED, result.recognition)
            assertEquals(0f, result.unrecognizedRatio, 0f)
        }
    }

    @Test
    fun `terrain underfoot is ground even though it is not passable`() {
        val analyzer = analyzer()

        val result = analyzer.analyze(mask(bottomClass = CityscapesTaxonomy.TERRAIN))

        assertEquals(GroundRecognition.RECOGNIZED, result.recognition)
    }

    @Test
    fun `indoor floor read as building is uncertain at once and unrecognized once held`() {
        val analyzer = analyzer()
        val floor = mask(bottomClass = CityscapesTaxonomy.BUILDING)

        val first = analyzer.analyze(floor)
        assertEquals(GroundRecognition.UNCERTAIN, first.recognition)
        assertEquals(1f, first.unrecognizedRatio, 0f)

        now += config.groundUnrecognizedEnterMs - 1
        assertEquals(GroundRecognition.UNCERTAIN, analyzer.analyze(floor).recognition)

        now += 1
        assertEquals(GroundRecognition.UNRECOGNIZED, analyzer.analyze(floor).recognition)
    }

    @Test
    fun `a brief flicker never confirms and drops straight back to recognized`() {
        val analyzer = analyzer()

        assertEquals(
            GroundRecognition.UNCERTAIN,
            analyzer.analyze(mask(bottomClass = CityscapesTaxonomy.BUILDING)).recognition,
        )
        now += 100
        assertEquals(
            GroundRecognition.RECOGNIZED,
            analyzer.analyze(mask(bottomClass = CityscapesTaxonomy.SIDEWALK)).recognition,
        )
        // The enter timer restarted: holding again needs the full duration.
        now += 100
        analyzer.analyze(mask(bottomClass = CityscapesTaxonomy.BUILDING))
        now += config.groundUnrecognizedEnterMs - 1
        assertEquals(
            GroundRecognition.UNCERTAIN,
            analyzer.analyze(mask(bottomClass = CityscapesTaxonomy.BUILDING)).recognition,
        )
    }

    @Test
    fun `leaving needs the ground back for the exit duration`() {
        val analyzer = analyzer()
        confirmUnrecognized(analyzer)

        val sidewalk = mask(bottomClass = CityscapesTaxonomy.SIDEWALK)
        assertEquals(GroundRecognition.UNRECOGNIZED, analyzer.analyze(sidewalk).recognition)
        now += config.groundUnrecognizedExitMs - 1
        assertEquals(GroundRecognition.UNRECOGNIZED, analyzer.analyze(sidewalk).recognition)
        now += 1
        assertEquals(GroundRecognition.RECOGNIZED, analyzer.analyze(sidewalk).recognition)
    }

    @Test
    fun `a ratio between the thresholds keeps the current state`() {
        val analyzer = analyzer()
        // Half the region is building: above exit (0.35), below enter (0.60).
        val mixed = mask(bottomClass = CityscapesTaxonomy.SIDEWALK, unrecognizedFraction = 0.5f)

        assertEquals(GroundRecognition.RECOGNIZED, analyzer.analyze(mixed).recognition)

        confirmUnrecognized(analyzer)
        now += config.groundUnrecognizedExitMs * 10
        assertEquals(GroundRecognition.UNRECOGNIZED, analyzer.analyze(mixed).recognition)
    }

    @Test
    fun `only the bottom centre counts, so buildings beside a sidewalk do not trigger it`() {
        val analyzer = analyzer()
        // Portrait 90x160 mask: the centre band is 0.40 of the long side, about columns 13..76.
        // Sidewalk a few columns wider than that on each side; building beyond.
        val width = 90
        val height = 160
        val classMap = IntArray(width * height) { index ->
            val x = index % width
            if (x in 10 until 80) CityscapesTaxonomy.SIDEWALK else CityscapesTaxonomy.BUILDING
        }

        val result = analyzer.analyze(SegmentationMask(width, height, classMap))

        assertEquals(GroundRecognition.RECOGNIZED, result.recognition)
        assertEquals(0f, result.unrecognizedRatio, 0f)
    }

    @Test
    fun `reset forgets a confirmed state`() {
        val analyzer = analyzer()
        confirmUnrecognized(analyzer)

        analyzer.reset()

        assertEquals(
            GroundRecognition.RECOGNIZED,
            analyzer.analyze(mask(bottomClass = CityscapesTaxonomy.SIDEWALK)).recognition,
        )
    }

    @Test
    fun `disabled gate always reports recognized but still measures`() {
        val analyzer = analyzer(AnalysisConfig(enableGroundRecognitionGate = false))
        val floor = mask(bottomClass = CityscapesTaxonomy.BUILDING)

        analyzer.analyze(floor)
        now += config.groundUnrecognizedEnterMs * 2
        val result = analyzer.analyze(floor)

        assertEquals(GroundRecognition.RECOGNIZED, result.recognition)
        assertEquals(1f, result.unrecognizedRatio, 0f)
    }

    private fun confirmUnrecognized(analyzer: GroundRecognitionAnalyzer) {
        val floor = mask(bottomClass = CityscapesTaxonomy.BUILDING)
        analyzer.analyze(floor)
        now += config.groundUnrecognizedEnterMs
        assertEquals(GroundRecognition.UNRECOGNIZED, analyzer.analyze(floor).recognition)
    }

    /**
     * A portrait mask whose top is sky and whose bottom band is [bottomClass], with the left
     * [unrecognizedFraction] of every bottom-band row replaced by building.
     */
    private fun mask(
        bottomClass: Int,
        unrecognizedFraction: Float = 0f,
        width: Int = 90,
        height: Int = 160,
    ): SegmentationMask {
        val bottomStartY = ((1 - config.segmentationBottomRatio) * height).toInt()
        val classMap = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            when {
                y < bottomStartY -> CityscapesTaxonomy.SKY
                x < (unrecognizedFraction * width).toInt() -> CityscapesTaxonomy.BUILDING
                else -> bottomClass
            }
        }
        return SegmentationMask(width, height, classMap)
    }
}
