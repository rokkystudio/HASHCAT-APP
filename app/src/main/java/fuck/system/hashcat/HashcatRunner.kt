package fuck.system.hashcat

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import kotlin.concurrent.thread

/**
 * Launches a native Hashcat executable stored in the APK as a native shared
 * object. Android extracts jniLibs into applicationInfo.nativeLibraryDir during
 * installation when jniLibs.useLegacyPackaging is enabled in Gradle.
 *
 * The payload is produced by the :hashcat Android library module and merged
 * into this APK through implementation(project(":hashcat")).
 *
 * The runtime module packages the published executable and ABI-matched
 * assets for all four Android architectures. The executable runs from
 * Android's extracted native-library directory and loads its matching
 * writable asset tree through HASHCAT_SHARED_FOLDER.
 *
 * Process output, cancellation and final resource cleanup are owned by
 * this runner.
 */
class HashcatRunner(
    private val context: Context
)
{
    interface Listener
    {
        /**
         * Receives the exact command after the native process has started.
         */
        fun onStarted(command: List<String>)

        /**
         * Receives one line from Hashcat's combined stdout and stderr stream.
         */
        fun onOutput(line: String)

        /**
         * Receives the process exit code after Hashcat terminates.
         */
        fun onFinished(exitCode: Int)

        /**
         * Receives process-launch or stream-reading failures.
         */
        fun onError(error: Throwable)
    }

    data class HashMode(
        val mode: Int,
        val name: String,
        val category: String
    )

    sealed class CandidateSource
    {
        data class Wordlist(
            val file: File
        ) : CandidateSource()

        data class Mask(
            val mask: String,
            val customCharset: String,
            val incrementMin: Int,
            val incrementMax: Int
        ) : CandidateSource()
    }

    /**
     * Defines the optional Android memory budget for a Hashcat run.
     */
    enum class MemoryProfile(val memoryLimitMb: Int?)
    {
        DISABLED(null),
        MB_512(512),
        MB_768(768),
        MB_1024(1024),
        MB_1280(1280),
        MB_1536(1536)
    }

    /**
     * Defines an optional Hashcat workload profile passed through -w.
     */
    enum class WorkloadProfile(val value: Int?)
    {
        DISABLED(null),
        LOW(1),
        DEFAULT(2),
        HIGH(3),
        MAXIMUM(4)
    }

    @Volatile
    private var process: Process? = null

    @Volatile
    private var stopRequested: Boolean = false

    /**
     * Returns true while the native Hashcat process is alive.
     */
    fun isRunning(): Boolean
    {
        return process?.isAlive == true
    }

    /**
     * Prepares the writable Hashcat data tree. This performs file I/O only and
     * is intended to run on a worker thread before the user starts an attack.
     */
    fun prepare()
    {
        prepareRuntime()
    }

    /**
     * Reads the hash-mode catalog generated from the same Hashcat release as the
     * published Android binaries.
     */
    fun loadHashModes(): List<HashMode>
    {
        val json = context.assets.open(HASH_MODE_CATALOG).bufferedReader().use { reader ->
            reader.readText()
        }
        val array = JSONArray(json)
        val modes = ArrayList<HashMode>(array.length())

        for (index in 0 until array.length())
        {
            val item = array.getJSONObject(index)

            modes += HashMode(
                mode = item.getInt("mode"),
                name = item.getString("name"),
                category = item.getString("category")
            )
        }

        if (modes.isEmpty())
        {
            throw IOException("Bundled Hashcat hash-mode catalog is empty.")
        }

        return modes
    }

    /**
     * Starts the native executable, forwards its combined stdout/stderr stream to
     * the listener, passes the selected backend exclusion flag and enables
     * Hashcat memory-budget diagnostics in that stream. Inputs are regular private
     * files because native code cannot read Android
     * content:// URIs directly.
     */
    @Synchronized
    fun start(
        hashFile: File,
        candidateSource: CandidateSource,
        hashMode: Int,
        recoveredFile: File,
        memoryProfile: MemoryProfile,
        workloadProfile: WorkloadProfile,
        optimizedKernel: Boolean,
        backend: ComputeBackend,
        listener: Listener
    )
    {
        if (isRunning())
        {
            throw IllegalStateException("Hashcat is already running.")
        }

        stopRequested = false

        val executable = prepareRuntime()
        val runtimeDirectory = resolveRuntimeDirectory()
        val nativeDirectory = resolveNativeLibraryDirectory()

        File(context.filesDir, "hashcat/work").mkdirs()
        val dataDirectory = File(context.filesDir, "hashcat/data").apply {
            mkdirs()
        }
        val cacheDirectory = File(context.cacheDir, "hashcat").apply {
            mkdirs()
        }
        val potfile = File(dataDirectory, "hashcat-app.potfile")
        recoveredFile.parentFile?.mkdirs()

        val command = buildCommand(
            executable = executable,
            hashFile = hashFile,
            candidateSource = candidateSource,
            hashMode = hashMode,
            potfile = potfile,
            recovered = recoveredFile,
            memoryProfile = memoryProfile,
            workloadProfile = workloadProfile,
            optimizedKernel = optimizedKernel,
            backend = backend
        )

        launchNativeProcess(
            command = command,
            runtimeDirectory = runtimeDirectory,
            nativeDirectory = nativeDirectory,
            dataDirectory = dataDirectory,
            cacheDirectory = cacheDirectory,
            memoryLimitMb = memoryProfile.memoryLimitMb,
            listener = listener
        )
    }


    /**
     * Execute one native command with common cancellation and output handling.
     *
     * The selected runtime supplies ABI-matched assets and a private HOME,
     * temporary and cache directory. Workload memory limits are optional.
     * One owned native process forwards its combined output to the listener
     * and clears the process reference after final exit or stream failure.
     */
    private fun launchNativeProcess(
        command: List<String>,
        runtimeDirectory: File,
        nativeDirectory: File,
        dataDirectory: File,
        cacheDirectory: File,
        memoryLimitMb: Int?,
        listener: Listener
    )
    {
        val processCommand = buildPrioritizedCommand(command)
        val processBuilder = ProcessBuilder(processCommand)
            .directory(runtimeDirectory)
            .redirectErrorStream(true)

        processBuilder.environment().apply {
            put("HOME", dataDirectory.absolutePath)
            put("TMPDIR", cacheDirectory.absolutePath)
            put("XDG_DATA_HOME", dataDirectory.absolutePath)
            put("XDG_CACHE_HOME", cacheDirectory.absolutePath)
            put("HASHCAT_SHARED_FOLDER", runtimeDirectory.absolutePath)
            memoryLimitMb?.let { limitMb ->
                put("HASHCAT_MEMORY_LIMIT_MB", limitMb.toString())
            }
            putNativeLibraryPath(
                runtimeDirectory,
                nativeDirectory,
                resolveVendorNativeLibraryDirectory()
            )
        }

        if (stopRequested)
        {
            throw InterruptedIOException("Hashcat start was cancelled before native process launch.")
        }

        Log.i(LOG_TAG, "Starting: ${processCommand.joinToString(" ")}")
        Log.d(
            LOG_TAG,
            "Runtime=${runtimeDirectory.absolutePath}, " +
                "native=${nativeDirectory.absolutePath}, " +
                "vendor=${resolveVendorNativeLibraryDirectory().absolutePath}"
        )

        val startedProcess = try
        {
            processBuilder.start()
        }
        catch (error: Throwable)
        {
            Log.e(LOG_TAG, "Failed to start Hashcat.", error)
            throw error
        }

        process = startedProcess
        if (stopRequested)
        {
            startedProcess.destroy()
        }
        listener.onStarted(processCommand)

        thread(name = "hashcat-output") {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)

            try
            {
                startedProcess.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        listener.onOutput(line)
                    }
                }

                val exitCode = startedProcess.waitFor()
                Log.i(LOG_TAG, "Finished with exit code $exitCode")
                listener.onFinished(exitCode)
            }
            catch (error: Throwable)
            {
                if (error is InterruptedIOException && stopRequested)
                {
                    Log.i(LOG_TAG, "Hashcat output stream interrupted by requested stop.")
                    val exitCode = waitForStoppedProcess(startedProcess)
                    Log.i(LOG_TAG, "Finished with exit code $exitCode after requested stop")
                    listener.onFinished(exitCode)
                }
                else
                {
                    Log.e(LOG_TAG, "Hashcat process failed.", error)
                    listener.onError(error)
                }
            }
            finally
            {
                synchronized(this)
                {
                    if (process === startedProcess)
                    {
                        process = null
                    }
                }
            }
        }
    }

    private fun buildPrioritizedCommand(command: List<String>): List<String>
    {
        val nice = File("/system/bin/nice")

        return if (nice.canExecute())
        {
            listOf(nice.absolutePath, "-n", HASHCAT_NICE_INCREMENT.toString()) + command
        }
        else
        {
            command
        }
    }

    /**
     * Returns the stopped process exit code after a bounded wait.
     */
    private fun waitForStoppedProcess(stoppedProcess: Process): Int
    {
        if (!stoppedProcess.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        {
            stoppedProcess.destroyForcibly()
            stoppedProcess.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        return try
        {
            stoppedProcess.exitValue()
        }
        catch (_: IllegalThreadStateException)
        {
            STOPPED_EXIT_CODE
        }
    }

    /**
     * Requests termination of the current native process and kills it if it does
     * not exit quickly after the graceful signal.
     */
    fun stop(reason: String = "unspecified")
    {
        Log.w(LOG_TAG, "Stop requested: $reason")
        stopRequested = true

        val activeProcess = process ?: return

        thread(name = "hashcat-stop") {
            activeProcess.destroy()
            waitForStoppedProcess(activeProcess)
        }
    }

    /**
     * Prepares the installed ABI resources and resolves the packaged native
     * executable, validating its presence and executable permission.
     */
    private fun prepareRuntime(): File
    {
        val runtimeDirectory = resolveRuntimeDirectory().apply {
            mkdirs()
        }

        copyHashcatSharedAssets(runtimeDirectory)

        val executable = resolveBundledExecutable()

        if (!executable.isFile)
        {
            throw IOException(
                "Native Hashcat backend is missing from the APK. " +
                    "Verify the published archive in hashcat/binaries and rebuild the app."
            )
        }

        if (!executable.canExecute())
        {
            throw IOException("Packaged Hashcat backend is not executable: ${executable.absolutePath}")
        }

        return executable
    }

    /**
     * Copies versioned native assets for the device's ABI into private storage.
     */
    private fun copyHashcatSharedAssets(runtimeDirectory: File)
    {
        val assetRoot = resolveHashcatAssetRoot()
        val rootEntries = context.assets.list(assetRoot)
            ?.filter { it.isNotBlank() }
            .orEmpty()

        if (rootEntries.isEmpty())
        {
            throw IOException(
                "Hashcat shared files are missing from the APK. " +
                    "Verify the published archive in hashcat/binaries and rebuild the app."
            )
        }

        val marker = File(runtimeDirectory, ASSET_MARKER_FILE)
        val assetVersion = resolveAssetVersion()
        val openClDirectory = File(runtimeDirectory, "OpenCL")

        if (marker.isFile && marker.readText() == assetVersion && openClDirectory.isDirectory)
        {
            return
        }

        rootEntries.forEach { entry ->
            copyAssetTree(
                assetPath = "$assetRoot/$entry",
                target = File(runtimeDirectory, entry)
            )
        }

        marker.writeText(assetVersion)
    }

    /**
     * Recursively copies an asset path into a regular filesystem path.
     */
    private fun copyAssetTree(assetPath: String, target: File)
    {
        val children = context.assets.list(assetPath)
            ?.filter { it.isNotBlank() }
            .orEmpty()

        if (children.isEmpty())
        {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return
        }

        target.mkdirs()

        children.forEach { child ->
            copyAssetTree(
                assetPath = "$assetPath/$child",
                target = File(target, child)
            )
        }
    }

    /**
     * Resolves the first packaged asset ABI supported by Android.
     */
    private fun resolveHashcatAssetRoot(): String
    {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { supportedAbi ->
            context.assets.list("$HASHCAT_ASSET_ROOT/$supportedAbi")
                ?.isNotEmpty() == true
        } ?: throw IOException(
            "No bundled Hashcat runtime matches this device ABI: " +
                Build.SUPPORTED_ABIS.joinToString()
        )

        return "$HASHCAT_ASSET_ROOT/$abi"
    }

    /**
     * Returns a stable value that changes when the installed APK changes.
     */
    private fun resolveAssetVersion(): String
    {
        @Suppress("DEPRECATION")
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)

        return packageInfo.lastUpdateTime.toString()
    }

    /**
     * Resolves the installed executable from Android's native-library folder.
     */
    private fun resolveBundledExecutable(): File
    {
        return File(resolveNativeLibraryDirectory(), HASHCAT_EXECUTABLE_NAME)
    }

    private fun resolveNativeLibraryDirectory(): File
    {
        return File(context.applicationInfo.nativeLibraryDir)
    }

    private fun resolveRuntimeDirectory(): File
    {
        return File(context.filesDir, "hashcat/runtime")
    }

    /**
     * Resolves the vendor native-library directory matching this app process.
     * Android devices commonly expose GPU OpenCL implementations from this
     * directory, including vendor-public libOpenCL.so implementations.
     */
    private fun resolveVendorNativeLibraryDirectory(): File
    {
        return if (android.os.Process.is64Bit())
        {
            File("/vendor/lib64")
        }
        else
        {
            File("/vendor/lib")
        }
    }

    /**
     * Prepends native library folders to LD_LIBRARY_PATH so companion native
     * dependencies can be placed in the runtime or jniLibs/<abi>/ directory.
     */
    private fun MutableMap<String, String>.putNativeLibraryPath(vararg directories: File)
    {
        val current = this["LD_LIBRARY_PATH"].orEmpty()
        val prefix = directories.joinToString(":") { it.absolutePath }

        this["LD_LIBRARY_PATH"] = if (current.isBlank())
        {
            prefix
        }
        else
        {
            "$prefix:$current"
        }
    }

    internal companion object
    {
        /**
         * Builds the native Hashcat workload command with an explicit compute backend,
         * placing its exclusion flag before hash and candidate arguments.
         */
        internal fun buildCommand(
            executable: File,
            hashFile: File,
            candidateSource: CandidateSource,
            hashMode: Int,
            potfile: File,
            recovered: File,
            memoryProfile: MemoryProfile,
            workloadProfile: WorkloadProfile,
            optimizedKernel: Boolean,
            backend: ComputeBackend
        ): List<String>
        {
            val command = ArrayList<String>()

            command += executable.absolutePath
            command += "-m"
            command += hashMode.toString()
            command += "-a"
            command += when (candidateSource)
            {
                is CandidateSource.Wordlist -> "0"
                is CandidateSource.Mask -> "3"
            }
            command += "--status"
            command += "--status-timer=1"
            command += "--self-test-disable"
            command += "--session"
            command += "hashcat-app"
            command += "--potfile-path"
            command += potfile.absolutePath
            command += "--outfile"
            command += recovered.absolutePath

            workloadProfile.value?.let { workload ->
                command += "-w"
                command += workload.toString()
            }

            if (optimizedKernel)
            {
                command += "-O"
            }

            command += when (backend)
            {
                ComputeBackend.OPENCL -> "--backend-ignore-vulkan"
                ComputeBackend.VULKAN -> "--backend-ignore-opencl"
            }

            when (candidateSource)
            {
                is CandidateSource.Wordlist ->
                {
                    command += hashFile.absolutePath
                    command += candidateSource.file.absolutePath
                }

                is CandidateSource.Mask ->
                {
                    if (candidateSource.customCharset.isNotBlank())
                    {
                        command += "-1"
                        command += candidateSource.customCharset
                    }

                    command += "--increment"
                    command += "--increment-min"
                    command += candidateSource.incrementMin.toString()
                    command += "--increment-max"
                    command += candidateSource.incrementMax.toString()
                    command += hashFile.absolutePath
                    command += candidateSource.mask
                }
            }

            return command
        }


        private const val LOG_TAG = "Hashcat"
        private const val HASHCAT_EXECUTABLE_NAME = "libhashcat_exec.so"
        private const val HASHCAT_ASSET_ROOT = "hashcat"
        private const val ASSET_MARKER_FILE = ".hashcat-app-assets-version"
        private const val STOP_TIMEOUT_SECONDS = 2L
        private const val STOPPED_EXIT_CODE = 143
        private const val HASHCAT_NICE_INCREMENT = 10
        private const val HASH_MODE_CATALOG = "hashcat-modes.json"
    }
}
