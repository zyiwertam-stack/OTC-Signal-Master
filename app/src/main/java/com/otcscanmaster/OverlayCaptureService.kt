package com.otcscanmaster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.io.File
import kotlin.math.max

class OverlayCaptureService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var windowManager: WindowManager
    private var overlay: LinearLayout? = null
    private var expandedPanel: LinearLayout? = null
    private var collapsedIcon: TextView? = null
    private var statusView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null
    @Volatile private var recordingActive = false
    private val recorderLock = Any()
    private var displayListener: DisplayManager.DisplayListener? = null
    private var foregroundStarted = false
    private var screenWidth = 0
    private var screenHeight = 0
    private var densityDpi = 0
    @Volatile private var captureMode = CaptureMode.IDLE
    private var lastFrames: List<Bitmap> = emptyList()

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ensureForeground()
            when (intent?.action) {
                ACTION_STOP -> stopSelf()
                ACTION_GRANT_PROJECTION -> acceptProjection(intent)
                ACTION_SCREENSHOT -> takeScreenshot()
                ACTION_VIDEO -> scanVideo()
                ACTION_SIGNAL -> analyzeLastCapture()
                ACTION_ANALYZE_URI -> analyzeImageUri(intent)
                else -> showOverlay()
            }
        } catch (exception: Exception) {
            publishStatus("Ошибка: ${exception.message ?: "не удалось запустить службу"}")
        }
        return START_NOT_STICKY
    }

    private fun acceptProjection(intent: Intent) {
        if (projection != null) {
            publishStatus("Сеанс захвата уже активен.")
            return
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode != RESULT_OK || resultData == null) {
            publishStatus("Захват экрана не разрешён. Повторите запрос из приложения.")
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            }
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("Android не создал сессию захвата.")
            projection?.registerCallback(projectionCallback, mainHandler)
            prepareVirtualDisplay()
            publishStatus("Захват экрана готов")
        } catch (exception: Exception) {
            projection?.stop()
            releaseProjection()
            publishStatus("Не удалось начать захват: ${exception.message ?: "ошибка Android"}")
        }
    }

    private fun ensureForeground() {
        if (foregroundStarted) return
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE)
        }
        foregroundStarted = true
    }

    private fun prepareVirtualDisplay() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = max(1, metrics.widthPixels)
        screenHeight = max(1, metrics.heightPixels)
        densityDpi = metrics.densityDpi
        imageReader = createImageReader(screenWidth, screenHeight)
        virtualDisplay = projection?.createVirtualDisplay(
            "OTCScanMasterCapture",
            screenWidth,
            screenHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            mainHandler
        ) ?: throw IllegalStateException("Не удалось создать виртуальный дисплей.")
        val manager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == android.view.Display.DEFAULT_DISPLAY) resizeCaptureSurface()
            }
        }.also { manager.registerDisplayListener(it, mainHandler) }
    }

    private fun createImageReader(width: Int, height: Int): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ reader -> onImageAvailable(reader) }, mainHandler)
        }

    private fun resizeCaptureSurface() {
        if (projection == null || recordingActive) return
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        if (metrics.widthPixels == screenWidth && metrics.heightPixels == screenHeight) return
        screenWidth = max(1, metrics.widthPixels)
        screenHeight = max(1, metrics.heightPixels)
        densityDpi = metrics.densityDpi
        val oldReader = imageReader
        val newReader = createImageReader(screenWidth, screenHeight)
        imageReader = newReader
        virtualDisplay?.resize(screenWidth, screenHeight, densityDpi)
        virtualDisplay?.surface = newReader.surface
        oldReader?.close()
    }

    private fun onImageAvailable(reader: ImageReader) {
        val mode = captureMode
        if (mode == CaptureMode.IDLE) {
            reader.acquireLatestImage()?.close()
            return
        }
        val image = reader.acquireLatestImage() ?: return
        worker.execute {
            val bitmap = try {
                reduceForAnalysis(imageToBitmap(image))
            } catch (exception: Exception) {
                publishStatus("Не удалось обработать кадр: ${exception.message ?: "ошибка изображения"}")
                null
            } finally {
                image.close()
            }
            if (bitmap != null) {
                when (mode) {
                    CaptureMode.SCREENSHOT -> completeScreenshot(bitmap)
                    CaptureMode.VIDEO -> bitmap.recycle()
                    CaptureMode.IDLE -> bitmap.recycle()
                }
            }
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes.firstOrNull() ?: throw IllegalStateException("В кадре нет изображения.")
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPixels = rowStride / pixelStride
        val padded = Bitmap.createBitmap(rowPixels, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        if (cropped !== padded) padded.recycle()
        return cropped
    }

    private fun reduceForAnalysis(bitmap: Bitmap): Bitmap {
        val longestSide = max(bitmap.width, bitmap.height)
        if (longestSide <= MAX_ANALYSIS_DIMENSION) return bitmap
        val scale = MAX_ANALYSIS_DIMENSION.toFloat() / longestSide
        val resized = Bitmap.createBitmap(
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(resized)
        canvas.drawBitmap(
            bitmap,
            null,
            android.graphics.Rect(0, 0, resized.width, resized.height),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        )
        bitmap.recycle()
        return resized
    }

    private fun takeScreenshot() {
        if (!ensureProjection()) return
        if (captureMode != CaptureMode.IDLE || recordingActive) {
            showMessage("Дождитесь завершения текущего захвата.")
            return
        }
        setOverlayVisible(false)
        publishStatus("Создание скриншота…")
        mainHandler.postDelayed({
            captureMode = CaptureMode.SCREENSHOT
            mainHandler.postDelayed({
                if (captureMode == CaptureMode.SCREENSHOT) {
                    captureMode = CaptureMode.IDLE
                    setOverlayVisible(true)
                    publishStatus("Не удалось получить кадр. Проверьте разрешение захвата.")
                }
            }, CAPTURE_TIMEOUT_MS)
        }, OVERLAY_HIDE_DELAY_MS)
    }

    private fun completeScreenshot(bitmap: Bitmap) {
        captureMode = CaptureMode.IDLE
        replaceLastFrames(listOf(bitmap))
        mainHandler.post { setOverlayVisible(true) }
        publishStatus("Скриншот создан; анализирую на устройстве…")
        analyzeFrames(listOf(bitmap))
    }

    private fun scanVideo() {
        if (!ensureProjection()) return
        if (captureMode != CaptureMode.IDLE || recordingActive) {
            showMessage("Дождитесь завершения текущего захвата.")
            return
        }
        setOverlayVisible(false)
        publishStatus("Подготовка короткого сканирования…")
        mainHandler.postDelayed({
            worker.execute {
                var preparedFile: File? = null
                var preparedRecorder: MediaRecorder? = null
                try {
                    val prepared = prepareVideoRecorder()
                    preparedFile = prepared.first
                    preparedRecorder = prepared.second
                    val recorder = prepared.second
                    val outputFile = prepared.first
                    mainHandler.post {
                        try {
                            if (projection == null || virtualDisplay == null) {
                                throw IllegalStateException("Сеанс захвата завершён.")
                            }
                            virtualDisplay?.surface = recorder.surface
                            recorder.start()
                            recordingFile = outputFile
                            mediaRecorder = recorder
                            recordingActive = true
                            captureMode = CaptureMode.VIDEO
                            publishStatus("Запись экрана (3,5 секунды)…")
                            mainHandler.postDelayed({
                                captureMode = CaptureMode.IDLE
                                finishVideoRecording()
                            }, VIDEO_DURATION_MS)
                        } catch (exception: Exception) {
                            releaseRecorder(recorder)
                            outputFile.delete()
                            virtualDisplay?.surface = imageReader?.surface
                            setOverlayVisible(true)
                            publishStatus("Не удалось начать запись: ${exception.message ?: "ошибка MediaRecorder"}")
                        }
                    }
                } catch (exception: Exception) {
                    preparedRecorder?.let(::releaseRecorder)
                    preparedFile?.delete()
                    mainHandler.post {
                        virtualDisplay?.surface = imageReader?.surface
                        setOverlayVisible(true)
                        publishStatus("Не удалось подготовить запись: ${exception.message ?: "ошибка MediaRecorder"}")
                    }
                }
            }
        }, OVERLAY_HIDE_DELAY_MS)
    }

    private fun prepareVideoRecorder(): Pair<File, MediaRecorder> {
        val scale = minOf(1f, MAX_ANALYSIS_DIMENSION.toFloat() / max(screenWidth, screenHeight))
        val width = ((screenWidth * scale).toInt() / 2) * 2
        val height = ((screenHeight * scale).toInt() / 2) * 2
        require(width > 0 && height > 0) { "Недопустимый размер экрана." }
        val file = File(cacheDir, "scan-${System.currentTimeMillis()}.mp4")
        val recorder = MediaRecorder()
        try {
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setVideoSize(width, height)
            recorder.setVideoFrameRate(15)
            recorder.setVideoEncodingBitRate(3_000_000)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            return file to recorder
        } catch (exception: Exception) {
            releaseRecorder(recorder)
            file.delete()
            throw exception
        }
    }

    private fun finishVideoRecording() {
        val recorder = mediaRecorder ?: run {
            setOverlayVisible(true)
            publishStatus("Запись экрана не была запущена.")
            return
        }
        val file = recordingFile
        worker.execute {
            var recordingError: Exception? = null
            synchronized(recorderLock) {
                if (mediaRecorder !== recorder) return@execute
                try {
                    recorder.stop()
                } catch (exception: Exception) {
                    recordingError = exception
                } finally {
                    releaseRecorder(recorder)
                    mediaRecorder = null
                    recordingFile = null
                    recordingActive = false
                }
            }
            mainHandler.post {
                virtualDisplay?.surface = imageReader?.surface
                resizeCaptureSurface()
                setOverlayVisible(true)
            }
            if (recordingError != null || file == null || !file.isFile || file.length() == 0L) {
                file?.delete()
                publishStatus("Запись завершилась без пригодного видео. Повторите сканирование.")
                return@execute
            }
            try {
                val frames = extractVideoFrames(file)
                if (frames.isEmpty()) {
                    publishStatus("Не удалось извлечь кадры из видеозаписи.")
                } else {
                    replaceLastFrames(frames)
                    publishStatus("Извлечено ${frames.size} последовательных кадров; анализирую на устройстве…")
                    analyzeFrames(frames)
                }
            } catch (exception: Exception) {
                publishStatus("Ошибка обработки видео: ${exception.message ?: "не удалось извлечь кадры"}")
            } finally {
                file.delete()
            }
        }
    }

    private fun extractVideoFrames(file: File): List<Bitmap> {
        val retriever = MediaMetadataRetriever()
        val frames = mutableListOf<Bitmap>()
        try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: VIDEO_DURATION_MS
            val frameCount = minOf(MAX_VIDEO_FRAMES, (durationMs / VIDEO_SAMPLE_INTERVAL_MS).toInt())
            for (index in 0 until frameCount) {
                val timestampUs = (index * VIDEO_SAMPLE_INTERVAL_MS + 100L)
                    .coerceAtMost((durationMs - 1).coerceAtLeast(0L)) * 1_000L
                val frame = retriever.getFrameAtTime(
                    timestampUs,
                    MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: continue
                frames.add(reduceForAnalysis(frame))
            }
        } catch (exception: Exception) {
            frames.forEach(Bitmap::recycle)
            throw exception
        } finally {
            retriever.release()
        }
        return frames
    }

    private fun releaseRecorder(recorder: MediaRecorder) {
        try {
            recorder.reset()
        } catch (_: IllegalStateException) {
            // MediaRecorder may already be stopped or uninitialized.
        }
        recorder.release()
    }

    private fun analyzeLastCapture() {
        val frames = lastFrames
        if (frames.isEmpty()) {
            publishResult(SignalResult.insufficient("Сначала создайте скриншот или выполните VIDEO SCAN."))
            return
        }
        analyzeFrames(frames)
    }

    private fun analyzeImageUri(intent: Intent) {
        val uri = intent.data
        if (uri == null) {
            publishStatus("Не выбрано изображение.")
            return
        }
        publishStatus("Загружаю изображение…")
        worker.execute {
            try {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Не удалось открыть выбранное изображение." }
                    android.graphics.BitmapFactory.decodeStream(input, null, bounds)
                }
                require(bounds.outWidth > 0 && bounds.outHeight > 0) {
                    "Файл не является читаемым изображением."
                }
                val options = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Не удалось открыть выбранное изображение." }
                    android.graphics.BitmapFactory.decodeStream(input, null, options)
                        ?: throw IllegalArgumentException("Файл не является читаемым изображением.")
                }
                replaceLastFrames(listOf(bitmap))
                analyzeFrames(listOf(bitmap))
            } catch (exception: Exception) {
                publishStatus("Ошибка изображения: ${exception.message ?: "не удалось открыть файл"}")
            }
        }
    }

    private fun analyzeFrames(frames: List<Bitmap>) {
        publishStatus("Локальный анализ ${frames.size} кадр(ов)…")
        worker.execute {
            try {
                val result = OnDeviceAnalysisEngine().analyze(frames)
                publishResult(result)
            } catch (exception: Exception) {
                publishStatus("Ошибка локального анализа: ${exception.message ?: "не удалось обработать кадр"}")
            }
        }
    }

    private fun replaceLastFrames(frames: List<Bitmap>) {
        val previous = lastFrames
        lastFrames = frames
        previous.filter { old -> frames.none { it === old } }.forEach(Bitmap::recycle)
    }

    private fun ensureProjection(): Boolean {
        if (projection != null && imageReader != null) return true
        publishStatus("Разрешите системный захват экрана в приложении и повторите попытку.")
        return false
    }

    private fun releaseProjection() {
        captureMode = CaptureMode.IDLE
        synchronized(recorderLock) {
            mediaRecorder?.let { recorder ->
                try {
                    recorder.stop()
                } catch (_: Exception) {
                    Unit
                } finally {
                    releaseRecorder(recorder)
                }
            }
            mediaRecorder = null
            recordingActive = false
            recordingFile?.delete()
            recordingFile = null
        }
        val manager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        displayListener?.let { manager.unregisterDisplayListener(it) }
        displayListener = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        projection?.unregisterCallback(projectionCallback)
        projection = null
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            releaseProjection()
            setOverlayVisible(true)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE)
            }
            publishStatus("Системный захват остановлен. Нажмите разрешение экрана для повтора.")
        }
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            publishStatus("Для плавающей панели разрешите отображение поверх других приложений.")
            stopSelf()
            return
        }
        if (overlay != null) return
        val host = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(8), dp(9), dp(8))
            setBackgroundColor(0xE91A2028.toInt())
        }
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val header = TextView(this).apply {
            text = "OTC SCAN MASTER   ⠿"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            setPadding(dp(3), dp(2), dp(3), dp(7))
            setOnTouchListener(DragListener())
        }
        headerRow.addView(header, LinearLayout.LayoutParams(0, dp(36), 1f))
        val minimizeButton = TextView(this).apply {
            text = "−"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(10), 0, dp(10), dp(5))
            setOnClickListener { collapsePanel() }
        }
        headerRow.addView(minimizeButton)
        root.addView(headerRow)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(actionButton("SCREENSHOT", ACTION_SCREENSHOT))
        actions.addView(actionButton("VIDEO SCAN", ACTION_VIDEO))
        actions.addView(actionButton("GET SIGNAL", ACTION_SIGNAL))
        root.addView(actions)
        statusView = TextView(this).apply {
            text = "Готово"
            setTextColor(0xFFB7C4CC.toInt())
            textSize = 10f
            maxLines = 2
            setPadding(dp(3), dp(5), dp(3), 0)
        }
        root.addView(statusView)
        host.addView(root)
        expandedPanel = root
        collapsedIcon = TextView(this).apply {
            text = "OTC"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xE91A2028.toInt())
            setOnClickListener { expandPanel() }
            setOnTouchListener(DragListener())
        }
        host.addView(collapsedIcon)
        collapsedIcon?.visibility = View.GONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(100)
        }
        overlay = host
        layoutParams = params
        windowManager.addView(host, params)
        publishStatus("Панель запущена")
    }

    private fun collapsePanel() {
        expandedPanel?.visibility = View.GONE
        collapsedIcon?.visibility = View.VISIBLE
    }

    private fun expandPanel() {
        collapsedIcon?.visibility = View.GONE
        expandedPanel?.visibility = View.VISIBLE
    }

    private fun actionButton(label: String, action: String): Button = Button(this).apply {
        text = label
        textSize = 9f
        minHeight = dp(38)
        minWidth = 0
        setPadding(dp(4), 0, dp(4), 0)
        setTextColor(0xFFFFFFFF.toInt())
        setOnClickListener {
            when (action) {
                ACTION_SCREENSHOT -> takeScreenshot()
                ACTION_VIDEO -> scanVideo()
                ACTION_SIGNAL -> analyzeLastCapture()
            }
        }
    }

    private inner class DragListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var downX = 0f
        private var downY = 0f
        private var moved = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = layoutParams ?: return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - downX).toInt()
                    val deltaY = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(deltaX) > dp(4) || kotlin.math.abs(deltaY) > dp(4)) moved = true
                    params.x = startX + deltaX
                    params.y = startY + deltaY
                    overlay?.let { windowManager.updateViewLayout(it, params) }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) view.performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }

    private fun setOverlayVisible(visible: Boolean) {
        mainHandler.post { overlay?.visibility = if (visible) View.VISIBLE else View.GONE }
    }

    private fun publishStatus(message: String) {
        statusView?.post { statusView?.text = message }
        sendBroadcast(Intent(ACTION_EVENT).setPackage(packageName).putExtra(EXTRA_STATUS, message))
    }

    private fun publishResult(result: SignalResult) {
        publishStatus("Анализ завершён: ${result.direction} (${result.confidence}%)")
        sendBroadcast(
            Intent(ACTION_EVENT).setPackage(packageName)
                .putExtra(EXTRA_STATUS, result.displayText())
                .putExtra(EXTRA_RESULT, result.toJson().toString())
        )
    }

    private fun showMessage(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun notification(): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Плавающая панель и захват экрана активны")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "OTC Scan Master", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        while (max(width / sampleSize, height / sampleSize) > MAX_ANALYSIS_DIMENSION) {
            sampleSize *= 2
        }
        return sampleSize
    }

    override fun onDestroy() {
        captureMode = CaptureMode.IDLE
        lastFrames.forEach(Bitmap::recycle)
        lastFrames = emptyList()
        val activeProjection = projection
        releaseProjection()
        activeProjection?.stop()
        overlay?.let { windowManager.removeView(it) }
        overlay = null
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private enum class CaptureMode { IDLE, SCREENSHOT, VIDEO }

    companion object {
        const val ACTION_START = "com.otcscanmaster.START"
        const val ACTION_STOP = "com.otcscanmaster.STOP"
        const val ACTION_GRANT_PROJECTION = "com.otcscanmaster.GRANT_PROJECTION"
        const val ACTION_SCREENSHOT = "com.otcscanmaster.SCREENSHOT"
        const val ACTION_VIDEO = "com.otcscanmaster.VIDEO"
        const val ACTION_SIGNAL = "com.otcscanmaster.SIGNAL"
        const val ACTION_ANALYZE_URI = "com.otcscanmaster.ANALYZE_URI"
        const val ACTION_EVENT = "com.otcscanmaster.EVENT"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        const val EXTRA_STATUS = "status"
        const val EXTRA_RESULT = "result"
        private const val CHANNEL_ID = "scanner"
        private const val NOTIFICATION_ID = 207
        private const val OVERLAY_HIDE_DELAY_MS = 450L
        private const val CAPTURE_TIMEOUT_MS = 4_000L
        private const val VIDEO_DURATION_MS = 3_500L
        private const val VIDEO_SAMPLE_INTERVAL_MS = 500L
        private const val MAX_VIDEO_FRAMES = 8
        private const val MAX_ANALYSIS_DIMENSION = 1280
        private const val RESULT_OK = -1
    }
}
