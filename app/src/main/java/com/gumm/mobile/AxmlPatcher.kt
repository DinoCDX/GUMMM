package com.gumm.mobile

import java.io.ByteArrayOutputStream

object AxmlPatcher {
    private const val FILE_TYPE = 0x0003
    private const val STRING_POOL = 0x0001
    private const val START_ELEMENT = 0x0102
    private const val END_ELEMENT = 0x0103
    private const val NONE = -1
    private const val TYPE_STRING = 0x03

    var status = ""

    private class Pool(val flags: Int, val strings: MutableList<String>, val utf8: Boolean, val end: Int)

    private fun ByteArray.u8(o: Int): Int = this[o].toInt() and 0xFF
    private fun ByteArray.u16(o: Int): Int = u8(o) or (u8(o + 1) shl 8)
    private fun ByteArray.u32(o: Int): Int = u8(o) or (u8(o + 1) shl 8) or (u8(o + 2) shl 16) or (u8(o + 3) shl 24)

    private fun ByteArray.set32(o: Int, v: Int) {
        this[o] = v.toByte()
        this[o + 1] = (v ushr 8).toByte()
        this[o + 2] = (v ushr 16).toByte()
        this[o + 3] = (v ushr 24).toByte()
    }

    private fun readString(d: ByteArray, start: Int, utf8: Boolean): String {
        var p = start
        if (utf8) {
            var chars = d.u8(p)
            p += 1
            if ((chars and 0x80) != 0) {
                chars = ((chars and 0x7F) shl 8) or d.u8(p)
                p += 1
            }
            var bytes = d.u8(p)
            p += 1
            if ((bytes and 0x80) != 0) {
                bytes = ((bytes and 0x7F) shl 8) or d.u8(p)
                p += 1
            }
            return String(d, p, bytes, Charsets.UTF_8)
        }
        var len = d.u16(p)
        p += 2
        if ((len and 0x8000) != 0) {
            len = ((len and 0x7FFF) shl 16) or d.u16(p)
            p += 2
        }
        return String(d, p, len * 2, Charsets.UTF_16LE)
    }

    private fun readPool(d: ByteArray, start: Int): Pool {
        val headerSize = d.u16(start + 2)
        val size = d.u32(start + 4)
        val count = d.u32(start + 8)
        val styleCount = d.u32(start + 12)
        val flags = d.u32(start + 16)
        val stringsStart = d.u32(start + 20)
        require(styleCount == 0) { "Unsupported styled string pool" }
        val utf8 = (flags and 0x100) != 0
        val list = ArrayList<String>(count)
        for (i in 0 until count) {
            val off = d.u32(start + headerSize + i * 4)
            list.add(readString(d, start + stringsStart + off, utf8))
        }
        return Pool(flags, list, utf8, start + size)
    }

    private fun put16(o: ByteArrayOutputStream, v: Int) {
        o.write(v and 0xFF)
        o.write((v ushr 8) and 0xFF)
    }

    private fun put32(o: ByteArrayOutputStream, v: Int) {
        put16(o, v and 0xFFFF)
        put16(o, (v ushr 16) and 0xFFFF)
    }

    private fun writeLen8(o: ByteArrayOutputStream, n: Int) {
        if (n > 0x7F) {
            o.write(((n shr 8) and 0x7F) or 0x80)
            o.write(n and 0xFF)
        } else {
            o.write(n)
        }
    }

    private fun writeLen16(o: ByteArrayOutputStream, n: Int) {
        if (n > 0x7FFF) {
            put16(o, ((n shr 16) and 0x7FFF) or 0x8000)
            put16(o, n and 0xFFFF)
        } else {
            put16(o, n)
        }
    }

