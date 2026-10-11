package com.gumm.mobile

import android.content.Context

object RootCopy {
    private const val BACKUP_ROOT = "/data/local/tmp/gumm_bk"

    private fun su(command: String): Pair<Int, String> {
        val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    fun available(): Boolean = try {
        val r = su("id")
        r.first == 0 && r.second.contains("uid=0")
    } catch (e: Exception) {
        false
    }

    fun backup(pkg: String): Boolean = try {
        val dest = "$BACKUP_ROOT/$pkg"
        su("rm -rf $dest && mkdir -p $dest && cp -a /data/data/$pkg/files $dest/files").first == 0
    } catch (e: Exception) {
        false
    }

    fun restore(ctx: Context, backupOf: String, toPkg: String): Boolean = try {
        val uid = ctx.packageManager.getApplicationInfo(toPkg, 0).uid
        val src = "$BACKUP_ROOT/$backupOf/files"
        val dst = "/data/data/$toPkg/files"
        val cmd = "am force-stop $toPkg; rm -rf $dst && cp -a $src $dst && chown -R $uid:$uid $dst && restorecon -R $dst"
        val ok = su(cmd).first == 0
        su("rm -rf $BACKUP_ROOT/$backupOf")
        ok
    } catch (e: Exception) {
        false
    }

    fun copyDirect(ctx: Context, from: String, to: String): Boolean = backup(from) && restore(ctx, from, to)
}
