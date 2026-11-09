package com.sailens.shell.sim

import com.sailens.guidance.model.trace.GuidanceAlertKinds
import com.sailens.guidance.model.trace.GuidanceAlertSources
import com.sailens.guidance.sim.Pose
import com.sailens.guidance.sim.Scenario
import com.sailens.guidance.sim.ScenarioFrame
import com.sailens.guidance.sim.Scenarios.HEIGHT
import com.sailens.guidance.sim.Scenarios.PITCH
import com.sailens.guidance.sim.Scenarios.street
import com.sailens.guidance.sim.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The director's handshakes with the pipeline, apart from any timeline (`docs/geometry.md` §7): what it sends a frame
 * to, what it waits for and what it lets time pass over, and how its ledger keeps runs apart. Each case runs a short
 * session and reads the director's own log ([SessionRun.directorLog]) and the session's entries.
 */
class DirectorHandshakeTest {

    private val sidewalk = World(street())

    private fun standing(seconds: Int) = Scenario(
        "handshake", "",
        List(seconds * 10) { ScenarioFrame(Pose(HEIGHT, PITCH), sidewalk, stationary = true) },
        alignClockToFrames = true,
    )

    private fun session(
        actions: List<ScriptAction>,
        endAtMs: Long,
        seconds: Int = (endAtMs / 1000).toInt(),
        cancellable: Boolean = true,
        predict: ((Int) -> Boolean)? = null,
    ) = SessionScript(
        name = "handshake",
        description = "",
        scenario = standing(seconds),
        actions = actions,
        endAtMs = endAtMs,
        gates = mapOf(ScriptAction.SEM to cancellable),
        predict = predict,
    )

    private fun SessionRun.states() = entries.filterIsInstance<TimelineEntry.State>()

    private fun SessionRun.frames() = entries.filterIsInstance<TimelineEntry.Frame>()

    private fun SessionRun.systemAlerts() = entries.filterIsInstance<TimelineEntry.Alert>().filter { it.trace.source == GuidanceAlertSources.SYSTEM }

    private fun SessionRun.count(line: String) = directorLog.count { it == line }

    private fun SessionRun.index(line: String): Int = directorLog.indexOf(line).also { assertTrue("the director log has \"$line\": $directorLog", it >= 0) }

    @Test
    fun `an initialisation that takes long receives no frame, while the polls go on and the startup stall is alarmed at 20 s`() {
        val run = SessionSimulator.run(session(listOf(ScriptAction.Start(0)), endAtMs = 21_000))
        assertEquals("no frame is received", emptyList<Int>(), run.delivered)
        assertTrue("the run waits at the gate", "run 1: init waiting at sem" in run.directorLog)
        assertEquals("the polls go on, every 500 ms", (0..21_000 step 500).map { "delay $it+500" }, run.directorLog.filter { it.startsWith("delay") })
        val stall = run.states().single { it.trace.stateLabel == "stop_required(startup_stalled)" }
        assertEquals(20_000L, stall.atMs)
        assertEquals("poll", stall.trace.cause)
        assertTrue("and alarmed", run.systemAlerts().any { it.atMs == 20_000L && it.trace.kind == GuidanceAlertKinds.STALLED })
    }

    @Test
    fun `an initialisation that fails at once receives no frame, fails the flow once and ends the run once`() {
        val run = SessionSimulator.run(
            session(listOf(ScriptAction.Start(0), ScriptAction.FailInit(0, "the model could not load")), endAtMs = 3_000),
        )
        assertEquals("no frame is received", emptyList<Int>(), run.delivered)
        assertEquals("the flow fails once", 1, run.states().count { it.trace.cause == "flow_failed" })
        assertEquals("failed(error)", run.states().single { it.trace.cause == "flow_failed" }.trace.stateLabel)
        assertEquals("the run ends once", 1, run.count("run 1: ended"))
        assertEquals("its record stays: a failed run has no stop", mapOf(1 to "run 1 ended"), run.ledger)
    }

