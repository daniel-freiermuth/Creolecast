package com.creolecast.app.airplay2

/**
 * The IEEE 1588-2008 / 802.1AS messages [PtpMaster] sends: two-step Sync, the
 * Follow_Up carrying its origin timestamp, and Announce.
 */
internal class PtpMessages(private val clockId: Long) {

    private companion object {
        const val TRANSPORT_SPECIFIC = 0x10 // 802.1AS
        const val PTP_VERSION = 2

        /** messageType values. */
        const val MSG_SYNC = 0x0
        const val MSG_FOLLOW_UP = 0x8
        const val MSG_ANNOUNCE = 0xB

        /** flagField bits. */
        const val FLAG_TWO_STEP = 0x0200
        const val FLAG_PTP_TIMESCALE = 0x0008

        /** controlField values (Sync is 0). */
        const val CONTROL_SYNC = 0
        const val CONTROL_FOLLOW_UP = 2
        const val CONTROL_OTHER = 5

        /** logMessageInterval: Sync/Follow_Up every 2^-3 s, Announce every 2^-2 s. */
        const val LOG_SYNC_INTERVAL = -3
        const val LOG_ANNOUNCE_INTERVAL = -2

        /** Common header (IEEE 1588 13.3). */
        const val HEADER_SIZE = 34
        const val MESSAGE_LENGTH_OFFSET = 2
        const val FLAGS_OFFSET = 6
        const val SOURCE_CLOCK_ID_OFFSET = 20
        const val SOURCE_PORT_NUMBER_OFFSET = 28
        const val SEQUENCE_ID_OFFSET = 30
        const val CONTROL_OFFSET = 32
        const val LOG_INTERVAL_OFFSET = 33
        const val SOURCE_PORT_NUMBER = 1
        const val SEQUENCE_ID_MASK = 0xFFFF

        /** 48-bit seconds + 32-bit nanoseconds. */
        const val TIMESTAMP_SIZE = 10
        const val TIMESTAMP_SECONDS_SIZE = 6
        const val NANOS_PER_SECOND = 1_000_000_000L

        /** 802.1AS Follow_Up information TLV (11.4.4.3), 32 bytes in all. */
        const val TLV_ORGANIZATION_EXTENSION = 0x0003
        const val FOLLOW_UP_TLV_SIZE = 32
        const val FOLLOW_UP_TLV_LENGTH = 28
        const val TLV_LENGTH_OFFSET = 2
        const val TLV_ORGANIZATION_ID_OFFSET = 4
        const val TLV_ORGANIZATION_SUBTYPE_OFFSET = 7
        const val IEEE_802_1_OUI = 0x0080C2L
        const val FOLLOW_UP_INFORMATION_SUBTYPE = 1L
        const val OUI_SIZE = 3

        /** Announce body (IEEE 1588 13.5), after a zero originTimestamp. */
        const val ANNOUNCE_BODY_SIZE = 30
        const val CURRENT_UTC_OFFSET_OFFSET = 10
        const val PRIORITY1_OFFSET = 13
        const val CLOCK_QUALITY_OFFSET = 14
        const val PRIORITY2_OFFSET = 18
        const val GRANDMASTER_IDENTITY_OFFSET = 19
        const val STEPS_REMOVED_OFFSET = 27
        const val TIME_SOURCE_OFFSET = 29
        const val CURRENT_UTC_OFFSET_S = 37
        const val GRANDMASTER_PRIORITY = 248

        /** clockClass 248, accuracy unknown (0xFE), offsetScaledLogVariance 0x436A. */
        const val GRANDMASTER_CLOCK_QUALITY = 0xF8FE436AL
        const val TIME_SOURCE_INTERNAL_OSCILLATOR = 0xA0

        const val SYNC_LENGTH = HEADER_SIZE + TIMESTAMP_SIZE
        const val FOLLOW_UP_LENGTH = HEADER_SIZE + TIMESTAMP_SIZE + FOLLOW_UP_TLV_SIZE
        const val ANNOUNCE_LENGTH = HEADER_SIZE + ANNOUNCE_BODY_SIZE
    }

