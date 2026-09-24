package com.pocketide.vault

import java.util.Locale

/**
 * Bech32 (BIP 173) the way age uses it: no length limit on the data part, and the checksum is
 * always computed over the lower-case string, so a string may be all upper or all lower case.
 */
internal object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val CHECKSUM_LENGTH = 6
    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    class Decoded(val hrp: String, val data: ByteArray)

    /** Encodes in lower case; age upper-cases identities after encoding. */
    fun encode(hrp: String, data: ByteArray): String {
        val prefix = hrp.lowercase(Locale.ROOT)
        require(prefix.isNotEmpty() && prefix.all { it.code in 33..126 }) { "Invalid Bech32 prefix" }
        val values = convertBits(data.map { it.toInt() and 0xff }, from = 8, to = 5, pad = true)
            ?: error("8-to-5 bit conversion with padding cannot fail")
        return buildString(prefix.length + 1 + values.size + CHECKSUM_LENGTH) {
            append(prefix).append('1')
            (values + checksum(prefix, values)).forEach { append(CHARSET[it]) }
        }
    }

    /** Returns the prefix in the string's own case and the data, or null when [text] is not valid Bech32. */
    fun decode(text: String): Decoded? {
        if (text.any { it.code !in 33..126 }) return null
        val lower = text.lowercase(Locale.ROOT)
        if (text != lower && text != text.uppercase(Locale.ROOT)) return null
        val separator = text.lastIndexOf('1')
        if (separator < 1 || separator + 1 + CHECKSUM_LENGTH > text.length) return null
        val values = lower.substring(separator + 1).map { c -> CHARSET.indexOf(c).takeIf { it >= 0 } ?: return null }
        if (polymod(expand(lower.substring(0, separator)) + values) != 1) return null
        val data = convertBits(values.dropLast(CHECKSUM_LENGTH), from = 5, to = 8, pad = false) ?: return null
        return Decoded(text.substring(0, separator), ByteArray(data.size) { data[it].toByte() })
    }

    private fun checksum(hrp: String, values: List<Int>): List<Int> {
        val mod = polymod(expand(hrp) + values + List(CHECKSUM_LENGTH) { 0 }) xor 1
        return List(CHECKSUM_LENGTH) { (mod shr (5 * (CHECKSUM_LENGTH - 1 - it))) and 31 }
    }

    private fun expand(hrp: String): List<Int> = hrp.map { it.code shr 5 } + 0 + hrp.map { it.code and 31 }

    private fun polymod(values: List<Int>): Int {
        var chk = 1
        for (value in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor value
            for (i in GENERATOR.indices) if ((top shr i) and 1 == 1) chk = chk xor GENERATOR[i]
        }
        return chk
    }

    /** Regroups bits; without padding it rejects leftover bits that are too many or not zero. */
    private fun convertBits(data: List<Int>, from: Int, to: Int, pad: Boolean): List<Int>? {
        var acc = 0
        var bits = 0
        val max = (1 shl to) - 1
        val out = ArrayList<Int>(data.size * from / to + 1)
        for (value in data) {
            if (value shr from != 0) return null
            acc = (acc shl from) or value
            bits += from
            while (bits >= to) {
                bits -= to
                out += (acc shr bits) and max
            }
            acc = acc and ((1 shl bits) - 1)
        }
        if (pad) {
            if (bits > 0) out += (acc shl (to - bits)) and max
        } else if (bits >= from || (acc shl (to - bits)) and max != 0) {
            return null
        }
        return out
    }
}
