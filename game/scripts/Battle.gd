extends Node2D

# Scène de combat : phase de placement puis auto-bataille.

signal battle_finished(victory: bool)

const FIELD_TOP := 70
const SPAWN_Y := 130
const CASTLE_Y := 1170
const FIELD_CENTER_X := 360

const GRID_COLS := 5
const GRID_ROWS := 4
const CELL_W := 120
const CELL_H := 100
const GRID_LEFT := 60
const GRID_TOP := 660

const BENCH_Y := 1080
const BENCH_H := 200

enum Phase { PLACEMENT, COMBAT, RESULT }
var phase: int = Phase.PLACEMENT

var castle_max_hp: int = 500
var castle_hp: int = 500

var castle_node: Node2D
var castle_hp_bar: ColorRect
var castle_hp_bar_bg: ColorRect

var phase_label: Label
var start_button: Button
var result_panel: Control

var grid_buttons: Array = []
var grid_occupants: Dictionary = {}

var bench_container: Control
var bench_buttons: Array = []
var selected_party_idx: int = -1
var placed_party_indices: Dictionary = {}

var heroes_alive: Array = []
var enemies_alive: Array = []
var spawn_queue: Array = []
var spawn_index: int = 0
var spawn_timer: float = 0.0

func _ready() -> void:
	# Augmente HP château avec l'étage
	castle_max_hp = 500 + (GameState.current_floor - 1) * 25
	castle_hp = castle_max_hp

	var bg := ColorRect.new()
	bg.color = Color(0.13, 0.11, 0.09)
	bg.size = Vector2(720, 1280)
	add_child(bg)

	var field := ColorRect.new()
	field.color = Color(0.18, 0.21, 0.16)
	field.position = Vector2(0, 56)
	field.size = Vector2(720, BENCH_Y - 56)
	add_child(field)

	# Lignes de chemin (5 voies)
	for c in range(GRID_COLS):
		var line := Line2D.new()
		line.add_point(Vector2(GRID_LEFT + c * CELL_W + CELL_W * 0.5, FIELD_TOP))
		line.add_point(Vector2(GRID_LEFT + c * CELL_W + CELL_W * 0.5, CASTLE_Y - 80))
		line.width = 2.0
		line.default_color = Color(1, 1, 1, 0.05)
		add_child(line)

	_build_castle()
	_build_grid()
	_build_bench()
	_build_phase_ui()

	spawn_queue = GameState.generate_wave(GameState.current_floor)

func _build_castle() -> void:
	castle_node = Node2D.new()
	castle_node.position = Vector2(FIELD_CENTER_X, CASTLE_Y)
	add_child(castle_node)

	var base := ColorRect.new()
	base.color = Color(0.55, 0.45, 0.35)
	base.size = Vector2(220, 130)
	base.position = Vector2(-110, -65)
	castle_node.add_child(base)

	var top := ColorRect.new()
	top.color = Color(0.40, 0.32, 0.25)
	top.size = Vector2(220, 22)
	top.position = Vector2(-110, -65)
	castle_node.add_child(top)

	# créneaux
	for i in range(5):
		var c := ColorRect.new()
		c.color = Color(0.40, 0.32, 0.25)
		c.size = Vector2(24, 14)
		c.position = Vector2(-110 + i * 50, -85)
		castle_node.add_child(c)

	var door := ColorRect.new()
	door.color = Color(0.20, 0.12, 0.08)
	door.size = Vector2(50, 60)
	door.position = Vector2(-25, 5)
	castle_node.add_child(door)

	castle_hp_bar_bg = ColorRect.new()
	castle_hp_bar_bg.color = Color(0.15, 0.05, 0.05)
	castle_hp_bar_bg.size = Vector2(220, 14)
	castle_hp_bar_bg.position = Vector2(-110, -100)
	castle_node.add_child(castle_hp_bar_bg)

	castle_hp_bar = ColorRect.new()
	castle_hp_bar.color = Color(0.30, 0.95, 0.30)
	castle_hp_bar.size = Vector2(220, 14)
	castle_hp_bar.position = Vector2(-110, -100)
	castle_node.add_child(castle_hp_bar)

