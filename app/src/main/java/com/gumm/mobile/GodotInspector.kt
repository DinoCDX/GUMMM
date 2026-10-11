package com.gumm.mobile

import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object GodotInspector {
    data class Info(val isGodot: Boolean, val version: String)

    private val versionRegex =
        Regex("""(\d)\.(\d{1,2})(?:\.(\d{1,2}))?\.(?:stable|beta\d*|rc\d*|dev\d*|alpha\d*)\.[a-z_]+""")

    fun looksLikeGodot(apk: File): Boolean = try {
        ZipFile(apk).use { z -> z.entries().asSequence().any { it.name.endsWith("libgodot_android.so") } }
    } catch (e: Exception) {
        false
    }

    fun inspect(apk: File): Info {
        return try {
            ZipFile(apk).use { z ->
                val libs = z.entries().asSequence()
                    .filter { it.name.startsWith("lib/") && it.name.endsWith("libgodot_android.so") }
                    .toList()
                if (libs.isEmpty()) {
                    Info(false, "")
                } else {
                    val pick = libs.firstOrNull { it.name.contains("arm64") } ?: libs.first()
                    Info(true, scanVersion(z, pick))
                }
            }
        } catch (e: Exception) {
            Info(false, "")
        }
    }

    private fun scanVersion(z: ZipFile, e: ZipEntry): String {
        z.getInputStream(e).use { s ->
            val chunk = ByteArray(1 shl 20)
            var carry = ""
            while (true) {
                val n = s.read(chunk)
                if (n <= 0) break
                val text = carry + String(chunk, 0, n, Charsets.ISO_8859_1)
                val m = versionRegex.find(text)
                if (m != null) {
                    val g = m.groupValues
                    return if (g[3].isNotEmpty()) "${g[1]}.${g[2]}.${g[3]}" else "${g[1]}.${g[2]}"
                }
                carry = text.takeLast(96)
            }
        }
        return ""
    }

    fun detectMainScene(apk: File): String? {
        return try {
            ZipFile(apk).use { z -> findMainScene(z) }
        } catch (e: Exception) {
            null
        }
    }

    private fun findMainScene(z: ZipFile): String? {
        val entries = z.entries().asSequence().toList()
        val loose = entries.firstOrNull { it.name.endsWith("project.binary") }
        if (loose != null) {
            val bytes = z.getInputStream(loose).use { it.readBytes() }
            val r = mainFromBinary(bytes)
            if (r != null) return r
        }
        val packs = entries.filter { it.name.endsWith(".pck") }.sortedByDescending { it.size }
        for (p in packs) {
            val r = fromPck(z, p)
            if (r != null) return r
        }
        return null
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun le64(b: ByteArray, o: Int): Long =
        (le32(b, o).toLong() and 0xFFFFFFFFL) or (le32(b, o + 4).toLong() shl 32)

    private fun mainFromBinary(b: ByteArray): String? {
        if (b.size < 8 || b[0] != 'E'.code.toByte() || b[1] != 'C'.code.toByte() ||
            b[2] != 'F'.code.toByte() || b[3] != 'G'.code.toByte()
        ) return null
        var p = 4
        val count = le32(b, p)
        p += 4
        for (i in 0 until count) {
            val kl = le32(b, p)
            p += 4
            val key = String(b, p, kl, Charsets.UTF_8)
            p += kl
            val vl = le32(b, p)
            p += 4
            if (key == "application/run/main_scene") {
                val type = le32(b, p) and 0xFFFF
                if (type == 4) {
                    val sl = le32(b, p + 4)
                    return String(b, p + 8, sl, Charsets.UTF_8)
                }
                return null
            }
            p += vl
        }
        return null
    }

    private fun skipFully(s: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val k = s.skip(left)
            if (k <= 0) {
                if (s.read() < 0) throw EOFException()
                left--
            } else {
                left -= k
            }
        }
    }

    private fun readN(s: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var got = 0
        while (got < n) {
            val k = s.read(b, got, n - got)
            if (k < 0) throw EOFException()
            got += k
        }
        return b
    }

    private fun readAt(z: ZipFile, e: ZipEntry, pos: Long, len: Int): ByteArray =
        z.getInputStream(e).use { skipFully(it, pos); readN(it, len) }

    private fun fromPck(z: ZipFile, e: ZipEntry): String? {
        val head = readAt(z, e, 0, 48)
        if (le32(head, 0) != 0x43504447) return null
        val ver = le32(head, 4)
        var flags = 0
        var fileBase = 0L
        var dirStart = 84L
        if (ver >= 3) {
            flags = le32(head, 20)
            fileBase = le64(head, 24)
            dirStart = le64(head, 32)
        } else if (ver == 2) {
            flags = le32(head, 20)
            fileBase = le64(head, 24)
            dirStart = 96L
        }
        if ((flags and 1) != 0) return null
        var foundOffset = -1L
        var foundSize = 0L
        var foundFlags = 0
        z.getInputStream(e).use { s ->
            skipFully(s, dirStart)
            val count = le32(readN(s, 4), 0)
            if (count < 0 || count > 5_000_000) return null
            for (i in 0 until count) {
                val pl = le32(readN(s, 4), 0)
                if (pl < 0 || pl > 4096) return null
                val path = String(readN(s, pl), Charsets.UTF_8).trimEnd('\u0000')
                val off = le64(readN(s, 8), 0)
                val size = le64(readN(s, 8), 0)
                skipFully(s, 16)
                val fl = if (ver >= 2) le32(readN(s, 4), 0) else 0
                if (path.endsWith("project.binary")) {
                    foundOffset = off
                    foundSize = size
                    foundFlags = fl
                    break
                }
            }
        }
        if (foundOffset < 0 || (foundFlags and 1) != 0 || foundSize <= 0 || foundSize > 4_000_000) return null
        val abs = if (ver >= 2) fileBase + foundOffset else foundOffset
        return mainFromBinary(readAt(z, e, abs, foundSize.toInt()))
    }
}
