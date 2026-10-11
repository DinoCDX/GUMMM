package com.gumm.mobile

import java.io.ByteArrayOutputStream
import java.math.BigInteger

object Der {
    private fun length(n: Int): ByteArray = when {
        n < 128 -> byteArrayOf(n.toByte())
        n < 256 -> byteArrayOf(0x81.toByte(), n.toByte())
        n < 65536 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    }

    private fun concat(parts: Array<out ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    fun tlv(tag: Int, content: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + length(content.size) + content

    fun seq(vararg parts: ByteArray): ByteArray = tlv(0x30, concat(parts))

    fun set(vararg parts: ByteArray): ByteArray = tlv(0x31, concat(parts))

    fun integer(v: BigInteger): ByteArray = tlv(0x02, v.toByteArray())

    fun utf8(s: String): ByteArray = tlv(0x0C, s.toByteArray(Charsets.UTF_8))

    fun utcTime(s: String): ByteArray = tlv(0x17, s.toByteArray(Charsets.US_ASCII))

    fun bitString(b: ByteArray): ByteArray = tlv(0x03, byteArrayOf(0) + b)

    fun nul(): ByteArray = byteArrayOf(0x05, 0x00)

    fun oid(dotted: String): ByteArray {
        val p = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((p[0] * 40 + p[1]).toInt())
        for (i in 2 until p.size) {
            var v = p[i]
            val tmp = ArrayList<Int>()
            do {
                tmp.add((v and 0x7F).toInt())
                v = v shr 7
            } while (v > 0)
            tmp.reverse()
            for (j in tmp.indices) {
                out.write(if (j < tmp.size - 1) tmp[j] or 0x80 else tmp[j])
            }
        }
        return tlv(0x06, out.toByteArray())
    }
}
