package fuck.system.hashcat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.rokkystudio.sharedapp.neoui.NeoNotificationViews

/**
 * Keeps Android aware of an active Hashcat run through a foreground notification.
 */
class HashcatForegroundService : Service()
{
    /**
     * Starts or stops the foreground notification according to the incoming
     * command action.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
    {
        when (intent?.action)
        {
            ACTION_STOP ->
            {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else ->
            {
                ensureNotificationChannel()
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        }

        return START_NOT_STICKY
    }

    /**
     * This service is controlled with explicit start commands and does not bind.
     */
    override fun onBind(intent: Intent?): IBinder?
    {
        return null
    }

    private fun ensureNotificationChannel()
    {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.hashcat_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )

        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification
    {
        val title = getString(R.string.hashcat_notification_title)
        val text = getString(R.string.hashcat_notification_text)
        val contentView = NeoNotificationViews.serviceAction(
            context = this,
            title = title,
            text = text,
            iconResId = R.drawable.ic_hashcat
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hashcat)
            .setContentTitle(title)
            .setContentText(text)
            .setCustomContentView(contentView)
            .setCustomBigContentView(contentView)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object
    {
        const val ACTION_START = "fuck.system.hashcat.action.START_HASHCAT_FOREGROUND"
        const val ACTION_STOP = "fuck.system.hashcat.action.STOP_HASHCAT_FOREGROUND"
        private const val NOTIFICATION_CHANNEL_ID = "hashcat_run"
        private const val NOTIFICATION_ID = 22000
    }
}
