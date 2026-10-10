package com.creolecast.app.airplay2

import android.util.Base64
import android.util.Log
import java.net.HttpURLConnection

/**
 * What the receiver told us about itself in GET /info (and its mDNS TXT key),
 * which pairing and capability negotiation work against.
 */
internal class AirPlay2ReceiverInfo(host: String, txtPk: ByteArray?) {

    private companion object {
        const val TAG = "AirPlay2Client"
        const val HEX_RADIX = 16
        const val UINT32_MASK = 0xFFFFFFFFL

        /** PTP needs SourceVersion >= 354.54.6 (compatibility.go). */
        val PTP_MIN_SOURCE_VERSION = listOf(354, 54, 6)

        /** 377.40.x advertises PTP but is known not to work with it. */
        const val PTP_BROKEN_MAJOR = 377
        const val PTP_BROKEN_MINOR = 40

        /** /info is a binary plist; some receivers answer with an XML one instead. */
        fun decodeInfo(body: ByteArray): Map<String, Any?> = try {
            BinaryPlist.decode(body)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Binary plist parse failed", e)
            parseXmlPlist(body.toString(Charsets.UTF_8))
        }

        fun parseXmlPlist(xml: String): Map<String, Any?> {
            val result = mutableMapOf<String, Any?>()
            val statusRegex =
                Regex(
                    "<key>statusFlags</key>\\s*<integer>(\\d+)</integer>",
                    RegexOption.DOT_MATCHES_ALL
                )
            val pkRegex =
                Regex(
                    "<key>pk</key>\\s*<data>\\s*([A-Za-z0-9+/=]+)\\s*</data>",
                    RegexOption.DOT_MATCHES_ALL
                )
            val deviceRegex =
                Regex(
                    "<key>deviceID</key>\\s*<string>([^<]*)</string>",
                    RegexOption.DOT_MATCHES_ALL
                )
            val nameRegex =
                Regex("<key>name</key>\\s*<string>([^<]*)</string>", RegexOption.DOT_MATCHES_ALL)
            statusRegex.find(xml)?.let { result["statusFlags"] = it.groupValues[1].toLong() }
            pkRegex.find(xml)?.let {
                result["pk"] = it.groupValues[1].replace(Regex("\\s+"), "")
            }
            deviceRegex.find(xml)?.let { result["deviceID"] = it.groupValues[1] }
            nameRegex.find(xml)?.let { result["name"] = it.groupValues[1] }
            return result
        }

        /** The `pk` entry: raw bytes in a binary plist, base64 text in the XML fallback. */
        fun decodePublicKey(pkRaw: Any?): ByteArray? = when (pkRaw) {
            is ByteArray -> pkRaw

            is String -> try {
                Base64.decode(pkRaw, Base64.NO_WRAP)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Ignoring /info pk that is not valid base64", e)
                null
            }

            else -> null
        }

        fun parseFeatures(value: Any?): Long = when (value) {
            is Long -> value
            is Int -> value.toLong()
            is String -> parseFeatureString(value)
            else -> 0L
        }

        /** mDNS-style feature strings are either "0x1234" or "0xLOW,0xHIGH". */
        fun parseFeatureString(text: String): Long {
            val parts = text.split(",")
            fun hex(s: String): Long =
                s.trim().removePrefix("0x").removePrefix("0X").toLongOrNull(HEX_RADIX) ?: 0L
            return when (parts.size) {
                0 -> 0L
                1 -> hex(parts[0])
                else -> (hex(parts[1]) shl Int.SIZE_BITS) or (hex(parts[0]) and UINT32_MASK)
            }
        }

        /** Version components compared in order; missing ones count as 0. */
        fun isAtLeast(version: List<Int>, minimum: List<Int>): Boolean {
            val firstDifference = minimum.indices
                .map { version.getOrElse(it) { 0 }.compareTo(minimum[it]) }
                .firstOrNull { it != 0 }
            return firstDifference == null || firstDifference > 0
        }
    }

    /** Receiver identity from /info; the key our pairing credentials are stored under. */
    var deviceId: String = host
        private set

    /** Long-term Ed25519 key that authenticates the receiver in pair-verify, if known. */
    var publicKey: ByteArray? = txtPk
        private set

    private var statusFlags = 0L
    private var features = 0L
    private var sourceVersion = ""

    /** GET /info and cache everything later steps negotiate against. */
    fun refresh(control: AirPlay2ControlChannel): Boolean {
        val resp = control.request("GET", "/info", null, null)
        val body = resp.body?.takeIf { resp.code == HttpURLConnection.HTTP_OK }
        body?.let { update(decodeInfo(it)) }
        return body != null
    }

    fun hasFeature(bit: Int): Boolean = (features shr bit) and 1L == 1L

    /**
     * PTP needs SourceVersion >= 354.54.6, and 377.40.x is a known-bad
     * advertisement (compatibility.go supportsPTPSourceVersion).
     */
    fun supportsPtpSourceVersion(): Boolean {
        val parts = sourceVersion.split(".").map { it.toIntOrNull() }
        val numbers = parts.filterNotNull()
        val wellFormed = sourceVersion.isNotEmpty() && numbers.size == parts.size
        val knownBad = numbers.size >= 2 &&
            numbers[0] == PTP_BROKEN_MAJOR &&
            numbers[1] == PTP_BROKEN_MINOR
        return wellFormed && !knownBad && isAtLeast(numbers, PTP_MIN_SOURCE_VERSION)
    }

    private fun update(info: Map<String, Any?>) {
        statusFlags = info["statusFlags"] as? Long ?: 0L
        (info["deviceID"] as? String)?.let { if (it.isNotEmpty()) deviceId = it }
        if (publicKey == null) publicKey = decodePublicKey(info["pk"])
        features = parseFeatures(info["features"])
        sourceVersion = (info["sourceVersion"] as? String).orEmpty()
        Log.d(
            TAG,
            "Receiver $deviceId flags=$statusFlags " +
                "features=0x${features.toString(HEX_RADIX)} src=$sourceVersion " +
                "hasPk=${publicKey != null}"
        )
    }
}
