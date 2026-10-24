package com.kevinushey.quell.data

import kotlinx.serialization.Serializable

/** The quantity stepped between the runs of a sweep. */
@Serializable
enum class SweepParameter(val label: String, val unit: String) {
    MASS("Damper mass", "g"),
    STIFFNESS("Spring stiffness", "N/mm"),
    POSITION("Damper position", "mm"),
    OTHER("Other", "");

    fun format(value: Double): String {
        val number = "%.2f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.')
        return if (unit.isEmpty()) number else "$number $unit"
    }

    /** The number alone, for text fields and table cells. */
    fun formatNumber(value: Double): String = "%.2f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.')
}

/**
 * A tuning session: runs recorded while one damper parameter is stepped,
 * compared against a baseline. Membership lives on the datasets (sweepId),
 * so deleting a run needs no bookkeeping here.
 */
@Serializable
data class Sweep(
    val id: String,
    val name: String,
    val parameter: SweepParameter = SweepParameter.MASS,
    val kind: ExperimentKind = ExperimentKind.DRIVE,
    /** Baseline run the members are compared against; usually recorded without the damper. */
    val referenceId: String? = null,
    val createdAtEpochMs: Long,
    /** Last value recorded and the step between the last two, to pre-fill the next run. */
    val lastValue: Double? = null,
    val step: Double? = null,
    /** Value the analysis recommends trying next (the fitted optimum, or past the best end); null when it has none. */
    val nextValue: Double? = null,
    val notes: String = "",
) {
    /**
     * A member's parameter value. Mass sweeps read the dataset's damper mass so
     * the Data tab and the sweep never disagree about it.
     */
    fun valueOf(meta: DatasetMeta): Double? = if (parameter == SweepParameter.MASS) meta.damperMassGrams else meta.sweepValue

    /** Value to pre-fill for the next run: what the analysis recommends, else continue the last step. */
    val suggestedNext: Double? get() = nextValue ?: lastValue?.let { it + (step ?: 0.0) }

    /** Bookkeeping after a run at `value` was added; the recommendation is recomputed from the new data. */
    fun advanced(value: Double): Sweep =
        copy(lastValue = value, step = lastValue?.let { if (value != it) value - it else step } ?: step, nextValue = null)

    /** Whether the parameter can go negative; masses and stiffnesses cannot. */
    val parameterNonNegative: Boolean get() = parameter == SweepParameter.MASS || parameter == SweepParameter.STIFFNESS
}
