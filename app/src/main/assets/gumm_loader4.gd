extends Node

const MAIN_SCENE = __MAIN_SCENE__
const MOD_LIST = __MOD_LIST__
const MODS_ROOT = __MODS_ROOT__
const CONFIG_PATH = __CONFIG_PATH__
const LOG_PATH = __LOG_PATH__
const REV = __REV__
const DEFAULTS = __DEFAULTS__
const KINDS = __KINDS__
const BUTTON = __BUTTON__


func _ready():
	var res = load("res://GUMM_core.gd")
	if res != null:
		var core = res.new()
		core.setup({
			"main_scene": MAIN_SCENE,
			"mods": MOD_LIST,
			"root": MODS_ROOT,
			"config": CONFIG_PATH,
			"log": LOG_PATH,
			"rev": REV,
			"defaults": DEFAULTS,
			"kinds": KINDS,
			"button": BUTTON
		})
		core.load_all(get_tree())
		get_tree().root.call_deferred("add_child", core)
	call_deferred("_start")


func _start():
	get_tree().change_scene_to_file(MAIN_SCENE)
