package com.putra.app

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    companion object {
        // Deliberately visible in source, as requested. This is a local access gate, not server-grade auth.
        const val ACCESS_USER = "@markputra.trade"
        const val ACCESS_PASS = "Putra_121204"
        const val SUPPORT_EMAIL = "markanthonypalo102@gmail.com"
        private const val GREEN = 0xFF27F59B.toInt()
        private const val BG = 0xFF06130F.toInt()
        private const val PANEL = 0xFF0A2116.toInt()
        private const val TEXT = 0xFFE4F7ED.toInt()
        private const val MUTED = 0xFF94B6A3.toInt()
        private const val RED = 0xFFFF465E.toInt()
    }

    private val ui = Handler(Looper.getMainLooper())
    private var rainView: BinaryRainView? = null
    private var loginCard: LinearLayout? = null
    private var userEntry: EditText? = null
    private var passEntry: EditText? = null
    private var loginError: TextView? = null
    private var clockLabel: TextView? = null
    private var monitorStatus: TextView? = null
    private var monitorDirection: TextView? = null
    private var monitorScore: TextView? = null
    private var monitorDetails: TextView? = null
    private var appRoot: View? = null
    private var authed = false
    private var pendingActivation = false
    private var nextCapturePromptAfterOverlay = false

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Continue even if denied; local audio can still alert, but Android may suppress notifications.
        activateCaptureFlow()
    }

    private val overlaySettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) {
            startFloatingControls()
            if (pendingActivation) {
                pendingActivation = false
                requestProjectionPermission()
            }
        } else {
            pendingActivation = false
            toast("Kailangan ang Allow display over other apps para lumabas ang green PUTRA touch ball.")
        }
    }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val projectionData = result.data
            val serviceIntent = Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_START
                putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(CaptureService.EXTRA_RESULT_DATA, projectionData)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            startFloatingControls()
            updateStatus("ANDROID SCREEN CAPTURE APPROVED · WAITING FOR CHART CROP", MUTED)
            toast("Buksan ang broker app. Tapikin ang green PUTRA ball → START READING → i-drag ang chart area.")
        } else {
            updateStatus("SCREEN CAPTURE NOT APPROVED", RED)
            toast("Hindi nagsimula ang live reading dahil kinansela ang Android screen-capture permission.")
        }
    }

    private val clockRunnable = object : Runnable {
        override fun run() {
            val now = ZonedDateTime.now(ZoneId.of("Asia/Manila"))
            val text = now.format(DateTimeFormatter.ofPattern("MMM dd yyyy · hh:mm:ss a", Locale.ENGLISH))
            clockLabel?.text = text
            ui.postDelayed(this, 1000)
        }
    }

    private val monitorRunnable = object : Runnable {
        override fun run() {
            refreshMonitor()
            ui.postDelayed(this, 700)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.decorView.systemUiVisibility = 0
        authed = savedInstanceState?.getBoolean("putra_authed") ?: false
        if (authed) buildAppScreen() else buildLoginScreen()
        ui.post(clockRunnable)
        ui.post(monitorRunnable)
    }

    override fun onResume() {
        super.onResume()
        // Do not let the selected chart crop read PUTRA's own monitor/dashboard when this Activity returns to foreground.
        if (authed && CaptureService.isProjectionActive && CaptureService.readingEnabled) {
            CaptureService.pauseReading()
            toast("Reading paused while PUTRA monitor is open. Switch to the broker chart and tap the green ball → START READING to resume.")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("putra_authed", authed)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        ui.removeCallbacks(clockRunnable)
        ui.removeCallbacks(monitorRunnable)
        super.onDestroy()
    }

    private fun buildLoginScreen() {
        val root = FrameLayout(this)
        val rain = BinaryRainView(this)
        rainView = rain
        root.addView(rain, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val scroll = ScrollView(this).apply { setFillViewport(true); clipToPadding = false }
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            background = rounded(PANEL, 18, 0xFF24563C.toInt(), dp(1))
            elevation = dp(10).toFloat()
        }
        loginCard = card
        val wrapper = FrameLayout(this)
        wrapper.addView(card, FrameLayout.LayoutParams(dp(340), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        scroll.addView(wrapper, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        card.addView(label("◉", 30f, GREEN, true).apply { gravity = Gravity.CENTER })
        card.addView(label("PUTRA", 25f, TEXT, true).apply { gravity = Gravity.CENTER; letterSpacing = 0.08f })
        card.addView(label("PRIVATE TRADING WORKSPACE", 9f, GREEN, true).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(15)) })
        card.addView(label("Email / username", 12f, 0xFFB9D6C5.toInt(), true), fullWidthParams(top = 2))
        userEntry = EditText(this).apply {
            hint = "@markputra.trade"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            textSize = 14f
            setTextColor(TEXT); setHintTextColor(0xFF688878.toInt())
            background = rounded(0xFF091B12.toInt(), 10, 0xFF24563C.toInt(), dp(1))
            setPadding(dp(12), dp(11), dp(12), dp(11))
        }
        card.addView(userEntry, fullWidthParams(top = 5, bottom = 12))
        card.addView(label("Password", 12f, 0xFFB9D6C5.toInt(), true), fullWidthParams())
        passEntry = EditText(this).apply {
            hint = "Enter password"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            textSize = 14f
            setTextColor(TEXT); setHintTextColor(0xFF688878.toInt())
            background = rounded(0xFF091B12.toInt(), 10, 0xFF24563C.toInt(), dp(1))
            setPadding(dp(12), dp(11), dp(12), dp(11))
        }
        card.addView(passEntry, fullWidthParams(top = 5, bottom = 5))
        loginError = label("", 11f, RED, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(2), 0, dp(4)) }
        card.addView(loginError, fullWidthParams())
        card.addView(button("SECURE LOGIN   ↗", GREEN, 0xFF04150C.toInt()) { attemptLogin() }, fullWidthParams(top = 5, bottom = 7))
        card.addView(label("Contact us", 12f, 0xFF98E8B9.toInt(), false).apply {
            gravity = Gravity.CENTER; paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            setPadding(0, dp(7), 0, dp(8)); setOnClickListener { openContact() }
        }, fullWidthParams())
        clockLabel = label("", 10f, MUTED, false).apply { gravity = Gravity.CENTER; typeface = Typeface.MONOSPACE }
        card.addView(clockLabel, fullWidthParams(top = 5))
        card.addView(label("LOCAL ACCESS GATE · PUTRA", 9f, 0xFF537260.toInt(), true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }, fullWidthParams())
        setContentView(root)
    }

    private fun attemptLogin() {
        val username = userEntry?.text?.toString()?.trim().orEmpty()
        val password = passEntry?.text?.toString().orEmpty()
        if (username == ACCESS_USER && password == ACCESS_PASS) {
            authed = true
            try {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(passEntry?.windowToken, 0)
            } catch (_: Exception) { }
            rainView?.red = false
            buildAppScreen()
            // Android needs explicit system consent for screen capture/overlay; alert notifications are optional.
            ui.postDelayed({ requestNotificationPermissionThenActivate() }, 350)
        } else {
            rainView?.red = true
            ui.postDelayed({ rainView?.red = false }, 1800)
            loginError?.text = "Incorrect username or password."
            passEntry?.setText("")
            try { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(passEntry?.windowToken, 0) } catch (_: Exception) { }
        }
    }

    private fun buildAppScreen() {
        val base = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        appRoot = base
        val scroll = ScrollView(this).apply { setFillViewport(true) }
        base.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(18), dp(16), dp(20)) }
        scroll.addView(content)

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("◉", 29f, GREEN, true), LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.WRAP_CONTENT))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("PUTRA", 24f, TEXT, true))
        titles.addView(label("LIVE CANDLE ANALYZER · M1 → 5 MIN", 10f, GREEN, true))
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(header, fullWidthParams(bottom = 16))

        val info = card()
        info.addView(label("ON-DEVICE ANALYSIS", 12f, GREEN, true))
        info.addView(label("The Python pattern/strategy engine runs locally on this phone. The Android screen-capture service sends chart frames to that engine; no cloud prediction API is used.", 13f, TEXT, false).apply { setPadding(0, dp(8), 0, dp(8)) })
        info.addView(label("To start: approve Android screen capture, switch to the broker chart, tap the green floating ball → START READING, then drag a rectangle around the candles.", 12f, MUTED, false))
        content.addView(info, fullWidthParams(bottom = 12))

        val monitor = card()
        monitor.addView(label("LIVE MONITOR", 12f, GREEN, true))
        monitorStatus = label("NOT STARTED", 11f, MUTED, true).apply { setPadding(0, dp(6), 0, dp(10)) }
        monitor.addView(monitorStatus, fullWidthParams())
        monitorDirection = label("WAITING FOR CHART", 23f, TEXT, true)
        monitor.addView(monitorDirection, fullWidthParams(bottom = 4))
        monitorScore = label("—", 30f, GREEN, true)
        monitor.addView(monitorScore, fullWidthParams(bottom = 6))
        monitorDetails = label("Candles: —   Green: —   Red: —\nPatterns: —   Confirmations: —", 12f, MUTED, false)
        monitor.addView(monitorDetails, fullWidthParams())
        content.addView(monitor, fullWidthParams(bottom = 12))

        content.addView(button("ACTIVATE LIVE SCREEN CAPTURE", GREEN, 0xFF04150C.toInt()) { activateCaptureFlow() }, fullWidthParams(bottom = 9))
        content.addView(button("SHOW FLOATING CONTROL", 0xFF123B2A.toInt(), TEXT) { startFloatingControls() }, fullWidthParams(bottom = 9))
        content.addView(button("CONTACT US", 0xFF102B1F.toInt(), TEXT) { openContact() }, fullWidthParams(bottom = 9))
        content.addView(button("LOG OUT", 0xFF3A1720.toInt(), TEXT) {
            stopService(Intent(this, FloatingControlService::class.java))
            CaptureService.stopCapture(this)
            authed = false
            buildLoginScreen()
        }, fullWidthParams(bottom = 14))
        content.addView(label("Scores are directional evidence, not verified winning probabilities. Trades remain manual. A chart that cannot be read will show an error instead of a made-up signal.", 11f, MUTED, false))

        clockLabel = label("", 10f, MUTED, false).apply { gravity = Gravity.CENTER; typeface = Typeface.MONOSPACE; setPadding(0, dp(12), 0, 0) }
        base.addView(clockLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(base)
        refreshMonitor()
    }

    private fun requestNotificationPermissionThenActivate() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            activateCaptureFlow()
        }
    }

    private fun activateCaptureFlow() {
        if (!authed) return
        if (!Settings.canDrawOverlays(this)) {
            pendingActivation = true
            try {
                overlaySettingsLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                pendingActivation = false
                toast("Hindi mabuksan ang overlay settings. I-enable ang PUTRA sa Settings → Display over other apps.")
            }
            return
        }
        startFloatingControls()
        if (CaptureService.isProjectionActive) {
            updateStatus("SCREEN CAPTURE ACTIVE · READY TO SELECT CANDLES", GREEN)
            return
        }
        requestProjectionPermission()
    }

    private fun requestProjectionPermission() {
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            projectionLauncher.launch(manager.createScreenCaptureIntent())
        } catch (e: Exception) {
            updateStatus("CANNOT OPEN SCREEN-CAPTURE PERMISSION", RED)
            toast("Hindi mabuksan ang Android screen-capture permission: ${e.javaClass.simpleName}")
        }
    }

    private fun startFloatingControls() {
        if (!Settings.canDrawOverlays(this)) return
        // CaptureService is the foreground service; the overlay controller itself is a normal service.
        try { startService(Intent(this, FloatingControlService::class.java)) }
        catch (_: Exception) { toast("Hindi masimulan ang floating control. Buksan muli ang PUTRA.") }
    }

    private fun refreshMonitor() {
        val prefs = getSharedPreferences(CaptureService.PREFS, MODE_PRIVATE)
        val status = prefs.getString("status", if (CaptureService.isProjectionActive) "SCREEN CAPTURE ACTIVE · SELECT CHART" else "NOT STARTED") ?: "NOT STARTED"
        monitorStatus?.text = status
        monitorStatus?.setTextColor(if (status.contains("ERROR") || status.contains("FAILED")) RED else if (status.contains("LIVE") || status.contains("ACTIVE")) GREEN else MUTED)
        val raw = prefs.getString("latest_result", null) ?: return
        try {
            val data = JSONObject(raw)
            val direction = data.optString("direction", "ANALYZING")
            val score = if (data.isNull("score_percent")) null else data.optDouble("score_percent")
            monitorDirection?.text = "$direction · ${data.optString("candidate_status", "CANDIDATE").replace('_', ' ')}"
            monitorDirection?.setTextColor(if (direction == "BUY") GREEN else if (direction == "SELL") RED else TEXT)
            val hiddenUntil = getSharedPreferences(CaptureService.PREFS, MODE_PRIVATE).getLong("score_hidden_until_epoch", 0L)
            val scoreHidden = System.currentTimeMillis() < hiddenUntil
            monitorScore?.text = if (scoreHidden) "HIDDEN DURING 5-MIN COOLDOWN" else score?.let { String.format(Locale.US, "%.1f%%", it) } ?: "NO SCORE"
            monitorScore?.setTextColor(if (scoreHidden) MUTED else if (direction == "BUY") GREEN else if (direction == "SELL") RED else MUTED)
            val cooldownText = if (scoreHidden) "\nScore hidden during alert cooldown · ${((hiddenUntil - System.currentTimeMillis()).coerceAtLeast(0L) / 1000L)}s left" else ""
            monitorDetails?.text = "Candles: ${data.optInt("candle_count")}  Green: ${data.optInt("green_count")}  Red: ${data.optInt("red_count")}\n" +
                "Patterns: ${data.optInt("pattern_count")}  Confirmed: ${data.optInt("confirmed_patterns")}\n" +
                "Checks: ${data.optInt("confirmation_check_count")}  Agreement: ${data.optDouble("confirmation_agreement_percent").toInt()}%\n" +
                "5-minute signal alert: ${if (data.optBoolean("signal_ready")) "QUALIFIED" else "NOT QUALIFIED"}$cooldownText\n" +
                data.optString("captured_at_manila", "") + " · ${data.optDouble("analysis_ms").toInt()} ms"
        } catch (_: Exception) {
            monitorDirection?.text = "WAITING FOR VALID ANALYSIS"
            monitorScore?.text = "—"
        }
    }

    private fun updateStatus(text: String, color: Int) {
        monitorStatus?.text = text
        monitorStatus?.setTextColor(color)
        getSharedPreferences(CaptureService.PREFS, MODE_PRIVATE).edit().putString("status", text).apply()
    }

    private fun openContact() {
        try {
            val uri = Uri.parse("mailto:$SUPPORT_EMAIL?subject=PUTRA%20App%20Support")
            startActivity(Intent(Intent.ACTION_SENDTO, uri))
        } catch (_: Exception) { toast("Walang naka-install na email app. Email: $SUPPORT_EMAIL") }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(14), dp(15), dp(14))
        background = rounded(0xFF0A2118.toInt(), 14, 0xFF173E2D.toInt(), dp(1))
        elevation = dp(2).toFloat()
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean): TextView = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        includeFontPadding = true
    }

    private fun button(text: String, backgroundColor: Int, foregroundColor: Int, action: () -> Unit): Button = Button(this).apply {
        this.text = text; textSize = 12f; isAllCaps = false; setTextColor(foregroundColor)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        background = rounded(backgroundColor, 11, backgroundColor, 0)
        minHeight = dp(46); setPadding(dp(12), dp(8), dp(12), dp(8)); setOnClickListener { action() }
    }

    private fun rounded(color: Int, radiusDp: Int, stroke: Int, strokePx: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
        if (strokePx > 0) setStroke(strokePx, stroke)
    }

    private fun fullWidthParams(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (top > 0) topMargin = dp(top); if (bottom > 0) bottomMargin = dp(bottom)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

private class BinaryRainView(context: Context) : View(context) {
    var red: Boolean = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textSize = 13f }
    private var columns = IntArray(42) { Random.nextInt(-22, 0) }
    private var lastWidth = 0
    private var steps = 0

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFF06130F.toInt())
        if (width != lastWidth) {
            lastWidth = width
            columns = IntArray(max(12, width / 13)) { Random.nextInt(-22, 0) }
        }
        paint.color = if (red) 0xFFFF465E.toInt() else 0xFF168D57.toInt()
        paint.alpha = if (red) 235 else 165
        for (i in columns.indices) {
            val y = columns[i] * 15f
            for (trail in 0..4) {
                val yy = y - trail * 15f
                if (yy > -15 && yy < height + 15) canvas.drawText(if (Random.nextBoolean()) "0" else "1", i * 13f, yy, paint)
            }
            columns[i] += 1
            if (columns[i] * 15f > height && Random.nextFloat() > 0.91f) columns[i] = Random.nextInt(-24, -1)
        }
        steps++
        postInvalidateDelayed(85)
    }
}
