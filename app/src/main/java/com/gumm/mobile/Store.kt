package com.gumm.mobile

import android.content.Context
import org.json.JSONArray
import java.io.File

class Store(context: Context) {
    val root: File = File(context.filesDir, "gumm").apply { mkdirs() }
    val defaultKit: File = File(root, "default_kit")
    private val gamesFile = File(root, "games.json")

    fun gameDir(id: String): File = File(root, "games/$id").apply { mkdirs() }
    fun originalApk(id: String): File = File(gameDir(id), "original.apk")
    fun splitsDir(id: String): File = File(gameDir(id), "splits")
    fun splitFiles(id: String): List<File> =
        splitsDir(id).listFiles()?.filter { it.isFile && it.extension.equals("apk", true) }?.sortedBy { it.name } ?: emptyList()
    fun modsDir(id: String): File = File(gameDir(id), "mods").apply { mkdirs() }
    fun kitDir(id: String): File = File(gameDir(id), "kit").apply { mkdirs() }
    fun buildDir(id: String): File = File(gameDir(id), "build").apply { mkdirs() }
    fun iconFile(id: String): File = File(gameDir(id), "icon.png")

    @Synchronized
    fun loadGames(): List<Game> {
        if (!gamesFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(gamesFile.readText())
            (0 until arr.length()).map { Game.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun saveGames(list: List<Game>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val tmp = File(root, "games.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(gamesFile)) {
            gamesFile.writeText(arr.toString())
            tmp.delete()
        }
    }

    fun stamp(g: Game): String {
        val sb = StringBuilder()
        sb.append(g.title).append('|').append(g.mainScene).append('|').append(g.kitMainScene).append('|')
        sb.append(g.kitModsPath).append('|').append(g.renamePackage).append('|').append(g.targetPackage()).append('|')
        sb.append(g.labelSuffix).append('|').append(g.modsExternal).append('|').append(g.customLoader).append('|')
        sb.append(LoaderGen.VERSION).append('|')
        val apk = originalApk(g.id)
        sb.append(apk.length()).append(':').append(apk.lastModified()).append('|')
        for (s in splitFiles(g.id)) sb.append(s.name).append(s.length()).append(s.lastModified()).append('|')
        if (g.customLoader) {
            kitDir(g.id).walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach {
                sb.append(it.name).append(it.length()).append(it.lastModified())
            }
        }
        sb.append(g.overlay).append('|')
        for (m in g.mods.filter { it.enabled && it.kind == ModKinds.LOADER }) {
            sb.append("|ml:").append(m.folder)
            File(modsDir(g.id), m.folder).walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach {
                sb.append(it.name).append(it.length()).append(it.lastModified())
            }
        }
        if (!g.modsExternal) {
            val packed = if (g.customLoader) g.mods.filter { it.enabled } else g.mods
            for (m in packed) {
                sb.append('|').append(m.folder)
                if (!g.customLoader) sb.append(m.enabled)
                sb.append(m.kind).append(m.kindManual)
                File(modsDir(g.id), m.folder).walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach {
                    sb.append(it.name).append(it.length()).append(it.lastModified())
                }
            }
        }
        return Integer.toHexString(sb.toString().hashCode())
    }

    fun get(id: String): Game? = loadGames().firstOrNull { it.id == id }

    @Synchronized
    fun add(game: Game) {
        saveGames(loadGames() + game)
    }

    @Synchronized
    fun update(id: String, change: (Game) -> Game) {
        saveGames(loadGames().map { if (it.id == id) change(it) else it })
    }

    fun remove(id: String) {
        synchronized(this) {
            saveGames(loadGames().filter { it.id != id })
        }
        File(root, "games/$id").deleteRecursively()
    }
}
