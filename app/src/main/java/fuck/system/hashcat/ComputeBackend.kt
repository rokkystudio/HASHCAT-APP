package fuck.system.hashcat

/**
 * Selects the Hashcat compute backend by excluding the other supported engine
 * from native workload commands. Preference names are the enum constant names.
 */
enum class ComputeBackend
{
    OPENCL,
    VULKAN
}
