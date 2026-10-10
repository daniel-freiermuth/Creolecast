package com.creolecast.app

/**
 * Maps resolved mDNS/DNS-SD services to [Server] entries and decides how they are keyed
 * and merged in [DiscoveryManager]'s server map.
 */
internal object MdnsServers {
    private const val AIRPLAY = "AirPlay"
    private const val AIRPLAY_2 = "AirPlay2"
    private const val DEFAULT_VERSION = "1.0"
    private const val DEFAULT_SAMPLE_RATE = 48000
    private const val DEFAULT_CHANNELS = 2

    /** Builds the [Server] a resolved service describes from its type, name and TXT record. */
    fun fromResolvedService(
        serviceType: String,
        serviceName: String,
        hostAddress: String,
        port: Int,
        attributes: Map<String, ByteArray?>
    ): Server {
        val txt = TxtRecord(attributes)
        val identity = identify(serviceType, serviceName, txt)
        val extraParts = identity.castFields + encodePresent(
            listOf(
                "model" to identity.model,
                "id" to identity.deviceId,
                "features" to txt["features"],
                "pk" to txt["pk"]
            )
        )
        return Server(
            // Ensure name is never empty
            name = identity.name.ifBlank { serviceName },
            host = hostAddress,
            port = port,
            version = txt["version"] ?: txt["srcvers"] ?: DEFAULT_VERSION,
            codecs = txt["codecs"]?.split(",") ?: listOf("pcm"),
            sampleRate = txt.int("sr") ?: txt.int("samplerate") ?: DEFAULT_SAMPLE_RATE,
            channels = txt.int("ch") ?: txt.int("channels") ?: DEFAULT_CHANNELS,
            platform = identity.platform,
            extra = if (extraParts.isEmpty()) null else extraParts.joinToString(";")
        )
    }

    /** AirPlay and AirPlay 2 entries for one device coexist, so their key names the platform. */
    fun storageKey(baseKey: String, platform: String?): String =
        platform?.takeIf(::isAirPlay)?.let { "$baseKey::$it" } ?: baseKey

    /** The key a service that has disappeared was stored under. */
    fun lostServiceKey(serviceType: String, serviceName: String): String {
        val isRaop = serviceType.contains("_raop")
        val baseName = if (isRaop && serviceName.contains("@")) {
            serviceName.substringAfter("@")
        } else {
            serviceName
        }
        val platform = when {
            isRaop -> AIRPLAY
            serviceType.contains("_airplay") -> AIRPLAY_2
            else -> null
        }
        return storageKey(baseName, platform)
    }

    /**
     * The entry to store for [candidate] when [existing] is already stored under its key.
     * Re-resolved AirPlay/AirPlay 2 entries merge their TXT fields: an _raop resolve
     * ([isRaop]) takes the new entry, any other keeps the existing one and only adopts a
     * non-default version. Everything else is simply replaced.
     */
    fun merged(existing: Server?, candidate: Server, isRaop: Boolean): Server {
        if (existing == null ||
            existing.platform != candidate.platform ||
            !isAirPlay(candidate.platform)
        ) {
            return candidate
        }
        val extra = mergeExtras(existing.extra, candidate.extra)
        return when {
            isRaop -> candidate.copy(extra = extra)

            candidate.version != DEFAULT_VERSION ->
                existing.copy(extra = extra, version = candidate.version)

            else -> existing.copy(extra = extra)
        }
    }

    private fun mergeExtras(old: String?, new: String?): String? = when {
        old == null -> new
        new == null -> old
        else -> ExtraFields.join((ExtraFields.parse(old) + ExtraFields.parse(new)).toList())
    }

    private fun isAirPlay(platform: String?): Boolean = platform == AIRPLAY || platform == AIRPLAY_2

    private fun identify(serviceType: String, serviceName: String, txt: TxtRecord): Identity {
        val advertised = Identity(
            name = serviceName,
            platform = txt["platform"],
            model = txt["model"] ?: txt["am"],
            deviceId = txt["deviceid"]
        )
        return when {
            serviceType.contains("_raop") -> raopIdentity(serviceName, advertised)

            serviceType.contains("_airplay") -> advertised.copy(platform = AIRPLAY_2)

            serviceType.contains("_googlecast") -> advertised.copy(
                platform = "Google Cast",
                name = txt["fn"] ?: serviceName,
                model = txt["md"],
                castFields = encodePresent(listOf("st", "ca", "ve").map { it to txt[it] })
            )

            serviceType.contains("_audiocast") -> advertised.copy(platform = "AriaCast")

            serviceType.contains("_snapcast") -> advertised.copy(platform = "Snapcast")

            else -> advertised
        }
    }

    /** RAOP instance names are "<device id>@<friendly name>". */
    private fun raopIdentity(serviceName: String, advertised: Identity): Identity {
        if (!serviceName.contains("@")) return advertised.copy(platform = AIRPLAY)
        return advertised.copy(
            platform = AIRPLAY,
            name = serviceName.substringAfter("@").ifEmpty { serviceName },
            deviceId = advertised.deviceId ?: serviceName.substringBefore("@")
        )
    }

    private fun encodePresent(fields: List<Pair<String, String?>>): List<String> =
        fields.mapNotNull { (key, value) -> value?.let { ExtraFields.encode(key, it) } }

    private data class Identity(
        val name: String,
        val platform: String?,
        val model: String?,
        val deviceId: String?,
        // Google Cast TXT fields, already encoded for Server.extra.
        val castFields: List<String> = emptyList()
    )

    private class TxtRecord(private val attributes: Map<String, ByteArray?>) {
        /** The trimmed UTF-8 value of [key], or null if it is absent or blank. */
        operator fun get(key: String): String? =
            attributes[key]?.let { String(it, Charsets.UTF_8).trim() }?.ifEmpty { null }

        fun int(key: String): Int? = get(key)?.toIntOrNull()
    }
}