    /** messageType, length, flagField, controlField and logMessageInterval per message. */
    private enum class Type(
        val id: Int,
        val length: Int,
        val flags: Int,
        val control: Int,
        val logInterval: Int
    ) {
        SYNC(
            MSG_SYNC,
            SYNC_LENGTH,
            FLAG_TWO_STEP or FLAG_PTP_TIMESCALE,
            CONTROL_SYNC,
            LOG_SYNC_INTERVAL
        ),
        FOLLOW_UP(
            MSG_FOLLOW_UP,
            FOLLOW_UP_LENGTH,
            FLAG_PTP_TIMESCALE,
            CONTROL_FOLLOW_UP,
            LOG_SYNC_INTERVAL
        ),
        ANNOUNCE(
            MSG_ANNOUNCE,
            ANNOUNCE_LENGTH,
            FLAG_PTP_TIMESCALE,
            CONTROL_OTHER,
            LOG_ANNOUNCE_INTERVAL
        )
    }

    /** twoStepFlag | ptpTimescale; the origin timestamp follows in Follow_Up. */
    fun sync(sequence: Int): ByteArray = message(Type.SYNC, sequence)

    fun followUp(sequence: Int, originNs: Long): ByteArray =
        message(Type.FOLLOW_UP, sequence).also {
            putTimestamp(it, HEADER_SIZE, originNs)
            // Organization extension, 00-80-C2 subtype 1, rate/phase-change
            // fields all zero.
            val tlv = HEADER_SIZE + TIMESTAMP_SIZE
            WireBytes.putUInt16(it, tlv, TLV_ORGANIZATION_EXTENSION)
            WireBytes.putUInt16(it, tlv + TLV_LENGTH_OFFSET, FOLLOW_UP_TLV_LENGTH)
            WireBytes.putBigEndian(it, tlv + TLV_ORGANIZATION_ID_OFFSET, IEEE_802_1_OUI, OUI_SIZE)
            WireBytes.putBigEndian(
                it,
                tlv + TLV_ORGANIZATION_SUBTYPE_OFFSET,
                FOLLOW_UP_INFORMATION_SUBTYPE,
                OUI_SIZE
            )
        }

    fun announce(sequence: Int): ByteArray = message(Type.ANNOUNCE, sequence).also {
        val body = HEADER_SIZE
        // originTimestamp (10 bytes) left zero
        WireBytes.putUInt16(it, body + CURRENT_UTC_OFFSET_OFFSET, CURRENT_UTC_OFFSET_S)
        it[body + PRIORITY1_OFFSET] = GRANDMASTER_PRIORITY.toByte()
        WireBytes.putUInt32(it, body + CLOCK_QUALITY_OFFSET, GRANDMASTER_CLOCK_QUALITY)
        it[body + PRIORITY2_OFFSET] = GRANDMASTER_PRIORITY.toByte()
        WireBytes.putUInt64(it, body + GRANDMASTER_IDENTITY_OFFSET, clockId)
        WireBytes.putUInt16(it, body + STEPS_REMOVED_OFFSET, 0)
        it[body + TIME_SOURCE_OFFSET] = TIME_SOURCE_INTERNAL_OSCILLATOR.toByte()
    }

    private fun message(type: Type, sequence: Int): ByteArray {
        val message = ByteArray(type.length)
        message[0] = (TRANSPORT_SPECIFIC or type.id).toByte()
        message[1] = PTP_VERSION.toByte()
        WireBytes.putUInt16(message, MESSAGE_LENGTH_OFFSET, message.size)
        // domain 0, reserved, correctionField 0, reserved: already zero
        WireBytes.putUInt16(message, FLAGS_OFFSET, type.flags)
        WireBytes.putUInt64(message, SOURCE_CLOCK_ID_OFFSET, clockId)
        WireBytes.putUInt16(message, SOURCE_PORT_NUMBER_OFFSET, SOURCE_PORT_NUMBER)
        WireBytes.putUInt16(message, SEQUENCE_ID_OFFSET, sequence and SEQUENCE_ID_MASK)
        message[CONTROL_OFFSET] = type.control.toByte()
        message[LOG_INTERVAL_OFFSET] = type.logInterval.toByte()
        return message
    }

    /** 48-bit seconds + 32-bit nanoseconds. */
    private fun putTimestamp(buf: ByteArray, off: Int, nanos: Long) {
        WireBytes.putBigEndian(buf, off, nanos / NANOS_PER_SECOND, TIMESTAMP_SECONDS_SIZE)
        WireBytes.putUInt32(buf, off + TIMESTAMP_SECONDS_SIZE, nanos % NANOS_PER_SECOND)
    }
}
