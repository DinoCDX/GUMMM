package com.gumm.mobile

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

object ModImporter {
    fun fromZip(ctx: Context, uri: Uri, modsRoot: File, log: (String) -> Unit): List<Mod> {
        val staging = File(ctx.cacheDir, "stage_" + System.nanoTime()).apply { mkdirs() }
        try {
            val base = staging.canonicalPath + File.separator
            val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open file")
            input.use { raw ->
                ZipInputStream(raw.buffered()).use { zis ->
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
            val hint = DocFiles.fileName(ctx.contentResolver, uri).substringBeforeLast('.')
            return finish(staging, hint, modsRoot, log)
        } finally {
            staging.deleteRecursively()
        }
    }

    fun fromTree(ctx: Context, tree: Uri, modsRoot: File, log: (String) -> Unit): List<Mod> {
        val staging = File(ctx.cacheDir, "stage_" + System.nanoTime()).apply { mkdirs() }
        try {
            DocFiles.copyTree(ctx.contentResolver, tree, staging)
            val hint = DocFiles.treeName(ctx.contentResolver, tree)
            return finish(staging, hint, modsRoot, log)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun finish(staging: File, hint: String, modsRoot: File, log: (String) -> Unit): List<Mod> {
        val roots = findModRoots(staging)
        if (roots.isEmpty()) throw IOException("No mod.cfg found. A mod needs mod.cfg and mod.gd.")
        val out = ArrayList<Mod>()
        for (modRoot in roots) {
            val cfg = parseCfg(File(modRoot, "mod.cfg"))
            val fallback = if (modRoot == staging) hint else modRoot.name
            val name = cfg["name"] ?: cfg["title"] ?: fallback
            val folder = uniqueFolder(modsRoot, sanitize(name.ifBlank { fallback }))
            modRoot.copyRecursively(File(modsRoot, folder), overwrite = true)
            log("Mod folder: $folder")
            out.add(
                Mod(
                    id = folder,
                    name = name,
                    version = cfg["version"] ?: "",
                    author = cfg["author"] ?: cfg["authors"] ?: "",
                    description = cfg["description"] ?: "",
                    folder = folder,
                    enabled = true
                )
            )
        }
        return out
    }

    private fun findModRoots(dir: File): List<File> {
        val found = ArrayList<File>()
        val queue = ArrayDeque<File>()
        queue.add(dir)
        while (queue.isNotEmpty()) {
            val d = queue.removeFirst()
            if (File(d, "mod.cfg").isFile) {
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
