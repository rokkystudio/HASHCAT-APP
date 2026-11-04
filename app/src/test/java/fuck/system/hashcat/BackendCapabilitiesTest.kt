package fuck.system.hashcat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies strict, order-independent schema-1 backend capability parsing.
 */
class BackendCapabilitiesTest
{
    /**
     * Reads a JSON contract fixture packaged with the unit tests.
     */
    private fun fixture(name: String): String
    {
        return requireNotNull(javaClass.classLoader?.getResourceAsStream("backend-capabilities/$name"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /**
     * Resolves available Vulkan by its ID regardless of the backend list order.
     */
    @Test
    fun parsesAvailableVulkan()
    {
        val capabilities = requireNotNull(BackendCapabilities.parse(fixture("available.json")))
        assertEquals(1, capabilities.schema)
        assertEquals("v7.1.2", capabilities.hashcatVersion)
        assertTrue(capabilities.vulkanSchedulable)
        assertEquals("Vulkan GPU", capabilities.backend("vulkan")?.devices?.first()?.name)
        assertEquals(2147483648L, capabilities.backend("opencl")?.devices?.first()?.vendorId)
        assertNull(capabilities.backend("unknown"))
    }

    /**
     * Reads a detected Vulkan backend that cannot schedule workloads.
     */
    @Test
    fun detectedVulkanCanBeUnschedulable()
    {
        val capabilities = requireNotNull(BackendCapabilities.parse(fixture("unschedulable.json")))
        assertTrue(capabilities.backend("vulkan")?.detected == true)
        assertFalse(capabilities.vulkanSchedulable)
    }

    /**
     * Treats omitted modes as an optional reserved contract field.
     */
    @Test
    fun acceptsMissingModes()
    {
        assertTrue(BackendCapabilities.parse(fixture("without-modes.json")) != null)
    }

    /**
     * Rejects incompatible versions without throwing.
     */
    @Test
    fun rejectsSchemaTwo()
    {
        assertNull(BackendCapabilities.parse(fixture("schema-two.json")))
    }

    /**
     * Rejects malformed JSON and empty or null inputs without throwing.
     */
    @Test
    fun rejectsMalformedJson()
    {
        assertNull(BackendCapabilities.parse(fixture("malformed.json")))
        assertNull(BackendCapabilities.parse(null))
        assertNull(BackendCapabilities.parse(""))
    }

    /**
     * Rejects backend descriptions without any backend entries.
     */
    @Test
    fun rejectsEmptyBackends()
    {
        assertNull(BackendCapabilities.parse(fixture("empty-backends.json")))
    }

    /**
     * Rejects non-boolean scheduling values rather than coercing strings.
     */
    @Test
    fun rejectsIncorrectTypes()
    {
        assertNull(BackendCapabilities.parse(fixture("invalid-types.json")))
    }
}
