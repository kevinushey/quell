package com.kevinushey.quell.data

import kotlinx.serialization.Serializable

/** Raw samples as parsed from a file or recorded on-device. */
class TimeSeries(
    val time: DoubleArray,
    val x: DoubleArray,
    val y: DoubleArray,
    val z: DoubleArray,
) {
    init {
        require(time.size == x.size && x.size == y.size && y.size == z.size)
    }

    val size: Int get() = time.size
    val duration: Double get() = if (size > 1) time.last() - time.first() else 0.0

    /** Samples whose time (relative to the first sample) falls in [start, end]. */
    fun slice(start: Double, end: Double): TimeSeries {
        if (size == 0)
            return this
        val t0 = time.first()
        var a = 0
        while (a < size - 1 && time[a] - t0 < start)
            a++
        var b = size - 1
        while (b > a && time[b] - t0 > end)
            b--
        if (a == 0 && b == size - 1)
            return this
        return TimeSeries(time.copyOfRange(a, b + 1), x.copyOfRange(a, b + 1), y.copyOfRange(a, b + 1), z.copyOfRange(a, b + 1))
    }

    fun magnitude(): DoubleArray = DoubleArray(size) { Math.sqrt(x[it] * x[it] + y[it] * y[it] + z[it] * z[it]) }

    /** Samples with absolute time in [from, to]. */
    fun sliceTime(from: Double, to: Double): TimeSeries {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (time[mid] < from)
                lo = mid + 1
            else
                hi = mid
        }
        var end = lo
        var hi2 = size
        while (end < hi2) {
            val mid = (end + hi2) ushr 1
            if (time[mid] < to)
                end = mid + 1
            else
                hi2 = mid
        }
        return TimeSeries(time.copyOfRange(lo, end), x.copyOfRange(lo, end), y.copyOfRange(lo, end), z.copyOfRange(lo, end))
    }
}

@Serializable
enum class ExperimentKind(val label: String) {
    /** Plain accelerometer capture; takes part in the Spectrum and Signal tabs only. */
    GENERAL("General"),
    DRIVE("Drive"),
    TAP("Tap test");
}

@Serializable
data class DatasetMeta(
    val id: String,
    val name: String,
    val sourceName: String,
    val importedAtEpochMs: Long,
    val sampleCount: Int,
    val sampleRate: Double,
    val durationSec: Double,
    val colorIndex: Int,
    val visible: Boolean = true,
    val notes: String = "",
    val deviceModel: String? = null,
    val sensorName: String? = null,
    val recordedAt: String? = null,
    val extraFields: Map<String, String> = emptyMap(),
    val kind: ExperimentKind = ExperimentKind.GENERAL,
    /** Analysis window in seconds from the start of the recording; trimEnd <= 0 means "to the end". */
    val trimStart: Double = 0.0,
    val trimEnd: Double = 0.0,
    /** Mass of the damper fitted during this run, if any. */
    val damperMassGrams: Double? = null,
) {
    val isTrimmed: Boolean get() = trimStart > 0.0 || (trimEnd > 0.0 && trimEnd < durationSec)
    val effectiveTrimEnd: Double get() = if (trimEnd > 0.0) minOf(trimEnd, durationSec) else durationSec
}

/** A dataset with its samples loaded. */
class Dataset(val meta: DatasetMeta, val series: TimeSeries)
