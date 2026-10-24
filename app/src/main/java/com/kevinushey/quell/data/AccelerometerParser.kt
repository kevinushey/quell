package com.kevinushey.quell.data

import com.kevinushey.quell.dsp.Resampler
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.util.zip.ZipInputStream

/**
 * Reads phyphox exports (zip containing "Raw Data.csv" plus meta/) and plain
 * CSV files with a time column and one or more acceleration columns.
 */
object AccelerometerParser {

    fun parse(input: InputStream, fileName: String): ParsedRun {
        // Content URIs often lack an extension, so decide by content when we can peek.
        val buffered = input as? BufferedInputStream ?: BufferedInputStream(input)
        return if (isZip(buffered) || fileName.lowercase().endsWith(".zip"))
            parseZip(buffered)
        else
            parseCsv(buffered)
    }

    private fun isZip(input: BufferedInputStream): Boolean {
        val magic = ByteArray(4)
        input.mark(magic.size)
        val n = input.read(magic)
        input.reset()
        return n == 4 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte() &&
            magic[2] == 3.toByte() && magic[3] == 4.toByte()
    }

    fun parseZip(input: InputStream): ParsedRun {
        var series: TimeSeries? = null
        var seriesScore = -1
        var lastError: String? = null
        var device: Map<String, String> = emptyMap()
        var recordedAt: String? = null

        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory)
                    continue

