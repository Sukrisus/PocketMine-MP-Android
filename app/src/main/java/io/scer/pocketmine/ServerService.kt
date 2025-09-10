package io.scer.pocketmine

import android.app.IntentService
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import io.scer.pocketmine.screens.home.MainActivity
import io.scer.pocketmine.server.Server
import io.scer.pocketmine.server.ServerBus
import io.scer.pocketmine.server.StopEvent

class ServerService : IntentService("pocketmine_intent_service") {
    private val notificationId = 1
    private val stopIntentAction = "stop"
    
    override fun onHandleIntent(intent: Intent?) {}

    private val notificationPendingIntent: PendingIntent get() {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_CANCEL_CURRENT
        }
        
        return PendingIntent.getActivity(
            applicationContext,
            notificationId,
            Intent(applicationContext, MainActivity::class.java),
            flags
        )
    }

    private val stopPendingIntent: PendingIntent get() {
        val stopIntent = Intent(applicationContext, this::class.java)
        stopIntent.action = stopIntentAction

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_CANCEL_CURRENT
        }

        return PendingIntent.getService(
            applicationContext,
            notificationId,
            stopIntent,
            flags
        )
    }

    private val stopObserver = ServerBus.listen(StopEvent::class.java).subscribe {
        stopSelf()
    }

    override fun onCreate() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setOngoing(true)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentIntent(notificationPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(0, getString(R.string.stop_server), stopPendingIntent)
            .build()

        startForeground(notificationId, notification)
        Server.getInstance().run()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == stopIntentAction) {
            Server.getInstance().kill()
        }
        return Service.START_NOT_STICKY
    }

    override fun onDestroy() {
        stopObserver.dispose()
        Server.getInstance().kill()
    }
}