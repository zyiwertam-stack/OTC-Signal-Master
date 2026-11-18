package com.otcscanmaster

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OnDeviceAnalysisEngine {
    fun analyze(frames: List<Bitmap>): SignalResult {
        require(frames.isNotEmpty()) { "Нет изображения для анализа." }
        check(OpenCVLoader.initLocal()) { "Не удалось загрузить локальную библиотеку OpenCV." }

        val latestCandles = detectCandles(frames.last())
        val opticalFlow = if (frames.size > 1) estimateOpticalFlow(frames) else null
        val ocrText = recognizeText(frames.last())
        if (latestCandles.size < MIN_CANDLES) {
            return SignalResult.insufficient(
                buildString {
                    append("Найдено цветных свечей: ${latestCandles.size}; нужно не менее $MIN_CANDLES. ")
                    append("Проверьте, что график виден целиком и свечи имеют различимые красный/зелёный цвета.")
                    appendOcr(ocrText)
                    if (opticalFlow == null && frames.size > 1) append(" Оптический поток не дал надёжных точек.")
                }
            )
        }

        val evidence = evaluateCandles(latestCandles)
        val flowScore = opticalFlow?.let { (-it / frames.last().height * FLOW_SCALE).coerceIn(-1.0, 1.0) }
        val combinedScore = (
            evidence.trend * TREND_WEIGHT +
                evidence.bodyBias * BODY_WEIGHT +
                (flowScore ?: 0.0) * (if (flowScore == null) 0.0 else FLOW_WEIGHT)
            ).coerceIn(-1.0, 1.0)
        val directionalAgreement = evidence.trend * evidence.bodyBias > 0.0 &&
            abs(evidence.trend) >= MIN_TREND_SCORE &&
            abs(evidence.bodyBias) >= MIN_BODY_SCORE &&
            (flowScore == null || abs(flowScore) < FLOW_CONFLICT_THRESHOLD || flowScore * combinedScore > 0)
        val direction = when {
            directionalAgreement && combinedScore >= SIGNAL_THRESHOLD -> "CALL"
            directionalAgreement && combinedScore <= -SIGNAL_THRESHOLD -> "PUT"
            else -> "WAIT"
        }
        val confidence = if (direction == "WAIT") 0 else
            (35 + abs(combinedScore) * 40 + min(latestCandles.size, 20) * 0.7).toInt().coerceIn(35, 75)
        val supportResistance = when {
            evidence.rangePosition >= 0.8 -> "цена близка к верхней границе видимого диапазона"
            evidence.rangePosition <= 0.2 -> "цена близка к нижней границе видимого диапазона"
            else -> "цена находится внутри видимого диапазона"
        }
        val analysis = buildString {
            append("Локально распознано свечей: ${latestCandles.size}; ")
            append("уклон свечей ${signedPercent(evidence.bodyBias)}, ")
            append("изменение положения цены ${signedPercent(evidence.trend)}; $supportResistance.")
            flowScore?.let { append(" Optical Flow: ${signedPercent(it)}.") }
            if (direction == "WAIT") {
                append(" Направленные признаки не достигли порога или расходятся; сигнал не выдан.")
            } else {
                append(" CALL/PUT основан на согласии локальных визуальных признаков, не является вероятностью прибыли.")
            }
            appendOcr(ocrText)
        }
        return SignalResult(
            pair = null,
            direction = direction,
            confidence = confidence,
            expiration = null,
            analysis = analysis,
            status = "success"
        )
    }

    private fun detectCandles(bitmap: Bitmap): List<Candle> {
        val rgba = Mat()
        val rgb = Mat()
        val hsv = Mat()
        val green = Mat()
        val redLow = Mat()
        val redHigh = Mat()
        val red = Mat()
        val combined = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
            Core.inRange(hsv, Scalar(35.0, 55.0, 45.0), Scalar(95.0, 255.0, 255.0), green)
            Core.inRange(hsv, Scalar(0.0, 65.0, 45.0), Scalar(12.0, 255.0, 255.0), redLow)
            Core.inRange(hsv, Scalar(168.0, 65.0, 45.0), Scalar(180.0, 255.0, 255.0), redHigh)
            Core.bitwise_or(redLow, redHigh, red)
            Core.bitwise_or(green, red, combined)

            val top = (bitmap.height * CHART_TOP).toInt().coerceIn(0, bitmap.height - 1)
            val bottom = (bitmap.height * CHART_BOTTOM).toInt().coerceIn(top + 1, bitmap.height)
            val chartHeight = bottom - top
            val pixels = ByteArray(bitmap.width * chartHeight)
            val redPixels = ByteArray(pixels.size)
            val greenPixels = ByteArray(pixels.size)
            val combinedRegion = combined.submat(Rect(0, top, bitmap.width, chartHeight))
            val redRegion = red.submat(Rect(0, top, bitmap.width, chartHeight))
            val greenRegion = green.submat(Rect(0, top, bitmap.width, chartHeight))
            try {
                combinedRegion.get(0, 0, pixels)
                redRegion.get(0, 0, redPixels)
                greenRegion.get(0, 0, greenPixels)
            } finally {
                combinedRegion.release()
                redRegion.release()
                greenRegion.release()
            }

            val candles = mutableListOf<Candle>()
            var x = 0
            while (x < bitmap.width) {
                while (x < bitmap.width && !hasColoredPixel(pixels, x, bitmap.width, chartHeight)) x++
                if (x == bitmap.width) break
                val left = x
                var right = x
                var lastColored = x
                while (x < bitmap.width) {
                    if (hasColoredPixel(pixels, x, bitmap.width, chartHeight)) {
                        right = x
                        lastColored = x
                    } else if (x - lastColored > MAX_CANDLE_GAP) {
                        break
                    }
                    x++
                }
                val width = right - left + 1
                if (width > maxCandleWidth(bitmap.width)) continue
                var minY = chartHeight
                var maxY = -1
                var redCount = 0
                var greenCount = 0
                val rowCounts = IntArray(chartHeight)
                for (column in left..right) {
                    for (row in 0 until chartHeight) {
                        val index = row * bitmap.width + column
                        if ((pixels[index].toInt() and 0xFF) == 0) continue
                        minY = min(minY, row)
                        maxY = max(maxY, row)
                        rowCounts[row]++
                        if ((redPixels[index].toInt() and 0xFF) != 0) redCount++
                        if ((greenPixels[index].toInt() and 0xFF) != 0) greenCount++
                    }
                }
                val height = maxY - minY + 1
                if (height >= MIN_CANDLE_HEIGHT && width <= height * MAX_CANDLE_ASPECT &&
                    redCount + greenCount >= MIN_COLOR_PIXELS
                ) {
                    val direction = if (greenCount > redCount) 1 else if (redCount > greenCount) -1 else 0
                    if (direction != 0) {
                        val bodyRows = (minY..maxY).filter { rowCounts[it] >= max(2, width / 2) }
                        val bodyCenter = if (bodyRows.isEmpty()) (minY + maxY) / 2.0
                        else (bodyRows.first() + bodyRows.last()) / 2.0
                        val closeY = if (direction > 0) bodyRows.lastOrNull()?.toDouble() ?: bodyCenter
                        else bodyRows.firstOrNull()?.toDouble() ?: bodyCenter
                        candles.add(Candle(top + closeY, direction))
                    }
                }
            }
            return candles.takeLast(MAX_ANALYZED_CANDLES)
        } finally {
            rgba.release()
            rgb.release()
            hsv.release()
            green.release()
            redLow.release()
            redHigh.release()
            red.release()
            combined.release()
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
            Imgproc.resize(gray, scaled, org.opencv.core.Size(FLOW_WIDTH.toDouble(), FLOW_HEIGHT.toDouble()))
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

    private fun evaluateCandles(candles: List<Candle>): CandleEvidence {
        val closes = candles.map { it.closeY }
        val split = candles.size / 2
        val firstHalf = closes.take(split).average()
        val secondHalf = closes.drop(split).average()
        val trend = ((firstHalf - secondHalf) / CHART_HEIGHT_FRACTION).coerceIn(-1.0, 1.0)
        val bodyBias = candles.takeLast(BODY_VOTE_WINDOW).map { it.direction }.average()
        val low = closes.minOrNull() ?: return CandleEvidence(0.0, 0.0, 0.5)
        val high = closes.maxOrNull() ?: return CandleEvidence(0.0, 0.0, 0.5)
        val rangePosition = if (high == low) 0.5 else (closes.last() - low) / (high - low)
        return CandleEvidence(trend, bodyBias, rangePosition)
    }

    private fun hasColoredPixel(pixels: ByteArray, x: Int, width: Int, height: Int): Boolean =
        (0 until height).count { (pixels[it * width + x].toInt() and 0xFF) != 0 } >= MIN_PIXELS_PER_COLUMN

    private fun StringBuilder.appendOcr(text: String) {
        val values = OCR_NUMBER.findAll(text).map { it.value }.distinct().take(MAX_OCR_VALUES).toList()
        append(if (values.isEmpty()) " OCR: числовые подписи не распознаны." else " OCR, числа с экрана: ${values.joinToString()}.")
    }

    private fun signedPercent(value: Double): String =
        "${if (value >= 0) "+" else ""}${(value * 100).toInt()}%"

    private data class Candle(val closeY: Double, val direction: Int)
    private data class CandleEvidence(val trend: Double, val bodyBias: Double, val rangePosition: Double)

    companion object {
        private const val CHART_TOP = 0.12
        private const val CHART_BOTTOM = 0.88
        private const val CHART_HEIGHT_FRACTION = CHART_BOTTOM - CHART_TOP
        private const val MIN_CANDLES = 6
        private const val MAX_ANALYZED_CANDLES = 40
        private const val MIN_CANDLE_HEIGHT = 7
        private const val MAX_CANDLE_GAP = 2
        private const val MAX_CANDLE_ASPECT = 1.5
        private const val MIN_COLOR_PIXELS = 5
        private const val MIN_PIXELS_PER_COLUMN = 2
        private const val BODY_VOTE_WINDOW = 12
        private const val SIGNAL_THRESHOLD = 0.4
        private const val MIN_TREND_SCORE = 0.18
        private const val MIN_BODY_SCORE = 0.2
        private const val FLOW_CONFLICT_THRESHOLD = 0.15
        private const val FLOW_SCALE = 1.5
        private const val TREND_WEIGHT = 0.55
        private const val BODY_WEIGHT = 0.3
        private const val FLOW_WEIGHT = 0.15
        private const val MAX_FLOW_POINTS = 100
        private const val MIN_FLOW_POINTS = 6
        private const val FLOW_WIDTH = 480
        private const val FLOW_HEIGHT = 270
        private const val OCR_TIMEOUT_SECONDS = 12L
        private const val MAX_OCR_VALUES = 6
        private val OCR_NUMBER = Regex("""(?<!\w)\d+(?:[.,]\d+)?(?!\w)""")

        private fun maxCandleWidth(imageWidth: Int) = max(14, imageWidth / 35)
    }
}
