package com.mantra.sampleplayer

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * WHAT THE OVERLAY IS FOR, AND WHY IT IS NOT MORE THAN THIS.
 *
 * Put the app in the background, read a script off the screen, speak, press the triangle, speak
 * again. Samples fill from one slot to the next like buckets. That is the whole workflow this app
 * exists for and it is the reason the overlay is not a convenience.
 *
 * THREE PIECES AND NO MORE:
 *
 *   the hairline    one device pixel across the very top, not touchable, showing the level
 *   the triangle    under the camera hole, touchable, present only while recording
 *   the status      under the camera hole, present only while generating, "12 / 30"
 *
 * `TTT_MINI`'s `MaRecordingLine` records what happens when this is got wrong: it became a black
 * notch carrying a meter, a red dot, a clock, a bin, a send arrow and a language badge — six
 * things in a strip stolen from the status bar, and every control on it had a better home
 * already. So the triangle advances and does nothing else. Stopping is done in the app.
 */
object OverlayState {

    /** Set by the recorder and by Generate. The service watches these and nothing else. */
    val recordingSlot = MutableStateFlow<Int?>(null)
    val generating = MutableStateFlow<String?>(null)

    /** Set by the service, read by the app: the triangle was pressed. */
    val advanceRequested = MutableStateFlow(0L)
}

/**
 * THE HAIRLINE, PORTED CONSTANT FOR CONSTANT FROM `TTT_MINI/MaRecordingLine.kt`.
 *
 * It was removed from this app on instruction and put back on argument. With the app in the
 * background there is nothing else that says audio is arriving, and `recorder.md` 3 names the one
 * lie a level display must never tell: a meter with automatic gain and no floor amplifies the
 * noise of a disconnected microphone into a convincing dance. Set a floor, and a flat trace means
 * the microphone is genuinely not receiving — which is the single most useful thing a recorder can
 * say before somebody talks for ten minutes into nothing.
 */
private class HairlineView(context: Context) : View(context) {

    private val paint = Paint().apply { color = Color.WHITE }
    var level: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val w = width * level
        canvas.drawRect(0f, 0f, w, height.toFloat(), paint)
    }
}

/**
 * THE TRIANGLE. Outlined, pointing right, and it carries the number 30 at the last slot.
 *
 * It does not wrap. Wrapping would begin overwriting slot 1 while the phone is in a pocket and
 * the person is reading from another app, which is the exact condition under which nobody would
 * notice.
 */
private class TriangleView(context: Context) : View(context) {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = dp(1.5f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(9f)
    }
    private val path = Path()

    var glyph: String = ""
        set(value) { field = value; invalidate() }

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics,
    )

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val inset = dp(3f)
        path.reset()
        path.moveTo(inset, inset)
        path.lineTo(w - inset, h / 2f)
        path.lineTo(inset, h - inset)
        path.close()
        canvas.drawPath(path, stroke)
        if (glyph.isNotEmpty()) {
            canvas.drawText(glyph, w * 0.42f, h / 2f + text.textSize / 3f, text)
        }
    }
}

/** The generating status. A terminal status line, as small as it can be read. */
private class StatusView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 10f, context.resources.displayMetrics,
        )
    }
    private val back = Paint().apply { color = Color.argb(190, 0, 0, 0) }

    var line: String = ""
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), back)
        canvas.drawText(line, width / 2f, height * 0.72f, paint)
    }
}

/**
 * EVERYTHING OVERLAID GOES THROUGH ONE ACCESSIBILITY SERVICE.
 *
 * `TYPE_ACCESSIBILITY_OVERLAY` rather than `TYPE_APPLICATION_OVERLAY`, so one permission covers
 * the whole app, and because draw-over-apps cannot draw over the notification shade — measured in
 * `MANTRA_ROUTE`.
 */
class OverlayService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private var hairline: HairlineView? = null
    private var triangle: TriangleView? = null
    private var status: StatusView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        showRecording(null, 0f)
        showGenerating(null)
        instance = null
        super.onDestroy()
    }

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics,
    ).toInt()

    /**
     * Show or hide the recording pieces. Idempotent: it is driven from a state flow that emits on
     * every level change, and adding a window that is already added throws.
     */
    fun showRecording(slot: Int?, level: Float) {
        if (slot == null) {
            hairline?.let { runCatching { wm.removeView(it) } }
            triangle?.let { runCatching { wm.removeView(it) } }
            hairline = null
            triangle = null
            return
        }
        if (hairline == null) {
            val h = HairlineView(this)
            // MATCH_PARENT wide, one device pixel tall. Not dp(1), which is three physical pixels
            // on this phone and reads as a stripe rather than a hairline. NOT_TOUCHABLE as well as
            // NOT_FOCUSABLE: with nothing to press, every touch goes to the app underneath and the
            // strip costs nothing at all.
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP }
            runCatching { wm.addView(h, p); hairline = h }
        }
        if (triangle == null) {
            val t = TriangleView(this)
            t.setOnClickListener { OverlayState.advanceRequested.value = System.currentTimeMillis() }
            val p = WindowManager.LayoutParams(
                dp(34f),
                dp(24f),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                // Directly under the camera hole, which on this phone is centred at the top.
                y = dp(30f)
            }
            runCatching { wm.addView(t, p); triangle = t }
        }
        hairline?.level = level
        triangle?.glyph = Advance.glyph(slot)
    }

    fun showGenerating(line: String?) {
        if (line == null) {
            status?.let { runCatching { wm.removeView(it) } }
            status = null
            return
        }
        if (status == null) {
            val s = StatusView(this)
            val p = WindowManager.LayoutParams(
                dp(76f),
                dp(16f),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(30f)
            }
            runCatching { wm.addView(s, p); status = s }
        }
        status?.line = line
    }

    companion object {
        @Volatile var instance: OverlayService? = null

        fun isRunning(): Boolean = instance != null
    }
}

/**
 * THE FOREGROUND SERVICE, so the microphone keeps working with the app in the background.
 *
 * `FOREGROUND_SERVICE_TYPE_MICROPHONE`, as `TTT_MINI/MaRecordingService` does. Without the type
 * declared, Android 14 stops the capture the moment the app leaves the screen and the symptom is
 * a slot that fills with nothing.
 */
class RecordingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "recording"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Recording", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val n: Notification = Notification.Builder(this, channelId)
            .setContentTitle("Sample Player")
            .setContentText("Recording")
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
        return START_STICKY
    }
}
