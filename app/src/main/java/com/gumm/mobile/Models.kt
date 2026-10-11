package com.gumm.mobile

import org.json.JSONArray
import org.json.JSONObject

data class Mod(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val folder: String,
    val enabled: Boolean
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("version", version)
        .put("author", author)
        .put("description", description)
        .put("folder", folder)
        .put("enabled", enabled)

    companion object {
        fun fromJson(o: JSONObject) = Mod(
            id = o.getString("id"),
            name = o.optString("name"),
            version = o.optString("version"),
            author = o.optString("author"),
            description = o.optString("description"),
            folder = o.getString("folder"),
            enabled = o.optBoolean("enabled", true)
        )
    }
}

data class Game(
    val id: String,
    val title: String,
    val originalPackage: String,
    val godotVersion: String,
    val mainScene: String,
    val kitMainScene: String,
    val renamePackage: Boolean,
    val customPackage: String,
    val labelSuffix: Boolean,
    val copyUserData: Boolean,
    val modsExternal: Boolean,
    val customLoader: Boolean,
    val overlay: Boolean,
    val kitModsPath: String,
    val builtStamp: String,
    val builtPackage: String,
    val lastBuildMs: Long,
    val mods: List<Mod>
) {
    fun targetPackage(): String =
        if (renamePackage) customPackage.ifBlank { "$originalPackage.gumm" } else originalPackage

    fun variant(): String {
        val parts = godotVersion.split('.')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return "?"
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return when (major) {
            2 -> "2.x"
            3 -> if (minor >= 3) "3.3+" else "3.x"
            4 -> if (minor >= 4) "4.4+" else "4.x"
            else -> "?"
        }
    }

    fun toJson(): JSONObject {
        val arr = JSONArray()
        mods.forEach { arr.put(it.toJson()) }
        return JSONObject()
            .put("id", id)
            .put("title", title)
            .put("originalPackage", originalPackage)
            .put("godotVersion", godotVersion)
            .put("mainScene", mainScene)
            .put("kitMainScene", kitMainScene)
            .put("renamePackage", renamePackage)
            .put("customPackage", customPackage)
            .put("labelSuffix", labelSuffix)
            .put("copyUserData", copyUserData)
            .put("modsExternal", modsExternal)
            .put("customLoader", customLoader)
            .put("overlay", overlay)
            .put("kitModsPath", kitModsPath)
            .put("builtStamp", builtStamp)
            .put("builtPackage", builtPackage)
            .put("lastBuildMs", lastBuildMs)
            .put("mods", arr)
    }

    companion object {
        fun fromJson(o: JSONObject): Game {
            val arr = o.optJSONArray("mods") ?: JSONArray()
            return Game(
                id = o.getString("id"),
                title = o.optString("title"),
                originalPackage = o.getString("originalPackage"),
                godotVersion = o.optString("godotVersion"),
                mainScene = o.optString("mainScene"),
                kitMainScene = o.optString("kitMainScene"),
                renamePackage = o.optBoolean("renamePackage", true),
                customPackage = o.optString("customPackage"),
                labelSuffix = o.optBoolean("labelSuffix", true),
                copyUserData = o.optBoolean("copyUserData", false),
                modsExternal = o.optBoolean("modsExternal", false),
                customLoader = o.optBoolean("customLoader", false),
                overlay = o.optBoolean("overlay", true),
                kitModsPath = o.optString("kitModsPath"),
                builtStamp = o.optString("builtStamp"),
                builtPackage = o.optString("builtPackage"),
                lastBuildMs = o.optLong("lastBuildMs", 0L),
                mods = (0 until arr.length()).map { Mod.fromJson(arr.getJSONObject(it)) }
            )
        }
    }
}