func _build_grid() -> void:
	grid_buttons.clear()
	for row in range(GRID_ROWS):
		var row_arr: Array = []
		for col in range(GRID_COLS):
			var btn := Button.new()
			btn.flat = true
			btn.position = Vector2(GRID_LEFT + col * CELL_W + 4, GRID_TOP + row * CELL_H + 4)
			btn.size = Vector2(CELL_W - 8, CELL_H - 8)

			var sb := StyleBoxFlat.new()
			sb.bg_color = Color(0.25, 0.22, 0.18, 0.55)
			sb.border_color = Color(0.55, 0.50, 0.40, 0.7)
			sb.set_border_width_all(2)
			sb.set_corner_radius_all(6)
			btn.add_theme_stylebox_override("normal", sb)
			btn.add_theme_stylebox_override("hover", sb)
			btn.add_theme_stylebox_override("pressed", sb)

			var c := col
			var r := row
			btn.pressed.connect(func() -> void: _on_cell_pressed(c, r))
			add_child(btn)
			row_arr.append(btn)
		grid_buttons.append(row_arr)

func _build_bench() -> void:
	bench_container = Control.new()
	bench_container.position = Vector2(0, BENCH_Y)
	bench_container.size = Vector2(720, BENCH_H)
	add_child(bench_container)

	var bench_bg := ColorRect.new()
	bench_bg.color = Color(0.09, 0.07, 0.05)
	bench_bg.size = Vector2(720, BENCH_H)
	bench_container.add_child(bench_bg)

	var lbl := Label.new()
	lbl.text = "Banc — touche un héros puis une case (touche-la encore pour retirer)"
	lbl.position = Vector2(14, 4)
	lbl.add_theme_font_size_override("font_size", 14)
	lbl.add_theme_color_override("font_color", Color(0.85, 0.85, 0.85))
	bench_container.add_child(lbl)

	bench_buttons.clear()
	var party: Array = GameState.battle_party
	var n: int = party.size()
	if n == 0:
		return
	var slot_w: float = 700.0 / float(n)
	for i in range(n):
		var inst: Dictionary = party[i]
		var stats: Dictionary = GameState.get_hero_stats(inst)
		var btn := Button.new()
		btn.position = Vector2(10 + i * slot_w, 26)
		btn.size = Vector2(slot_w - 8, BENCH_H - 32)
		btn.text = "%s\nLv%d\nHP %d\nATK %d" % [stats["name"], stats["level"], stats["max_hp"], stats["attack"]]
		btn.add_theme_font_size_override("font_size", 13)
		btn.add_theme_color_override("font_color", Color(0.05, 0.05, 0.05))

		var sb := StyleBoxFlat.new()
		sb.bg_color = (stats["color"] as Color).darkened(0.1)
		sb.set_corner_radius_all(8)
		btn.add_theme_stylebox_override("normal", sb)
		btn.add_theme_stylebox_override("hover", sb)
		btn.add_theme_stylebox_override("pressed", sb)

		var idx := i
		btn.pressed.connect(func() -> void: _on_bench_pressed(idx))
		bench_container.add_child(btn)
		bench_buttons.append(btn)

func _build_phase_ui() -> void:
	phase_label = Label.new()
	phase_label.text = "Phase de placement"
	phase_label.position = Vector2(20, 64)
	phase_label.add_theme_font_size_override("font_size", 22)
	phase_label.add_theme_color_override("font_color", Color(1, 0.95, 0.7))
	add_child(phase_label)

	start_button = Button.new()
	start_button.text = "▶ COMMENCER"
	start_button.position = Vector2(720 - 230, 60)
	start_button.size = Vector2(210, 56)
	start_button.add_theme_font_size_override("font_size", 22)
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.20, 0.65, 0.28)
	sb.set_corner_radius_all(10)
	start_button.add_theme_stylebox_override("normal", sb)
	var sb_hover := sb.duplicate() as StyleBoxFlat
	sb_hover.bg_color = sb.bg_color.lightened(0.1)
	start_button.add_theme_stylebox_override("hover", sb_hover)
	start_button.add_theme_stylebox_override("pressed", sb_hover)
	var sb_dis := sb.duplicate() as StyleBoxFlat
	sb_dis.bg_color = Color(0.25, 0.25, 0.25)
	start_button.add_theme_stylebox_override("disabled", sb_dis)
	start_button.pressed.connect(_on_start_pressed)
	start_button.disabled = true
	add_child(start_button)

