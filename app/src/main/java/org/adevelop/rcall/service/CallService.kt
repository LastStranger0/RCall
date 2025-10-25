package org.adevelop.rcall.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.adevelop.rcall.R
import org.adevelop.rcall.ui.CallActivity

class CallService : Service() {

    companion object {
        private const val CHANNEL_ID = "webrtc_call_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        startAsForegroundService()
    }

    private fun startAsForegroundService() {
        createNotificationChannel()

        val pendingIntent: PendingIntent =
            Intent(this, CallActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
            }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Активный звонок")
            .setContentText("Нажмите, чтобы вернуться в приложение")
            .setSmallIcon(R.drawable.ic_call)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        val foregroundServiceType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
            } else {
                0
            }

        startForeground(NOTIFICATION_ID, notification, foregroundServiceType)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Активные звонки"
            val descriptionText = "Уведомления, которые отображаются во время WebRTC звонка"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
