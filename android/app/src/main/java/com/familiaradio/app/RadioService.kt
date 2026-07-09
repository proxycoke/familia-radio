package com.familiaradio.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/**
 * Mantiene la conexión de voz activa aunque la app esté cerrada o la pantalla
 * bloqueada. Solo se usa para el rol de familiar mayor: necesita poder recibir
 * audio en cualquier momento sin tener que abrir la app.
 */
class RadioService : Service() {

    inner class LocalBinder : Binder() {
        val connectionManager: AgoraConnectionManager get() = manager
    }

    private val binder = LocalBinder()
    private lateinit var manager: AgoraConnectionManager

    override fun onCreate() {
        super.onCreate()
        manager = AgoraConnectionManager(applicationContext)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        manager.leaveChannel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Familia Radio",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mantiene el radio familiar conectado en segundo plano"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Familia Radio activo")
            .setContentText("Escuchando al radio familiar")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "familia_radio_channel"
        private const val NOTIFICATION_ID = 1
    }
}
