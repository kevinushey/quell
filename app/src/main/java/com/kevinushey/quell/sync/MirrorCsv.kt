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
     * the heap for this. [header] (see [header]) goes first. Returns the byte
     * length; with no [out] it only measures, to check a folder copy without
     * reading it.
     */
    fun writeSamples(bin: File, header: String, out: Writer?): Long {
        out?.write(header)
        out?.write(SAMPLES_HEADER)
        out?.write("\n")
        // Only the header can hold non-ASCII text (names, notes); the rows are ASCII.
        var bytes = header.toByteArray(Charsets.UTF_8).size + SAMPLES_HEADER.length + 1L
        DatasetRepository.streamSeries(bin) { t, x, y, z ->
            val row = "$t,$x,$y,$z\n"
            out?.write(row)
            bytes += row.length
        }
        return bytes
    }

    /** A samples CSV read back: its rows, and the '#' lines above the column header exactly as they were. */
    class Samples(val series: TimeSeries, val header: String)

    /**
     * Reads a samples CSV back exactly: Double.toString round-trips, and any
     * value it writes (NaN included) reads back. Another header, a malformed
     * row, a last row without its newline, or a row count other than the
     * index lists (a copy cut short, or one edited elsewhere) is refused, not
     * restored as a different run. The caller bounds [expectedRows] by the heap.
     */
    fun readSamples(input: Reader, expectedRows: Int): Samples {
        require(expectedRows >= 0)
        val lines = TerminatedLines(input)
        // The details header is skipped, not parsed: the index is what restore trusts. Bounded, as rows are.
        val header = StringBuilder()
        var first = lines.next()?.trimEnd('\r')
        while (first != null && first.startsWith("#")) {
            header.append(first).append('\n')
            if (header.length > MAX_HEADER_CHARS)
                throw IllegalStateException("has a header longer than any this app writes")
            first = lines.next()?.trimEnd('\r')
        }
        if (first != SAMPLES_HEADER)
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
        return Samples(TimeSeries(columns[0], columns[1], columns[2], columns[3]), header.toString())
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

    /** The [RUNS_COLUMNS] that hold numbers; the header leaves them unquoted so YAML reads them as numbers. */
    private val NUMERIC_COLUMNS = setOf("samples", "sample_rate_hz", "duration_s", "trim_start_s", "trim_end_s", "damper_mass_g", "sweep_value")

    /** One row per run in [metas]; [files] maps a run id to its samples CSV in the folder, when it has one. */
    fun runsTable(metas: List<DatasetMeta>, sweeps: List<Sweep>, files: Map<String, String>): String {
        val sweepsById = sweeps.associateBy { it.id }
        return buildString {
            append(RUNS_COLUMNS.joinToString(","))
            append("\n")
            for (m in metas) {
                val cells = listOf(files[m.id] ?: "") + details(m, m.sweepId?.let { sweepsById[it] })
                append(cells.joinToString(",") { field(it) })
                append("\n")
            }
        }
    }

    /** A run's [RUNS_COLUMNS] after "file", empty where it has no value. */
    private fun details(m: DatasetMeta, sweep: Sweep?): List<String> = listOf(
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

    /**
     * The run's runs.csv row, minus the file name, as '#' comment lines that
     * head its samples CSV, so the file still says what it is once it leaves
     * the folder. With the '#' and one space removed the lines are YAML;
     * CSV readers skip them given the comment character (pandas comment="#",
     * R comment.char = "#"), and the importer skips them on its own. Empty
     * values are left out.
     */
    fun header(meta: DatasetMeta, sweep: Sweep?): String = buildString {
        for ((key, value) in RUNS_COLUMNS.drop(1).zip(details(meta, sweep))) {
            if (value.isEmpty())
                continue
            if (key in NUMERIC_COLUMNS)
                append("# $key: ${yamlNumber(value)}\n")
            else if (fitsLiteralBlock(value))
                literalBlock(key, value)
            else
                append("# $key: ${yamlQuoted(value)}\n")
        }
    }

    /** Kotlin writes 1.0E10; YAML 1.1 readers (PyYAML, R's yaml) read a float only with the exponent's sign. */
    private fun yamlNumber(s: String): String = if ("E" in s && "E-" !in s) s.replace("E", "E+") else s

    /**
     * Characters YAML will not take as they are, or that some reader would
     * take as a line break; within a header line, a break would end the comment.
     */
    private fun needsEscape(c: Char): Boolean =
        c < ' ' || c in '\u007f'..'\u009f' || c == '\u2028' || c == '\u2029' || c == '\ufeff' || c == '\ufffe' || c == '\uffff'

    private fun yamlQuoted(s: String): String = buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c.code < 0x100 && needsEscape(c) -> append("\\x").append(c.code.toString(16).padStart(2, '0'))
                needsEscape(c) -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> append(c)
            }
        }
        append('"')
    }

    /**
     * Multi-line notes read better as a literal block than as one quoted line,
     * but a block carries them exactly only when nothing in them needs
     * escaping and they neither start nor end with whitespace (YAML would
     * take leading spaces as indentation and trailing breaks as chomping).
     */
    private fun fitsLiteralBlock(s: String): Boolean =
        '\n' in s && !s.first().isWhitespace() && !s.last().isWhitespace() && s.none { it != '\n' && needsEscape(it) }

    /** "|-" drops a trailing line break, which is exact as [fitsLiteralBlock] admits none. */
    private fun StringBuilder.literalBlock(key: String, value: String) {
        append("# $key: |-\n")
        for (line in value.split('\n')) {
            if (line.isEmpty())
                append("#\n")
            else
                append("#   $line\n")
        }
    }

    /** RFC 4180 quoting, applied only where a value needs it. */
    fun field(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    private fun number(v: Double): String = if (v.isFinite()) v.toString() else ""

    /** Far beyond any header this app writes; a longer run of '#' lines is not one of its files. */
    private const val MAX_HEADER_CHARS = 1 shl 20
}