                val name = entry.name.substringAfterLast('/')
                val path = entry.name.lowercase()
                when {
                    path.endsWith("meta/device.csv") -> device = parseKeyValueCsv(readAll(zip))
                    path.endsWith("meta/time.csv") -> recordedAt = parseStartTime(readAll(zip))
                    name.lowercase().endsWith(".csv") -> {
                        // phyphox experiments can export several tables; prefer the one that looks
                        // like raw samples. Score from the header alone and stream the rows, so a
                        // large table never has to exist in memory as text.
                        val lines = LineReader(InputStreamReader(zip, Charsets.UTF_8))
                        // Header peek included: a malformed auxiliary table must not sink the whole import.
                        runCatching {
                            val header = lines.readLine() ?: ""
                            lines.pushBack(header)
                            val score = csvScore(name, header)
                            if (score > seriesScore) {
                                series = parseCsvLines(lines)
                                seriesScore = score
                            }
                        }.onFailure { lastError = it.message }
                    }
                }
                zip.closeEntry()
            }
        }

        val s = series ?: throw ImportException(lastError ?: "No acceleration CSV found inside the zip file")
        return ParsedRun(
            series = s,
            deviceModel = device["deviceModel"]?.let { m -> device["deviceBrand"]?.let { "$it $m" } ?: m },
            sensorName = device["accelerometer Name"],
            recordedAt = recordedAt,
            extraFields = device.filterKeys { it.startsWith("accelerometer") || it == "version" }
        )
    }

    fun parseCsv(input: InputStream): ParsedRun = ParsedRun(parseCsvLines(LineReader(InputStreamReader(input, Charsets.UTF_8))))

    private fun csvScore(name: String, headerLine: String): Int {
        var score = 0
        if (name.contains("raw", ignoreCase = true))
            score += 10
        val header = headerLine.lowercase()
        if (header.contains("time"))
            score += 5
        if (header.contains("acceleration") || header.contains("accel"))
            score += 5
        return score
    }

    fun parseCsvText(text: String): TimeSeries = parseCsvLines(LineReader(text.reader()))

    /** Rows are consumed as they are read; nothing beyond the sample arrays is held. */
    fun parseCsvLines(reader: LineReader): TimeSeries {
        var headerLine: String? = null
        // Skip blank lines and comments ahead of the header.
        while (true) {
            val line = reader.readLine() ?: break
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#"))
                continue
            headerLine = trimmed.removePrefix("\uFEFF")
            break
        }
        val header = headerLine ?: throw ImportException("File is empty")
        val delimiter = detectDelimiter(header)
        val columns = splitLine(header, delimiter).map { it.trim().trim('"').lowercase() }

        // Letters alone are not enough: a header-less first row may use scientific notation (1.0E-3).
        // phyphox's "decimal comma" exports use ';' or a tab, so ',' cannot be the delimiter there.
        val decimalComma = delimiter != ','
        val hasHeader = columns.any { finite(it, decimalComma) == null }
        val layout = if (hasHeader)
            ColumnLayout.fromHeader(columns)
        else
            ColumnLayout.positional(columns.size)

        val maxRows = MemoryBudget.maxSamples()
        val time = DoubleList()
        val x = DoubleList()
        val y = DoubleList()
        val z = DoubleList()
        // The representation is fixed by the header: a bad axis cell in an XYZ file
        // skips that row rather than switching it to magnitude-on-X.
        val axes = layout.x != null && layout.y != null && layout.z != null

        fun consume(line: String) {
            val cells = splitLine(line, delimiter)
            if (cells.size <= layout.maxIndex)
                return
            val t = finite(cells[layout.time], decimalComma) ?: return
            if (axes) {
                val vx = finite(cells[layout.x!!], decimalComma) ?: return
                val vy = finite(cells[layout.y!!], decimalComma) ?: return
                val vz = finite(cells[layout.z!!], decimalComma) ?: return
                time.add(t); x.add(vx); y.add(vy); z.add(vz)
            } else {
                // Magnitude-only files: keep it on one axis so |a| and the summed
                // Total power still equal the supplied values.
                val vm = finite(cells[layout.magnitude!!], decimalComma) ?: return
                time.add(t); x.add(vm); y.add(0.0); z.add(0.0)
            }
            if (time.size > maxRows)
                throw ImportException("More than $maxRows samples; split the recording before importing")
        }

        if (!hasHeader)
            consume(header)
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank())
                continue
            consume(line)
        }

        if (time.size < 16)
            throw ImportException("Too few samples (${time.size}); is this an accelerometer export?")

        val series = ensureMonotonic(TimeSeries(time.toArray(), x.toArray(), y.toArray(), z.toArray()))
        checkResamplable(series)
        return series
    }

    /** NaN and Infinity parse as doubles but poison every FFT downstream. */
    private fun finite(cell: String, decimalComma: Boolean): Double? {
        val text = if (decimalComma) cell.replace(',', '.') else cell
        return text.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /**
     * A single wild timestamp (epoch seconds among relative ones, or the wrong
     * unit) makes the uniform grid impossibly large. Refuse it here, before the
     * dataset is persisted and re-analyzed on every launch.
     */
    private fun checkResamplable(s: TimeSeries) {
        val fs = Resampler.estimateSampleRate(s.time)
        val length = Resampler.resampledLength(s.time, fs)
        if (fs <= 0 || length < Resampler.MIN_SAMPLES)
            throw ImportException("The time column does not advance; check that the first column holds timestamps in seconds.")
        if (length > MemoryBudget.maxSamples())
            throw ImportException(
                "Timestamps are inconsistent: %.0f s span at %.1f Hz would need %d samples. Check the time column's units.".format(
                    java.util.Locale.US,
                    s.time.last() - s.time.first(),
                    fs,
                    length,
                )
            )
    }

    /** Timestamps are expected sorted; if not, sort them so interpolation works. */
    private fun ensureMonotonic(s: TimeSeries): TimeSeries {
        var sorted = true
        for (i in 1 until s.size) {
            if (s.time[i] < s.time[i - 1]) {
                sorted = false
                break
            }
        }
        if (sorted)
            return s

        val order = (0 until s.size).sortedBy { s.time[it] }
        return TimeSeries(
            DoubleArray(s.size) { s.time[order[it]] },
            DoubleArray(s.size) { s.x[order[it]] },
            DoubleArray(s.size) { s.y[order[it]] },
            DoubleArray(s.size) { s.z[order[it]] },
        )
    }

    private class ColumnLayout(val time: Int, val x: Int?, val y: Int?, val z: Int?, val magnitude: Int?) {
        val maxIndex: Int = listOfNotNull(time, x, y, z, magnitude).max()

        companion object {
            fun fromHeader(cols: List<String>): ColumnLayout {
                val time = cols.indexOfFirst { it.startsWith("time") || it == "t" || it.contains("time (") || it.contains("timestamp") }
                if (time < 0)
                    throw ImportException("No time column found. Header: ${cols.joinToString()}")

                fun axis(letter: String): Int? {
                    val exact = cols.indexOfFirst { it == letter || it == "a$letter" || it == "acc$letter" }
                    if (exact >= 0)
                        return exact
                    val idx = cols.indexOfFirst { c ->
                        c != cols[time] && Regex("(^|[^a-z])$letter([^a-z]|$)").containsMatchIn(c) &&
                            !c.contains("absolute") && !c.contains("magnitude")
                    }
                    return if (idx >= 0) idx else null
                }

                val x = axis("x")
                val y = axis("y")
                val z = axis("z")
                val mag = cols.indexOfFirst { it.contains("absolute") || it.contains("magnitude") || it.contains("|a|") || it.contains("total") }
                    .takeIf { it >= 0 }
                if ((x == null || y == null || z == null) && mag == null)
                    throw ImportException("No acceleration columns found. Header: ${cols.joinToString()}")
                return ColumnLayout(time, x, y, z, mag)
            }

            fun positional(count: Int): ColumnLayout = when {
                count >= 4 -> ColumnLayout(0, 1, 2, 3, null)
                count == 2 -> ColumnLayout(0, null, null, null, 1)
                else -> throw ImportException("Cannot infer columns from a header-less file with $count columns")
            }
        }
    }

    private fun detectDelimiter(header: String): Char {
        // A decimal-comma row ("0;0,5;3,4") has at least as many commas as separators, so ';' and
        // tab win whenever they appear; whitespace only wins when no real separator is present.
        for (c in listOf(';', '\t'))
            if (header.count { it == c } > 0)
                return c
        return if (header.count { it == ',' } > 0) ',' else ' '
    }

    private fun splitLine(line: String, delimiter: Char): List<String> {
        if (delimiter == ' ')
            return line.trim().split(Regex("\\s+"))

        val out = ArrayList<String>(8)
        val sb = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == delimiter && !inQuotes -> {
                    out.add(sb.toString().trim())
                    sb.setLength(0)
                }
                else -> sb.append(ch)
            }
        }
        out.add(sb.toString().trim())
        return out
    }

    private fun parseKeyValueCsv(text: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        text.lineSequence().drop(1).forEach { line ->
            val cells = splitLine(line, ',')
            if (cells.size >= 2 && cells[1] != "null")
                map[cells[0]] = cells[1]
        }
        return map
    }

    private fun parseStartTime(text: String): String? {
        return text.lineSequence().drop(1)
            .map { splitLine(it, ',') }
            .firstOrNull { it.size >= 4 && it[0] == "START" }
            ?.get(3)
    }

    /** Only metadata tables are read whole; sample tables stream through [parseCsvLines]. */
    private const val MAX_META_CHARS = 1 shl 20

    /** Longest line a real CSV export could plausibly contain. */
    const val MAX_LINE_CHARS = 1 shl 16

    /**
     * Line reader with a hard cap on line length, so one pathological line
     * cannot exhaust the heap the way BufferedReader.readLine would.
     */
    class LineReader(private val reader: Reader, private val maxChars: Int = MAX_LINE_CHARS) {
        private val buf = CharArray(1 shl 16)
        private var pos = 0
        private var len = 0
        private var eof = false
        private var pushedBack: String? = null
        private var skipLf = false
        private val sb = StringBuilder()

        /** Hands a line back so the next [readLine] returns it again (used to peek at the header). */
        fun pushBack(line: String) {
            pushedBack = line
        }

        fun readLine(): String? {
            pushedBack?.let {
                pushedBack = null
                return it
            }
            sb.setLength(0)
            var consumed = false
            while (true) {
                if (pos >= len) {
                    if (eof)
                        break
                    val n = reader.read(buf)
                    if (n < 0) {
                        eof = true
                        break
                    }
                    pos = 0
                    len = n
                    if (n == 0)
                        continue
                    // A CRLF split across buffers: the CR already ended the line.
                    if (skipLf) {
                        skipLf = false
                        if (buf[0] == '\n')
                            pos = 1
                        if (pos >= len)
                            continue
                    }
                }
                var i = pos
                while (i < len && buf[i] != '\n' && buf[i] != '\r')
                    i++
                if (sb.length + (i - pos) > maxChars)
                    throw ImportException("A line exceeds $maxChars characters; this does not look like a CSV export")
                sb.appendRange(buf, pos, i)
                consumed = true
                if (i < len) {
                    val cr = buf[i] == '\r'
                    pos = i + 1
                    if (cr) {
                        if (pos < len) {
                            if (buf[pos] == '\n')
                                pos++
                        } else {
                            skipLf = true
                        }
                    }
                    return sb.toString()
                }
                pos = len
            }
            return if (consumed) sb.toString() else null
        }
    }

    private fun readAll(stream: InputStream): String {
        // Do not close: for zip entries the caller owns the stream.
        val reader = InputStreamReader(stream, Charsets.UTF_8)
        val sb = StringBuilder()
        val buf = CharArray(64 * 1024)
        while (true) {
            val n = reader.read(buf)
            if (n < 0)
                break
            if (sb.length + n > MAX_META_CHARS)
                throw ImportException("Metadata table is unexpectedly large")
            sb.appendRange(buf, 0, n)
        }
        return sb.toString()
    }

    /** Minimal growable double array; avoids boxing for large files. */
    private class DoubleList {
        private var data = DoubleArray(4096)
        var size = 0
            private set

        fun add(v: Double) {
            if (size == data.size)
                data = data.copyOf(size + size / 2)
            data[size++] = v
        }

        fun toArray(): DoubleArray = data.copyOf(size)
    }
}
