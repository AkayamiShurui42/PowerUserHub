package com.poweruserhub.app.service

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.*
import com.poweruserhub.app.MainActivity
import com.poweruserhub.app.model.TriggerAction
import com.poweruserhub.app.model.TriggerPlacement
import kotlin.math.abs
import kotlin.math.roundToInt

class PixelShadeTriggerService : Service() {
    private lateinit var wm: WindowManager
    private var trigger: View? = null
    private lateinit var shell: ShellService

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        shell = ShellService(applicationContext)
        createChannel()
        startForeground(1717, Notification.Builder(this, "pixel_shade")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Pixel Shade trigger active")
            .setContentText("Top-edge gesture detection is running")
            .build())
        showTrigger()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showTrigger()
        return START_STICKY
    }

    private fun showTrigger() {
        trigger?.let { runCatching { wm.removeView(it) } }
        if (!Settings.canDrawOverlays(this)) return
        val c = PixelShadePreferences.load(this)
        if (!c.enabled) return
        val d = resources.displayMetrics.density
        val h = (c.detectionHeightDp * d).roundToInt().coerceAtLeast(1)
        val w = (resources.displayMetrics.widthPixels * c.widthPercent / 100f).roundToInt()
        val statusBar = resources.getIdentifier("status_bar_height", "dimen", "android")
            .takeIf { it != 0 }?.let { resources.getDimensionPixelSize(it) } ?: 0
        val v = View(this).apply {
            setBackgroundColor(if (c.editMode) Color.argb(105, 0, 180, 255) else Color.TRANSPARENT)
            setOnTouchListener(GestureListener())
        }
        val lp = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = if (c.placement == TriggerPlacement.BELOW_STATUS_BAR) statusBar else 0
        }
        wm.addView(v, lp)
        trigger = v
    }

    private inner class GestureListener : View.OnTouchListener {
        private var x0 = 0f; private var y0 = 0f; private var b0 = 128; private var mode = 0
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val c = PixelShadePreferences.load(this@PixelShadeTriggerService)
            val d = resources.displayMetrics.density
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = e.rawX; y0 = e.rawY; mode = 0
                    b0 = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - x0; val dy = e.rawY - y0
                    if (mode == 0) {
                        if (c.brightnessSwipeEnabled && abs(dx) >= c.horizontalSwipeThresholdDp*d && abs(dx) > abs(dy)*1.2f) mode = 2
                        else if (dy >= c.verticalSwipeThresholdDp*d && abs(dy) > abs(dx)*1.2f) mode = 1
                    }
                    if (mode == 2) {
                        val dir = if (c.brightnessReverse) -1f else 1f
                        val value = (b0 + dx/resources.displayMetrics.widthPixels*255f*c.brightnessSensitivity*dir).roundToInt().coerceIn(1,255)
                        shell.executeCommand("settings put system screen_brightness $value")
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val dx=e.rawX-x0; val dy=e.rawY-y0
                    if (mode == 1 || (dy >= c.verticalSwipeThresholdDp*d && abs(dy)>abs(dx))) perform(c.swipeDownAction)
                    else if (mode == 0 && c.tapEnabled && abs(dx)<8*d && abs(dy)<8*d) perform(c.tapAction)
                }
            }
            return true
        }
    }

    private fun perform(action: TriggerAction) {
        when(action) {
            TriggerAction.NONE -> Unit
            TriggerAction.OPEN_QS -> shell.executeCommand("cmd statusbar expand-settings")
            TriggerAction.OPEN_NOTIFICATIONS -> shell.executeCommand("cmd statusbar expand-notifications")
            TriggerAction.OPEN_SHADE -> startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("open_pixel_shade", true)
            })
        }
    }

    private fun createChannel() {
        val nm=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("pixel_shade","Pixel Shade",NotificationManager.IMPORTANCE_MIN))
    }

    override fun onDestroy() { trigger?.let { runCatching { wm.removeView(it) } }; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
