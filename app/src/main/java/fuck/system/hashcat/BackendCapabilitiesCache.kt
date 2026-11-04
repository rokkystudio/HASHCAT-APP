package fuck.system.hashcat

/**
 * Resolves a version-stamped native capabilities response without querying the
 * executable again while the installed package timestamp remains unchanged.
 */
object BackendCapabilitiesCache
{
    /**
     * Carries the cached or newly queried JSON and whether preferences must
     * persist this response, including a null response.
     */
    data class Result(
        val json: String?,
        val refreshed: Boolean
    )

    /**
     * Reuses the JSON when package stamps match. A missing or failed response
     * is cached as well, and the reader runs only for a different package stamp.
     */
    fun resolve(
        installedStamp: String,
        cachedStamp: String?,
        cachedJson: String?,
        readJson: () -> String?
    ): Result
    {
        if (installedStamp == cachedStamp)
        {
            return Result(cachedJson, false)
        }

        return Result(readJson(), true)
    }
}
