package fuck.system.hashcat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies exclusive native backend flags and positional argument ordering.
 */
class HashcatCommandTest
{
    /**
     * Builds the native command for the selected backend and a mask attack.
     */
    private fun command(backend: ComputeBackend): List<String>
    {
        return HashcatRunner.buildCommand(
            executable = File("/native/libhashcat_exec.so"),
            hashFile = File("/private/hashes.input"),
            candidateSource = HashcatRunner.CandidateSource.Mask(
                mask = "?d?d?d",
                customCharset = "",
                incrementMin = 1,
                incrementMax = 3
            ),
            hashMode = 0,
            potfile = File("/private/session.potfile"),
            recovered = File("/private/output.txt"),
            memoryProfile = HashcatRunner.MemoryProfile.DISABLED,
            workloadProfile = HashcatRunner.WorkloadProfile.DISABLED,
            optimizedKernel = false,
            backend = backend,
            backendFlagsSupported = true
        )
    }

    /**
     * Verifies the OpenCL exclusion flag precedes the hash argument and the
     * Vulkan exclusion flag is absent.
     */
    @Test
    fun openclSelectsOnlyOpencl()
    {
        val command = command(ComputeBackend.OPENCL)
        assertEquals(1, command.count { it == "--backend-ignore-vulkan" })
        assertFalse(command.contains("--backend-ignore-opencl"))
        assertTrue(command.indexOf("--backend-ignore-vulkan") < command.indexOf(File("/private/hashes.input").absolutePath))
        assertTrue(command.indexOf(File("/private/hashes.input").absolutePath) < command.indexOf("?d?d?d"))
    }

    /**
     * Verifies the Vulkan exclusion flag precedes the hash argument and the
     * OpenCL exclusion flag is absent.
     */
    @Test
    fun vulkanSelectsOnlyVulkan()
    {
        val command = command(ComputeBackend.VULKAN)
        assertEquals(1, command.count { it == "--backend-ignore-opencl" })
        assertFalse(command.contains("--backend-ignore-vulkan"))
        assertTrue(command.indexOf("--backend-ignore-opencl") < command.indexOf(File("/private/hashes.input").absolutePath))
    }

    /**
     * Verifies a dictionary attack places the exclusive backend flag before
     * both the hash input and the wordlist path.
     */
    @Test
    fun wordlistArgumentsFollowBackendFlag()
    {
        val command = HashcatRunner.buildCommand(
            executable = File("/native/libhashcat_exec.so"),
            hashFile = File("/private/hashes.input"),
            candidateSource = HashcatRunner.CandidateSource.Wordlist(File("/private/words.txt")),
            hashMode = 0,
            potfile = File("/private/session.potfile"),
            recovered = File("/private/output.txt"),
            memoryProfile = HashcatRunner.MemoryProfile.DISABLED,
            workloadProfile = HashcatRunner.WorkloadProfile.DISABLED,
            optimizedKernel = true,
            backend = ComputeBackend.OPENCL,
            backendFlagsSupported = true
        )

        assertEquals(1, command.count { it == "--backend-ignore-vulkan" })
        assertTrue(command.indexOf("--backend-ignore-vulkan") < command.indexOf(File("/private/hashes.input").absolutePath))
        assertTrue(command.indexOf(File("/private/hashes.input").absolutePath) < command.indexOf(File("/private/words.txt").absolutePath))
        assertTrue(command.contains("-O"))
    }

    /**
     * Verifies a packaged binary without backend flags receives no exclusion
     * flag, so a workload still starts on an older native archive.
     */
    @Test
    fun legacyBinaryOmitsBackendFlags()
    {
        val command = HashcatRunner.buildCommand(
            executable = File("/native/libhashcat_exec.so"),
            hashFile = File("/private/hashes.input"),
            candidateSource = HashcatRunner.CandidateSource.Wordlist(File("/private/words.txt")),
            hashMode = 0,
            potfile = File("/private/session.potfile"),
            recovered = File("/private/output.txt"),
            memoryProfile = HashcatRunner.MemoryProfile.DISABLED,
            workloadProfile = HashcatRunner.WorkloadProfile.DISABLED,
            optimizedKernel = false,
            backend = ComputeBackend.OPENCL,
            backendFlagsSupported = false
        )

        assertFalse(command.contains("--backend-ignore-vulkan"))
        assertFalse(command.contains("--backend-ignore-opencl"))
        assertTrue(command.contains(File("/private/hashes.input").absolutePath))
    }
}
