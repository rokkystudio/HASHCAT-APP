package fuck.system.hashcat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies package-timestamp cache reuse and negative-result caching.
 */
class BackendCapabilitiesCacheTest
{
    /**
     * Returns cached JSON without invoking the native reader for a matching stamp.
     */
    @Test
    fun matchingStampUsesCachedJson()
    {
        var reads = 0
        val result = BackendCapabilitiesCache.resolve("42", "42", "cached") {
            reads++
            "new"
        }

        assertFalse(result.refreshed)
        assertEquals("cached", result.json)
        assertEquals(0, reads)
    }

    /**
     * Requests the description and signals a preference update on a new APK.
     */
    @Test
    fun newPackageStampRefreshes()
    {
        var reads = 0
        val result = BackendCapabilitiesCache.resolve("43", "42", "cached") {
            reads++
            "updated"
        }

        assertTrue(result.refreshed)
        assertEquals("updated", result.json)
        assertEquals(1, reads)
    }

    /**
     * Preserves a failed result as an empty cached preference until the stamp changes.
     */
    @Test
    fun negativeResultIsCached()
    {
        var reads = 0
        val first = BackendCapabilitiesCache.resolve("44", null, null) {
            reads++
            null
        }
        assertTrue(first.refreshed)
        assertNull(first.json)

        val second = BackendCapabilitiesCache.resolve("44", "44", first.json.orEmpty()) {
            reads++
            "unexpected"
        }
        assertFalse(second.refreshed)
        assertEquals("", second.json)
        assertEquals(1, reads)
    }
}
