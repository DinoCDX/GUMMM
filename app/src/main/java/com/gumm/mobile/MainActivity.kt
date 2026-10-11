package com.gumm.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.zip.ZipFile
import java.util.UUID

class MainActivity : Activity() {

    private lateinit var store: Store
    private lateinit var keys: KeyManager
    private lateinit var shell: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var strip: TextView
    private lateinit var body: FrameLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var logView: TextView

    private var busy = false
    private var tab = 0
    private val tabNames = arrayOf("Play", "Mods", "Files", "Settings", "Log")
    private var currentGame: String? = null
    private var pendingGame: String? = null
    private var pendingApk: File? = null
    private var installingGame: String? = null
    private var restoreBackupFor: String? = null
    private var shownKey = ""
    private var pendingInstall: Pair<String, File>? = null
    private var pendingStamp: String? = null
    private var awaitingGame = false
    private var awaitingAccess = false
    private val syncPool = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val packageRegex = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    private val apkTypes = arrayOf("application/vnd.android.package-archive", "application/octet-stream")
    private val zipTypes = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

    companion object {
        const val REQ_ADD_APK = 1
        const val REQ_MOD_ZIP = 2
        const val REQ_MOD_DIR = 3
        const val REQ_KIT = 4
        const val REQ_UPDATE_APK = 5
        const val REQ_EXPORT_KEY = 6
        const val REQ_IMPORT_KEY = 7
        const val REQ_UNINSTALL = 8
        const val REQ_EDIT_FILE = 9
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppPrefs.init(this)
        Theme.apply(AppPrefs.light)
        setTheme(if (AppPrefs.light) R.style.AppThemeLight else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.bg
        window.navigationBarColor = Theme.bg
        if (AppPrefs.light) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        store = Store(this)
        keys = KeyManager(store)
        logView = tv(this, "", 12f, Theme.muted)
        val a = this
        shell = vbox(this).apply {
            setBackgroundColor(Theme.bg)
            fitsSystemWindows = true
        }
        topBar = hbox(this).apply { setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 8)) }
        strip = tv(this, "", 12f, Theme.muted).apply {
            visibility = View.GONE
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setBackgroundColor(Theme.card)
            setPadding(dp(a, 16), dp(a, 8), dp(a, 16), dp(a, 8))
            setOnClickListener { visibility = View.GONE }
        }
        body = FrameLayout(this)
        tabBar = hbox(this).apply {
            setBackgroundColor(Theme.card)
            visibility = View.GONE
        }
        shell.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        shell.addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        shell.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        shell.addView(tabBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(shell)
        Installer.onResult = { status, pkg, msg -> runOnUiThread { onInstallResult(status, pkg, msg) } }
        showLibrary()
        autoCheckUpdate()
        if (AppPrefs.reopenSettings) {
            AppPrefs.reopenSettings = false
            appSettings()
        }
    }

    override fun onResume() {
        super.onResume()
        val p = pendingInstall
        if (p != null && Installer.canInstall(this)) {
            pendingInstall = null
            val g = store.get(p.first)
            if (g != null) startInstall(g, p.second)
        }
        if (awaitingGame) {
            awaitingGame = false
            refreshCurrent()
        }
        if (awaitingAccess && ExternalMods.available()) {
            awaitingAccess = false
            currentGame?.let { scheduleSync(it) }
            refreshCurrent()
        }
    }

    override fun onDestroy() {
        Installer.onResult = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentGame != null || inAppSettings) {
            showLibrary()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_UNINSTALL) {
            afterUninstall()
            return
        }
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        val gid = currentGame
        when (requestCode) {
            REQ_ADD_APK -> addFromUri(uri)
            REQ_MOD_ZIP -> if (gid != null) importModZip(gid, uri)
            REQ_MOD_DIR -> if (gid != null) importModDir(gid, uri)
            REQ_KIT -> if (gid != null) importKit(gid, uri)
            REQ_UPDATE_APK -> if (gid != null) updateFromApk(gid, uri)
            REQ_EXPORT_KEY -> exportKey(uri)
            REQ_IMPORT_KEY -> importKey(uri)
            REQ_EDIT_FILE -> if (gid != null) replaceEdit(gid, uri)
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun appendLog(m: String) {
        logView.append(m + "\n")
        if (logView.length() > 20000) logView.text = logView.text.takeLast(10000)
        val error = m.startsWith("!")
        if (busy || error) {
            strip.text = m
            strip.setTextColor(if (error) Theme.danger else Theme.muted)
            strip.visibility = View.VISIBLE
        }
    }

    private fun statusOf(g: Game): String {
        if (g.builtStamp.isEmpty() || !Installer.isInstalled(this, g.builtPackage)) return "Not installed"
        return if (store.stamp(g) == g.builtStamp) "Up to date" else "Rebuild needed"
    }

    private fun scheduleSync(id: String) {
        val g = store.get(id) ?: return
        if (!g.modsExternal || !ExternalMods.available()) return
        syncPool.execute {
            try {
                val fresh = store.get(id)
                if (fresh != null) {
                    ExternalMods.sync(store, fresh) { m -> runOnUiThread { appendLog(m) } }
                }
            } catch (t: Throwable) {
                runOnUiThread { appendLog("! ${t.message ?: t.javaClass.simpleName}") }
            }
        }
    }

    private fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

    private fun refreshCurrent() {
        val id = currentGame
        if (id != null) showGame(id) else showLibrary()
    }

    private fun task(title: String, done: () -> Unit = {}, block: (log: (String) -> Unit) -> Unit) {
        if (busy) {
            toast("Another task is running")
            return
        }
        busy = true
        appendLog("> $title")
        Thread {
            try {
                block { m -> runOnUiThread { appendLog(m) } }
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                runOnUiThread { appendLog("! $msg") }
            }
            runOnUiThread {
                busy = false
                if (!strip.text.startsWith("!")) strip.visibility = View.GONE
                done()
            }
        }.start()
    }

    private fun screen(inner: View, key: String) {
        val old = body.getChildAt(0) as? ScrollView
        val keepY = if (key == shownKey && old != null) old.scrollY else 0
        shownKey = key
        val pad = dp(this, 16)
        val wrap = vbox(this).apply { setPadding(pad, pad / 2, pad, pad * 2) }
        wrap.addView(inner, fullWidth(this))
        val scroll = ScrollView(this)
        scroll.addView(wrap)
        body.removeAllViews()
        body.addView(scroll)
        if (keepY > 0) scroll.post { scroll.scrollTo(0, keepY) }
    }

    private fun setTop(title: String, sub: String?, back: Boolean, trailing: View?) {
        topBar.removeAllViews()
        if (back) {
            val b = tv(this, "\u2039", 32f, Theme.text, true)
            b.setPadding(0, 0, dp(this, 16), dp(this, 4))
            b.setOnClickListener { showLibrary() }
            topBar.addView(b)
        }
        val col = vbox(this)
        val t = tv(this, title, if (back) 18f else 28f, Theme.text, true)
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        col.addView(t)
        if (sub != null) {
            val st = tv(this, sub, 12f, Theme.muted)
            st.maxLines = 1
            st.ellipsize = TextUtils.TruncateAt.END
            col.addView(st)
        }
        topBar.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) topBar.addView(trailing)
    }

    private fun statusChip(g: Game): View {
        val s = statusOf(g)
        val color = when (s) {
            "Up to date" -> Theme.success
            "Rebuild needed" -> Theme.warn
            else -> Theme.muted
        }
        return chip(this, s, color)
    }

    private var logMode = 0

    private var fbGame = ""
    private var fbSource = 0
    private var fbDir = ""
    private var fbQuery = ""
    private var fbContent = false
    private var fbSearching = false
    private var fbLoading = false
    private var fbHits: List<Hit>? = null
    private var fbHitsBusy = false
    private var fbShown = 300
    private val fbCache = HashMap<String, List<FEntry>>()
    private var editTarget: String? = null
    private val fbNames = arrayOf("Original", "Modded", "Game pack")

