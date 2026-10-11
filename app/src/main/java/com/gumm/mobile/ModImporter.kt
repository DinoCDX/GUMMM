package com.gumm.mobile

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

object ModImporter {
    private val packExt = setOf("pck", "zip")

    fun fromZip(ctx: Context, uri: Uri, modsRoot: File, log: (String) -> Unit): List<Mod> {
        val work = File(ctx.cacheDir, "stage_" + System.nanoTime()).apply { mkdirs() }
        try {
            val raw = File(work, "raw.bin")
            val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open file")
            input.use { i -> raw.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
            val fileName = DocFiles.fileName(ctx.contentResolver, uri)
            val staging = File(work, "x").apply { mkdirs() }
            if (!isPck(raw)) extract(raw, staging)
            val hint = fileName.substringBeforeLast('.')
            return finish(staging, hint, modsRoot, log, raw)
        } finally {
            work.deleteRecursively()
        }
    }

    fun fromTree(ctx: Context, tree: Uri, modsRoot: File, log: (String) -> Unit): List<Mod> {
        val staging = File(ctx.cacheDir, "stage_" + System.nanoTime()).apply { mkdirs() }
        try {
            DocFiles.copyTree(ctx.contentResolver, tree, staging)
            val hint = DocFiles.treeName(ctx.contentResolver, tree)
            return finish(staging, hint, modsRoot, log, null)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun isPck(f: File): Boolean = try {
        f.inputStream().use { s ->
            val b = ByteArray(4)
            s.read(b) == 4 && String(b, Charsets.ISO_8859_1) == "GDPC"
        }
    } catch (e: Exception) {
        false
    }

    private fun extract(zip: File, staging: File) {
        val base = staging.canonicalPath + File.separator
        zip.inputStream().buffered().use { raw ->
            ZipInputStream(raw).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val out = File(staging, entry.name)
                    if (!out.canonicalPath.startsWith(base)) throw IOException("Unsafe path in zip")
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it, 1 shl 16) }
                    }
                    entry = zis.nextEntry
                }
            }
        }
    }

    private fun finish(staging: File, hint: String, modsRoot: File, log: (String) -> Unit, raw: File?): List<Mod> {
        val gummRoots = findRoots(staging) { File(it, "mod.cfg").isFile }
        if (gummRoots.isNotEmpty()) return gummRoots.map { importGumm(it, staging, hint, modsRoot, log) }
        val loaderRoots = findRoots(staging) { File(it, "mods-unpacked").isDirectory }
        if (loaderRoots.isNotEmpty()) return loaderRoots.map { importLoader(it, staging, hint, modsRoot, log) }
        return listOf(importPack(staging, hint, modsRoot, log, raw))
    }

    private fun importGumm(modRoot: File, staging: File, hint: String, modsRoot: File, log: (String) -> Unit): Mod {
        val cfg = parseCfg(File(modRoot, "mod.cfg"))
        val fallback = if (modRoot == staging) hint else modRoot.name
        val name = cfg["name"] ?: cfg["title"] ?: fallback
        val folder = uniqueFolder(modsRoot, sanitize(name.ifBlank { fallback }))
        modRoot.copyRecursively(File(modsRoot, folder), overwrite = true)
        log("Mod folder: $folder")
        return Mod(
            id = folder,
            name = name,
            version = cfg["version"] ?: "",
            author = cfg["author"] ?: cfg["authors"] ?: "",
            description = cfg["description"] ?: "",
            folder = folder,
            enabled = true,
            kind = ModKinds.GUMM
        )
    }

    private fun importLoader(modRoot: File, staging: File, hint: String, modsRoot: File, log: (String) -> Unit): Mod {
        val manifest = File(modRoot, "mods-unpacked").listFiles()
            ?.filter { it.isDirectory }
            ?.map { File(it, "manifest.json") }
            ?.firstOrNull { it.isFile }
        var name = if (modRoot == staging) hint else modRoot.name
        var author = ""
        var version = ""
        var description = ""
        if (manifest != null) {
            try {
                val j = JSONObject(manifest.readText())
                name = j.optString("name", name).ifBlank { name }
                author = j.optString("namespace", "")
                version = j.optString("version_number", "")
                description = j.optString("description", "")
            } catch (e: Exception) {
                log("Could not read manifest.json")
            }
        }
        val folder = uniqueFolder(modsRoot, sanitize((if (author.isNotBlank()) "$author-" else "") + name))
        modRoot.copyRecursively(File(modsRoot, folder), overwrite = true)
        log("Mod folder: $folder")
        log("Godot Mod Loader mod (experimental)")
        return Mod(
            id = folder,
            name = name,
            version = version,
            author = author,
            description = description,
            folder = folder,
            enabled = true,
            kind = ModKinds.LOADER
        )
    }

    private fun importPack(staging: File, hint: String, modsRoot: File, log: (String) -> Unit, raw: File?): Mod {
        val base = hint.ifBlank { "pack" }
        val folder = uniqueFolder(modsRoot, sanitize(base))
        val dst = File(modsRoot, folder)
        val nested = staging.walkTopDown().any { it.isFile && it.extension.lowercase() in packExt }
        if (nested) {
            staging.copyRecursively(dst, overwrite = true)
        } else if (raw != null && raw.length() > 0 && (isPck(raw) || staging.walkTopDown().any { it.isFile })) {
            dst.mkdirs()
            raw.copyTo(File(dst, if (isPck(raw)) "$folder.pck" else "$folder.zip"), overwrite = true)
        } else {
            throw IOException("No mod found. Supported: GUMM mods (mod.cfg and mod.gd) and resource packs (.pck or .zip).")
        }
        log("Resource pack: $folder")
        return Mod(
            id = folder,
            name = base,
            version = "",
            author = "",
            description = "Resource pack",
            folder = folder,
            enabled = true,
            kind = ModKinds.PACK
        )
    }

    private fun findRoots(dir: File, match: (File) -> Boolean): List<File> {
        val found = ArrayList<File>()
        val queue = ArrayDeque<File>()
        queue.add(dir)
        while (queue.isNotEmpty()) {
            val d = queue.removeFirst()
            if (match(d)) {
                found.add(d)
                continue
            }
            d.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { queue.add(it) }
        }
        return found
    }

    private fun parseCfg(f: File): Map<String, String> {
        val m = HashMap<String, String>()
        f.readLines().forEach { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith(";") || t.startsWith("[")) return@forEach
            val i = t.indexOf('=')
            if (i > 0) {
                m.putIfAbsent(t.substring(0, i).trim().lowercase(), t.substring(i + 1).trim().trim('"'))
            }
        }
        return m
    }

    private fun sanitize(s: String): String {
        val r = s.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_')
        return if (r.isEmpty()) "mod" else r
    }

    private fun uniqueFolder(root: File, base: String): String {
        var name = base
        var n = 2
        while (File(root, name).exists()) {
            name = base + "_" + n
            n++
        }
        return name
    }
}
