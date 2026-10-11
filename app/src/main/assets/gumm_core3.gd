extends Node

const STATE_PATH = "user://gumm_state.txt"
const LOCAL_LOG = "user://gumm_loader.log"
const ENTRY_NAMES = ["mod.gd", "GUMM_mod.gd"]
const FAB_SIZE = 36.0

var main_scene = ""
var mod_dirs = []
var mods_root = ""
var config_path = ""
var log_path = ""
var rev = ""
var defaults = {}
var show_button = true
var tree = null

var last_err = 0
var entries = []
var states = {}
var lines = []
var all_mods = []

var ui_layer = null
var fab = null
var panel = null
var mods_scroll = null
var log_scroll = null
var log_label = null
var log_btn = null
var ui_scale = 1.0
var drag_dist = 0.0
var grab = Vector2(0, 0)
var dragging = false
var idle_time = 0.0


func setup(cfg):
	main_scene = cfg["main_scene"]
	mod_dirs = cfg["mods"]
	mods_root = cfg["root"]
	config_path = cfg["config"]
	log_path = cfg["log"]
	rev = cfg["rev"]
	defaults = cfg["defaults"]
	show_button = cfg["button"]
	pause_mode = Node.PAUSE_MODE_PROCESS
	name = "GUMMCore"


func load_all(t):
	tree = t
	var v = Engine.get_version_info()
	log_line("GUMM core on Godot " + str(v["major"]) + "." + str(v["minor"]))
	read_config()
	read_state()
	var dirs = []
	for d in mod_dirs:
		dirs.append(d)
	if mods_root != "":
		for d in scan(mods_root):
			dirs.append(d)
	dirs = order_dirs(dirs)
	for d in dirs:
		entries.append(make_entry(d))
	var active = []
	for e in entries:
		if e["want"]:
			active.append(e["dir"])
	ProjectSettings.set_setting("gumm/mod_list", active)
	ProjectSettings.set_setting("gumm/main_scene", main_scene)
	for e in entries:
		if e["want"]:
			activate(e)
	tree.set_meta("GUMM_mods", all_mods)
	log_line("Mods active: " + str(active.size()) + " of " + str(entries.size()))
	flush()


func order_dirs(dirs):
	# config and defaults keep the order set in GUMMM (top loads first)
	var out = []
	for k in defaults.keys():
		for d in dirs:
			if d.get_file() == k and not out.has(d):
				out.append(d)
	for d in dirs:
		if not out.has(d):
			out.append(d)
	return out


func list_entries(root, mode):
	var found = []
	var d = Directory.new()
	if d.open(root) != OK:
		return found
	d.list_dir_begin(true, true)
	var entry = d.get_next()
	while entry != "":
		var is_dir = d.current_is_dir()
		if mode == 2 or (mode == 1 and is_dir) or (mode == 0 and not is_dir):
			found.append(entry)
		entry = d.get_next()
	d.list_dir_end()
	found.sort()
	return found


func scan(root):
	var found = []
	if not Directory.new().dir_exists(root):
		log_line("Mods folder not readable: " + root)
		return found
	for n in list_entries(root, 1):
		found.append(root + "/" + n)
	return found


func describe_dir(dir):
	var names = list_entries(dir, 2)
	if names.size() == 0:
		return "empty or unreadable"
	var text = ""
	var count = 0
	for n in names:
		if count >= 12:
			text += ", ..."
			break
		if count > 0:
			text += ", "
		text += n
		count += 1
	return text


func exists(path):
	var f = File.new()
	return f.file_exists(path) or ResourceLoader.exists(path)


func entry_script(dir):
	# file_exists can be wrong on shared storage, so trust the folder listing too
	var files = list_entries(dir, 2)
	for n in ENTRY_NAMES:
		if files.has(n) or exists(dir + "/" + n):
			return dir + "/" + n
	return ""


func prime_base(dir):
	# mod.gd extends GUMM_mod.gd by relative path, so load and cache it first
	if list_entries(dir, 2).has("GUMM_mod.gd"):
		load_script(dir + "/GUMM_mod.gd")


func make_entry(dir):
	var folder = dir.get_file()
	var title = folder
	var cfg = ConfigFile.new()
	if cfg.load(dir + "/mod.cfg") == OK:
		title = str(cfg.get_value("Godot Mod", "name", folder))
	var want = true
	if defaults.has(folder):
		want = bool(defaults[folder])
	if states.has(folder):
		want = bool(states[folder])
	return {"dir": dir, "folder": folder, "title": title, "want": want, "inst": null, "active": false, "btn": null, "error": ""}


func fail(e, msg):
	e["error"] = msg
	log_line(msg)
	flush()


func load_script(path):
	var res = load(path)
	if res != null:
		return res
	var text = read_text(path)
	if text == "":
		log_line("Cannot read " + path + " (error " + str(last_err) + ")")
		if last_err == ERR_FILE_NOT_FOUND and path.begins_with("/storage"):
			log_line("The game is blocked from reading shared storage. Allow All files access for it (GUMMM, Mods tab) or turn off external mods.")
		return null
	var s = GDScript.new()
	s.source_code = text
	s.take_over_path(path)
	if s.reload() != OK:
		log_line("Script error in " + path)
		return null
	return s