    @Test
    fun `a stop while the initialisation waits at a gate that gives way ends the run, back to idle, and time goes on`() {
        val run = SessionSimulator.run(
            session(listOf(ScriptAction.Start(0), ScriptAction.Stop(500), ScriptAction.SpeechSetting(5_000, on = true)), endAtMs = 6_000),
        )
        assertTrue("no failure", run.states().none { it.trace.state == "failed" })
        assertEquals("idle", run.states().last().trace.stateLabel)
        assertTrue("the run gave way", run.index("run 1: stopped while awaiting_init") < run.index("run 1: ended"))
        assertTrue("and left the ledger", run.index("run 1: ended") < run.index("run 1: removed") && run.index("run 1: reset done") < run.index("run 1: removed"))
        assertTrue("time went on to the next event", run.entries.any { it is TimelineEntry.Script && it.atMs == 5_000L })
        assertEquals(emptyMap<Int, String>(), run.ledger)
    }

    /** Stop at 1000 while held at a gate that does not give way, start again at 1500, the gate opens at 4000. */
    private fun restartBehindHeldRun(reverseCompletion: Boolean): SessionRun = SessionSimulator.run(
        session(
            listOf(
                ScriptAction.Start(0),
                ScriptAction.Stop(1_000),
                ScriptAction.Start(1_500),
                ScriptAction.Background(2_000),
                ScriptAction.Foreground(2_500),
                ScriptAction.CameraResume(2_500),
                ScriptAction.ReleaseInit(4_000),
            ),
            endAtMs = 6_000,
            cancellable = false,
        ),
        reverseCompletion = reverseCompletion,
    )

    @Test
    fun `a restart waits for the run held at a gate that does not give way, and time goes on until it opens`() {
        for (reverse in listOf(false, true)) {
            val run = restartBehindHeldRun(reverse)
            val log = run.directorLog
            val name = if (reverse) "the reset read first" else "the run's end read first"
            assertTrue("$name: the scripts between the stop and the gate run at their times", listOf(1_500L, 2_000L, 2_500L).all { at -> run.entries.any { it is TimelineEntry.Script && it.atMs == at } })
            // The partial order within the event at 4000.
            val ended = run.index("job 1 completed")
            val reset = log.withIndex().first { (i, line) -> i > ended && line == "reset" }.index
            val began = run.index("run 2: began")
            val subscribed = run.index("run 2: subscribed")
            assertTrue("$name: run 1 ends, then its reset, then run 2 begins and subscribes: $log", ended < reset && reset < began && began < subscribed)
            assertEquals("$name: run 1's end read once", 1, run.count("run 1: ended"))
            assertEquals("$name: its reset's end read once", 1, run.count("run 1: reset done"))
            val removed = run.index("run 1: removed")
            assertTrue("$name: its record goes only after both", removed > run.index("run 1: ended") && removed > run.index("run 1: reset done"))
            assertTrue(
                "$name: the two were read in the order asked for",
                if (reverse) run.index("run 1: reset done") < run.index("run 1: ended") else run.index("run 1: ended") < run.index("run 1: reset done"),
            )
            // The new run's stall watch and flow start at 4000, not at 1500.
            assertEquals("$name: the flow starts once a run begins", listOf(0L, 4_000L), run.states().filter { it.trace.cause == "flow_started" }.map { it.atMs })
            val delays = log.filter { it.startsWith("delay") }
            assertTrue("$name: no stall loop between the stop and 4000: $delays", delays.none { d -> d.removePrefix("delay ").substringBefore('+').toLong() in 1_000L until 4_000L })
            assertTrue("$name: the new run's stall loop starts at 4000", "delay 4000+500" in delays)
            // Nothing of run 1 reached run 2 or the timeline.
            assertEquals("$name: the first frame received is the first after 4000, by run 2", 4_050L, run.frames().first().atMs)
            assertTrue("$name: no frame before 4000", run.delivered.none { run.script.frameAtMs(it) < 4_000 })
            assertTrue("$name: no interruption before the run is running", run.systemAlerts().none { it.trace.kind == GuidanceAlertKinds.INTERRUPTED })
        }
    }

