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