    private fun filesTab(root: LinearLayout, g: Game) {
        if (fbGame != g.id) {
            fbGame = g.id
            fbSource = 0
            fbDir = ""
            fbQuery = ""
            fbSearching = false
            fbHits = null
            fbCache.clear()
        }
        val sources = hbox(this)
        for (i in fbNames.indices) {
            sources.addView(
                pill(this, fbNames[i], if (i == fbSource) Theme.accent else Theme.input) {
                    fbSource = i
                    fbDir = ""
                    fbSearching = false
                    fbHits = null
                    fbShown = 300
                    showGame(g.id)
                },
                lp(this, left = if (i == 0) 0 else 6, weight = 1f, width = 0)
            )
        }
        root.addView(sources)
        val note = when (fbSource) {
            0 -> "Files of the game as you added it."
            1 -> "Files of the last build, with the loader and mods added."
            else -> "Files inside the game's .pck, as the game sees them in res://."
        }
        root.addView(tv(this, note, 12f, Theme.muted), lp(this, top = 6))
        val key = g.id + ":" + fbSource
        val entries = fbCache[key]
        if (entries == null) {
            root.addView(tv(this, "Reading files...", 14f, Theme.muted), lp(this, top = 16))
            loadFileIndex(g, key)
            return
        }
        if (entries.isEmpty()) {
            val c = card(this)
            val msg = when (fbSource) {
                1 -> "Nothing built yet. Build the game first."
                2 -> "No .pck found. This game keeps its files directly in the APK, see Original."
                else -> "No files found."
            }
            c.addView(tv(this, msg, 13f, Theme.muted))
            root.addView(c, fullWidth(this, 12))
            return
        }
        val edited = FileTools.editedPaths(FileTools.editsZip(store, g.id))
        val searchBox = field(this, "Search file names", fbQuery) { fbQuery = it }
        root.addView(searchBox, fullWidth(this, 12))
        root.addView(toggleRow(this, "Search inside text files", "Slower, looks at scripts, scenes and configs", fbContent) { fbContent = it })
        val actions = hbox(this)
        actions.addView(primary(this, "Search", Theme.accent) { runFileSearch(g, entries) }, lp(this, weight = 1f, width = 0))
        if (fbSearching) {
            actions.addView(pill(this, "Clear", Theme.input) {
                fbSearching = false
                fbQuery = ""
                fbHits = null
                showGame(g.id)
            }, lp(this, left = 8, weight = 1f, width = 0))
        }
        root.addView(actions, fullWidth(this, 8))

        if (fbSearching && fbQuery.isNotBlank()) {
            val q = fbQuery.lowercase()
            val byName = entries.filter { it.path.lowercase().contains(q) }
            root.addView(section(this, "Names matching (${byName.size})"))
            byName.take(fbShown).forEach { root.addView(fileRow(g, it, edited)) }
            if (fbContent) {
                root.addView(section(this, "Text matches"))
                val hits = fbHits
                if (hits == null) {
                    root.addView(tv(this, "Searching...", 13f, Theme.muted))
                } else if (hits.isEmpty()) {
                    root.addView(tv(this, "No matches", 13f, Theme.muted))
                } else {
                    for (h in hits) {
                        val c = card(this)
                        c.addView(tv(this, h.entry.path + ":" + h.line, 12f, Theme.accent))
                        c.addView(tv(this, h.text, 12f, Theme.text).apply { typeface = Typeface.MONOSPACE })
                        c.setOnClickListener { openFile(g, h.entry) }
                        root.addView(c, fullWidth(this, 8))
                    }
                }
            }
            return
        }

        val folders = java.util.TreeMap<String, Int>()
        val files = ArrayList<FEntry>()
        for (e in entries) {
            if (!e.path.startsWith(fbDir)) continue
            val rest = e.path.substring(fbDir.length)
            val i = rest.indexOf('/')
            if (i < 0) files.add(e) else folders[rest.substring(0, i)] = (folders[rest.substring(0, i)] ?: 0) + 1
        }
        files.sortBy { it.path.lowercase() }
        val head = card(this)
        head.addView(tv(this, "/" + fbDir, 13f, Theme.text, true))
        head.addView(tv(this, entries.size.toString() + " files in this view", 12f, Theme.muted))
        root.addView(head, fullWidth(this, 12))
        val bar = hbox(this)
        if (fbDir.isNotEmpty()) {
            bar.addView(pill(this, "Up", Theme.input) {
                fbDir = fbDir.dropLast(1).substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
                fbShown = 300
                showGame(g.id)
            }, lp(this, weight = 1f, width = 0))
        }
        bar.addView(pill(this, "Export this folder", Theme.input) {
            val list = entries.filter { it.path.startsWith(fbDir) }
            exportEntries(g, list, fbNames[fbSource])
        }, lp(this, left = if (fbDir.isNotEmpty()) 8 else 0, weight = 1f, width = 0))
        root.addView(bar, fullWidth(this, 8))
        var shown = 0
        for ((name, count) in folders) {
            if (shown >= fbShown) break
            shown++
            val c = card(this)
            c.addView(tv(this, name + "/", 15f, Theme.accent, true))
            c.addView(tv(this, "$count files", 12f, Theme.muted))
            c.setOnClickListener {
                fbDir = fbDir + name + "/"
                fbShown = 300
                showGame(g.id)
            }
            root.addView(c, fullWidth(this, 8))
        }
        for (e in files) {
            if (shown >= fbShown) break
            shown++
            root.addView(fileRow(g, e, edited))
        }
        if (folders.size + files.size > shown) {
            root.addView(pill(this, "Show more", Theme.input) {
                fbShown += 300
                showGame(g.id)
            }, fullWidth(this, 8))
        }
    }

    private fun fileRow(g: Game, e: FEntry, edited: Set<String>): View {
        val c = card(this)
        val name = if (fbDir.isEmpty() || fbSearching) e.path else e.path.substring(fbDir.length)
        val res = FileTools.resPath(e)
        val mark = if (res != null && res in edited) "  (in My edits)" else ""
        c.addView(tv(this, name + mark, 14f, Theme.text))
        c.addView(tv(this, ModInfo.formatSize(e.size) + if (e.encrypted) " \u00b7 encrypted" else "", 12f, Theme.muted))
        c.setOnClickListener { openFile(g, e) }
        return c.also { it.layoutParams = fullWidth(this, 8) }
    }

    private fun loadFileIndex(g: Game, key: String) {
        if (fbLoading) return
        fbLoading = true
        val id = g.id
        val source = fbSource
        Thread {
            val list = try {
                when (source) {
                    0 -> FileTools.indexApks(FileTools.originalFiles(store, id))
                    1 -> FileTools.indexApks(FileTools.moddedFiles(store, id))
                    else -> FileTools.indexPcks(FileTools.originalFiles(store, id))
                }.sortedBy { it.path.lowercase() }
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                fbLoading = false
                fbCache[key] = list
                if (currentGame == id && tab == 2) showGame(id)
            }
        }.start()
    }

    private fun runFileSearch(g: Game, entries: List<FEntry>) {
        if (fbQuery.isBlank()) {
            toast("Type something to search")
            return
        }
        fbSearching = true
        fbShown = 300
        fbHits = null
        if (fbContent && !fbHitsBusy) {
            fbHitsBusy = true
            val q = fbQuery
            val id = g.id
            Thread {
                val hits = FileTools.searchContent(entries, q, 200) { false }
                runOnUiThread {
                    fbHitsBusy = false
                    fbHits = hits
                    if (currentGame == id && tab == 2) showGame(id)
                }
            }.start()
        }
        showGame(g.id)
    }

    private fun openFile(g: Game, e: FEntry) {
        Thread {
            val data = try {
                FileTools.Reader().use { it.read(e, 262144) }
            } catch (ex: Exception) {
                ByteArray(0)
            }
            runOnUiThread { showFileDialog(g, e, data) }
        }.start()
    }

