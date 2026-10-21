package com.sailens.guidance.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Every M2a scenario, run through the real pipeline, must produce its golden output line for line
 * (implementation doc §6). A change in Guidance behaviour shows up here as a diff to review; when it
 * is intended, regenerate and commit the goldens with the change:
 *
 * ```
 * gradlew :sailens-guidance:testDebugUnitTest --tests "*ScenarioGoldenTest*" -PupdateGoldens
 * ```
 */
class ScenarioGoldenTest {

    @Test fun `straight clear path`() = check(Scenarios.straightClearPath)
    @Test fun `pitch change with a fixed obstacle`() = check(Scenarios.pitchChangeFixedObstacle)
    @Test fun `phone height change`() = check(Scenarios.phoneHeightChange)
    @Test fun `ground and no ground`() = check(Scenarios.groundNoGround)
    @Test fun `curb and drop`() = check(Scenarios.curbAndDrop)
    @Test fun `partially occluded person`() = check(Scenarios.partiallyOccludedPerson)
    @Test fun `tilt sweep near a person`() = check(Scenarios.tiltSweepNearPerson)
    @Test fun `approach a parked car`() = check(Scenarios.approachParkedCar)

    @Test
    fun `every scenario has a golden test`() {
        val tested = javaClass.declaredMethods.count { it.isAnnotationPresent(Test::class.java) } - 2
        assertEquals("add a test per scenario", Scenarios.all.size, tested)
    }

    @Test
    fun `a run is deterministic`() {
        val scenario = Scenarios.partiallyOccludedPerson
        val first = GuidanceSimulator().run(scenario).map(GuidanceSimulator::goldenLine)
        val second = GuidanceSimulator().run(scenario).map(GuidanceSimulator::goldenLine)
        assertEquals(first, second)
    }

    private fun check(scenario: Scenario) {
        val results = GuidanceSimulator().run(scenario)
        assertEquals("every frame produces a result", scenario.frames.size, results.size)
        Goldens.check(scenario, "${scenario.name}.golden.txt", results.map(GuidanceSimulator::goldenLine))
    }
}

/** Compares a scenario's lines with its checked-in golden file, or rewrites it under -PupdateGoldens. */
object Goldens {
    private val update = System.getProperty("sailens.updateGoldens") == "true"

    fun check(scenario: Scenario, fileName: String, lines: List<String>) {
        val actual = listOf("# ${scenario.name}: ${scenario.description}") + lines
        if (update) {
            val dir = File(checkNotNull(System.getProperty("sailens.goldens.dir")) { "goldens dir not set" })
            dir.mkdirs()
            File(dir, fileName).writeText(actual.joinToString("\n", postfix = "\n"))
            return
        }
        val resource = javaClass.getResource("/scenarios/$fileName")
            ?: return fail("no golden $fileName; generate it with -PupdateGoldens")
        val expected = resource.readText().trimEnd('\n').split('\n')
        val firstDiff = (0 until maxOf(expected.size, actual.size)).firstOrNull { expected.getOrNull(it) != actual.getOrNull(it) }
            ?: return
        fail(
            "$fileName differs from its golden at line ${firstDiff + 1}\n" +
                "  golden: ${expected.getOrNull(firstDiff) ?: "<end>"}\n" +
                "  actual: ${actual.getOrNull(firstDiff) ?: "<end>"}\n" +
                "If the change is intended, regenerate with -PupdateGoldens and commit the diff.",
        )
    }
}
