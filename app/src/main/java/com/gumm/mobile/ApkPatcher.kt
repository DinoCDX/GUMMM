package com.gumm.mobile

import android.content.Context
import com.android.apksig.ApkSigner
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ApkPatcher(private val ctx: Context, private val store: Store, private val keys: KeyManager) {

    private class Added(val bytes: ByteArray?, val file: File?)

    private class CountingOut(private val target: OutputStream) : OutputStream() {
        var count = 0L

        override fun write(b: Int) {
            target.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            target.write(b, off, len)
            count += len
        }

        override fun flush() = target.flush()

        override fun close() = target.close()
    }

    private val textExtensions = setOf("tscn", "gd", "cfg", "tres", "txt", "json")
    private val storedExtensions = setOf("png", "jpg", "jpeg", "webp", "ogg", "mp3", "mp4", "webm", "ogv", "zip", "ctex")

    fun patch(game: Game, log: (String) -> Unit): File {
        val original = store.originalApk(game.id)
        require(original.isFile) { "Original APK is missing" }
        val kit = store.kitDir(game.id)
        val oldPkg = game.originalPackage
        val newPkg = game.targetPackage()
        val modsRef = if (game.modsExternal) ExternalMods.root(newPkg).path else "res://mods"
        val added = LinkedHashMap<String, Added>()
        var kitOverride: String? = null
        val active = when {
            game.modsExternal -> emptyList<Mod>()
            game.customLoader -> game.mods.filter { it.enabled }
            else -> game.mods
        }

        if (game.customLoader) {
            require(File(kit, "override.cfg").isFile) { "Import your loader files first (override.cfg is missing)" }
            val kitFiles = kit.walkTopDown().filter { it.isFile }.toList()
            for (f in kitFiles) {
                val rel = f.relativeTo(kit).path.replace(File.separatorChar, '/')
                val data = transform(game, rel, f.readBytes(), modsRef)
                if (rel == "override.cfg") {
                    kitOverride = String(data, Charsets.UTF_8)
                } else {
                    added["assets/$rel"] = Added(data, null)
                }
            }
            log("Custom loader files: ${kitFiles.size}")
        } else {
            require(LoaderGen.supports(game)) { "The generated loader supports Godot 3 and 4. Check the Godot version." }
            require(game.mainScene.isNotBlank()) { "Set the main scene path first" }
            val list = active.map { "res://mods/${it.folder}" }
            val root = if (game.modsExternal) modsRef else ""
            val configPath = if (game.modsExternal) ExternalMods.configFile(newPkg).path else ""
            val logPath = if (game.modsExternal) ExternalMods.logFile(newPkg).path else ""
            val defaults = LinkedHashMap<String, Boolean>()
            for (m in game.mods) defaults[m.folder] = m.enabled
            val major = LoaderGen.major(game)
            val template = ctx.assets.open("gumm_loader$major.gd").use { String(it.readBytes(), Charsets.UTF_8) }
            val core = ctx.assets.open("gumm_core$major.gd").use { it.readBytes() }
            val scene = LoaderGen.scene(
                game, template, list, root, configPath, logPath,
                System.currentTimeMillis().toString(), defaults, game.overlay
            )
            added["assets/GUMM_mod_loader.tscn"] = Added(scene.toByteArray(Charsets.UTF_8), null)
            added["assets/GUMM_core.gd"] = Added(core, null)
            kitOverride = LoaderGen.override()
            log("Generated loader for Godot ${game.variant()}")
        }

        if (game.modsExternal) log("Mods are loaded from $modsRef")
        for (mod in active) {
            val dir = File(store.modsDir(game.id), mod.folder)
            if (!dir.isDirectory) {
                log("Skipping missing mod ${mod.name}")
                continue
            }
            var n = 0
            for (f in dir.walkTopDown().filter { it.isFile }) {
                val rel = f.relativeTo(dir).path.replace(File.separatorChar, '/')
                added["assets/mods/${mod.folder}/$rel"] = Added(null, f)
                n++
            }
            log("Mod ${mod.name}: $n files")
        }

        val label = if (game.labelSuffix) "${game.title} (Modded)" else null
        var manifestPatch: ((ByteArray) -> ByteArray)? = null
        val perms = if (game.modsExternal) listOf("android.permission.MANAGE_EXTERNAL_STORAGE") else emptyList()
        if (oldPkg != newPkg || label != null || perms.isNotEmpty()) {
            manifestPatch = { bytes -> AxmlPatcher.patch(bytes, oldPkg, newPkg, label, perms) }
        }
        if (perms.isNotEmpty()) log("Adding All files access permission to the game")
        if (oldPkg != newPkg) log("Package: $oldPkg -> $newPkg")

        val buildDir = store.buildDir(game.id)
        val stage = File(buildDir, "stage.apk")
        val out = File(buildDir, "modded.apk")
        val fresh = File(buildDir, "modded.new.apk")
        stage.delete()
        fresh.delete()

        log("Rewriting APK")
        rewrite(original, stage, added, kitOverride, manifestPatch)
        if (perms.isNotEmpty()) {
            when (AxmlPatcher.status) {
                "added" -> log("All files access added to the game manifest")
                "present" -> log("The game already requests All files access")
                else -> log("! Could not add All files access (unexpected manifest layout)")
            }
        }

        log("Signing")
        val cfg = keys.signerConfig()
        ApkSigner.Builder(listOf(cfg))
            .setInputApk(stage)
            .setOutputApk(fresh)
            .setMinSdkVersion(28)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
        stage.delete()
        out.delete()
        if (!fresh.renameTo(out)) {
            fresh.copyTo(out, overwrite = true)
            fresh.delete()
        }
        log("Built ${out.length() / 1024} KB")
        if (perms.isNotEmpty()) {
            val info = ctx.packageManager.getPackageArchiveInfo(out.path, android.content.pm.PackageManager.GET_PERMISSIONS)
            val ok = info?.requestedPermissions?.contains("android.permission.MANAGE_EXTERNAL_STORAGE") == true
            log(if (ok) "Checked: built APK requests All files access" else "! Built APK does not request All files access")
        }
        return out
    }

    private fun transform(game: Game, rel: String, bytes: ByteArray, modsRef: String): ByteArray {
        val ext = rel.substringAfterLast('.', "").lowercase()
        if (ext !in textExtensions) return bytes
        var text = String(bytes, Charsets.UTF_8)
        if (game.kitMainScene.isNotBlank()) text = text.replace(game.kitMainScene, "{{MAIN_SCENE}}")
        if (game.kitModsPath.isNotBlank()) text = text.replace(game.kitModsPath, "{{MODS_DIR}}")
        if (text.contains("{{MAIN_SCENE}}") && game.mainScene.isBlank()) {
            throw IllegalStateException("Main scene path is empty but $rel needs it")
        }
        text = text.replace("{{MAIN_SCENE}}", game.mainScene).replace("{{MODS_DIR}}", modsRef)
        return text.toByteArray(Charsets.UTF_8)
    }

    private fun isSignature(name: String): Boolean =
        name.startsWith("META-INF/") && (
            name == "META-INF/MANIFEST.MF" ||
                name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC")
            )

    private fun rewrite(
        src: File,
        dest: File,
        added: Map<String, Added>,
        kitOverride: String?,
        manifestPatch: ((ByteArray) -> ByteArray)?
    ) {
        val counting = CountingOut(BufferedOutputStream(FileOutputStream(dest), 1 shl 16))
        ZipFile(src).use { zf ->
            if (zf.size() + added.size > 65000) {
                throw IllegalStateException("Too many files for an APK (limit 65535). Use the external mods folder.")
            }
            ZipOutputStream(counting).use { zos ->
                zos.setLevel(Deflater.BEST_SPEED)
                val written = HashSet<String>()
                var overrideDone = false
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    val name = e.name
                    if (e.isDirectory) continue
                    if (isSignature(name)) continue
                    if (added.containsKey(name)) continue
                    if (!written.add(name)) continue
                    if (name == "AndroidManifest.xml" && manifestPatch != null) {
                        val patched = manifestPatch(zf.getInputStream(e).use { it.readBytes() })
                        putBytes(zos, name, patched)
                    } else if (name == "assets/override.cfg" && kitOverride != null) {
                        val orig = zf.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) }
                        val merged = orig.trimEnd() + "\n\n" + kitOverride + "\n"
                        putBytes(zos, name, merged.toByteArray(Charsets.UTF_8))
                        overrideDone = true
                    } else {
                        copyEntry(zf, e, zos, counting)
                    }
                }
                if (!overrideDone && kitOverride != null) {
                    putBytes(zos, "assets/override.cfg", kitOverride.toByteArray(Charsets.UTF_8))
                }
                for ((path, a) in added) {
                    if (!written.add(path)) continue
                    if (a.bytes != null) {
                        putBytes(zos, path, a.bytes)
                    } else if (a.file != null) {
                        val ze = ZipEntry(path)
                        if (path.substringAfterLast('.', "").lowercase() in storedExtensions) {
                            val crc = java.util.zip.CRC32()
                            val buf = ByteArray(1 shl 16)
                            a.file.inputStream().use { ins ->
                                while (true) {
                                    val n = ins.read(buf)
                                    if (n < 0) break
                                    crc.update(buf, 0, n)
                                }
                            }
                            ze.method = ZipEntry.STORED
                            ze.size = a.file.length()
                            ze.compressedSize = a.file.length()
                            ze.crc = crc.value
                        } else {
                            ze.method = ZipEntry.DEFLATED
                        }
                        zos.putNextEntry(ze)
                        a.file.inputStream().use { it.copyTo(zos, 1 shl 16) }
                        zos.closeEntry()
                    }
                }
            }
        }
    }

    private fun putBytes(zos: ZipOutputStream, name: String, bytes: ByteArray) {
        val ze = ZipEntry(name)
        ze.method = ZipEntry.DEFLATED
        zos.putNextEntry(ze)
        zos.write(bytes)
        zos.closeEntry()
    }

    private fun copyEntry(zf: ZipFile, e: ZipEntry, zos: ZipOutputStream, counting: CountingOut) {
        val ze = ZipEntry(e.name)
        if (e.method == ZipEntry.STORED) {
            ze.method = ZipEntry.STORED
            ze.size = e.size
            ze.compressedSize = e.size
            ze.crc = e.crc
            val align = if (e.name.endsWith(".so")) 16384 else 4
            ze.extra = alignExtra(counting.count, e.name, align)
        } else {
            ze.method = ZipEntry.DEFLATED
        }
        zos.putNextEntry(ze)
        zf.getInputStream(e).use { it.copyTo(zos, 1 shl 16) }
        zos.closeEntry()
    }

    private fun alignExtra(offset: Long, name: String, align: Int): ByteArray? {
        val base = offset + 30 + name.toByteArray(Charsets.UTF_8).size
        var pad = ((align - (base % align)) % align).toInt()
        if (pad == 0) return null
        while (pad < 6) pad += align
        val b = ByteArray(pad)
        b[0] = 0x35
        b[1] = 0xD9.toByte()
        val l = pad - 4
        b[2] = l.toByte()
        b[3] = (l shr 8).toByte()
        b[4] = align.toByte()
        b[5] = (align shr 8).toByte()
        return b
    }
}
