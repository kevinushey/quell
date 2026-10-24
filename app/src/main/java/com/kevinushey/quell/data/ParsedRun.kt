package com.kevinushey.quell.data

data class ParsedRun(
    val series: TimeSeries,
    val deviceModel: String? = null,
    val sensorName: String? = null,
    val recordedAt: String? = null,
    val extraFields: Map<String, String> = emptyMap(),
    val kind: ExperimentKind = ExperimentKind.GENERAL,
    val locationMarks: List<Double> = emptyList(),
)

class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)