    private fun showFileDialog(g: Game, e: FEntry, data: ByteArray) {
        val pad = dp(this, 16)
        val box = vbox(this).apply { setPadding(pad, pad, pad, pad) }
        box.addView(tv(this, e.path, 13f, Theme.accent, true))
        val truncated = e.size > data.size && !e.encrypted
        box.addView(tv(this, ModInfo.formatSize(e.size) + if (truncated) " \u00b7 showing the first 256 KB" else "", 12f, Theme.muted))
        var editor: EditText? = null
        val res = FileTools.resPath(e)
        if (e.encrypted) {
            box.addView(tv(this, "This file is encrypted and can't be read.", 13f, Theme.danger), lp(this, top = 10))
        } else if (FileTools.isImage(e.path)) {
            val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
            if (bmp != null) {
                val iv = ImageView(this)
                iv.adjustViewBounds = true
                iv.setImageBitmap(bmp)
                box.addView(iv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(this@MainActivity, 10) })
                box.addView(tv(this, bmp.width.toString() + " x " + bmp.height, 12f, Theme.muted))
            } else {
                box.addView(tv(this, FileTools.hexDump(data), 11f, Theme.text).apply { typeface = Typeface.MONOSPACE }, lp(this, top = 10))
            }
        } else if (FileTools.looksText(e.path, data)) {
            val ed = EditText(this)
            ed.setText(String(data, Charsets.UTF_8))
            ed.typeface = Typeface.MONOSPACE
            ed.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            ed.setTextColor(Theme.text)
            ed.background = rounded(Theme.input, dp(this, 8).toFloat())
            ed.setPadding(dp(this, 10), dp(this, 10), dp(this, 10), dp(this, 10))
            ed.gravity = Gravity.TOP
            ed.setHorizontallyScrolling(true)
            box.addView(ed, fullWidth(this, 10))
            editor = ed
        } else {
            box.addView(tv(this, FileTools.hexDump(data), 11f, Theme.text).apply { typeface = Typeface.MONOSPACE }, lp(this, top = 10))
        }
        val dialog = AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(box) }).setNegativeButton("Close", null).create()
        val row = hbox(this)
        row.addView(pill(this, "Export", Theme.input) {
            exportEntries(g, listOf(e), fbNames[fbSource])
        }, lp(this, weight = 1f, width = 0))
        if (res != null && fbSource != 1 && !e.encrypted) {
            row.addView(pill(this, "Copy to My edits", Theme.input) {
                dialog.dismiss()
                copyToEdits(g.id, e, res)
            }, lp(this, left = 8, weight = 1f, width = 0))
        }
        box.addView(row, fullWidth(this, 12))
        if (res != null && fbSource != 1 && !e.encrypted) {
            val ed = editor
            if (ed != null && !truncated) {
                box.addView(primary(this, "Save my changes to My edits", Theme.accent) {
                    val bytes = ed.text.toString().toByteArray(Charsets.UTF_8)
                    dialog.dismiss()
                    saveEdit(g.id, res) { it.write(bytes) }
                }, fullWidth(this, 8))
            }
            box.addView(pill(this, "Replace with a file from my phone", Theme.input) {
                editTarget = res
                dialog.dismiss()
                pickDocument(REQ_EDIT_FILE, arrayOf("*/*"))
            }, fullWidth(this, 8))
            box.addView(tv(this, "Edits go into a resource pack called My edits. It loads last, so it wins over other mods.", 12f, Theme.muted), lp(this, top = 8))
        } else if (fbSource != 1) {
            box.addView(tv(this, "This file is not a game resource, so a mod can't replace it.", 12f, Theme.muted), lp(this, top = 8))
        }
        dialog.show()
    }

    private fun ensureEditsMod(id: String) {
        val g = store.get(id) ?: return
        if (g.mods.any { it.folder == FileTools.EDITS_FOLDER }) return
        val mod = Mod(
            id = FileTools.EDITS_FOLDER,
            name = "My edits",
            version = "",
            author = "",
            description = "Files edited or replaced in GUMMM",
            folder = FileTools.EDITS_FOLDER,
            enabled = true,
            kind = ModKinds.PACK
        )
        store.update(id) { it.copy(mods = it.mods + mod) }
    }

    private fun saveEdit(id: String, res: String, writer: (java.io.OutputStream) -> Unit) {
        try {
            FileTools.putEdit(FileTools.editsZip(store, id), res, writer)
            ensureEditsMod(id)
            scheduleSync(id)
            toast("Saved to My edits. Rebuild to apply.")
            showGame(id)
        } catch (ex: Exception) {
            appendLog("! " + (ex.message ?: ex.javaClass.simpleName))
        }
    }

    private fun copyToEdits(id: String, e: FEntry, res: String) {
        task("Copying to My edits", done = {
            scheduleSync(id)
            showGame(id)
        }) { log ->
            FileTools.putEdit(FileTools.editsZip(store, id), res) { out ->
                FileTools.Reader().use { it.copyTo(e, out) }
            }
            ensureEditsMod(id)
            log("Copied $res to My edits")
        }
    }

    private fun replaceEdit(id: String, uri: Uri) {
        val res = editTarget ?: return
        editTarget = null
        try {
            val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open file")
            saveEdit(id, res) { out -> input.use { it.copyTo(out, 1 shl 16) } }
        } catch (ex: Exception) {
            appendLog("! " + (ex.message ?: ex.javaClass.simpleName))
        }
    }

    private fun exportEntries(g: Game, list: List<FEntry>, source: String) {
        if (!ExternalMods.available()) {
            toast("Allow All files access first")
            awaitingAccess = true
            ExternalMods.openSettings(this)
            return
        }
        val root = FileTools.exportRoot(g.title, source)
        task("Exporting ${list.size} files") { log ->
            var n = 0
            FileTools.Reader().use { r ->
                for (e in list) {
                    if (e.encrypted) continue
                    val dest = File(root, e.path)
                    val canonical = dest.canonicalPath
                    if (!canonical.startsWith(root.canonicalPath + File.separator)) continue
                    dest.parentFile?.mkdirs()
                    dest.outputStream().buffered().use { out -> r.copyTo(e, out) }
                    n++
                }
            }
            log("Exported $n files to Download/GUMMM/" + FileTools.safe(g.title) + "/" + source)
        }
    }

    private fun logTab(root: LinearLayout, g: Game) {
        val names = arrayOf("Build log", "Loader log")
        val modes = hbox(this)
        for (i in names.indices) {
            val color = if (i == logMode) Theme.accent else Theme.input
            modes.addView(pill(this, names[i], color) {
                if (logMode != i) {
                    logMode = i
                    showGame(g.id)
                }
            }, lp(this, left = if (i == 0) 0 else 8))
        }
        root.addView(modes, lp(this, top = 12))
        root.addView(if (logMode == 0) logCard() else loaderLogCard(g))
    }

    private fun loaderLogCard(g: Game): View {
        val c = card(this)
        val head = hbox(this)
        head.addView(tv(this, "Loader log", 14f, Theme.muted, true), lp(this, weight = 1f, width = 0))
        val refresh = tv(this, "Refresh", 13f, Theme.accent)
        refresh.setPadding(dp(this, 10), dp(this, 4), dp(this, 10), dp(this, 4))
        refresh.setOnClickListener { showGame(g.id) }
        head.addView(refresh)
        c.addView(head)
        val body = tv(this, loaderLogText(g), 12f, Theme.text)
        body.setTextIsSelectable(true)
        c.addView(body, lp(this, top = 8))
        return c
    }

    private fun loaderLogText(g: Game): String {
        if (!g.modsExternal) return "Loader log is only written with external mod loading. Turn it on in Settings."
        val f = ExternalMods.logFile(g.targetPackage())
        return try {
            if (f.isFile) f.readText().takeLast(6000) else "No log yet. Launch the game once."
        } catch (e: Exception) {
            "Cannot read ${f.path}"
        }
    }

    private fun logCard(): View {
        (logView.parent as? ViewGroup)?.removeView(logView)
        val c = card(this)
        val head = hbox(this)
        head.addView(tv(this, "Activity log", 14f, Theme.muted, true), lp(this, weight = 1f, width = 0))
        val clear = tv(this, "Clear", 13f, Theme.accent)
        clear.setPadding(dp(this, 10), dp(this, 4), dp(this, 10), dp(this, 4))
        clear.setOnClickListener {
            logView.text = ""
            refreshCurrent()
        }
        head.addView(clear)
        c.addView(head)
        c.addView(logView)
        return c
    }

    private fun openGame(id: String) {
        tab = 0
        showGame(id)
    }

    private var inAppSettings = false

    private fun showLibrary() {
        currentGame = null
        inAppSettings = false
        setTop("GUMMM", "Mod Godot games", false, null)
        tabBar.visibility = View.GONE
        val root = vbox(this)
        root.addView(primary(this, "Add game") { chooseSource() }, fullWidth(this, 4))
        val games = store.loadGames()
        if (games.isEmpty()) {
            val c = card(this)
            c.addView(tv(this, "No games yet", 16f, Theme.text, true))
            c.addView(tv(this, "Add an installed game or pick an APK.", 13f, Theme.muted), lp(this, top = 4))
            root.addView(c)
        }
        for (g in games) {
            val c = card(this)
            c.addView(gameRow(g))
            root.addView(c)
        }
        if (logView.length() > 0) root.addView(logCard())
        val bottom = hbox(this)
        bottom.addView(pill(this, "App settings", Theme.input) { appSettings() })
        bottom.addView(pill(this, "Signing key", Theme.input) { showKeyDialog() }, lp(this, left = 8))
        root.addView(bottom, lp(this, top = 16))
        screen(root, "library")
    }

    private fun setLight(v: Boolean) {
        if (AppPrefs.light == v) return
        AppPrefs.light = v
        AppPrefs.reopenSettings = true
        recreate()
    }

    private fun appSettings() {
        currentGame = null
        inAppSettings = true
        setTop("Settings", null, true, null)
        tabBar.visibility = View.GONE
        val root = vbox(this)
        root.addView(section(this, "Appearance"))
        val look = card(this)
        val modes = hbox(this)
        modes.addView(pill(this, "Dark", if (!AppPrefs.light) Theme.accent else Theme.input) { setLight(false) }, lp(this, weight = 1f, width = 0))
        modes.addView(pill(this, "Light", if (AppPrefs.light) Theme.accent else Theme.input) { setLight(true) }, lp(this, left = 8, weight = 1f, width = 0))
        look.addView(modes)
        root.addView(look)

        root.addView(section(this, "Defaults for new games"))
        val d = card(this)
        d.addView(toggleRow(this, "Install as a separate app", "Keep the original game installed too", AppPrefs.renamePackage) { AppPrefs.renamePackage = it })
        d.addView(divider(this), dividerParams(this))
        d.addView(toggleRow(this, "Add (Modded) to the app name", null, AppPrefs.labelSuffix) { AppPrefs.labelSuffix = it })
        d.addView(divider(this), dividerParams(this))
        d.addView(toggleRow(this, "Copy save data", "Needs root. Otherwise saves start empty.", AppPrefs.copyUserData) { AppPrefs.copyUserData = it })
        d.addView(divider(this), dividerParams(this))
        d.addView(toggleRow(this, "Load mods from an external folder", "Needs All files access for the game too.", AppPrefs.modsExternal) { AppPrefs.modsExternal = it })
        d.addView(divider(this), dividerParams(this))
        d.addView(toggleRow(this, "In-game mod button", "G button to switch mods while playing", AppPrefs.overlay) { AppPrefs.overlay = it })
        root.addView(d)
        root.addView(tv(this, "Applies to games you add from now on. Existing games keep their own settings.", 12f, Theme.muted), lp(this, top = 8))

        root.addView(section(this, "About"))
        val about = card(this)
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
        about.addView(infoRow(this, "App", "GUMMM"))
        about.addView(infoRow(this, "Version", version))
        about.addView(infoRow(this, "Creator", "DinoCDX"))
        about.addView(infoRow(this, "Repository", "DinoCDX/GUMMM"))
        val gh = tv(this, "github.com/DinoCDX/GUMMM", 14f, Theme.accent)
        gh.setPadding(0, dp(this, 10), 0, dp(this, 2))
        gh.setOnClickListener { openLink(Updater.REPO_URL) }
        about.addView(gh)
        val page = tv(this, "dinocdx.github.io/GUMMM", 14f, Theme.accent)
        page.setPadding(0, dp(this, 8), 0, dp(this, 2))
        page.setOnClickListener { openLink(Updater.PAGE_URL) }
        about.addView(page)
        about.addView(divider(this), dividerParams(this))
        about.addView(toggleRow(this, "Check for updates on start", "Looks for a new GitHub release", AppPrefs.autoUpdate) { AppPrefs.autoUpdate = it })
        about.addView(pill(this, "Check for updates now", Theme.input) { checkUpdate(true) }, lp(this, top = 8))
        root.addView(about)
        screen(root, "app-settings")
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("No browser found")
        }
    }

    private fun currentVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }

    private fun autoCheckUpdate() {
        if (!AppPrefs.autoUpdate) return
        val now = System.currentTimeMillis()
        if (now - AppPrefs.lastUpdateCheck < 6L * 3600 * 1000) return
        checkUpdate(false)
    }

    private fun checkUpdate(manual: Boolean) {
        if (manual) toast("Checking for updates")
        Thread {
            try {
                val r = Updater.latest()
                AppPrefs.lastUpdateCheck = System.currentTimeMillis()
                runOnUiThread {
                    when {
                        isFinishing || isDestroyed -> {}
                        r == null -> if (manual) toast("No APK found in the latest release")
                        Updater.isNewer(r.tag, currentVersion()) -> askUpdate(r)
                        else -> if (manual) toast("You are on the latest version")
                    }
                }
            } catch (e: Exception) {
                if (manual) runOnUiThread { toast("Update check failed: " + (e.message ?: e.javaClass.simpleName)) }
            }
        }.start()
    }

    private fun askUpdate(r: Release) {
        val notes = r.notes.trim().take(600)
        val msg = "Installed: " + currentVersion() + "\nLatest: " + r.tag + " (" + ModInfo.formatSize(r.size) + ")" +
            if (notes.isNotEmpty()) "\n\n" + notes else ""
        AlertDialog.Builder(this)
            .setTitle("Update available")
            .setMessage(msg)
            .setPositiveButton("Update") { _, _ -> downloadUpdate(r) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun downloadUpdate(r: Release) {
        if (!Installer.canInstall(this)) {
            toast("Allow installs from GUMMM, then update again")
            Installer.openInstallSettings(this)
            return
        }
        var apk: File? = null
        task("Downloading ${r.tag}", done = {
            val f = apk
            if (f != null) {
                installingGame = null
                safeInstall(f)
            }
        }) { log ->
            var shown = -10
            apk = Updater.download(this, r) { pct ->
                if (pct >= shown + 10) {
                    shown = pct
                    log("Downloading $pct%")
                }
            }
            log("Download done")
        }
    }

    private fun gameRow(g: Game): View {
        val row = hbox(this)
        val icon = ImageView(this)
        val f = store.iconFile(g.id)
        if (f.exists()) icon.setImageBitmap(BitmapFactory.decodeFile(f.path))
        row.addView(icon, LinearLayout.LayoutParams(dp(this, 52), dp(this, 52)))
        val col = vbox(this)
        col.addView(tv(this, g.title, 16f, Theme.text, true))
        val version = g.godotVersion.ifBlank { "?" }
        val active = g.mods.count { it.enabled }
        col.addView(tv(this, "Godot $version \u00b7 $active of ${g.mods.size} mods on", 12f, Theme.muted))
        col.addView(statusChip(g), lp(this, top = 6))
        row.addView(col, lp(this, left = 12, weight = 1f, width = 0))
        if (Installer.isInstalled(this, g.builtPackage)) {
            row.addView(pill(this, "Play") { Installer.launch(this, g.builtPackage) })
        }
        row.setOnClickListener { openGame(g.id) }
        return row
    }

    private fun chooseSource() {
        AlertDialog.Builder(this)
            .setTitle("Add game")
            .setItems(arrayOf("Installed Godot game", "APK file")) { _, i ->
                if (i == 0) pickInstalled() else pickDocument(REQ_ADD_APK, apkTypes)
            }
            .show()
    }

    private fun pickDocument(code: Int, types: Array<String>) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(Intent.EXTRA_MIME_TYPES, types)
        @Suppress("DEPRECATION")
        startActivityForResult(i, code)
    }

    private fun pickTree(code: Int) {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), code)
    }

    private fun pickInstalled() = task("Scanning installed apps") { _ ->
        val pm = packageManager
        val all = pm.getInstalledApplications(0)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != packageName }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
        val apps = all.filter {
            GodotInspector.looksLikeGodotAny(File(it.sourceDir), (it.splitSourceDirs ?: emptyArray()).map { s -> File(s) })
        }
        runOnUiThread {
            if (apps.isEmpty()) {
                showAppChoice("No Godot games detected. All apps", all)
            } else {
                val names = apps.map { pm.getApplicationLabel(it).toString() + "\n" + it.packageName }.toMutableList()
                names.add("Not listed? Show all apps")
                AlertDialog.Builder(this)
                    .setTitle("Installed Godot games")
                    .setItems(names.toTypedArray()) { _, i ->
                        if (i < apps.size) addInstalled(apps[i]) else showAppChoice("All apps", all)
                    }
                    .show()
            }
        }
    }

    private fun showAppChoice(title: String, apps: List<ApplicationInfo>) {
        val pm = packageManager
        val names = apps.map { pm.getApplicationLabel(it).toString() + "\n" + it.packageName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(names) { _, i -> addInstalled(apps[i]) }
            .show()
    }

    private fun addInstalled(ai: ApplicationInfo) {
        val id = newId()
        task("Copying ${packageManager.getApplicationLabel(ai)}", done = { showLibrary() }) { log ->
            val dest = store.originalApk(id)
            File(ai.sourceDir).copyTo(dest, overwrite = true)
            copySplits(id, ai, log)
            registerSafely(id, dest, log)
        }
    }

    private fun copySplits(id: String, ai: ApplicationInfo, log: (String) -> Unit) {
        val dir = store.splitsDir(id)
        dir.deleteRecursively()
        val list = ai.splitSourceDirs ?: return
        if (list.isEmpty()) return
        dir.mkdirs()
        for (p in list) File(p).copyTo(File(dir, File(p).name), overwrite = true)
        log("Copied ${list.size} split APKs")
    }

    private fun extractBundle(file: File, id: String, base: File, log: (String) -> Unit): Boolean {
        return try {
            ZipFile(file).use { z ->
                if (z.getEntry("AndroidManifest.xml") != null) return@use false
                val apks = z.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".apk", true) }.toList()
                if (apks.isEmpty()) return@use false
                fun leaf(n: String) = n.substringAfterLast('/')
                fun isSplit(n: String): Boolean {
                    val l = leaf(n).lowercase()
                    return l.startsWith("config.") || l.startsWith("split_") || l.contains("split_config")
                }
                val baseEntry = apks.firstOrNull { leaf(it.name).equals("base.apk", true) }
                    ?: apks.filter { !isSplit(it.name) }.maxByOrNull { it.size }
                    ?: apks.maxByOrNull { it.size }
                if (baseEntry == null) return@use false
                z.getInputStream(baseEntry).use { i -> base.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
                val dir = store.splitsDir(id)
                dir.deleteRecursively()
                dir.mkdirs()
                var n = 0
                for (e in apks) {
                    if (e.name == baseEntry.name) continue
                    z.getInputStream(e).use { i -> File(dir, leaf(e.name)).outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
                    n++
                }
                log("Bundle: base APK plus $n split APKs")
                true
            }
        } catch (e: java.util.zip.ZipException) {
            false
        }
    }

    private fun addFromUri(uri: Uri) {
        val id = newId()
        task("Reading APK", done = { showLibrary() }) { log ->
            val dest = store.originalApk(id)
            val tmp = File(cacheDir, "pick_" + System.nanoTime())
            try {
                val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open file")
                input.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
                if (!extractBundle(tmp, id, dest, log)) tmp.copyTo(dest, overwrite = true)
            } finally {
                tmp.delete()
            }
            registerSafely(id, dest, log)
        }
    }

    private fun registerSafely(id: String, apk: File, log: (String) -> Unit) {
        try {
            register(id, apk, log)
        } catch (t: Throwable) {
            store.gameDir(id).deleteRecursively()
            throw t
        }
    }

    private fun register(id: String, apk: File, log: (String) -> Unit) {
        val pm = packageManager
        val info = pm.getPackageArchiveInfo(apk.path, 0) ?: throw IllegalStateException("Not a valid APK")
        val ai = info.applicationInfo ?: throw IllegalStateException("APK has no application")
        ai.sourceDir = apk.path
        ai.publicSourceDir = apk.path
        val title = ai.loadLabel(pm).toString()
        saveIcon(ai.loadIcon(pm), store.iconFile(id))
        val gi = GodotInspector.inspectAll(apk, store.splitFiles(id))
        if (!gi.isGodot) {
            log("Warning: Godot engine library not found, this may not be a Godot game")
            log(GodotInspector.describe(apk))
            for (sp in store.splitFiles(id)) log(GodotInspector.describe(sp))
        }
        val scene = GodotInspector.detectMainSceneAll(apk, store.splitFiles(id)) ?: ""
        if (store.defaultKit.isDirectory) {
            store.defaultKit.copyRecursively(store.kitDir(id), overwrite = true)
        }
        val game = Game(
            id = id,
            title = title,
            originalPackage = info.packageName,
            godotVersion = gi.version,
            mainScene = scene,
            kitMainScene = "",
            renamePackage = AppPrefs.renamePackage,
            customPackage = info.packageName + ".gumm",
            labelSuffix = AppPrefs.labelSuffix,
            copyUserData = AppPrefs.copyUserData,
            modsExternal = AppPrefs.modsExternal && ExternalMods.supported(),
            customLoader = false,
            overlay = AppPrefs.overlay,
            kitModsPath = "",
            builtStamp = "",
            builtPackage = "",
            lastBuildMs = 0L,
            mods = emptyList()
        )
        store.add(game)
        log("Added $title (${info.packageName}), Godot ${gi.version.ifEmpty { "?" }}")
        if (scene.isEmpty()) log("Main scene not detected, set it manually")
    }

    private fun saveIcon(d: Drawable, file: File) {
        val size = 144
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bmp))
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun showGame(id: String) {
        val g = store.get(id)
        if (g == null) {
            showLibrary()
            return
        }
        currentGame = id
        pendingGame = id
        setTop(g.title, g.originalPackage, true, statusChip(g))
        buildTabs(g)
        val root = vbox(this)
        when (tab) {
            0 -> playTab(root, g)
            1 -> modsTab(root, g)
            2 -> filesTab(root, g)
            3 -> settingsTab(root, g)
            else -> logTab(root, g)
        }
        screen(root, "$id:$tab" + (if (tab == 2) ":$fbSource:$fbDir:$fbSearching" else ""))
    }

    private fun buildTabs(g: Game) {
        tabBar.removeAllViews()
        tabBar.visibility = View.VISIBLE
        for (i in tabNames.indices) {
            val selected = i == tab
            val cell = vbox(this)
            val bar = View(this)
            bar.setBackgroundColor(if (selected) Theme.accent else Color.TRANSPARENT)
            cell.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 3)))
            val label = tv(this, tabNames[i], 14f, if (selected) Theme.accent else Theme.muted, selected)
            label.gravity = Gravity.CENTER
            label.setPadding(0, dp(this, 14), 0, dp(this, 14))
            cell.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            cell.setOnClickListener {
                if (tab != i) {
                    tab = i
                    showGame(g.id)
                }
            }
            tabBar.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun playTab(root: LinearLayout, g: Game) {
        val status = statusOf(g)
        val installed = Installer.isInstalled(this, g.builtPackage)
        val head = card(this)
        val top = hbox(this)
        val icon = ImageView(this)
        val f = store.iconFile(g.id)
        if (f.exists()) icon.setImageBitmap(BitmapFactory.decodeFile(f.path))
        top.addView(icon, LinearLayout.LayoutParams(dp(this, 64), dp(this, 64)))
        val col = vbox(this)
        col.addView(tv(this, g.title, 18f, Theme.text, true))
        col.addView(tv(this, "Godot ${g.godotVersion.ifBlank { "unknown" }}", 12f, Theme.muted))
        top.addView(col, lp(this, left = 14, weight = 1f, width = 0))
        head.addView(top)
        root.addView(head)
        root.addView(primary(this, if (installed) "Rebuild and install" else "Build and install") { build(g.id) }, fullWidth(this, 14))
        val hint = when (status) {
            "Up to date" -> "No changes since the last build."
            "Rebuild needed" -> "Changed since the last build. Rebuild to apply."
            else -> "Builds a modded copy and installs it."
        }
        root.addView(tv(this, hint, 12f, Theme.muted), lp(this, top = 6))
        if (installed) {
            val row = hbox(this)
            row.addView(primary(this, "Play", Theme.success) { Installer.launch(this, g.builtPackage) }, lp(this, weight = 1f, width = 0))
            row.addView(primary(this, "Uninstall", Theme.input) { launchUninstall(g.builtPackage) }, lp(this, left = 8, weight = 1f, width = 0))
            root.addView(row, fullWidth(this, 10))
        }
        root.addView(section(this, "At a glance"))
        val glance = card(this)
        val on = g.mods.count { it.enabled }
        glance.addView(infoRow(this, "Mods", "$on of ${g.mods.size} on"))
        glance.addView(infoRow(this, "Mod loading", if (g.modsExternal) "External folder, no rebuild" else "Packed into the app"))
        glance.addView(infoRow(this, "In-game button", if (g.overlay && !g.customLoader) "On" else "Off"))
        glance.addView(infoRow(this, "Installs as", g.targetPackage()))
        root.addView(glance)
        root.addView(pill(this, "Update game from a new APK", Theme.input) { pickDocument(REQ_UPDATE_APK, apkTypes) }, lp(this, top = 14))
    }

    private fun modsTab(root: LinearLayout, g: Game) {
        if (g.modsExternal) {
            if (!ExternalMods.supported()) {
                val c = card(this)
                c.addView(tv(this, "External mods need Android 11+. Turn them off in Settings to pack mods into the app.", 13f, Theme.danger))
                root.addView(c)
            } else if (!ExternalMods.available()) {
                val c = card(this)
                c.addView(tv(this, "All files access needed", 15f, Theme.text, true))
                c.addView(tv(this, "Needed to copy mods into the game's media folder.", 12f, Theme.muted), lp(this, top = 2))
                c.addView(pill(this, "Grant access") {
                    awaitingAccess = true
                    ExternalMods.openSettings(this)
                }, lp(this, top = 8))
                root.addView(c)
            }
        }
        if (g.modsExternal && ExternalMods.available() && g.builtPackage.isNotBlank() && Installer.isInstalled(this, g.builtPackage)) {
            val ok = ExternalMods.gameAccess(this, g.builtPackage)
            val requests = ExternalMods.gameRequests(this, g.builtPackage)
            if (requests == false) {
                val c = card(this)
                c.addView(tv(this, "Game file access", 15f, Theme.text, true))
                c.addView(tv(this, "The installed game doesn't request All files access, so Android greys the switch. Rebuild and install, then check the Log.", 12f, Theme.muted), lp(this, top = 2))
                c.addView(primary(this, "Rebuild and install") { build(g.id) }, fullWidth(this, 8))
                root.addView(c)
            } else if (ok != true) {
                val c = card(this)
                c.addView(tv(this, "Game file access", 15f, Theme.text, true))
                val msg = if (ok == null) "Could not check. If mods don't load, allow All files access for the game." else "Not allowed yet. The game needs All files access to read external mods."
                c.addView(tv(this, msg, 12f, Theme.muted), lp(this, top = 2))
                c.addView(pill(this, "Grant") {
                    awaitingGame = true
                    ExternalMods.openSettings(this, g.builtPackage)
                }, lp(this, top = 8))
                root.addView(c)
            }
        }
        if (statusOf(g) == "Rebuild needed") {
            val c = card(this)
            c.addView(tv(this, "Changes need a rebuild", 15f, Theme.warn, true))
            c.addView(primary(this, "Rebuild and install") { build(g.id) }, fullWidth(this, 8))
            root.addView(c)
        }
        val actions = hbox(this)
        actions.addView(primary(this, "Import file", Theme.accent) { pickDocument(REQ_MOD_ZIP, zipTypes) }, lp(this, weight = 1f, width = 0))
        actions.addView(primary(this, "Import folder", Theme.input) { pickTree(REQ_MOD_DIR) }, lp(this, left = 8, weight = 1f, width = 0))
        root.addView(actions, fullWidth(this, 12))
        if (g.mods.isEmpty()) {
            val c = card(this)
            c.addView(tv(this, "No mods yet", 16f, Theme.text, true))
            c.addView(tv(this, "Import a mod zip, .pck or folder. Supported: GUMM mods (mod.cfg and mod.gd) and resource packs. Godot Mod Loader mods are experimental: Godot 4 games, or Godot 3 games that already include the loader (like Brotato).", 13f, Theme.muted), lp(this, top = 4))
            root.addView(c)
        }
        val conflicts = ModConflicts.scan(store, g)
        if (conflicts.isNotEmpty()) root.addView(conflictCard(conflicts))
        if (g.mods.isNotEmpty()) {
            val legend = hbox(this)
            legend.addView(chip(this, "Active", Theme.success))
            legend.addView(chip(this, "Conflict", Theme.warn), lp(this, left = 6))
            legend.addView(chip(this, "Error", Theme.danger), lp(this, left = 6))
            legend.addView(chip(this, "Off", Theme.muted), lp(this, left = 6))
            root.addView(legend, fullWidth(this, 12))
        }
        val states = ModStatus.compute(store, g, conflicts)
        for (i in g.mods.indices.reversed()) {
            val m = g.mods[i]
            root.addView(modCard(g, m, i, g.mods.size, states[m.id] ?: ModState.OFF))
        }
        if (g.mods.isNotEmpty()) {
            val tip = if (g.modsExternal) "Changes apply on next game start." else "Changes need a rebuild."
            val live = if (g.overlay && !g.customLoader) " The G button switches mods in-game." else ""
            val order = if (g.mods.size > 1) " Mods load from bottom to top, so the top mod wins." else ""
            root.addView(tv(this, tip + live + order, 12f, Theme.muted), lp(this, top = 12))
        }
    }

    private fun conflictCard(list: List<Conflict>): View {
        val c = card(this)
        c.addView(tv(this, "Conflicts: ${list.size} files", 15f, Theme.warn, true))
        val groups = list.groupBy { it.mods.joinToString(" + ") }
        for ((names, items) in groups.entries.take(4)) {
            c.addView(tv(this, "$names: ${items.size} files", 13f, Theme.text), lp(this, top = 6))
            c.addView(tv(this, items.first().path, 11f, Theme.muted))
        }
        c.addView(tv(this, "The upper mod wins. Use the arrows to change the order.", 12f, Theme.muted), lp(this, top = 8))
        return c
    }

    private fun chooseKind(id: String, m: Mod) {
        val labels = arrayOf(
            "Auto-detect",
            "GUMM (mod.cfg and mod.gd)",
            "Resource pack (.pck or .zip)",
            "Godot Mod Loader (experimental)"
        )
        val current = if (!m.kindManual) 0 else when (m.kind) {
            ModKinds.GUMM -> 1
            ModKinds.PACK -> 2
            else -> 3
        }
        AlertDialog.Builder(this)
            .setTitle("Mod type")
            .setSingleChoiceItems(labels, current) { d, which ->
                d.dismiss()
                val dir = File(store.modsDir(id), m.folder)
                val kind = when (which) {
                    1 -> ModKinds.GUMM
                    2 -> ModKinds.PACK
                    3 -> ModKinds.LOADER
                    else -> ModKinds.detect(dir)
                }
                store.update(id) { game ->
                    game.copy(mods = game.mods.map { if (it.id == m.id) it.copy(kind = kind, kindManual = which != 0) else it })
                }
                scheduleSync(id)
                showGame(id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun moveMod(id: String, m: Mod, delta: Int) {
        store.update(id) { game ->
            val list = game.mods.toMutableList()
            val i = list.indexOfFirst { it.id == m.id }
            val j = i + delta
            if (i < 0 || j < 0 || j >= list.size) {
                game
            } else {
                java.util.Collections.swap(list, i, j)
                game.copy(mods = list)
            }
        }
        scheduleSync(id)
        showGame(id)
    }

    private fun modCard(g: Game, m: Mod, index: Int, count: Int, state: ModState): View {
        val outer = hbox(this).apply {
            background = rounded(Theme.card, dp(this@MainActivity, 14).toFloat())
            clipToOutline = true
            gravity = Gravity.NO_GRAVITY
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(this@MainActivity, 12)
            }
        }
        val bandColor = when (state) {
            ModState.ACTIVE -> Theme.success
            ModState.CONFLICT -> Theme.warn
            ModState.ERROR -> Theme.danger
            ModState.OFF -> Theme.trackOff
        }
        val band = View(this)
        band.setBackgroundColor(bandColor)
        outer.addView(band, LinearLayout.LayoutParams(dp(this, 6), ViewGroup.LayoutParams.MATCH_PARENT))
        val c = vbox(this).apply { setPadding(dp(this@MainActivity, 14), dp(this@MainActivity, 12), dp(this@MainActivity, 14), dp(this@MainActivity, 12)) }
        outer.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val row = hbox(this)
        val dir = File(store.modsDir(g.id), m.folder)
        val icon = ModInfo.icon(dir, 128)
        if (icon != null) {
            val iv = ImageView(this)
            iv.setImageBitmap(icon)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.background = rounded(Theme.input, dp(this, 8).toFloat())
            row.addView(iv, lp(this, width = dp(this, 48)).apply { height = dp(this@MainActivity, 48); rightMargin = dp(this@MainActivity, 12) })
        }
        val col = vbox(this)
        val title = m.name + if (m.version.isNotBlank()) " " + m.version else ""
        col.addView(tv(this, title, 15f, Theme.text, true))
        if (m.author.isNotBlank()) col.addView(tv(this, "by " + m.author, 12f, Theme.muted))
        val (bytes, files) = ModInfo.size(dir)
        col.addView(tv(this, ModInfo.formatSize(bytes) + " \u00b7 " + files + " files", 12f, Theme.muted))
        val stateText = when (state) {
            ModState.ACTIVE -> "Active"
            ModState.CONFLICT -> "Conflicts with another mod"
            ModState.ERROR -> "Error: " + (ModStatus.problem(dir, m, g) ?: "failed to load, see Log")
            ModState.OFF -> "Not active"
        }
        col.addView(tv(this, stateText, 12f, if (state == ModState.OFF) Theme.muted else bandColor, true))
        val kindText = ModKinds.label(m.kind) + (if (m.kindManual) " (set by you)" else "") + " \u25BE"
        val kindView = tv(this, kindText, 12f, Theme.accent)
        kindView.setPadding(0, dp(this, 6), dp(this, 12), dp(this, 2))
        kindView.setOnClickListener { chooseKind(g.id, m) }
        col.addView(kindView)
        row.addView(col, lp(this, weight = 1f, width = 0))
        val sw = styleSwitch(Switch(this))
        sw.isChecked = m.enabled
        sw.setOnCheckedChangeListener { _, v ->
            store.update(g.id) { game -> game.copy(mods = game.mods.map { if (it.id == m.id) it.copy(enabled = v) else it }) }
            scheduleSync(g.id)
            body.post { showGame(g.id) }
        }
        row.addView(sw)
        c.addView(row)
        if (m.description.isNotBlank()) {
            val d = tv(this, m.description, 13f, Theme.muted)
            d.maxLines = 3
            d.ellipsize = TextUtils.TruncateAt.END
            c.addView(d, lp(this, top = 6))
        }
        val bottom = hbox(this)
        val rm = tv(this, "Remove", 13f, Theme.danger)
        rm.setPadding(0, dp(this, 8), dp(this, 8), dp(this, 2))
        rm.setOnClickListener { confirmRemoveMod(g.id, m) }
        bottom.addView(rm, lp(this, weight = 1f, width = 0))
        if (count > 1) {
            bottom.addView(tv(this, "load #" + (index + 1), 12f, Theme.muted))
            val up = tv(this, "\u25B2", 16f, if (index < count - 1) Theme.accent else Theme.line)
            val down = tv(this, "\u25BC", 16f, if (index > 0) Theme.accent else Theme.line)
            for (t in listOf(up, down)) t.setPadding(dp(this, 14), dp(this, 6), dp(this, 14), dp(this, 2))
            if (index < count - 1) up.setOnClickListener { moveMod(g.id, m, 1) }
            if (index > 0) down.setOnClickListener { moveMod(g.id, m, -1) }
            bottom.addView(up)
            bottom.addView(down)
        }
        c.addView(bottom)
        return outer
    }

    private fun settingsTab(root: LinearLayout, g: Game) {
        root.addView(section(this, "Game"))
        val game = card(this)
        game.addView(tv(this, "Name", 12f, Theme.muted))
        game.addView(field(this, "Title", g.title) { v -> store.update(g.id) { it.copy(title = v) } }, fullWidth(this, 4))
        game.addView(tv(this, "Godot version (loader API ${g.variant()})", 12f, Theme.muted), lp(this, top = 10))
        game.addView(field(this, "4.3", g.godotVersion) { v -> store.update(g.id) { it.copy(godotVersion = v.trim()) } }, fullWidth(this, 4))
        game.addView(tv(this, "Main scene path", 12f, Theme.muted), lp(this, top = 10))
        game.addView(field(this, "res://Main.tscn", g.mainScene) { v -> store.update(g.id) { it.copy(mainScene = v.trim()) } }, fullWidth(this, 4))
        val gameRow = hbox(this)
        gameRow.addView(pill(this, "Detect from APK", Theme.input) { detect(g.id) })
        gameRow.addView(pill(this, "Re-read installed", Theme.input) { rereadInstalled(g.id) }, lp(this, left = 8))
        game.addView(gameRow, lp(this, top = 12))
        root.addView(game)

        root.addView(section(this, "Install"))
        val install = card(this)
        install.addView(toggleRow(this, "Install as a separate app", "Keep the original game installed too", g.renamePackage) { v ->
            store.update(g.id) { it.copy(renamePackage = v) }
            body.post { showGame(g.id) }
        })
        if (g.renamePackage) {
            install.addView(field(this, g.originalPackage + ".gumm", g.customPackage) { v -> store.update(g.id) { it.copy(customPackage = v.trim()) } }, fullWidth(this, 2))
        }
        install.addView(divider(this), dividerParams(this))
        install.addView(toggleRow(this, "Add (Modded) to the app name", null, g.labelSuffix) { v -> store.update(g.id) { it.copy(labelSuffix = v) } })
        install.addView(divider(this), dividerParams(this))
        install.addView(toggleRow(this, "Copy save data", "Needs root. Otherwise saves start empty.", g.copyUserData) { v -> store.update(g.id) { it.copy(copyUserData = v) } })
        root.addView(install)

        root.addView(section(this, "Mod loading"))
        val loading = card(this)
        loading.addView(toggleRow(this, "Load mods from an external folder", "Needs All files access for the game too.", g.modsExternal) { v ->
            store.update(g.id) { it.copy(modsExternal = v) }
            body.post { showGame(g.id) }
        })
        if (g.modsExternal) {
            loading.addView(tv(this, ExternalMods.root(g.targetPackage()).path, 11f, Theme.muted), lp(this, top = 2))
            if (!ExternalMods.supported()) {
                loading.addView(tv(this, "Needs Android 11 or newer", 12f, Theme.danger))
            } else if (!ExternalMods.available()) {
                loading.addView(tv(this, "Needs All files access.", 12f, Theme.danger), lp(this, top = 4))
                loading.addView(pill(this, "Grant access", Theme.input) {
                    awaitingAccess = true
                    ExternalMods.openSettings(this)
                }, lp(this, top = 6))
            }
        }
        loading.addView(divider(this), dividerParams(this))
        loading.addView(toggleRow(this, "In-game mod button", "G button to switch mods while playing", g.overlay) { v ->
            store.update(g.id) { it.copy(overlay = v) }
        })
        root.addView(loading)

        root.addView(section(this, "Loader"))
        root.addView(kitCard(g))

        root.addView(section(this, "More"))
        val more = card(this)
        val moreRow = hbox(this)
        moreRow.addView(pill(this, "Signing info", Theme.input) { showSigningInfo(g) })
        more.addView(moreRow)
        root.addView(more)
        root.addView(pill(this, "Remove game from GUMMM", Theme.danger) { confirmRemove(g) }, lp(this, top = 20))
    }

    private fun kitCard(g: Game): View {
        val c = card(this)
        if (!g.customLoader) {
            val ok = LoaderGen.supports(g)
            val text = if (ok) "Loader for Godot ${g.godotVersion} is made on every build." else "Set a Godot 3 or 4 version above, or use your own loader."
            c.addView(tv(this, text, 13f, if (ok) Theme.muted else Theme.danger))
        }
        c.addView(toggleRow(this, "Use my own loader files", "Replaces the built-in loader. No in-game button.", g.customLoader) { v ->
            store.update(g.id) { it.copy(customLoader = v) }
            body.post { showGame(g.id) }
        })
        if (g.customLoader) {
            val kit = store.kitDir(g.id)
            val count = kit.walkTopDown().count { it.isFile }
            val ready = File(kit, "override.cfg").isFile
            val status = if (ready) "$count files ready" else "Missing. Import a folder with override.cfg and GUMM_mod_loader.tscn."
            c.addView(tv(this, status, 13f, if (ready) Theme.muted else Theme.danger), lp(this, top = 4))
            c.addView(pill(this, "Import loader folder", Theme.input) { pickTree(REQ_KIT) }, lp(this, top = 8))
            c.addView(tv(this, "Main scene used in these files", 12f, Theme.muted), lp(this, top = 10))
            c.addView(field(this, "optional", g.kitMainScene) { v -> store.update(g.id) { it.copy(kitMainScene = v.trim()) } }, fullWidth(this, 4))
            c.addView(tv(this, "Mods path used in these files. {{MODS_DIR}} also works.", 12f, Theme.muted), lp(this, top = 10))
            c.addView(field(this, "optional", g.kitModsPath) { v -> store.update(g.id) { it.copy(kitModsPath = v.trim()) } }, fullWidth(this, 4))
        }
        return c
    }

    private fun confirmRemoveMod(id: String, m: Mod) {
        AlertDialog.Builder(this)
            .setTitle("Remove ${m.name}?")
            .setMessage("Deletes the mod from GUMMM.")
            .setPositiveButton("Remove") { _, _ -> removeMod(id, m) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeMod(id: String, m: Mod) {
        File(store.modsDir(id), m.folder).deleteRecursively()
        store.update(id) { g -> g.copy(mods = g.mods.filter { it.id != m.id }) }
        scheduleSync(id)
        showGame(id)
    }

    private fun confirmRemove(g: Game) {
        AlertDialog.Builder(this)
            .setTitle("Remove ${g.title}?")
            .setMessage("Deletes the stored APK, mods and loader files. Installed apps stay.")
            .setPositiveButton("Remove") { _, _ ->
                store.remove(g.id)
                showLibrary()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun detect(id: String) = task("Inspecting APK", done = { showGame(id) }) { log ->
        val apk = store.originalApk(id)
        val info = GodotInspector.inspectAll(apk, store.splitFiles(id))
        val scene = GodotInspector.detectMainSceneAll(apk, store.splitFiles(id))
        store.update(id) {
            it.copy(
                godotVersion = if (info.version.isNotEmpty()) info.version else it.godotVersion,
                mainScene = scene ?: it.mainScene
            )
        }
        log("Godot ${info.version.ifEmpty { "unknown" }}, main scene ${scene ?: "not found"}")
    }

    private fun updateFromApk(id: String, uri: Uri) {
        var ok = false
        task("Updating game APK", done = {
            showGame(id)
            if (ok) build(id)
        }) { log ->
            val tmp = File(cacheDir, "update.apk")
            val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open file")
            input.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
            val info = packageManager.getPackageArchiveInfo(tmp.path, 0) ?: throw IllegalStateException("Not a valid APK")
            val g = store.get(id) ?: throw IllegalStateException("Game missing")
            if (info.packageName != g.originalPackage) {
                tmp.delete()
                throw IllegalStateException("Package mismatch: ${info.packageName}")
            }
            tmp.copyTo(store.originalApk(id), overwrite = true)
            tmp.delete()
            val apk = store.originalApk(id)
            if (GodotInspector.looksLikeGodot(apk)) store.splitsDir(id).deleteRecursively()
            val gi = GodotInspector.inspectAll(apk, store.splitFiles(id))
            val scene = GodotInspector.detectMainSceneAll(apk, store.splitFiles(id))
            store.update(id) {
                it.copy(
                    godotVersion = gi.version.ifEmpty { it.godotVersion },
                    mainScene = scene ?: it.mainScene
                )
            }
            log("Game APK replaced (${info.versionName}). Rebuilding.")
            ok = true
        }
    }

    private fun rereadInstalled(id: String) {
        var ok = false
        task("Reading installed original", done = {
            showGame(id)
            if (ok) build(id)
        }) { log ->
            val g = store.get(id) ?: throw IllegalStateException("Game missing")
            val ai = packageManager.getApplicationInfo(g.originalPackage, 0)
            File(ai.sourceDir).copyTo(store.originalApk(id), overwrite = true)
            copySplits(id, ai, log)
            val apk = store.originalApk(id)
            val gi = GodotInspector.inspectAll(apk, store.splitFiles(id))
            val scene = GodotInspector.detectMainSceneAll(apk, store.splitFiles(id))
            store.update(id) {
                it.copy(
                    godotVersion = gi.version.ifEmpty { it.godotVersion },
                    mainScene = scene ?: it.mainScene
                )
            }
            log("Stored APK updated from the installed app. Rebuilding.")
            ok = true
        }
    }

    private fun importModZip(id: String, uri: Uri) = task("Importing mod zip", done = { showGame(id); scheduleSync(id) }) { log ->
        for (mod in ModImporter.fromZip(this, uri, store.modsDir(id), log)) {
            addMod(id, mod)
            log("Imported ${mod.name}")
        }
    }

    private fun importModDir(id: String, uri: Uri) = task("Importing mod folder", done = { showGame(id); scheduleSync(id) }) { log ->
        for (mod in ModImporter.fromTree(this, uri, store.modsDir(id), log)) {
            addMod(id, mod)
            log("Imported ${mod.name}")
        }
    }

    private fun addMod(id: String, mod: Mod) {
        val g = store.get(id) ?: return
        val old = g.mods.firstOrNull { it.name == mod.name }
        if (old == null) {
            store.update(id) { it.copy(mods = it.mods + mod) }
        } else {
            File(store.modsDir(id), old.folder).deleteRecursively()
            store.update(id) { game ->
                game.copy(mods = game.mods.map { if (it.id == old.id) mod.copy(enabled = old.enabled) else it })
            }
        }
    }

    private fun importKit(id: String, tree: Uri) = task("Importing loader files", done = { showGame(id) }) { log ->
        val kit = store.kitDir(id)
        kit.deleteRecursively()
        kit.mkdirs()
        DocFiles.copyTree(contentResolver, tree, kit, setOf("mods"))
        val count = kit.walkTopDown().count { it.isFile }
        if (!File(kit, "override.cfg").isFile) log("Warning: no override.cfg at the top of that folder")
        store.defaultKit.deleteRecursively()
        kit.copyRecursively(store.defaultKit, overwrite = true)
        log("Loader files imported: $count")
    }

    private fun build(id: String) {
        val g = store.get(id) ?: return
        if (g.renamePackage && !packageRegex.matches(g.targetPackage())) {
            appendLog("! Invalid package name: ${g.targetPackage()}")
            toast("Invalid package name")
            return
        }
        if (g.renamePackage && g.targetPackage() == g.originalPackage) {
            appendLog("! New package name equals the original")
            return
        }
        if (g.modsExternal) {
            if (!ExternalMods.supported()) {
                appendLog("! External mods need Android 11 or newer")
                return
            }
            if (!ExternalMods.available()) {
                AlertDialog.Builder(this)
                    .setTitle("All files access needed")
                    .setMessage("External mods are copied to the game's media folder, so GUMMM needs All files access. Android opens a settings page: switch it on, then come back and build again.\n\nAfter the game is installed, allow All files access for the game too (the Mods tab shows a Grant button). You only do this once.")
                    .setPositiveButton("Continue") { _, _ ->
                        awaitingAccess = true
                        ExternalMods.openSettings(this)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return
            }
        }
        val stampNow = store.stamp(g)
        var built: File? = null
        task("Building ${g.title}", done = {
            val fresh = store.get(id)
            val file = built
            if (fresh != null && file != null) startInstall(fresh, file)
        }) { log ->
            if (g.mainScene.isBlank()) log("Warning: main scene path is empty")
            built = ApkPatcher(this, store, keys).patch(g, log)
            if (g.modsExternal) ExternalMods.sync(store, g, log)
            pendingStamp = stampNow
            store.update(id) { it.copy(lastBuildMs = System.currentTimeMillis()) }
        }
    }

    private fun startInstall(g: Game, apk: File) {
        if (!Installer.canInstall(this)) {
            pendingInstall = g.id to apk
            toast("Allow installs from GUMMM, then come back")
            Installer.openInstallSettings(this)
            return
        }
        val target = g.targetPackage()
        installingGame = g.id
        if (Installer.isInstalled(this, target) && !keys.matches(this, target)) {
            val extra = if (g.copyUserData) " Saves are backed up first if root is available." else ""
            AlertDialog.Builder(this)
                .setTitle("Signature differs")
                .setMessage("$target has a different signature and can't be updated. Uninstalling removes its saves.$extra")
                .setPositiveButton("Uninstall") { _, _ -> beginUninstall(g, apk) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        safeInstall(apk)
    }

    private fun safeInstall(apk: File) {
        try {
            appendLog("> Installing")
            Installer.install(this, apk)
        } catch (e: Exception) {
            appendLog("! ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun beginUninstall(g: Game, apk: File) {
        pendingGame = g.id
        pendingApk = apk
        val target = g.targetPackage()
        if (g.copyUserData) {
            task("Backing up save data", done = { launchUninstall(target) }) { log ->
                if (RootCopy.available() && RootCopy.backup(target)) {
                    restoreBackupFor = target
                    log("Backup done")
                } else {
                    log("Root not available, skipping backup")
                }
            }
        } else {
            launchUninstall(target)
        }
    }

    private fun launchUninstall(pkg: String) {
        val i = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
        i.putExtra(Intent.EXTRA_RETURN_RESULT, true)
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_UNINSTALL)
    }

    private fun afterUninstall() {
        val apk = pendingApk
        val gid = pendingGame
        pendingApk = null
        if (apk != null && gid != null) {
            val g = store.get(gid)
            if (g != null && !Installer.isInstalled(this, g.targetPackage())) {
                installingGame = gid
                safeInstall(apk)
                return
            }
            restoreBackupFor = null
            toast("Still installed, cancelled")
        }
        refreshCurrent()
    }

    private fun onInstallResult(status: Int, pkg: String?, msg: String?) {
        val id = installingGame
        installingGame = null
        if (status == PackageInstaller.STATUS_SUCCESS) {
            appendLog("Installed ${pkg ?: ""}")
            val g = if (id != null) store.get(id) else null
            if (id != null && g != null) {
                val installed = pkg ?: g.targetPackage()
                store.update(id) { it.copy(builtPackage = installed, builtStamp = pendingStamp ?: it.builtStamp) }
                if (g.copyUserData) {
                    task("Copying save data", done = { refreshCurrent() }) { log -> copySaves(g, installed, log) }
                    return
                }
            }
            refreshCurrent()
        } else {
            appendLog("! Install failed ($status): ${msg ?: ""}")
            if ((msg ?: "").contains("DOWNGRADE")) {
                appendLog("Installed version is newer. Uninstall the modded app first.")
            }
            if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || (msg ?: "").contains("INCOMPATIBLE")) {
                appendLog("Signature differs. Uninstall it or use a separate app.")
            }
        }
    }

    private fun copySaves(g: Game, installed: String, log: (String) -> Unit) {
        if (!RootCopy.available()) {
            log("Root not available, save data was not copied")
            restoreBackupFor = null
            return
        }
        val backup = restoreBackupFor
        val ok = when {
            backup != null -> RootCopy.restore(this, backup, installed)
            g.renamePackage -> RootCopy.copyDirect(this, g.originalPackage, installed)
            else -> true
        }
        restoreBackupFor = null
        log(if (ok) "Save data copied" else "Copying save data failed")
    }

    private fun showSigningInfo(g: Game) {
        val pm = packageManager
        val mine = keys.certificate().encoded
        val sb = StringBuilder()
        sb.append("GUMMM signing key (used for modded builds)\n").append(SigningInfo.describe(mine)).append("\n\n")
        val target = if (g.builtPackage.isNotBlank()) g.builtPackage else g.targetPackage()
        val installed = SigningInfo.fromInstalled(pm, target)
        sb.append("Installed $target\n")
        if (installed.isEmpty()) {
            sb.append("Not installed\n")
        } else {
            for (c in installed) {
                sb.append("SHA-256: ").append(SigningInfo.fingerprint(c, "SHA-256")).append("\n")
                sb.append("Matches GUMM key: ").append(if (c.contentEquals(mine)) "yes" else "no").append("\n")
            }
        }
        sb.append("\nOriginal game APK\n")
        val original = SigningInfo.fromApk(pm, store.originalApk(g.id).path)
        if (original.isEmpty()) {
            sb.append("Unsigned or unreadable\n")
        } else {
            for (c in original) sb.append(SigningInfo.describe(c)).append("\n")
        }
        val text = tv(this, sb.toString(), 12f, Theme.text)
        text.setTextIsSelectable(true)
        val pad = dp(this, 16)
        val holder = ScrollView(this)
        holder.setPadding(pad, pad / 2, pad, 0)
        holder.addView(text)
        AlertDialog.Builder(this)
            .setTitle("Signing info")
            .setView(holder)
            .setPositiveButton("Copy certificate") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("GUMM certificate", SigningInfo.pem(mine)))
                toast("Certificate copied")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showKeyDialog() {
        AlertDialog.Builder(this)
            .setTitle("Signing key")
            .setMessage("All modded builds use one key stored here, so updates install in place. Export it to keep it after reinstalling GUMMM.")
            .setPositiveButton("Export") { _, _ ->
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                i.addCategory(Intent.CATEGORY_OPENABLE)
                i.type = "application/octet-stream"
                i.putExtra(Intent.EXTRA_TITLE, "gumm-signing.p12")
                @Suppress("DEPRECATION")
                startActivityForResult(i, REQ_EXPORT_KEY)
            }
            .setNegativeButton("Import") { _, _ -> pickDocument(REQ_IMPORT_KEY, arrayOf("*/*")) }
            .setNeutralButton("Close", null)
            .show()
    }

    private fun exportKey(uri: Uri) = task("Exporting key") { log ->
        val out = contentResolver.openOutputStream(uri) ?: throw IllegalStateException("Cannot write file")
        out.use { keys.exportTo(it) }
        log("Key exported")
    }

    private fun importKey(uri: Uri) = task("Importing key") { log ->
        val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open file")
        input.use { keys.importFrom(it) }
        log("Key imported")
    }
}
