package com.example.moma

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

/** 网关前台服务：常驻通知，避免系统杀掉本地 HTTP 服务。 */
class GatewayService : Service() {

    companion object {
        const val CHANNEL_ID = "moma_gateway"
        const val NOTIFY_ID = 1001

        @Volatile
        var gateway: GatewayServer? = null
            private set

        @Volatile
        var isRunningFlag: Boolean = false
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFY_ID, buildNotification())
        if (gateway == null) {
            val cfg = ConfigStore(this)
            val g = GatewayServer(
                port = cfg.port,
                upstreamBaseRaw = cfg.baseUrl,
                keys = cfg.apiKeys,
                defaultModel = cfg.model,
            )
            g.start()
            gateway = g
            isRunningFlag = true
        }
        return START_STICKY
    }

    override fun onDestroy() {
        gateway?.stop()
        gateway = null
        isRunningFlag = false
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "MoMA 网关", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val cfg = ConfigStore(this)
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("MoMA 网关运行中")
            .setContentText("端口 ${cfg.port} · 局域网开放 (0.0.0.0)")
            .setOngoing(true)
            .build()
    }
}
