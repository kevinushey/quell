package com.kevinushey.quell.record

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import com.kevinushey.quell.data.MemoryBudget
import com.kevinushey.quell.data.ParsedRun
import com.kevinushey.quell.data.TimeSeries
import com.kevinushey.quell.dsp.Resampler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecorderStatus(
    val recording: Boolean = false,
    val elapsedSec: Double = 0.0,
    val sampleCount: Int = 0,
    val sampleRate: Double = 0.0,
    val latest: FloatArray = FloatArray(3),
    val sensorName: String? = null,
    val sensorAvailable: Boolean = true,
    /** The capture hit [Recorder.maxSamples] and was stopped automatically; it is still waiting to be saved. */
    val limitReached: Boolean = false,
)

/** Captures the device's own accelerometer at its fastest rate into memory. */
class Recorder(context: Context) : SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _status = MutableStateFlow(RecorderStatus(sensorName = sensor?.name, sensorAvailable = sensor != null))
    val status: StateFlow<RecorderStatus> = _status

    private var times = DoubleArray(1 shl 16)
    private var xs = DoubleArray(1 shl 16)
    private var ys = DoubleArray(1 shl 16)
    private var zs = DoubleArray(1 shl 16)
    private var count = 0
    private var startNanos = 0L
    private var startedAt: Date? = null
    private var lastStatusPush = 0L
    /** Highest median delivery rate seen in any recent window: an upper bound on the full-capture median. */
    private var peakRate = 0.0

    /** Heap-aware sample limit shared with the parser, so a saved recording is always analyzable untrimmed. */
    val maxSamples: Int = MemoryBudget.maxSamples()

    val minDelayMicros: Int get() = sensor?.minDelay ?: 0

    fun start(): Boolean {
        val s = sensor ?: return false
        count = 0
        startNanos = 0L
        startedAt = Date()
        // SENSOR_DELAY_FASTEST asks for the hardware maximum; some devices cap this without HIGH_SAMPLING_RATE_SENSORS.
        val ok = manager.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST)
        _status.value = RecorderStatus(recording = ok, sensorName = s.name, sensorAvailable = true)
        return ok
    }

    /** Stops and returns the captured run, or null if nothing useful was captured. */
    fun stop(): ParsedRun? {
        manager.unregisterListener(this)
        _status.value = _status.value.copy(recording = false)
        if (count < 16)
            return null

        val kept = analyzableCount()
        val series = TimeSeries(times.copyOf(kept), xs.copyOf(kept), ys.copyOf(kept), zs.copyOf(kept))
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return ParsedRun(
            series = series,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            sensorName = sensor?.name,
            recordedAt = startedAt?.let { fmt.format(it) },
            extraFields = mapOf(
                "accelerometer Vendor" to (sensor?.vendor ?: ""),
                "accelerometer Range" to (sensor?.maximumRange?.toString() ?: ""),
                "accelerometer Resolution" to (sensor?.resolution?.toString() ?: ""),
                "accelerometer MinDelay" to (sensor?.minDelay?.toString() ?: ""),
            ),
        )
    }

    fun suggestedName(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US)
        return "Recording ${fmt.format(startedAt ?: Date())}"
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (startNanos == 0L)
            startNanos = event.timestamp
        val t = (event.timestamp - startNanos) / 1e9
        if (count >= maxSamples || projectedLength(t) > maxSamples) {
            // The resampled grid (span times median rate) is what the analysis bounds, not the
            // event count; a timestamp gap makes it longer. Stop here and keep what we have.
            manager.unregisterListener(this)
            _status.value = _status.value.copy(recording = false, limitReached = true, sampleCount = count)
            return
        }
        if (count == times.size) {
            val n = count * 2
            times = times.copyOf(n); xs = xs.copyOf(n); ys = ys.copyOf(n); zs = zs.copyOf(n)
        }
        times[count] = t
        xs[count] = event.values[0].toDouble()
        ys[count] = event.values[1].toDouble()
        zs[count] = event.values[2].toDouble()
        count++

        val now = System.nanoTime()
        if (now - lastStatusPush > 100_000_000L) {
            lastStatusPush = now
            if (count > RATE_WINDOW) {
                val deltas = DoubleArray(RATE_WINDOW - 1) { times[count - 1 - it] - times[count - 2 - it] }.filter { it > 0 }.sorted()
                if (deltas.isNotEmpty())
                    peakRate = maxOf(peakRate, 1.0 / deltas[deltas.size / 2])
            }
            _status.value = _status.value.copy(
                elapsedSec = t,
                sampleCount = count,
                sampleRate = if (t > 0.5) (count - 1) / t else 0.0,
                latest = floatArrayOf(event.values[0], event.values[1], event.values[2]),
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Conservative stand-in for Resampler.resampledLength: the full-capture
     * median rate can never exceed the highest windowed median seen.
     */
    private fun projectedLength(spanSec: Double): Double {
        val running = if (spanSec > 0.5) (count - 1) / spanSec else 0.0
        return spanSec * maxOf(peakRate, running) + 1
    }

    /**
     * Number of leading samples whose exact resampled length fits the limit.
     * The projection is conservative, so this normally returns `count`.
     */
    private fun analyzableCount(): Int {
        val time = times.copyOf(count)
        val fs = Resampler.estimateSampleRate(time)
        if (fs <= 0 || Resampler.resampledLength(time, fs) <= maxSamples)
            return count
        val maxSpan = (maxSamples - 1) / fs
        var n = count
        while (n > 0 && time[n - 1] - time[0] > maxSpan)
            n--
        return n
    }

    companion object {
        private const val RATE_WINDOW = 1024
    }

    /** Everything captured so far. */
    fun snapshot(): TimeSeries? {
        if (count < 64)
            return null
        return TimeSeries(times.copyOf(count), xs.copyOf(count), ys.copyOf(count), zs.copyOf(count))
    }

    /** Last few seconds of samples, for a live preview spectrum. */
    fun recentWindow(seconds: Double): TimeSeries? {
        if (count < 64)
            return null
        val tEnd = times[count - 1]
        var start = count - 1
        while (start > 0 && tEnd - times[start] < seconds)
            start--
        val n = count - start
        return TimeSeries(
            times.copyOfRange(start, count),
            xs.copyOfRange(start, count),
            ys.copyOfRange(start, count),
            zs.copyOfRange(start, count),
        )
    }
}
