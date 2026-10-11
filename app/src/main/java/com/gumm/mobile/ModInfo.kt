package com.gumm.mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

object ModInfo {
    private val iconNames = listOf("icon.png", "icon.jpg", "icon.jpeg", "icon.webp")

    fun size(dir: File): Pair<Long, Int> {
        var bytes = 0L
        var count = 0
        dir.walkTopDown().filter { it.isFile }.forEach {
            bytes += it.length()
            count++
        }
        return Pair(bytes, count)
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / 1073741824.0)
        bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / 1048576.0)
        bytes >= 1L shl 10 -> (bytes shr 10).toString() + " KB"
        else -> "$bytes B"
    }

    fun icon(dir: File, maxPx: Int): Bitmap? {
        val f = iconNames.map { File(dir, it) }.firstOrNull { it.isFile } ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Throwable) {
            null
        }
    }
}

object ModKinds {
    const val GUMM = "gumm"
    const val PACK = "pack"
    const val LOADER = "modloader"
    private val packExt = setOf("pck", "zip")

    fun label(kind: String): String = when (kind) {
        PACK -> "Resource pack"
        LOADER -> "Godot Mod Loader"
        else -> "GUMM"
    }

    fun packFiles(dir: File): List<File> =
        dir.walkTopDown().maxDepth(2).filter { it.isFile && it.extension.lowercase() in packExt }.sortedBy { it.path }.toList()

    fun detect(dir: File): String = when {
        File(dir, "mod.gd").isFile || File(dir, "GUMM_mod.gd").isFile -> GUMM
        File(dir, "mods-unpacked").isDirectory -> LOADER
        packFiles(dir).isNotEmpty() -> PACK
        else -> GUMM
    }
}

data class Conflict(val path: String, val mods: List<String>)

object ModConflicts {
    private val pattern = Regex("""(?:replace_resource_at|take_over_path)\s*\(\s*"(res://[^"]+)"""")

    private fun packPaths(dir: File): Set<String> {
        val out = HashSet<String>()
        for (f in ModKinds.packFiles(dir)) {
            if (!f.extension.equals("zip", true)) continue
            try {
                java.util.zip.ZipFile(f).use { z ->
                    for (e in z.entries()) {
                        if (out.size > 50000) break
                        if (!e.isDirectory && !e.name.startsWith(".import")) out.add("res://" + e.name)
                    }
                }
            } catch (e: Exception) {
            }
        }
        return out
    }

    fun scan(store: Store, game: Game): List<Conflict> {
        val owners = LinkedHashMap<String, MutableList<String>>()
        for (m in game.mods.filter { it.enabled }) {
            val modDir = File(store.modsDir(game.id), m.folder)
            if (m.kind == ModKinds.PACK) {
                for (hit in packPaths(modDir)) owners.getOrPut(hit) { ArrayList() }.add(m.name)
                continue
            }
            if (m.kind != ModKinds.GUMM) continue
            val script = File(modDir, "mod.gd")
            if (!script.isFile) continue
            val text = try { script.readText() } catch (e: Exception) { continue }
            for (hit in pattern.findAll(text).map { it.groupValues[1] }.toSet()) {
                owners.getOrPut(hit) { ArrayList() }.add(m.name)
            }
        }
        return owners.filter { it.value.size > 1 }.map { Conflict(it.key, it.value) }
    }
}

enum class ModState { ACTIVE, CONFLICT, ERROR, OFF }

object ModStatus {
    fun compute(store: Store, game: Game, conflicts: List<Conflict>): Map<String, ModState> {
        val conflicted = conflicts.flatMap { it.mods }.toSet()
        val problems = loaderProblems(game)
        val out = HashMap<String, ModState>()
        for (m in game.mods) {
            val dir = File(store.modsDir(game.id), m.folder)
            out[m.id] = when {
                !m.enabled -> ModState.OFF
                problem(dir, m, game) != null -> ModState.ERROR
                problems.any { it.contains(m.folder, true) } -> ModState.ERROR
                m.name in conflicted -> ModState.CONFLICT
                else -> ModState.ACTIVE
            }
        }
        return out
    }

    fun problem(dir: File, m: Mod, game: Game): String? = when (m.kind) {
        ModKinds.PACK -> if (ModKinds.packFiles(dir).isEmpty()) "No .pck or .zip file found" else null
        ModKinds.LOADER -> when {
            !LoaderGen.supports(game) -> "Needs a Godot 3 or 4 game"
            !File(dir, "mods-unpacked").isDirectory -> "mods-unpacked folder is missing"
            else -> null
        }
        else -> if (!File(dir, "mod.gd").isFile && !File(dir, "GUMM_mod.gd").isFile) "mod.gd is missing" else null
    }

    private fun loaderProblems(game: Game): List<String> {
        if (!game.modsExternal) return emptyList()
        return try {
            val f = ExternalMods.logFile(game.targetPackage())
            if (!f.isFile) emptyList()
            else f.readLines().takeLast(300).filter {
                val l = it.lowercase()
                l.contains("error") || l.contains("fail") || l.contains("not found") || l.contains("missing")
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