func assign_paths(inst, dir):
	for p in inst.get_property_list():
		if (int(p["usage"]) & PROPERTY_USAGE_SCRIPT_VARIABLE) != 0 and int(p["type"]) == TYPE_STRING:
			var pname = str(p["name"])
			var lower = pname.to_lower()
			if lower.find("path") != -1 or lower.find("dir") != -1:
				if str(inst.get(pname)) == "":
					inst.set(pname, dir)


func activate(e):
	if e["inst"] == null:
		var path = entry_script(e["dir"])
		prime_base(e["dir"])
		if path == "":
			fail(e, "No mod.gd or GUMM_mod.gd in " + e["dir"] + " (found: " + describe_dir(e["dir"]) + ")")
			return false
		var res = load_script(path)
		if res == null:
			fail(e, "Cannot load " + path + " (script error or unreadable)")
			return false
		var inst = res.new()
		if inst == null:
			fail(e, "Cannot create an instance from " + path)
			return false
		e["inst"] = inst
		e["error"] = ""
		all_mods.append(inst)
		if inst.has_method("initialize"):
			inst.initialize(e["dir"], tree)
		elif inst.has_method("_initialize"):
			assign_paths(inst, e["dir"])
			inst._initialize(tree)
		else:
			assign_paths(inst, e["dir"])
			log_line("No initialize method in " + path)
		log_line("Loaded " + e["title"] + " from " + path)
	elif e["inst"].has_method("set_enabled"):
		e["inst"].set_enabled(true)
		log_line("Enabled " + e["title"])
	e["active"] = true
	return true


func deactivate(e):
	if e["inst"] != null and e["inst"].has_method("set_enabled"):
		e["inst"].set_enabled(false)
		e["active"] = false
		log_line("Disabled " + e["title"])
		return true
	return false


func read_text(path):
	var f = File.new()
	last_err = f.open(path, File.READ)
	if last_err != OK:
		return ""
	var t = f.get_as_text()
	f.close()
	return t


func write_text(path, text):
	var f = File.new()
	if f.open(path, File.WRITE) == OK:
		f.store_string(text)
		f.close()


func read_config():
	if config_path == "":
		return
	var text = read_text(config_path)
	if text == "":
		return
	var loaded = {}
	for line in text.split("\n"):
		var t = line.strip_edges()
		var i = t.find("=")
		if i <= 0:
			continue
		var k = t.substr(0, i)
		var v = t.substr(i + 1)
		if k == "rev":
			rev = v
		else:
			loaded[k] = (v == "1")
	defaults = loaded


func read_state():
	var text = read_text(STATE_PATH)
	if text == "":
		return
	var loaded = {}
	var same = false
	for line in text.split("\n"):
		var t = line.strip_edges()
		var i = t.find("=")
		if i <= 0:
			continue
		var k = t.substr(0, i)
		var v = t.substr(i + 1)
		if k == "rev":
			same = (v == rev)
		else:
			loaded[k] = (v == "1")
	if same:
		states = loaded


func write_state():
	var out = "rev=" + rev + "\n"
	for k in states.keys():
		out += k + "=" + ("1" if states[k] else "0") + "\n"
	write_text(STATE_PATH, out)


func log_line(msg):
	print("GUMM: " + msg)
	lines.append(msg)


func log_text():
	var text = ""
	var start = max(lines.size() - 40, 0)
	for i in range(start, lines.size()):
		text += lines[i] + "\n"
	return text


func flush():
	var text = ""
	for l in lines:
		text += l + "\n"
	write_text(LOCAL_LOG, text)
	if log_path != "":
		write_text(log_path, text)


func row_text(e):
	var t = e["title"]
	if e["error"] != "":
		t += "  (failed, see Log)"
	elif e["want"] != e["active"]:
		t += "  (restart to apply)"
	return t


func make_style(color, radius, margin):
	var sb = StyleBoxFlat.new()
	sb.bg_color = color
	sb.set_corner_radius_all(radius)
	sb.content_margin_left = margin
	sb.content_margin_right = margin
	sb.content_margin_top = margin
	sb.content_margin_bottom = margin
	return sb


func _ready():
	if show_button and entries.size() > 0:
		build_ui()


