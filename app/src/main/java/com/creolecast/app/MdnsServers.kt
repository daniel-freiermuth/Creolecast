package com.creolecast.app

/**
 * Maps resolved mDNS/DNS-SD services to [Server] entries and decides how they are keyed
 * and merged in [DiscoveryManager]'s server map.
 */
internal object MdnsServers {
    private const val DEFAULT_VERSION = "1.0"
    private const val DEFAULT_SAMPLE_RATE = 48000
    private const val DEFAULT_CHANNELS = 2

    /**
     * Builds the [Server] a resolved service describes from its type, name and TXT record,
     * or returns null for a service type that is not one of the subscribed receiver types.
     */
    fun fromResolvedService(
        serviceType: String,
        serviceName: String,
        hostAddress: String,
        port: Int,
        attributes: Map<String, ByteArray?>
    ): Server? {
        val txt = TxtRecord(attributes)
        val identity = identify(serviceType, serviceName, txt) ?: return null
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
    fun storageKey(baseKey: String, platform: CastPlatform?): String =
        platform?.takeIf(::isAirPlay)?.let { "$baseKey::${it.label}" } ?: baseKey

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

    private fun isAirPlay(platform: CastPlatform): Boolean =
        platform == CastPlatform.AIRPLAY || platform == CastPlatform.AIRPLAY2

    private fun identify(serviceType: String, serviceName: String, txt: TxtRecord): Identity? {
        val platform = platformOf(serviceType) ?: return null
        val advertised = Identity(
            name = serviceName,
            platform = platform,
            model = txt["model"] ?: txt["am"],
            deviceId = txt["deviceid"]
        )
        return when (platform) {
            CastPlatform.AIRPLAY -> raopIdentity(serviceName, advertised)

            CastPlatform.GOOGLE_CAST -> advertised.copy(
                name = txt["fn"] ?: serviceName,
                model = txt["md"],
                castFields = encodePresent(listOf("st", "ca", "ve").map { it to txt[it] })
            )

            CastPlatform.AIRPLAY2, CastPlatform.ARIACAST, CastPlatform.SNAPCAST,
            CastPlatform.DLNA, CastPlatform.MANUAL -> advertised
        }
    }

    /** The platform a subscribed service type speaks, or null for any other type. */
    private fun platformOf(serviceType: String): CastPlatform? = when {
        serviceType.contains("_raop") -> CastPlatform.AIRPLAY
        serviceType.contains("_airplay") -> CastPlatform.AIRPLAY2
        serviceType.contains("_googlecast") -> CastPlatform.GOOGLE_CAST
        serviceType.contains("_audiocast") -> CastPlatform.ARIACAST
        serviceType.contains("_snapcast") -> CastPlatform.SNAPCAST
        else -> null
    }

    /** RAOP instance names are "<device id>@<friendly name>". */
    private fun raopIdentity(serviceName: String, advertised: Identity): Identity {
        if (!serviceName.contains("@")) return advertised
        return advertised.copy(
            name = serviceName.substringAfter("@").ifEmpty { serviceName },
            deviceId = advertised.deviceId ?: serviceName.substringBefore("@")
        )
    }

    private fun encodePresent(fields: List<Pair<String, String?>>): List<String> =
        fields.mapNotNull { (key, value) -> value?.let { ExtraFields.encode(key, it) } }

    private data class Identity(
        val name: String,
        val platform: CastPlatform,
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

/**
 * The [DiscoveryManager] key each resolved mDNS instance was stored under. A lost service
 * carries neither its TXT record nor its host, so the key - the Google Cast "fn" name,
 * "name@host" for a disambiguated entry, the AirPlay platform suffix - cannot be derived
 * again from the instance name alone.
 */
internal class ResolvedServiceKeys {
    private val keys = mutableMapOf<String, String>()

    fun record(serviceType: String, serviceName: String, key: String) {
        keys[instanceId(serviceType, serviceName)] = key
    }

    /** The key [serviceName] was stored under, or null if it was never resolved. */
    fun forget(serviceType: String, serviceName: String): String? =
        keys.remove(instanceId(serviceType, serviceName))

    fun clear() = keys.clear()

    // Found/lost and resolved NsdServiceInfo do not agree on the type's surrounding dots
    // ("_googlecast._tcp." vs "._googlecast._tcp").
    private fun instanceId(serviceType: String, serviceName: String) =
        "${serviceType.trim('.')}/$serviceName"
}
