package fuck.system.hashcat

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Describes the backends reported by the native --backend-info-json command.
 */
data class Capabilities(
    val schema: Int,
    val hashcatVersion: String,
    val backends: List<BackendInfo>
)
{
    /**
     * Finds a backend by its stable JSON identifier, independently of its position.
     */
    fun backend(id: String): BackendInfo?
    {
        return backends.firstOrNull { it.id == id }
    }

    val vulkanSchedulable: Boolean
        get() = backend("vulkan")?.schedulable == true
}

/**
 * Describes detection, scheduling support and devices of one compute backend.
 */
data class BackendInfo(
    val id: String,
    val detected: Boolean,
    val schedulable: Boolean,
    val devices: List<DeviceInfo>
)

/**
 * Describes a device identifier, display name, type, vendor and compute backend.
 */
data class DeviceInfo(
    val id: Int,
    val name: String,
    val type: String,
    val vendorId: Long,
    val backend: String
)

/**
 * Parses the versioned native capabilities contract without interpreting
 * human-readable Hashcat output.
 */
object BackendCapabilities
{
    /**
     * Returns a validated schema-1 description, or null for absent, malformed
     * or incompatible data. Additional fields and optional modes are ignored.
     */
    fun parse(json: String?): Capabilities?
    {
        if (json.isNullOrBlank())
        {
            return null
        }

        return try
        {
            val root = JsonParser.parseString(json).asJsonObject
            val schema = root.getRequiredInt("schema")

            if (schema != 1)
            {
                return null
            }

            val backendArray = root.getAsJsonArray("backends") ?: return null

            if (backendArray.size() == 0)
            {
                return null
            }

            val backends = ArrayList<BackendInfo>(backendArray.size())

            for (index in 0 until backendArray.size())
            {
                val item = backendArray[index].asJsonObject
                val devicesJson = item.getAsJsonArray("devices") ?: return null
                val devices = ArrayList<DeviceInfo>(devicesJson.size())

                for (deviceIndex in 0 until devicesJson.size())
                {
                    val device = devicesJson[deviceIndex].asJsonObject
                    devices += DeviceInfo(
                        id = device.getRequiredInt("id"),
                        name = device.getRequiredString("name"),
                        type = device.getRequiredString("type"),
                        vendorId = device.getRequiredLong("vendor_id"),
                        backend = device.getRequiredString("backend")
                    )
                }

                backends += BackendInfo(
                    id = item.getRequiredString("id"),
                    detected = item.getRequiredBoolean("detected"),
                    schedulable = item.getRequiredBoolean("schedulable"),
                    devices = devices
                )
            }

            if (backends.map { it.id }.toSet().size != backends.size)
            {
                return null
            }

            Capabilities(
                schema = schema,
                hashcatVersion = root.getRequiredString("hashcat_version"),
                backends = backends
            )
        }
        catch (_: Exception)
        {
            null
        }
    }

    /**
     * Returns the required JSON string, rejecting non-string primitives.
     */
    private fun JsonObject.getRequiredString(name: String): String
    {
        val value = get(name)?.asJsonPrimitive
            ?: throw IllegalArgumentException("Missing JSON string: $name")

        require(value.isString)

        return value.asString
    }

    /**
     * Returns the required integer without coercing decimal values or strings.
     */
    private fun JsonObject.getRequiredInt(name: String): Int
    {
        val value = get(name)?.asJsonPrimitive
            ?: throw IllegalArgumentException("Missing JSON integer: $name")

        require(value.isNumber)

        return value.asString.toInt()
    }

    /**
     * Returns the required integer vendor identifier, including unsigned IDs
     * that exceed the positive range of a signed 32-bit integer.
     */
    private fun JsonObject.getRequiredLong(name: String): Long
    {
        val value = get(name)?.asJsonPrimitive
            ?: throw IllegalArgumentException("Missing JSON integer: $name")

        require(value.isNumber)

        return value.asString.toLong()
    }

    /**
     * Returns the required JSON boolean without accepting quoted strings.
     */
    private fun JsonObject.getRequiredBoolean(name: String): Boolean
    {
        val value = get(name)?.asJsonPrimitive
            ?: throw IllegalArgumentException("Missing JSON boolean: $name")

        require(value.isBoolean)

        return value.asBoolean
    }
}
