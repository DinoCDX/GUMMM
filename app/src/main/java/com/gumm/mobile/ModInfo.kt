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

data class Conflict(val path: String, val mods: List<String>)

object ModConflicts {
    private val pattern = Regex("""(?:replace_resource_at|take_over_path)\s*\(\s*"(res://[^"]+)"""")

    fun scan(store: Store, game: Game): List<Conflict> {
        val owners = LinkedHashMap<String, MutableList<String>>()
        for (m in game.mods.filter { it.enabled }) {
            val script = File(File(store.modsDir(game.id), m.folder), "mod.gd")
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
                !File(dir, "mod.gd").isFile && !File(dir, "GUMM_mod.gd").isFile -> ModState.ERROR
                problems.any { it.contains(m.folder, true) } -> ModState.ERROR
                m.name in conflicted -> ModState.CONFLICT
                else -> ModState.ACTIVE
            }
        }
        return out
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