func _on_bench_pressed(idx: int) -> void:
	if phase != Phase.PLACEMENT:
		return
	selected_party_idx = idx
	_refresh_bench_visuals()

func _refresh_bench_visuals() -> void:
	for i in range(bench_buttons.size()):
		var btn: Button = bench_buttons[i]
		var sb: StyleBoxFlat = btn.get_theme_stylebox("normal") as StyleBoxFlat
		if not sb:
			continue
		var stats: Dictionary = GameState.get_hero_stats(GameState.battle_party[i])
		if placed_party_indices.has(i):
			sb.bg_color = Color(0.18, 0.18, 0.18)
		elif i == selected_party_idx:
			sb.bg_color = stats["color"]
			sb.border_color = Color(1, 1, 1)
			sb.set_border_width_all(3)
		else:
			sb.bg_color = (stats["color"] as Color).darkened(0.2)
			sb.set_border_width_all(0)

func _on_cell_pressed(col: int, row: int) -> void:
	if phase != Phase.PLACEMENT:
		return
	var key := "%d,%d" % [col, row]

	# Si la case est occupée : retire le héros
	if grid_occupants.has(key):
		var hero = grid_occupants[key]
		var party_idx: int = -1
		for k in placed_party_indices.keys():
			if placed_party_indices[k] == key:
				party_idx = k
				break
		if party_idx >= 0:
			placed_party_indices.erase(party_idx)
		grid_occupants.erase(key)
		if is_instance_valid(hero):
			hero.queue_free()
		_refresh_bench_visuals()
		_refresh_start_button()
		return

	if selected_party_idx < 0:
		return

	# Si déjà placé ailleurs, on déplace
	if placed_party_indices.has(selected_party_idx):
		var old_key: String = placed_party_indices[selected_party_idx]
		if grid_occupants.has(old_key):
			var old_hero = grid_occupants[old_key]
			if is_instance_valid(old_hero):
				old_hero.queue_free()
			grid_occupants.erase(old_key)

	var inst: Dictionary = GameState.battle_party[selected_party_idx]
	var stats: Dictionary = GameState.get_hero_stats(inst)
	var hero = load("res://scripts/Hero.gd").new()
	add_child(hero)
	hero.setup(stats, inst, self)
	hero.position = Vector2(GRID_LEFT + col * CELL_W + CELL_W * 0.5, GRID_TOP + row * CELL_H + CELL_H * 0.5)
	hero.col = col
	hero.row = row
	hero.died.connect(_on_hero_died)
	grid_occupants[key] = hero
	placed_party_indices[selected_party_idx] = key
	selected_party_idx = -1
	_refresh_bench_visuals()
	_refresh_start_button()

func _refresh_start_button() -> void:
	start_button.disabled = grid_occupants.is_empty()

func _on_start_pressed() -> void:
	if grid_occupants.is_empty():
		return
	phase = Phase.COMBAT
	phase_label.text = "Combat !"
	start_button.visible = false
	bench_container.visible = false
	for r in grid_buttons:
		for b in r:
			b.visible = false

	heroes_alive = grid_occupants.values()
	spawn_index = 0
	spawn_timer = 0.0

func _process(delta: float) -> void:
	if phase != Phase.COMBAT:
		return
	spawn_timer += delta

	while spawn_index < spawn_queue.size():
		var entry: Dictionary = spawn_queue[spawn_index]
		if spawn_timer >= float(entry["delay"]):
			_spawn_enemy(entry)
			spawn_index += 1
		else:
			break

	heroes_alive = heroes_alive.filter(func(h) -> bool: return is_instance_valid(h) and h.hp > 0)
	enemies_alive = enemies_alive.filter(func(e) -> bool: return is_instance_valid(e) and e.hp > 0)

	# Conditions de fin
	if castle_hp <= 0:
		_end_battle(false)
		return
	if heroes_alive.is_empty() and (spawn_index < spawn_queue.size() or not enemies_alive.is_empty()):
		_end_battle(false)
		return
	if spawn_index >= spawn_queue.size() and enemies_alive.is_empty():
		_end_battle(true)

