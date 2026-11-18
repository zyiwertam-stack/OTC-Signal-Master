package com.otcscanmaster

import android.Manifest
import android.app.Activity
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var permissionsView: TextView
    private lateinit var connectionView: TextView
    private lateinit var resultView: TextView
    private lateinit var serviceStatusView: TextView
    private var receiverRegistered = false

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(OverlayCaptureService.EXTRA_STATUS) ?: return
            serviceStatusView.text = status
            intent.getStringExtra(OverlayCaptureService.EXTRA_RESULT)?.let { json ->
                try {
                    resultView.text = SignalResult.fromJson(org.json.JSONObject(json)).displayText() +
                        "\n\nСигналы не гарантируют результат торговли."
                } catch (exception: Exception) {
                    resultView.text = "Ошибка чтения результата: ${exception.message ?: "некорректные данные"}"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        updatePermissionStatuses()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatuses()
        restoreLatestState()
    }

    private fun restoreLatestState() {
        if (!::serviceStatusView.isInitialized || !::resultView.isInitialized) return
        val state = getSharedPreferences(OverlayCaptureService.PREFERENCES, MODE_PRIVATE)
        serviceStatusView.text = state.getString(OverlayCaptureService.KEY_STATUS, serviceStatusView.text.toString())
        state.getString(OverlayCaptureService.KEY_RESULT, null)?.let { json ->
            try {
                resultView.text = SignalResult.fromJson(org.json.JSONObject(json)).displayText() +
                    "\n\nСигналы не гарантируют результат торговли."
            } catch (exception: Exception) {
                resultView.text = "Ошибка чтения сохранённого результата: ${exception.message ?: "некорректные данные"}"
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(OverlayCaptureService.ACTION_EVENT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(eventReceiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(eventReceiver, filter)
        receiverRegistered = true
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(eventReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun buildScreen() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
            setBackgroundColor(Color.rgb(16, 20, 25))
        }
        val scroll = ScrollView(this).apply { addView(page) }

        page.addView(label("OTC SCAN MASTER", 24f, Color.WHITE))
        page.addView(label("Локальный сканер графиков · Android 10–15", 13f, 0xFF9EADB8.toInt()))
        page.addView(sectionTitle("СОСТОЯНИЕ"))
        permissionsView = label("", 13f, 0xFFD4DCE2.toInt())
        page.addView(permissionsView)
        connectionView = label("", 13f, 0xFFB7C4CC.toInt())
        page.addView(connectionView)
        connectionView.text = "Анализ на устройстве: OpenCV · ML Kit OCR · Technical Analysis · Optical Flow"
        serviceStatusView = label("Сканер остановлен", 13f, 0xFFB7C4CC.toInt())
        page.addView(serviceStatusView)

        page.addView(sectionTitle("ПЛАВАЮЩАЯ ПАНЕЛЬ"))
        page.addView(button("ЗАПУСТИТЬ ПАНЕЛЬ") {
            if (!Settings.canDrawOverlays(this)) openOverlaySettings()
            else startScannerService(OverlayCaptureService.ACTION_START)
        })
        page.addView(button("РАЗРЕШИТЬ ЗАХВАТ ЭКРАНА") { requestScreenCapture() })
        page.addView(button("ОСТАНОВИТЬ СКАНЕР") {
            startScannerService(OverlayCaptureService.ACTION_STOP)
            serviceStatusView.text = "Остановка службы…"
        })
        page.addView(button("ЗАГРУЗИТЬ ИЗОБРАЖЕНИЕ") { chooseImage() })

        page.addView(sectionTitle("ПОСЛЕДНИЙ РЕЗУЛЬТАТ"))
        resultView = label("Пока нет результата. Сначала сделайте скриншот или VIDEO SCAN.", 14f, Color.WHITE)
        resultView.setPadding(dp(14), dp(12), dp(14), dp(12))
        resultView.setBackgroundColor(0xFF1A222B.toInt())
        page.addView(resultView)

        page.addView(sectionTitle("ЛОКАЛЬНЫЙ АНАЛИЗ"))
        page.addView(label(
            "Скриншоты и кадры видео обрабатываются на устройстве. API Key и подключение к интернету не требуются. При недостатке данных движок возвращает WAIT.",
            12f,
            0xFF9EADB8.toInt()
        ))

        page.addView(sectionTitle("РАЗРЕШЕНИЯ"))
        page.addView(button("OPEN SETTINGS · OVERLAY") { openOverlaySettings() })
        page.addView(button("OPEN SETTINGS · NOTIFICATIONS") { openNotificationSettings() })
        page.addView(label(
            "Захват экрана требует отдельного системного согласия пользователя. Android отзывает его при завершении сеанса; фоновый обход защиты не используется. Изображения остаются на устройстве.",
            12f,
            0xFF9EADB8.toInt()
        ))
        setContentView(scroll)
    }

    private fun updatePermissionStatuses() {
        if (!::permissionsView.isInitialized) return
        val overlayEnabled = Settings.canDrawOverlays(this)
        val notification = if (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) "ENABLED" else "ACTION REQUIRED"
        permissionsView.text = buildString {
            appendLine("Overlay: ${if (overlayEnabled) "ENABLED" else "ACTION REQUIRED"}")
            appendLine("MediaProjection: ACTION REQUIRED — системное согласие на каждый сеанс")
            appendLine("Foreground service: ENABLED — объявлен в приложении")
            appendLine("Уведомления: $notification")
            appendLine("Фото: системный выбор файла (доступ без разрешения к галерее)")
            append("Интернет: не требуется")
        }
    }

    private fun requestScreenCapture() {
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE)
        } catch (exception: Exception) {
            serviceStatusView.text = "Не удалось запросить захват экрана: ${exception.message}"
        }
    }

    @Deprecated("Handled for Android MediaProjection and document picker results.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_SCREEN_CAPTURE -> {
                if (resultCode == RESULT_OK && data != null) {
                    startScannerService(
                        OverlayCaptureService.ACTION_GRANT_PROJECTION,
                        resultCode = resultCode,
                        resultData = data
                    )
                    serviceStatusView.text = "Разрешение получено; запуск захвата…"
                } else {
                    serviceStatusView.text = "Захват не разрешён. Вы можете повторить запрос."
                }
            }
            REQUEST_IMAGE -> if (resultCode == RESULT_OK) {
                data?.data?.let { uri -> startScannerService(OverlayCaptureService.ACTION_ANALYZE_URI, uri = uri) }
            }
        }
    }

    private fun chooseImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, REQUEST_IMAGE)
    }

    private fun startScannerService(
        action: String,
        resultCode: Int? = null,
        resultData: Intent? = null,
        uri: Uri? = null
    ) {
        val intent = Intent(this, OverlayCaptureService::class.java).setAction(action)
        resultCode?.let { intent.putExtra(OverlayCaptureService.EXTRA_RESULT_CODE, it) }
        resultData?.let { intent.putExtra(OverlayCaptureService.EXTRA_RESULT_DATA, it) }
        uri?.let { intent.data = it }
        try {
            startForegroundService(intent)
        } catch (exception: Exception) {
            serviceStatusView.text = "Не удалось запустить службу: ${exception.message}"
        }
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun openNotificationSettings() {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    private fun button(title: String, onClick: () -> Unit): Button = Button(this).apply {
        text = title
        textSize = 12f
        setTextColor(Color.WHITE)
        setBackgroundColor(0xFF263641.toInt())
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
        pageGap(this)
    }

    private fun label(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
    }

    private fun sectionTitle(title: String) = label(title, 12f, 0xFF31D6A0.toInt()).apply {
        setPadding(0, dp(22), 0, dp(8))
    }

    private fun pageGap(view: android.view.View) {
        view.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_SCREEN_CAPTURE = 410
        private const val REQUEST_IMAGE = 411
        private const val REQUEST_NOTIFICATIONS = 412
    }
}