    /**
     * The second stop is the screen going away: before its block begins the restarted run is not running yet, so the
     * toggle would start again, and the screen's `onCleared` is the one stop that can come then.
     */
    @Test
    fun `a restart stopped again before the held run ends never waits for a reset, and its block never begins`() {
        val run = SessionSimulator.run(
            session(
                listOf(
                    ScriptAction.Start(0),
                    ScriptAction.Stop(1_000),
                    ScriptAction.Start(1_500),
                    ScriptAction.LeaveScreen(2_000),
                    ScriptAction.ReleaseInit(4_000),
                ),
                endAtMs = 6_000,
                cancellable = false,
            ),
            // The resets hang on the gate until 4000: a director that waited for one would give up here.
            handshakeTimeoutMs = 2_000,
        )
        val log = run.directorLog
        assertTrue("the second start is asked for but never begins: $log", "run 2: requested" in log && "run 2: began" !in log)
        assertTrue("its stop belongs to no record", "run 2: stopped before it began" in log)
        assertTrue("the scripts before the gate run at their times", listOf(1_500L, 2_000L).all { at -> run.entries.any { it is TimelineEntry.Script && it.atMs == at } })
        // The first stop's reset was in the view model's scope, which the screen's going away cancels; the release's
        // reset, in its own scope, runs once the held run ends.
        assertEquals("one reset runs, after the held run ends: $log", 1, log.count { it == "reset" })
        assertTrue("after the held run ends: $log", log.indexOf("reset") > run.index("job 1 completed"))
        assertEquals("nothing is left", emptyMap<Int, String>(), run.ledger)
        assertEquals("idle", run.states().last().trace.stateLabel)
        assertEquals("no frame is received", emptyList<Int>(), run.delivered)
    }

    @Test
    fun `a restart once initialised skips the gate and receives its first frame`() {
        val run = SessionSimulator.run(
            session(SessionScript.defaultActions(ScriptAction.Stop(3_000), ScriptAction.Start(3_500)), endAtMs = 5_000),
        )
        assertEquals("only the first run waits at the gate", 1, run.directorLog.count { it.endsWith("init waiting at sem") })
        val firstAfter = run.delivered.first { run.script.frameAtMs(it) > 3_500 }
        assertEquals("the first frame after the restart is received", 3_550L, run.script.frameAtMs(firstAfter))
        assertEquals("and guides", 3_550L, run.states().first { it.atMs > 3_500 && it.trace.state == "guiding" }.atMs)
    }

    @Test
    fun `the thirtieth failure in a row ends the run without its frame being processed`() {
        val run = SessionSimulator.run(SessionScenarios.pipelineFailureSlow)
        val first = SessionScenarios.pipelineFailureSlow.scenario.frames.indexOfFirst { it.semFails } + 1
        val processed = run.directorLog.filter { it.startsWith("run 1: processed ") }.map { it.substringAfterLast(' ').toLong() }
        assertTrue("the first 29 failures are processed", (first until first + 29L).all { it in processed })
        assertFalse("the thirtieth is not", first + 29L in processed)
        assertEquals("the run ends", 1, run.count("run 1: ended"))
    }

    @Test
    fun `a frame source that completes ends the run, later frames go to nobody and the screen still says running`() {
        val run = SessionSimulator.run(session(SessionScript.defaultActions(ScriptAction.EndFrames(3_000)), endAtMs = 5_000))
        assertEquals("the run has ended and keeps its record", mapOf(1 to "run 1 ended"), run.ledger)
        assertTrue("no frame after the end", run.delivered.none { run.script.frameAtMs(it) > 3_000 })
        assertTrue("running", run.finalUi.isRunning)
        assertTrue("never failed", run.states().none { it.trace.state == "failed" })
    }

    @Test
    fun `a wrong prediction fails at its frame`() {
        for ((predict, message) in listOf<Pair<(Int) -> Boolean, String>>(
            { index: Int -> index != 5 } to "seq 6 was expected to give no result and gave one",
            { _: Int -> false } to "seq 1 was expected to give no result and gave one",
        )) {
            try {
                SessionSimulator.run(session(SessionScript.defaultActions(), endAtMs = 2_000, predict = predict))
                fail("a wrong prediction must fail")
            } catch (error: AssertionError) {
                assertTrue("${error.message}", error.message!!.startsWith(message))
            }
        }
        val failing = SessionScenarios.pipelineFailureSlow
        try {
            SessionSimulator.run(failing.copy(predict = { true }))
            fail("a wrong prediction must fail")
        } catch (error: AssertionError) {
            val seq = failing.scenario.frames.indexOfFirst { it.semFails } + 1
            assertTrue("${error.message}", error.message!!.startsWith("seq $seq was expected to give a result and gave none"))
        }
    }
}
