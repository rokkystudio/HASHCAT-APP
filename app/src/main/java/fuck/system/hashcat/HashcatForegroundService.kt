package fuck.system.hashcat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import kotlin.concurrent.thread

/**
 * Owns the native Hashcat process while an audit is active.
 *
 * The service enters foreground state before Hashcat is launched, keeps the
 * process alive when the activity leaves the foreground and exposes a local
 * binder so the UI can start, stop and observe the current job.
 */
class HashcatForegroundService : Service()
{
    enum class State
    {
        IDLE,
        STARTING,
        RUNNING,
        STOPPING
    }

    data class StartRequest(
        val hashFile: File,
        val candidateSource: HashcatRunner.CandidateSource,
        val hashMode: Int,
        val recoveredFile: File
    )

    interface Listener
    {
        fun onStateChanged(state: State)

        fun onStarted(command: List<String>)

        fun onOutput(line: String)

        fun onFinished(exitCode: Int, recoveredFile: File)

        fun onStopped(exitCode: Int?)

        fun onError(error: Throwable)
    }

    inner class LocalBinder : Binder()
    {
        fun service(): HashcatForegroundService = this@HashcatForegroundService
    }

    private val binder = LocalBinder()
    private val hashcatRunner by lazy { HashcatRunner(applicationContext) }

    @Volatile
    private var listener: Listener? = null

    @Volatile
    private var state: State = State.IDLE

    @Volatile
    private var stopRequested: Boolean = false

    @Volatile
    private var activeRequest: StartRequest? = null

    override fun onCreate()
    {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder
    {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
    {
        when (intent?.action)
        {
            ACTION_STOP -> stopHashcat("notification action")
            ACTION_ENTER_FOREGROUND -> enterForeground(state)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy()
    {
        listener = null
        hashcatRunner.stop("foreground service destroyed")
        super.onDestroy()
    }

    fun currentState(): State
    {
        return state
    }

    fun setListener(listener: Listener)
    {
        this.listener = listener
        listener.onStateChanged(state)
    }

    fun clearListener(listener: Listener)
    {
        if (this.listener === listener)
        {
            this.listener = null
        }
    }

    @Synchronized
    fun startHashcat(request: StartRequest): Boolean
    {
        if (state != State.IDLE)
        {
            return false
        }

        state = State.STARTING
        stopRequested = false
        activeRequest = request
        notifyStateChanged()

        enterForeground(State.STARTING)

        thread(name = "hashcat-service-start") {
            try
            {
                hashcatRunner.start(
                    hashFile = request.hashFile,
                    candidateSource = request.candidateSource,
                    hashMode = request.hashMode,
                    recoveredFile = request.recoveredFile,
                    listener = createRunnerListener(request)
                )
            }
            catch (error: Throwable)
            {
                if (stopRequested)
                {
                    completeStopped(null)
                }
                else
                {
                    completeError(error)
                }
            }
        }

        return true
    }

    @Synchronized
    fun stopHashcat(reason: String)
    {
        if (state == State.IDLE || state == State.STOPPING)
        {
            return
        }

        stopRequested = true
        state = State.STOPPING
        notifyStateChanged()
        updateNotification(State.STOPPING)
        hashcatRunner.stop(reason)
    }

    private fun createRunnerListener(request: StartRequest): HashcatRunner.Listener
    {
        return object : HashcatRunner.Listener
        {
            override fun onStarted(command: List<String>)
            {
                if (stopRequested)
                {
                    hashcatRunner.stop("stop requested while Hashcat was starting")
                    return
                }

                listener?.onStarted(command)
            }

            override fun onOutput(line: String)
            {
                if (state == State.STARTING && !stopRequested)
                {
                    state = State.RUNNING
                    notifyStateChanged()
                    updateNotification(State.RUNNING)
                }

                listener?.onOutput(line)
            }

            override fun onFinished(exitCode: Int)
            {
                if (stopRequested)
                {
                    completeStopped(exitCode)
                }
                else
                {
                    state = State.IDLE
                    activeRequest = null
                    notifyStateChanged()
                    listener?.onFinished(exitCode, request.recoveredFile)
                    leaveForeground()
                }
            }

            override fun onError(error: Throwable)
            {
                if (stopRequested)
                {
                    completeStopped(null)
                }
                else
                {
                    completeError(error)
                }
            }
        }
    }

    @Synchronized
    private fun completeStopped(exitCode: Int?)
    {
        state = State.IDLE
        activeRequest = null
        stopRequested = false
        notifyStateChanged()
        listener?.onStopped(exitCode)
        leaveForeground()
    }

    @Synchronized
    private fun completeError(error: Throwable)
    {
        state = State.IDLE
        activeRequest = null
        stopRequested = false
        notifyStateChanged()
        listener?.onError(error)
        leaveForeground()
    }

    private fun notifyStateChanged()
    {
        listener?.onStateChanged(state)
    }

    private fun enterForeground(state: State)
    {
        val notification = createNotification(state)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        {
            val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            else
            {
                0
            }

            startForeground(NOTIFICATION_ID, notification, foregroundType)
        }
        else
        {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(state: State)
    {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, createNotification(state))
    }

    private fun leaveForeground()
    {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(state: State): Notification
    {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, HashcatForegroundService::class.java)
                .setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hashcat)
            .setContentTitle(getString(R.string.hashcat_notification_title))
            .setContentText(notificationText(state))
            .setContentIntent(contentIntent)
            .setOngoing(state != State.IDLE)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (state == State.STARTING || state == State.RUNNING || state == State.STOPPING)
        {
            builder.addAction(
                0,
                getString(R.string.stop_hashcat),
                stopIntent
            )
        }

        return builder.build()
    }

    private fun notificationText(state: State): String
    {
        return when (state)
        {
            State.IDLE -> getString(R.string.hashcat_notification_idle)
            State.STARTING -> getString(R.string.hashcat_notification_starting)
            State.RUNNING -> getString(R.string.hashcat_notification_running)
            State.STOPPING -> getString(R.string.hashcat_notification_stopping)
        }
    }

    private fun createNotificationChannel()
    {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
        {
            return
        }

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.hashcat_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.hashcat_notification_channel_description)
            setShowBadge(false)
        }

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    companion object
    {
        private const val NOTIFICATION_CHANNEL_ID = "hashcat_execution"
        private const val NOTIFICATION_ID = 22000
        const val ACTION_ENTER_FOREGROUND = "fuck.system.hashcat.action.ENTER_FOREGROUND"
        const val ACTION_STOP = "fuck.system.hashcat.action.STOP"
    }
}