    private fun writePool(pool: Pool): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = IntArray(pool.strings.size)
        for (i in pool.strings.indices) {
            val s = pool.strings[i]
            offsets[i] = data.size()
            if (pool.utf8) {
                val b = s.toByteArray(Charsets.UTF_8)
                writeLen8(data, s.length)
                writeLen8(data, b.size)
                data.write(b)
                data.write(0)
            } else {
                writeLen16(data, s.length)
                data.write(s.toByteArray(Charsets.UTF_16LE))
                data.write(0)
                data.write(0)
            }
        }
        while (data.size() % 4 != 0) data.write(0)
        val count = pool.strings.size
        val stringsStart = 28 + count * 4
        val total = stringsStart + data.size()
        val out = ByteArrayOutputStream(total)
        put16(out, STRING_POOL)
        put16(out, 28)
        put32(out, total)
        put32(out, count)
        put32(out, 0)
        put32(out, pool.flags and 1.inv())
        put32(out, stringsStart)
        put32(out, 0)
        offsets.forEach { put32(out, it) }
        out.write(data.toByteArray())
        return out.toByteArray()
    }

    fun patch(src: ByteArray, oldPkg: String, newPkg: String, label: String?, addPermissions: List<String> = emptyList()): ByteArray {
        require(src.u16(0) == FILE_TYPE) { "Not a binary manifest" }
        require(src.u16(8) == STRING_POOL) { "Unexpected manifest layout" }
        val pool = readPool(src, 8)
        val data = src.copyOf()
        val rename = oldPkg != newPkg

        fun stringIndex(s: String): Int {
            val i = pool.strings.indexOf(s)
            if (i >= 0) return i
            pool.strings.add(s)
            return pool.strings.size - 1
        }

        fun swap(v: String): String? = when {
            v == oldPkg -> newPkg
            v.startsWith("$oldPkg.") -> newPkg + v.substring(oldPkg.length)
            else -> null
        }

        fun setString(attr: Int, s: String) {
            val idx = stringIndex(s)
            data.set32(attr + 8, idx)
            data[attr + 15] = TYPE_STRING.toByte()
            data.set32(attr + 16, idx)
        }

        status = ""
        val present = HashSet<String>()
        var nsIdx = NONE
        var nameIdx = NONE
        var manifestEnd = -1
        var cloneStart = -1
        var cloneLen = 0
        var off = pool.end
        while (off + 8 <= data.size) {
            val type = data.u16(off)
            val size = data.u32(off + 4)
            if (size <= 0) break
            if (type == START_ELEMENT) {
                val element = pool.strings[data.u32(off + 20)]
                if (element == "manifest" && manifestEnd < 0) manifestEnd = off + size
                val attrStart = data.u16(off + 24)
                val attrSize = data.u16(off + 26)
                val attrCount = data.u16(off + 28)
                val base = off + 16 + attrStart
                for (i in 0 until attrCount) {
                    val a = base + i * attrSize
                    val attrName = pool.strings[data.u32(a + 4)]
                    val raw = data.u32(a + 8)
                    val isString = data.u8(a + 15) == TYPE_STRING && raw != NONE
                    val current = if (isString) pool.strings[raw] else null
                    if (attrName == "name" && nameIdx == NONE && data.u32(a) != NONE) {
                        nsIdx = data.u32(a)
                        nameIdx = data.u32(a + 4)
                    }
                    if (element == "uses-permission" && attrName == "name" && current != null) {
                        present.add(current)
                        val next = off + size
                        if (cloneStart < 0 && attrCount == 1 && next + 8 <= data.size && data.u16(next) == END_ELEMENT) {
                            cloneStart = off
                            cloneLen = size + data.u32(next + 4)
                        }
                    }
                    if (rename && current != null) {
                        when {
                            element == "manifest" && attrName == "package" ->
                                swap(current)?.let { setString(a, it) }
                            element == "provider" && attrName == "authorities" -> {
                                val parts = current.split(';').map { swap(it) ?: it }
                                val joined = parts.joinToString(";")
                                if (joined != current) setString(a, joined)
                            }
                            (element == "permission" || element == "uses-permission" ||
                                element == "uses-permission-sdk-23" || element == "uses-permission-sdk-m") &&
                                attrName == "name" ->
                                swap(current)?.let { setString(a, it) }
                            attrName == "permission" ->
                                swap(current)?.let { setString(a, it) }
                        }
                    }
                    if (label != null && attrName == "label" &&
                        (element == "application" || element == "activity" || element == "activity-alias")
                    ) {
                        setString(a, label)
                    }
                }
            }
            off += size
        }
        var insertAt = -1
        val clones = ByteArrayOutputStream()
        for (p in addPermissions) {
            if (p in present) {
                status = "present"
                continue
            }
            if (cloneStart >= 0) {
                val chunk = data.copyOfRange(cloneStart, cloneStart + cloneLen)
                val a = 16 + chunk.u16(24)
                val idx = stringIndex(p)
                chunk.set32(a + 8, idx)
                chunk[a + 15] = TYPE_STRING.toByte()
                chunk.set32(a + 16, idx)
                clones.write(chunk)
                insertAt = cloneStart + cloneLen
                status = "added"
            } else if (nameIdx != NONE && manifestEnd >= 0) {
                val elem = stringIndex("uses-permission")
                val idx = stringIndex(p)
                put16(clones, START_ELEMENT)
                put16(clones, 16)
                put32(clones, 56)
                put32(clones, 0)
                put32(clones, NONE)
                put32(clones, NONE)
                put32(clones, elem)
                put16(clones, 20)
                put16(clones, 20)
                put16(clones, 1)
                put16(clones, 0)
                put16(clones, 0)
                put16(clones, 0)
                put32(clones, nsIdx)
                put32(clones, nameIdx)
                put32(clones, idx)
                put16(clones, 8)
                clones.write(0)
                clones.write(TYPE_STRING)
                put32(clones, idx)
                put16(clones, END_ELEMENT)
                put16(clones, 16)
                put32(clones, 24)
                put32(clones, 0)
                put32(clones, NONE)
                put32(clones, NONE)
                put32(clones, elem)
                insertAt = manifestEnd
                status = "added"
            } else {
                status = "failed"
            }
        }
        val newPool = writePool(pool)
        val rest = if (insertAt >= 0 && clones.size() > 0) {
            val head = data.copyOfRange(pool.end, insertAt)
            val tail = data.copyOfRange(insertAt, data.size)
            head + clones.toByteArray() + tail
        } else {
            data.copyOfRange(pool.end, data.size)
        }
        val out = ByteArrayOutputStream(8 + newPool.size + rest.size)
        put16(out, FILE_TYPE)
        put16(out, 8)
        put32(out, 8 + newPool.size + rest.size)
        out.write(newPool)
        out.write(rest)
        return out.toByteArray()
    }
}
