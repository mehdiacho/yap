package com.mehdiacho.yap.overlay

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.mehdiacho.yap.MainActivity
import com.mehdiacho.yap.R
import com.mehdiacho.yap.audio.RecorderStatus
import com.mehdiacho.yap.audio.RecordingBus
import com.mehdiacho.yap.audio.RecordingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A draggable, always-on-top mic bubble (system overlay). Tap to start/stop recording,
 * drag to move (snaps to nearest edge), fades when idle. Reflects the recorder state
 * (indigo mic when idle, coral stop when recording).
 */
class FloatingMicService : Service() {

    private var wm: WindowManager? = null
    private var bubble: FrameLayout? = null
    private var icon: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val fade = Runnable { bubble?.animate()?.alpha(0.35f)?.setDuration(400)?.start() }

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (bubble == null) addBubble()
        return START_STICKY
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun addBubble() {
        val w = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = w
        val size = dp(56)

        val iv = ImageView(this).apply {
            setImageResource(R.drawable.ic_tile_mic)
            setColorFilter(Color.WHITE)
            val p = dp(15); setPadding(p, p, p, p)
        }
        val root = FrameLayout(this).apply {
            addView(iv, FrameLayout.LayoutParams(size, size))
            elevation = dp(8).toFloat()
        }
        bubble = root; icon = iv
        applyStatus(RecorderStatus.IDLE)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12); y = dp(220)
        }
        params = lp
        runCatching { w.addView(root, lp) }
        setupTouch(root, lp, w)
        scheduleFade()

        scope.launch { RecordingBus.state.collect { applyStatus(it.status) } }
    }

    private fun applyStatus(status: RecorderStatus) {
        val recording = status != RecorderStatus.IDLE
        bubble?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (recording) 0xFFFF6B6B.toInt() else 0xFF1A63D8.toInt())
        }
        icon?.setImageResource(if (recording) R.drawable.ic_stop else R.drawable.ic_tile_mic)
        icon?.setColorFilter(Color.WHITE)
        if (recording) {
            handler.removeCallbacks(fade)
            bubble?.animate()?.alpha(1f)?.start()
        } else {
            scheduleFade()
        }
    }

    private fun scheduleFade() {
        handler.removeCallbacks(fade)
        handler.postDelayed(fade, 3000)
    }

    private fun setupTouch(view: View, lp: WindowManager.LayoutParams, w: WindowManager) {
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                    handler.removeCallbacks(fade)
                    view.animate().alpha(1f).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) moved = true
                    lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                    runCatching { w.updateViewLayout(view, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap() else snapToEdge(view, lp, w)
                    scheduleFade()
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge(view: View, lp: WindowManager.LayoutParams, w: WindowManager) {
        val screenW = resources.displayMetrics.widthPixels
        lp.x = if (lp.x + view.width / 2 < screenW / 2) dp(12) else screenW - view.width - dp(12)
        runCatching { w.updateViewLayout(view, lp) }
    }

    private fun onTap() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            // Can't request a runtime permission from an overlay — bounce into the app.
            startActivity(Intent(this, MainActivity::class.java).apply {
                // CLEAR_TOP as well as NEW_TASK: NEW_TASK on its own was enough, on some
                // OEM builds, to stand up a second MainActivity beside the running one —
                // and two of those meant one recording transcribed (and billed) twice.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_START_RECORDING, true)
            })
            return
        }
        if (RecordingBus.state.value.status == RecorderStatus.IDLE) RecordingController.start(this)
        else RecordingController.stop(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        handler.removeCallbacks(fade)
        bubble?.let { b -> runCatching { wm?.removeView(b) } }
        bubble = null; icon = null; params = null
    }
}
