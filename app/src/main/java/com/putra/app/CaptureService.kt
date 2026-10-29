package com.putra.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.AudioManager
import android.media.ToneGenerator
import android.media.RingtoneManager
import android.net.Uri
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {
    companion object {
        const val ACTION_START = "com.putra.app.action.START_CAPTURE"
        const val ACTION_STOP = "com.putra.app.action.STOP_CAPTURE"
        const val ACTION_PAUSE_READING = "com.putra.app.action.PAUSE_READING"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        const val PREFS = "putra_local_state"
        private const val CHANNEL_ID = "putra_capture_channel"
        private const val ALERT_CHANNEL_ID = "putra_signal_alerts"
        private const val NOTIFICATION_ID = 7318
        private const val ALERT_NOTIFICATION_ID = 7319
        private const val ALERT_COOLDOWN_MS = 300_000L
        private const val ALERT_THRESHOLD_PERCENT = 65.0
        private const val MIN_ALERT_CANDLES = 12
        private const val TAG = "PUTRA-Capture"

        @Volatile var isProjectionActive: Boolean = false
            private set
        @Volatile var readingEnabled: Boolean = false
            private set
        @Volatile var cropRegion: Rect? = null
            private set
        @Volatile private var activeService: CaptureService? = null

        fun setRegion(region: Rect) {
            val copy = Rect(region)
            cropRegion = copy
            readingEnabled = true
            activeService?.onCropSelected(copy)
        }

        fun pauseReading() {
            readingEnabled = false
            activeService?.clearLastResultAndSetStatus(if (isProjectionActive) "PAUSED · SCREEN CAPTURE ACTIVE" else "NOT STARTED")
        }

        fun stopCapture(context: Context) {
            try { context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_STOP)) }
            catch (_: Exception) { context.stopService(Intent(context, CaptureService::class.java)) }
        }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val engineExecutor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "putra-python-analysis").apply { isDaemon = true } }
    private val analysisBusy = AtomicBoolean(false)
    @Volatile private var engineReady = false
    @Volatile private var toneGenerator: ToneGenerator? = null
    @Volatile private var lastSubmittedAt = 0L
    @Volatile private var serviceStopped = false

    override fun onCreate() {
        super.onCreate()
        activeService = this
        createNotificationChannel()
        captureThread = HandlerThread("putra-screen-reader").apply { start() }
        captureHandler = Handler(captureThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCaptureInternal()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_READING -> {
                pauseReading()
                return START_STICKY
            }
            ACTION_START, null -> {
                if (projection != null) return START_STICKY
                val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultCode == 0 || resultData == null) {
                    setStatus("SCREEN CAPTURE PERMISSION MISSING · ACTIVATE FROM PUTRA")
                    stopSelf()
                    return START_NOT_STICKY
                }
                try {
                    serviceStopped = false
                    cropRegion = null
                    readingEnabled = false
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .remove("latest_result")
                        .putString("status", "STARTING ANDROID SCREEN CAPTURE")
                        .apply()
                    // Android 14+ requires foreground mode to be entered before getMediaProjection.
                    if (Build.VERSION.SDK_INT >= 29) {
                        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                    } else {
                        startForeground(NOTIFICATION_ID, buildNotification())
                    }
                    val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    projection = manager.getMediaProjection(resultCode, resultData)
                    if (projection == null) throw IllegalStateException("MEDIA_PROJECTION_TOKEN_NULL")
                    projection!!.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            setStatus("ANDROID SCREEN CAPTURE STOPPED")
                            stopCaptureInternal()
                            stopSelf()
                        }
                    }, captureHandler)
                    createVirtualDisplay()
                    isProjectionActive = true
                    readingEnabled = false
                    setStatus("SCREEN CAPTURE ACTIVE · OPEN BROKER APP, TAP PUTRA BALL")
                    startFloatingControls()
                    initializePythonEngine()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start MediaProjection", e)
                    setStatus("CAPTURE START ERROR · ${e.javaClass.simpleName}")
                    stopCaptureInternal()
                    stopSelf()
                    return START_NOT_STICKY
                }
                return START_STICKY
            }
        }
        return START_STICKY
    }

    private fun createVirtualDisplay() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val density = metrics.densityDpi.coerceAtLeast(120)
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        imageReader!!.setOnImageAvailableListener({ reader -> onImageAvailable(reader) }, captureHandler)
        virtualDisplay = projection?.createVirtualDisplay(
            "PUTRA-Live-Chart-Capture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, captureHandler
        )
        if (virtualDisplay == null) throw IllegalStateException("VIRTUAL_DISPLAY_NOT_CREATED")
    }

    private fun initializePythonEngine() {
        engineExecutor.execute {
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
                val module = Python.getInstance().getModule("putra_android_bridge")
                val config = module.callAttr("configure", filesDir.absolutePath).toString()
                val parsed = JSONObject(config)
                if (!parsed.optBoolean("ready")) throw IllegalStateException("PYTHON_ENGINE_NOT_READY")
                engineReady = true
                setStatus(if (readingEnabled) "ENGINE READY · STARTING LIVE ANALYSIS" else "ENGINE READY · SELECT CHART")
                Log.i(TAG, "On-device Python analysis engine ready: $config")
            } catch (e: Exception) {
                engineReady = false
                Log.e(TAG, "Python engine initialization failed", e)
                setStatus("PYTHON ENGINE ERROR · ${e.javaClass.simpleName}: ${(e.message ?: "").take(80)}")
            }
        }
    }

    private fun startFloatingControls() {
        if (android.provider.Settings.canDrawOverlays(this)) {
            try { startService(Intent(this, FloatingControlService::class.java)) }
            catch (e: Exception) { Log.w(TAG, "Float control unavailable", e) }
        }
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image: Image = try { reader.acquireLatestImage() ?: return } catch (_: Exception) { return }
        try {
            if (!isProjectionActive || !readingEnabled || !engineReady) return
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastSubmittedAt < 650L) return
            if (!analysisBusy.compareAndSet(false, true)) return
            val region = cropRegion?.let { Rect(it) }
            if (region == null) {
                analysisBusy.set(false)
                return
            }
            val png = cropImageToPng(image, region)
            if (png == null) {
                analysisBusy.set(false)
                setStatus("CROP ERROR · SELECT A REGION INSIDE THE SCREEN")
                return
            }
            lastSubmittedAt = now
            engineExecutor.execute {
                try {
                    val module = Python.getInstance().getModule("putra_android_bridge")
                    val json = module.callAttr("analyze_png", png, System.currentTimeMillis()).toString()
                    val result = JSONObject(json)
                    if (!result.optBoolean("ok", false)) throw IllegalStateException("ANALYSIS_RESULT_INVALID")
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("latest_result", json)
                        .putString("status", "READING LIVE · ${result.optInt("candle_count")} CANDLES · ${result.optString("captured_at_manila", "")}")
                        .putLong("last_success_epoch", System.currentTimeMillis())
                        .apply()
                    maybeDispatchQualifiedAlert(result)
                    Log.d(TAG, "Analyzed ${result.optInt("candle_count")} visible candles; ${result.optString("direction")} ${result.opt("score_percent")} %")
                } catch (e: Exception) {
                    Log.e(TAG, "Frame analysis failed", e)
                    val errorStatus = "ANALYSIS ERROR · ${e.javaClass.simpleName}: ${(e.message ?: "").take(70)}"
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .remove("latest_result")
                        .putString("status", errorStatus)
                        .apply()
                } finally {
                    analysisBusy.set(false)
                }
            }
        } catch (e: Exception) {
            analysisBusy.set(false)
            Log.e(TAG, "Frame handling failed", e)
            setStatus("FRAME ERROR · ${e.javaClass.simpleName}")
        } finally {
            try { image.close() } catch (_: Exception) { }
        }
    }

    private fun cropImageToPng(image: Image, requested: Rect): ByteArray? {
        val width = image.width
        val height = image.height
        val left = requested.left.coerceIn(0, maxOf(0, width - 1))
        val top = requested.top.coerceIn(0, maxOf(0, height - 1))
        val right = requested.right.coerceIn(left + 1, width)
        val bottom = requested.bottom.coerceIn(top + 1, height)
        val cropWidth = right - left
        val cropHeight = bottom - top
        if (cropWidth < 100 || cropHeight < 80) return null
        val plane = image.planes.firstOrNull() ?: return null
        val buffer: ByteBuffer = plane.buffer.duplicate()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val pixels = IntArray(cropWidth * cropHeight)
        for (y in 0 until cropHeight) {
            var srcIndex = (top + y) * rowStride + left * pixelStride
            val dstBase = y * cropWidth
            for (x in 0 until cropWidth) {
                val r = buffer.get(srcIndex).toInt() and 0xFF
                val g = buffer.get(srcIndex + 1).toInt() and 0xFF
                val b = buffer.get(srcIndex + 2).toInt() and 0xFF
                val a = buffer.get(srcIndex + 3).toInt() and 0xFF
                pixels[dstBase + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                srcIndex += pixelStride
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, cropWidth, cropHeight, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { out ->
            // PNG is lossless: JPEG artifacts can change red/green mask pixels and candle counts.
            val success = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            if (!success) null else out.toByteArray()
        }
    }

    private fun setStatus(status: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("status", status).apply()
    }

    private fun onCropSelected(region: Rect) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val edit = prefs.edit()
            .remove("latest_result")
            .putString("status", "READING LIVE · CHART CROP ${region.width()} × ${region.height()}")
        // Mirror the desktop alert-cycle start: the first selected crop starts the first alert window.
        if (prefs.getLong("last_alert_epoch", 0L) <= 0L && prefs.getLong("reading_started_epoch", 0L) <= 0L) {
            edit.putLong("reading_started_epoch", now)
        }
        edit.apply()
    }

    /** Native equivalent of the desktop siren gate; the Python score and strategy logic are untouched. */
    private fun maybeDispatchQualifiedAlert(result: JSONObject) {
        val direction = result.optString("direction", "").uppercase(java.util.Locale.US)
        val score = if (result.isNull("score_percent")) Double.NaN else result.optDouble("score_percent", Double.NaN)
        val qualified = result.optBoolean("signal_ready", false) &&
            result.optBoolean("confirmation_gate_passed", false) &&
            result.optBoolean("confirmation_matrix_qualified", false) &&
            score.isFinite() && score >= ALERT_THRESHOLD_PERCENT &&
            result.optInt("candle_count", 0) >= MIN_ALERT_CANDLES &&
            (direction == "BUY" || direction == "SELL")
        if (!qualified) return

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val started = prefs.getLong("reading_started_epoch", 0L)
        if (started <= 0L || now < started) return
        val lastAlert = prefs.getLong("last_alert_epoch", 0L)
        if (lastAlert > 0L && now - lastAlert < ALERT_COOLDOWN_MS) return

        // Persist before notifying to avoid duplicate alarms from adjacent live frames.
        prefs.edit()
            .putLong("last_alert_epoch", now)
            .putLong("score_hidden_until_epoch", now + ALERT_COOLDOWN_MS)
            .putString("alert_status", "QUALIFIED ${direction} SIGNAL · 5-MINUTE COOLDOWN")
            .apply()

        val content = "$direction · ${String.format(java.util.Locale.US, "%.1f", score)}% directional evidence · 5-minute horizon"
        val openApp = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("PUTRA · QUALIFIED $direction")
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$content\n${result.optInt("candle_count")} visible candles · ${result.optInt("confirmation_check_count")} confirmation checks. This is a directional evidence score, not a guaranteed win probability."))
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVibrate(longArrayOf(0, 350, 140, 350, 140, 700))
                .setDefaults(NotificationCompat.DEFAULT_SOUND)
                .build()
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ALERT_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission not granted; using local alarm tone only")
        } catch (e: Exception) {
            Log.w(TAG, "Signal notification could not be displayed", e)
        }
        try {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 90)
            toneGenerator = tone
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 1200)
            Handler(mainLooper).postDelayed({
                try { tone.stopTone(); tone.release() } catch (_: Exception) { }
                if (toneGenerator === tone) toneGenerator = null
            }, 1400L)
        } catch (e: Exception) {
            Log.w(TAG, "Signal alarm tone unavailable", e)
        }
    }

    private fun clearLastResultAndSetStatus(status: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove("latest_result").putString("status", status).apply()
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("PUTRA live candle reading")
            .setContentText("On-device Python engine · no cloud prediction API")
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL_ID, "PUTRA screen reading", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Visible notification while Android screen capture is active"
            }
            val alerts = NotificationChannel(ALERT_CHANNEL_ID, "PUTRA qualified candle alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Sound and vibration only when the Python engine's signal gates pass"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 350, 140, 350, 140, 700)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).build())
            }
            manager.createNotificationChannel(channel)
            manager.createNotificationChannel(alerts)
        }
    }

    private fun stopCaptureInternal() {
        if (serviceStopped) return
        serviceStopped = true
        readingEnabled = false
        isProjectionActive = false
        cropRegion = null
        engineReady = false
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) { }
        try { virtualDisplay?.release() } catch (_: Exception) { }
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) { }
        imageReader = null
        try { projection?.stop() } catch (_: Exception) { }
        projection = null
        try { captureThread?.quitSafely() } catch (_: Exception) { }
        captureThread = null
        captureHandler = null
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("status", "STOPPED").apply()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { @Suppress("DEPRECATION") stopForeground(true) }
        try { stopService(Intent(this, FloatingControlService::class.java)) } catch (_: Exception) { }
        try { toneGenerator?.stopTone(); toneGenerator?.release() } catch (_: Exception) { }
        toneGenerator = null
    }

    override fun onDestroy() {
        stopCaptureInternal()
        if (activeService === this) activeService = null
        try { engineExecutor.shutdownNow() } catch (_: Exception) { }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
