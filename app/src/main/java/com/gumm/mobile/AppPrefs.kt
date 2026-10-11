package com.gumm.mobile

import android.content.Context
import android.content.SharedPreferences

object AppPrefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences("gumm_prefs", Context.MODE_PRIVATE)
    }

    private fun flag(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun put(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()

    var light: Boolean
        get() = flag("light", false)
        set(v) = put("light", v)

    var renamePackage: Boolean
        get() = flag("d_rename", true)
        set(v) = put("d_rename", v)

    var labelSuffix: Boolean
        get() = flag("d_label", true)
        set(v) = put("d_label", v)

    var copyUserData: Boolean
        get() = flag("d_saves", false)
        set(v) = put("d_saves", v)

    var modsExternal: Boolean
        get() = flag("d_external", false)
        set(v) = put("d_external", v)

    var overlay: Boolean
        get() = flag("d_overlay", false)
        set(v) = put("d_overlay", v)

    var reopenSettings: Boolean
        get() = flag("reopen", false)
        set(v) = put("reopen", v)

    var autoUpdate: Boolean
        get() = flag("auto_update", true)
        set(v) = put("auto_update", v)

    var lastUpdateCheck: Long
        get() = sp.getLong("last_update_check", 0L)
        set(v) = sp.edit().putLong("last_update_check", v).apply()
}
