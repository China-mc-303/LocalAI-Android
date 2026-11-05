package com.buxingjie.localai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import com.buxingjie.localai.R
import com.buxingjie.localai.tools.PhoneControl

/**
 * AI 操控手机时显示在屏幕上的“紧急停止”悬浮按钮。
 */
class QuickStopService : Service() {

    private var windowManager: WindowManager? = null
    private var overlay: FrameLayout? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        showOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }

    private fun stopEverything() {
        PhoneControl.abort(this)
        removeOverlay()
        stopSelf()
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        if (overlay != null) return
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = manager

        val button = Button(this).apply {
            text = "紧急关闭"
            setTextColor(Color.WHITE)
            textSize = 15f
            background = GradientDrawable().apply {
                cornerRadius = 100f
                setColor(Color.parseColor("#D93025"))
                setStroke(2, Color.WHITE)
            }
            setPadding(36, 16, 36, 16)
            setOnClickListener { stopEverything() }
        }
        val container = FrameLayout(this).apply {
            addView(button)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120
        }
        runCatching {
            manager.addView(container, params)
            overlay = container
        }
    }

    private fun removeOverlay() {
        overlay?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        overlay = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI 操控手机",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "AI 操作手机时显示紧急停止按钮"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, QuickStopService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("AI 正在操控手机")
            .setContentText("点击“紧急关闭”可立即中断所有操作")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "紧急关闭", stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "quick_stop"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.buxingjie.localai.STOP_PHONE_CONTROL"

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, QuickStopService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, QuickStopService::class.java)) }
        }
    }
}
