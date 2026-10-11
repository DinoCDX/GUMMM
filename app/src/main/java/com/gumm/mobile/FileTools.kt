package com.gumm.mobile

import android.os.Environment
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class FEntry(
    val path: String,
    val size: Long,
    val file: File,
    val zipEntry: String,
    val pckOffset: Long = -1L,
    val encrypted: Boolean = false
) {
    val isPck: Boolean get() = pckOffset >= 0
}

class Hit(val entry: FEntry, val line: Int, val text: String)

object FileTools {
    const val EDITS_FOLDER = "gumm_edits"

    private val textExt = setOf(
        "gd", "tscn", "tres", "cfg", "json", "txt", "md", "csv", "gdshader", "shader", "import", "remap",
        "ini", "xml", "po", "godot", "svg", "gdextension", "properties", "yml", "yaml", "html", "glsl"
    )
    private val imageExt = setOf("png", "jpg", "jpeg", "webp", "bmp")

    fun ext(path: String): String = path.substringAfterLast('.', "").lowercase()

    fun isImage(path: String): Boolean = ext(path) in imageExt

    fun looksText(path: String, data: ByteArray): Boolean {
        if (data.isEmpty()) return ext(path) in textExt
        val n = minOf(data.size, 4096)
        for (i in 0 until n) if (data[i].toInt() == 0) return false
        return true
    }

    fun indexApks(files: List<File>): List<FEntry> {
        val out = ArrayList<FEntry>()
        for (f in files) {
            try {
                ZipFile(f).use { z ->
                    for (e in z.entries()) if (!e.isDirectory) out.add(FEntry(e.name, e.size, f, e.name))
                }
            } catch (e: Exception) {
            }
        }
        return out
    }

    fun indexPcks(files: List<File>): List<FEntry> {
        val out = ArrayList<FEntry>()
        for (f in files) {
            try {
                ZipFile(f).use { z ->
                    for (e in z.entries()) {
                        if (e.isDirectory || !e.name.endsWith(".pck")) continue
                        for (p in GodotInspector.listPck(z, e)) out.add(FEntry(p.path, p.size, f, e.name, p.offset, p.encrypted))
                    }
                }
            } catch (e: Exception) {
            }
        }
        return out
    }

    fun originalFiles(store: Store, id: String): List<File> = listOf(store.originalApk(id)) + store.splitFiles(id)

    fun moddedFiles(store: Store, id: String): List<File> {
        val base = File(store.buildDir(id), "modded.apk")
        if (!base.isFile) return emptyList()
        val splits = File(store.buildDir(id), "splits").listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
        return listOf(base) + splits
    }

    class Reader : AutoCloseable {
        private val zips = HashMap<File, ZipFile>()

        private fun zip(f: File): ZipFile = zips.getOrPut(f) { ZipFile(f) }

        fun open(e: FEntry): InputStream? {
            val ze = zip(e.file).getEntry(e.zipEntry) ?: return null
            val s = zip(e.file).getInputStream(ze)
            if (e.isPck) skipFully(s, e.pckOffset)
            return s
        }

        fun read(e: FEntry, max: Int): ByteArray {
            if (e.encrypted) return ByteArray(0)
            val s = open(e) ?: return ByteArray(0)
            val want = if (e.isPck) minOf(e.size, max.toLong()).toInt() else max
            return s.use { readUpTo(it, want) }
        }

        fun copyTo(e: FEntry, out: OutputStream) {
            if (e.encrypted) return
            val s = open(e) ?: return
            s.use {
                var left = if (e.isPck) e.size else Long.MAX_VALUE
                val buf = ByteArray(1 shl 16)
                while (left > 0) {
                    val n = it.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
            }
        }

        override fun close() {
            for (z in zips.values) {
                try {
                    z.close()
                } catch (e: Exception) {
                }
            }
            zips.clear()
        }
    }

    private fun skipFully(s: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(1 shl 16)
        while (left > 0) {
            val k = s.skip(left)
            if (k > 0) {
                left -= k
            } else {
                val r = s.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (r < 0) return
                left -= r
            }
        }
    }

    private fun readUpTo(s: InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1 shl 14)
        var total = 0
        while (total < max) {
            val n = s.read(buf, 0, minOf(buf.size, max - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    fun searchContent(entries: List<FEntry>, query: String, limit: Int, cancelled: () -> Boolean): List<Hit> {
        val q = query.lowercase()
        val hits = ArrayList<Hit>()
        var scanned = 0
        Reader().use { r ->
            for (e in entries) {
                if (cancelled() || hits.size >= limit || scanned > 6000) break
                if (ext(e.path) !in textExt || e.size > 400000 || e.encrypted) continue
                scanned++
                val text = try {
                    String(r.read(e, 400000), Charsets.UTF_8)
                } catch (ex: Exception) {
                    continue
                }
                var count = 0
                var lineNo = 0
                for (line in text.lineSequence()) {
                    lineNo++
                    if (line.lowercase().contains(q)) {
                        hits.add(Hit(e, lineNo, line.trim().take(160)))
                        count++
                        if (count >= 3 || hits.size >= limit) break
                    }
                }
            }
        }
        return hits
    }

    fun hexDump(data: ByteArray, max: Int = 512): String {
        val sb = StringBuilder()
        val n = minOf(data.size, max)
        var i = 0
        while (i < n) {
            sb.append(String.format("%06x  ", i))
            val ascii = StringBuilder()
            for (j in 0 until 16) {
                if (i + j < n) {
                    val b = data[i + j].toInt() and 0xFF
                    sb.append(String.format("%02x ", b))
                    ascii.append(if (b in 32..126) b.toChar() else '.')
                } else {
                    sb.append("   ")
                }
            }
            sb.append(' ').append(ascii).append('\n')
            i += 16
        }
        return sb.toString()
    }

    fun resPath(e: FEntry): String? = when {
        e.isPck -> e.path
        e.path.startsWith("assets/") -> e.path.removePrefix("assets/")
        else -> null
    }

    fun editsZip(store: Store, id: String): File {
        val dir = File(store.modsDir(id), EDITS_FOLDER)
        dir.mkdirs()
        return File(dir, "gumm_edits.zip")
    }

    fun editedPaths(zip: File): Set<String> {
        if (!zip.isFile) return emptySet()
        return try {
            ZipFile(zip).use { z -> z.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toSet() }
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun putEdit(zip: File, resPath: String, writer: (OutputStream) -> Unit) {
        val tmp = File(zip.parentFile, "edits.tmp")
        ZipOutputStream(tmp.outputStream().buffered()).use { out ->
            if (zip.isFile) {
                ZipFile(zip).use { z ->
                    for (e in z.entries()) {
                        if (e.isDirectory || e.name == resPath) continue
                        out.putNextEntry(ZipEntry(e.name))
                        z.getInputStream(e).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
            }
            out.putNextEntry(ZipEntry(resPath))
            writer(out)
            out.closeEntry()
        }
        zip.delete()
        tmp.renameTo(zip)
    }

    fun safe(s: String): String = s.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_').ifEmpty { "game" }

    fun exportRoot(title: String, source: String): File =
        File(Environment.getExternalStorageDirectory(), "Download/GUMMM/" + safe(title) + "/" + source)
}
