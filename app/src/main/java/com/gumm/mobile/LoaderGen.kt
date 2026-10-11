package com.gumm.mobile

object LoaderGen {
    const val VERSION = 7

    fun major(game: Game): Int = game.godotVersion.split('.').firstOrNull()?.toIntOrNull() ?: 0

    fun supports(game: Game): Boolean = major(game) == 3 || major(game) == 4

    fun override(): String = "[application]\n\nrun/main_scene=\"res://GUMM_mod_loader.tscn\"\n"

    private fun gd(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun gdList(items: List<String>): String = items.joinToString(", ", "[", "]") { gd(it) }

    private fun gdDict(items: Map<String, Boolean>): String =
        items.entries.joinToString(", ", "{", "}") { gd(it.key) + ": " + it.value }

    private fun tscnString(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t")

    fun scene(
        game: Game,
        template: String,
        mods: List<String>,
        modsRoot: String,
        configPath: String,
        logPath: String,
        rev: String,
        defaults: Map<String, Boolean>,
        button: Boolean
    ): String {
        val four = major(game) == 4
        val source = template
            .replace("__MAIN_SCENE__", gd(game.mainScene))
            .replace("__MOD_LIST__", gdList(mods))
            .replace("__MODS_ROOT__", gd(modsRoot))
            .replace("__CONFIG_PATH__", gd(configPath))
            .replace("__LOG_PATH__", gd(logPath))
            .replace("__REV__", gd(rev))
            .replace("__DEFAULTS__", gdDict(defaults))
            .replace("__BUTTON__", button.toString())
        val body = tscnString(source)
        return if (four) {
            "[gd_scene load_steps=2 format=3]\n\n" +
                "[sub_resource type=\"GDScript\" id=\"GDScript_gumm\"]\n" +
                "script/source = \"" + body + "\"\n\n" +
                "[node name=\"GUMMModLoader\" type=\"Node\"]\n" +
                "script = SubResource(\"GDScript_gumm\")\n"
        } else {
            "[gd_scene load_steps=2 format=2]\n\n" +
                "[sub_resource type=\"GDScript\" id=1]\n" +
                "script/source = \"" + body + "\"\n\n" +
                "[node name=\"GUMMModLoader\" type=\"Node\"]\n" +
                "script = SubResource( 1 )\n"
        }
    }
}
