package com.gumm.mobile

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Release(val tag: String, val notes: String, val apkName: String, val apkUrl: String, val size: Long)

object Updater {
    const val REPO = "DinoCDX/GUMMM"
    const val REPO_URL = "https://github.com/DinoCDX/GUMMM"
    const val PAGE_URL = "https://dinocdx.github.io/GUMMM/"

    private fun open(url: String): HttpURLConnection {
        var u = url
        repeat(5) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "GUMMM-updater")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            val code = c.responseCode
            if (code in 300..399) {
                u = c.getHeaderField("Location") ?: throw IllegalStateException("Bad redirect")
                c.disconnect()
            } else {
                if (code != 200) {
                    c.disconnect()
                    throw IllegalStateException("GitHub answered $code")
                }
                return c
            }
        }
        throw IllegalStateException("Too many redirects")
    }

    fun latest(): Release? {
        val c = open("https://api.github.com/repos/$REPO/releases/latest")
        val text = try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
        val json = JSONObject(text)
        val assets = json.optJSONArray("assets") ?: return null
        var best: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", true)) {
                best = a
                break
            }
        }
        if (best == null) return null
        return Release(
            json.optString("tag_name"),
            json.optString("body"),
            best.optString("name"),
            best.optString("browser_download_url"),
            best.optLong("size")
        )
    }

    private fun parts(v: String): List<Int> =
        v.trim().trimStart('v', 'V').split('.', '-', '_').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    fun isNewer(tag: String, current: String): Boolean {
        val a = parts(tag)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun download(ctx: Context, r: Release, progress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "update")
        dir.deleteRecursively()
        dir.mkdirs()
        val out = File(dir, "GUMMM-update.apk")
        val c = open(r.apkUrl)
        try {
            val total = if (c.contentLengthLong > 0) c.contentLengthLong else r.size
            var done = 0L
            var last = -1
            c.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != last) {
                                last = pct
                                progress(pct)
                            }
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        if (out.length() < 1024) throw IllegalStateException("Download is empty")
        return out
    }
}
