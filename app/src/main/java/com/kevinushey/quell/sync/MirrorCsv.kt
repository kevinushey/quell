package com.kevinushey.quell.sync

import com.kevinushey.quell.data.DatasetMeta
import com.kevinushey.quell.data.DatasetRepository
import com.kevinushey.quell.data.Sweep
import com.kevinushey.quell.data.TimeSeries
import java.io.File
import java.io.Reader
import java.io.Writer
import java.time.Instant
import java.util.Locale

/**
 * The folder's plain-text formats, so other tools can read a mirror without
 * this app: one samples CSV per run, and runs.csv describing every run.
 */
object MirrorCsv {

    /** The importer reads this header too, so a mirrored run can also be imported by hand. */
    const val SAMPLES_HEADER = "\"Time (s)\",\"Acceleration x (m/s^2)\",\"Acceleration y (m/s^2)\",\"Acceleration z (m/s^2)\""

    /**
     * Streamed row by row from the .bin: a long run must not be loaded into
     * the heap for this. Returns the byte length (the format is ASCII); with
     * no [out] it only measures, to check a folder copy without reading it.
     */
    fun writeSamples(bin: File, out: Writer?): Long {
        out?.write(SAMPLES_HEADER)
        out?.write("\n")
        var bytes = SAMPLES_HEADER.length + 1L
        DatasetRepository.streamSeries(bin) { t, x, y, z ->
            val row = "$t,$x,$y,$z\n"
            out?.write(row)
            bytes += row.length
        }
        return bytes
    }

    /**
     * Reads a samples CSV back exactly: Double.toString round-trips, and any
     * value it writes (NaN included) reads back. Another header, a malformed
     * row, a last row without its newline, or a row count other than the
     * index lists (a copy cut short, or one edited elsewhere) is refused, not
     * restored as a different run. The caller bounds [expectedRows] by the heap.
     */
    fun readSamples(input: Reader, expectedRows: Int): TimeSeries {
        require(expectedRows >= 0)
        val lines = TerminatedLines(input)
        if (lines.next()?.trimEnd('\r') != SAMPLES_HEADER)
            throw IllegalStateException("is not a samples file written by this app")

        val columns = Array(4) { DoubleArray(expectedRows) }
        var n = 0
        while (true) {
            val line = lines.next()?.trimEnd('\r') ?: break
            if (line.isEmpty())
                continue
            if (n == expectedRows)
                throw IllegalStateException("holds more than the $expectedRows samples the index lists")
            val cells = line.split(',')
            if (cells.size != 4)
                throw IllegalStateException("row ${n + 1} does not have four columns")
            for (k in 0 until 4)
                columns[k][n] = cells[k].toDoubleOrNull() ?: throw IllegalStateException("row ${n + 1} holds a value that is not a number")
            n++
        }

        // A cut inside the last row can still leave four numbers; only the missing newline gives it away.
        if (lines.fragment)
            throw IllegalStateException("ends partway through a row; the copy may be incomplete")
        if (n != expectedRows)
            throw IllegalStateException("holds $n of the $expectedRows samples the index lists; the copy may be incomplete")
        return TimeSeries(columns[0], columns[1], columns[2], columns[3])
    }

    /** Lines that end in '\n', without it; text left after the last one is flagged as [fragment], not returned. */
    private class TerminatedLines(private val input: Reader) {
        private val buffer = CharArray(1 shl 16)
        private var pos = 0
        private var end = 0
        private val line = StringBuilder()

        var fragment = false
            private set

        fun next(): String? {
            line.setLength(0)
            while (true) {
                if (pos == end) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        fragment = line.isNotEmpty()
                        return null
                    }
                    pos = 0
                    end = read
                }
                val start = pos
                while (pos < end && buffer[pos] != '\n')
                    pos++
                line.append(buffer, start, pos - start)
                if (pos < end) {
                    pos++
                    return line.toString()
                }
            }
        }
    }

    /** runs.csv columns: enough to pick runs and window them without reading index.json. */
    val RUNS_COLUMNS = listOf(
        "file", "id", "name", "notes", "kind", "recorded_at", "imported_at", "source",
        "samples", "sample_rate_hz", "duration_s", "trim_start_s", "trim_end_s", "damper_mass_g",
        "sweep", "sweep_parameter", "sweep_unit", "sweep_value", "device", "sensor",
    )

    /** One row per run in [metas]; [files] maps a run id to its samples CSV in the folder, when it has one. */
    fun runsTable(metas: List<DatasetMeta>, sweeps: List<Sweep>, files: Map<String, String>): String {
        val sweepsById = sweeps.associateBy { it.id }
        return buildString {
            append(RUNS_COLUMNS.joinToString(","))
            append("\n")
            for (m in metas) {
                val sweep = m.sweepId?.let { sweepsById[it] }
                val cells = listOf(
                    files[m.id] ?: "",
                    m.id,
                    m.name,
                    m.notes,
                    m.kind.name.lowercase(Locale.US),
                    m.recordedAt ?: "",
                    Instant.ofEpochMilli(m.importedAtEpochMs).toString(),
                    m.sourceName,
                    m.sampleCount.toString(),
                    number(m.sampleRate),
                    number(m.durationSec),
                    number(m.trimStart),
                    number(m.effectiveTrimEnd),
                    m.damperMassGrams?.let { number(it) } ?: "",
                    sweep?.name ?: "",
                    sweep?.parameter?.name?.lowercase(Locale.US) ?: "",
                    sweep?.parameter?.unit ?: "",
                    sweep?.valueOf(m)?.let { number(it) } ?: "",
                    m.deviceModel ?: "",
                    m.sensorName ?: "",
                )
                append(cells.joinToString(",") { field(it) })
                append("\n")
            }
        }
    }

    /** RFC 4180 quoting, applied only where a value needs it. */
    fun field(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    private fun number(v: Double): String = if (v.isFinite()) v.toString() else ""
}
