package com.kevinushey.quell.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import com.kevinushey.quell.data.AnalysisSettings
import com.kevinushey.quell.data.DatasetMeta
import com.kevinushey.quell.data.Sweep
import com.kevinushey.quell.data.TimeSeries
import com.kevinushey.quell.sync.MirrorCsv
import com.kevinushey.quell.ui.SpectrumResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes share-able files into the cache dir and hands them to the system share sheet. */
object Exporter {

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun exportDir(context: Context): File = File(context.cacheDir, "exports").apply { mkdirs() }

    suspend fun shareChartImage(context: Context, bitmap: Bitmap, baseName: String) = withContext(Dispatchers.IO) {
        val file = File(exportDir(context), "$baseName-${stamp()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        share(context, file, "image/png")
    }

    /** Wide CSV: one frequency column, one value column per visible dataset. */
    suspend fun shareSpectraCsv(context: Context, results: List<SpectrumResult>, settings: AnalysisSettings, yLabel: String) =
        withContext(Dispatchers.IO) {
            if (results.isEmpty())
                return@withContext
            val file = File(exportDir(context), "spectra-${stamp()}.csv")
            file.bufferedWriter().use { w ->
                w.write("# channel=${settings.channel.name} normalization=${settings.normalization.name} segment=${settings.segmentSize} window=${settings.window.name} values=$yLabel\n")
                w.write("frequency_hz")
                for (r in results) {
                    // A run that could not be reference-band normalized is on a different scale; say so in its header.
                    val label = r.meta.name + if (r.referenceBandMissing) " [reference band not covered; unnormalized]" else ""
                    w.write(",\"${label.replace("\"", "\"\"")}\"")
                }
                w.write("\n")
                // Union of every run's bins, so no run's coverage depends on which dataset is listed first.
                val grid = results.flatMap { it.normalized.freq.asList() }.distinct().sorted().toDoubleArray()
                // Interpolate each value as its row is written: nothing grid-sized is held per run.
                val spectra = results.map { com.kevinushey.quell.dsp.Spectrum(it.normalized.freq, it.display) }
                for (f in grid) {
                    w.write(f.toString())
                    for (s in spectra) {
                        w.write(",")
                        // Past a run's Nyquist there is no value; leave the cell empty.
                        if (s.supports(f))
                            w.write(s.interpolate(f).toString())
                    }
                    w.write("\n")
                }
            }
            share(context, file, "text/csv")
        }

    /** One row per sweep run: parameter value and the numbers the optimum is read from. */
    suspend fun shareSweepCsv(context: Context, sweep: com.kevinushey.quell.data.Sweep, summary: com.kevinushey.quell.ui.SweepSummary) =
        withContext(Dispatchers.IO) {
            val safe = sweep.name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
            val file = File(exportDir(context), "sweep-$safe-${stamp()}.csv")
            file.bufferedWriter().use { w ->
                val unit = sweep.parameter.unit.ifEmpty { "value" }
                w.write("# sweep=${sweep.name} parameter=${sweep.parameter.name} kind=${sweep.kind.name} metric=${com.kevinushey.quell.ui.SweepAnalysis.metricLabel(sweep.kind)}\n")
                summary.optimum?.let { w.write("# optimum=$it half_width=${summary.optimumHalfWidth}\n") }
                w.write("run,${sweep.parameter.name.lowercase(Locale.US)}_$unit,metric,metric_error,peak_hz,band_rms_db,problem\n")
                for (p in summary.points) {
                    w.write("\"${p.meta.name.replace("\"", "\"\"")}\",")
                    w.write("${p.value ?: ""},${csvNumber(p.metric)},${csvNumber(p.error)},${csvNumber(p.peakFreq)},${csvNumber(p.bandRmsDb)},${p.problem ?: ""}\n")
                }
            }
            share(context, file, "text/csv")
        }

    private fun csvNumber(v: Double): String = if (v.isFinite()) v.toString() else ""

    /** Re-export a dataset's raw samples as CSV (useful for recordings made in-app), headed by its details as the mirror's copy is. */
    suspend fun shareRawCsv(context: Context, meta: DatasetMeta, sweep: Sweep?, series: TimeSeries) = withContext(Dispatchers.IO) {
        val safe = meta.name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val file = File(exportDir(context), "$safe.csv")
        file.bufferedWriter().use { w ->
            w.write(MirrorCsv.header(meta, sweep))
            w.write("\"Time (s)\",\"Acceleration x (m/s^2)\",\"Acceleration y (m/s^2)\",\"Acceleration z (m/s^2)\",\"Absolute acceleration (m/s^2)\"\n")
            for (i in 0 until series.size) {
                val x = series.x[i]
                val y = series.y[i]
                val z = series.z[i]
                val m = Math.sqrt(x * x + y * y + z * z)
                w.write("${series.time[i]},$x,$y,$z,$m\n")
            }
        }
        share(context, file, "text/csv")
    }

    private fun share(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share ${file.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
