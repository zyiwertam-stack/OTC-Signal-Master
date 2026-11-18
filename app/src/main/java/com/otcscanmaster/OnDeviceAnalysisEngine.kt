package com.otcscanmaster

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class OnDeviceAnalysisEngine {
    fun analyze(frames: List<Bitmap>): SignalResult {
        require(frames.isNotEmpty()) { "Нет изображения для анализа." }
        check(OpenCVLoader.initLocal()) { "Не удалось загрузить локальную библиотеку OpenCV." }

        frames.forEachIndexed { index, bitmap ->
            require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
                "Кадр ${index + 1} пуст или уже освобождён."
            }
        }

        val detections = frames.map(::detectCandles)
        detections.forEachIndexed { index, detection ->
            Log.i(
                TAG,
                "frame=${index + 1}/${frames.size} size=${frames[index].width}x${frames[index].height} " +
                    "redPixels=${detection.redPixels} greenPixels=${detection.greenPixels} " +
                    "candles=${detection.candles.size} redCandles=${detection.redCandles} " +
                    "greenCandles=${detection.greenCandles}"
            )
        }

        val latest = detections.last()
        val latestCandles = latest.candles
        if (latest.redPixels + latest.greenPixels < MIN_CHART_COLOR_PIXELS) {
            return SignalResult.technicalFailure(
                SignalResult.STATUS_CHART_NOT_FOUND,
                "На последнем кадре не найдено достаточно красных/зелёных пикселей графика. " +
                    "Размер кадра ${frames.last().width}x${frames.last().height}. " +
                    "Проверьте, что график виден и не защищён от захвата."
            )
        }
        if (latestCandles.isEmpty()) {
            return SignalResult.technicalFailure(
                SignalResult.STATUS_CANDLES_NOT_FOUND,
                "Цвета графика обнаружены, но свечные тела/тени не распознаны. " +
                    "Проверьте масштаб и цветовую схему графика."
            )
        }
        if (latestCandles.size < MIN_CANDLES) {
            return SignalResult.insufficient(
                "Найдено цветных свечей: ${latestCandles.size}; нужно не менее $MIN_CANDLES. " +
                    "Кадров обработано: ${frames.size}; " +
                    "красных: ${latest.redCandles}, зелёных: ${latest.greenCandles}."
            )
        }

        val ocrText = recognizeText(frames.last())
        val evidence = evaluateCandles(latestCandles, frames.last().height)
        val frameTrend = if (detections.size > 1) {
            estimateFrameTrend(detections, frames.last().height)
        } else {
            null
        }
        val opticalFlow = if (frames.size > 1) estimateOpticalFlow(frames) else null
        val flowScore = opticalFlow?.let { (-it / FLOW_HEIGHT * FLOW_SCALE).coerceIn(-1.0, 1.0) }
        val combinedScore = (
            evidence.trend * TREND_WEIGHT +
                evidence.bodyBias * BODY_WEIGHT +
                (frameTrend ?: 0.0) * FRAME_TREND_WEIGHT +
                (flowScore ?: 0.0) * (if (flowScore == null) 0.0 else FLOW_WEIGHT)
            ).coerceIn(-1.0, 1.0)
        val directionalAgreement = evidence.trend * evidence.bodyBias > 0.0 &&
            abs(evidence.trend) >= MIN_TREND_SCORE &&
            abs(evidence.bodyBias) >= MIN_BODY_SCORE &&
            (frameTrend == null || abs(frameTrend) < FRAME_TREND_CONFLICT_THRESHOLD ||
                frameTrend * combinedScore > 0) &&
            (flowScore == null || abs(flowScore) < FLOW_CONFLICT_THRESHOLD || flowScore * combinedScore > 0)
        val direction = when {
            directionalAgreement && combinedScore >= SIGNAL_THRESHOLD -> "CALL"
            directionalAgreement && combinedScore <= -SIGNAL_THRESHOLD -> "PUT"
            else -> "WAIT"
        }
        val evidenceStrength = (
            abs(combinedScore) * 45 +
                (abs(evidence.trend) + abs(evidence.bodyBias)) * 12 +
                min(latestCandles.size, 20) * 0.7
            ).toInt().coerceIn(0, 75)
        val confidence = if (direction == "WAIT") evidenceStrength else max(35, evidenceStrength)
        val supportResistance = when {
            evidence.rangePosition >= 0.8 -> "цена близка к верхней границе видимого диапазона"
            evidence.rangePosition <= 0.2 -> "цена близка к нижней границе видимого диапазона"
            else -> "цена находится внутри видимого диапазона"
        }
        val patterns = recognizePatterns(latestCandles)
        val analysis = buildString {
            append("Обработано кадров: ${frames.size}; распознано свечей на последнем кадре: ${latestCandles.size} ")
            append("(красных: ${latest.redCandles}, зелёных: ${latest.greenCandles}); ")
            append("тела: ${evidence.bodies}, свечи с верхней/нижней тенью: ")
            append("${evidence.upperWicks}/${evidence.lowerWicks}; локальные максимумы/минимумы: ")
            append("${evidence.localHighs}/${evidence.localLows}; ")
            append("уклон свечей ${signedPercent(evidence.bodyBias)}, ")
            append("изменение положения цены ${signedPercent(evidence.trend)}; ")
            frameTrend?.let { append("межкадровое изменение цены ${signedPercent(it)}; ") }
            append("$supportResistance.")
            if (patterns.isNotEmpty()) append(" Паттерны: ${patterns.joinToString()}.")
            flowScore?.let { append(" Optical Flow: ${signedPercent(it)}.") }
            if (direction == "WAIT") {
                append(" Направленные признаки не достигли порога или расходятся; WAIT — итог анализа, не ошибка захвата.")
            } else {
                append(" $direction основан на согласии локальных визуальных признаков, не является вероятностью прибыли.")
            }
            append(" Уверенность — эвристическая сила визуальных свидетельств, не вероятность прибыли.")
            appendOcr(ocrText)
        }
        Log.i(
            TAG,
            "analysis direction=$direction confidence=$confidence trend=${evidence.trend} " +
                "bodyBias=${evidence.bodyBias} frameTrend=$frameTrend flow=$flowScore " +
                "patterns=${patterns.joinToString()} reason=" +
                if (direction == "WAIT") "evidence weak or conflicting" else "directional evidence agreed"
        )
        return SignalResult(
            pair = null,
            direction = direction,
            confidence = confidence,
            expiration = null,
            analysis = analysis,
            status = SignalResult.STATUS_SUCCESS,
            patterns = patterns
        )
    }

    private fun detectCandles(bitmap: Bitmap): Detection {
        val rgba = Mat()
        val rgb = Mat()
        val hsv = Mat()
        val green = Mat()
        val redLow = Mat()
        val redHigh = Mat()
        val red = Mat()
        val greenClosed = Mat()
        val redClosed = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, 5.0))
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
            Core.inRange(hsv, Scalar(35.0, 55.0, 45.0), Scalar(95.0, 255.0, 255.0), green)
            Core.inRange(hsv, Scalar(0.0, 55.0, 45.0), Scalar(14.0, 255.0, 255.0), redLow)
            Core.inRange(hsv, Scalar(166.0, 55.0, 45.0), Scalar(180.0, 255.0, 255.0), redHigh)
            Core.bitwise_or(redLow, redHigh, red)
            Imgproc.morphologyEx(green, greenClosed, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.morphologyEx(red, redClosed, Imgproc.MORPH_CLOSE, kernel)

            val greenPixels = Core.countNonZero(green)
            val redPixels = Core.countNonZero(red)
            val candles = mutableListOf<Candle>()
            candles += findCandleComponents(greenClosed, direction = 1, imageWidth = bitmap.width)
            candles += findCandleComponents(redClosed, direction = -1, imageWidth = bitmap.width)
            candles.sortBy { it.x }
            return Detection(
                candles = candles,
                redPixels = redPixels,
                greenPixels = greenPixels,
                redCandles = candles.count { it.direction < 0 },
                greenCandles = candles.count { it.direction > 0 }
            )
        } finally {
            rgba.release()
            rgb.release()
            hsv.release()
            green.release()
            redLow.release()
            redHigh.release()
            red.release()
            greenClosed.release()
            redClosed.release()
            kernel.release()
        }
    }

    private fun findCandleComponents(mask: Mat, direction: Int, imageWidth: Int): List<Candle> {
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        try {
            val componentCount = Imgproc.connectedComponentsWithStats(
                mask,
                labels,
                stats,
                centroids,
                8,
                CvType.CV_32S
            )
            val result = mutableListOf<Candle>()
            val minHeight = max(MIN_CANDLE_HEIGHT, mask.rows() / MIN_CANDLE_HEIGHT_DIVISOR)
            val maxWidth = max(MIN_CANDLE_WIDTH, imageWidth / MAX_CANDLE_WIDTH_DIVISOR)
            for (label in 1 until componentCount) {
                val x = stats.get(label, Imgproc.CC_STAT_LEFT)[0].toInt()
                val y = stats.get(label, Imgproc.CC_STAT_TOP)[0].toInt()
                val width = stats.get(label, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val height = stats.get(label, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                val area = stats.get(label, Imgproc.CC_STAT_AREA)[0].toInt()
                if (height < minHeight || width > maxWidth ||
                    height < width * MIN_CANDLE_ASPECT ||
                    area < max(MIN_CANDLE_AREA, height * MIN_CANDLE_DENSITY)
                ) continue

                val component = labels.submat(y, y + height, x, x + width)
                val componentMask = Mat()
                val pixels = ByteArray(width * height)
                try {
                    Core.compare(component, Scalar(label.toDouble()), componentMask, Core.CMP_EQ)
                    componentMask.get(0, 0, pixels)
                } finally {
                    component.release()
                    componentMask.release()
                }
                val rowCounts = IntArray(height)
                for (row in 0 until height) {
                    var count = 0
                    for (column in 0 until width) {
                        if ((pixels[row * width + column].toInt() and 0xFF) != 0) count++
                    }
                    rowCounts[row] = count
                }
                val widestRow = rowCounts.maxOrNull() ?: 0
                val bodyThreshold = max(1, ceil(widestRow * BODY_ROW_WIDTH_RATIO).toInt())
                val bodyRows = rowCounts.indices.filter { rowCounts[it] >= bodyThreshold }
                val bodyTop = bodyRows.firstOrNull() ?: height / 3
                val bodyBottom = bodyRows.lastOrNull() ?: (height * 2 / 3)
                val openY: Double
                val closeY: Double
                if (direction > 0) {
                    openY = (y + bodyBottom).toDouble()
                    closeY = (y + bodyTop).toDouble()
                } else {
                    openY = (y + bodyTop).toDouble()
                    closeY = (y + bodyBottom).toDouble()
                }
                result += Candle(
                    x = x + width / 2.0,
                    openY = openY,
                    closeY = closeY,
                    bodyTopY = (y + bodyTop).toDouble(),
                    bodyBottomY = (y + bodyBottom).toDouble(),
                    highY = y.toDouble(),
                    lowY = (y + height - 1).toDouble(),
                    direction = direction
                )
            }
            return result
        } finally {
            labels.release()
            stats.release()
            centroids.release()
        }
    }

    private fun estimateOpticalFlow(frames: List<Bitmap>): Double? {
        val first = toGray(frames.first())
        val last = toGray(frames.last())
        val points = MatOfPoint()
        val nextPoints = MatOfPoint2f()
        val status = MatOfByte()
        val errors = MatOfFloat()
        try {
            Imgproc.goodFeaturesToTrack(first, points, MAX_FLOW_POINTS, 0.01, 8.0)
            if (points.rows() < MIN_FLOW_POINTS) return null
            val previousPoints = MatOfPoint2f(*points.toArray())
            try {
                Video.calcOpticalFlowPyrLK(first, last, previousPoints, nextPoints, status, errors)
                val tracked = nextPoints.toArray()
                val original = previousPoints.toArray()
                val valid = status.toArray()
                val verticalMoves = tracked.indices
                    .filter { it < valid.size && valid[it].toInt() != 0 }
                    .map { tracked[it].y - original[it].y }
                if (verticalMoves.size < MIN_FLOW_POINTS) return null
                return verticalMoves.sorted()[verticalMoves.size / 2]
            } finally {
                previousPoints.release()
            }
        } finally {
            first.release()
            last.release()
            points.release()
            nextPoints.release()
            status.release()
            errors.release()
        }
    }

    private fun toGray(bitmap: Bitmap): Mat {
        val source = Mat()
        val gray = Mat()
        val scaled = Mat()
        try {
            Utils.bitmapToMat(bitmap, source)
            Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.resize(gray, scaled, Size(FLOW_WIDTH.toDouble(), FLOW_HEIGHT.toDouble()))
            return scaled
        } catch (exception: Exception) {
            scaled.release()
            throw exception
        } finally {
            source.release()
            gray.release()
        }
    }

    private fun recognizeText(bitmap: Bitmap): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val result = Tasks.await(
                recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                OCR_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )
            result.text
        } finally {
            recognizer.close()
        }
    }

    private fun evaluateCandles(candles: List<Candle>, imageHeight: Int): CandleEvidence {
        val closes = candles.map { it.closeY }
        val split = candles.size / 2
        val firstHalf = closes.take(split).average()
        val secondHalf = closes.drop(split).average()
        val trend = ((firstHalf - secondHalf) / imageHeight * TREND_SCALE).coerceIn(-1.0, 1.0)
        val bodyBias = candles.takeLast(BODY_VOTE_WINDOW).map { it.direction }.average()
        val localHighs = (1 until candles.lastIndex).count {
            candles[it].highY < candles[it - 1].highY && candles[it].highY < candles[it + 1].highY
        }
        val localLows = (1 until candles.lastIndex).count {
            candles[it].lowY > candles[it - 1].lowY && candles[it].lowY > candles[it + 1].lowY
        }
        val topY = closes.minOrNull() ?: return CandleEvidence(0.0, 0.0, 0.5, 0, 0, 0, 0, 0)
        val bottomY = closes.maxOrNull() ?: return CandleEvidence(0.0, 0.0, 0.5, 0, 0, 0, 0, 0)
        val rangePosition = if (bottomY == topY) 0.5 else (bottomY - closes.last()) / (bottomY - topY)
        return CandleEvidence(
            trend,
            bodyBias,
            rangePosition,
            candles.size,
            localHighs,
            localLows,
            candles.count { it.bodyTopY > it.highY },
            candles.count { it.bodyBottomY < it.lowY }
        )
    }

    private fun estimateFrameTrend(detections: List<Detection>, imageHeight: Int): Double? {
        val first = detections.first().candles.maxByOrNull { it.x } ?: return null
        val last = detections.last().candles.maxByOrNull { it.x } ?: return null
        return ((first.closeY - last.closeY) / imageHeight * TREND_SCALE).coerceIn(-1.0, 1.0)
    }

    private fun recognizePatterns(candles: List<Candle>): List<String> {
        val patterns = mutableListOf<String>()
        val lastTwo = candles.takeLast(2)
        if (lastTwo.size == 2) {
            val previous = lastTwo[0]
            val current = lastTwo[1]
            if (previous.direction < 0 && current.direction > 0 &&
                current.openY >= previous.openY && current.closeY <= previous.closeY
            ) {
                patterns += "бычье поглощение (визуальная эвристика)"
            } else if (previous.direction > 0 && current.direction < 0 &&
                current.openY <= previous.openY && current.closeY >= previous.closeY
            ) {
                patterns += "медвежье поглощение (визуальная эвристика)"
            }
        }
        val lastThree = candles.takeLast(3)
        if (lastThree.size == 3 && lastThree.all { it.direction > 0 } &&
            lastThree[0].closeY > lastThree[1].closeY && lastThree[1].closeY > lastThree[2].closeY
        ) {
            patterns += "три последовательные растущие свечи"
        } else if (lastThree.size == 3 && lastThree.all { it.direction < 0 } &&
            lastThree[0].closeY < lastThree[1].closeY && lastThree[1].closeY < lastThree[2].closeY
        ) {
            patterns += "три последовательные падающие свечи"
        }
        return patterns
    }

    private fun StringBuilder.appendOcr(text: String) {
        append(ocrSummary(text))
    }

    private fun ocrSummary(text: String): String {
        val values = OCR_NUMBER.findAll(text).map { it.value }.distinct().take(MAX_OCR_VALUES).toList()
        return if (values.isEmpty()) " OCR: числовые подписи не распознаны."
        else " OCR, числа с экрана: ${values.joinToString()}."
    }

    private fun signedPercent(value: Double): String =
        "${if (value >= 0) "+" else ""}${(value * 100).toInt()}%"

    private data class Candle(
        val x: Double,
        val openY: Double,
        val closeY: Double,
        val bodyTopY: Double,
        val bodyBottomY: Double,
        val highY: Double,
        val lowY: Double,
        val direction: Int
    )

    private data class Detection(
        val candles: List<Candle>,
        val redPixels: Int,
        val greenPixels: Int,
        val redCandles: Int,
        val greenCandles: Int
    )

    private data class CandleEvidence(
        val trend: Double,
        val bodyBias: Double,
        val rangePosition: Double,
        val bodies: Int,
        val localHighs: Int,
        val localLows: Int,
        val upperWicks: Int,
        val lowerWicks: Int
    )

    companion object {
        private const val TAG = "OTCScanAnalysis"
        private const val MIN_CANDLES = 6
        private const val MIN_CANDLE_HEIGHT = 7
        private const val MIN_CANDLE_WIDTH = 3
        private const val MAX_CANDLE_WIDTH_DIVISOR = 32
        private const val MIN_CANDLE_HEIGHT_DIVISOR = 120
        private const val MIN_CANDLE_ASPECT = 1.15
        private const val MIN_CANDLE_AREA = 8
        private const val MIN_CANDLE_DENSITY = 1
        private const val MIN_CHART_COLOR_PIXELS = 20
        private const val BODY_ROW_WIDTH_RATIO = 0.65
        private const val BODY_VOTE_WINDOW = 12
        private const val SIGNAL_THRESHOLD = 0.4
        private const val MIN_TREND_SCORE = 0.18
        private const val MIN_BODY_SCORE = 0.2
        private const val FLOW_CONFLICT_THRESHOLD = 0.15
        private const val FRAME_TREND_CONFLICT_THRESHOLD = 0.15
        private const val FLOW_SCALE = 1.5
        private const val TREND_SCALE = 4.0
        private const val TREND_WEIGHT = 0.45
        private const val BODY_WEIGHT = 0.25
        private const val FRAME_TREND_WEIGHT = 0.15
        private const val FLOW_WEIGHT = 0.15
        private const val MAX_FLOW_POINTS = 100
        private const val MIN_FLOW_POINTS = 6
        private const val FLOW_WIDTH = 480
        private const val FLOW_HEIGHT = 270
        private const val OCR_TIMEOUT_SECONDS = 12L
        private const val MAX_OCR_VALUES = 6
        private val OCR_NUMBER = Regex("""(?<!\w)\d+(?:[.,]\d+)?(?!\w)""")
    }
}
