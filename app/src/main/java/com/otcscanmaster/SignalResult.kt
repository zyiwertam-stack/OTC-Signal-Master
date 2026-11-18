package com.otcscanmaster

import org.json.JSONObject

data class SignalResult(
    val pair: String?,
    val direction: String,
    val confidence: Int,
    val expiration: String?,
    val analysis: String,
    val status: String,
    val patterns: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("pair", pair ?: JSONObject.NULL)
        put("direction", direction)
        put("confidence", confidence)
        put("expiration", expiration ?: JSONObject.NULL)
        put("analysis", analysis)
        put("status", status)
        put("patterns", org.json.JSONArray(patterns))
    }

    fun displayText(): String = buildString {
        appendLine("OTC SCAN MASTER")
        pair?.let { appendLine("PAIR: $it") }
        appendLine("SIGNAL: $direction")
        appendLine("CONFIDENCE: $confidence%")
        appendLine("STATUS: ${statusLabel(status)}")
        expiration?.let { appendLine("EXPIRATION: $it") }
        if (patterns.isNotEmpty()) appendLine("PATTERNS: ${patterns.joinToString()}")
        append("ANALYSIS: $analysis")
    }

    companion object {
        const val STATUS_SUCCESS = "success"
        const val STATUS_CAPTURE_ERROR = "capture_error"
        const val STATUS_ANALYSIS_ERROR = "analysis_error"
        const val STATUS_CHART_NOT_FOUND = "chart_not_found"
        const val STATUS_CANDLES_NOT_FOUND = "candles_not_found"
        const val STATUS_INSUFFICIENT_DATA = "insufficient_data"
        const val STATUS_ANALYZING = "analyzing"

        fun insufficient(reason: String, extra: String = "") = SignalResult(
            pair = null,
            direction = "WAIT",
            confidence = 0,
            expiration = null,
            analysis = reason + extra,
            status = STATUS_INSUFFICIENT_DATA
        )

        fun technicalFailure(status: String, reason: String) = SignalResult(
            pair = null,
            direction = "ERROR",
            confidence = 0,
            expiration = null,
            analysis = reason,
            status = status
        )

        fun fromJson(json: JSONObject): SignalResult {
            val requestedDirection = json.optString("direction", "WAIT").uppercase()
                .takeIf { it in setOf("CALL", "PUT", "WAIT") } ?: "WAIT"
            val status = json.optString("status", "success")
                .takeIf {
                    it in setOf(
                        STATUS_SUCCESS,
                        STATUS_CAPTURE_ERROR,
                        STATUS_ANALYSIS_ERROR,
                        STATUS_CHART_NOT_FOUND,
                        STATUS_CANDLES_NOT_FOUND,
                        STATUS_INSUFFICIENT_DATA,
                        STATUS_ANALYZING
                    )
                } ?: STATUS_INSUFFICIENT_DATA
            val confidence = json.optInt("confidence", 0).coerceIn(0, 100)
            val isCompletedAnalysis = status == STATUS_SUCCESS
            val displayedDirection = if (isCompletedAnalysis) {
                requestedDirection
            } else {
                when (status) {
                    STATUS_CAPTURE_ERROR,
                    STATUS_ANALYSIS_ERROR,
                    STATUS_CHART_NOT_FOUND,
                    STATUS_CANDLES_NOT_FOUND -> "ERROR"
                    STATUS_ANALYZING -> "ANALYZING"
                    else -> "WAIT"
                }
            }
            return SignalResult(
                pair = json.optString("pair").takeIf { it.isNotBlank() && it != "null" },
                direction = displayedDirection,
                confidence = if (isCompletedAnalysis) confidence else 0,
                expiration = json.optString("expiration").takeIf { it.isNotBlank() && it != "null" },
                analysis = json.optString("analysis", "Недостаточно данных для анализа."),
                status = status,
                patterns = json.optJSONArray("patterns")?.let { array ->
                    (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
                } ?: emptyList()
            )
        }

        private fun statusLabel(status: String): String = when (status) {
            STATUS_SUCCESS -> "ANALYSIS COMPLETE"
            STATUS_CAPTURE_ERROR -> "CAPTURE ERROR"
            STATUS_ANALYSIS_ERROR -> "ANALYSIS ERROR"
            STATUS_CHART_NOT_FOUND -> "CHART NOT FOUND"
            STATUS_CANDLES_NOT_FOUND -> "CANDLES NOT FOUND"
            STATUS_INSUFFICIENT_DATA -> "INSUFFICIENT DATA"
            STATUS_ANALYZING -> "ANALYZING"
            else -> status.uppercase()
        }
    }
}
