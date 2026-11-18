package com.otcscanmaster

import android.graphics.Bitmap
import android.graphics.Rect
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
import kotlin.math.tanh

class OnDeviceAnalysisEngine {
    fun analyze(
        frames: List<Bitmap>,
        frameTimestampsMs: List<Long> = frames.indices.map { it * DEFAULT_FRAME_INTERVAL_MS }
    ): SignalResult {
        require(frames.isNotEmpty()) { "Нет изображения для анализа." }
        require(frameTimestampsMs.size == frames.size) { "Для каждого кадра нужна отметка времени." }
        check(OpenCVLoader.initLocal()) { "Не удалось загрузить локальную библиотеку OpenCV." }

        frames.forEachIndexed { index, bitmap ->
            require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
                "Кадр ${index + 1} пуст или уже освобождён."
            }
        }

        val priceReadings = frames.mapIndexedNotNull { index, bitmap ->
            try {
                recognizePrice(bitmap)?.let { reading ->
                    PriceReading(reading.value, frameTimestampsMs[index], reading.region)
                }
            } catch (exception: Exception) {
                Log.w(TAG, "price OCR failed for frame=${index + 1}; continuing with other frames", exception)
                null
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
        val priceEvidence = evaluatePrices(priceReadings)
        if (latest.redPixels + latest.greenPixels < MIN_CHART_COLOR_PIXELS &&
            latestCandles.isEmpty() && priceReadings.isEmpty()
        ) {
            return SignalResult.technicalFailure(
                SignalResult.STATUS_CHART_NOT_FOUND,
                "На последнем кадре не найдено достаточно красных/зелёных пикселей графика. " +
                    "Размер кадра ${frames.last().width}x${frames.last().height}. " +
                    "Проверьте, что график виден и не защищён от захвата."
            )
        }
        if (latestCandles.isEmpty() && priceReadings.isEmpty()) {
            return SignalResult.technicalFailure(
                SignalResult.STATUS_CANDLES_NOT_FOUND,
                "Цвета графика обнаружены, но свечные тела/тени не распознаны. " +
                    "Проверьте масштаб и цветовую схему графика."
            )
        }

        val evidence = evaluateCandles(latestCandles, frames.last().height)
        val sequence = analyzeCandleSequence(latestCandles)
        val frameTrends = detections.zipWithNext().mapNotNull { (previous, current) ->
            estimateFrameTrend(listOf(previous, current), frames.last().height)
        }
        val frameTrend = frameTrends.takeIf { it.isNotEmpty() }?.average()
        val opticalFlow = if (frames.size > 1) estimateOpticalFlow(frames) else null
        val flowScore = opticalFlow?.let { (-it / FLOW_HEIGHT * FLOW_SCALE).coerceIn(-1.0, 1.0) }
        val factors = buildList {
            if (latestCandles.isNotEmpty()) {
                add(WeightedEvidence(evidence.trend, TREND_WEIGHT))
                add(WeightedEvidence(evidence.bodyBias, BODY_WEIGHT))
                add(WeightedEvidence(sequence.structureScore, STRUCTURE_WEIGHT))
                add(WeightedEvidence(sequence.reversalScore, REVERSAL_WEIGHT))
            }
            frameTrend?.let { add(WeightedEvidence(it, FRAME_TREND_WEIGHT)) }
            flowScore?.let { add(WeightedEvidence(it, FLOW_WEIGHT)) }
            priceEvidence?.let {
                add(WeightedEvidence(it.movementScore, PRICE_TREND_WEIGHT))
                add(WeightedEvidence(it.velocityScore, PRICE_VELOCITY_WEIGHT))
            }
        }
        val totalWeight = factors.sumOf { it.weight }
        val combinedScore = if (totalWeight > 0.0) {
            (factors.sumOf { it.score * it.weight } / totalWeight).coerceIn(-1.0, 1.0)
        } else {
            0.0
        }
        val bullishSupport = factors.sumOf { max(0.0, it.score) * it.weight }
        val bearishSupport = factors.sumOf { max(0.0, -it.score) * it.weight }
        val directionSign = when {
            combinedScore > DIRECTION_TIE_EPSILON -> 1
            combinedScore < -DIRECTION_TIE_EPSILON -> -1
            bullishSupport > bearishSupport -> 1
            bearishSupport > bullishSupport -> -1
            else -> factors.maxByOrNull { abs(it.score) * it.weight }?.score?.compareTo(0.0) ?: 0
        }
        if (directionSign == 0) {
            return SignalResult.technicalFailure(
                SignalResult.STATUS_ANALYSIS_ERROR,
                "Изображение обработано, но на графике не найдено измеримого направления цены или свечей."
            )
        }
        val direction = if (directionSign > 0) "CALL" else "PUT"
        val agreement = if (totalWeight > 0) {
            factors.filter { it.score * directionSign > 0.0 }.sumOf { it.weight } / totalWeight
        } else {
            0.0
        }
        val evidenceSources = listOf(
            latestCandles.isNotEmpty(),
            frameTrend != null,
            flowScore != null,
            priceEvidence != null
        ).count { it }
        val agreementQuality = (evidenceSources / MIN_INDEPENDENT_EVIDENCE_SOURCES.toDouble()).coerceIn(0.0, 1.0)
        val candleQuality = (latestCandles.size / CANDLE_QUALITY_REFERENCE).coerceIn(0.0, 1.0)
        val frameQuality = ((frames.size - 1).toDouble() / FRAME_QUALITY_REFERENCE).coerceIn(0.0, 1.0)
        val priceQuality = priceReadings.size.toDouble() / frames.size *
            (PRICE_QUALITY_FLOOR + (1.0 - PRICE_QUALITY_FLOOR) * (priceEvidence?.stability ?: 0.0))
        val reversalPenalty = abs(sequence.reversalScore).takeIf { sequence.reversalScore * directionSign < 0 } ?: 0.0
        val confidence = (
            abs(combinedScore) * SCORE_CONFIDENCE_WEIGHT +
                    agreement * agreementQuality * AGREEMENT_CONFIDENCE_WEIGHT +
                    candleQuality * CANDLE_CONFIDENCE_WEIGHT +
                    frameQuality * FRAME_CONFIDENCE_WEIGHT +
                    priceQuality * PRICE_CONFIDENCE_WEIGHT -
                    reversalPenalty * REVERSAL_CONFIDENCE_PENALTY
            ).toInt().coerceIn(0, 100)
        val lowDataQuality = latestCandles.size < MIN_CANDLES ||
            frames.size < MIN_VIDEO_QUALITY_FRAMES ||
            priceReadings.isEmpty() ||
            confidence < LOW_CONFIDENCE_THRESHOLD
        val visualMovement = frameTrend ?: evidence.trend.takeIf { latestCandles.isNotEmpty() } ?: 0.0
        val movement = priceEvidence?.movement?.takeIf { priceReadings.size >= MIN_PRICE_SAMPLES_FOR_MOVEMENT }
            ?: when {
            visualMovement > FLAT_VISUAL_MOVEMENT_THRESHOLD -> "UP"
            visualMovement < -FLAT_VISUAL_MOVEMENT_THRESHOLD -> "DOWN"
            else -> "FLAT"
        }
        val momentum = priceEvidence?.momentum
            ?.takeIf { priceReadings.size >= MIN_PRICE_SAMPLES_FOR_ACCELERATION }
            ?: classifyFrameMomentum(frameTrends)
        val supportResistance = when {
            evidence.rangePosition >= 0.8 -> "цена близка к верхней границе видимого диапазона"
            evidence.rangePosition <= 0.2 -> "цена близка к нижней границе видимого диапазона"
            else -> "цена находится внутри видимого диапазона"
        }
        val patterns = sequence.patterns
        val candleSummary = sequence.summary(latestCandles.size)
        val analysis = buildString {
            append("Обработано кадров: ${frames.size}; OCR цены: ${priceReadings.size}/${frames.size} кадров; ")
            append("распознано свечей на последнем кадре: ${latestCandles.size} ")
            append("(красных: ${latest.redCandles}, зелёных: ${latest.greenCandles}); ")
            append("тела: ${evidence.bodies}, свечи с верхней/нижней тенью: ")
            append("${evidence.upperWicks}/${evidence.lowerWicks}; локальные максимумы/минимумы: ")
            append("${evidence.localHighs}/${evidence.localLows}; ")
            append("уклон свечей ${signedPercent(evidence.bodyBias)}, структура ${signedPercent(sequence.structureScore)}, ")
            append("признаки разворота ${signedPercent(sequence.reversalScore)}; ")
            priceEvidence?.let {
                append("цена ${it.firstPrice} → ${it.lastPrice}, изменение ${it.changePercent}; ")
                append("скорость ${it.velocityScore}, ускорение ${it.acceleration}, ")
                append("согласованность движения ${signedPercent(it.stability)}; ")
            }
            if (priceEvidence == null) append("направление изменения оценено по положению свечей; ")
            frameTrend?.let { append("изменение свечей между кадрами ${signedPercent(it)}; ") }
            append("$supportResistance. ")
            if (patterns.isNotEmpty()) append(" Паттерны: ${patterns.joinToString()}.")
            flowScore?.let { append(" Optical Flow: ${signedPercent(it)}.") }
            append(" Итог $direction выбран по совокупной оценке ${signedPercent(combinedScore)}; ")
            append("уверенность — внутренняя эвристическая оценка, не вероятность успешной сделки.")
        }
        Log.i(
            TAG,
            "analysis direction=$direction confidence=$confidence score=$combinedScore " +
                "candles=${latestCandles.size} priceFrames=${priceReadings.size}/${frames.size} " +
                "frameTrend=$frameTrend flow=$flowScore patterns=${patterns.joinToString()}"
        )
        return SignalResult(
            pair = null,
            direction = direction,
            confidence = confidence,
            expiration = null,
            analysis = analysis,
            status = SignalResult.STATUS_SUCCESS,
            patterns = patterns,
            priceMovement = movement,
            momentum = momentum,
            candleAnalysis = candleSummary,
            lowDataQuality = lowDataQuality
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
                    direction = direction,
                    bodyHeight = max(1, bodyBottom - bodyTop + 1).toDouble(),
                    upperWick = bodyTop.toDouble(),
                    lowerWick = (height - 1 - bodyBottom).toDouble()
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
        val movements = frames.zipWithNext().mapNotNull { (previous, current) ->
            estimateOpticalFlow(previous, current)
        }
        return movements.takeIf { it.isNotEmpty() }?.average()
    }

    private fun estimateOpticalFlow(previousFrame: Bitmap, currentFrame: Bitmap): Double? {
        val first = toGray(previousFrame)
        val last = toGray(currentFrame)
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

    private fun recognizePrice(bitmap: Bitmap): PriceCandidate? {
        val regions = findBluePriceRegions(bitmap)
        if (regions.isEmpty()) return null
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val fullFrame = recognize(bitmap, recognizer)
            val detected = regions.mapNotNull { region ->
                fullFrame.textBlocks.asSequence()
                    .flatMap { it.lines.asSequence() }
                    .filter { line ->
                        val bounds = line.boundingBox
                        bounds != null && isNear(bounds, region.rect)
                    }
                    .mapNotNull { line -> parsePrice(line.text)?.let { PriceCandidate(it, region.rect) } }
                    .firstOrNull()
            }
            detected.maxByOrNull { it.region.width() * it.region.height() }?.let { return it }

            regions.take(MAX_PRICE_REGIONS_TO_CROP).forEach { region ->
                val crop = Bitmap.createBitmap(
                    bitmap,
                    region.rect.left,
                    region.rect.top,
                    region.rect.width(),
                    region.rect.height()
                )
                try {
                    val result = recognize(crop, recognizer)
                    val value = parsePrice(result.text) ?: return@forEach
                    return PriceCandidate(value, region.rect)
                } finally {
                    crop.recycle()
                }
            }
            null
        } finally {
            recognizer.close()
        }
    }

    private fun recognize(bitmap: Bitmap, recognizer: com.google.mlkit.vision.text.TextRecognizer) =
        Tasks.await(
            recognizer.process(InputImage.fromBitmap(bitmap, 0)),
            OCR_TIMEOUT_SECONDS,
            TimeUnit.SECONDS
        )

    private fun findBluePriceRegions(bitmap: Bitmap): List<BlueRegion> {
        val rgba = Mat()
        val hsv = Mat()
        val blue = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            val rgb = Mat()
            try {
                Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
                Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
            } finally {
                rgb.release()
            }
            Core.inRange(hsv, Scalar(92.0, 75.0, 65.0), Scalar(138.0, 255.0, 255.0), blue)
            Imgproc.findContours(blue, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            val minWidth = max(MIN_PRICE_REGION_WIDTH, bitmap.width / PRICE_REGION_WIDTH_DIVISOR)
            val minHeight = max(MIN_PRICE_REGION_HEIGHT, bitmap.height / PRICE_REGION_HEIGHT_DIVISOR)
            val minArea = max(MIN_PRICE_REGION_AREA, bitmap.width * bitmap.height / PRICE_REGION_AREA_DIVISOR)
            return contours.mapNotNull { contour ->
                val bounds = Imgproc.boundingRect(contour)
                val area = bounds.width * bounds.height
                if (bounds.width < minWidth || bounds.height < minHeight || area < minArea ||
                    bounds.width > bitmap.width * MAX_PRICE_REGION_WIDTH_RATIO ||
                    bounds.height > bitmap.height * MAX_PRICE_REGION_HEIGHT_RATIO
                ) {
                    null
                } else {
                    val padX = max(2, bounds.width / 8)
                    val padY = max(2, bounds.height / 6)
                    val rect = Rect(
                        (bounds.x - padX).coerceAtLeast(0),
                        (bounds.y - padY).coerceAtLeast(0),
                        (bounds.x + bounds.width + padX).coerceAtMost(bitmap.width),
                        (bounds.y + bounds.height + padY).coerceAtMost(bitmap.height)
                    )
                    BlueRegion(rect, area)
                }
            }.sortedByDescending { it.area }.take(MAX_PRICE_REGIONS)
        } finally {
            rgba.release()
            hsv.release()
            blue.release()
            hierarchy.release()
            contours.forEach(MatOfPoint::release)
        }
    }

    private fun isNear(textBounds: Rect, priceRegion: Rect): Boolean {
        val expanded = Rect(priceRegion)
        val padX = max(3, priceRegion.width() / 4)
        val padY = max(3, priceRegion.height() / 3)
        expanded.inset(-padX, -padY)
        return Rect.intersects(expanded, textBounds)
    }

    private fun parsePrice(text: String): Double? =
        OCR_NUMBER.findAll(text)
            .mapNotNull { match -> match.value.replace(',', '.').toDoubleOrNull() }
            .firstOrNull { it.isFinite() && it > 0.0 }

    private fun evaluatePrices(readings: List<PriceReading>): PriceEvidence? {
        if (readings.isEmpty()) return null
        val ordered = readings.sortedBy { it.timestampMs }
        val first = ordered.first()
        val last = ordered.last()
        val change = (last.value - first.value) / first.value
        val deltas = ordered.zipWithNext().mapNotNull { (previous, current) ->
            val seconds = ((current.timestampMs - previous.timestampMs).coerceAtLeast(1L)) / 1_000.0
            val relativeChange = (current.value - previous.value) / previous.value
            relativeChange.takeIf { it.isFinite() }?.let { it / seconds }
        }
        val averageVelocity = deltas.average().takeIf { it.isFinite() } ?: 0.0
        val firstHalfVelocity = deltas.take((deltas.size / 2).coerceAtLeast(1)).average()
        val lastHalfVelocity = deltas.drop((deltas.size / 2).coerceAtLeast(1)).average()
        val acceleration = when {
            deltas.size < 2 || !firstHalfVelocity.isFinite() || !lastHalfVelocity.isFinite() -> "STABLE"
            firstHalfVelocity * lastHalfVelocity < 0 -> "STABLE"
            abs(lastHalfVelocity) > abs(firstHalfVelocity) * ACCELERATION_RATIO -> "ACCELERATING"
            abs(lastHalfVelocity) < abs(firstHalfVelocity) * DECELERATION_RATIO -> "SLOWING"
            else -> "STABLE"
        }
        val stability = if (deltas.isEmpty()) 0.0 else {
            val sign = deltas.sum().compareTo(0.0)
            if (sign == 0) 0.0 else deltas.count { it * sign > 0 }.toDouble() / deltas.size
        }
        val movement = when {
            change > FLAT_PRICE_CHANGE_RATIO -> "UP"
            change < -FLAT_PRICE_CHANGE_RATIO -> "DOWN"
            else -> "FLAT"
        }
        return PriceEvidence(
            movementScore = tanh(change / PRICE_CHANGE_SCALE) * stability.coerceAtLeast(MIN_PRICE_STABILITY),
            velocityScore = tanh(averageVelocity / PRICE_VELOCITY_SCALE),
            movement = movement,
            momentum = acceleration,
            firstPrice = first.value,
            lastPrice = last.value,
            changePercent = "${"%.5f".format(java.util.Locale.US, change * 100)}%",
            acceleration = acceleration.lowercase(),
            stability = stability
        )
    }

    private fun classifyFrameMomentum(frameTrends: List<Double>): String {
        if (frameTrends.size < 2) return "STABLE"
        val split = (frameTrends.size / 2).coerceAtLeast(1)
        val previousSpeed = frameTrends.take(split).map(::abs).average()
        val latestSpeed = frameTrends.drop(split).map(::abs).average()
        if (previousSpeed == 0.0 && latestSpeed == 0.0) return "STABLE"
        return when {
            latestSpeed > previousSpeed * ACCELERATION_RATIO -> "ACCELERATING"
            latestSpeed < previousSpeed * DECELERATION_RATIO -> "SLOWING"
            else -> "STABLE"
        }
    }

    private fun evaluateCandles(candles: List<Candle>, imageHeight: Int): CandleEvidence {
        if (candles.isEmpty()) return CandleEvidence(0.0, 0.0, 0.5, 0, 0, 0, 0, 0)
        val closes = candles.map { it.closeY }
        val split = candles.size / 2
        val trend = if (split == 0) 0.0 else {
            val firstHalf = closes.take(split).average()
            val secondHalf = closes.drop(split).average()
            ((firstHalf - secondHalf) / imageHeight * TREND_SCALE).coerceIn(-1.0, 1.0)
        }
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

    private fun analyzeCandleSequence(candles: List<Candle>): CandleSequence {
        val patterns = mutableListOf<String>()
        if (candles.isEmpty()) return CandleSequence(0.0, 0.0, patterns, "Свечи не распознаны")
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
        val recent = candles.takeLast(SEQUENCE_WINDOW)
        val directions = recent.map { it.direction }
        val averageDirection = directions.average()
        var structure = averageDirection * SEQUENCE_DIRECTION_WEIGHT
        val trailingRun = directions.asReversed().takeWhile { it == directions.last() }.size
        if (trailingRun >= MIN_DIRECTION_STREAK) {
            structure += directions.last() * STREAK_SCORE
            patterns += if (directions.last() > 0) {
                "$trailingRun зелёные свечи подряд"
            } else {
                "$trailingRun красные свечи подряд"
            }
        }
        if (directions.size >= ALTERNATING_CANDLE_COUNT &&
            directions.takeLast(ALTERNATING_CANDLE_COUNT).zipWithNext().all { (a, b) -> a != b }
        ) {
            patterns += "чередование цветов свечей"
        }

        val candleBodies = recent.map { it.bodyHeight }
        val bodyAverage = candleBodies.average().coerceAtLeast(1.0)
        val expanding = candleBodies.takeLast(3).average() > bodyAverage * BODY_EXPANSION_RATIO
        val shrinking = candleBodies.size >= 3 &&
            candleBodies.takeLast(2).average() < candleBodies.dropLast(2).takeLast(3).average() * BODY_SHRINK_RATIO
        if (expanding) {
            structure += averageDirection * BODY_SIZE_SCORE
            patterns += "увеличение размеров тел"
        } else if (shrinking) {
            structure -= averageDirection * BODY_SIZE_SCORE
            patterns += "уменьшение размеров тел — возможное ослабление движения"
        }

        val upperWickRatios = recent.map { it.upperWick / it.bodyHeight.coerceAtLeast(1.0) }
        val lowerWickRatios = recent.map { it.lowerWick / it.bodyHeight.coerceAtLeast(1.0) }
        val upperWickBias = (upperWickRatios.average() / WICK_NORMALIZATION).coerceIn(0.0, 1.0)
        val lowerWickBias = (lowerWickRatios.average() / WICK_NORMALIZATION).coerceIn(0.0, 1.0)
        val reversal = ((lowerWickBias - upperWickBias) * WICK_REVERSAL_WEIGHT).coerceIn(-1.0, 1.0)
        if (upperWickRatios.lastOrNull()?.let { it >= LONG_WICK_BODY_RATIO } == true) {
            patterns += "длинная верхняя тень"
        }
        if (lowerWickRatios.lastOrNull()?.let { it >= LONG_WICK_BODY_RATIO } == true) {
            patterns += "длинная нижняя тень"
        }
        if (shrinking && averageDirection != 0.0) {
            patterns += "возможное ослабление текущего движения"
        }
        if (reversal * averageDirection < -REVERSAL_PATTERN_THRESHOLD) {
            patterns += "тени допускают возможный разворот"
        }
        val summary = buildString {
            append("${candles.size} свечей; ")
            append(if (trailingRun >= MIN_DIRECTION_STREAK) {
                "$trailingRun ${if (directions.last() > 0) "зелёных" else "красных"} подряд"
            } else if (averageDirection == 0.0) {
                "цвета чередуются"
            } else {
                "последовательность ${if (averageDirection > 0) "преимущественно зелёная" else "преимущественно красная"}"
            })
            if (expanding) append(", тела увеличиваются")
            if (shrinking) append(", тела уменьшаются")
            if (upperWickBias > WICK_SIGNAL_THRESHOLD) append(", выражены верхние тени")
            if (lowerWickBias > WICK_SIGNAL_THRESHOLD) append(", выражены нижние тени")
        }
        return CandleSequence(
            structureScore = structure.coerceIn(-1.0, 1.0),
            reversalScore = reversal,
            patterns = patterns,
            description = summary
        )
    }

    private fun signedPercent(value: Double): String =
        "${if (value >= 0) "+" else ""}${(value * 100).toInt()}%"

    private fun CandleSequence.summary(count: Int): String =
        if (count == 0) "Свечи не распознаны" else description

    private data class BlueRegion(val rect: Rect, val area: Int)
    private data class PriceCandidate(val value: Double, val region: Rect)
    private data class PriceReading(val value: Double, val timestampMs: Long, val region: Rect)
    private data class PriceEvidence(
        val movementScore: Double,
        val velocityScore: Double,
        val movement: String,
        val momentum: String,
        val firstPrice: Double,
        val lastPrice: Double,
        val changePercent: String,
        val acceleration: String,
        val stability: Double
    )
    private data class WeightedEvidence(val score: Double, val weight: Double)
    private data class CandleSequence(
        val structureScore: Double,
        val reversalScore: Double,
        val patterns: List<String>,
        val description: String
    )

    private data class Candle(
        val x: Double,
        val openY: Double,
        val closeY: Double,
        val bodyTopY: Double,
        val bodyBottomY: Double,
        val highY: Double,
        val lowY: Double,
        val direction: Int,
        val bodyHeight: Double,
        val upperWick: Double,
        val lowerWick: Double
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
        private const val DEFAULT_FRAME_INTERVAL_MS = 1_000L
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
        private const val DIRECTION_TIE_EPSILON = 0.0001
        private const val FLOW_SCALE = 1.5
        private const val TREND_SCALE = 4.0
        private const val TREND_WEIGHT = 0.45
        private const val BODY_WEIGHT = 0.25
        private const val STRUCTURE_WEIGHT = 0.18
        private const val REVERSAL_WEIGHT = 0.10
        private const val FRAME_TREND_WEIGHT = 0.18
        private const val FLOW_WEIGHT = 0.12
        private const val PRICE_TREND_WEIGHT = 0.24
        private const val PRICE_VELOCITY_WEIGHT = 0.12
        private const val MAX_FLOW_POINTS = 100
        private const val MIN_FLOW_POINTS = 6
        private const val FLOW_WIDTH = 480
        private const val FLOW_HEIGHT = 270
        private const val OCR_TIMEOUT_SECONDS = 12L
        private const val MAX_PRICE_REGIONS = 8
        private const val MAX_PRICE_REGIONS_TO_CROP = 3
        private const val MIN_PRICE_REGION_WIDTH = 18
        private const val MIN_PRICE_REGION_HEIGHT = 10
        private const val MIN_PRICE_REGION_AREA = 100
        private const val PRICE_REGION_WIDTH_DIVISOR = 100
        private const val PRICE_REGION_HEIGHT_DIVISOR = 100
        private const val PRICE_REGION_AREA_DIVISOR = 40_000
        private const val MAX_PRICE_REGION_WIDTH_RATIO = 0.35
        private const val MAX_PRICE_REGION_HEIGHT_RATIO = 0.12
        private const val FLAT_PRICE_CHANGE_RATIO = 0.00001
        private const val FLAT_VISUAL_MOVEMENT_THRESHOLD = 0.02
        private const val PRICE_CHANGE_SCALE = 0.0005
        private const val PRICE_VELOCITY_SCALE = 0.00005
        private const val MIN_PRICE_STABILITY = 0.2
        private const val ACCELERATION_RATIO = 1.2
        private const val DECELERATION_RATIO = 0.8
        private const val SEQUENCE_WINDOW = 8
        private const val MIN_DIRECTION_STREAK = 3
        private const val ALTERNATING_CANDLE_COUNT = 4
        private const val SEQUENCE_DIRECTION_WEIGHT = 0.7
        private const val STREAK_SCORE = 0.3
        private const val BODY_EXPANSION_RATIO = 1.25
        private const val BODY_SHRINK_RATIO = 0.7
        private const val BODY_SIZE_SCORE = 0.15
        private const val WICK_NORMALIZATION = 2.0
        private const val WICK_REVERSAL_WEIGHT = 0.45
        private const val LONG_WICK_BODY_RATIO = 1.5
        private const val WICK_SIGNAL_THRESHOLD = 0.55
        private const val REVERSAL_PATTERN_THRESHOLD = 0.35
        private const val CANDLE_QUALITY_REFERENCE = 8.0
        private const val FRAME_QUALITY_REFERENCE = 6.0
        private const val MIN_VIDEO_QUALITY_FRAMES = 4
        private const val MIN_PRICE_SAMPLES_FOR_MOVEMENT = 2
        private const val MIN_PRICE_SAMPLES_FOR_ACCELERATION = 3
        private const val SCORE_CONFIDENCE_WEIGHT = 45
        private const val AGREEMENT_CONFIDENCE_WEIGHT = 25
        private const val CANDLE_CONFIDENCE_WEIGHT = 12
        private const val FRAME_CONFIDENCE_WEIGHT = 10
        private const val PRICE_CONFIDENCE_WEIGHT = 10
        private const val PRICE_QUALITY_FLOOR = 0.35
        private const val REVERSAL_CONFIDENCE_PENALTY = 12
        private const val LOW_CONFIDENCE_THRESHOLD = 40
        private const val MIN_INDEPENDENT_EVIDENCE_SOURCES = 3
        private val OCR_NUMBER = Regex("""(?<!\w)\d+(?:[.,]\d+)?(?!\w)""")
    }
}
