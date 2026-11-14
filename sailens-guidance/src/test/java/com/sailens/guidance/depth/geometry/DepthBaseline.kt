package com.sailens.guidance.depth.geometry

import com.sailens.core.mask.BinaryMask
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.GeometryQuality
import com.sailens.guidance.geometry.GroundFitter
import com.sailens.guidance.geometry.GroundObservation
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * A frozen record of depth fits, one line per fit, every field named: the observation's numbers as their raw float
 * bits, its three masks (a digest of size and bits, and the cell count), its time, and the fitter's guard right after
 * the fit -- the state ([GroundFitter.guardState]) and the history [GroundFitter.sameGuardHistory] compares (the hold
 * and the very cells of another level). Two records are the same fits only when every field of every line is the same.
 *
 * What a caller adds before the observation identifies the fit (which case, which run) and records what it was given
 * or published around it (the geometry, the snapshot's times).
 */
internal object DepthBaseline {

    /** One line's fields, in order. */
    class Line(val fields: List<Pair<String, String>>) {
        val text: String get() = fields.joinToString(" ") { (key, value) -> "$key=$value" }

        /** The fields that say which fit this is: everything before the observation. */
        val label: String get() = fields.takeWhile { it.first != VALIDITY }.joinToString(" ") { (key, value) -> "$key=$value" }
    }

    fun line(identity: List<Pair<String, String>>, observation: GroundObservation, fitter: GroundFitter?, extras: List<Pair<String, String>> = emptyList()): Line =
        Line(identity + observation(observation) + (fitter?.let(::guard) ?: emptyList()) + extras)

    fun observation(o: GroundObservation): List<Pair<String, String>> = listOf(
        VALIDITY to o.validity.name.lowercase(),
        "time" to (o.frameTimeNanos?.toString() ?: NONE),
        "scale" to bits(o.disparityScale),
        "shift" to bits(o.disparityShift),
        "inliers" to bits(o.inlierRatio),
        "confidence" to bits(o.confidence),
        "agrees" to o.planeAgreesWithGravity.toString(),
        "ground" to mask(o.groundMask),
        "above" to mask(o.aboveGroundMask),
        "below" to mask(o.belowGroundMask),
    )

    fun guard(fitter: GroundFitter): List<Pair<String, String>> {
        val state = fitter.guardState()
        return listOf(
            "holding" to state.holdingSwitch.toString(),
            "armed" to state.armed.toString(),
            "level" to (fitter.guardLevelCells()?.let(::mask) ?: NONE),
        )
    }

    /** The geometry a fit was given: its time and basis, quality, intrinsics and gravity, numbers as raw bits. */
    fun geometry(g: CameraGeometry): List<Pair<String, String>> = listOf(
        "frameTime" to (g.frameTimeNanos?.toString() ?: NONE),
        "basis" to g.timeBasis.name.lowercase(),
        "quality" to when (val q = g.quality) {
            GeometryQuality.Ok -> "ok"
            is GeometryQuality.Degraded -> "degraded:" + q.issues.map { it.name.lowercase() }.sorted().joinToString(",")
            is GeometryQuality.Unavailable -> "unavailable:" + q.issues.map { it.name.lowercase() }.sorted().joinToString(",")
        },
        "intrinsics" to (g.intrinsics?.let { "${bits(it.fx)},${bits(it.fy)},${bits(it.cx)},${bits(it.cy)},${it.width}x${it.height},${it.source.name.lowercase()},${it.bufferMappingAssumed}" } ?: NONE),
        "gravity" to (g.gravityInCamera?.let { "${bits(it.x)},${bits(it.y)},${bits(it.z)}" } ?: NONE),
        "gravityAge" to (g.gravityAgeNanos?.toString() ?: NONE),
    )

    fun bits(value: Float): String = "%08x".format(value.toRawBits())

    /** Size, a digest of the bits and the cell count. */
    fun mask(mask: BinaryMask): String {
        val packed = mask.copyPackedBits()
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ByteBuffer.allocate(8).putInt(mask.width).putInt(mask.height).array())
        val bytes = ByteBuffer.allocate(packed.size * Long.SIZE_BYTES).apply { packed.forEach { putLong(it) } }.array()
        digest.update(bytes)
        val hex = digest.digest().take(DIGEST_BYTES).joinToString("") { "%02x".format(it) }
        return "${mask.width}x${mask.height}:$hex:${mask.countTrue()}"
    }

    fun parse(text: String): Line = Line(
        text.split(' ').filter { it.isNotEmpty() }.map { field ->
            val at = field.indexOf('=')
            require(at > 0) { "not a field: $field" }
            field.substring(0, at) to field.substring(at + 1)
        },
    )

    /** How [actual] differs from [expected], field by field; [count] counts every difference, [kept] are the first. */
    class Comparison(val expectedLines: Int, val actualLines: Int, val kept: List<String>, val count: Int) {
        val same: Boolean get() = count == 0
        override fun toString(): String = if (same) {
            "$actualLines line(s), the same field by field"
        } else {
            "$count difference(s) over $expectedLines expected and $actualLines actual line(s); first: " + kept.joinToString("; ")
        }
    }

    /** Lines that start with `#` are a header: compared as text, never parsed. */
    fun compare(expected: List<String>, actual: List<String>, keep: Int = 20): Comparison {
        val kept = mutableListOf<String>()
        var count = 0
        fun differ(what: String) {
            count++
            if (kept.size < keep) kept += what
        }
        if (expected.size != actual.size) differ("${expected.size} line(s) expected, ${actual.size} given")
        for (index in 0 until minOf(expected.size, actual.size)) {
            val e = expected[index]
            val a = actual[index]
            if (e == a) continue
            if (e.startsWith("#") || a.startsWith("#")) {
                differ("line ${index + 1}: expected \"$e\", given \"$a\"")
                continue
            }
            val ef = parse(e)
            val af = parse(a)
            val label = ef.label
            if (ef.fields.map { it.first } != af.fields.map { it.first }) {
                differ("line ${index + 1} ($label): fields ${ef.fields.map { it.first }} expected, ${af.fields.map { it.first }} given")
                continue
            }
            for ((x, y) in ef.fields.zip(af.fields)) {
                if (x.second != y.second) differ("line ${index + 1} ($label): ${x.first} expected ${x.second}, given ${y.second}")
            }
        }
        return Comparison(expected.size, actual.size, kept, count)
    }

    const val NONE: String = "-"
    private const val VALIDITY = "validity"
    private const val DIGEST_BYTES = 12
}
