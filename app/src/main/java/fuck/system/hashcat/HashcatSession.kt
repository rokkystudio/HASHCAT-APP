package fuck.system.hashcat

import android.content.Context
import android.content.Intent
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Owns the native Hashcat process independently from Activity instances.
 * A foreground service keeps the app process alive while this session forwards
 * process events and buffered log text to the currently attached UI.
 */
class HashcatSession private constructor(private val appContext: Context)
{
    enum class RunState
    {
        IDLE,
        STARTING,
        RUNNING,
        STOPPING
    }

    data class Snapshot(
        val runState: RunState,
        val logText: String,
        val command: List<String>?,
        val recoveredFile: File?,
        val hashFile: File?,
        val error: Throwable?
    )

    interface Observer
    {
        fun onSnapshot(snapshot: Snapshot)
        fun onStarted(command: List<String>)
        fun onOutput(line: String)
        fun onFinished(exitCode: Int, recoveredFile: File, hashFile: File)
        fun onError(error: Throwable)
    }

    private val runner = HashcatRunner(appContext)
    private val observers = CopyOnWriteArraySet<Observer>()
    private val lock = Any()
    private val logHistory = ArrayDeque<String>()
    private var runState = RunState.IDLE
    private var command: List<String>? = null
    private var recoveredFile: File? = null
    private var hashFile: File? = null
    private var error: Throwable? = null
    private var logCharCount = 0

    fun addObserver(observer: Observer)
    {
        observers += observer
        observer.onSnapshot(snapshot())
    }

    fun removeObserver(observer: Observer)
    {
        observers -= observer
    }

    fun isRunning(): Boolean
    {
        return runner.isRunning() || runState == RunState.STARTING || runState == RunState.RUNNING || runState == RunState.STOPPING
    }

    fun snapshot(): Snapshot
    {
        synchronized(lock)
        {
            return Snapshot(
                runState = runState,
                logText = buildLogTextLocked(),
                command = command,
                recoveredFile = recoveredFile,
                hashFile = hashFile,
                error = error
            )
        }
    }

    @Synchronized
    fun start(
        hashFile: File,
        candidateSource: HashcatRunner.CandidateSource,
        hashMode: Int,
        recoveredFile: File,
        memoryProfile: HashcatRunner.MemoryProfile,
        workloadProfile: HashcatRunner.WorkloadProfile,
        optimizedKernel: Boolean
    )
    {
        if (isRunning())
        {
            throw IllegalStateException("Hashcat is already running.")
        }

        synchronized(lock)
        {
            runState = RunState.STARTING
            command = null
            this.recoveredFile = recoveredFile
            this.hashFile = hashFile
            error = null
            logHistory.clear()
            logCharCount = 0
        }

        startForegroundService()

        try
        {
            runner.start(
                hashFile = hashFile,
                candidateSource = candidateSource,
                hashMode = hashMode,
                recoveredFile = recoveredFile,
                memoryProfile = memoryProfile,
                workloadProfile = workloadProfile,
                optimizedKernel = optimizedKernel,
                listener = createRunnerListener(recoveredFile, hashFile)
            )
        }
        catch (failure: Throwable)
        {
            synchronized(lock)
            {
                runState = RunState.IDLE
                error = failure
            }
            stopForegroundService()
            throw failure
        }
    }

    fun stop(reason: String)
    {
        synchronized(lock)
        {
            if (runState == RunState.IDLE)
            {
                return
            }
            runState = RunState.STOPPING
        }
        runner.stop(reason)
    }

    private fun createRunnerListener(recoveredFile: File, hashFile: File): HashcatRunner.Listener
    {
        return object : HashcatRunner.Listener
        {
            override fun onStarted(command: List<String>)
            {
                synchronized(lock)
                {
                    runState = RunState.STARTING
                    this@HashcatSession.command = command
                    appendLogLineLocked("$ " + command.joinToString(" "))
                }
                observers.forEach { it.onStarted(command) }
            }

            override fun onOutput(line: String)
            {
                synchronized(lock)
                {
                    appendLogLineLocked(line)
                    if (line.startsWith("Status") && line.contains("Running", ignoreCase = true))
                    {
                        runState = RunState.RUNNING
                    }
                }
                observers.forEach { it.onOutput(line) }
            }

            override fun onFinished(exitCode: Int)
            {
                synchronized(lock)
                {
                    runState = RunState.IDLE
                }
                stopForegroundService()
                observers.forEach { it.onFinished(exitCode, recoveredFile, hashFile) }
            }

            override fun onError(error: Throwable)
            {
                synchronized(lock)
                {
                    runState = RunState.IDLE
                    this@HashcatSession.error = error
                    appendLogLineLocked("ERROR: ${error.message ?: error.javaClass.simpleName}")
                }
                stopForegroundService()
                observers.forEach { it.onError(error) }
            }
        }
    }

    private fun appendLogLineLocked(line: String)
    {
        logHistory.addLast(line)
        logCharCount += line.length + 1

        while (logCharCount > MAX_LOG_CHARS && logHistory.isNotEmpty())
        {
            val removed = logHistory.removeFirst()
            logCharCount -= removed.length + 1
        }
    }

    private fun buildLogTextLocked(): String
    {
        return buildString(logCharCount) {
            logHistory.forEach { line ->
                append(line)
                append('\n')
            }
        }
    }

    private fun startForegroundService()
    {
        appContext.startForegroundService(
            Intent(appContext, HashcatForegroundService::class.java).setAction(
                HashcatForegroundService.ACTION_START
            )
        )
    }

    private fun stopForegroundService()
    {
        appContext.startService(
            Intent(appContext, HashcatForegroundService::class.java).setAction(
                HashcatForegroundService.ACTION_STOP
            )
        )
    }

    companion object
    {
        private const val MAX_LOG_CHARS = 512 * 1024

        @Volatile
        private var instance: HashcatSession? = null

        fun get(context: Context): HashcatSession
        {
            return instance ?: synchronized(this)
            {
                instance ?: HashcatSession(context.applicationContext).also { instance = it }
            }
        }
    }
}
