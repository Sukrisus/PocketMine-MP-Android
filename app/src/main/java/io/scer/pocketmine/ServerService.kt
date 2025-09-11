package io.scer.pocketmine

import android.app.IntentService
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.scer.pocketmine.screens.home.MainActivity
import io.scer.pocketmine.server.Server
import io.scer.pocketmine.server.ServerBus
import io.scer.pocketmine.server.StopEvent

class ServerService : IntentService("pocketmine_intent_service") {
    private val notificationId = 1
    private val stopIntentAction = "stop"
    // Handled below with PHAR pre-check

    private val notificationPendingIntent: PendingIntent get() =
        PendingIntent.getActivity(
                applicationContext,
                notificationId,
                Intent(applicationContext, MainActivity::class.java),
                PendingIntent.FLAG_CANCEL_CURRENT
        )

    private val stopPendingIntent: PendingIntent get() {
        val stopIntent = Intent(applicationContext, this::class.java)
        stopIntent.action = stopIntentAction

        return PendingIntent.getService(
                applicationContext,
                notificationId,
                stopIntent,
                PendingIntent.FLAG_CANCEL_CURRENT
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent !== null && intent.action === stopIntentAction) Server.getInstance().kill()

        return Service.START_NOT_STICKY
    }

    override fun onHandleIntent(intent: Intent?) {
        // Ensure PHAR exists before starting the server
        val server = Server.getInstance()
        val pharFile = server.files.phar
        if (!pharFile.exists()) {
            try {
                val url = java.net.URL("https://github.com/pmmp/PocketMine-MP/releases/download/5.33.1/PocketMine-MP.phar")
                url.openStream().use { input ->
                    java.io.FileOutputStream(pharFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (_: Exception) {
                // ServerFragment listens and shows toast via ErrorEvent
                ServerBus.publish(io.scer.pocketmine.server.ErrorEvent(null, io.scer.pocketmine.server.Errors.PHAR_NOT_EXIST))
                return
            }
        }
        server.run()
    }

    override fun onDestroy() {
        stopObserver.dispose()
        Server.getInstance().kill()
    }
}