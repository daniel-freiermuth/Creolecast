package com.creolecast.app

/**
 * Maps resolved mDNS/DNS-SD services to [Server] entries and decides how they are keyed
 * and merged in [DiscoveryManager]'s server map.
 */
internal object MdnsServers {
    private const val DEFAULT_VERSION = "1.0"
    private const val DEFAULT_SAMPLE_RATE = 48000
    private const val DEFAULT_CHANNELS = 2

    // RAOP receivers that omit sr still expect the AirPlay 1 rate.
    private const val RAOP_DEFAULT_SAMPLE_RATE = 44100

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
        val isRaop = serviceType.contains("_raop")
        val sampleRate = txt.int("sr") ?: txt.int("samplerate")
            ?: if (isRaop) RAOP_DEFAULT_SAMPLE_RATE else DEFAULT_SAMPLE_RATE
        // RAOP TXT values, stored as the JmDNS path did; RaopSession reads et back.
        val raopFields = if (isRaop) {
            listOf(
                "et" to encryptionTypes(txt),
                "sr" to sampleRate,
                "cn" to (txt.int("cn") ?: 0)
            ).map { (key, value) -> ExtraFields.encode(key, value.toString()) }
        } else {
            emptyList()
        }
        val extraParts = identity.castFields + raopFields + encodePresent(
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
            sampleRate = sampleRate,
            channels = txt.int("ch") ?: txt.int("channels") ?: DEFAULT_CHANNELS,
            platform = identity.platform,
            extra = if (extraParts.isEmpty()) null else extraParts.joinToString(";")
        )
    }

    /**
     * True for an _raop service this app cannot stream to. The RAOP sender implements only
     * RSA (et bit 0) or no encryption; et is a bit set, so any other non-zero value rules
     * both out. A comma list such as "0,3,5" does not parse and counts as "none", which
     * the session code then streams unencrypted.
     */
    fun isUnsupportedRaop(serviceType: String, attributes: Map<String, ByteArray?>): Boolean {
        if (!serviceType.contains("_raop")) return false
        val encryptionTypes = encryptionTypes(TxtRecord(attributes))
        return encryptionTypes != 0 && (encryptionTypes and 1) == 0
    }

    private fun encryptionTypes(txt: TxtRecord): Int = txt.int("et") ?: 0

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
