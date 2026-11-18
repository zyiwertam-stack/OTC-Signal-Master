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
        expiration?.let { appendLine("EXPIRATION: $it") }
        if (patterns.isNotEmpty()) appendLine("PATTERNS: ${patterns.joinToString()}")
        append("ANALYSIS: $analysis")
    }

    companion object {
        fun insufficient(reason: String) = SignalResult(
            pair = null,
            direction = "WAIT",
            confidence = 0,
            expiration = null,
            analysis = reason,
            status = "insufficient_data"
        )

        fun fromJson(json: JSONObject): SignalResult {
            val requestedDirection = json.optString("direction", "WAIT").uppercase()
                .takeIf { it in setOf("CALL", "PUT", "WAIT") } ?: "WAIT"
            val status = json.optString("status", "success")
                .takeIf { it == "success" || it == "insufficient_data" } ?: "insufficient_data"
            val confidence = json.optInt("confidence", 0).coerceIn(0, 100)
            val hasSupportedSignal = status == "success" && requestedDirection != "WAIT" && confidence > 0
            return SignalResult(
                pair = json.optString("pair").takeIf { it.isNotBlank() && it != "null" },
                direction = if (hasSupportedSignal) requestedDirection else "WAIT",
                confidence = if (hasSupportedSignal) confidence else 0,
                expiration = json.optString("expiration").takeIf { it.isNotBlank() && it != "null" },
                analysis = json.optString("analysis", "Недостаточно данных для анализа."),
                status = status,
                patterns = json.optJSONArray("patterns")?.let { array ->
                    (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
                } ?: emptyList()
            )
        }
    }
}