func build_ui():
	var vp = get_viewport().get_visible_rect().size
	ui_scale = clamp(vp.y / 540.0, 1.0, 3.0)
	ui_layer = CanvasLayer.new()
	ui_layer.layer = 128
	add_child(ui_layer)
	var panel_w = min(vp.x / ui_scale - 24.0, 440.0)
	var panel_h = min(vp.y / ui_scale - 24.0, 380.0)
	panel = PanelContainer.new()
	panel.visible = false
	panel.set_scale(Vector2(ui_scale, ui_scale))
	panel.add_stylebox_override("panel", make_style(Color(0.09, 0.1, 0.13, 0.96), 12, 12))
	ui_layer.add_child(panel)
	var box = VBoxContainer.new()
	panel.add_child(box)
	var head = HBoxContainer.new()
	box.add_child(head)
	var title = Label.new()
	title.text = "GUMMM mods"
	title.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	head.add_child(title)
	log_btn = Button.new()
	log_btn.text = "Log"
	head.add_child(log_btn)
	var close = Button.new()
	close.text = "Close"
	head.add_child(close)
	var area_h = max(panel_h - 120.0, 80.0)
	mods_scroll = ScrollContainer.new()
	mods_scroll.set_custom_minimum_size(Vector2(panel_w, area_h))
	box.add_child(mods_scroll)
	var list = VBoxContainer.new()
	list.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	mods_scroll.add_child(list)
	for i in range(entries.size()):
		var e = entries[i]
		var cb = CheckButton.new()
		cb.text = row_text(e)
		cb.clip_text = true
		cb.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		cb.set_custom_minimum_size(Vector2(0, 40))
		cb.set_pressed(e["want"])
		cb.connect("toggled", self, "on_toggled", [i])
		list.add_child(cb)
		e["btn"] = cb
	log_scroll = ScrollContainer.new()
	log_scroll.visible = false
	log_scroll.set_custom_minimum_size(Vector2(panel_w, area_h))
	box.add_child(log_scroll)
	log_label = Label.new()
	log_label.autowrap = true
	log_label.set_custom_minimum_size(Vector2(panel_w - 20.0, 0))
	log_scroll.add_child(log_label)
	var note = Label.new()
	note.text = "Restart the game to apply rows marked restart. Drag G to move it."
	note.autowrap = true
	note.set_custom_minimum_size(Vector2(panel_w, 0))
	box.add_child(note)
	fab = Button.new()
	fab.text = "G"
	fab.set_custom_minimum_size(Vector2(FAB_SIZE, FAB_SIZE))
	fab.set_scale(Vector2(ui_scale, ui_scale))
	var fab_style = make_style(Color(0.36, 0.55, 0.94, 0.95), 18, 4)
	for state in ["normal", "hover", "pressed", "focus"]:
		fab.add_stylebox_override(state, fab_style)
	fab.set_position(Vector2(vp.x - FAB_SIZE * ui_scale, vp.y * 0.3))
	ui_layer.add_child(fab)
	fab.connect("pressed", self, "on_fab")
	fab.connect("gui_input", self, "on_fab_input")
	close.connect("pressed", self, "hide_panel")
	log_btn.connect("pressed", self, "toggle_log")


func _process(delta):
	if fab == null:
		return
	var awake = dragging or panel.visible
	if awake:
		idle_time = 0.0
	else:
		idle_time += delta
	var resting = idle_time > 2.0
	var want_alpha = 0.9
	if resting:
		want_alpha = 0.3
	var c = fab.modulate
	c.a = c.a + (want_alpha - c.a) * min(delta * 6.0, 1.0)
	fab.modulate = c
	if resting and not dragging:
		var p = fab.get_position()
		fab.set_position(p + (snap_target() - p) * min(delta * 8.0, 1.0))


func snap_target():
	var vp = get_viewport().get_visible_rect().size
	var fab_px = FAB_SIZE * ui_scale
	var p = fab.get_position()
	var x = 0.0
	if p.x + fab_px / 2.0 > vp.x / 2.0:
		x = vp.x - fab_px
	return Vector2(x, clamp(p.y, 0.0, vp.y - fab_px))


func on_toggled(pressed, i):
	var e = entries[i]
	e["want"] = pressed
	states[e["folder"]] = pressed
	write_state()
	if pressed:
		activate(e)
	else:
		deactivate(e)
	flush()
	if e["btn"] != null:
		e["btn"].text = row_text(e)
	if log_scroll.visible:
		log_label.text = log_text()


func on_fab_input(ev):
	if ev is InputEventMouseButton:
		if ev.pressed:
			drag_dist = 0.0
			dragging = true
			grab = get_viewport().get_mouse_position() - fab.get_position()
		else:
			dragging = false
	elif ev is InputEventMouseMotion:
		if (ev.button_mask & 1) != 0:
			var vp = get_viewport().get_visible_rect().size
			var fab_px = FAB_SIZE * ui_scale
			var p = get_viewport().get_mouse_position() - grab
			p.x = clamp(p.x, 0.0, vp.x - fab_px)
			p.y = clamp(p.y, 0.0, vp.y - fab_px)
			drag_dist += (p - fab.get_position()).length()
			fab.set_position(p)


func on_fab():
	if drag_dist > 10.0:
		drag_dist = 0.0
		return
	if panel.visible:
		hide_panel()
	else:
		var vp = get_viewport().get_visible_rect().size
		panel.visible = true
		panel.set_position((vp - panel.get_combined_minimum_size() * ui_scale) / 2.0)


func toggle_log():
	var want_log = not log_scroll.visible
	log_scroll.visible = want_log
	mods_scroll.visible = not want_log
	if want_log:
		log_label.text = log_text()
		log_btn.text = "Mods"
	else:
		log_btn.text = "Log"


func hide_panel():
	panel.visible = false