func _spawn_enemy(entry: Dictionary) -> void:
	var stats: Dictionary = GameState.get_scaled_enemy(entry["id"], float(entry["diff"]))
	var enemy = load("res://scripts/Enemy.gd").new()
	add_child(enemy)
	var lane: int = randi() % GRID_COLS
	var lane_x: float = GRID_LEFT + lane * CELL_W + CELL_W * 0.5 + randf_range(-12, 12)
	enemy.setup(stats, self)
	enemy.position = Vector2(lane_x, SPAWN_Y)
	enemy.died.connect(_on_enemy_died)
	enemy.reached_castle.connect(_on_enemy_reached_castle)
	enemies_alive.append(enemy)

func _on_enemy_died(_enemy, gold: int, xp: int) -> void:
	GameState.gold += gold
	var n: int = max(1, placed_party_indices.size())
	var per_hero: int = max(1, int(round(float(xp) / float(n))))
	for idx in placed_party_indices.keys():
		var inst: Dictionary = GameState.battle_party[idx]
		GameState.grant_xp(inst, per_hero)

func _on_enemy_reached_castle(enemy) -> void:
	var dmg: int = int(enemy.stats["attack"]) * 4
	castle_hp = max(0, castle_hp - dmg)
	castle_hp_bar.size.x = 220.0 * (float(castle_hp) / float(castle_max_hp))
	enemies_alive.erase(enemy)

	# Flash château
	var tw := create_tween()
	tw.tween_property(castle_node, "modulate", Color(2, 0.5, 0.5), 0.06)
	tw.tween_property(castle_node, "modulate", Color(1, 1, 1), 0.20)

func _on_hero_died(hero) -> void:
	heroes_alive.erase(hero)

func _end_battle(victory: bool) -> void:
	if phase == Phase.RESULT:
		return
	phase = Phase.RESULT
	_show_result(victory)

func _show_result(victory: bool) -> void:
	if result_panel and is_instance_valid(result_panel):
		result_panel.queue_free()
	result_panel = Control.new()
	result_panel.size = Vector2(720, 1280)
	add_child(result_panel)

	var dim := ColorRect.new()
	dim.color = Color(0, 0, 0, 0.65)
	dim.size = Vector2(720, 1280)
	result_panel.add_child(dim)

	var panel := ColorRect.new()
	panel.color = Color(0.14, 0.14, 0.20)
	panel.size = Vector2(580, 480)
	panel.position = Vector2(70, 360)
	result_panel.add_child(panel)

	var lbl := Label.new()
	lbl.text = "VICTOIRE !" if victory else "DÉFAITE…"
	lbl.position = Vector2(0, 24)
	lbl.size = Vector2(580, 48)
	lbl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	lbl.add_theme_font_size_override("font_size", 38)
	lbl.add_theme_color_override("font_color", Color(0.3, 1.0, 0.4) if victory else Color(1.0, 0.4, 0.4))
	panel.add_child(lbl)

	var summary := ""
	for inst in GameState.battle_party:
		var s: Dictionary = GameState.get_hero_stats(inst)
		summary += "• %s — Lv %d  (%d/%d xp)\n" % [s["name"], s["level"], inst["exp"], inst["exp_to_next"]]
	var info := Label.new()
	info.text = "Étage %d\nOr total : %d\n\n%s" % [GameState.current_floor, GameState.gold, summary]
	info.position = Vector2(40, 100)
	info.size = Vector2(500, 280)
	info.add_theme_font_size_override("font_size", 18)
	info.add_theme_color_override("font_color", Color(0.95, 0.95, 0.95))
	panel.add_child(info)

	var continue_btn := Button.new()
	continue_btn.text = "Continuer"
	continue_btn.position = Vector2(190, 400)
	continue_btn.size = Vector2(200, 60)
	continue_btn.add_theme_font_size_override("font_size", 22)
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.30, 0.55, 0.85)
	sb.set_corner_radius_all(10)
	continue_btn.add_theme_stylebox_override("normal", sb)
	continue_btn.pressed.connect(func() -> void: emit_signal("battle_finished", victory))
	panel.add_child(continue_btn)
