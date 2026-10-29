package com.putra.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FloatingControlService : Service() {
    private lateinit var wm: WindowManager
    private var ball: TextView? = null
    private var popup: LinearLayout? = null
    private var selector: RegionSelectionView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var popupParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var originalX = 0
    private var originalY = 0
    private var resultText: TextView? = null
    private var statusText: TextView? = null

    private val refreshPopup = object : Runnable {
        override fun run() {
            updatePopup()
            handler.postDelayed(this, 700)
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (!android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        addBall()
        handler.post(refreshPopup)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ball == null && android.provider.Settings.canDrawOverlays(this)) addBall()
        return START_STICKY
    }

    private fun addBall() {
        if (ball != null) return
        val view = TextView(this).apply {
            text = "●"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(0xFF04150C.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF27F59B.toInt())
                setStroke(dp(2), 0xFFB4FFD9.toInt())
            }
            elevation = dp(8).toFloat()
            contentDescription = "PUTRA touch ball. Tap for Start Reading menu; drag to move."
        }
        val params = WindowManager.LayoutParams(
            dp(58), dp(58), overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(14); y = dp(250) }
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false; downRawX = event.rawX; downRawY = event.rawY
                    originalX = params.x; originalY = params.y; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > dp(5) || abs(dy) > dp(5)) dragging = true
                    if (dragging) {
                        params.x = (originalX + dx).coerceAtLeast(0)
                        params.y = (originalY + dy).coerceAtLeast(0)
                        try { wm.updateViewLayout(view, params) } catch (_: Exception) { }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) togglePopup()
                    true
                }
                else -> true
            }
        }
        try {
            wm.addView(view, params)
            ball = view; ballParams = params
        } catch (e: Exception) {
            Toast.makeText(this, "PUTRA floating ball could not be displayed. Check overlay permission.", Toast.LENGTH_LONG).show()
        }
    }

    private fun togglePopup() {
        if (popup != null) { removePopup(); return }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(0xF0091B13.toInt())
                cornerRadius = dp(17).toFloat()
                setStroke(dp(1), 0xFF27F59B.toInt())
            }
            elevation = dp(12).toFloat()
        }
        card.addView(TextView(this).apply {
            text = "◉  PUTRA"
            textSize = 19f; setTextColor(0xFF27F59B.toInt()); typeface = Typeface.DEFAULT_BOLD
        }, matchParams(bottom = 3))
        card.addView(TextView(this).apply {
            text = "M1 candles · 5-minute analysis"
            textSize = 11f; setTextColor(0xFFB7D9C6.toInt())
        }, matchParams(bottom = 8))
        statusText = TextView(this).apply { textSize = 11f; setTextColor(0xFF94B6A3.toInt()); setPadding(0, dp(4), 0, dp(5)) }
        card.addView(statusText, matchParams())
        resultText = TextView(this).apply { textSize = 12f; setTextColor(Color.WHITE); setPadding(0, dp(2), 0, dp(9)) }
        card.addView(resultText, matchParams())
        card.addView(actionButton("START READING", 0xFF27F59B.toInt(), 0xFF04150C.toInt()) { onStartReading() }, matchParams(bottom = 7))
        card.addView(actionButton("SELECT / REDRAG CHART", 0xFF123B2A.toInt(), Color.WHITE) { beginRegionSelection() }, matchParams(bottom = 7))
        card.addView(actionButton("OPEN FULL MONITOR", 0xFF102B1F.toInt(), Color.WHITE) { openMonitor() }, matchParams(bottom = 7))
        card.addView(actionButton("STOP READING", 0xFF3A1720.toInt(), Color.WHITE) {
            CaptureService.pauseReading()
            Toast.makeText(this, "PUTRA reading paused. Screen sharing remains active until stopped from the notification.", Toast.LENGTH_LONG).show()
            updatePopup()
        }, matchParams())
        val params = WindowManager.LayoutParams(
            dp(286), WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12); y = dp(80)
        }
        try {
            wm.addView(card, params)
            popup = card; popupParams = params; updatePopup()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not show PUTRA popup: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun onStartReading() {
        if (!CaptureService.isProjectionActive) {
            Toast.makeText(this, "Open PUTRA and approve ACTIVATE LIVE SCREEN CAPTURE first. Android requires permission before another app's screen can be read.", Toast.LENGTH_LONG).show()
            openMonitor()
            return
        }
        if (CaptureService.cropRegion == null) beginRegionSelection() else {
            CaptureService.setRegion(Rect(CaptureService.cropRegion!!))
            Toast.makeText(this, "PUTRA is reading the selected chart crop.", Toast.LENGTH_SHORT).show()
            updatePopup()
        }
    }

    private fun beginRegionSelection() {
        if (!CaptureService.isProjectionActive) {
            Toast.makeText(this, "Enable live screen capture in PUTRA first.", Toast.LENGTH_LONG).show()
            openMonitor()
            return
        }
        removePopup()
        removeBall()
        if (selector != null) return
        val view = RegionSelectionView(this) { rect ->
            removeSelector()
            CaptureService.setRegion(rect)
            addBall()
            togglePopup()
            Toast.makeText(this, "Selected ${rect.width()} × ${rect.height()} chart pixels. Live analysis started.", Toast.LENGTH_LONG).show()
        }
        selector = view
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try { wm.addView(view, params) }
        catch (e: Exception) {
            selector = null; addBall()
            Toast.makeText(this, "Chart selection overlay failed: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun removeSelector() {
        val current = selector ?: return
        try { wm.removeView(current) } catch (_: Exception) { }
        selector = null
    }

    private fun removeBall() {
        val current = ball ?: return
        try { wm.removeView(current) } catch (_: Exception) { }
        ball = null; ballParams = null
    }

    private fun removePopup() {
        val current = popup ?: return
        try { wm.removeView(current) } catch (_: Exception) { }
        popup = null; popupParams = null; statusText = null; resultText = null
    }

    private fun openMonitor() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Open PUTRA from the app icon to view the monitor.", Toast.LENGTH_LONG).show()
        }
    }

    private fun updatePopup() {
        statusText?.text = getSharedPreferences(CaptureService.PREFS, MODE_PRIVATE)
            .getString("status", if (CaptureService.isProjectionActive) "SCREEN CAPTURE ACTIVE" else "CAPTURE PERMISSION NEEDED")
        val raw = getSharedPreferences(CaptureService.PREFS, MODE_PRIVATE).getString("latest_result", null)
        if (raw == null) {
            resultText?.text = "Waiting for the first valid candle frame.\nNo score is displayed until analysis succeeds."
            return
        }
        try {
            val obj = JSONObject(raw)
            val direction = obj.optString("direction", "ANALYZING")
            val hiddenUntil = getSharedPreferences(CaptureService.PREFS, MODE_PRIVATE).getLong("score_hidden_until_epoch", 0L)
            val scoreHidden = System.currentTimeMillis() < hiddenUntil
            val score = if (scoreHidden) "HIDDEN DURING 5-MIN COOLDOWN" else if (obj.isNull("score_percent")) "—" else String.format(Locale.US, "%.1f%%", obj.optDouble("score_percent"))
            resultText?.text = "$direction · $score\nCandles ${obj.optInt("candle_count")} (G ${obj.optInt("green_count")} / R ${obj.optInt("red_count")})\n" +
                "Patterns ${obj.optInt("pattern_count")} · Confirmed ${obj.optInt("confirmed_patterns")}\n" +
                "Confirmation checks ${obj.optInt("confirmation_check_count")}\n" +
                "Alert: ${if (obj.optBoolean("signal_ready")) "QUALIFIED" else "NOT QUALIFIED"}\n" +
                obj.optString("captured_at_manila", "")
        } catch (_: Exception) { resultText?.text = "Could not read latest analysis result." }
    }

    private fun actionButton(text: String, background: Int, foreground: Int, action: () -> Unit): Button = Button(this).apply {
        this.text = text; textSize = 11f; isAllCaps = false; setTextColor(foreground)
        typeface = Typeface.DEFAULT_BOLD; minHeight = dp(42); setPadding(dp(9), dp(4), dp(9), dp(4))
        this.background = GradientDrawable().apply { setColor(background); cornerRadius = dp(9).toFloat() }
        setOnClickListener { action() }
    }

    private fun matchParams(bottom: Int = 0): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { if (bottom > 0) bottomMargin = dp(bottom) }

    private fun overlayType(): Int = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        handler.removeCallbacks(refreshPopup)
        removeSelector(); removePopup(); removeBall()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

private class RegionSelectionView(
    context: Context,
    private val onSelected: (Rect) -> Unit
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var startX = -1f
    private var startY = -1f
    private var endX = -1f
    private var endY = -1f
    private var selecting = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0x70000000)
        paint.color = 0xFF27F59B.toInt(); paint.textSize = 18f; paint.typeface = Typeface.DEFAULT_BOLD; paint.style = Paint.Style.FILL
        canvas.drawText("PUTRA · DRAG TO SELECT CANDLE CHART", dp(18).toFloat(), dp(35).toFloat(), paint)
        paint.color = Color.WHITE; paint.textSize = 12f; paint.typeface = Typeface.DEFAULT
        canvas.drawText("Select chart candles only. Avoid the menu and price axis if possible.", dp(18).toFloat(), dp(57).toFloat(), paint)
        if (selecting) {
            val rect = RectF(min(startX, endX), min(startY, endY), max(startX, endX), max(startY, endY))
            paint.color = 0x3327F59B; paint.style = Paint.Style.FILL; canvas.drawRect(rect, paint)
            paint.color = 0xFF27F59B.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2).toFloat(); canvas.drawRect(rect, paint)
            paint.style = Paint.Style.FILL; paint.textSize = 13f
            canvas.drawText("${rect.width().toInt()} × ${rect.height().toInt()}", rect.left + dp(5), max(dp(75).toFloat(), rect.top - dp(8).toFloat()), paint)
        } else {
            val cx = width / 2f; val cy = height / 2f
            paint.color = 0xFF27F59B.toInt(); paint.strokeWidth = dp(2).toFloat(); paint.style = Paint.Style.STROKE
            canvas.drawCircle(cx, cy, dp(21).toFloat(), paint)
            canvas.drawLine(cx - dp(34), cy, cx + dp(34), cy, paint)
            canvas.drawLine(cx, cy - dp(34), cx, cy + dp(34), paint)
            paint.style = Paint.Style.FILL; paint.textSize = 12f
            canvas.drawText("+  START DRAG HERE", cx + dp(28), cy + dp(47), paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX; startY = event.rawY; endX = startX; endY = startY; selecting = true; invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> {
                endX = event.rawX; endY = event.rawY; invalidate(); return true
            }
            MotionEvent.ACTION_UP -> {
                endX = event.rawX; endY = event.rawY
                val left = min(startX, endX).toInt().coerceAtLeast(0)
                val top = min(startY, endY).toInt().coerceAtLeast(0)
                val right = max(startX, endX).toInt().coerceAtLeast(left)
                val bottom = max(startY, endY).toInt().coerceAtLeast(top)
                val rect = Rect(left, top, right, bottom)
                if (rect.width() < dp(100) || rect.height() < dp(80)) {
                    selecting = false; startX = -1f; startY = -1f; invalidate()
                    Toast.makeText(context, "Too small. Drag a larger rectangle around the candles.", Toast.LENGTH_LONG).show()
                } else {
                    onSelected(rect)
                }
                return true
            }
        }
        return true
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
