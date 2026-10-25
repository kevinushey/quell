package com.kevinushey.quell

import com.kevinushey.quell.data.DatasetMeta
import com.kevinushey.quell.data.DatasetRepository
import com.kevinushey.quell.data.ExperimentKind
import com.kevinushey.quell.data.Sweep
import com.kevinushey.quell.data.SweepParameter
import com.kevinushey.quell.ui.SweepAnalysis
import com.kevinushey.quell.ui.SweepPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepAnalysisTest {

    private val sweep = Sweep("s", "Mass sweep", SweepParameter.MASS, ExperimentKind.DRIVE, createdAtEpochMs = 0)

    private fun meta(id: String, mass: Double?) =
        DatasetMeta(id, id, "$id.csv", 0, 10, 400.0, 1.0, 0, damperMassGrams = mass, sweepId = "s")

    private fun point(value: Double?, metric: Double, error: Double = 0.1, problem: String? = null) =
        SweepPoint(meta("m$value", value), value, metric, 25.0, metric, error, problem)

    @Test
    fun massSweepsReadTheDamperMass() {
        assertEquals(300.0, sweep.valueOf(meta("a", 300.0))!!, 0.0)
        val other = sweep.copy(parameter = SweepParameter.STIFFNESS)
        assertNull(other.valueOf(meta("a", 300.0)))
        assertEquals(12.0, other.valueOf(meta("a", 300.0).copy(sweepValue = 12.0))!!, 0.0)
    }

    @Test
    fun advancingRemembersTheStep() {
        val a = sweep.advanced(300.0)
        assertEquals(300.0, a.lastValue!!, 0.0)
        assertEquals(300.0, a.suggestedNext!!, 0.0)
        val b = a.advanced(350.0)
        assertEquals(50.0, b.step!!, 0.0)
        assertEquals(400.0, b.suggestedNext!!, 0.0)
        // Descending mass sweeps stop at zero instead of proposing a negative mass.
        assertEquals(0.0, sweep.advanced(30.0).advanced(10.0).suggestedNext!!, 0.0)
        // A recommendation from the analysis wins, and recording clears it.
        assertEquals(420.0, b.copy(nextValue = 420.0).suggestedNext!!, 0.0)
        assertNull(b.copy(nextValue = 420.0).advanced(420.0).nextValue)
        assertEquals("27.2 g", SweepParameter.MASS.format(27.2))
        assertEquals("30 g", SweepParameter.MASS.format(30.0))
    }

    @Test
    fun parabolaRecoversTheMinimum() {
        // peak dB = 0.001 (m - 420)^2 - 6, sampled at 300..500 g
        val pts = listOf(300.0, 350.0, 400.0, 450.0, 500.0).map { m -> point(m, 0.001 * (m - 420) * (m - 420) - 6) }
        val fit = SweepAnalysis.fit(pts)!!
        assertEquals(420.0, fit.extremum, 1e-6)
        assertEquals(-6.0, fit.at(420.0), 1e-6)
        assertEquals(0.0, fit.residualRms, 1e-9)

        val summary = SweepAnalysis.summarize(sweep, pts)
        assertEquals(420.0, summary.optimum!!, 1e-6)
        // One sigma = the median point error (0.1 dB): the curve rises 0.1 dB at sqrt(0.1 / 0.001) g from the optimum.
        assertEquals(10.0, summary.optimumHalfWidth, 1e-6)
        assertTrue(summary.note, summary.note.startsWith("Optimum near 420 g"))
        // 400 g and 450 g are both more than one sigma away: the optimum is predicted, not yet measured.
        assertTrue(summary.note, summary.note.contains("Record one at 420 g"))
        assertEquals(420.0, summary.nextValue!!, 1e-6)

        val confirmed = SweepAnalysis.summarize(sweep, pts + point(420.0, -6.0))
        assertNull(confirmed.nextValue)
    }

    @Test
    fun runsWithinTheirErrorBarsGiveNoOptimum() {
        // Spread of 1.7 dB against 1.8 dB per point: indistinguishable from flat.
        val pts = listOf(point(27.2, 1.4, error = 1.8), point(30.0, 0.1, error = 1.8), point(69.0, -0.3, error = 1.8))
        val summary = SweepAnalysis.summarize(sweep, pts)
        assertNull(summary.optimum)
        assertTrue(summary.note, summary.note.contains("less than twice their uncertainty"))
        // The best run is the heaviest, so the suggestion is one more step up.
        assertEquals(69.0 + 20.9, summary.nextValue!!, 1e-9)
    }

    @Test
    fun aVertexBelowZeroIsNotReportedAsAnOptimum() {
        // Convex fit 0.002 (m + 5)^2 - 10: lighter is better, with the vertex at -5 g just inside the range's margin.
        fun at(m: Double) = 0.002 * (m + 5) * (m + 5) - 10
        val fromZero = SweepAnalysis.summarize(sweep, listOf(0.0, 20.0, 40.0).map { point(it, at(it)) })
        assertNull(fromZero.optimum)
        assertTrue(fromZero.note, fromZero.note.contains("below zero"))
        // 0 g was already tried, so there is nothing lighter left to suggest.
        assertNull(fromZero.nextValue)
        val fromTen = SweepAnalysis.summarize(sweep, listOf(10.0, 30.0, 50.0).map { point(it, at(it)) })
        assertNull(fromTen.optimum)
        assertEquals(0.0, fromTen.nextValue!!, 0.0)
    }

    @Test
    fun tapSweepsWithinTheirErrorBarsShowDampingToFourDecimals() {
        // Damping ratios of 0.020-0.023 at +/- 0.003: in the dB format both would read 0.0.
        val tap = sweep.copy(kind = ExperimentKind.TAP)
        val pts = listOf(point(10.0, 0.020, error = 0.003), point(30.0, 0.023, error = 0.003), point(50.0, 0.021, error = 0.003))
        val summary = SweepAnalysis.summarize(tap, pts)
        assertNull(summary.optimum)
        assertTrue(summary.note, summary.note.contains("differ by 0.0030 between best and worst"))
        assertTrue(summary.note, summary.note.contains("uncertainty (0.0030)"))
    }

    @Test
    fun silentRunsAreReportedNotFitted() {
        // A flat zero PSD against a real baseline: the ratio is -infinity, which must not reach the fit or the table.
        val fs = 400.0
        val freq = DoubleArray(513) { it * fs / 1024 }
        val base = com.kevinushey.quell.dsp.Spectrum(freq, DoubleArray(513) { 1e-3 }, 4, 1024)
        val silent = com.kevinushey.quell.dsp.Spectrum(freq, DoubleArray(513), 4, 1024)
        fun result(id: String, s: com.kevinushey.quell.dsp.Spectrum) =
            com.kevinushey.quell.ui.SpectrumResult(meta(id, 50.0), s, s, DoubleArray(s.size), emptyList(), fs, 1.0)
        val pts = SweepAnalysis.drivePoints(sweep, listOf(result("quiet", silent)), result("base", base), 20.0, 30.0)
        assertEquals("no power inside the target band", pts[0].problem)
        assertTrue(!pts[0].usable)
    }

    @Test
    fun suggestionsNeverGoNegativeForMasses() {
        // Lighter keeps getting better: the next step down from 10 g would be -10 g.
        val pts = listOf(point(10.0, -4.0), point(30.0, -2.0), point(50.0, -1.0))
        val summary = SweepAnalysis.summarize(sweep, pts)
        assertEquals(0.0, summary.nextValue!!, 0.0)
        val position = sweep.copy(parameter = SweepParameter.POSITION)
        val pos = SweepAnalysis.summarize(position, listOf(point(10.0, -4.0), point(30.0, -2.0), point(50.0, -1.0)).map {
            SweepPoint(it.meta.copy(damperMassGrams = null, sweepValue = it.value), it.value, it.metric, 25.0, it.metric, 0.1, null)
        })
        assertEquals(-10.0, pos.nextValue!!, 1e-9)
    }

    @Test
    fun tinyStepsStillFit() {
        // Stiffness steps of 0.001 N/mm: the raw normal equations would be singular to the eye.
        val pts = listOf(0.010, 0.011, 0.012, 0.013).map { k -> point(k, 1e6 * (k - 0.0115) * (k - 0.0115) - 3) }
        val fit = SweepAnalysis.fit(pts)
        assertNotNull(fit)
        assertEquals(0.0115, fit!!.extremum, 1e-9)
    }

    @Test
    fun repeatRunsKeepTheStep() {
        val s = sweep.advanced(300.0).advanced(350.0).advanced(350.0)
        assertEquals(50.0, s.step!!, 0.0)
        assertEquals(400.0, s.suggestedNext!!, 0.0)
    }

    @Test
    fun repeatsArePooledWithTighterErrors() {
        // Three runs at 350 g with 1.8 dB each: the pooled error is 1.8 / sqrt(3) unless the repeats disagree more.
        val pts = listOf(point(300.0, -2.0, 1.8), point(350.0, -4.0, 1.8), point(350.0, -4.2, 1.8), point(350.0, -3.8, 1.8), point(400.0, -2.0, 1.8))
        val pooled = SweepAnalysis.pool(pts)
        assertEquals(3, pooled.size)
        val mid = pooled[1]
        assertEquals(3, mid.repeats)
        assertEquals(-4.0, mid.metric, 1e-9)
        assertEquals(1.8 / Math.sqrt(3.0), mid.ownError, 1e-9)

        // Disagreeing repeats widen the error instead.
        val noisy = SweepAnalysis.pool(listOf(point(350.0, -1.0, 0.1), point(350.0, -5.0, 0.1)))
        assertEquals(1, noisy.size)
        assertEquals(2.0, noisy[0].ownError, 1e-9)
    }

    @Test
    fun weightedFitLeansOnThePreciseRuns() {
        // Exact parabola, one wild point with a huge error: the fit should ignore it.
        val exact = listOf(300.0, 350.0, 400.0, 450.0).map { m -> point(m, 0.001 * (m - 400) * (m - 400) - 5, 0.1) }
        val wild = point(500.0, +10.0, 100.0)
        val fit = SweepAnalysis.fit(exact + wild)!!
        assertEquals(400.0, fit.extremum, 1.0)
    }

    @Test
    fun durationHintSaysHowLongRunsMustBe() {
        // 17 s runs at 1.8 dB: resolving 1 dB at two sigma needs 0.354 dB per point, 26 times the data.
        val meta17 = meta("m", 300.0).copy(durationSec = 17.0)
        val pts = listOf(SweepPoint(meta17, 300.0, -2.0, 25.0, -2.0, 1.8, null))
        val hint = SweepAnalysis.durationHint(pts)!!
        assertTrue(hint, hint.startsWith("Each run of about 17 s gives +/- 1.8 dB; about 441 s per value (or 26 repeats"))
        // Already precise enough: nothing to say.
        assertEquals(null, SweepAnalysis.durationHint(listOf(SweepPoint(meta17, 300.0, -2.0, 25.0, -2.0, 0.2, null))))
        // The baseline's error is common to every value and must not inflate the hint: 1.27 dB own error
        // inside a 1.8 dB combined one asks for about 13 repeats, not 26.
        val shared = SweepPoint(meta17, 300.0, -2.0, 25.0, -2.0, 1.8, null, ownError = 1.27)
        assertTrue(SweepAnalysis.durationHint(listOf(shared))!!.contains("(or 13 repeats"))
    }

    @Test
    fun displaySmoothingDoesNotShrinkTheSweepError() {
        // The sweep reads the raw peak, so smoothing the display must leave its error alone.
        val fs = 400.0
        val x = DoubleArray(8000) { 9.81 + 0.5 * Math.sin(2 * Math.PI * 25.0 * it / fs) + 0.05 * Math.sin(it * 0.7) }
        val raw = com.kevinushey.quell.dsp.Welch.psd(x, fs, 1024)
        val plain = com.kevinushey.quell.data.AnalysisSettings(smoothingBins = 1)
        val smooth = plain.copy(smoothingBins = 15)
        val m = meta("a", 300.0)
        val base = com.kevinushey.quell.ui.AnalysisEngine.finish(meta("b", null), raw, fs, plain)
        val r1 = com.kevinushey.quell.ui.AnalysisEngine.finish(m, raw, fs, plain)
        val r15 = com.kevinushey.quell.ui.AnalysisEngine.finish(m, raw, fs, smooth)
        assertTrue(r15.randomErrorDb < r1.randomErrorDb)
        val e1 = SweepAnalysis.drivePoints(sweep, listOf(r1), base, 20.0, 30.0)[0].ownError
        val e15 = SweepAnalysis.drivePoints(sweep, listOf(r15), base, 20.0, 30.0)[0].ownError
        assertEquals(e1, e15, 1e-12)
        assertEquals(r1.segmentErrorDb, e1, 1e-12)
    }

    @Test
    fun twoValuesPointTheWay() {
        val pts = listOf(point(300.0, -2.0), point(350.0, -4.0))
        val summary = SweepAnalysis.summarize(sweep, pts)
        assertNull(summary.fit)
        assertEquals(400.0, summary.nextValue!!, 1e-9)
        assertNull(SweepAnalysis.summarize(sweep, pts.take(1)).nextValue)
    }

    @Test
    fun tooFewValuesGiveGuidanceInsteadOfAFit() {
        val pts = listOf(point(300.0, -2.0), point(350.0, -4.0), point(350.0, -4.2))
        assertNull(SweepAnalysis.fit(pts))
        val summary = SweepAnalysis.summarize(sweep, pts)
        assertNull(summary.optimum)
        assertTrue(summary.note, summary.note.contains("at least 3"))
    }

    @Test
    fun runsWithoutAValueOrWithProblemsStayOut() {
        val pts = listOf(point(300.0, -2.0), point(350.0, -4.0), point(400.0, -2.0), point(null, -9.0), point(450.0, Double.NaN, problem = "no peak inside the target band"))
        assertEquals(3, pts.count { it.usable })
        val fit = SweepAnalysis.fit(pts)
        assertNotNull(fit)
        // The excluded runs would have pulled the minimum away from 350 g.
        assertEquals(350.0, fit!!.extremum, 1e-6)
    }

    @Test
    fun wrongCurvatureAsksToExtendTheSweep() {
        // Improving ever faster with mass: the curve bends the wrong way for a minimum, so push on.
        val pts = listOf(point(300.0, -1.0), point(350.0, -2.0), point(400.0, -3.5), point(450.0, -5.5))
        val summary = SweepAnalysis.summarize(sweep, pts)
        assertTrue(summary.note, summary.note.contains("Extend the sweep"))
        assertEquals(500.0, summary.nextValue!!, 1e-9)
    }

    @Test
    fun optimumFarOutsideTheRangeIsFlagged() {
        val pts = listOf(300.0, 350.0, 400.0).map { m -> point(m, 0.001 * (m - 800) * (m - 800)) }
        val summary = SweepAnalysis.summarize(sweep, pts)
        assertEquals(800.0, summary.optimum!!, 1e-6)
        assertTrue(summary.note, summary.note.contains("above the range tried"))
        // Toward it, but at most one span past the data.
        assertEquals(500.0, summary.nextValue!!, 1e-9)
    }

    @Test
    fun tapSweepsMaximizeDamping() {
        val tap = sweep.copy(kind = ExperimentKind.TAP)
        // zeta = 0.05 - 1e-6 (m - 400)^2
        val pts = listOf(300.0, 350.0, 400.0, 450.0, 500.0).map { m -> point(m, 0.05 - 1e-6 * (m - 400) * (m - 400), error = 0.001) }
        val summary = SweepAnalysis.summarize(tap, pts)
        assertEquals(400.0, summary.optimum!!, 1e-6)
        assertTrue(summary.note, summary.note.contains("damping ratio 0.0500"))
    }

    @Test
    fun sweepsSurviveTheIndexRoundTrip() {
        val json = DatasetRepository.indexJson
        val list = listOf(sweep.advanced(300.0).copy(referenceId = "base"))
        val back = json.decodeFromString<List<Sweep>>(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Sweep.serializer()), list))
        assertEquals(list, back)
        // Older indexes have no sweep fields; they must still decode.
        val legacy = """[{"id":"a","name":"Baseline","sourceName":"a.zip","importedAtEpochMs":1,"sampleCount":10,"sampleRate":400.0,"durationSec":1.0,"colorIndex":0,"kind":"DRIVE"}]"""
        val metas = json.decodeFromString<List<DatasetMeta>>(DatasetRepository.migrateIndex(legacy))
        assertNull(metas[0].sweepId)
    }
}
