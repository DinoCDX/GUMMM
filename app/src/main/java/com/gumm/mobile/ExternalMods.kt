package com.gumm.mobile

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

object ExternalMods {
    private const val MARKER = ".gumm_managed"

    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun available(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    fun root(pkg: String): File =
        File(Environment.getExternalStorageDirectory(), "Android/media/$pkg/GUMM/mods")

    fun logFile(pkg: String): File = File(root(pkg).parentFile, "loader.log")

    fun configFile(pkg: String): File = File(root(pkg).parentFile, "config.txt")

    fun gameAccess(ctx: android.content.Context, pkg: String): Boolean? = try {
        val uid = ctx.packageManager.getApplicationInfo(pkg, 0).uid
        val ops = ctx.getSystemService(android.app.AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow("android:manage_external_storage", uid, pkg) == android.app.AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        null
    }

    fun gameRequests(ctx: android.content.Context, pkg: String): Boolean? = try {
        val info = ctx.packageManager.getPackageInfo(pkg, android.content.pm.PackageManager.GET_PERMISSIONS)
        info.requestedPermissions?.contains("android.permission.MANAGE_EXTERNAL_STORAGE") == true
    } catch (e: Exception) {
        null
    }

    fun openSettings(activity: Activity, pkg: String = activity.packageName) {
        try {
            val uri = Uri.parse("package:$pkg")
            activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri))
        } catch (e: ActivityNotFoundException) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun stamp(dir: File): String {
        var count = 0
        var size = 0L
        var newest = 0L
        dir.walkTopDown().filter { it.isFile && it.name != MARKER }.forEach {
            count++
            size += it.length()
            if (it.lastModified() > newest) newest = it.lastModified()
        }
        return "$count:$size:$newest"
    }

    fun sync(store: Store, game: Game, log: (String) -> Unit) {
        val root = root(game.targetPackage())
        root.mkdirs()
        if (!root.isDirectory) throw IllegalStateException("Cannot create ${root.path}")
        val wanted = game.mods.map { it.folder }.toSet()
        root.listFiles()
            ?.filter { it.isDirectory && File(it, MARKER).isFile && it.name !in wanted }
            ?.forEach {
                it.deleteRecursively()
                log("Removed ${it.name}")
            }
        for (mod in game.mods) {
            val src = File(store.modsDir(game.id), mod.folder)
            val dst = File(root, mod.folder)
            if (!src.isDirectory) continue
            val marker = File(dst, MARKER)
            if (dst.exists() && !marker.isFile) {
                log("Skipped ${mod.name}: a folder with that name already exists")
                continue
            }
            val s = stamp(src)
            if (marker.isFile && marker.readText() == s) continue
            dst.deleteRecursively()
            src.copyRecursively(dst, overwrite = true)
            marker.writeText(s)
            log("Synced ${mod.name}")
        }
        writeConfig(game)
    }

    private fun writeConfig(game: Game) {
        val cfg = configFile(game.targetPackage())
        val body = game.mods.joinToString("\n") { it.folder + "=" + (if (it.enabled) "1" else "0") }
        val old = if (cfg.isFile) cfg.readLines().filter { !it.startsWith("rev=") }.joinToString("\n").trim() else null
        if (old == body) return
        cfg.writeText("rev=" + System.currentTimeMillis() + "\n" + body + "\n")
    }
}
